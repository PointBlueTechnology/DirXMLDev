package com.pointblue.dirxml.dev.events;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A logged event as a simulator case: {@code cases/<name>/input.xds} is the row's XDS document
 * (as received when the store kept it; rebuilt from the JSON otherwise, and marked so), and
 * {@code case.properties} names the driver and channel to run it through and where it came from.
 * The tree's own {@code cases/} directory is the default, so a production sample becomes a
 * regression test in the repository; {@code --dir} writes elsewhere.
 */
public final class EventsCase {
    /** What a save would write: the files by path (relative to the case directory's parent), or a refusal. */
    public static final class Plan {
        public String refusal;
        public Path dir;
        public final Map<String, String> files = new LinkedHashMap<>();
        public boolean reconstructed;
        public boolean replaced;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", refusal == null);
            if (refusal != null) {
                m.put("refusal", refusal);
            }
            m.put("dir", dir == null ? null : dir.toString());
            m.put("files", files);
            m.put("reconstructed", reconstructed);
            m.put("replaced", replaced);
            return m;
        }
    }

    private EventsCase() {
    }

    /**
     * Plans the case. {@code driver} null = the row's driver (its leaf name) which must be in the
     * tree; {@code channel} null = the row's channel, else subscriber; {@code dir} null = the
     * tree's {@code cases/}.
     */
    public static Plan plan(Path tree, Event e, String envName, String name, String driver, String channel, Path dir, boolean replace, boolean masked) throws IOException {
        Plan p = new Plan();
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,79}")) {
            p.refusal = "a case name is a short file name (letters, digits, . _ - and spaces)";
            return p;
        }
        DriverSet ds = AsCodeReader.read(tree);
        String driverName = driver != null && !driver.isBlank() ? driver.trim() : e.driverName();
        if (driverName == null || driverName.isBlank()) {
            p.refusal = "the row names no driver; give --driver";
            return p;
        }
        Driver d = ds.driver(driverName);
        if (d == null) {
            p.refusal = "the tree has no driver '" + driverName + "'" + (driver == null ? " (the row's driver); name one with --driver" : "");
            return p;
        }
        String ch = channel != null && !channel.isBlank() ? channel.trim().toLowerCase(Locale.ROOT) : (e.channel() != null ? e.channel() : "subscriber");
        if (!ch.equals("subscriber") && !ch.equals("publisher")) {
            p.refusal = "channel is subscriber or publisher";
            return p;
        }
        String xml = e.xml();
        if (xml == null || xml.isBlank()) {
            if (e.schemaVersion() < 2) {
                p.refusal = "row " + e.id() + " has no XML and its JSON is schema version 1, which cannot be rebuilt faithfully";
                return p;
            }
            xml = JsonToXml.rebuild(e.json());
            if (xml == null) {
                p.refusal = "row " + e.id() + " has no XML (the driver ran with storeXML=false) and the logger jar that rebuilds one is not on the class path (dirxml-event-logger-<v>.jar)";
                return p;
            }
            p.reconstructed = true;
        }
        Path base = dir != null ? dir : tree.resolve("cases");
        p.dir = base.resolve(name);
        if (Files.exists(p.dir.resolve("case.properties")) && !replace) {
            p.refusal = "case '" + name + "' exists at " + p.dir + " (--replace to overwrite it)";
            return p;
        }
        p.replaced = Files.exists(p.dir.resolve("case.properties"));
        StringBuilder props = new StringBuilder();
        props.append("driver=").append(d.name).append('\n');
        props.append("channel=").append(ch).append('\n');
        props.append("source=events:").append(envName).append(':').append(e.id()).append('\n');
        if (e.eventId() != null) {
            props.append("sourceEventId=").append(e.eventId()).append('\n');
        }
        if (e.policy() != null) {
            props.append("sourcePolicy=").append(e.policy()).append('\n');
            props.append("sourceStage=").append(e.stage() == null ? "input" : e.stage()).append('\n');
        }
        if (e.time() != null) {
            props.append("sourceTime=").append(e.time()).append('\n');
        }
        if (p.reconstructed) {
            props.append("reconstructed=true\n");
        }
        if (masked) {
            props.append("pseudonymised=true\n");
        }
        p.files.put("case.properties", props.toString());
        p.files.put("input.xds", xml.endsWith("\n") ? xml : xml + "\n");
        return p;
    }

    /** Writes a planned case. */
    public static void write(Plan p) throws IOException {
        if (p.refusal != null) {
            throw new IOException(p.refusal);
        }
        Files.createDirectories(p.dir);
        for (Map.Entry<String, String> f : p.files.entrySet()) {
            Files.writeString(p.dir.resolve(f.getKey()), f.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** The logger's {@code JsonToXmlConverter}, when its jar is on the class path. */
    static final class JsonToXml {
        static String rebuild(Map<String, Object> json) {
            try {
                Class<?> c = Class.forName("com.pointblue.idm.eventlogger.xds2json.JsonToXmlConverter");
                Object conv = c.getDeclaredConstructor().newInstance();
                java.lang.reflect.Method m = c.getMethod("convertToXml", String.class);
                return String.valueOf(m.invoke(conv, com.pointblue.dirxml.dev.json.Json.compact(json)));
            } catch (ClassNotFoundException e) {
                return null;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("JsonToXmlConverter: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()), e);
            }
        }
    }
}
