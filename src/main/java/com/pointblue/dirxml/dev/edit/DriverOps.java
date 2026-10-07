package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.source.ExportReader;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code driver.add} — a new driver in the tree (docs/designer-roundtrip.md §4),
 * created in the vault later by the deployer (stopped, start option manual):
 * <ul>
 *   <li>{@code --from-export <file>}: a driver export (single-driver, or a
 *       driver-set export with {@code --source-driver}) — its artifacts, links,
 *       config blobs and shim settings become driver {@code name}; Library
 *       artifacts the export carries are added to the tree's Library when absent
 *       (matched by name; an existing one is kept as is);</li>
 *   <li>{@code --copy-of D}: a clone of an existing driver, links re-pointed;</li>
 *   <li>{@code --shim-class C}: a blank driver with a starting filter (User and Group, the
 *       usual attributes synchronised both ways), shim settings with one example parameter per
 *       section, and an empty configuration-values document — every document the engine and
 *       Designer expect, as templates to edit rather than blanks to invent.</li>
 * </ul>
 * Package meta travels with the copied artifacts, so the deployer treats them as
 * packaged and the project writer will refuse a packaged driver.
 */
public final class DriverOps {

    private DriverOps() {
    }

    /** A starting filter for a blank driver: User and Group, the usual attributes, synchronised both ways. */
    static final String BLANK_FILTER = "<filter>"
        + "<filter-class class-name=\"User\" publisher=\"sync\" publisher-create-homedir=\"true\" publisher-track-template-member=\"false\" subscriber=\"sync\">"
        + "<filter-attr attr-name=\"CN\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Surname\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Given Name\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Full Name\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Internet EMail Address\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Telephone Number\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Login Disabled\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Group Membership\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "</filter-class>"
        + "<filter-class class-name=\"Group\" publisher=\"sync\" publisher-create-homedir=\"false\" publisher-track-template-member=\"false\" subscriber=\"sync\">"
        + "<filter-attr attr-name=\"CN\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Description\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "<filter-attr attr-name=\"Member\" merge-authority=\"default\" publisher=\"sync\" publisher-optimize-modify=\"true\" subscriber=\"sync\"/>"
        + "</filter-class>"
        + "</filter>";

    /** Shim settings for a blank driver: the three sections, each with one example parameter in the engine's definition form. */
    static final String BLANK_SHIM_CONFIG = "<driver-config name=\"%NAME%\">"
        + "<driver-options><configuration-values><definitions>"
        + "<definition display-name=\"Example driver option\" name=\"example-option\" type=\"string\">"
        + "<description>A driver parameter the shim reads from its init document (replace or remove).</description><value/></definition>"
        + "</definitions></configuration-values></driver-options>"
        + "<subscriber-options><configuration-values><definitions>"
        + "<definition display-name=\"Example subscriber option\" name=\"example-sub-option\" type=\"string\">"
        + "<description>A Subscriber channel parameter (replace or remove).</description><value/></definition>"
        + "</definitions></configuration-values></subscriber-options>"
        + "<publisher-options><configuration-values><definitions>"
        + "<definition display-name=\"Publisher heartbeat interval\" name=\"pub-heartbeat-interval\" type=\"integer\">"
        + "<description>Minutes of Publisher silence after which the shim sends a status document; 0 disables it.</description><value>1</value></definition>"
        + "</definitions></configuration-values></publisher-options>"
        + "</driver-config>";

    public static final class Add implements Operation {
        private final String name;
        private final Path export;
        private final String sourceDriver;   // within a driver-set export, or the driver to copy
        private final boolean copy;
        private final String shimClass;
        private final String authServer;
        private final String authId;

        private java.util.List<Path> packages = java.util.List.of();
        private java.util.Map<String, String> answers = java.util.Map.of();

        /** {@code --packages}: a base package (first) and its features; the driver is built from the base and the set installed. */
        public Add withPackages(java.util.List<Path> jars, java.util.Map<String, String> answers) {
            this.packages = jars == null ? java.util.List.of() : jars;
            this.answers = answers == null ? java.util.Map.of() : answers;
            return this;
        }

        public Add(String name, Path export, String sourceDriver, boolean copy, String shimClass, String authServer, String authId) {
            this.name = name;
            this.export = export;
            this.sourceDriver = sourceDriver;
            this.copy = copy;
            this.shimClass = shimClass;
            this.authServer = authServer;
            this.authId = authId;
        }

        @Override
        public String name() {
            return "driver.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
                throw new Refusal("a plain driver name is required");
            }
            if (ds.driver(name) != null) {
                throw new Refusal("driver '" + name + "' already exists");
            }
            Driver d;
            if (export != null) {
                DriverSet src;
                try {
                    src = ExportReader.read(export);
                } catch (RuntimeException e) {
                    throw new Refusal("cannot read export " + export + ": " + e.getMessage());
                }
                Driver from = pick(src, sourceDriver, "the export");
                d = clone(from, name, ds);
                // Library artifacts the export carries: add when absent, keep the tree's when present
                Map<String, Artifact> index = ds.index();
                for (Artifact a : src.library.artifacts()) {
                    if (!index.containsKey(a.path())) {
                        Artifact copyA = ArtifactOps.copyWithName(a, a.name);
                        ArtifactOps.place(ds, null, copyA);
                        tx.touch(copyA);
                    }
                }
            } else if (copy) {
                Driver from = pick(ds, sourceDriver, "the tree");
                d = clone(from, name, ds);
            } else {
                String shim = shimClass;
                if ((shim == null || shim.isBlank()) && !packages.isEmpty()) {
                    shim = com.pointblue.dirxml.dev.packages.PackageInstall.shimClassOf(packages.get(0));
                }
                if (shim == null || shim.isBlank()) {
                    throw new Refusal("give --from-export <file>, --copy-of <driver>, --shim-class <class>, or --packages <base jar,…>");
                }
                d = new Driver(name);
                d.shimClass = shim;
                d.shimAuthServer = authServer;
                d.shimAuthId = authId;
                d.config.put(Driver.DRIVER_FILTER, CanonicalXml.parse(BLANK_FILTER).getDocumentElement());
                d.config.put(Driver.SHIM_CONFIG_INFO, CanonicalXml.parse(
                    BLANK_SHIM_CONFIG.replace("%NAME%", name.replace("&", "&amp;").replace("\"", "&quot;"))).getDocumentElement());
                d.config.put(Driver.CONFIG_VALUES, CanonicalXml.parse("<configuration-values><definitions/></configuration-values>").getDocumentElement());
            }
            if (ds.dn != null && !ds.dn.isBlank()) {
                d.dn = "cn=" + name + "," + ds.dn;
            }
            ds.drivers.add(d);
            for (Artifact a : d.artifacts()) {
                tx.touch(a);
            }
            if (!packages.isEmpty()) {
                new com.pointblue.dirxml.dev.packages.PackageInstall(packages, name, answers, false).apply(ds, tx);
            }
        }

        private static Driver pick(DriverSet src, String wanted, String where) throws Refusal {
            if (wanted != null && !wanted.isBlank()) {
                Driver d = src.driver(wanted);
                if (d == null) {
                    List<String> names = new ArrayList<>();
                    for (Driver x : src.drivers) {
                        names.add(x.name);
                    }
                    throw new Refusal("no driver '" + wanted + "' in " + where + "; drivers: " + names);
                }
                return d;
            }
            if (src.drivers.size() == 1) {
                return src.drivers.get(0);
            }
            List<String> names = new ArrayList<>();
            for (Driver x : src.drivers) {
                names.add(x.name);
            }
            throw new Refusal(where + " has " + src.drivers.size() + " drivers; say which with --source-driver: " + names);
        }

        /** A deep-enough copy: new artifact objects for the new driver, links re-pointed, config re-parsed. */
        static Driver clone(Driver from, String name, DriverSet ds) {
            Driver d = new Driver(name);
            d.shimClass = from.shimClass;
            d.shimAuthServer = from.shimAuthServer;
            d.shimAuthId = from.shimAuthId;
            for (Map.Entry<String, Element> c : from.config.entrySet()) {
                if (c.getValue() != null) {
                    d.config.put(c.getKey(), CanonicalXml.parse(CanonicalXml.serialize(c.getValue())).getDocumentElement());
                }
            }
            d.meta.putAll(from.meta);
            for (Policy p : from.policies) {
                d.policies.add(copyPolicy(p, Scope.DRIVER, name));
            }
            for (Policy p : from.subscriber.policies) {
                d.subscriber.policies.add(copyPolicy(p, Scope.SUBSCRIBER, name));
            }
            for (Policy p : from.publisher.policies) {
                d.publisher.policies.add(copyPolicy(p, Scope.PUBLISHER, name));
            }
            for (Resource r : from.resources) {
                Resource n = new Resource(r.name, r.scope, name, r.contentType);
                n.content = r.content == null ? null : CanonicalXml.parse(CanonicalXml.serialize(r.content)).getDocumentElement();
                n.text = r.text;
                n.meta.putAll(r.meta);
                d.resources.add(n);
            }
            String oldPrefix = "drivers/" + from.name + "/";
            for (PolicyLink l : from.links) {
                String ref = l.ref.startsWith(oldPrefix) ? "drivers/" + name + "/" + l.ref.substring(oldPrefix.length()) : l.ref;
                d.links.add(new PolicyLink(l.set, ref, l.order));
            }
            return d;
        }

        private static Policy copyPolicy(Policy p, Scope scope, String driver) {
            Policy n = new Policy(p.name, scope, driver,
                p.content == null ? null : CanonicalXml.parse(CanonicalXml.serialize(p.content)).getDocumentElement());
            n.meta.putAll(p.meta);
            return n;
        }
    }
}
