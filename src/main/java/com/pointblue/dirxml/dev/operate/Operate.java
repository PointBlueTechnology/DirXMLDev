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
import java.util.List;

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

        void startDriver(String dn);

        void stopDriver(String dn);

        void restartDriver(String dn);

        String waitForState(String dn, int wanted, int seconds);

        Vault.Entry read(String dn);

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

        void removeNamedPassword(String dn, String name);
    }

    /** Adapts a real, connected {@link Vault} to {@link Engine}. */
    public static Engine vaultEngine(Vault v) {
        return new Engine() {
            public int driverState(String dn) {
                return v.driverState(dn);
            }

            public int driverStartOption(String dn) {
                return v.driverStartOption(dn);
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
            String key = Secrets.named(driver, name);
            value = secrets.get(key);
            if (value == null) {
                return Result.refused("no secret '" + key + "' in the environment's secrets file (or use --stdin)");
            }
        }
        String dn = driverDn(env, driver);
        String error = null;
        try {
            engine.setNamedPassword(dn, name, name, value);
        } catch (RuntimeException e) {
            error = e.getMessage();
            Arrays.fill(value, '\0');
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        if (error == null) {
            rec.secretsSet.add(driver + ".named." + name);
        }
        rec.detail = "driver.secrets set '" + driver + "': name=" + name + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "set named password '" + name + "' on '" + driver + "'\n" : "FAILED   " + error + "\n";
        r.json = "{\"ok\":" + r.ok + ",\"name\":" + q(name) + (error != null ? ",\"error\":" + q(error) : "") + "}";
        return r;
    }

    public static Result secretsRemove(Engine engine, Environments.Environment env, String driver, String name,
            boolean yes, String confirm, Path tree) throws IOException {
        String refusal = gate(env, OpClass.LIGHT, yes, confirm);
        if (refusal != null) {
            return Result.refused(refusal);
        }
        String dn = driverDn(env, driver);
        String error = null;
        try {
            engine.removeNamedPassword(dn, name);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        DeployLog.Record rec = DeployLog.record(env.name, "operate");
        rec.outcome = error == null ? "ok" : "failed";
        rec.detail = "driver.secrets remove '" + driver + "': name=" + name + (error != null ? " — " + error : "");
        DeployLog.append(tree, rec);

        Result r = new Result();
        r.ok = error == null;
        r.text = error == null ? "removed named password '" + name + "' from '" + driver + "'\n" : "FAILED   " + error + "\n";
        r.json = "{\"ok\":" + r.ok + ",\"name\":" + q(name) + (error != null ? ",\"error\":" + q(error) : "") + "}";
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

    private static String q(String s) {
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
