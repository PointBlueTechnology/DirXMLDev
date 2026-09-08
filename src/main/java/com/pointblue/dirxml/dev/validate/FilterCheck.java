package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The driver filter ({@code DirXML-DriverFilter}) and the schema map (the driver's
 * {@code <attr-name-map>}, typically linked in policy set 0).
 *
 * <p>A driver with no filter is skipped entirely — {@link LinkCheck} already
 * reports {@code driver-no-filter}. Filter findings are attributed to the driver
 * ({@code drivers/<name>}); schema-map findings to the schema-map policy's path.
 *
 * <p>Codes: {@code filter-malformed} (E), {@code filter-invalid-value} (E),
 * {@code filter-duplicate-class} / {@code filter-duplicate-attr} (E),
 * {@code filter-dead-attr} (I), {@code schema-map-malformed} (E),
 * {@code schema-map-duplicate} (E), {@code schema-map-unfiltered-class} (I).
 */
public final class FilterCheck implements Check {

    private static final Set<String> SYNC_VALUES = set("sync", "ignore", "notify", "reset");
    private static final Set<String> MERGE_AUTHORITY_VALUES = set("default", "edir", "app", "none");
    private static final Set<String> BOOL_VALUES = set("true", "false");

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    /** One {@code <nds-name>}/{@code <app-name>} pair from the schema map. */
    private record Entry(String nds, String app) {
    }

    @Override
    public String name() {
        return "filter";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        Map<String, Artifact> index = ds.index();
        Set<String> schemaMapChecked = new HashSet<>();
        Set<String> seenUnfiltered = new HashSet<>();
        for (Driver d : ds.drivers) {
            Element filter = d.config.get(Driver.DRIVER_FILTER);
            if (filter == null) {
                continue;   // no filter: LinkCheck reports driver-no-filter, nothing more to check here
            }
            String dpath = "drivers/" + d.name;
            Set<String> filterClasses = checkFilter(filter, dpath, r);

            for (PolicyLink l : d.links(PolicySet.SCHEMA_MAPPING)) {
                Artifact a = index.get(l.ref);
                if (!(a instanceof Policy)) {
                    continue;   // wrong kind: LinkCheck reports link-kind
                }
                Policy p = (Policy) a;
                if (p.policyKind() != Policy.Kind.SCHEMA_MAP) {
                    continue;
                }
                if (schemaMapChecked.add(p.path())) {
                    checkSchemaMapStructure(p, r);
                }
                checkUnfilteredClasses(p, filterClasses, r, seenUnfiltered);
            }
        }
    }

    // ---- filter ---------------------------------------------------------------

    /** Validates the filter and returns the {@code class-name} of every filter-class it declares. */
    private static Set<String> checkFilter(Element filter, String dpath, Report r) {
        String ln = localName(filter);
        if (!"filter".equals(ln)) {
            r.add(Finding.error("filter-malformed", dpath, "filter root element is <" + ln + ">, not <filter>"));
        }
        Set<String> classNames = new LinkedHashSet<>();
        Map<String, Integer> classSeen = new LinkedHashMap<>();
        for (Element cls : Xds.childrenByName(filter, "filter-class")) {
            String className = cls.hasAttribute("class-name") ? cls.getAttribute("class-name").trim() : "";
            String classDesc = className.isEmpty() ? "a <filter-class>" : "filter-class '" + className + "'";
            if (className.isEmpty()) {
                r.add(Finding.error("filter-malformed", dpath, "<filter-class> has no class-name"));
            } else if (classSeen.merge(className, 1, Integer::sum) > 1) {
                r.add(Finding.error("filter-duplicate-class", dpath,
                    "class '" + className + "' appears more than once in the filter"));
            } else {
                classNames.add(className);
            }

            checkEnum(cls, "publisher", SYNC_VALUES, dpath, r, classDesc);
            checkEnum(cls, "subscriber", SYNC_VALUES, dpath, r, classDesc);
            checkEnum(cls, "publisher-create-homedir", BOOL_VALUES, dpath, r, classDesc);
            checkEnum(cls, "publisher-track-template-member", BOOL_VALUES, dpath, r, classDesc);

            boolean classIgnoredBothChannels = "ignore".equals(cls.getAttribute("publisher"))
                && "ignore".equals(cls.getAttribute("subscriber"));

            Map<String, Integer> attrSeen = new LinkedHashMap<>();
            for (Element at : Xds.childrenByName(cls, "filter-attr")) {
                String attrName = at.hasAttribute("attr-name") ? at.getAttribute("attr-name").trim() : "";
                if (attrName.isEmpty()) {
                    r.add(Finding.error("filter-malformed", dpath,
                        "<filter-attr> has no attr-name (in " + classDesc + ")"));
                } else if (attrSeen.merge(attrName, 1, Integer::sum) > 1) {
                    r.add(Finding.error("filter-duplicate-attr", dpath,
                        "attribute '" + attrName + "' appears more than once in class '" + className + "'"));
                }
                String attrDesc = attrName.isEmpty()
                    ? "a <filter-attr> in " + classDesc
                    : "filter-attr '" + attrName + "' in " + classDesc;

                checkEnum(at, "publisher", SYNC_VALUES, dpath, r, attrDesc);
                checkEnum(at, "subscriber", SYNC_VALUES, dpath, r, attrDesc);
                checkEnum(at, "merge-authority", MERGE_AUTHORITY_VALUES, dpath, r, attrDesc);
                checkEnum(at, "publisher-optimize-modify", BOOL_VALUES, dpath, r, attrDesc);
                checkEnum(at, "subscriber-optimize-modify", BOOL_VALUES, dpath, r, attrDesc);

                if (classIgnoredBothChannels && !attrName.isEmpty()) {
                    String pub = at.getAttribute("publisher");
                    String sub = at.getAttribute("subscriber");
                    if (isSyncOrNotify(pub) || isSyncOrNotify(sub)) {
                        r.add(Finding.info("filter-dead-attr", dpath,
                            "attribute '" + attrName + "' in class '" + className
                                + "' is set to sync/notify, but the class is ignored on both channels"));
                    }
                }
            }
        }
        return classNames;
    }

    private static boolean isSyncOrNotify(String v) {
        return "sync".equals(v) || "notify".equals(v);
    }

    private static void checkEnum(Element el, String attr, Set<String> allowed, String dpath, Report r, String desc) {
        if (!el.hasAttribute(attr)) {
            return;
        }
        String v = el.getAttribute(attr);
        if (!allowed.contains(v)) {
            r.add(Finding.error("filter-invalid-value", dpath,
                desc + " has " + attr + "=\"" + v + "\"; must be one of: " + String.join(" ", allowed)));
        }
    }

    private static String localName(Element el) {
        String ln = el.getLocalName();
        return ln != null ? ln : el.getNodeName();
    }

    // ---- schema map -------------------------------------------------------------

    private static void checkSchemaMapStructure(Policy p, Report r) {
        Element root = p.content;
        if (root == null) {
            return;   // reported by CompileCheck (policy-empty)
        }
        List<Element> classes = Xds.childrenByName(root, "class-name");
        List<Entry> classEntries = new ArrayList<>();
        for (Element c : classes) {
            Entry e = checkEntry(c, "<class-name>", p.path(), r);
            if (e != null) {
                classEntries.add(e);
            }
        }
        checkDuplicates(classEntries, p.path(), r, "class-name");

        List<Entry> topEntries = new ArrayList<>();
        for (Element a : Xds.childrenByName(root, "attr-name")) {
            Entry e = checkEntry(a, "<attr-name> (top level)", p.path(), r);
            if (e != null) {
                topEntries.add(e);
            }
        }
        checkDuplicates(topEntries, p.path(), r, "top-level attr-name");

        for (Element c : classes) {
            String className = childText(c, "nds-name");
            String label = className == null ? "?" : className;
            List<Entry> attrEntries = new ArrayList<>();
            for (Element a : Xds.childrenByName(c, "attr-name")) {
                Entry e = checkEntry(a, "<attr-name> in class '" + label + "'", p.path(), r);
                if (e != null) {
                    attrEntries.add(e);
                }
            }
            checkDuplicates(attrEntries, p.path(), r, "attr-name in class '" + label + "'");
        }
    }

    private static void checkUnfilteredClasses(Policy p, Set<String> filterClasses, Report r, Set<String> seen) {
        Element root = p.content;
        if (root == null) {
            return;
        }
        for (Element c : Xds.childrenByName(root, "class-name")) {
            String nds = childText(c, "nds-name");
            if (nds == null || filterClasses.contains(nds)) {
                continue;   // malformed already reported, or it is a filter-class
            }
            Finding f = Finding.info("schema-map-unfiltered-class", p.path(),
                "schema map class '" + nds + "' is not a filter-class of the driver");
            if (seen.add(f.code + "|" + f.path + "|" + f.message)) {
                r.add(f);
            }
        }
    }

    /** Validates one {@code <class-name>}/{@code <attr-name>} entry; returns null (and reports) if malformed. */
    private static Entry checkEntry(Element el, String label, String path, Report r) {
        String nds = childText(el, "nds-name");
        String app = childText(el, "app-name");
        if (nds == null || app == null) {
            List<String> missing = new ArrayList<>();
            if (nds == null) {
                missing.add("<nds-name>");
            }
            if (app == null) {
                missing.add("<app-name>");
            }
            r.add(Finding.error("schema-map-malformed", path, label + " is missing " + String.join(" and ", missing)));
            return null;
        }
        return new Entry(nds, app);
    }

    private static void checkDuplicates(List<Entry> entries, String path, Report r, String levelDesc) {
        Map<String, Integer> ndsCount = new LinkedHashMap<>();
        Map<String, Set<String>> appToNds = new LinkedHashMap<>();
        for (Entry e : entries) {
            ndsCount.merge(e.nds(), 1, Integer::sum);
            appToNds.computeIfAbsent(e.app(), k -> new LinkedHashSet<>()).add(e.nds());
        }
        for (Map.Entry<String, Integer> en : ndsCount.entrySet()) {
            if (en.getValue() > 1) {
                r.add(Finding.error("schema-map-duplicate", path,
                    "nds-name '" + en.getKey() + "' is mapped twice among the " + levelDesc + " entries"));
            }
        }
        for (Map.Entry<String, Set<String>> en : appToNds.entrySet()) {
            if (en.getValue().size() > 1) {
                r.add(Finding.error("schema-map-duplicate", path,
                    "app-name '" + en.getKey() + "' is mapped to two different nds-name(s) among the " + levelDesc
                        + " entries: " + String.join(", ", en.getValue())));
            }
        }
    }

    private static String childText(Element parent, String childName) {
        List<Element> kids = Xds.childrenByName(parent, childName);
        if (kids.isEmpty()) {
            return null;
        }
        String t = Xds.text(kids.get(0));
        if (t == null) {
            return null;
        }
        t = t.trim();
        return t.isEmpty() ? null : t;
    }
}
