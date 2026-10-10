package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.DeployLog;
import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Secrets;
import com.pointblue.dirxml.dev.deploy.Vault;
import com.pointblue.dirxml.dev.deploy.VaultMapping;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 — operate: day-two operation of a driver set (docs/operate.md): what
 * every driver is doing, start/stop/restart, cache view/clear, migrate/resync,
 * named passwords, trace — behind the deploy's environments, tiers and audit
 * log ({@link DeployLog}, {@code operation="operate"}).
 *
 * <p>{@link Vault} is {@code final}, so every call this class makes goes
 * through the small package-private {@link Engine} seam instead — {@link
 * #vaultEngine(Vault)} adapts a real vault to it; tests use a fake.
 *
 * <p>Not here: {@code driver.trace tail} (SSH) and {@code driver.submit} —
 * built separately per the task note.
 */
public final class Operate {

    private Operate() {
    }

    // ---- the seam: everything this class needs from a live vault ------------------------

    /**
     * A page of a driver's event cache — decoupled from {@link Vault.CachePage} (whose
     * constructor is package-private to {@code deploy}) so a test fake can build one.
     */
    static final class CachePage {
        final String xds;
        final int nextToken;
        final boolean empty;

        CachePage(String xds, int nextToken) {
            this.xds = xds == null ? "" : xds;
            this.nextToken = nextToken;
            this.empty = this.xds.isEmpty();
        }
    }

    /** Package-private so tests can supply a fake without touching the real (final) {@link Vault}. */
    interface Engine {
        int driverState(String dn);

        int driverStartOption(String dn);

        /** {@code SetDriverStartOption}: auto, manual or disabled, live (docs/operate.md). */
        default void setDriverStartOption(String dn, int option) {
            throw new UnsupportedOperationException("setDriverStartOption");
        }

        /** An LDAP search, for the association and password-sync reads. */
        default List<Vault.Entry> search(String base, String filter, int scope) {
            throw new UnsupportedOperationException("search");
        }

        /** {@code StartJob} (docs/console-gaps.md §1). */
        default void startJob(String jobDn) {
            throw new UnsupportedOperationException("startJob");
        }

        /** {@code AbortJob}. */
        default void abortJob(String jobDn) {
            throw new UnsupportedOperationException("abortJob");
        }

        /** {@code GetJobState}; null when the engine will not say. */
        default Vault.JobState jobState(String jobDn) {
            throw new UnsupportedOperationException("jobState");
        }

        /** {@code SubmitCommand}: an XDS document into the subscriber channel of a running driver; the result document (docs/console-gaps.md §10). */
        default String submitCommand(String driverDn, byte[] xds) {
            throw new UnsupportedOperationException("submitCommand");
        }

        void startDriver(String dn);

        void stopDriver(String dn);

        void restartDriver(String dn);

        String waitForState(String dn, int wanted, int seconds);

        Vault.Entry read(String dn);

        /** A read of named attributes only: the way to get an engine-written (no-user-modification) attribute such as {@code DirXML-LogEvents}. */
        default Vault.Entry read(String dn, String... attrs) {
            return read(dn);
        }

        List<Vault.Entry> children(String base);

        void replace(String dn, String attr, List<byte[]> values);

        CachePage viewCache(String dn, int position, int count);

        void deleteCacheEntries(String dn, int p2, int p3, String p4, int priority);

        void migrateApp(String dn, byte[] xds);

        void resync(String dn, long sinceMillis);

        int engineVersion();

        String driverStats(String dn, int arg);

        String jvmStats(int a, int b);

        List<String> namedPasswords(String dn);

        void setNamedPassword(String dn, String name, String displayName, char[] value);

        /** The Remote Loader password and the mutual-authentication key and keystore passwords (docs/console-gaps.md §11). */
        default void setRemoteLoaderPassword(String dn, char[] value) {
            throw new UnsupportedOperationException("setRemoteLoaderPassword");
        }

        default void setMutualAuthKeyPassword(String dn, char[] value) {
            throw new UnsupportedOperationException("setMutualAuthKeyPassword");
        }

        default void setMutualAuthKeystorePassword(String dn, char[] value) {
            throw new UnsupportedOperationException("setMutualAuthKeystorePassword");
        }

        default void clearRemoteLoaderPassword(String dn) {
            throw new UnsupportedOperationException("clearRemoteLoaderPassword");
        }

        default void clearMutualAuthKeyPassword(String dn) {
            throw new UnsupportedOperationException("clearMutualAuthKeyPassword");
        }

        default void clearMutualAuthKeystorePassword(String dn) {
            throw new UnsupportedOperationException("clearMutualAuthKeystorePassword");
        }

        /** {@code SetLogEvents} / {@code ClearLogEvents} (docs/console-gaps.md §11). */
        default void setLogEvents(String dn, int[] eventIds) {
            throw new UnsupportedOperationException("setLogEvents");
        }

        default void clearLogEvents(String dn) {
            throw new UnsupportedOperationException("clearLogEvents");
        }

        void removeNamedPassword(String dn, String name);
    }

    /** Adapts a real, connected {@link Vault} to {@link Engine}. */
    public static Engine vaultEngine(Vault v) {
        return new Engine() {
            @Override
            public String submitCommand(String driverDn, byte[] xds) {
                return v.submitCommand(driverDn, xds);
            }

            public int driverState(String dn) {
                return v.driverState(dn);
            }

            public int driverStartOption(String dn) {
                return v.driverStartOption(dn);
            }

            public void setDriverStartOption(String dn, int option) {
                v.setDriverStartOption(dn, option);
            }

            public List<Vault.Entry> search(String base, String filter, int scope) {
                return v.search(base, filter, scope);
            }

            public void startJob(String jobDn) {
                v.startJob(jobDn);
            }

            public void abortJob(String jobDn) {
                v.abortJob(jobDn);
            }

            public Vault.JobState jobState(String jobDn) {
                return v.jobState(jobDn);
            }

            public void startDriver(String dn) {
                v.startDriver(dn);
            }

            public void stopDriver(String dn) {
                v.stopDriver(dn);
            }

            public void restartDriver(String dn) {
                v.restartDriver(dn);
            }

            public String waitForState(String dn, int wanted, int seconds) {
                return v.waitForState(dn, wanted, seconds);
            }

            public Vault.Entry read(String dn) {
                return v.read(dn);
            }

            public List<Vault.Entry> children(String base) {
                return v.children(base);
            }

            public void replace(String dn, String attr, List<byte[]> values) {
                v.replace(dn, attr, values);
            }

            public CachePage viewCache(String dn, int position, int count) {
                Vault.CachePage p = v.viewCache(dn, position, count);
                return new CachePage(p.xds, p.nextToken);
            }

            public void deleteCacheEntries(String dn, int p2, int p3, String p4, int priority) {
                v.deleteCacheEntries(dn, p2, p3, p4, priority);
            }

            public void migrateApp(String dn, byte[] xds) {
                v.migrateApp(dn, xds);
            }

            public void resync(String dn, long sinceMillis) {
                v.resync(dn, sinceMillis);
            }

            public int engineVersion() {
                return v.engineVersion();
            }

            public String driverStats(String dn, int arg) {
                return v.driverStats(dn, arg);
            }

            public String jvmStats(int a, int b) {
                return v.jvmStats(a, b);
            }

            public List<String> namedPasswords(String dn) {
                return v.namedPasswords(dn);
            }

            public Vault.Entry read(String dn, String... attrs) {
                return v.read(dn, attrs);
            }

            @Override
            public void setRemoteLoaderPassword(String dn, char[] value) {
                v.setRemoteLoaderPassword(dn, value);
            }

            @Override
            public void setMutualAuthKeyPassword(String dn, char[] value) {
                v.setMutualAuthKeyPassword(dn, value);
            }

            @Override
            public void setMutualAuthKeystorePassword(String dn, char[] value) {
                v.setMutualAuthKeystorePassword(dn, value);
            }

            @Override
            public void clearRemoteLoaderPassword(String dn) {
                v.clearRemoteLoaderPassword(dn);
            }

            @Override
            public void clearMutualAuthKeyPassword(String dn) {
                v.clearMutualAuthKeyPassword(dn);
            }

            @Override
            public void clearMutualAuthKeystorePassword(String dn) {
                v.clearMutualAuthKeystorePassword(dn);
            }

            @Override
            public void setLogEvents(String dn, int[] eventIds) {
                v.setLogEvents(dn, eventIds);
            }

            @Override
            public void clearLogEvents(String dn) {
                v.clearLogEvents(dn);
            }

            @Override
            public void setNamedPassword(String dn, String name, String displayName, char[] value) {
                v.setNamedPassword(dn, name, displayName, value);
            }

            public void removeNamedPassword(String dn, String name) {
                v.removeNamedPassword(dn, name);
            }
        };
    }

    // ---- result: text and --json, like Deployer.Result ------------------------------------

    public static final class Result {
        public boolean ok;
        public String text = "";
        public String json = "{}";

        public String text() {
            return text;
        }

        public String json() {
            return json;
        }

        static Result refused(String why) {
            Result r = new Result();
            r.ok = false;
            r.text = "REFUSED — " + why + "\n";
            r.json = "{\"ok\":false,\"refusal\":" + q(why) + "}";
            return r;
        }
    }

    // ---- gating: docs/operate.md "Safeguards" ----------------------------------------------

    enum OpClass {
        READ_ONLY, LIGHT, HEAVY, CACHE_CLEAR
    }

    /** Null when allowed; otherwise the refusal reason. */
    static String gate(Environments.Environment env, OpClass cls, boolean yes, String confirm) {
        if (cls == OpClass.READ_ONLY) {
            return null;
        }
        Environments.Tier t = env.tier;
        boolean needsYes;
        boolean needsConfirm;
        switch (cls) {
            case LIGHT:
                needsYes = t != Environments.Tier.DEV;
                needsConfirm = t == Environments.Tier.PRD;
                break;
            case HEAVY:
                needsYes = true;
                needsConfirm = t == Environments.Tier.PRD;
                break;
            case CACHE_CLEAR:
                needsYes = true;
                needsConfirm = t == Environments.Tier.STG || t == Environments.Tier.PRD;
                break;
            default:
                needsYes = false;
                needsConfirm = false;
        }
        if (needsYes && !yes) {
            return "'" + env.name + "' (" + t.name().toLowerCase() + ") needs --yes for this operation";
        }
        if (needsConfirm && !env.name.equals(confirm)) {
            return "'" + env.name + "' (" + t.name().toLowerCase() + ") needs --confirm " + env.name + " for this operation";
        }
        return null;
    }

    // ---- driverset.status / driver.status --------------------------------------------------

    public static Result driversetStatus(Engine engine, Environments.Environment env) {
        List<Vault.Entry> drivers = new ArrayList<>();
        for (Vault.Entry e : engine.children(env.driverSetDn)) {
            if (e.hasClass("DirXML-Driver")) {
                drivers.add(e);
            }
        }
        drivers.sort(Comparator.comparing(Operate::nameOf, String.CASE_INSENSITIVE_ORDER));

        StringBuilder text = new StringBuilder();
        text.append(String.format("%-30s %-10s %-16s %6s %6s %-6s%n",
            "DRIVER", "STATE", "START", "CACHE B", "UNPR B", "TRACE"));
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < drivers.size(); i++) {
            Vault.Entry d = drivers.get(i);
            String name = nameOf(d);
            String dn = d.dn;
            int state = engine.driverState(dn);
            int startOption = engine.driverStartOption(dn);
            String[] sizes = parseCacheSizes(safeDriverStats(engine, dn));
            String traceLevel = d.string(Vault.TRACE_LEVEL);
            text.append(String.format("%-30s %-10s %-16s %6s %6s %-6s%n",
                name, Vault.stateName(state), startOptionName(startOption), sizes[0], sizes[1],
                traceLevel == null ? "-" : traceLevel));
            json.append(i == 0 ? "" : ",")
                .append("{\"name\":").append(q(name)).append(",\"dn\":").append(q(dn))
                .append(",\"state\":").append(q(Vault.stateName(state)))
                .append(",\"startOption\":").append(q(startOptionName(startOption)))
                .append(",\"cacheSize\":").append(q(sizes[0])).append(",\"unprocessedSize\":").append(q(sizes[1]))
                .append(",\"traceLevel\":").append(jn(traceLevel)).append('}');
        }
        json.append(']');

        Result r = new Result();
        r.ok = true;
        r.text = text.toString();
        r.json = json.toString();
        return r;
    }

    public static Result driverStatus(Engine engine, Environments.Environment env, String driver, Path tree) throws IOException {
        String dn = driverDn(env, driver);
        Vault.Entry entry = engine.read(dn);
        if (entry == null) {
            return Result.refused("no such driver '" + driver + "' under " + env.driverSetDn);
        }
        int state = engine.driverState(dn);
        int startOption = engine.driverStartOption(dn);
        String[] sizes = parseCacheSizes(safeDriverStats(engine, dn));
        String traceLevel = entry.string(Vault.TRACE_LEVEL);
        String traceFile = entry.string(Vault.TRACE_FILE);
        List<String> names = engine.namedPasswords(dn);
        List<DeployLog.Record> audit = lastAuditFor(tree, env.name, driver, 5);

        StringBuilder text = new StringBuilder();
        text.append(driver).append('\n');
        text.append("  dn              ").append(dn).append('\n');
        text.append("  state           ").append(Vault.stateName(state)).append('\n');
        text.append("  start option    ").append(startOptionName(startOption)).append('\n');
        text.append("  cache size      ").append(sizes[0]).append('\n');
        text.append("  unprocessed     ").append(sizes[1]).append('\n');
        text.append("  trace level     ").append(traceLevel == null ? "-" : traceLevel).append('\n');
        text.append("  trace file      ").append(traceFile == null ? "-" : traceFile).append('\n');
        text.append("  named passwords ").append(names.isEmpty() ? "(none)" : String.join(", ", names)).append('\n');
        text.append("  recent audit:\n");
        if (audit.isEmpty()) {
            text.append("    (none)\n");
        } else {
            for (DeployLog.Record rec : audit) {
                text.append("    ").append(rec.timestamp).append("  ").append(rec.outcome).append("  ").append(rec.detail).append('\n');
            }
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"name\":").append(q(driver)).append(",\"dn\":").append(q(dn));
        json.append(",\"state\":").append(q(Vault.stateName(state)));
        json.append(",\"startOption\":").append(q(startOptionName(startOption)));
        json.append(",\"cacheSize\":").append(q(sizes[0])).append(",\"unprocessedSize\":").append(q(sizes[1]));
        json.append(",\"traceLevel\":").append(jn(traceLevel));
        json.append(",\"traceFile\":").append(jn(traceFile));
        json.append(",\"namedPasswords\":").append(strArr(names));
        json.append(",\"audit\":[");
        for (int i = 0; i < audit.size(); i++) {
            DeployLog.Record rec = audit.get(i);
            json.append(i == 0 ? "" : ",").append("{\"timestamp\":").append(q(rec.timestamp))
                .append(",\"outcome\":").append(jn(rec.outcome)).append(",\"detail\":").append(jn(rec.detail)).append('}');
        }
        json.append("]}");

        Result res = new Result();
        res.ok = true;
        res.text = text.toString();
        res.json = json.toString();
        return res;
    }

    private static String safeDriverStats(Engine engine, String dn) {
        try {
            return engine.driverStats(dn, 0);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static List<DeployLog.Record> lastAuditFor(Path tree, String env, String driver, int limit) throws IOException {
        List<DeployLog.Record> all = DeployLog.read(tree, env);
        List<DeployLog.Record> out = new ArrayList<>();
        String marker = "'" + driver + "':";
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            DeployLog.Record r = all.get(i);
            if ("operate".equals(r.operation) && r.detail != null && r.detail.contains(marker)) {
                out.add(r);
            }
        }
        return out;
    }

    private static String nameOf(Vault.Entry e) {
        String cn = e.string("cn");
        return cn != null ? cn : e.dn;
    }

    private static String startOptionName(int o) {
        switch (o) {
            case Vault.START_DISABLED: return "disabled";
            case Vault.START_MANUAL: return "manual";
            case Vault.START_AUTO: return "auto";
            case 3: return "overflow-manual";
            case 4: return "overflow-auto";
            default: return "option-" + o;
        }
    }

    // ---- driver.start | stop | restart -------------------------------------------------------

    public static Result lifecycle(Engine engine, Environments.Environment env, String driver, String action,
            int waitSeconds, boolean yes, String confirm, Path tree) throws IOException {
        String dn = driverDn(env, driver);
        OpClass cls = "stop".equals(action) ? OpClass.HEAVY : OpClass.LIGHT;
        String refusal = gate(env, cls, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        int before = engine.driverState(dn);
        int wanted = "stop".equals(action) ? Vault.STATE_STOPPED : Vault.STATE_RUNNING;
        String seen = "";
        String error = null;
        try {
            switch (action) {
                case "start": engine.startDriver(dn); break;
                case "stop": engine.stopDriver(dn); break;
                case "restart": engine.restartDriver(dn); break;
                default: throw new IllegalArgumentException("unknown action '" + action + "'");
            }
            seen = engine.waitForState(dn, wanted, waitSeconds);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        int after = engine.driverState(dn);
        boolean ok = error == null;

        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = ok ? "ok" : "failed";
        rec.detail = "driver." + action + " '" + driver + "': " + Vault.stateName(before) + " → " + Vault.stateName(after)
            + (seen != null && !seen.isEmpty() ? " (" + seen + ")" : "") + (error != null ? " — " + error : "");
        if (ok) {
            rec.restarted.add(driver);
        }
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = ok;
        r.text = "driver." + action + " '" + driver + "': " + Vault.stateName(before) + " → " + Vault.stateName(after) + "\n"
            + (seen != null && !seen.isEmpty() ? "  states seen: " + seen + "\n" : "")
            + (error != null ? "FAILED   " + error + "\n" : "OK\n");
        r.json = "{\"ok\":" + ok + ",\"driver\":" + q(driver) + ",\"before\":" + q(Vault.stateName(before))
            + ",\"after\":" + q(Vault.stateName(after)) + ",\"seen\":" + q(seen == null ? "" : seen)
            + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    // ---- driver.start-option (G10) ------------------------------------------------------------

    /** The start option as the CLI names it → the vault's number; -1 when not a name. */
    static int startOptionOf(String name) {
        switch (name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "auto": return Vault.START_AUTO;
            case "manual": return Vault.START_MANUAL;
            case "disabled": return Vault.START_DISABLED;
            default: return -1;
        }
    }

    /** Set a driver's start option live ({@code SetDriverStartOption}): a light write, gated and audited like start/stop. */
    public static Result startOption(Engine engine, Environments.Environment env, String driver, String option,
            boolean yes, String confirm, Path tree) throws IOException {
        int wanted = startOptionOf(option);
        if (wanted < 0) {
            return Result.refused("start option is auto, manual or disabled, not '" + option + "'");
        }
        String dn = driverDn(env, driver);
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        int before = engine.driverStartOption(dn);
        String error = null;
        try {
            engine.setDriverStartOption(dn, wanted);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        int after = engine.driverStartOption(dn);
        boolean ok = error == null && after == wanted;
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = ok ? "ok" : "failed";
        rec.detail = "driver.start-option '" + driver + "': " + startOptionName(before) + " → " + startOptionName(after) + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = ok;
        r.text = "driver.start-option '" + driver + "': " + startOptionName(before) + " → " + startOptionName(after) + "\n" + (error != null ? "FAILED   " + error + "\n" : ok ? "OK\n" : "FAILED   the vault still says " + startOptionName(after) + "\n");
        r.json = "{\"ok\":" + ok + ",\"driver\":" + q(driver) + ",\"before\":" + q(startOptionName(before)) + ",\"after\":" + q(startOptionName(after)) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    // ---- object.inspect, driver.associations, driver.password-sync (G3, G4) --------------------

    /** The association states eDirectory records on {@code DirXML-Associations}. */
    static String associationState(int state) {
        switch (state) {
            case 0: return "disabled";
            case 1: return "processed";
            case 2: return "pending";
            case 3: return "manual";
            case 4: return "migrate";
            default: return "state-" + state;
        }
    }

    static int associationStateOf(String name) {
        switch (name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "disabled": return 0;
            case "processed": return 1;
            case "pending": return 2;
            case "manual": return 3;
            case "migrate": return 4;
            default: return -1;
        }
    }

    /** One {@code DirXML-Associations} value: {@code <driver dn>#<state>#<value>}. */
    static final class Association {
        final String driverDn;
        final int state;
        final String value;

        Association(String driverDn, int state, String value) {
            this.driverDn = driverDn;
            this.state = state;
            this.value = value;
        }

        String driverName() {
            return driverDn.replaceFirst("^[^=]+=", "").replaceFirst(",.*$", "");
        }
    }

    /** {@code <driver dn>#<state>#<value>}; null when the value is not of that shape. */
    static Association parseAssociation(String raw) {
        if (raw == null) {
            return null;
        }
        int a = raw.indexOf('#');
        int b = a < 0 ? -1 : raw.indexOf('#', a + 1);
        if (a < 0 || b < 0) {
            return null;
        }
        int state;
        try {
            state = Integer.parseInt(raw.substring(a + 1, b).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return new Association(raw.substring(0, a), state, raw.substring(b + 1));
    }

    /** One {@code DirXML-PasswordSyncStatus} value: {@code <driver dn>#<yyyyMMddHHmmss…>#<status>} (the engine's own form). */
    static String[] parsePasswordSync(String raw) {
        if (raw == null) {
            return null;
        }
        int a = raw.indexOf('#');
        int b = a < 0 ? -1 : raw.indexOf('#', a + 1);
        if (a < 0 || b < 0) {
            return new String[] { raw, "", "" };
        }
        return new String[] { raw.substring(0, a), raw.substring(a + 1, b), raw.substring(b + 1) };
    }

    /** What the vault says about one object: its classes, its associations across drivers, its password-sync status. Read-only. */
    public static Result inspectObject(Engine engine, Environments.Environment env, String dn) {
        Vault.Entry e = engine.read(dn);
        Result r = new Result();
        if (e == null) {
            r.ok = false;
            r.text = "no object " + dn + "\n";
            r.json = "{\"ok\":false,\"dn\":" + q(dn) + ",\"error\":\"not found\"}";
            return r;
        }
        StringBuilder t = new StringBuilder();
        StringBuilder j = new StringBuilder();
        t.append(dn).append("\n");
        List<String> classes = e.strings("objectClass");
        t.append("  class           ").append(String.join(", ", classes)).append("\n");
        j.append("{\"ok\":true,\"dn\":").append(q(dn)).append(",\"objectClass\":[");
        for (int i = 0; i < classes.size(); i++) {
            j.append(i > 0 ? "," : "").append(q(classes.get(i)));
        }
        j.append("],\"associations\":[");
        List<String> assoc = e.strings("DirXML-Associations");
        t.append("  associations    ").append(assoc.size()).append("\n");
        int n = 0;
        for (String raw : assoc) {
            Association a = parseAssociation(raw);
            if (a == null) {
                t.append("    ").append(raw).append("\n");
                j.append(n++ > 0 ? "," : "").append("{\"raw\":").append(q(raw)).append("}");
                continue;
            }
            t.append("    ").append(String.format("%-28s %-10s %s", a.driverName(), associationState(a.state), a.value)).append("\n");
            j.append(n++ > 0 ? "," : "").append("{\"driver\":").append(q(a.driverName())).append(",\"driverDn\":").append(q(a.driverDn))
             .append(",\"state\":").append(q(associationState(a.state))).append(",\"value\":").append(q(a.value)).append("}");
        }
        j.append("],\"passwordSync\":[");
        List<String> ps = e.strings("DirXML-PasswordSyncStatus");
        t.append("  password sync   ").append(ps.isEmpty() ? "no status recorded" : ps.size() + " status value(s)").append("\n");
        n = 0;
        for (String raw : ps) {
            String[] p = parsePasswordSync(raw);
            String drv = p[0].replaceFirst("^[^=]+=", "").replaceFirst(",.*$", "");
            t.append("    ").append(String.format("%-28s %-16s %s", drv, p[1], p[2])).append("\n");
            j.append(n++ > 0 ? "," : "").append("{\"driver\":").append(q(drv)).append(",\"driverDn\":").append(q(p[0])).append(",\"time\":").append(q(p[1])).append(",\"status\":").append(q(p[2])).append("}");
        }
        j.append("]}");
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /**
     * The objects associated with a driver, by state ({@code DirXML-Associations=<driver>#<state>#*}
     * — eDirectory indexes the path syntax that way; a bare {@code #*} is refused). Read-only.
     */
    public static Result driverAssociations(Engine engine, Environments.Environment env, String driver, String stateName, String base, int limit) {
        String dn = driverDn(env, driver);
        int only = stateName == null || stateName.isBlank() ? -1 : associationStateOf(stateName);
        if (stateName != null && !stateName.isBlank() && only < 0) {
            return Result.refused("state is processed, disabled, pending, manual or migrate, not '" + stateName + "'");
        }
        StringBuilder t = new StringBuilder();
        StringBuilder j = new StringBuilder();
        t.append("associations of '").append(driver).append("'").append(base == null || base.isBlank() ? "" : " under " + base).append("\n");
        j.append("{\"ok\":true,\"driver\":").append(q(driver)).append(",\"counts\":{");
        int total = 0;
        StringBuilder rows = new StringBuilder();
        StringBuilder jrows = new StringBuilder();
        int listed = 0;
        for (int state = 0; state <= 4; state++) {
            if (only >= 0 && state != only) {
                continue;
            }
            List<Vault.Entry> found = engine.search(base == null ? "" : base, "(DirXML-Associations=" + dn + "#" + state + "#*)", javax.naming.directory.SearchControls.SUBTREE_SCOPE);
            j.append(state > 0 && j.charAt(j.length() - 1) != '{' ? "," : "").append(q(associationState(state))).append(":").append(found.size());
            t.append("  ").append(String.format("%-10s %d", associationState(state), found.size())).append("\n");
            total += found.size();
            for (Vault.Entry e : found) {
                if (listed >= limit) {
                    break;
                }
                String value = "";
                for (String raw : e.strings("DirXML-Associations")) {
                    Association a = parseAssociation(raw);
                    if (a != null && a.driverDn.equalsIgnoreCase(dn)) {
                        value = a.value;
                        break;
                    }
                }
                rows.append("    ").append(String.format("%-10s %-50s %s", associationState(state), e.dn, value)).append("\n");
                jrows.append(listed > 0 ? "," : "").append("{\"dn\":").append(q(e.dn)).append(",\"state\":").append(q(associationState(state))).append(",\"value\":").append(q(value)).append("}");
                listed++;
            }
        }
        t.append("  total      ").append(total).append(listed < total ? " (first " + listed + " listed; --limit N for more)" : "").append("\n");
        if (rows.length() > 0) {
            t.append(rows);
        }
        j.append("},\"total\":").append(total).append(",\"listed\":").append(listed).append(",\"objects\":[").append(jrows).append("]}");
        Result r = new Result();
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /**
     * Password synchronisation as the vault has it for a driver: the driver set's
     * {@code DirXML-PasswordSyncTimeout} and every password-related GCV of the driver's live
     * configuration ({@code DirXML-ConfigValues}, by name). Read-only.
     */
    public static Result passwordSync(Engine engine, Environments.Environment env, String driver) {
        String dn = driverDn(env, driver);
        Vault.Entry ds = engine.read(env.driverSetDn);
        Vault.Entry d = engine.read(dn);
        Result r = new Result();
        if (d == null) {
            r.ok = false;
            r.text = "no driver " + dn + "\n";
            r.json = "{\"ok\":false,\"error\":\"no driver\"}";
            return r;
        }
        String timeout = ds == null ? "" : String.join(",", ds.strings("DirXML-PasswordSyncTimeout"));
        StringBuilder t = new StringBuilder();
        StringBuilder j = new StringBuilder();
        t.append("password sync of '").append(driver).append("'\n");
        t.append("  driver set timeout   ").append(timeout.isEmpty() ? "not set (the engine's default)" : timeout + " min").append("\n");
        j.append("{\"ok\":true,\"driver\":").append(q(driver)).append(",\"driverSetTimeout\":").append(q(timeout)).append(",\"settings\":[");
        String xml = d.string("DirXML-ConfigValues");
        int n = 0;
        if (xml != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<definition[^>]*\\sname=\"([^\"]*[Pp]assword[^\"]*)\"[^>]*>(.*?)</definition>", java.util.regex.Pattern.DOTALL).matcher(xml);
            while (m.find()) {
                String name = m.group(1);
                java.util.regex.Matcher v = java.util.regex.Pattern.compile("<value>(.*?)</value>", java.util.regex.Pattern.DOTALL).matcher(m.group(2));
                String value = v.find() ? v.group(1).trim() : "";
                t.append("  ").append(String.format("%-44s %s", name, value)).append("\n");
                j.append(n++ > 0 ? "," : "").append("{\"name\":").append(q(name)).append(",\"value\":").append(q(value)).append("}");
            }
        }
        if (n == 0) {
            t.append("  no password-related settings in the driver's live configuration\n");
        }
        j.append("]}");
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    // ---- job.list | status | start | abort (docs/console-gaps.md §1) --------------------------

    /** The engine's running-state code of a job as a word: 0 is not running, 1 running (the codes `GetJobState` returns). */
    static String jobRunningState(int code) {
        switch (code) {
            case 0: return "not running";
            case 1: return "running";
            default: return "state-" + code;
        }
    }

    static String jobConfigState(int code) {
        switch (code) {
            case 0: return "ok";
            default: return "config-" + code;
        }
    }

    /** {@code cn=<job>,<driver dn>} or {@code cn=<job>,<driver set dn>} when no driver is named. */
    static String jobDn(Environments.Environment env, String job, String driver) {
        return VaultMapping.jobDn(env.driverSetDn, driver == null || driver.isBlank() ? null : driver, job);
    }

    private static String owner(String jobDn, Environments.Environment env) {
        String parent = jobDn.substring(jobDn.indexOf(',') + 1);
        return parent.equalsIgnoreCase(env.driverSetDn) ? "driver set" : parent.replaceFirst("^[^=]+=", "").replaceFirst(",.*$", "");
    }

    /** Every job of the driver set (or of one driver) with what the engine says about it. Read-only. */
    public static Result jobList(Engine engine, Environments.Environment env, String driver) {
        String base = driver == null || driver.isBlank() ? env.driverSetDn : driverDn(env, driver);
        List<Vault.Entry> found = engine.search(base, "(objectClass=DirXML-Job)", javax.naming.directory.SearchControls.SUBTREE_SCOPE);
        StringBuilder t = new StringBuilder();
        StringBuilder j = new StringBuilder("{\"ok\":true,\"jobs\":[");
        t.append(String.format("%-28s %-26s %-12s %-10s %-9s %s", "job", "owner", "running", "config", "scheduled", "next run")).append('\n');
        int n = 0;
        for (Vault.Entry e : found) {
            String name = e.dn.substring(e.dn.indexOf('=') + 1, e.dn.indexOf(','));
            String own = owner(e.dn, env);
            com.pointblue.dirxml.dev.model.Job job = new com.pointblue.dirxml.dev.model.Job(name, parseOrNull(e.string("XmlData")));
            String running = "?";
            String config = "?";
            String scheduled = "?";
            String next = "";
            try {
                Vault.JobState st = engine.jobState(e.dn);
                if (st != null) {
                    running = jobRunningState(st.runningState);
                    config = jobConfigState(st.configurationState);
                    scheduled = st.scheduled ? "yes" : "no";
                    next = st.nextRun == null ? "" : st.nextRun.toInstant().toString();
                }
            } catch (RuntimeException ex) {
                running = "unknown (" + ex.getMessage() + ")";
            }
            t.append(String.format("%-28s %-26s %-12s %-10s %-9s %s", name, own, running, config, scheduled, next)).append('\n');
            if (job.disabled()) {
                t.append("    disabled in its configuration\n");
            }
            j.append(n++ > 0 ? "," : "").append("{\"name\":").append(q(name)).append(",\"dn\":").append(q(e.dn)).append(",\"owner\":").append(q(own))
             .append(",\"driver\":").append(own.equals("driver set") ? "null" : q(own)).append(",\"javaClass\":").append(q(job.javaClass() == null ? "" : job.javaClass()))
             .append(",\"disabled\":").append(job.disabled()).append(",\"servers\":").append(e.strings("DirXML-ServerList").size())
             .append(",\"running\":").append(q(running)).append(",\"config\":").append(q(config)).append(",\"scheduled\":").append(q(scheduled)).append(",\"nextRun\":").append(q(next)).append("}");
        }
        if (n == 0) {
            t.append("  no jobs\n");
        }
        j.append("]}");
        Result r = new Result();
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /**
     * The role-based entitlement policies of the driver set (docs/console-gaps.md §9): the set's
     * {@code DirXML-SharedProfileSet} container's order, each {@code DirXML-SharedProfile} with its membership
     * query, static members, the entitlements it grants and the member count the directory computes.
     * Read-only.
     */
    public static Result rbeList(Engine engine, Environments.Environment env) {
        List<Vault.Entry> sets = engine.search(env.driverSetDn, "(objectClass=DirXML-SharedProfileSet)", javax.naming.directory.SearchControls.ONELEVEL_SCOPE);
        StringBuilder t = new StringBuilder();
        StringBuilder j = new StringBuilder("{\"ok\":true,\"policies\":[");
        Result r = new Result();
        r.ok = true;
        if (sets.isEmpty()) {
            r.text = "  no entitlement policy container under " + env.driverSetDn + "\n";
            r.json = "{\"ok\":true,\"container\":null,\"policies\":[]}";
            return r;
        }
        Vault.Entry set = sets.get(0);
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (String tn : set.strings("DirXML-SPPriority")) {
            String[] parts = tn.split("#", 3);
            if (parts.length >= 2) {
                try {
                    levels.put(parts[0].toLowerCase(), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ex) {
                    // unordered
                }
            }
        }
        List<Vault.Entry> found = engine.search(set.dn, "(objectClass=DirXML-SharedProfile)", javax.naming.directory.SearchControls.ONELEVEL_SCOPE);
        found.sort(java.util.Comparator.comparing((Vault.Entry e) -> levels.getOrDefault(e.dn.toLowerCase(), Integer.MAX_VALUE)).thenComparing(e -> e.dn, String.CASE_INSENSITIVE_ORDER));
        t.append("container ").append(set.dn).append('\n');
        t.append(String.format("%-8s %-32s %-8s %-8s %s", "priority", "policy", "members", "grants", "membership")).append('\n');
        int n = 0;
        for (Vault.Entry e : found) {
            String name = e.dn.substring(e.dn.indexOf('=') + 1, e.dn.indexOf(','));
            Integer level = levels.get(e.dn.toLowerCase());
            List<String> members = e.strings("Member");
            List<String> refs = e.strings("DirXML-EntitlementRef");
            String query = e.string("memberQueryURL");
            String membership = query != null && !query.isBlank() ? query : (members.isEmpty() ? "none" : "static only");
            t.append(String.format("%-8s %-32s %-8d %-8d %s", level == null ? "-" : level, name, members.size(), refs.size(), membership)).append('\n');
            for (String ref : refs) {
                t.append("    grants ").append(com.pointblue.dirxml.dev.model.EntitlementPolicy.refDn(ref));
                String xml = com.pointblue.dirxml.dev.model.EntitlementPolicy.refXml(ref);
                if (!xml.equals("<ref/>")) {
                    t.append("  ").append(xml);
                }
                t.append('\n');
            }
            j.append(n++ > 0 ? "," : "").append("{\"name\":").append(q(name)).append(",\"dn\":").append(q(e.dn))
             .append(",\"priority\":").append(level == null ? "null" : level).append(",\"members\":").append(members.size())
             .append(",\"memberQuery\":").append(q(query == null ? "" : query)).append(",\"grants\":[");
            int g = 0;
            for (String ref : refs) {
                j.append(g++ > 0 ? "," : "").append("{\"entitlement\":").append(q(com.pointblue.dirxml.dev.model.EntitlementPolicy.refDn(ref)))
                 .append(",\"ref\":").append(q(com.pointblue.dirxml.dev.model.EntitlementPolicy.refXml(ref))).append("}");
            }
            j.append("]}");
        }
        if (n == 0) {
            t.append("  no policies\n");
        }
        j.append("],\"container\":").append(q(set.dn)).append("}");
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /** The members the directory computes for one policy (its static members plus the membership query's). Read-only. */
    public static Result rbeMembers(Engine engine, Environments.Environment env, String policy) {
        List<Vault.Entry> sets = engine.search(env.driverSetDn, "(objectClass=DirXML-SharedProfileSet)", javax.naming.directory.SearchControls.ONELEVEL_SCOPE);
        Result r = new Result();
        if (sets.isEmpty()) {
            r.ok = false;
            r.text = "no entitlement policy container under " + env.driverSetDn;
            r.json = "{\"ok\":false,\"error\":" + q(r.text) + "}";
            return r;
        }
        String dn = "cn=" + policy + "," + sets.get(0).dn;
        Vault.Entry e = engine.read(dn);
        if (e == null) {
            r.ok = false;
            r.text = "no policy " + dn;
            r.json = "{\"ok\":false,\"error\":" + q(r.text) + "}";
            return r;
        }
        List<String> members = e.strings("Member");
        List<String> statics = new ArrayList<>();
        StringBuilder t = new StringBuilder(dn).append(": ").append(members.size()).append(" member(s)\n");
        StringBuilder j = new StringBuilder("{\"ok\":true,\"dn\":").append(q(dn)).append(",\"members\":[");
        int n = 0;
        for (String m : members) {
            t.append("  ").append(m).append('\n');
            j.append(n++ > 0 ? "," : "").append(q(m));
        }
        j.append("]}");
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    // ---- G9: query the connected system through the driver ---------------------------------------

    /** What {@link #driverQuery} asks: the class, the scope, a DN or an association to start from, search attributes, the attributes to read. */
    public static final class Query {
        public String className;
        /** {@code subtree} (the default), {@code subordinates} or {@code entry}. */
        public String scope = "subtree";
        public String destDn;
        public String association;
        /** {@code name=value} search attributes. */
        public final List<String> searchAttrs = new ArrayList<>();
        /** Attributes to read; empty reads every attribute, {@code none} reads none (a match test). */
        public final List<String> readAttrs = new ArrayList<>();

        /** The {@code <query>} document the engine's query verb takes (NDS DTD 4.0). */
        public String xds() {
            StringBuilder sb = new StringBuilder("<nds dtdversion=\"4.0\" ndsversion=\"8.x\"><source><product>DirXMLDev</product><contact>Point Blue</contact></source><input>");
            sb.append("<query event-id=\"dirxmldev-query\" scope=\"").append(xmlAttr(scope)).append('"');
            if (className != null && !className.isBlank()) {
                sb.append(" class-name=\"").append(xmlAttr(className)).append('"');
            }
            if (destDn != null && !destDn.isBlank()) {
                sb.append(" dest-dn=\"").append(xmlAttr(slashDn(destDn))).append('"');
            }
            sb.append('>');
            if (association != null && !association.isBlank()) {
                sb.append("<association>").append(xmlText(association)).append("</association>");
            }
            if (className != null && !className.isBlank() && !"entry".equals(scope)) {
                sb.append("<search-class class-name=\"").append(xmlAttr(className)).append("\"/>");
            }
            for (String sa : searchAttrs) {
                int eq = sa.indexOf('=');
                String n = eq < 0 ? sa : sa.substring(0, eq);
                String v = eq < 0 ? "" : sa.substring(eq + 1);
                sb.append("<search-attr attr-name=\"").append(xmlAttr(n)).append("\"><value>").append(xmlText(v)).append("</value></search-attr>");
            }
            if (readAttrs.size() == 1 && "none".equalsIgnoreCase(readAttrs.get(0))) {
                sb.append("<read-attr/>");
            } else {
                for (String ra : readAttrs) {
                    sb.append("<read-attr attr-name=\"").append(xmlAttr(ra)).append("\"/>");
                }
            }
            sb.append("</query></input></nds>");
            return sb.toString();
        }
    }

    /**
     * Ask the connected system a question through the driver (the console's {@code queryValues},
     * docs/console-gaps.md §10): the {@code <query>} goes down the subscriber channel of the running
     * driver as a command, the shim answers with {@code <instance>} elements. A read of the application
     * that occupies the driver's channel: gated light and audited.
     */
    public static Result driverQuery(Engine engine, Environments.Environment env, String driver, Query q,
            boolean yes, String confirm, Path tree) throws IOException {
        String dn = driverDn(env, driver);
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        int state = engine.driverState(dn);
        if (state != Vault.STATE_RUNNING) {
            return Result.refused("driver '" + driver + "' is " + Vault.stateName(state) + "; a query needs a running driver");
        }
        String xds = q.xds();
        String answer = null;
        String error = null;
        try {
            answer = engine.submitCommand(dn, xds.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.query '" + driver + "': " + (q.className == null ? "any class" : q.className) + " " + q.scope
            + (q.destDn == null ? "" : " from " + q.destDn) + (q.association == null ? "" : " association " + q.association)
            + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        if (error != null) {
            r.ok = false;
            r.text = "FAILED   " + error + "\n";
            r.json = "{\"ok\":false,\"error\":" + q(error) + "}";
            return r;
        }
        List<QueryInstance> instances = parseInstances(answer);
        String status = queryStatus(answer);
        StringBuilder t = new StringBuilder();
        StringBuilder j = new StringBuilder("{\"ok\":true,\"driver\":").append(q(driver)).append(",\"count\":").append(instances.size()).append(",\"instances\":[");
        int n = 0;
        for (QueryInstance in : instances) {
            t.append(in.className == null ? "instance" : in.className);
            if (in.srcDn != null) {
                t.append("  ").append(in.srcDn);
            }
            if (in.association != null) {
                t.append("  [").append(in.association).append(']');
            }
            t.append('\n');
            j.append(n++ > 0 ? "," : "").append("{\"className\":").append(q(in.className == null ? "" : in.className)).append(",\"srcDn\":").append(q(in.srcDn == null ? "" : in.srcDn))
             .append(",\"association\":").append(q(in.association == null ? "" : in.association)).append(",\"attrs\":{");
            int a = 0;
            for (Map.Entry<String, List<String>> en : in.attrs.entrySet()) {
                t.append("    ").append(en.getKey()).append(" = ").append(String.join(" | ", en.getValue())).append('\n');
                j.append(a++ > 0 ? "," : "").append(q(en.getKey())).append(":[");
                for (int i = 0; i < en.getValue().size(); i++) {
                    j.append(i > 0 ? "," : "").append(q(en.getValue().get(i)));
                }
                j.append(']');
            }
            j.append("}}");
        }
        if (n == 0) {
            t.append("  no instances\n");
        }
        if (status != null && !status.isBlank()) {
            t.append("status: ").append(status).append('\n');
        }
        j.append("],\"status\":").append(q(status == null ? "" : status)).append(",\"xds\":").append(q(xds)).append("}");
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /**
     * The engine's query verb takes DNs in slash form ({@code data\\users\\jdoe}); an LDAP DN
     * ({@code cn=jdoe,ou=users,o=data}) is converted, one given in slash form is kept.
     */
    public static String slashDn(String dn) {
        if (dn == null || dn.indexOf('=') < 0) {
            return dn;
        }
        List<String> values = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        for (char ch : dn.toCharArray()) {
            if (escaped) {
                cur.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == ',') {
                values.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        values.add(cur.toString());
        StringBuilder sb = new StringBuilder();
        for (int i = values.size() - 1; i >= 0; i--) {
            String v = values.get(i).trim();
            int eq = v.indexOf('=');
            if (eq >= 0) {
                v = v.substring(eq + 1).trim();
            }
            if (v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\\');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** One {@code <instance>} of a query answer. */
    public static final class QueryInstance {
        public String className;
        public String srcDn;
        public String association;
        public final Map<String, List<String>> attrs = new LinkedHashMap<>();
    }

    /** The {@code <instance>} elements of a result document, in order; empty when it has none or does not parse. */
    public static List<QueryInstance> parseInstances(String xml) {
        List<QueryInstance> out = new ArrayList<>();
        org.w3c.dom.Element root = parseOrNull(xml);
        if (root == null) {
            return out;
        }
        for (org.w3c.dom.Element in : com.pointblue.dirxml.sim.Xds.descendantsByName(root, "instance")) {
            QueryInstance qi = new QueryInstance();
            qi.className = emptyToNull(in.getAttribute("class-name"));
            qi.srcDn = emptyToNull(in.getAttribute("src-dn"));
            for (org.w3c.dom.Element a : com.pointblue.dirxml.sim.Xds.childrenByName(in, "association")) {
                qi.association = emptyToNull(com.pointblue.dirxml.sim.Xds.text(a).trim());
            }
            for (org.w3c.dom.Element a : com.pointblue.dirxml.sim.Xds.childrenByName(in, "attr")) {
                List<String> values = new ArrayList<>();
                for (org.w3c.dom.Element v : com.pointblue.dirxml.sim.Xds.childrenByName(a, "value")) {
                    values.add(com.pointblue.dirxml.sim.Xds.text(v));
                }
                qi.attrs.put(a.getAttribute("attr-name"), values);
            }
            out.add(qi);
        }
        return out;
    }

    /** The {@code <status>} of a result document as {@code level: description}, or null. */
    static String queryStatus(String xml) {
        org.w3c.dom.Element root = parseOrNull(xml);
        if (root == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (org.w3c.dom.Element st : com.pointblue.dirxml.sim.Xds.descendantsByName(root, "status")) {
            String level = st.getAttribute("level");
            String desc = com.pointblue.dirxml.sim.Xds.text(st).trim();
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(level.isEmpty() ? "?" : level).append(desc.isEmpty() ? "" : ": " + desc.replaceAll("\\s+", " "));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String xmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String xmlAttr(String s) {
        return xmlText(s).replace("\"", "&quot;");
    }

    // ---- G7: driver health -----------------------------------------------------------------------

    /** The attribute the Driver Health job writes its last evaluation to, on the driver object (aux class {@code DirXML-uiExtensions}). */
    public static final String HEALTH_STATUS_ATTR = "DirXML-uiXMLSmall";
    /** The job class whose job holds the schedule and the login the checks run as. */
    public static final String HEALTH_JOB_CLASS = "com.novell.nds.dirxml.job.ckdrvhealth.CheckDriverHealthJob";

    /**
     * A driver's health (docs/console-gaps.md §10): the last state the Driver Health job recorded per server
     * ({@code DirXML-uiXMLSmall}: {@code <dirxml-ui><health-config-status><last-state><driver dn><server dn
     * last-state="green|yellow|red"/>}, plus each custom state's true/false), the health configuration on the
     * driver's manifest ({@code DirXML-ConfigManifest}'s {@code <health-config>}: the states and their actions),
     * and the set's Driver Health jobs with whether this driver is in their scope. Read-only.
     */
    public static Result driverHealth(Engine engine, Environments.Environment env, String driver) {
        String dn = driverDn(env, driver);
        Vault.Entry e = engine.read(dn);
        if (e == null) {
            return Result.refused("no such driver '" + driver + "' under " + env.driverSetDn);
        }
        StringBuilder t = new StringBuilder(driver).append('\n');
        StringBuilder j = new StringBuilder("{\"ok\":true,\"driver\":").append(q(driver)).append(",\"dn\":").append(q(dn));
        // 1. the last evaluation
        org.w3c.dom.Element status = parseOrNull(e.string(HEALTH_STATUS_ATTR));
        j.append(",\"states\":[");
        int n = 0;
        if (status == null) {
            t.append("  health status   none recorded (the Driver Health job has not evaluated this driver)\n");
        } else {
            for (org.w3c.dom.Element hcs : com.pointblue.dirxml.sim.Xds.childrenByName(status, "health-config-status")) {
                for (org.w3c.dom.Element last : com.pointblue.dirxml.sim.Xds.childrenByName(hcs, "last-state")) {
                    for (org.w3c.dom.Element d : com.pointblue.dirxml.sim.Xds.childrenByName(last, "driver")) {
                        for (org.w3c.dom.Element s : com.pointblue.dirxml.sim.Xds.childrenByName(d, "server")) {
                            t.append("  health status   ").append(s.getAttribute("last-state")).append("  on ").append(s.getAttribute("dn")).append('\n');
                            j.append(n++ > 0 ? "," : "").append("{\"server\":").append(q(s.getAttribute("dn"))).append(",\"state\":").append(q(s.getAttribute("last-state"))).append("}");
                        }
                    }
                }
                for (org.w3c.dom.Element cs : com.pointblue.dirxml.sim.Xds.childrenByName(hcs, "custom-state")) {
                    for (org.w3c.dom.Element last : com.pointblue.dirxml.sim.Xds.childrenByName(cs, "last-state")) {
                        for (org.w3c.dom.Element d : com.pointblue.dirxml.sim.Xds.childrenByName(last, "driver")) {
                            for (org.w3c.dom.Element s : com.pointblue.dirxml.sim.Xds.childrenByName(d, "server")) {
                                t.append("  custom state    ").append(cs.getAttribute("unique-id")).append(" = ").append(s.getAttribute("last-state")).append("  on ").append(s.getAttribute("dn")).append('\n');
                                j.append(n++ > 0 ? "," : "").append("{\"server\":").append(q(s.getAttribute("dn"))).append(",\"customState\":").append(q(cs.getAttribute("unique-id"))).append(",\"state\":").append(q(s.getAttribute("last-state"))).append("}");
                            }
                        }
                    }
                }
            }
            if (n == 0) {
                t.append("  health status   recorded, but no server state in it\n");
            }
        }
        j.append("],\"configured\":[");
        // 2. the configuration on the manifest
        org.w3c.dom.Element manifest = parseOrNull(e.string("DirXML-ConfigManifest"));
        org.w3c.dom.Element hc = manifest == null ? null : com.pointblue.dirxml.sim.Xds.firstByName(manifest, "health-config");
        int c = 0;
        if (hc == null) {
            t.append("  health config   none on the driver (DirXML-ConfigManifest has no <health-config>)\n");
        } else {
            for (org.w3c.dom.Element st : com.pointblue.dirxml.sim.Xds.childElements(hc)) {
                String name = st.getLocalName() != null ? st.getLocalName() : st.getNodeName();
                if ("custom-state".equals(name) && !st.getAttribute("unique-id").isEmpty()) {
                    name = "custom-state " + st.getAttribute("unique-id");
                }
                int actions = 0;
                for (org.w3c.dom.Element acts : com.pointblue.dirxml.sim.Xds.childrenByName(st, "actions")) {
                    actions += com.pointblue.dirxml.sim.Xds.childElements(acts).size();
                }
                int conditions = com.pointblue.dirxml.sim.Xds.descendantsByName(st, "and").size() + com.pointblue.dirxml.sim.Xds.descendantsByName(st, "or").size();
                t.append("  health config   ").append(name).append(": ").append(conditions).append(" condition group(s), ").append(actions).append(" action(s)\n");
                j.append(c++ > 0 ? "," : "").append("{\"state\":").append(q(name)).append(",\"conditionGroups\":").append(conditions).append(",\"actions\":").append(actions).append("}");
            }
        }
        j.append("],\"jobs\":[");
        // 3. the set's health jobs
        int k = 0;
        List<Vault.Entry> jobs;
        try {
            jobs = engine.search(env.driverSetDn, "(objectClass=DirXML-Job)", javax.naming.directory.SearchControls.SUBTREE_SCOPE);
        } catch (RuntimeException ex) {
            jobs = List.of();
        }
        for (Vault.Entry je : jobs) {
            String xml = je.string("XmlData");
            if (xml == null || !xml.contains(HEALTH_JOB_CLASS)) {
                continue;
            }
            com.pointblue.dirxml.dev.model.Job job = new com.pointblue.dirxml.dev.model.Job(je.dn.substring(je.dn.indexOf('=') + 1, je.dn.indexOf(',')), parseOrNull(xml));
            boolean inScope = false;
            for (String sc : je.strings("DirXML-Scope")) {
                String scopeDn = sc.indexOf('#') < 0 ? sc : sc.substring(0, sc.indexOf('#'));
                if (scopeDn.equalsIgnoreCase(dn) || scopeDn.equalsIgnoreCase(env.driverSetDn)) {
                    inScope = true;
                }
            }
            t.append("  health job      ").append(job.name).append(job.disabled() ? " (disabled)" : "").append(inScope ? ", this driver in scope" : ", this driver NOT in scope").append('\n');
            j.append(k++ > 0 ? "," : "").append("{\"name\":").append(q(job.name)).append(",\"dn\":").append(q(je.dn)).append(",\"disabled\":").append(job.disabled()).append(",\"inScope\":").append(inScope).append("}");
        }
        if (k == 0) {
            t.append("  health job      none in the driver set (nothing evaluates the health configuration)\n");
        }
        j.append("]}");
        Result r = new Result();
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /** Clear the recorded health status (the console's {@code clearDriverHealthStatus}): the driver's {@code DirXML-uiXMLSmall} is removed. Gated light, audited. */
    public static Result driverHealthClear(Engine engine, Environments.Environment env, String driver, boolean yes, String confirm, Path tree) throws IOException {
        String dn = driverDn(env, driver);
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        Vault.Entry e = engine.read(dn);
        if (e == null) {
            return Result.refused("no such driver '" + driver + "' under " + env.driverSetDn);
        }
        boolean had = e.string(HEALTH_STATUS_ATTR) != null;
        String error = null;
        if (had) {
            try {
                engine.replace(dn, HEALTH_STATUS_ATTR, List.of());
            } catch (RuntimeException ex) {
                error = ex.getMessage();
            }
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.health clear '" + driver + "': " + (had ? "status cleared" : "nothing recorded") + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? (had ? "health status cleared for '" + driver + "'\n" : "no health status recorded for '" + driver + "'; nothing to clear\n") : "FAILED   " + error + "\n";
        r.json = "{\"ok\":" + (error == null) + ",\"cleared\":" + (had && error == null) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    /** What the engine says about one job. Read-only. */
    public static Result jobStatus(Engine engine, Environments.Environment env, String job, String driver) {
        String dn = jobDn(env, job, driver);
        Vault.Entry e = engine.read(dn);
        Result r = new Result();
        if (e == null) {
            r.ok = false;
            r.text = "no job " + dn + "\n";
            r.json = "{\"ok\":false,\"error\":\"no job\",\"dn\":" + q(dn) + "}";
            return r;
        }
        com.pointblue.dirxml.dev.model.Job model = new com.pointblue.dirxml.dev.model.Job(job, parseOrNull(e.string("XmlData")));
        Vault.JobState st = engine.jobState(dn);
        StringBuilder t = new StringBuilder();
        t.append(dn).append('\n');
        t.append("  class           ").append(model.javaClass() == null ? "?" : model.javaClass()).append('\n');
        t.append("  disabled        ").append(model.disabled()).append('\n');
        t.append("  servers         ").append(String.join(", ", e.strings("DirXML-ServerList"))).append('\n');
        t.append("  running         ").append(st == null ? "?" : jobRunningState(st.runningState)).append('\n');
        t.append("  configuration   ").append(st == null ? "?" : jobConfigState(st.configurationState)).append('\n');
        t.append("  scheduled       ").append(st == null ? "?" : st.scheduled ? "yes" : "no").append('\n');
        t.append("  next run        ").append(st == null || st.nextRun == null ? "-" : st.nextRun.toInstant().toString()).append('\n');
        r.ok = true;
        r.text = t.toString();
        r.json = "{\"ok\":true,\"dn\":" + q(dn) + ",\"javaClass\":" + q(model.javaClass() == null ? "" : model.javaClass()) + ",\"disabled\":" + model.disabled()
            + ",\"running\":" + q(st == null ? "?" : jobRunningState(st.runningState)) + ",\"config\":" + q(st == null ? "?" : jobConfigState(st.configurationState))
            + ",\"scheduled\":" + (st != null && st.scheduled) + ",\"nextRun\":" + q(st == null || st.nextRun == null ? "" : st.nextRun.toInstant().toString()) + "}";
        return r;
    }

    /** {@code StartJob} / {@code AbortJob}: a light write, gated and audited like the driver lifecycle. */
    public static Result jobAction(Engine engine, Environments.Environment env, String job, String driver, String action,
            boolean yes, String confirm, Path tree) throws IOException {
        if (!"start".equals(action) && !"abort".equals(action)) {
            return Result.refused("action is start or abort, not '" + action + "'");
        }
        String dn = jobDn(env, job, driver);
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        String error = null;
        try {
            if ("start".equals(action)) {
                engine.startJob(dn);
            } else {
                engine.abortJob(dn);
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        String after = "?";
        try {
            Vault.JobState st = engine.jobState(dn);
            after = st == null ? "?" : jobRunningState(st.runningState);
        } catch (RuntimeException ignore) {
            // the state read is informative only
        }
        boolean ok = error == null;
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = ok ? "ok" : "failed";
        rec.detail = "job." + action + " '" + job + "'" + (driver == null || driver.isBlank() ? "" : " of '" + driver + "'") + ": " + after + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = ok;
        r.text = "job." + action + " '" + job + "': " + (ok ? "OK, now " + after : "FAILED   " + error) + "\n";
        r.json = "{\"ok\":" + ok + ",\"job\":" + q(job) + ",\"dn\":" + q(dn) + ",\"running\":" + q(after) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    private static org.w3c.dom.Element parseOrNull(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            return com.pointblue.dirxml.dev.xml.CanonicalXml.parse(xml).getDocumentElement();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- driver.cache view | clear ------------------------------------------------------------

    /** The whole cache, paged: raw pages (for the snapshot) and every event (parsed). */
    private static final class CachePages {
        final List<String> rawPages = new ArrayList<>();
        final List<Element> events = new ArrayList<>();
    }

    private static CachePages readWholeCache(Engine engine, String dn, int pageSize) {
        CachePages out = new CachePages();
        int position = 0;
        while (true) {
            CachePage p = engine.viewCache(dn, position, pageSize);
            if (p.empty) {
                break;
            }
            out.rawPages.add(p.xds);
            out.events.addAll(extractEvents(p.xds));
            if (p.nextToken == position) {
                break;
            }
            position = p.nextToken;
        }
        return out;
    }

    private static List<Element> extractEvents(String xds) {
        List<Element> out = new ArrayList<>();
        if (xds == null || xds.isBlank()) {
            return out;
        }
        Document doc = CanonicalXml.parse(xds);
        NodeList inputs = doc.getElementsByTagName("input");
        if (inputs.getLength() == 0) {
            return out;
        }
        Element input = (Element) inputs.item(0);
        NodeList kids = input.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    public static Result cacheView(Engine engine, Environments.Environment env, String driver, int count, Path outDir) throws IOException {
        String dn = driverDn(env, driver);
        CachePages pages = readWholeCache(engine, dn, count);

        StringBuilder text = new StringBuilder();
        text.append(pages.events.size()).append(" event(s)\n");
        for (Element e : pages.events) {
            text.append("  ").append(summarize(e)).append('\n');
        }

        StringBuilder json = new StringBuilder("{\"ok\":true,\"count\":").append(pages.events.size()).append(",\"events\":[");
        for (int i = 0; i < pages.events.size(); i++) {
            json.append(i == 0 ? "" : ",").append(eventJson(pages.events.get(i)));
        }
        json.append(']');

        if (outDir != null) {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("cache.xds"), buildCacheXds(pages), StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("input.xds"), buildInputXds(pages), StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("README"),
                "from `driver.cache view --env " + env.name + " --driver " + driver + "` at " + Instant.now() + "\n",
                StandardCharsets.UTF_8);
            text.append("wrote ").append(outDir).append('\n');
            json.append(",\"out\":").append(q(outDir.toString()));
        }
        json.append('}');

        Result r = new Result();
        r.ok = true;
        r.text = text.toString();
        r.json = json.toString();
        return r;
    }

    public static Result cacheClear(Engine engine, Environments.Environment env, String driver, boolean yes, String confirm, Path tree) throws IOException {
        String dn = driverDn(env, driver);
        CachePages pages = readWholeCache(engine, dn, 100);
        int count = pages.events.size();

        StringBuilder preview = new StringBuilder();
        preview.append("cache: ").append(count).append(" event(s)\n");
        if (count > 0) {
            preview.append("  first  ").append(summarize(pages.events.get(0))).append('\n');
            preview.append("  last   ").append(summarize(pages.events.get(count - 1))).append('\n');
        }

        if (count == 0) {
            Result r = new Result();
            r.ok = true;
            r.text = preview + "cache already empty; nothing to clear\n";
            r.json = "{\"ok\":true,\"count\":0}";
            return r;
        }

        String refusal = gate(env, OpClass.CACHE_CLEAR, yes, confirm);
        if (refusal != null) {
            Result r = new Result();
            r.ok = false;
            r.text = preview + "REFUSED — " + refusal + "\n";
            r.json = "{\"ok\":false,\"count\":" + count + ",\"refusal\":" + q(refusal) + "}";
            return r;
        }

        // snapshot first — a cleared cache is recoverable only from here
        Path snapDir = tree.resolve("deploy-snapshots").resolve(env.name);
        Files.createDirectories(snapDir);
        String ts = TS.format(Instant.now());
        Path snapFile = snapDir.resolve(ts + "-cache-" + safeFileName(driver) + ".xds");
        Files.writeString(snapFile, buildCacheXds(pages), StandardCharsets.UTF_8);
        String snapRel = tree.toAbsolutePath().relativize(snapFile.toAbsolutePath()).toString().replace('\\', '/');

        String error = null;
        try {
            engine.deleteCacheEntries(dn, 0, count, "", 0);
        } catch (RuntimeException e) {
            // fall back to repeating (0, 1) — spike 5a
            String fallbackError = null;
            for (int i = 0; i < count; i++) {
                try {
                    engine.deleteCacheEntries(dn, 0, 1, "", 0);
                } catch (RuntimeException e2) {
                    fallbackError = e2.getMessage();
                    break;
                }
            }
            error = fallbackError;
        }

        boolean empty = false;
        for (int attempt = 0; attempt < 5 && !empty; attempt++) {
            try {
                empty = engine.viewCache(dn, 0, 1).empty;
            } catch (RuntimeException ignored) {
                // a -641 can happen briefly right after the delete; retry
            }
            if (!empty) {
                sleep(1000);
            }
        }

        boolean ok = error == null && empty;
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = ok ? "ok" : "failed";
        rec.snapshot = snapRel;
        rec.changes = count;
        rec.detail = "driver.cache clear '" + driver + "': cleared " + count + " event(s)"
            + (error != null ? " — " + error : "") + (!empty && error == null ? " — verify FAILED: cache still not empty" : "");
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = ok;
        r.text = preview.toString() + "snapshot: " + snapRel + "\n"
            + (ok ? "cleared " + count + " event(s); cache verified empty\n"
                  : "FAILED   " + (error != null ? error : "cache not verified empty after clear") + "\n");
        r.json = "{\"ok\":" + ok + ",\"count\":" + count + ",\"snapshot\":" + q(snapRel)
            + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String summarize(Element e) {
        String op = e.getTagName();
        String id = attrOrNull(e, "event-id");
        String cls = attrOrNull(e, "class-name");
        String dn = attrOrNull(e, "src-dn");
        if (dn == null) {
            dn = attrOrNull(e, "qualified-src-dn");
        }
        return op + " " + (cls == null ? "" : cls) + " " + (dn == null ? "" : dn) + " (event-id=" + (id == null ? "" : id) + ")";
    }

    private static String eventJson(Element e) {
        return "{\"operation\":" + q(e.getTagName())
            + ",\"eventId\":" + jn(attrOrNull(e, "event-id"))
            + ",\"className\":" + jn(attrOrNull(e, "class-name"))
            + ",\"srcDn\":" + jn(attrOrNull(e, "src-dn"))
            + "}";
    }

    private static String attrOrNull(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    /** The raw ViewCacheEntries shape, merged across pages: the first page's {@code <source>}, plus every event. */
    private static String buildCacheXds(CachePages pages) {
        Document out = newDocument();
        Element newRoot;
        if (!pages.rawPages.isEmpty()) {
            Document first = CanonicalXml.parse(pages.rawPages.get(0));
            Element firstRoot = first.getDocumentElement();
            newRoot = out.createElement(firstRoot.getTagName());
            copyAttributes(firstRoot, newRoot);
            NodeList children = firstRoot.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals("source")) {
                    newRoot.appendChild(out.importNode(n, true));
                }
            }
        } else {
            newRoot = out.createElement("nds");
            newRoot.setAttribute("dtdversion", "4.0");
        }
        Element input = out.createElement("input");
        for (Element e : pages.events) {
            input.appendChild(out.importNode(e, true));
        }
        newRoot.appendChild(input);
        return CanonicalXml.serialize(newRoot);
    }

    /** The simulator case shape: {@code <nds dtdversion="4.0"><input>…</input></nds>}. */
    private static String buildInputXds(CachePages pages) {
        Document out = newDocument();
        Element root = out.createElement("nds");
        root.setAttribute("dtdversion", "4.0");
        Element input = out.createElement("input");
        for (Element e : pages.events) {
            input.appendChild(out.importNode(e, true));
        }
        root.appendChild(input);
        return CanonicalXml.serialize(root);
    }

    private static Document newDocument() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyAttributes(Element from, Element to) {
        NamedNodeMap attrs = from.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            to.setAttribute(a.getName(), a.getValue());
        }
    }

    private static String safeFileName(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    // ---- driver.migrate | driver.resync --------------------------------------------------------

    public static Result migrate(Engine engine, Environments.Environment env, String driver, byte[] xds, boolean yes, String confirm, Path tree) throws IOException {
        String dn = driverDn(env, driver);
        int state = engine.driverState(dn);
        if (state != Vault.STATE_RUNNING) {
            return Result.refused("driver '" + driver + "' must be running to migrate (state is " + Vault.stateName(state) + ")");
        }
        String refusal = gate(env, OpClass.HEAVY, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        String error = null;
        try {
            engine.migrateApp(dn, xds);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.migrate '" + driver + "': submitted " + xds.length + " byte(s)" + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "migrate submitted (" + xds.length + " bytes)\n" : "FAILED   " + error + "\n";
        r.json = "{\"ok\":" + r.ok + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    public static Result resync(Engine engine, Environments.Environment env, String driver, Long sinceMillis, boolean yes, String confirm, Path tree) throws IOException {
        String dn = driverDn(env, driver);
        int state = engine.driverState(dn);
        if (state != Vault.STATE_RUNNING) {
            return Result.refused("driver '" + driver + "' must be running to resync (state is " + Vault.stateName(state) + ")");
        }
        String refusal = gate(env, OpClass.HEAVY, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        long since = sinceMillis == null ? 0L : sinceMillis;
        String sinceText = sinceMillis == null ? "epoch 0 (full resync)" : Instant.ofEpochMilli(since).toString();
        String error = null;
        try {
            engine.resync(dn, since);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.resync '" + driver + "': since=" + sinceText + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "resync requested (since " + sinceText + ")\n" : "FAILED   " + error + "\n";
        r.json = "{\"ok\":" + r.ok + ",\"since\":" + q(sinceText) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    // ---- driver.secrets list | set | remove ----------------------------------------------------

    public static Result secretsList(Engine engine, Environments.Environment env, String driver) {
        String dn = driverDn(env, driver);
        List<String> names = engine.namedPasswords(dn);
        Result r = new Result();
        r.ok = true;
        r.text = names.isEmpty() ? "(no named passwords)\n" : String.join("\n", names) + "\n";
        r.json = "{\"ok\":true,\"names\":" + strArr(names) + "}";
        return r;
    }

    public static Result secretsSet(Engine engine, Environments.Environment env, String driver, String name,
            Secrets secrets, boolean stdin, boolean yes, String confirm, Path tree) throws IOException {
        return secretsSet(engine, env, driver, "named", name, secrets, stdin, yes, confirm, tree);
    }

    /** The secret kinds {@code driver.secrets} knows: a named password, the shim's authentication password, the Remote Loader password, its mutual-authentication key and keystore passwords. */
    public static final List<String> SECRET_KINDS = List.of("named", "shim-auth", "remote-loader", "key", "keystore");

    /** The Secrets key of a kind for a driver ({@code named} needs the name). */
    public static String secretKey(String kind, String driver, String name) {
        switch (kind) {
            case "named": return Secrets.named(driver, name);
            case "shim-auth": return Secrets.shimAuth(driver);
            case "remote-loader": return Secrets.remoteLoader(driver);
            case "key": return Secrets.key(driver);
            case "keystore": return Secrets.keystore(driver);
            default: throw new IllegalArgumentException("secret kind: " + kind);
        }
    }

    /**
     * Set one secret of a driver live (docs/console-gaps.md §11): a named password ({@code SetNamedPassword}),
     * the shim's authentication password ({@code DirXML-ShimAuthPassword}), the Remote Loader password or
     * its mutual-authentication key and keystore passwords (the engine's extended operations). The value
     * comes from the environment's secrets file by its key, or from stdin. Light write, audited.
     */
    public static Result secretsSet(Engine engine, Environments.Environment env, String driver, String kind, String name,
            Secrets secrets, boolean stdin, boolean yes, String confirm, Path tree) throws IOException {
        if (!SECRET_KINDS.contains(kind)) {
            return Result.refused("--kind is one of " + String.join(", ", SECRET_KINDS) + ", not '" + kind + "'");
        }
        if ("named".equals(kind) && (name == null || name.isBlank())) {
            return Result.refused("a named password needs --name");
        }
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        char[] value;
        if (stdin) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                return Result.refused("no value on stdin");
            }
            value = line.toCharArray();
        } else {
            String key = secretKey(kind, driver, name);
            value = secrets.get(key);
            if (value == null) {
                return Result.refused("no secret '" + key + "' in the environment's secrets file (or use --stdin)");
            }
        }
        String dn = driverDn(env, driver);
        String error = null;
        String what = "named".equals(kind) ? "named password '" + name + "'" : kind.replace('-', ' ') + " password";
        try {
            switch (kind) {
                case "named": engine.setNamedPassword(dn, name, name, value); break;
                case "shim-auth": engine.replace(dn, com.pointblue.dirxml.dev.deploy.VaultMapping.SHIM_AUTH_PASSWORD, List.of(new String(value).getBytes(StandardCharsets.UTF_8))); Arrays.fill(value, '\0'); break;
                case "remote-loader": engine.setRemoteLoaderPassword(dn, value); break;
                case "key": engine.setMutualAuthKeyPassword(dn, value); break;
                default: engine.setMutualAuthKeystorePassword(dn, value); break;
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
            Arrays.fill(value, '\0');
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        if (error == null) {
            rec.secretsSet.add(secretKey(kind, driver, name));
        }
        rec.detail = "driver.secrets set '" + driver + "': " + what + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "set " + what + " on '" + driver + "'\n" : "FAILED   " + error + "\n";
        r.json = "{\"ok\":" + r.ok + ",\"kind\":" + q(kind) + ",\"name\":" + q(name == null ? "" : name) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    public static Result secretsRemove(Engine engine, Environments.Environment env, String driver, String name,
            boolean yes, String confirm, Path tree) throws IOException {
        return secretsRemove(engine, env, driver, "named", name, yes, confirm, tree);
    }

    /** Remove one secret of a driver live, by kind (see {@link #SECRET_KINDS}). Light write, audited. */
    public static Result secretsRemove(Engine engine, Environments.Environment env, String driver, String kind, String name,
            boolean yes, String confirm, Path tree) throws IOException {
        if (!SECRET_KINDS.contains(kind)) {
            return Result.refused("--kind is one of " + String.join(", ", SECRET_KINDS) + ", not '" + kind + "'");
        }
        if ("named".equals(kind) && (name == null || name.isBlank())) {
            return Result.refused("a named password needs --name");
        }
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        String dn = driverDn(env, driver);
        String what = "named".equals(kind) ? "named password '" + name + "'" : kind.replace('-', ' ') + " password";
        String error = null;
        try {
            switch (kind) {
                case "named": engine.removeNamedPassword(dn, name); break;
                case "shim-auth": engine.replace(dn, com.pointblue.dirxml.dev.deploy.VaultMapping.SHIM_AUTH_PASSWORD, List.of()); break;
                case "remote-loader": engine.clearRemoteLoaderPassword(dn); break;
                case "key": engine.clearMutualAuthKeyPassword(dn); break;
                default: engine.clearMutualAuthKeystorePassword(dn); break;
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.secrets remove '" + driver + "': " + what + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "removed " + what + " from '" + driver + "'\n" : "FAILED   " + error + "\n";
        r.json = "{\"ok\":" + r.ok + ",\"kind\":" + q(kind) + ",\"name\":" + q(name == null ? "" : name) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    // ---- G12: the log level ------------------------------------------------------------------------

    /** {@code DirXML-DriverTraceLevel}: Designer's log level choice (0 errors, 1 errors and warnings, 2 last log time only, 3 off, 5 specific events). */
    public static final String LOG_LEVEL_ATTR = "DirXML-DriverTraceLevel";
    public static final String LOG_EVENTS_ATTR = "DirXML-LogEvents";
    public static final String LOG_LIMIT_ATTR = "DirXML-LogLimit";
    public static final String LOG_EVENTS_TYPE_ATTR = "DirXML-LogEventsType";

    /** The level names, by {@code DirXML-DriverTraceLevel} value (Designer's log level page, read 2026-10-09). */
    public static String logLevelName(int level) {
        switch (level) {
            case 0: return "errors";
            case 1: return "errors-and-warnings";
            case 2: return "last-log-time";
            case 3: return "off";
            case 5: return "specific-events";
            case 6: return "xdas-events";
            default: return "level-" + level;
        }
    }

    /** A level by name or number; -1 when unknown. */
    public static int logLevelValue(String s) {
        if (s == null) {
            return -1;
        }
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "errors": case "error": return 0;
            case "errors-and-warnings": case "warnings": return 1;
            case "last-log-time": return 2;
            case "off": case "none": return 3;
            case "specific-events": case "specific": case "events": return 5;
            default:
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }

    /** The event ids Designer selects for a level ({@code DSUtil.LOG_LEVEL_n_EVENTS}): 0 → 4 5 38; 1 → 3 4 5 35 38 39; 2 → -1; 3 → 0. */
    public static int[] logLevelEvents(int level) {
        switch (level) {
            case 0: return new int[] { 4, 5, 38 };
            case 1: return new int[] { 3, 4, 5, 35, 38, 39 };
            case 2: return new int[] { -1 };
            case 3: return new int[] { 0 };
            default: return null;
        }
    }

    /**
     * The log level of a driver, or of the driver set when {@code driver} is null (docs/console-gaps.md §11):
     * {@code DirXML-DriverTraceLevel} (the level), {@code DirXML-LogEvents} (the event ids the engine logs),
     * {@code DirXML-LogLimit} (the most entries kept) and {@code DirXML-LogEventsType}. A driver without its
     * own values uses the driver set's. Read-only.
     */
    public static Result logLevelShow(Engine engine, Environments.Environment env, String driver) {
        String dn = driver == null || driver.isBlank() ? env.driverSetDn : driverDn(env, driver);
        // DirXML-LogEvents is engine-written (no-user-modification): LDAP returns it only when asked for by name
        Vault.Entry e = engine.read(dn, LOG_LEVEL_ATTR, LOG_EVENTS_ATTR, LOG_LIMIT_ATTR, LOG_EVENTS_TYPE_ATTR, "objectClass");
        if (e == null) {
            return Result.refused("no such object " + dn);
        }
        String level = e.string(LOG_LEVEL_ATTR);
        List<String> events = e.strings(LOG_EVENTS_ATTR);
        String limit = e.string(LOG_LIMIT_ATTR);
        String type = e.string(LOG_EVENTS_TYPE_ATTR);
        boolean inherits = driver != null && level == null && events.isEmpty() && limit == null && type == null;
        StringBuilder t = new StringBuilder(driver == null ? "driver set" : driver).append('\n');
        if (inherits) {
            t.append("  log level       (uses the driver set's settings)\n");
        }
        t.append("  log level       ").append(level == null ? "-" : level + " (" + logLevelName(Integer.parseInt(level.trim())) + ")").append('\n');
        t.append("  log events      ").append(events.isEmpty() ? "-" : String.join(" ", events)).append('\n');
        t.append("  log limit       ").append(limit == null ? "-" : limit).append('\n');
        t.append("  log events type ").append(type == null ? "-" : type).append('\n');
        StringBuilder j = new StringBuilder("{\"ok\":true,\"dn\":").append(q(dn)).append(",\"inherits\":").append(inherits)
            .append(",\"level\":").append(level == null ? "null" : level.trim()).append(",\"levelName\":").append(q(level == null ? "" : logLevelName(Integer.parseInt(level.trim()))))
            .append(",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            j.append(i > 0 ? "," : "").append(events.get(i).trim());
        }
        j.append("],\"limit\":").append(limit == null ? "null" : limit.trim()).append(",\"eventsType\":").append(type == null ? "null" : type.trim()).append("}");
        Result r = new Result();
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /**
     * Set the log level live: the level writes {@code DirXML-DriverTraceLevel} and, unless {@code events} are
     * given, the event ids Designer selects for it through {@code SetLogEvents} ({@code DirXML-LogEvents} is
     * engine-written); {@code specific-events} needs the ids. {@code limit} and {@code eventsType} are
     * written when given. Light write, audited.
     */
    public static Result logLevelSet(Engine engine, Environments.Environment env, String driver, Integer level, int[] events,
            Integer limit, Integer eventsType, boolean yes, String confirm, Path tree) throws IOException {
        return logLevelSet(engine, env, driver, level, events, limit, eventsType, false, yes, confirm, tree);
    }

    /** The same; with {@code inherit}, a driver drops its own four log attributes and uses the driver set's. */
    public static Result logLevelSet(Engine engine, Environments.Environment env, String driver, Integer level, int[] events,
            Integer limit, Integer eventsType, boolean inherit, boolean yes, String confirm, Path tree) throws IOException {
        if (inherit) {
            if (driver == null || driver.isBlank()) {
                return Result.refused("--inherit is for a driver (it then uses the driver set's log settings)");
            }
            String refusal = gate(env, OpClass.LIGHT, yes, confirm);
            if (refusal != null) {
                return Result.refused(refusal);
            }
            String dn = driverDn(env, driver);
            String error = null;
            try {
                engine.clearLogEvents(dn);
                for (String a : new String[] { LOG_LEVEL_ATTR, LOG_LIMIT_ATTR, LOG_EVENTS_TYPE_ATTR }) {
                    engine.replace(dn, a, List.of());
                }
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            DeployLog.Record rec = DeployLog.record(env.name, "operate");
            rec.outcome = error == null ? "ok" : "failed";
            rec.detail = "driver.log-level set '" + driver + "': inherit the driver set's" + (error != null ? " — " + error : "");
            DeployLog.append(tree, rec);
            Result r = new Result();
            r.ok = error == null;
            r.text = error == null ? "'" + driver + "' now uses the driver set's log settings\n" : "FAILED   " + error + "\n";
            r.json = "{\"ok\":" + r.ok + ",\"inherit\":true" + (error != null ? ",\"error\":" + q(error) : "") + "}";
            return r;
        }
        if (level == null && events == null && limit == null && eventsType == null) {
            return Result.refused("nothing to set: give --level, --events, --limit, --events-type or --inherit");
        }
        if (level != null && logLevelName(level).startsWith("level-")) {
            return Result.refused("--level is errors (0), errors-and-warnings (1), last-log-time (2), off (3) or specific-events (5)");
        }
        if (level != null && level == 5 && events == null) {
            return Result.refused("specific-events needs --events id,id,…");
        }
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        String dn = driver == null || driver.isBlank() ? env.driverSetDn : driverDn(env, driver);
        int[] ids = events != null ? events : (level != null ? logLevelEvents(level) : null);
        String error = null;
        List<String> done = new ArrayList<>();
        try {
            if (level != null) {
                engine.replace(dn, LOG_LEVEL_ATTR, List.of(String.valueOf(level).getBytes(StandardCharsets.UTF_8)));
                done.add("level=" + level + " (" + logLevelName(level) + ")");
            }
            if (ids != null) {
                if (ids.length == 1 && ids[0] == 0) {
                    engine.clearLogEvents(dn);
                    done.add("events cleared");
                } else {
                    engine.setLogEvents(dn, ids);
                    done.add("events=" + Arrays.toString(ids));
                }
            }
            if (limit != null) {
                engine.replace(dn, LOG_LIMIT_ATTR, List.of(String.valueOf(limit).getBytes(StandardCharsets.UTF_8)));
                done.add("limit=" + limit);
            }
            if (eventsType != null) {
                engine.replace(dn, LOG_EVENTS_TYPE_ATTR, List.of(String.valueOf(eventsType).getBytes(StandardCharsets.UTF_8)));
                done.add("events-type=" + eventsType);
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.log-level set '" + (driver == null ? "driver set" : driver) + "': " + String.join(", ", done) + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "log level of '" + (driver == null ? "driver set" : driver) + "': " + String.join(", ", done) + "\n" : "FAILED   " + error + " (done before it: " + String.join(", ", done) + ")\n";
        r.json = "{\"ok\":" + r.ok + ",\"done\":" + q(String.join(", ", done)) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    // ---- G8: the e-mail server ----------------------------------------------------------------------

    /** The notification collection's SMTP attributes, in the order shown ({@code notfSMTPMailPassword} is never read back). */
    public static final String[][] EMAIL_SERVER_ATTRS = {
        { "host", "notfSMTPEmailHost" }, { "port", "notfSMTPPort" }, { "from", "notfSMTPEmailFrom" }, { "user", "notfSMTPEmailUserName" },
        { "tls", "notfSMTPUseTLS" }, { "timeout", "notfSMTPTimeout" }, { "protocol", "notfSMTPMailProtocol" }, { "auth", "notfSMTPAuthMechanisms" },
    };
    public static final String EMAIL_SERVER_PASSWORD_ATTR = "notfSMTPMailPassword";
    /** The secrets-file key the password comes from. */
    public static final String EMAIL_SERVER_PASSWORD_KEY = "email-server.password";

    /** The vault's one notification collection ({@code notfTemplateCollection} under {@code cn=Security}), or null. */
    static Vault.Entry emailCollection(Engine engine) {
        try {
            List<Vault.Entry> found = engine.search("cn=Security", "(objectClass=notfTemplateCollection)", javax.naming.directory.SearchControls.ONELEVEL_SCOPE);
            return found.isEmpty() ? null : found.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The SMTP settings the engine sends notification mail with (docs/console-gaps.md §12). Read-only; the password is reported as set or not. */
    public static Result emailServerShow(Engine engine, Environments.Environment env) {
        Vault.Entry c = emailCollection(engine);
        if (c == null) {
            return Result.refused("no notification collection under cn=Security (notfTemplateCollection)");
        }
        Vault.Entry e = engine.read(c.dn);
        StringBuilder t = new StringBuilder(c.dn).append('\n');
        StringBuilder j = new StringBuilder("{\"ok\":true,\"dn\":").append(q(c.dn));
        for (String[] a : EMAIL_SERVER_ATTRS) {
            String v = e == null ? null : e.string(a[1]);
            t.append(String.format("  %-9s %s", a[0], v == null ? "-" : v)).append('\n');
            j.append(",").append(q(a[0])).append(":").append(v == null ? "null" : q(v));
        }
        boolean pw = e != null && e.bytes(EMAIL_SERVER_PASSWORD_ATTR) != null;
        t.append("  password  ").append(pw ? "(set)" : "-").append('\n');
        j.append(",\"passwordSet\":").append(pw).append("}");
        Result r = new Result();
        r.ok = true;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    /**
     * Write the SMTP settings given ({@code host}, {@code port}, {@code from}, {@code user}, {@code tls}, {@code timeout},
     * {@code protocol}, {@code auth}; an empty value clears one) and the password when it is given. Light write, audited.
     */
    public static Result emailServerSet(Engine engine, Environments.Environment env, Map<String, String> values, char[] password,
            boolean yes, String confirm, Path tree) throws IOException {
        if (values.isEmpty() && password == null) {
            return Result.refused("nothing to set: give --host, --port, --from, --user, --tls, --timeout, --protocol, --auth or a password");
        }
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        Vault.Entry c = emailCollection(engine);
        if (c == null) {
            return Result.refused("no notification collection under cn=Security (notfTemplateCollection)");
        }
        List<String> done = new ArrayList<>();
        String error = null;
        try {
            for (String[] a : EMAIL_SERVER_ATTRS) {
                if (!values.containsKey(a[0])) {
                    continue;
                }
                String v = values.get(a[0]);
                engine.replace(c.dn, a[1], v == null || v.isEmpty() ? List.of() : List.of(v.getBytes(StandardCharsets.UTF_8)));
                done.add(a[0] + "=" + (v == null || v.isEmpty() ? "(cleared)" : v));
            }
            if (password != null) {
                engine.replace(c.dn, EMAIL_SERVER_PASSWORD_ATTR, List.of(new String(password).getBytes(StandardCharsets.UTF_8)));
                Arrays.fill(password, '\0');
                done.add("password=(set)");
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "vault.email-server set: " + String.join(", ", done) + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "e-mail server: " + String.join(", ", done) + "\n" : "FAILED   " + error + " (done before it: " + String.join(", ", done) + ")\n";
        r.json = "{\"ok\":" + r.ok + ",\"done\":" + q(String.join(", ", done)) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    // ---- G5: migrate into the application --------------------------------------------------------

    /**
     * Migrate vault objects into the application (the console's {@code migrateFromNDS}, docs/console-gaps.md
     * §11): an LDAP search finds them, then each goes down the running driver's subscriber channel as a
     * {@code <sync>} command, which makes the engine read the object and send its add or modify to the shim.
     * Heavy write, audited; {@code dryRun} only lists what would go.
     */
    public static Result migrateIntoApp(Engine engine, Environments.Environment env, String driver, String base, String filter,
            String className, int max, boolean dryRun, boolean yes, String confirm, Path tree) throws IOException {
        if (base == null || base.isBlank() || filter == null || filter.isBlank() || className == null || className.isBlank()) {
            return Result.refused("--base, --filter and --class are needed");
        }
        String dn = driverDn(env, driver);
        List<Vault.Entry> found;
        try {
            found = engine.search(base, filter, javax.naming.directory.SearchControls.SUBTREE_SCOPE);
        } catch (RuntimeException e) {
            return Result.refused("search " + base + " " + filter + ": " + e.getMessage());
        }
        if (found.size() > max) {
            found = new ArrayList<>(found.subList(0, max));
        }
        if (dryRun) {
            StringBuilder t = new StringBuilder().append(found.size()).append(" object(s) would be migrated into '").append(driver).append("' (dry run):\n");
            StringBuilder j = new StringBuilder("{\"ok\":true,\"dryRun\":true,\"count\":").append(found.size()).append(",\"objects\":[");
            int n = 0;
            for (Vault.Entry e : found) {
                t.append("  ").append(e.dn).append('\n');
                j.append(n++ > 0 ? "," : "").append(q(e.dn));
            }
            j.append("]}");
            Result r = new Result();
            r.ok = true;
            r.text = t.toString();
            r.json = j.toString();
            return r;
        }
        String refusal = gate(env, OpClass.HEAVY, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        int state = engine.driverState(dn);
        if (state != Vault.STATE_RUNNING) {
            return Result.refused("driver '" + driver + "' is " + Vault.stateName(state) + "; a migration needs a running driver");
        }
        StringBuilder t = new StringBuilder();
        StringBuilder j = new StringBuilder("{\"ok\":true,\"driver\":").append(q(driver)).append(",\"results\":[");
        int ok = 0;
        int failed = 0;
        int n = 0;
        for (Vault.Entry e : found) {
            String xds = "<nds dtdversion=\"4.0\" ndsversion=\"8.x\"><source><product>DirXMLDev</product><contact>Point Blue</contact></source><input>"
                + "<sync class-name=\"" + xmlAttr(className) + "\" event-id=\"dirxmldev-migrate\" src-dn=\"" + xmlAttr(slashDn(e.dn)) + "\"/></input></nds>";
            String status;
            try {
                String answer = engine.submitCommand(dn, xds.getBytes(StandardCharsets.UTF_8));
                status = queryStatus(answer);
                if (status == null) {
                    status = "submitted";
                }
            } catch (RuntimeException ex) {
                status = "error: " + ex.getMessage();
            }
            boolean good = !status.startsWith("error") && !status.startsWith("fatal") && !status.startsWith("retry");
            if (good) {
                ok++;
            } else {
                failed++;
            }
            t.append(good ? "  ok      " : "  FAILED  ").append(e.dn).append("  ").append(status).append('\n');
            j.append(n++ > 0 ? "," : "").append("{\"dn\":").append(q(e.dn)).append(",\"ok\":").append(good).append(",\"status\":").append(q(status)).append("}");
        }
        t.insert(0, found.size() + " object(s) sent into '" + driver + "': " + ok + " ok, " + failed + " failed\n");
        j.append("],\"sent\":").append(found.size()).append(",\"succeeded\":").append(ok).append(",\"failed\":").append(failed).append("}");
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = failed == 0 ? "ok" : "failed";
        rec.detail = "driver.migrate --direction vault '" + driver + "': " + found.size() + " object(s) from " + base + " " + filter + " as " + className + ": " + ok + " ok, " + failed + " failed";
        DeployLog.append(tree, rec);
        Result r = new Result();
        r.ok = failed == 0;
        r.text = t.toString();
        r.json = j.toString();
        return r;
    }

    // ---- driver.trace show | set | reset --------------------------------------------------------

    public static Result traceShow(Engine engine, Environments.Environment env, String driver) {
        String dn = driverDn(env, driver);
        Vault.Entry e = engine.read(dn);
        String level = e == null ? null : e.string(Vault.TRACE_LEVEL);
        String file = e == null ? null : e.string(Vault.TRACE_FILE);
        Result r = new Result();
        r.ok = true;
        r.text = "level: " + sentinel(level) + "\nfile:  " + sentinel(file) + "\n";
        r.json = "{\"ok\":true,\"level\":" + jn(level) + ",\"file\":" + jn(file) + "}";
        return r;
    }

    public static Result traceSet(Engine engine, Environments.Environment env, String driver, Integer level, String file,
            boolean yes, String confirm, Path tree) throws IOException {
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        String dn = driverDn(env, driver);
        Vault.Entry before = engine.read(dn);
        String beforeLevel = before == null ? null : before.string(Vault.TRACE_LEVEL);
        String beforeFile = before == null ? null : before.string(Vault.TRACE_FILE);
        String error = null;
        try {
            if (level != null) {
                engine.replace(dn, Vault.TRACE_LEVEL, Vault.value(String.valueOf(level)));
            }
            if (file != null) {
                engine.replace(dn, Vault.TRACE_FILE, Vault.value(file));
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        String afterLevel = level != null ? String.valueOf(level) : beforeLevel;
        String afterFile = file != null ? file : beforeFile;

        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = traceDetail("driver.trace set", driver, beforeLevel, afterLevel, beforeFile, afterFile)
            + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = error == null;
        r.text = "trace level: " + sentinel(beforeLevel) + " → " + sentinel(afterLevel) + "\n"
            + "trace file:  " + sentinel(beforeFile) + " → " + sentinel(afterFile) + "\n"
            + (error != null ? "FAILED   " + error + "\n" : "");
        r.json = "{\"ok\":" + r.ok + ",\"beforeLevel\":" + jn(beforeLevel) + ",\"afterLevel\":" + jn(afterLevel)
            + ",\"beforeFile\":" + jn(beforeFile) + ",\"afterFile\":" + jn(afterFile)
            + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    public static Result traceReset(Engine engine, Environments.Environment env, String driver, boolean yes, String confirm, Path tree) throws IOException {
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        List<DeployLog.Record> all = DeployLog.read(tree, env.name);
        String prefix = "driver.trace set '" + driver + "':";
        DeployLog.Record last = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            DeployLog.Record r = all.get(i);
            if ("operate".equals(r.operation) && r.detail != null && r.detail.startsWith(prefix)) {
                last = r;
                break;
            }
        }
        if (last == null) {
            return Result.refused("no previous 'driver.trace set' for '" + driver + "' in "
                + DeployLog.file(tree, env.name) + " to reset from");
        }
        String[] original = parseTraceSetDetail(last.detail);
        String restoreLevel = original[0];
        String restoreFile = original[1];

        String dn = driverDn(env, driver);
        Vault.Entry current = engine.read(dn);
        String curLevel = current == null ? null : current.string(Vault.TRACE_LEVEL);
        String curFile = current == null ? null : current.string(Vault.TRACE_FILE);

        String error = null;
        try {
            engine.replace(dn, Vault.TRACE_LEVEL, restoreLevel == null ? List.of() : Vault.value(restoreLevel));
            engine.replace(dn, Vault.TRACE_FILE, restoreFile == null ? List.of() : Vault.value(restoreFile));
        } catch (RuntimeException e) {
            error = e.getMessage();
        }

        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.trace reset '" + driver + "': level=" + sent(curLevel) + "->" + sent(restoreLevel)
            + " file=" + sent(curFile) + "->" + sent(restoreFile) + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = error == null;
        r.text = "trace level: " + sentinel(curLevel) + " → " + sentinel(restoreLevel) + "\n"
            + "trace file:  " + sentinel(curFile) + " → " + sentinel(restoreFile) + "\n"
            + (error != null ? "FAILED   " + error + "\n" : "");
        r.json = "{\"ok\":" + r.ok + ",\"level\":" + jn(restoreLevel) + ",\"file\":" + jn(restoreFile)
            + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    private static String traceDetail(String cmd, String driver, String beforeLevel, String afterLevel, String beforeFile, String afterFile) {
        return cmd + " '" + driver + "': level=" + sent(beforeLevel) + "->" + sent(afterLevel)
            + " file=" + sent(beforeFile) + "->" + sent(afterFile);
    }

    /** Extracts the ORIGINAL (before) level/file from a {@code driver.trace set} audit detail line. */
    private static String[] parseTraceSetDetail(String detail) {
        int levelIdx = detail.indexOf("level=");
        int fileIdx = detail.indexOf(" file=");
        if (levelIdx < 0 || fileIdx < 0) {
            return new String[] {null, null};
        }
        String levelPart = detail.substring(levelIdx + 6, fileIdx);
        String rest = detail.substring(fileIdx + 6);
        int dash = rest.indexOf(" —");
        String filePart = dash >= 0 ? rest.substring(0, dash) : rest;
        String[] lv = levelPart.split("->", 2);
        String[] fv = filePart.split("->", 2);
        String beforeLevel = lv.length > 0 ? unsent(lv[0]) : null;
        String beforeFile = fv.length > 0 ? unsent(fv[0]) : null;
        return new String[] {beforeLevel, beforeFile};
    }

    private static String sent(String s) {
        return s == null ? "-" : s;
    }

    private static String unsent(String s) {
        return "-".equals(s) ? null : s;
    }

    private static String sentinel(String s) {
        return s == null ? "(unset)" : s;
    }

    // ---- engine.version / engine.stats -----------------------------------------------------------

    public static Result engineVersion(Engine engine) {
        int packed = engine.engineVersion();
        String versionText;
        int[] parsed;
        try {
            parsed = com.novell.nds.dirxml.util.DxConst.parseDirXMLVersion(packed);
            versionText = parsed.length >= 5
                ? String.format("%d.%d.%d.%d build %d", parsed[0], parsed[1], parsed[2], parsed[3], parsed[4])
                : Arrays.toString(parsed);
        } catch (RuntimeException e) {
            parsed = new int[0];
            versionText = "packed=" + packed + " (unparsed: " + e.getMessage() + ")";
        }
        Result r = new Result();
        r.ok = true;
        r.text = versionText + "\n";
        r.json = "{\"ok\":true,\"packed\":" + packed + ",\"version\":" + q(versionText) + ",\"parts\":" + intArr(parsed) + "}";
        return r;
    }

    static final class JvmStats {
        String heapUsed = "?";
        String heapCommitted = "?";
        String heapTotal = "?";
        String threadsCurrent = "?";
        String threadsDaemon = "?";
        String threadsPeak = "?";
    }

    static final class DriverStat {
        final String name;
        String cacheSize = "?";
        String unprocessedSize = "?";
        String reportedEvents = "?";
        String commands = "?";

        DriverStat(String name) {
            this.name = name;
        }
    }

    public static Result engineStats(Engine engine, Environments.Environment env, List<String> drivers) {
        JvmStats jvm = parseJvmStats(safeJvmStats(engine));

        StringBuilder text = new StringBuilder();
        text.append(String.format("JVM heap used=%s committed=%s total=%s threads current=%s daemon=%s peak=%s%n",
            jvm.heapUsed, jvm.heapCommitted, jvm.heapTotal, jvm.threadsCurrent, jvm.threadsDaemon, jvm.threadsPeak));

        StringBuilder json = new StringBuilder("{\"ok\":true,\"jvm\":{");
        json.append("\"heapUsed\":").append(q(jvm.heapUsed)).append(",\"heapCommitted\":").append(q(jvm.heapCommitted))
            .append(",\"heapTotal\":").append(q(jvm.heapTotal)).append(",\"threadsCurrent\":").append(q(jvm.threadsCurrent))
            .append(",\"threadsDaemon\":").append(q(jvm.threadsDaemon)).append(",\"threadsPeak\":").append(q(jvm.threadsPeak))
            .append("},\"drivers\":[");

        for (int i = 0; i < drivers.size(); i++) {
            String d = drivers.get(i);
            String dn = driverDn(env, d);
            DriverStat ds = parseDriverStats(d, safeDriverStats(engine, dn));
            text.append(String.format("  %-30s cache=%s unprocessed=%s reported=%s commands=%s%n",
                d, ds.cacheSize, ds.unprocessedSize, ds.reportedEvents, ds.commands));
            json.append(i == 0 ? "" : ",")
                .append("{\"name\":").append(q(d)).append(",\"cacheSize\":").append(q(ds.cacheSize))
                .append(",\"unprocessedSize\":").append(q(ds.unprocessedSize))
                .append(",\"reportedEvents\":").append(q(ds.reportedEvents)).append(",\"commands\":").append(q(ds.commands))
                .append('}');
        }
        json.append("]}");

        Result r = new Result();
        r.ok = true;
        r.text = text.toString();
        r.json = json.toString();
        return r;
    }

    private static String safeJvmStats(Engine engine) {
        try {
            return engine.jvmStats(0, 0);
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---- tiny, tolerant XML readers for stats documents (schema not fully specified; ? on failure) ----

    static JvmStats parseJvmStats(String xml) {
        JvmStats s = new JvmStats();
        if (xml == null || xml.isBlank()) {
            return s;
        }
        try {
            Document doc = CanonicalXml.parse(xml);
            // the engine's shape (spike 5): <memory_stats><heap><initial/><comitted/><used/><total/></heap>…
            // <thread_stats><daemon_count/><current_count/><peak_count/>…  ("comitted" is the engine's spelling)
            Element heap = firstElement(doc, "heap");
            s.heapUsed = childText(heap, "used");
            s.heapCommitted = childText(heap, "comitted");
            if ("?".equals(s.heapCommitted)) {
                s.heapCommitted = childText(heap, "committed");
            }
            s.heapTotal = childText(heap, "total");
            if ("?".equals(s.heapTotal)) {
                s.heapTotal = childText(heap, "max");
            }
            Element threads = firstElement(doc, "thread_stats");
            if (threads == null) {
                threads = firstElement(doc, "threads");
            }
            s.threadsCurrent = childText(threads, "current_count");
            if ("?".equals(s.threadsCurrent)) {
                s.threadsCurrent = childText(threads, "current");
            }
            s.threadsDaemon = childText(threads, "daemon_count");
            if ("?".equals(s.threadsDaemon)) {
                s.threadsDaemon = childText(threads, "daemon");
            }
            s.threadsPeak = childText(threads, "peak_count");
            if ("?".equals(s.threadsPeak)) {
                s.threadsPeak = childText(threads, "peak");
            }
        } catch (RuntimeException ignored) {
            // leave every field "?"
        }
        return s;
    }

    /** The sum of the integer children of every element named {@code tag}; -1 when there is none. */
    private static long sumChildren(Document doc, String tag) {
        org.w3c.dom.NodeList list = doc.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return -1;
        }
        long sum = 0;
        for (int i = 0; i < list.getLength(); i++) {
            org.w3c.dom.Node n = list.item(i).getFirstChild();
            for (; n != null; n = n.getNextSibling()) {
                if (n instanceof Element) {
                    try {
                        sum += Long.parseLong(n.getTextContent().trim());
                    } catch (NumberFormatException ignored) {
                        // a nested container, not a count
                    }
                }
            }
        }
        return sum;
    }

    static DriverStat parseDriverStats(String name, String xml) {
        DriverStat s = new DriverStat(name);
        String[] sizes = parseCacheSizes(xml);
        s.cacheSize = sizes[0];
        s.unprocessedSize = sizes[1];
        if (xml == null || xml.isBlank()) {
            return s;
        }
        try {
            Document doc = CanonicalXml.parse(xml);
            // the engine's shape (spike 5): per channel <operations><reported-events><modify>12</modify>…</reported-events>
            // <commands><query>48</query>…</commands> — a count per operation type; report the totals
            long reported = sumChildren(doc, "reported-events");
            long commands = sumChildren(doc, "commands");
            s.reportedEvents = reported < 0 ? firstTagText(doc, "reported-event-count") : Long.toString(reported);
            s.commands = commands < 0 ? firstTagText(doc, "command-count") : Long.toString(commands);
        } catch (RuntimeException ignored) {
            // leave "?"
        }
        return s;
    }

    /** {@code {cacheSize, unprocessedSize}} from a {@code GetDriverStats} document; "?" on any failure. */
    static String[] parseCacheSizes(String xml) {
        if (xml == null || xml.isBlank()) {
            return new String[] {"?", "?"};
        }
        try {
            Document doc = CanonicalXml.parse(xml);
            Element cache = firstElement(doc, "cache");
            String size = childText(cache, "size");
            String unprocessed = firstTagText(doc, "unprocessed-size");
            return new String[] {size, unprocessed};
        } catch (RuntimeException e) {
            return new String[] {"?", "?"};
        }
    }

    private static Element firstElement(Document doc, String tag) {
        NodeList l = doc.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    private static String childText(Element parent, String tag) {
        if (parent == null) {
            return "?";
        }
        NodeList kids = parent.getElementsByTagName(tag);
        if (kids.getLength() == 0) {
            return "?";
        }
        String t = kids.item(0).getTextContent();
        return t == null ? "?" : t.trim();
    }

    private static String firstTagText(Document doc, String tag) {
        NodeList l = doc.getElementsByTagName(tag);
        if (l.getLength() == 0) {
            return "?";
        }
        String t = l.item(0).getTextContent();
        return t == null ? "?" : t.trim();
    }

    // ---- shared helpers -----------------------------------------------------------------------

    private static String driverDn(Environments.Environment env, String driver) {
        return VaultMapping.driverDn(env.driverSetDn, driver);
    }

    private static String jn(String s) {
        return s == null ? "null" : q(s);
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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

    private static String strArr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String intArr(int[] items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            sb.append(i == 0 ? "" : ",").append(items[i]);
        }
        return sb.append(']').toString();
    }
}
