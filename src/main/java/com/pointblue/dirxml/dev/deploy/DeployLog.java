package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit log: one JSON line per deploy (or rollback) in
 * {@code deploy-log/<env>.jsonl} under the tree, committed with the tree — the
 * deployment history, and what the production known-state check reads.
 *
 * <pre>
 * {"timestamp":"…","env":"stg","user":"…","operation":"deploy","treeCommit":"…",
 *  "outcome":"ok|failed|partial","snapshot":"deploy-snapshots/stg/….ldif",
 *  "changes":12,"restarted":["AD"],"secretsSet":["AD.named.x"],"detail":"…"}
 * </pre>
 * Secrets appear by name only. Never edited, only appended.
 */
public final class DeployLog {

    public static final String DIR = "deploy-log";

    public static final class Record {
        public String timestamp;
        public String env;
        public String user;
        public String operation;     // deploy | rollback | secrets
        public String treeCommit;    // null when the tree isn't in git
        public String outcome;       // ok | failed | partial | refused
        public String snapshot;      // relative path, or null
        public int changes;
        public List<String> restarted = new ArrayList<>();
        public List<String> secretsSet = new ArrayList<>();
        public String detail;

        public String json() {
            StringBuilder sb = new StringBuilder("{");
            field(sb, "timestamp", timestamp).append(',');
            field(sb, "env", env).append(',');
            field(sb, "user", user).append(',');
            field(sb, "operation", operation).append(',');
            field(sb, "treeCommit", treeCommit).append(',');
            field(sb, "outcome", outcome).append(',');
            field(sb, "snapshot", snapshot).append(',');
            sb.append("\"changes\":").append(changes).append(',');
            sb.append("\"restarted\":").append(arr(restarted)).append(',');
            sb.append("\"secretsSet\":").append(arr(secretsSet)).append(',');
            field(sb, "detail", detail);
            return sb.append('}').toString();
        }
    }

    private DeployLog() {
    }

    public static Path file(Path tree, String env) {
        return tree.resolve(DIR).resolve(env + ".jsonl");
    }

    public static Record record(String env, String operation) {
        Record r = new Record();
        r.timestamp = Instant.now().toString();
        r.env = env;
        r.user = System.getProperty("user.name");
        r.operation = operation;
        return r;
    }

    /** Append one line. Creates the directory and file as needed. */
    public static void append(Path tree, Record r) throws IOException {
        Path f = file(tree, r.env);
        Files.createDirectories(f.getParent());
        Files.writeString(f, r.json() + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** All records for an environment, oldest first (empty when there's no log). */
    public static List<Record> read(Path tree, String env) throws IOException {
        Path f = file(tree, env);
        List<Record> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
        for (String line : Files.readString(f, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            out.add(parse(line));
        }
        return out;
    }

    /** The last successful deploy to an environment, or null. */
    public static Record lastOk(Path tree, String env) throws IOException {
        List<Record> all = read(tree, env);
        for (int i = all.size() - 1; i >= 0; i--) {
            Record r = all.get(i);
            if ("deploy".equals(r.operation) && "ok".equals(r.outcome)) {
                return r;
            }
        }
        return null;
    }

    /** True if a green deploy of {@code commit} to {@code env} is on record. */
    public static boolean hasOkDeploy(Path tree, String env, String commit) throws IOException {
        if (commit == null) {
            return false;
        }
        for (Record r : read(tree, env)) {
            if ("deploy".equals(r.operation) && "ok".equals(r.outcome) && commit.equals(r.treeCommit)) {
                return true;
            }
        }
        return false;
    }

    /** The tree's git HEAD commit, or null when it isn't a git checkout (or git isn't available). */
    public static String treeCommit(Path tree) {
        try {
            Process p = new ProcessBuilder("git", "-C", tree.toAbsolutePath().toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            return p.waitFor() == 0 && out.matches("[0-9a-f]{40}") ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the tree has uncommitted changes (null commit → false). */
    public static boolean treeDirty(Path tree) {
        try {
            Process p = new ProcessBuilder("git", "-C", tree.toAbsolutePath().toString(), "status", "--porcelain")
                .redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            return p.waitFor() == 0 && !out.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- a small flat-JSON parser for our own lines ----

    static Record parse(String line) {
        Map<String, Object> m = flat(line);
        Record r = new Record();
        r.timestamp = str(m, "timestamp");
        r.env = str(m, "env");
        r.user = str(m, "user");
        r.operation = str(m, "operation");
        r.treeCommit = str(m, "treeCommit");
        r.outcome = str(m, "outcome");
        r.snapshot = str(m, "snapshot");
        Object c = m.get("changes");
        r.changes = c instanceof Number ? ((Number) c).intValue() : 0;
        r.restarted = list(m, "restarted");
        r.secretsSet = list(m, "secretsSet");
        r.detail = str(m, "detail");
        return r;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List ? (List<String>) v : new ArrayList<>();
    }

    /** Parses {@code {"k":"v","n":1,"a":["x"],"z":null}} — the only shape we write. */
    static Map<String, Object> flat(String s) {
        Map<String, Object> out = new LinkedHashMap<>();
        int i = s.indexOf('{') + 1;
        while (i < s.length()) {
            while (i < s.length() && (s.charAt(i) == ',' || s.charAt(i) == ' ')) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) == '}') {
                break;
            }
            int[] pos = {i};
            String key = readString(s, pos);
            i = pos[0];
            while (s.charAt(i) != ':') {
                i++;
            }
            i++;
            while (s.charAt(i) == ' ') {
                i++;
            }
            char c = s.charAt(i);
            if (c == '"') {
                pos[0] = i;
                out.put(key, readString(s, pos));
                i = pos[0];
            } else if (c == '[') {
                List<String> items = new ArrayList<>();
                i++;
                while (s.charAt(i) != ']') {
                    if (s.charAt(i) == '"') {
                        pos[0] = i;
                        items.add(readString(s, pos));
                        i = pos[0];
                    } else {
                        i++;
                    }
                }
                i++;
                out.put(key, items);
            } else if (s.startsWith("null", i)) {
                out.put(key, null);
                i += 4;
            } else {
                int j = i;
                while (j < s.length() && "-0123456789.".indexOf(s.charAt(j)) >= 0) {
                    j++;
                }
                out.put(key, Integer.parseInt(s.substring(i, j)));
                i = j;
            }
        }
        return out;
    }

    private static String readString(String s, int[] pos) {
        int i = pos[0] + 1;   // after the opening quote
        StringBuilder sb = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\') {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
            i++;
        }
        pos[0] = i + 1;
        return sb.toString();
    }

    private static StringBuilder field(StringBuilder sb, String k, String v) {
        sb.append('"').append(k).append("\":");
        return v == null ? sb.append("null") : sb.append(q(v));
    }

    private static String arr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(items.get(i)));
        }
        return sb.append(']').toString();
    }

    static String q(String s) {
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
}
