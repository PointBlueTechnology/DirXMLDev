package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Vault;
import com.pointblue.dirxml.dev.deploy.VaultAccess;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import javax.naming.directory.SearchControls;

/**
 * {@code vault.export-clone}: reads the source tree and writes a {@link CloneBundle}
 * (docs/vault-clone.md §3–§4, §9). Every entry the policy allows is taken with every
 * attribute the policy allows, verbatim; DN-bearing attributes are held back into the
 * references file (mandatory ones and the driver set's server list stay on the entry),
 * ACLs into the ACL file, secrets into the list of what the target must be given.
 * Entries are written parents first.
 *
 * <p><b>Servers.</b> IDM's server-specific driver settings are eDirectory never-sync
 * attributes ({@code X-NDS_NEVER_SYNC}: shim settings, GCVs, start option, state, engine
 * control values…): each server in the driver set's {@code DirXML-ServerList} holds its own
 * values, readable only through that server. The export reads them from every server it can
 * reach — the primary (the connection it was given) and the others through {@code connector}
 * — and records, per driver, which server it ran on, so the import can merge everything onto
 * one lab server or map servers one to one. Nothing is rewritten here except every driver's
 * start option, which becomes manual.
 */
public final class CloneExporter {

    public static final class Options {
        public boolean withRbs;
        public boolean keepDriverState;
        /** Containers whose identity data (users, groups) is cloned too; empty = configuration only. */
        public List<String> dataContainers = List.of();
        /** Replace people's names and mail local parts with consistent fakes on the way into the bundle. */
        public boolean pseudonymise;
        public String sourceName;     // environment name / URL for the manifest
        /** Source server DN -> LDAP URL, for servers the tree does not describe reachably; derived otherwise. */
        public Map<String, String> serverUrls = new LinkedHashMap<>();
        /** Opens a connection to another server of the same tree (same credentials); null = primary only. */
        public Function<String, VaultAccess> connector;
    }

    public static final class Report {
        public final CloneBundle bundle = new CloneBundle();
        public final List<String> notes = new ArrayList<>();
        public final Map<String, Integer> excludedAttributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        public final List<String> skippedSubtrees = new ArrayList<>();
        public int readEntries;
        public int skippedEntries;
        public int driversForcedManual;
        public int dataEntries;
        public int pseudonymised;

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("export-clone: read ").append(readEntries).append(" entries, cloning ")
                .append(bundle.containers.size()).append(" containers + ").append(bundle.objects.size()).append(" objects, ")
                .append(bundle.references.size()).append(" entries with held-back references, ")
                .append(bundle.acls.size()).append(" with ACLs; schema ")
                .append(bundle.attributeTypes.size()).append(" attributes / ").append(bundle.objectClasses.size()).append(" classes\n");
            for (Map<String, Object> s : bundle.servers()) {
                sb.append("  server   ").append(s.get("dn")).append(Boolean.TRUE.equals(s.get("primary")) ? " (primary)" : "")
                    .append(Boolean.FALSE.equals(s.get("reachable")) ? "  UNREACHABLE: " + s.get("error") : "  " + s.get("url"))
                    .append('\n');
            }
            if (!skippedSubtrees.isEmpty()) {
                sb.append("  skipped (never cloned): ").append(String.join(", ", skippedSubtrees)).append('\n');
            }
            sb.append("  skipped entries: ").append(skippedEntries).append("; drivers forced to manual start: ").append(driversForcedManual).append('\n');
            if (dataEntries > 0) {
                sb.append("  identity data: ").append(dataEntries).append(" entries").append(pseudonymised > 0 ? ", " + pseudonymised + " people pseudonymised (names and mail local parts)" : "").append('\n');
            }
            if (!excludedAttributes.isEmpty()) {
                sb.append("  excluded attributes (values): ");
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Integer> e : excludedAttributes.entrySet()) {
                    parts.add(e.getKey() + "=" + e.getValue());
                }
                sb.append(String.join(", ", parts)).append('\n');
            }
            if (!bundle.secretsNeeded.isEmpty()) {
                sb.append("  secrets the target must be given (").append(bundle.secretsNeeded.size()).append("):\n");
                for (String s : bundle.secretsNeeded) {
                    sb.append("    ").append(s).append('\n');
                }
            }
            for (String n : notes) {
                sb.append("  note     ").append(n).append('\n');
            }
            return sb.toString();
        }
    }

    private final VaultAccess vault;
    private final Options options;

    public CloneExporter(VaultAccess vault, Options options) {
        this.vault = vault;
        this.options = options;
    }

    /** Reads the source subschema — what a reading connection must be opened with (binary attributes). */
    public static Schema readSchema(VaultAccess v) {
        Vault.Entry e = v.read("cn=schema", "attributeTypes", "objectClasses");
        if (e == null) {
            throw new IllegalStateException("cn=schema not readable");
        }
        return Schema.of(e.strings("attributeTypes"), e.strings("objectClasses"));
    }

    public Report run() {
        Report r = new Report();
        CloneBundle b = r.bundle;
        Vault.Entry schemaEntry = vault.read("cn=schema", "attributeTypes", "objectClasses");
        if (schemaEntry == null) {
            throw new IllegalStateException("cn=schema not readable");
        }
        b.attributeTypes.addAll(schemaEntry.strings("attributeTypes"));
        b.objectClasses.addAll(schemaEntry.strings("objectClasses"));
        Schema schema = b.schema();
        Vault.Entry root = vault.read("", "directoryTreeName", "vendorVersion", "dsaName");
        String primary = root == null ? null : root.string("dsaName");

        String filter = options.dataContainers.isEmpty() ? configOnlyFilter() : "(objectClass=*)";
        List<Vault.Entry> all = new ArrayList<>();
        for (Vault.Entry e : vault.search("", filter, SearchControls.SUBTREE_SCOPE)) {
            if (e.dn == null || e.dn.isEmpty()) {
                continue;
            }
            all.add(e);
        }
        r.readEntries = all.size();
        all.sort(Comparator.comparingInt((Vault.Entry e) -> depth(e.dn)).thenComparing(e -> e.dn.toLowerCase(Locale.ROOT)));

        Set<String> skippedRoots = new LinkedHashSet<>();
        List<Vault.Entry> cloned = new ArrayList<>();
        for (Vault.Entry e : all) {
            String reason = skipReason(e, skippedRoots);
            if (reason != null) {
                r.skippedEntries++;
                if (!"under a skipped subtree".equals(reason) && !"data".equals(reason)) {
                    skippedRoots.add(e.dn.toLowerCase(Locale.ROOT));
                    r.skippedSubtrees.add(e.dn + " (" + reason + ")");
                }
                continue;
            }
            cloned.add(e);
        }
        Set<String> parentsOfCloned = new LinkedHashSet<>();
        for (Vault.Entry e : cloned) {
            parentsOfCloned.add(parent(e.dn).toLowerCase(Locale.ROOT));
        }
        List<String> driverSetDns = new ArrayList<>();
        Set<String> servers = new LinkedHashSet<>();
        for (Vault.Entry e : cloned) {
            if (e.hasClass("DirXML-DriverSet")) {
                driverSetDns.add(e.dn);
                servers.addAll(e.strings(ClonePolicy.SERVER_LIST_ATTR));
            }
        }
        if (primary == null) {
            primary = servers.isEmpty() ? null : servers.iterator().next();
        }
        Map<String, Map<String, List<byte[]>>> primaryValues = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> placement = new LinkedHashMap<>();   // driver -> server -> {state, startOption}
        Pseudonymiser pseudonymiser = options.pseudonymise ? new Pseudonymiser() : null;

        for (Vault.Entry e : cloned) {
            if (ClonePolicy.isData(e)) {
                r.dataEntries++;
                if (pseudonymiser != null) {
                    int before = pseudonymiser.people();
                    pseudonymiser.apply(e);
                    r.pseudonymised += pseudonymiser.people() - before;
                }
            }
            Vault.Entry out = new Vault.Entry(e.dn);
            Map<String, List<byte[]>> refs = new LinkedHashMap<>();
            Map<String, List<byte[]>> acls = new LinkedHashMap<>();
            Map<String, List<byte[]>> perServer = new LinkedHashMap<>();
            boolean inDriverSet = underAny(e.dn, driverSetDns);
            List<String> classes = e.objectClasses();
            for (Map.Entry<String, List<byte[]>> a : e.attrs.entrySet()) {
                String name = a.getKey();
                List<byte[]> values = a.getValue();
                if (name.equalsIgnoreCase("objectClass")) {
                    List<byte[]> kept = new ArrayList<>();
                    for (byte[] v : values) {
                        if (!ClonePolicy.DROP_CLASSES.contains(new String(v, StandardCharsets.UTF_8))) {
                            kept.add(v);
                        }
                    }
                    out.attrs.put(name, kept);
                    continue;
                }
                Placed p = place(schema, e, classes, name, values, r, b, primary, inDriverSet);
                switch (p.where) {
                    case DROP: break;
                    case ENTRY: out.attrs.put(name, p.values); break;
                    case REFERENCE: refs.put(name, p.values); break;
                    case ACL: acls.put(name, p.values); break;
                    case SERVER: perServer.put(name, p.values); break;
                    default: break;
                }
                if (name.equalsIgnoreCase(ClonePolicy.SERVER_LIST_ATTR)) {
                    for (byte[] v : values) {
                        servers.add(new String(v, StandardCharsets.UTF_8));
                    }
                }
            }
            if (e.hasClass("DirXML-Driver")) {
                if (!perServer.containsKey(ClonePolicy.START_OPTION_ATTR) && !out.attrs.containsKey(ClonePolicy.START_OPTION_ATTR)) {
                    // no start option recorded means the engine's default; the clone says manual, explicitly
                    (inDriverSet ? perServer : out.attrs).put(ClonePolicy.START_OPTION_ATTR, List.of(ClonePolicy.MANUAL_START.getBytes(StandardCharsets.UTF_8)));
                    r.driversForcedManual++;
                }
                if (primary != null) {
                    placement.computeIfAbsent(e.dn, k -> new LinkedHashMap<>()).put(primary, stateOf(e));
                }
            }
            boolean container = parentsOfCloned.contains(e.dn.toLowerCase(Locale.ROOT)) || isContainerClass(classes);
            (container ? b.containers : b.objects).add(out);
            if (!refs.isEmpty()) {
                b.references.put(e.dn, refs);
            }
            if (!acls.isEmpty()) {
                b.acls.put(e.dn, acls);
            }
            if (!perServer.isEmpty() && primary != null) {
                primaryValues.put(e.dn, perServer);
            }
        }

        // ---- the servers: the primary's never-sync values were read above; the others' now ----
        List<Map<String, Object>> serverList = new ArrayList<>();
        if (primary != null) {
            b.serverValues.put(primary, primaryValues);
            serverList.add(serverRecord(primary, options.sourceName, true, true, null));
        }
        List<String> neverSync = schema.neverSyncAttributes();
        for (String s : servers) {
            if (s.equalsIgnoreCase(primary)) {
                continue;
            }
            String url = options.serverUrls.get(s);
            if (url == null) {
                url = deriveUrl(s, r);
            }
            if (url == null || options.connector == null) {
                serverList.add(serverRecord(s, url, false, false, url == null ? "no LDAP URL (give <env>.servers=" + s + "=ldaps://host:port)" : "no connector"));
                r.notes.add("server " + s + " not read: " + (url == null ? "no URL known" : "no connector") + " — drivers that ran there keep the primary's server-specific values");
                continue;
            }
            Map<String, Map<String, List<byte[]>>> values = new LinkedHashMap<>();
            try (VaultAccess other = options.connector.apply(url)) {
                for (Vault.Entry e : cloned) {
                    if (!underAny(e.dn, driverSetDns)) {
                        continue;
                    }
                    Vault.Entry se = other.read(e.dn, neverSync.toArray(new String[0]));
                    if (se == null) {
                        continue;
                    }
                    Map<String, List<byte[]>> perServer = new LinkedHashMap<>();
                    for (Map.Entry<String, List<byte[]>> a : se.attrs.entrySet()) {
                        if (a.getKey().equalsIgnoreCase("objectClass") || !schema.neverSync(a.getKey())) {
                            continue;
                        }
                        Placed p = place(schema, se, e.objectClasses(), a.getKey(), a.getValue(), r, b, s, true);
                        if (p.where == Where.SERVER || p.where == Where.ENTRY) {
                            perServer.put(a.getKey(), p.values);
                        }
                    }
                    if (e.hasClass("DirXML-Driver")) {
                        if (!perServer.containsKey(ClonePolicy.START_OPTION_ATTR)) {
                            perServer.put(ClonePolicy.START_OPTION_ATTR, List.of(ClonePolicy.MANUAL_START.getBytes(StandardCharsets.UTF_8)));
                        }
                        placement.computeIfAbsent(e.dn, k -> new LinkedHashMap<>()).put(s, stateOf(se));
                    }
                    if (!perServer.isEmpty()) {
                        values.put(e.dn, perServer);
                    }
                }
                b.serverValues.put(s, values);
                serverList.add(serverRecord(s, url, false, true, null));
            } catch (Exception ex) {
                serverList.add(serverRecord(s, url, false, false, ex.getMessage()));
                r.notes.add("server " + s + " unreachable at " + url + ": " + ex.getMessage()
                    + " — drivers that ran there keep the primary's server-specific values");
            }
        }
        Map<String, Object> placementOut = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, Object>>> d : placement.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runsOn", runsOn(d.getValue(), primary));
            m.put("servers", d.getValue());
            placementOut.put(d.getKey(), m);
        }

        b.manifest.put("format", "dirxmldev-clone/2");
        b.manifest.put("exportedAt", Instant.now().toString());
        b.manifest.put("source", options.sourceName);
        b.manifest.put("sourceTree", root == null ? null : root.string("directoryTreeName"));
        b.manifest.put("sourceVersion", root == null ? null : root.string("vendorVersion"));
        b.manifest.put("sourceServerDn", primary != null ? primary : (servers.isEmpty() ? null : servers.iterator().next()));
        b.manifest.put("sourceServers", new ArrayList<>(servers));
        b.manifest.put("servers", serverList);
        b.manifest.put("driverSets", driverSetDns);
        b.manifest.put("driverPlacement", placementOut);
        b.manifest.put("withRbs", options.withRbs);
        b.manifest.put("keepDriverState", options.keepDriverState);
        b.manifest.put("dataContainers", options.dataContainers);
        b.manifest.put("dataEntries", r.dataEntries);
        b.manifest.put("pseudonymised", options.pseudonymise);
        b.manifest.put("pseudonymisedPeople", r.pseudonymised);
        b.manifest.put("driversForcedManual", r.driversForcedManual);
        b.manifest.put("skippedSubtrees", r.skippedSubtrees);
        Map<String, Object> excluded = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> x : r.excludedAttributes.entrySet()) {
            excluded.put(x.getKey(), x.getValue());
        }
        b.manifest.put("excludedAttributes", excluded);
        if (servers.size() > 1) {
            r.notes.add("the driver set names " + servers.size() + " servers; the import merges them onto one lab server (per driver: the server it ran on) unless --map names a lab server for each");
        }
        return r;
    }

    // ---- one attribute's fate ----

    enum Where { DROP, ENTRY, REFERENCE, ACL, SERVER }

    private static final class Placed {
        final Where where;
        final List<byte[]> values;

        Placed(Where where, List<byte[]> values) {
            this.where = where;
            this.values = values;
        }
    }

    private Placed place(Schema schema, Vault.Entry e, List<String> classes, String name, List<byte[]> values,
                         Report r, CloneBundle b, String server, boolean inDriverSet) {
        if (ClonePolicy.SECRET_ATTRS.contains(name)) {
            b.secretsNeeded.add(e.dn + ": " + name + (server == null ? "" : " (on " + server + ")"));
            count(r.excludedAttributes, name, values.size());
            return new Placed(Where.DROP, values);
        }
        if (ClonePolicy.excludedAttribute(name, options.keepDriverState, !options.dataContainers.isEmpty()) || schema.isOperational(name)) {
            count(r.excludedAttributes, name, values.size());
            return new Placed(Where.DROP, values);
        }
        if (name.equalsIgnoreCase(ClonePolicy.START_OPTION_ATTR)) {
            String cur = new String(values.get(0), StandardCharsets.UTF_8).trim();
            if (!cur.equals(ClonePolicy.MANUAL_START)) {
                r.driversForcedManual++;
            }
            List<byte[]> manual = List.of(ClonePolicy.MANUAL_START.getBytes(StandardCharsets.UTF_8));
            return new Placed(inDriverSet ? Where.SERVER : Where.ENTRY, manual);
        }
        if (name.equalsIgnoreCase(ClonePolicy.SERVER_LIST_ATTR)) {
            return new Placed(Where.ENTRY, values);    // the association, and mandatory on a job
        }
        if (schema.isAcl(name)) {
            return new Placed(Where.ACL, values);
        }
        if (schema.carriesDn(name) && !mandatory(schema, classes, name)) {
            return new Placed(Where.REFERENCE, values);
        }
        if (inDriverSet && schema.neverSync(name)) {
            return new Placed(Where.SERVER, values);
        }
        if (schema.attribute(name) == null) {
            r.notes.add(e.dn + ": attribute '" + name + "' is not in the source schema; kept as text");
        }
        return new Placed(Where.ENTRY, values);
    }

    /** A driver's run state on one server: {@code DirXML-State} and its recorded start option. */
    private static Map<String, Object> stateOf(Vault.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        String state = e.string("DirXML-State");
        String start = e.string(ClonePolicy.START_OPTION_ATTR);
        m.put("state", state == null ? null : state.trim());
        m.put("startOption", start == null ? null : start.trim());
        return m;
    }

    /** The server a driver ran on: running/starting there, else enabled there, else the primary. */
    static String runsOn(Map<String, Map<String, Object>> byServer, String primary) {
        for (Map.Entry<String, Map<String, Object>> s : byServer.entrySet()) {
            String st = String.valueOf(s.getValue().get("state"));
            if (st.equals("2") || st.equals("1")) {
                return s.getKey();
            }
        }
        for (Map.Entry<String, Map<String, Object>> s : byServer.entrySet()) {
            String so = String.valueOf(s.getValue().get("startOption"));
            if (so.equals("1") || so.equals("2")) {
                return s.getKey();
            }
        }
        return primary != null ? primary : (byServer.isEmpty() ? null : byServer.keySet().iterator().next());
    }

    private static Map<String, Object> serverRecord(String dn, String url, boolean primary, boolean reachable, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dn", dn);
        m.put("url", url);
        m.put("primary", primary);
        m.put("reachable", reachable);
        m.put("error", error);
        return m;
    }

    /**
     * An LDAPS URL for a server of the tree: the IP from the server object's TCP
     * {@code networkAddress} ({@code 9#<port><ip>}) and the LDAPS port from its LDAP Server
     * object's {@code ldapInterfaces}; null when the tree does not say.
     */
    private String deriveUrl(String serverDn, Report r) {
        String url = com.pointblue.dirxml.dev.deploy.Servers.deriveUrl(vault, serverDn);
        if (url == null) {
            r.notes.add("could not derive an LDAP URL for " + serverDn + " (no TCP networkAddress on the server object)");
        }
        return url;
    }

    private static int indexOf(byte[] v, byte b) {
        for (int i = 0; i < v.length; i++) {
            if (v[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** Persons, groups, aliases and roles are data; everything else is configuration. */
    static String configOnlyFilter() {
        StringBuilder sb = new StringBuilder("(&(objectClass=*)");
        for (String c : List.of("Person", "groupOfNames", "dynamicGroup", "aliasObject", "organizationalRole", "Template", "Profile")) {
            sb.append("(!(objectClass=").append(c).append("))");
        }
        return sb.append(')').toString();
    }

    private String skipReason(Vault.Entry e, Set<String> skippedRoots) {
        String l = e.dn.toLowerCase(Locale.ROOT);
        for (String root : skippedRoots) {
            if (l.endsWith("," + root)) {
                return "under a skipped subtree";
            }
        }
        if (l.equals("cn=schema")) {
            return "schema";
        }
        if (ClonePolicy.neverClone(e, options.withRbs)) {
            return ClonePolicy.isServerSubtree(e.dn) ? "servers" : "never cloned: " + String.join(",", e.objectClasses());
        }
        if (ClonePolicy.isSecurityInternal(e.dn)) {
            return "Security internals";
        }
        if (ClonePolicy.isData(e) && !underData(e.dn)) {
            return "data";
        }
        return null;
    }

    private boolean underData(String dn) {
        return underAny(dn, options.dataContainers);
    }

    static boolean underAny(String dn, List<String> roots) {
        String l = dn.toLowerCase(Locale.ROOT);
        for (String c : roots) {
            String lc = c.toLowerCase(Locale.ROOT);
            if (l.equals(lc) || l.endsWith("," + lc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mandatory(Schema schema, List<String> classes, String attr) {
        for (String c : classes) {
            if (schema.mustHave(c, attr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContainerClass(List<String> classes) {
        for (String c : classes) {
            String l = c.toLowerCase(Locale.ROOT);
            if (l.equals("organization") || l.equals("organizationalunit") || l.equals("domain") || l.equals("country")
                || l.equals("locality") || l.equals("dirxml-driverset") || l.equals("sassecurity") || l.equals("container")) {
                return true;
            }
        }
        return false;
    }

    /** RDN count, escaped commas respected. */
    static int depth(String dn) {
        int n = 1;
        for (int i = 0; i < dn.length(); i++) {
            if (dn.charAt(i) == ',' && (i == 0 || dn.charAt(i - 1) != '\\')) {
                n++;
            }
        }
        return n;
    }

    static String parent(String dn) {
        for (int i = 0; i < dn.length(); i++) {
            if (dn.charAt(i) == ',' && (i == 0 || dn.charAt(i - 1) != '\\')) {
                return dn.substring(i + 1).trim();
            }
        }
        return "";
    }

    private static void count(Map<String, Integer> m, String k, int n) {
        m.merge(k, n, Integer::sum);
    }

    /** Sorted, distinct DNs of everything the bundle creates — the importer's "will exist" set. */
    static Set<String> dns(CloneBundle b) {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Vault.Entry e : b.containers) {
            s.add(e.dn);
        }
        for (Vault.Entry e : b.objects) {
            s.add(e.dn);
        }
        return s;
    }
}
