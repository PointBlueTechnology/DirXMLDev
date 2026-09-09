package com.pointblue.dirxml.dev.docs;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.ModelDiff;
import com.pointblue.dirxml.dev.deploy.SecretInventory;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders documentation straight from the model — {@code docs/designer-roundtrip.md},
 * "1. Documentation from the model": {@code README.md} (the driver set), one page
 * per driver, {@code library.md} (shared policies/tables/GCVs/ECMAScript), and
 * {@code changes.md} when {@code --since} is given.
 *
 * <p>Renders are deterministic — the same tree produces byte-identical files —
 * so the output can live in the client repo and diff cleanly. Nothing here reads
 * the clock; the only thing resembling a timestamp is the commit id named in
 * {@code changes.md}.
 */
public final class DocsGenerator {

    /** The engine's stage order per channel — copied from {@code edit.ReadCli} (package-private there). */
    private static final List<PolicySet> SUB_CHAIN = Arrays.asList(PolicySet.SUB_EVENT, PolicySet.SUB_MATCH,
        PolicySet.SUB_CREATE, PolicySet.SUB_PLACEMENT, PolicySet.SUB_COMMAND, PolicySet.SCHEMA_MAPPING, PolicySet.OUTPUT);
    private static final List<PolicySet> PUB_CHAIN = Arrays.asList(PolicySet.INPUT, PolicySet.SCHEMA_MAPPING,
        PolicySet.PUB_EVENT, PolicySet.PUB_MATCH, PolicySet.PUB_CREATE, PolicySet.PUB_PLACEMENT, PolicySet.PUB_COMMAND);

    private static final Pattern ES_CALL = Pattern.compile("es:([A-Za-z_][A-Za-z0-9_]*)\\(");
    private static final Pattern JS_FUNCTION = Pattern.compile("function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final int TABLE_ROW_CAP = 50;

    private DocsGenerator() {
    }

    /**
     * Reads the as-code tree at {@code treeDir} and writes {@code README.md},
     * {@code drivers/<name>.md} (only for {@code driverFilter}, or all drivers
     * when empty/null), {@code library.md}, and — when {@code sinceCommit} is
     * non-null — {@code changes.md}, under {@code outDir}. {@code format}
     * {@code "html"} additionally writes a self-contained {@code index.html}.
     */
    public static void generate(Path treeDir, Path outDir, List<String> driverFilter, String sinceCommit, String format)
            throws IOException {
        DriverSet ds = AsCodeReader.read(treeDir);
        Files.createDirectories(outDir);

        List<Driver> toRender = new ArrayList<>();
        for (Driver d : ds.drivers) {
            if (driverFilter == null || driverFilter.isEmpty() || driverFilter.contains(d.name)) {
                toRender.add(d);
            }
        }

        writeFile(outDir.resolve("README.md"), renderReadme(ds));
        Path driversDir = outDir.resolve("drivers");
        Files.createDirectories(driversDir);
        for (Driver d : toRender) {
            writeFile(driversDir.resolve(AsCodeWriter.fileSafe(d.name) + ".md"), renderDriver(ds, d));
        }
        writeFile(outDir.resolve("library.md"), renderLibrary(ds));
        boolean hasChanges = sinceCommit != null;
        if (hasChanges) {
            writeFile(outDir.resolve("changes.md"), renderChanges(treeDir, ds, sinceCommit));
        }
        if ("html".equalsIgnoreCase(format)) {
            writeHtml(outDir, ds, toRender, hasChanges);
        }
    }

    private static void writeFile(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // README.md
    // ------------------------------------------------------------------

    private static String renderReadme(DriverSet ds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ds.name).append("\n\n");

        sb.append("## Drivers\n\n");
        List<String> headers = List.of("Name", "Shim class", "Auth server", "Auth id",
            "Driver policies", "Subscriber policies", "Publisher policies", "Packaged", "Customized");
        List<List<String>> rows = new ArrayList<>();
        for (Driver d : ds.drivers) {
            int packaged = 0;
            int customized = 0;
            for (Artifact a : d.artifacts()) {
                if (Packages.isPackaged(a)) {
                    packaged++;
                }
                if (Packages.isCustomized(a)) {
                    customized++;
                }
            }
            rows.add(List.of(
                "[" + d.name + "](drivers/" + AsCodeWriter.fileSafe(d.name) + ".md)",
                nvl(d.shimClass), nvl(d.shimAuthServer), nvl(d.shimAuthId),
                String.valueOf(d.policies.size()), String.valueOf(d.subscriber.policies.size()),
                String.valueOf(d.publisher.policies.size()), String.valueOf(packaged), String.valueOf(customized)));
        }
        sb.append(table(headers, rows)).append('\n');

        sb.append("## Library\n\n");
        sb.append("### Policies\n\n").append(libraryPolicyLinks(ds.library.policies));
        sb.append("### Mapping tables\n\n").append(libraryResourceLinks(ds.library.resources, Resource::isMappingTable));
        sb.append("### GCV objects\n\n").append(libraryResourceLinks(ds.library.resources, Resource::isGcvDef));
        sb.append("### ECMAScript\n\n").append(libraryResourceLinks(ds.library.resources, Resource::isEcmaScript));

        sb.append("## Driver-set GCVs\n\n").append(gcvTable(ds.configValues));

        sb.append("## Packages\n\n").append(renderPackagesSeen(ds));

        sb.append("## Customized packaged objects\n\n").append(renderCustomized(ds));

        return sb.toString();
    }

    private static String libraryPolicyLinks(List<Policy> list) {
        if (list.isEmpty()) {
            return "(none)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Policy p : list) {
            sb.append("- [").append(p.name).append("](library.md#").append(MarkdownToHtml.slug(p.name)).append(")\n");
        }
        return sb.append('\n').toString();
    }

    private static String libraryResourceLinks(List<Resource> list, Predicate<Resource> filter) {
        List<Resource> f = new ArrayList<>();
        for (Resource r : list) {
            if (filter.test(r)) {
                f.add(r);
            }
        }
        if (f.isEmpty()) {
            return "(none)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Resource r : f) {
            sb.append("- [").append(r.name).append("](library.md#").append(MarkdownToHtml.slug(r.name)).append(")\n");
        }
        return sb.append('\n').toString();
    }

    private static String renderPackagesSeen(DriverSet ds) {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> versions = new TreeMap<>();
        for (Artifact a : ds.index().values()) {
            countPackage(a.meta, counts, versions);
        }
        for (Driver d : ds.drivers) {
            countPackage(d.meta, counts, versions);
        }
        if (counts.isEmpty()) {
            return "(none)\n\n";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            rows.add(List.of(e.getKey(), nvl(versions.get(e.getKey())), String.valueOf(e.getValue())));
        }
        return table(List.of("Package", "Version", "Objects"), rows) + "\n";
    }

    private static void countPackage(Map<String, String> meta, Map<String, Integer> counts, Map<String, String> versions) {
        String id = packageId(meta);
        if (id == null) {
            return;
        }
        counts.merge(id, 1, Integer::sum);
        String v = packageVersion(meta);
        if (v != null) {
            versions.putIfAbsent(id, v);
        }
    }

    private static String packageId(Map<String, String> meta) {
        if (meta.containsKey("package-id")) {
            return meta.get("package-id");
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            String k = e.getKey().toLowerCase();
            if (k.startsWith("dirxml-pkg") && k.contains("assoc")) {
                return e.getValue();
            }
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (e.getKey().toLowerCase().startsWith("dirxml-pkg")) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String packageVersion(Map<String, String> meta) {
        if (meta.containsKey("package-version")) {
            return meta.get("package-version");
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (e.getKey().toLowerCase().contains("version")) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String renderCustomized(DriverSet ds) {
        List<Artifact> customized = new ArrayList<>();
        for (Artifact a : ds.index().values()) {
            if (Packages.isCustomized(a)) {
                customized.add(a);
            }
        }
        if (customized.isEmpty()) {
            return "(none)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Artifact a : customized) {
            sb.append("- `").append(a.path()).append("`\n");
        }
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------
    // drivers/<name>.md
    // ------------------------------------------------------------------

    private static String renderDriver(DriverSet ds, Driver d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(d.name).append("\n\n");

        sb.append("## Connection\n\n");
        sb.append("- Shim class: ").append(nvl(d.shimClass)).append('\n');
        sb.append("- Auth server: ").append(nvl(d.shimAuthServer)).append('\n');
        sb.append("- Auth id: ").append(nvl(d.shimAuthId)).append("\n\n");

        sb.append("## Filter\n\n").append(renderFilter(d.config.get(Driver.DRIVER_FILTER)));
        sb.append("## Schema map\n\n").append(renderSchemaMaps(ds, d));
        sb.append("## GCVs in scope\n\n").append(renderDriverGcvs(ds, d));
        sb.append("## Subscriber chain\n\n").append(renderChain(ds, d, true));
        sb.append("## Publisher chain\n\n").append(renderChain(ds, d, false));
        sb.append("## Library policies linked\n\n").append(renderLibraryLinks(ds, d));
        sb.append("## Mapping tables used\n\n").append(renderTablesUsed(ds, d));
        sb.append("## Named passwords\n\n").append(renderNamedPasswords(ds, d));
        sb.append("## ECMAScript functions called\n\n").append(renderEsCalls(ds, d));
        sb.append("## Driver parameters\n\n").append(renderDefinitions(d.config.get(Driver.SHIM_CONFIG_INFO)));
        sb.append("## Engine control values\n\n").append(renderDefinitions(d.config.get(Driver.ENGINE_CONTROL_VALUES)));

        return sb.toString();
    }

    private static String renderFilter(Element filter) {
        if (filter == null) {
            return "(no filter)\n\n";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Element fc : Xds.childrenByName(filter, "filter-class")) {
            String cls = fc.getAttribute("class-name");
            rows.add(List.of("**" + cls + "**", "", fc.getAttribute("subscriber"), fc.getAttribute("publisher"),
                fc.getAttribute("merge-authority")));
            for (Element fa : Xds.childrenByName(fc, "filter-attr")) {
                rows.add(List.of(cls, fa.getAttribute("attr-name"), fa.getAttribute("subscriber"),
                    fa.getAttribute("publisher"), fa.getAttribute("merge-authority")));
            }
        }
        if (rows.isEmpty()) {
            return "(empty)\n\n";
        }
        return table(List.of("Class", "Attribute", "Subscriber", "Publisher", "Merge authority"), rows) + "\n";
    }

    private static String renderSchemaMaps(DriverSet ds, Driver d) {
        List<PolicyLink> links = d.links(PolicySet.SCHEMA_MAPPING);
        if (links.isEmpty()) {
            return "(none)\n\n";
        }
        Map<String, Artifact> index = ds.index();
        StringBuilder sb = new StringBuilder();
        for (PolicyLink l : links) {
            Artifact a = index.get(l.ref);
            if (!(a instanceof Policy)) {
                continue;
            }
            Policy p = (Policy) a;
            sb.append("### ").append(p.name).append("\n\n");
            if (p.policyKind() == Policy.Kind.SCHEMA_MAP) {
                sb.append(renderAttrNameMap(p.content));
            } else {
                sb.append('(').append(kindLabel(p)).append(" — see chain)\n\n");
            }
        }
        return sb.length() == 0 ? "(none)\n\n" : sb.toString();
    }

    private static String renderAttrNameMap(Element map) {
        if (map == null) {
            return "(empty)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        List<Element> topAttrs = Xds.childrenByName(map, "attr-name");
        if (!topAttrs.isEmpty()) {
            sb.append("**Top-level attribute mappings**\n\n").append(mapEntryTable(topAttrs));
        }
        for (Element cn : Xds.childrenByName(map, "class-name")) {
            String app = firstText(cn, "app-name");
            String nds = firstText(cn, "nds-name");
            sb.append("**Class:** ").append(nvl(app)).append(" \u2192 ").append(nvl(nds)).append("\n\n");
            List<Element> attrs = Xds.childrenByName(cn, "attr-name");
            if (!attrs.isEmpty()) {
                sb.append(mapEntryTable(attrs));
            }
        }
        return sb.length() == 0 ? "(empty)\n\n" : sb.toString();
    }

    private static String mapEntryTable(List<Element> attrNameEls) {
        List<List<String>> rows = new ArrayList<>();
        for (Element a : attrNameEls) {
            rows.add(List.of(nvl(firstText(a, "app-name")), nvl(firstText(a, "nds-name"))));
        }
        return table(List.of("App name", "NDS name"), rows) + "\n";
    }

    private static String renderDriverGcvs(DriverSet ds, Driver d) {
        Map<String, String[]> seen = new LinkedHashMap<>();
        Map<String, Artifact> index = ds.index();
        collectGcvs(seen, d.config.get(Driver.CONFIG_VALUES), "drivers/" + d.name + " (config-values)");
        for (PolicyLink l : d.links(PolicySet.GCV)) {
            Artifact a = index.get(l.ref);
            if (a instanceof Resource) {
                collectGcvs(seen, ((Resource) a).content, a.path());
            }
        }
        collectGcvs(seen, ds.configValues, "driverset (config-values)");
        for (Map.Entry<String, String> m : ds.meta.entrySet()) {
            if (m.getKey().startsWith("driverset.linkage.")) {
                String leaf = leafOfLinkage(m.getValue());
                for (Resource r : ds.library.resources) {
                    if (r.isGcvDef() && r.name.equals(leaf)) {
                        collectGcvs(seen, r.content, r.path());
                    }
                }
            }
        }
        if (seen.isEmpty()) {
            return "(none)\n\n";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, String[]> e : seen.entrySet()) {
            rows.add(List.of(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return table(List.of("Name", "Value", "Where defined"), rows) + "\n";
    }

    private static void collectGcvs(Map<String, String[]> seen, Element cv, String where) {
        if (cv == null) {
            return;
        }
        for (Element def : Xds.descendantsByName(cv, "definition")) {
            String name = def.getAttribute("name");
            if (name.isBlank() || seen.containsKey(name)) {
                continue;
            }
            List<Element> v = Xds.childrenByName(def, "value");
            String value = v.isEmpty() ? "" : Xds.text(v.get(0));
            if ("password-ref".equals(def.getAttribute("type"))) {
                value = "(password)";
            }
            seen.put(name, new String[] {value, where});
        }
    }

    private static String renderChain(DriverSet ds, Driver d, boolean sub) {
        Map<String, Artifact> index = ds.index();
        List<PolicySet> chain = sub ? SUB_CHAIN : PUB_CHAIN;
        StringBuilder sb = new StringBuilder();
        for (PolicySet set : chain) {
            List<PolicyLink> links = d.links(set);
            if (links.isEmpty()) {
                continue;
            }
            sb.append("### ").append(set.key).append("\n\n");
            for (PolicyLink l : links) {
                sb.append(renderChainEntry(index.get(l.ref), l));
            }
        }
        return sb.length() == 0 ? "(empty)\n\n" : sb.toString();
    }

    private static String renderChainEntry(Artifact a, PolicyLink l) {
        if (a == null) {
            return "**UNRESOLVED** `" + l.ref + "`\n\n";
        }
        StringBuilder sb = new StringBuilder();
        if (a instanceof Policy) {
            Policy p = (Policy) a;
            sb.append("**").append(p.path()).append("** (").append(kindLabel(p)).append(")\n\n");
            switch (p.policyKind()) {
                case DIRXML_SCRIPT:
                    sb.append(renderRules(p.content));
                    break;
                case XSLT:
                    sb.append(Xds.descendantsByName(p.content, "template").size()).append(" template(s)\n\n");
                    break;
                case SCHEMA_MAP:
                    int entries = Xds.descendantsByName(p.content, "class-name").size()
                        + Xds.descendantsByName(p.content, "attr-name").size();
                    sb.append(entries).append(" entry/entries\n\n");
                    break;
                default:
                    break;
            }
        } else {
            sb.append("**").append(a.path()).append("** (resource)\n\n");
        }
        return sb.toString();
    }

    private static String renderRules(Element policy) {
        if (policy == null) {
            return "(no content)\n\n";
        }
        List<Element> rules = Xds.childrenByName(policy, "rule");
        if (rules.isEmpty()) {
            return "(no rules)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Element rule : rules) {
            String desc = firstText(rule, "description");
            if (desc == null || desc.isBlank()) {
                desc = "(no description)";
            }
            List<Element> condList = Xds.childrenByName(rule, "conditions");
            String condSummary = ConditionSummary.summarize(condList.isEmpty() ? null : condList.get(0));
            List<Element> actionsList = Xds.childrenByName(rule, "actions");
            String firstAction = "(no actions)";
            if (!actionsList.isEmpty()) {
                List<Element> acts = Xds.childElements(actionsList.get(0));
                if (!acts.isEmpty()) {
                    firstAction = localName(acts.get(0));
                }
            }
            boolean disabled = "true".equals(rule.getAttribute("disabled"));
            sb.append("- **").append(mdEscape(desc)).append("** \u2014 if ").append(condSummary)
                .append(" \u2192 ").append(firstAction);
            if (disabled) {
                sb.append(" (disabled)");
            }
            sb.append('\n');
        }
        return sb.append('\n').toString();
    }

    private static String renderLibraryLinks(DriverSet ds, Driver d) {
        Map<String, Artifact> index = ds.index();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (PolicyLink l : d.links) {
            Artifact a = index.get(l.ref);
            if (a != null && a.scope == Scope.LIBRARY) {
                paths.add(a.path());
            }
        }
        if (paths.isEmpty()) {
            return "(none)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            String name = p.substring("library/".length());
            sb.append("- [").append(name).append("](../library.md#").append(MarkdownToHtml.slug(name)).append(")\n");
        }
        return sb.append('\n').toString();
    }

    private static String renderTablesUsed(DriverSet ds, Driver d) {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        for (Policy p : reachablePolicies(ds, d)) {
            if (p.content == null) {
                continue;
            }
            for (Element tm : Xds.descendantsByName(p.content, "token-map")) {
                String t = tm.getAttribute("table");
                if (t != null && !t.isBlank() && !t.contains("$")) {
                    tableNames.add(leaf(t));
                }
            }
        }
        if (tableNames.isEmpty()) {
            return "(none)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : tableNames) {
            Resource table = findTable(ds, d, name);
            sb.append("### ").append(name).append("\n\n");
            sb.append(table == null ? "(not found in reach)\n\n" : renderMappingTable(table.content));
        }
        return sb.toString();
    }

    private static Resource findTable(DriverSet ds, Driver d, String name) {
        for (Resource r : ds.library.resources) {
            if (r.isMappingTable() && r.name.equals(name)) {
                return r;
            }
        }
        for (Resource r : d.resources) {
            if (r.isMappingTable() && r.name.equals(name)) {
                return r;
            }
        }
        return null;
    }

    private static String renderMappingTable(Element content) {
        if (content == null) {
            return "(empty)\n\n";
        }
        List<String> cols = new ArrayList<>();
        for (Element c : Xds.childrenByName(content, "col-def")) {
            cols.add(c.getAttribute("name"));
        }
        List<Element> rowsEl = Xds.childrenByName(content, "row");
        List<List<String>> rows = new ArrayList<>();
        int cap = Math.min(rowsEl.size(), TABLE_ROW_CAP);
        for (int i = 0; i < cap; i++) {
            List<String> vals = new ArrayList<>();
            for (Element c : Xds.childrenByName(rowsEl.get(i), "col")) {
                vals.add(Xds.text(c).trim());
            }
            rows.add(vals);
        }
        StringBuilder sb = new StringBuilder(table(cols, rows));
        if (rowsEl.size() > TABLE_ROW_CAP) {
            sb.append("\n_… ").append(rowsEl.size() - TABLE_ROW_CAP).append(" more row(s) not shown_\n");
        }
        return sb.append('\n').toString();
    }

    private static String renderNamedPasswords(DriverSet ds, Driver d) {
        List<SecretInventory.Need> needs = SecretInventory.forDriver(ds, d);
        List<SecretInventory.Need> named = new ArrayList<>();
        for (SecretInventory.Need n : needs) {
            if ("named".equals(n.kind)) {
                named.add(n);
            }
        }
        if (named.isEmpty()) {
            return "(none)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (SecretInventory.Need n : named) {
            sb.append("- `").append(n.key).append("` — ").append(n.because).append('\n');
        }
        return sb.append('\n').toString();
    }

    private static String renderEsCalls(DriverSet ds, Driver d) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Policy p : reachablePolicies(ds, d)) {
            if (p.content == null) {
                continue;
            }
            Matcher m = ES_CALL.matcher(CanonicalXml.serialize(p.content));
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        if (names.isEmpty()) {
            return "(none)\n\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            sb.append("- `es:").append(n).append("`\n");
        }
        return sb.append('\n').toString();
    }

    private static String renderDefinitions(Element root) {
        if (root == null) {
            return "(none)\n\n";
        }
        List<Element> defs = Xds.descendantsByName(root, "definition");
        if (defs.isEmpty()) {
            return "(none)\n\n";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Element def : defs) {
            String name = def.getAttribute("name");
            String display = def.getAttribute("display-name");
            String label = display.isBlank() ? name : display;
            List<Element> v = Xds.childrenByName(def, "value");
            String value = v.isEmpty() ? "" : Xds.text(v.get(0));
            if ("password-ref".equals(def.getAttribute("type"))) {
                value = "(password)";
            }
            rows.add(List.of(label, value));
        }
        return table(List.of("Parameter", "Value"), rows) + "\n";
    }

    /** Driver-scope + both channels + Library policies this driver's links reach. */
    private static List<Policy> reachablePolicies(DriverSet ds, Driver d) {
        List<Policy> out = new ArrayList<>(d.policies);
        out.addAll(d.subscriber.policies);
        out.addAll(d.publisher.policies);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Policy p : out) {
            seen.add(p.path());
        }
        Map<String, Artifact> index = ds.index();
        for (PolicyLink l : d.links) {
            Artifact a = index.get(l.ref);
            if (a instanceof Policy && seen.add(a.path())) {
                out.add((Policy) a);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // library.md
    // ------------------------------------------------------------------

    private static String renderLibrary(DriverSet ds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Library\n\n");

        sb.append("## Policies\n\n");
        if (ds.library.policies.isEmpty()) {
            sb.append("(none)\n\n");
        }
        for (Policy p : ds.library.policies) {
            sb.append("### ").append(p.name).append("\n\n");
            sb.append("Kind: ").append(kindLabel(p)).append("\n\n");
            switch (p.policyKind()) {
                case DIRXML_SCRIPT:
                    sb.append(renderRules(p.content));
                    break;
                case XSLT:
                    sb.append(Xds.descendantsByName(p.content, "template").size()).append(" template(s)\n\n");
                    break;
                case SCHEMA_MAP:
                    sb.append(renderAttrNameMap(p.content));
                    break;
                default:
                    break;
            }
        }

        sb.append("## Mapping tables\n\n");
        boolean anyTable = false;
        for (Resource r : ds.library.resources) {
            if (!r.isMappingTable()) {
                continue;
            }
            anyTable = true;
            sb.append("### ").append(r.name).append("\n\n").append(renderMappingTable(r.content));
        }
        if (!anyTable) {
            sb.append("(none)\n\n");
        }

        sb.append("## GCV objects\n\n");
        boolean anyGcv = false;
        for (Resource r : ds.library.resources) {
            if (!r.isGcvDef()) {
                continue;
            }
            anyGcv = true;
            sb.append("### ").append(r.name).append("\n\n").append(renderDefinitions(r.content));
        }
        if (!anyGcv) {
            sb.append("(none)\n\n");
        }

        sb.append("## ECMAScript\n\n");
        boolean anyEs = false;
        for (Resource r : ds.library.resources) {
            if (!r.isEcmaScript()) {
                continue;
            }
            anyEs = true;
            sb.append("### ").append(r.name).append("\n\n");
            LinkedHashSet<String> fns = new LinkedHashSet<>();
            if (r.text != null) {
                Matcher m = JS_FUNCTION.matcher(r.text);
                while (m.find()) {
                    fns.add(m.group(1));
                }
            }
            if (fns.isEmpty()) {
                sb.append("(no functions found)\n\n");
            } else {
                for (String f : fns) {
                    sb.append("- `").append(f).append("()`\n");
                }
                sb.append('\n');
            }
        }
        if (!anyEs) {
            sb.append("(none)\n\n");
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // changes.md
    // ------------------------------------------------------------------

    private static String renderChanges(Path tree, DriverSet now, String sinceCommit) throws IOException {
        Path checkout = checkoutCommit(tree, sinceCommit);
        if (checkout == null) {
            return "# Changes since " + sinceCommit + "\n\ncould not check out commit `" + sinceCommit
                + "` from " + tree + "\n";
        }
        try {
            DriverSet then = AsCodeReader.read(checkout);
            ModelDiff diff = ModelDiff.of(then, now);
            StringBuilder sb = new StringBuilder();
            sb.append("# Changes since ").append(sinceCommit).append("\n\n");
            if (diff.isEmpty()) {
                sb.append("no differences\n");
                return sb.toString();
            }
            List<String> affected = diff.affectedDrivers();
            sb.append(diff.changes().size()).append(" change(s), ").append(affected.size())
                .append(" driver(s) affected.\n\n");
            sb.append("## Affected drivers\n\n");
            if (affected.isEmpty()) {
                sb.append("(none)\n\n");
            } else {
                for (String d : affected) {
                    sb.append("- ").append(d).append('\n');
                }
                sb.append('\n');
            }
            sb.append("## Changes\n\n");
            for (ModelDiff.Change c : diff.changes()) {
                sb.append("- ").append(mdEscape(c.summary)).append('\n');
                if (c.detail != null && !c.detail.isBlank()) {
                    sb.append("\n```diff\n").append(c.detail).append("\n```\n\n");
                }
            }
            return sb.toString();
        } finally {
            deleteRecursively(checkout);
        }
    }

    /** A temp checkout of {@code tree} at {@code commit} — same recipe as {@code deploy.Deployer.checkout}. */
    private static Path checkoutCommit(Path tree, String commit) {
        try {
            Path dir = Files.createTempDirectory("idm-docs-since");
            Process p = new ProcessBuilder("/bin/sh", "-c",
                "git -C '" + tree.toAbsolutePath() + "' archive --format=tar " + commit + " | tar -x -C '" + dir + "'")
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // temp dir
        }
    }

    // ------------------------------------------------------------------
    // --format html
    // ------------------------------------------------------------------

    private static final String STYLE = "<style>"
        + "body{font-family:sans-serif;max-width:960px;margin:2rem auto;padding:0 1rem;line-height:1.5}"
        + "table{border-collapse:collapse;width:100%;margin:1rem 0}"
        + "th,td{border:1px solid #ccc;padding:.35rem .6rem;text-align:left}"
        + "code,pre{font-family:monospace}pre{background:#f5f5f5;padding:.75rem;overflow-x:auto}"
        + "nav ul{list-style:none;padding:0;display:flex;flex-wrap:wrap;gap:.75rem}"
        + "section{margin-bottom:2rem;padding-top:2rem;border-top:1px solid #eee}"
        + "</style>";

    private static void writeHtml(Path outDir, DriverSet ds, List<Driver> drivers, boolean hasChanges) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>")
            .append(MarkdownToHtml.escapeHtml(ds.name)).append(" \u2014 IDM documentation</title>")
            .append(STYLE).append("</head><body>\n");

        html.append("<nav><ul>\n<li><a href=\"#readme\">README</a></li>\n");
        for (Driver d : drivers) {
            html.append("<li><a href=\"#driver-").append(MarkdownToHtml.slug(d.name)).append("\">")
                .append(MarkdownToHtml.escapeHtml(d.name)).append("</a></li>\n");
        }
        html.append("<li><a href=\"#library\">Library</a></li>\n");
        if (hasChanges) {
            html.append("<li><a href=\"#changes\">Changes</a></li>\n");
        }
        html.append("</ul></nav>\n");

        html.append("<section id=\"readme\">\n")
            .append(MarkdownToHtml.convert(readFile(outDir.resolve("README.md")), "readme")).append("</section>\n");
        for (Driver d : drivers) {
            String id = "driver-" + MarkdownToHtml.slug(d.name);
            Path f = outDir.resolve("drivers").resolve(AsCodeWriter.fileSafe(d.name) + ".md");
            html.append("<section id=\"").append(id).append("\">\n")
                .append(MarkdownToHtml.convert(readFile(f), id)).append("</section>\n");
        }
        html.append("<section id=\"library\">\n")
            .append(MarkdownToHtml.convert(readFile(outDir.resolve("library.md")), "library")).append("</section>\n");
        if (hasChanges) {
            html.append("<section id=\"changes\">\n")
                .append(MarkdownToHtml.convert(readFile(outDir.resolve("changes.md")), "changes")).append("</section>\n");
        }
        html.append("</body></html>\n");
        Files.writeString(outDir.resolve("index.html"), html.toString(), StandardCharsets.UTF_8);
    }

    private static String readFile(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // small shared helpers
    // ------------------------------------------------------------------

    private static String kindLabel(Policy p) {
        return p.policyKind().name().toLowerCase().replace('_', '-');
    }

    private static String localName(Element e) {
        String ln = e.getLocalName();
        return ln != null ? ln : e.getNodeName();
    }

    private static String firstText(Element parent, String childName) {
        List<Element> c = Xds.childrenByName(parent, childName);
        return c.isEmpty() ? null : Xds.text(c.get(0)).trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** Escapes a table/inline cell: backslashes, pipes, and newlines (Markdown-hostile). */
    private static String mdEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\r", "").replace("\n", " ");
    }

    private static String table(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append(" --- |".repeat(headers.size())).append('\n');
        for (List<String> row : rows) {
            List<String> esc = new ArrayList<>();
            for (String c : row) {
                esc.add(mdEscape(c));
            }
            sb.append("| ").append(String.join(" | ", esc)).append(" |\n");
        }
        return sb.toString();
    }

    /** The last component of a slash DN ({@code ..\Library\X} -> {@code X}) or bare name. Copied from {@code edit.Refs}. */
    private static String leaf(String dn) {
        if (dn == null) {
            return null;
        }
        String s = dn.trim();
        int i = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** The leaf of the DN in a {@code <dn>#<order>#<set>} linkage value. Copied from {@code edit.Refs}. */
    private static String leafOfLinkage(String value) {
        int hash = value.indexOf('#');
        String dn = hash >= 0 ? value.substring(0, hash) : value;
        if (dn.contains("=")) {
            String first = dn.split("(?<!\\\\),")[0];
            return first.substring(first.indexOf('=') + 1).trim();
        }
        return leaf(dn);
    }

    private static String gcvTable(Element configValues) {
        if (configValues == null) {
            return "(none)\n\n";
        }
        List<Element> defs = Xds.descendantsByName(configValues, "definition");
        if (defs.isEmpty()) {
            return "(none)\n\n";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Element def : defs) {
            String name = def.getAttribute("name");
            String type = def.getAttribute("type");
            List<Element> v = Xds.childrenByName(def, "value");
            String value = v.isEmpty() ? "" : Xds.text(v.get(0));
            if ("password-ref".equals(type)) {
                value = "(password)";
            }
            rows.add(List.of(name, type, value));
        }
        return table(List.of("Name", "Type", "Value"), rows) + "\n";
    }
}
