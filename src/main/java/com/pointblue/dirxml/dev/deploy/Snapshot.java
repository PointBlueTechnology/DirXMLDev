package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A point-in-time capture of everything a deploy is about to touch — every
 * object's full entry (or an "absent" marker for one about to be added) and
 * every affected driver's state / start option — written to disk as LDIF
 * (RFC 2849) + a JSON manifest, read back, and restored.
 *
 * <p>Secrets ({@code DirXML-ShimAuthPassword}, anything with "password" in its
 * name) are captured — rollback needs them — but only the {@code .ldif} ever
 * carries their bytes; the manifest and every text/log rendering show
 * {@code <secret, N bytes>} instead.
 *
 * <p>Restore and diff logic runs against a small package-private {@link Store}
 * interface so it can be tested with a fake, since {@link Vault} is {@code final}.
 * {@link #store(Vault)} adapts a real vault to it.
 */
public final class Snapshot {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
        .withZone(ZoneOffset.UTC);

    public final String env;
    public final String driverSetDn;
    public final String timestamp;
    public final String treeCommit;   // nullable
    public final String user;
    public final String label;        // nullable
    public final String plan;         // nullable — the plan text the caller passed
    public final List<CapturedEntry> entries = new ArrayList<>();
    public final List<DriverStateInfo> drivers = new ArrayList<>();

    Snapshot(String env, String driverSetDn, String timestamp, String treeCommit, String user, String label, String plan) {
        this.env = env;
        this.driverSetDn = driverSetDn;
        this.timestamp = timestamp;
        this.treeCommit = treeCommit;
        this.user = user;
        this.label = label;
        this.plan = plan;
    }

    /** One captured DN: its full entry, or an absent marker (the DN didn't exist). */
    public static final class CapturedEntry {
        public final String dn;
        public final boolean absent;
        public final Vault.Entry entry;   // null iff absent

        CapturedEntry(String dn, boolean absent, Vault.Entry entry) {
            this.dn = dn;
            this.absent = absent;
            this.entry = entry;
        }
    }

    /** A captured driver's state and start option at snapshot time. */
    public static final class DriverStateInfo {
        public final String dn;
        public final int state;
        public final int startOption;

        DriverStateInfo(String dn, int state, int startOption) {
            this.dn = dn;
            this.state = state;
            this.startOption = startOption;
        }
    }

    // ---- capture --------------------------------------------------------------------

    /**
     * Read every DN's full entry (or record it absent) and every driver's
     * state/start option, as of right now. Refuses (IllegalArgumentException)
     * a DN that is not {@code driverSetDn} itself or under it.
     */
    public static Snapshot capture(Vault v, String env, String driverSetDn, Collection<String> dns,
            Collection<String> driverDns, String treeCommit, String label, String planText) {
        for (String dn : dns) {
            requireUnderDriverSet(dn, driverSetDn);
        }
        for (String dn : driverDns) {
            requireUnderDriverSet(dn, driverSetDn);
        }
        Snapshot s = new Snapshot(env, driverSetDn, TS.format(Instant.now()), treeCommit,
            System.getProperty("user.name"), label, planText);
        for (String dn : dns) {
            Vault.Entry e = v.read(dn);
            s.entries.add(new CapturedEntry(dn, e == null, e));
        }
        for (String dn : driverDns) {
            s.drivers.add(new DriverStateInfo(dn, v.driverState(dn), v.driverStartOption(dn)));
        }
        return s;
    }

    private static void requireUnderDriverSet(String dn, String driverSetDn) {
        String d = dn.toLowerCase(Locale.ROOT);
        String ds = driverSetDn.toLowerCase(Locale.ROOT);
        if (!d.equals(ds) && !d.endsWith("," + ds)) {
            throw new IllegalArgumentException(dn + " is not " + driverSetDn + " or under it");
        }
    }

    // ---- disk: write / read -----------------------------------------------------------

    /** Writes {@code <timestamp>.ldif} and {@code <timestamp>.json} into {@code dir}; returns the .ldif path. */
    public Path write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path ldifPath = dir.resolve(timestamp + ".ldif");
        Path jsonPath = dir.resolve(timestamp + ".json");
        Files.writeString(ldifPath, toLdif(), StandardCharsets.UTF_8);
        Files.writeString(jsonPath, json(), StandardCharsets.UTF_8);
        return ldifPath;
    }

    /** Reads a snapshot back from its {@code .ldif} (entries/values) and sibling {@code .json} (manifest). */
    public static Snapshot read(Path ldif) throws IOException {
        String ldifText = Files.readString(ldif, StandardCharsets.UTF_8);
        Path jsonPath = siblingJson(ldif);
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) Json.parse(Files.readString(jsonPath, StandardCharsets.UTF_8));

        Snapshot s = new Snapshot(
            str(manifest, "env"), str(manifest, "driverSetDn"), str(manifest, "timestamp"),
            str(manifest, "treeCommit"), str(manifest, "user"), str(manifest, "label"), str(manifest, "plan"));

        for (List<String> block : blocks(unfold(ldifText))) {
            s.entries.add(parseBlock(block));
        }
        for (Object o : asList(manifest.get("drivers"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            s.drivers.add(new DriverStateInfo(str(m, "dn"), num(m, "state"), num(m, "startOption")));
        }
        return s;
    }

    private static Path siblingJson(Path ldif) {
        String name = ldif.getFileName().toString();
        String base = name.endsWith(".ldif") ? name.substring(0, name.length() - 5) : name;
        return ldif.resolveSibling(base + ".json");
    }

    // ---- LDIF rendering ---------------------------------------------------------------

    private String toLdif() {
        List<String> blocks = new ArrayList<>();
        for (CapturedEntry ce : entries) {
            if (ce.absent) {
                blocks.add("# absent: " + ce.dn);
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(ldifLine("dn", ce.entry.dn.getBytes(StandardCharsets.UTF_8))).append('\n');
                for (Map.Entry<String, List<byte[]>> a : ce.entry.attrs.entrySet()) {
                    for (byte[] v : a.getValue()) {
                        sb.append(ldifLine(a.getKey(), v)).append('\n');
                    }
                }
                blocks.add(sb.toString().stripTrailing());
            }
        }
        return String.join("\n\n", blocks) + (blocks.isEmpty() ? "" : "\n");
    }

    private static String ldifLine(String name, byte[] value) {
        String raw = isSafe(value)
            ? name + ": " + new String(value, StandardCharsets.UTF_8)
            : name + ":: " + Base64.getEncoder().encodeToString(value);
        return fold(raw);
    }

    /** RFC 2849 "safe string": printable ASCII, not starting with space/colon/less-than. */
    private static boolean isSafe(byte[] v) {
        if (v.length == 0) {
            return true;
        }
        if (v[0] == ' ' || v[0] == ':' || v[0] == '<') {
            return false;
        }
        for (byte b : v) {
            int c = b & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static String fold(String line) {
        if (line.length() <= 76) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(line, 0, 76);
        int i = 76;
        while (i < line.length()) {
            int end = Math.min(i + 75, line.length());
            sb.append('\n').append(' ').append(line, i, end);
            i = end;
        }
        return sb.toString();
    }

    // ---- LDIF parsing -------------------------------------------------------------------

    /** Merge continuation lines (a single leading space) back into the logical line they fold. */
    private static List<String> unfold(String text) {
        String[] raw = text.split("\n", -1);
        List<String> logical = new ArrayList<>();
        for (String line : raw) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.startsWith(" ") && !logical.isEmpty() && !logical.get(logical.size() - 1).isEmpty()) {
                int last = logical.size() - 1;
                logical.set(last, logical.get(last) + line.substring(1));
            } else {
                logical.add(line);
            }
        }
        return logical;
    }

    private static List<List<String>> blocks(List<String> logicalLines) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String line : logicalLines) {
            if (line.isEmpty()) {
                if (!cur.isEmpty()) {
                    out.add(cur);
                    cur = new ArrayList<>();
                }
            } else {
                cur.add(line);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static CapturedEntry parseBlock(List<String> block) {
        String first = block.get(0);
        if (first.startsWith("# absent: ")) {
            return new CapturedEntry(first.substring("# absent: ".length()), true, null);
        }
        AttrLine dnLine = parseAttrLine(first);
        if (!dnLine.name.equalsIgnoreCase("dn")) {
            throw new IllegalArgumentException("expected 'dn:' line, got: " + first);
        }
        String dn = new String(dnLine.value, StandardCharsets.UTF_8);
        Vault.Entry entry = new Vault.Entry(dn);
        for (int i = 1; i < block.size(); i++) {
            AttrLine a = parseAttrLine(block.get(i));
            entry.attrs.computeIfAbsent(a.name, k -> new ArrayList<>()).add(a.value);
        }
        return new CapturedEntry(dn, false, entry);
    }

    private static final class AttrLine {
        final String name;
        final byte[] value;
        AttrLine(String name, byte[] value) {
            this.name = name;
            this.value = value;
        }
    }

    private static AttrLine parseAttrLine(String line) {
        int c = line.indexOf(':');
        if (c < 0) {
            throw new IllegalArgumentException("bad LDIF line: " + line);
        }
        String name = line.substring(0, c);
        if (c + 1 < line.length() && line.charAt(c + 1) == ':') {
            String b64 = line.substring(c + 2).trim();
            return new AttrLine(name, b64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(b64));
        }
        String rest = c + 1 < line.length() ? line.substring(c + 1) : "";
        if (rest.startsWith(" ")) {
            rest = rest.substring(1);
        }
        return new AttrLine(name, rest.getBytes(StandardCharsets.UTF_8));
    }

    // ---- manifest (JSON) ----------------------------------------------------------------

    public String json() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"env\":").append(q(env));
        sb.append(",\"driverSetDn\":").append(q(driverSetDn));
        sb.append(",\"timestamp\":").append(q(timestamp));
        sb.append(",\"treeCommit\":").append(treeCommit == null ? "null" : q(treeCommit));
        sb.append(",\"user\":").append(q(user));
        sb.append(",\"label\":").append(label == null ? "null" : q(label));
        sb.append(",\"plan\":").append(plan == null ? "null" : q(plan));
        sb.append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            CapturedEntry ce = entries.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"dn\":").append(q(ce.dn)).append(",\"absent\":").append(ce.absent).append('}');
        }
        sb.append("],\"drivers\":[");
        for (int i = 0; i < drivers.size(); i++) {
            DriverStateInfo d = drivers.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"dn\":").append(q(d.dn)).append(",\"state\":").append(d.state)
                .append(",\"startOption\":").append(d.startOption).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---- human text (never a secret's bytes) --------------------------------------------

    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("snapshot ").append(timestamp).append(" env=").append(env)
            .append(" driverSet=").append(driverSetDn).append(" user=").append(user);
        if (label != null) {
            sb.append(" label=").append(label);
        }
        if (treeCommit != null) {
            sb.append(" commit=").append(treeCommit);
        }
        sb.append('\n');
        for (CapturedEntry ce : entries) {
            if (ce.absent) {
                sb.append("  absent   ").append(ce.dn).append('\n');
            } else {
                sb.append("  present  ").append(ce.dn).append(" (").append(ce.entry.attrs.size()).append(" attribute(s))\n");
                for (Map.Entry<String, List<byte[]>> a : ce.entry.attrs.entrySet()) {
                    for (byte[] v : a.getValue()) {
                        sb.append("    ").append(a.getKey()).append(": ").append(describeValue(a.getKey(), v)).append('\n');
                    }
                }
            }
        }
        for (DriverStateInfo d : drivers) {
            sb.append("  driver   ").append(d.dn).append(" state=").append(Vault.stateName(d.state))
                .append(" startOption=").append(startOptionName(d.startOption)).append('\n');
        }
        return sb.toString();
    }

    private static String describeValue(String name, byte[] v) {
        if (isSecret(name)) {
            return "<secret, " + v.length + " bytes>";
        }
        if (isSafe(v)) {
            return new String(v, StandardCharsets.UTF_8);
        }
        return "<" + v.length + " bytes>";
    }

    private static String startOptionName(int o) {
        switch (o) {
            case Vault.START_DISABLED: return "disabled";
            case Vault.START_MANUAL: return "manual";
            case Vault.START_AUTO: return "auto";
            default: return "option-" + o;
        }
    }

    // ---- restore --------------------------------------------------------------------

    /** Puts the snapshot back into {@code v}: deletes, restores, re-adds, then restarts. */
    public RestoreResult restore(Vault v) {
        return restore(store(v));
    }

    RestoreResult restore(Store s) {
        RestoreResult r = new RestoreResult();

        // 1. absent DNs that now exist -> delete, child before parent
        List<CapturedEntry> deletes = new ArrayList<>();
        for (CapturedEntry ce : entries) {
            if (ce.absent && s.read(ce.dn) != null) {
                deletes.add(ce);
            }
        }
        deletes.sort((a, b) -> Integer.compare(componentCount(b.dn), componentCount(a.dn)));
        for (CapturedEntry ce : deletes) {
            try {
                s.delete(ce.dn);
                r.actions.add("deleted " + ce.dn);
            } catch (RuntimeException e) {
                r.failures.put(ce.dn, e.getMessage());
            }
        }

        // 2. present DNs that still exist -> replace attributes to match
        for (CapturedEntry ce : entries) {
            if (ce.absent) {
                continue;
            }
            Vault.Entry live = s.read(ce.dn);
            if (live == null) {
                continue;   // handled in step 3
            }
            try {
                restorePresentEntry(s, ce.entry, live);
                r.actions.add("restored " + ce.dn);
            } catch (RuntimeException e) {
                r.failures.put(ce.dn, e.getMessage());
            }
        }

        // 3. present DNs that no longer exist -> re-add, parent before child
        List<CapturedEntry> adds = new ArrayList<>();
        for (CapturedEntry ce : entries) {
            if (!ce.absent && s.read(ce.dn) == null) {
                adds.add(ce);
            }
        }
        adds.sort((a, b) -> Integer.compare(componentCount(a.dn), componentCount(b.dn)));
        for (CapturedEntry ce : adds) {
            try {
                Map<String, List<byte[]>> attrs = new LinkedHashMap<>();
                for (Map.Entry<String, List<byte[]>> a : ce.entry.attrs.entrySet()) {
                    if (a.getKey().equalsIgnoreCase("objectClass") || isOperational(a.getKey())) {
                        continue;
                    }
                    attrs.put(a.getKey(), a.getValue());
                }
                s.add(ce.dn, ce.entry.objectClasses(), attrs);
                r.actions.add("recreated " + ce.dn);
            } catch (RuntimeException e) {
                r.failures.put(ce.dn, e.getMessage());
            }
        }

        // 4. drivers that were running -> restart and wait
        for (DriverStateInfo d : drivers) {
            if (d.state == Vault.STATE_RUNNING) {
                try {
                    s.restartDriver(d.dn);
                    String seen = s.waitForState(d.dn, Vault.STATE_RUNNING, 180);
                    r.actions.add("restarted " + d.dn + (seen == null || seen.isEmpty() ? "" : " (" + seen + ")"));
                } catch (RuntimeException e) {
                    r.failures.put(d.dn, e.getMessage());
                }
            }
        }
        return r;
    }

    private static void restorePresentEntry(Store s, Vault.Entry snap, Vault.Entry live) {
        for (Map.Entry<String, List<byte[]>> a : snap.attrs.entrySet()) {
            String name = a.getKey();
            if (name.equalsIgnoreCase("objectClass") || name.equalsIgnoreCase("cn")) {
                continue;
            }
            s.replace(snap.dn, name, a.getValue());
        }
        for (String name : new ArrayList<>(live.attrs.keySet())) {
            if (name.equalsIgnoreCase("objectClass") || name.equalsIgnoreCase("cn") || isOperational(name)) {
                continue;
            }
            if (snap.attrs.containsKey(name)) {
                continue;
            }
            s.replace(snap.dn, name, List.of());
        }
    }

    // ---- differences ------------------------------------------------------------------

    /** Re-reads every captured DN and reports how the vault differs from this snapshot; empty = matches. */
    public List<String> differences(Vault v) {
        return differences(store(v));
    }

    List<String> differences(Store s) {
        List<String> diffs = new ArrayList<>();
        for (CapturedEntry ce : entries) {
            Vault.Entry live = s.read(ce.dn);
            if (ce.absent) {
                if (live != null) {
                    diffs.add(ce.dn + ": expected absent, found present");
                }
                continue;
            }
            if (live == null) {
                diffs.add(ce.dn + ": expected present, found absent");
                continue;
            }
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(ce.entry.attrs.keySet());
            names.addAll(live.attrs.keySet());
            for (String name : names) {
                if (isOperational(name)) {
                    continue;
                }
                List<byte[]> snapV = ce.entry.attrs.get(name);
                List<byte[]> liveV = live.attrs.get(name);
                String secretSuffix = isSecret(name) ? " (secret)" : "";
                if (snapV == null) {
                    diffs.add(ce.dn + ": " + name + " added" + secretSuffix);
                } else if (liveV == null) {
                    diffs.add(ce.dn + ": " + name + " removed" + secretSuffix);
                } else if (!valuesEqual(snapV, liveV)) {
                    diffs.add(ce.dn + ": " + name + " changed" + secretSuffix);
                }
            }
        }
        return diffs;
    }

    private static boolean valuesEqual(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ---- Store: the seam that lets restore()/differences() be tested without a live Vault ----

    interface Store {
        Vault.Entry read(String dn);
        void add(String dn, List<String> objectClasses, Map<String, List<byte[]>> attrs);
        void replace(String dn, String attr, List<byte[]> values);
        void delete(String dn);
        int driverState(String dn);
        void restartDriver(String dn);
        String waitForState(String dn, int wanted, int seconds);
    }

    static Store store(Vault v) {
        return new Store() {
            public Vault.Entry read(String dn) {
                return v.read(dn);
            }
            public void add(String dn, List<String> objectClasses, Map<String, List<byte[]>> attrs) {
                v.add(dn, objectClasses, attrs);
            }
            public void replace(String dn, String attr, List<byte[]> values) {
                v.replace(dn, attr, values);
            }
            public void delete(String dn) {
                v.delete(dn);
            }
            public int driverState(String dn) {
                return v.driverState(dn);
            }
            public void restartDriver(String dn) {
                v.restartDriver(dn);
            }
            public String waitForState(String dn, int wanted, int seconds) {
                return v.waitForState(dn, wanted, seconds);
            }
        };
    }

    // ---- restore result -----------------------------------------------------------------

    public static final class RestoreResult {
        public final List<String> actions = new ArrayList<>();
        public final Map<String, String> failures = new LinkedHashMap<>();

        public boolean ok() {
            return failures.isEmpty();
        }

        public String text() {
            StringBuilder sb = new StringBuilder("restore: ").append(ok() ? "ok" : "FAILED").append('\n');
            for (String a : actions) {
                sb.append("  ").append(a).append('\n');
            }
            for (Map.Entry<String, String> f : failures.entrySet()) {
                sb.append("  FAILED   ").append(f.getKey()).append(": ").append(f.getValue()).append('\n');
            }
            return sb.toString();
        }

        public String json() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"ok\":").append(ok());
            sb.append(",\"actions\":").append(arr(actions));
            sb.append(",\"failures\":{");
            boolean first = true;
            for (Map.Entry<String, String> f : failures.entrySet()) {
                sb.append(first ? "" : ",").append(q(f.getKey())).append(':').append(q(f.getValue()));
                first = false;
            }
            sb.append("}}");
            return sb.toString();
        }
    }

    // ---- shared helpers -----------------------------------------------------------------

    static boolean isSecret(String name) {
        return name.equalsIgnoreCase("DirXML-ShimAuthPassword") || name.toLowerCase(Locale.ROOT).contains("password");
    }

    private static boolean isOperational(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        // eDirectory operational attributes: creatorsName, createTimestamp, modifiersName,
        // modifyTimestamp, entryFlags, subordinateCount, GUID, revision, localEntryID,
        // structuralObjectClass, subschemaSubentry (ACL and DirXML-pkg* are restorable)
        return n.startsWith("creat") || n.startsWith("modif") || n.startsWith("entry")
            || n.startsWith("subordinate") || n.startsWith("guid") || n.startsWith("revision")
            || n.startsWith("localentryid") || n.startsWith("structuralobjectclass") || n.startsWith("subschema");
    }

    private static int componentCount(String dn) {
        if (dn.isBlank()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ',') {
                count++;
            }
        }
        return count;
    }

    // ---- tiny JSON: writer helpers + a minimal reader (no library dependency) -------------

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static String arr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : (String) v;
    }

    private static int num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    /** A minimal recursive-descent JSON reader — objects, arrays, strings, numbers, booleans, null. */
    private static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json p = new Json(s);
            p.ws();
            Object v = p.value();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            return number();
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, i)) {
                throw new IllegalArgumentException("expected " + lit + " at " + i);
            }
            i += lit.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                if (s.charAt(i) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + i);
                }
                i++;
                ws();
                Object v = value();
                m.put(key, v);
                ws();
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    break;
                }
                throw new IllegalArgumentException("expected ',' or '}' at " + i);
            }
            return m;
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    break;
                }
                throw new IllegalArgumentException("expected ',' or ']' at " + i);
            }
            return l;
        }

        private String string() {
            if (s.charAt(i) != '"') {
                throw new IllegalArgumentException("expected string at " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            i++;
            return sb.toString();
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String num = s.substring(start, i);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }
    }
}
