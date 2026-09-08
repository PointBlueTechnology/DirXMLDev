package com.pointblue.dirxml.dev.validate;

import com.novell.nds.dirxml.engine.gcv.GCDefinitions;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.EngineContext;
import com.pointblue.dirxml.sim.MappingTableStore;
import com.pointblue.dirxml.sim.PolicyStage;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pointblue.dirxml.dev.model.PolicyLink;

/**
 * Compiles every policy with the engine's own compilers — {@code DirXMLScriptProcessor},
 * the XSLT stylesheet parser, the schema-mapping processor — exactly what the engine
 * runs at driver start. A policy that fails here aborts the driver in production
 * (Phase 0 spike 1b), so every failure is an {@code ERROR}, quoting the engine's
 * diagnostic (element not allowed, invalid XPath, invalid regex, bad attribute
 * value, missing {@code <app-name>}, XSLT parse error, …).
 *
 * <p>The compile runs in a context like the driver's own: its GCVs (the engine
 * expands {@code ~gcv~} inside XPath expressions at compile time, so an XPath that
 * reads {@code ~name~} only parses once the GCV is defined — and {@link GcvCheck}
 * reports the undefined name separately), and its mapping tables and includable
 * policies registered with the simulator's object store, since the engine resolves
 * {@code Map} tokens and {@code <include>}s at compile time. A missing table or
 * included policy then fails compile the way it would in the vault.
 *
 * <p>Also reports Java extension classes the policy references that aren't on the
 * classpath ({@code java-class-missing}, W — the vault's classpath may differ) and
 * named passwords the policy reads ({@code named-password}, I — deploy must set them).
 */
public final class CompileCheck implements Check {

    @Override
    public String name() {
        return "compile";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        Map<String, Artifact> index = ds.index();
        // A library policy is loaded by each driver that links it, in that driver's
        // context (its GCVs, its tables) — so compile it there, once per linking
        // driver, and attribute the finding to the library policy. Findings that
        // repeat verbatim across drivers are reported once.
        Map<String, Set<String>> linkedBy = new HashMap<>();
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links) {
                if (l.ref.startsWith("library/")) {
                    linkedBy.computeIfAbsent(l.ref, k -> new LinkedHashSet<>()).add(d.name);
                }
            }
        }
        Set<String> seen = new HashSet<>();
        MappingTableStore.clear();
        try {
            for (Driver d : ds.drivers) {
                MappingTableStore.clear();
                registerObjects(ds, d);
                String dn = Model.slashDn(ds, d);
                GCDefinitions gcv = gcvs(ds, d, index);
                for (Policy p : Model.policies(d)) {
                    compile(p, dn, gcv, r, null, seen);
                }
                for (Policy p : ds.library.policies) {
                    if (linkedBy.getOrDefault(p.path(), Set.of()).contains(d.name)) {
                        compile(p, dn, gcv, r, d.name, seen);
                    }
                }
            }
            // Library policies no driver links: compile in a driver-set-level
            // context with the union of every driver's GCVs.
            MappingTableStore.clear();
            registerObjects(ds, null);
            String dsDn = ds.dn != null ? Model.toSlash(ds.dn) : "\\[root]\\" + ds.name;
            GCDefinitions union = gcvs(ds, null, index);
            for (Driver d : ds.drivers) {
                union.merge(gcvs(ds, d, index));
            }
            for (Policy p : ds.library.policies) {
                if (!linkedBy.containsKey(p.path())) {
                    compile(p, dsDn + "\\Library", union, r, null, seen);
                }
            }
        } finally {
            MappingTableStore.clear();
        }
    }

    /** Mapping tables and includable policies in a driver's reach, by name (the store resolves by DN leaf). */
    private static void registerObjects(DriverSet ds, Driver d) {
        for (Resource t : Model.mappingTables(ds, d).values()) {
            if (t.content != null) {
                MappingTableStore.register(t.name, CanonicalXml.serialize(t.content));
            }
        }
        for (Policy p : ds.library.policies) {
            registerPolicy(p);
        }
        if (d != null) {
            for (Policy p : Model.policies(d)) {
                registerPolicy(p);
            }
        }
    }

    private static void registerPolicy(Policy p) {
        if (p.content != null && p.policyKind() == Policy.Kind.DIRXML_SCRIPT) {
            MappingTableStore.register(p.name, CanonicalXml.serialize(p.content));
        }
    }

    /** The GCVs the engine would have when it compiles this driver's (or the library's) policies. */
    private static GCDefinitions gcvs(DriverSet ds, Driver d, Map<String, Artifact> index) {
        GCDefinitions gcv = new GCDefinitions();
        // GCDefinitions.merge() keeps the FIRST definition of a name, so merge in
        // descending precedence: the driver's own values first, the driver set's last.
        if (d != null) {
            merge(gcv, d.config.get(Driver.CONFIG_VALUES));
            for (Resource r : Model.linkedResources(ds, d, PolicySet.GCV, index)) {
                merge(gcv, r.content);
            }
        }
        merge(gcv, ds.configValues);
        for (Resource r : Model.driverSetGcvResources(ds)) {
            merge(gcv, r.content);
        }
        return gcv;
    }

    private static void merge(GCDefinitions into, Element configurationValues) {
        if (configurationValues == null) {
            return;
        }
        try {
            // construct(Node) looks for a <configuration-values> child of the node.
            String doc = "<nds>" + Xds.serializeElement(Xds.parse(CanonicalXml.serialize(configurationValues))
                .getDocumentElement()) + "</nds>";
            into.merge(GCDefinitions.construct((org.w3c.dom.Node) Xds.parse(doc).getDocumentElement()));
        } catch (Throwable t) {
            // A malformed config-values block is reported by whoever owns it; here it
            // just means fewer GCVs are known at compile time.
        }
    }

    private static void compile(Policy p, String driverDn, GCDefinitions gcv, Report r,
                                String linkingDriver, Set<String> seen) {
        if (p.content == null) {
            add(r, seen, Finding.error("policy-empty", p.path(), "policy has no content"));
            return;
        }
        Policy.Kind kind = p.policyKind();
        if (kind == Policy.Kind.OTHER) {
            add(r, seen, Finding.error("policy-unknown-root", p.path(),
                "policy root element <" + p.content.getNodeName() + "> is not <policy>, an XSLT stylesheet, or <attr-name-map>"));
            return;
        }
        // Re-parse through the engine's own DOM (the compilers cast to it).
        Element el;
        try {
            el = Xds.parse(CanonicalXml.serialize(p.content)).getDocumentElement();
        } catch (RuntimeException e) {
            add(r, seen, Finding.error("policy-unparseable", p.path(), "engine parser rejected the policy: " + e.getMessage()));
            return;
        }
        String where = linkingDriver == null ? "" : " (as loaded by driver '" + linkingDriver + "')";
        EngineContext ctx = EngineContext.create(driverDn, "slash", true, gcv);
        // The XSLT parser reports the useful detail through the engine tracer (which
        // prints to stderr) and throws a generic -9014; capture stderr for it.
        PrintStream origErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PolicyStage stage;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            stage = PolicyStage.fromElement(p.name, el, ctx);
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            String msg = c.getMessage() != null ? c.getMessage().strip() : c.toString();
            String detail = traceMessages(captured.toString(StandardCharsets.UTF_8));
            add(r, seen, Finding.error("compile-error", p.path(), msg + where, detail.isBlank() ? null : detail));
            return;
        } finally {
            System.setErr(origErr);
        }
        List<String> missing = stage.missingJavaClasses();
        if (!missing.isEmpty()) {
            add(r, seen, Finding.warning("java-class-missing", p.path(),
                "references Java extension class(es) not on this classpath: " + String.join(", ", missing)));
        }
        List<String> pw = stage.referencedNamedPasswords();
        if (!pw.isEmpty()) {
            add(r, seen, Finding.info("named-password", p.path(),
                "reads named password(s): " + String.join(", ", pw)));
        }
    }

    private static void add(Report r, Set<String> seen, Finding f) {
        if (seen.add(f.code + "|" + f.path + "|" + f.message)) {
            r.add(f);
        }
    }

    /** The {@code Message:} lines of any DirXML Log Events the engine traced during compile. */
    private static String traceMessages(String stderr) {
        StringBuilder sb = new StringBuilder();
        for (String line : stderr.split("\n")) {
            String s = line.strip();
            if (s.startsWith("Message:")) {
                sb.append(s.substring("Message:".length()).strip()).append('\n');
            }
        }
        return sb.toString();
    }
}
