package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.KnownDifference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** {@code overrides/<env>.known.json}: the differences of one environment accepted as they are ({@link KnownDifference}). */
public final class KnownDifferences {

    public static final String EXT = ".known.json";

    private KnownDifferences() {
    }

    public static Map<String, List<KnownDifference>> read(Path tree) throws IOException {
        Map<String, List<KnownDifference>> out = new TreeMap<>();
        Path dir = tree.resolve(Overrides.DIR);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(EXT)).sorted()::iterator) {
                String env = p.getFileName().toString();
                env = env.substring(0, env.length() - EXT.length());
                List<KnownDifference> list = new ArrayList<>();
                for (Object o : Json.asList(Json.asMap(Json.parse(Files.readString(p, StandardCharsets.UTF_8))).get("known"))) {
                    Map<String, Object> m = Json.asMap(o);
                    list.add(new KnownDifference(Json.asString(m.get("with")), Json.asString(m.get("path")), Json.asString(m.get("fingerprint")),
                        Json.asString(m.get("decision")), Json.asString(m.get("summary")), Json.asString(m.get("note")),
                        Json.asString(m.get("by")), Json.asString(m.get("at"))));
                }
                out.put(env, list);
            }
        }
        return out;
    }

    /** One file per environment with entries; an environment whose list emptied loses its file. */
    public static void write(Path tree, Map<String, List<KnownDifference>> known) throws IOException {
        Path dir = tree.resolve(Overrides.DIR);
        for (Map.Entry<String, List<KnownDifference>> e : known.entrySet()) {
            Path f = dir.resolve(e.getKey() + EXT);
            if (e.getValue().isEmpty()) {
                Files.deleteIfExists(f);
                continue;
            }
            Files.createDirectories(dir);
            List<Object> list = new ArrayList<>();
            for (KnownDifference k : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("with", k.with);
                m.put("path", k.path);
                m.put("fingerprint", k.fingerprint);
                m.put("decision", k.decision);
                m.put("summary", k.summary);
                if (k.note != null && !k.note.isBlank()) {
                    m.put("note", k.note);
                }
                m.put("by", k.by);
                m.put("at", k.at);
                list.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("environment", e.getKey());
            root.put("known", list);
            Files.writeString(f, Json.pretty(root) + "\n", StandardCharsets.UTF_8);
        }
    }

    /** A change's fingerprint: its kind, path and what it shows changed, hashed. */
    public static String fingerprint(ModelDiff.Change ch) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String text = ch.kind + "\n" + ch.path + "\n" + (ch.detail == null ? ch.summary : ch.detail);
            byte[] h = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The accepted differences of the pair (each environment's file, naming the other). */
    public static List<KnownDifference> forPair(Map<String, List<KnownDifference>> known, String envA, String envB) {
        List<KnownDifference> out = new ArrayList<>();
        for (KnownDifference k : known.getOrDefault(envA, List.of())) {
            if (envB.equalsIgnoreCase(k.with)) {
                out.add(k);
            }
        }
        for (KnownDifference k : known.getOrDefault(envB, List.of())) {
            if (envA.equalsIgnoreCase(k.with)) {
                out.add(k);
            }
        }
        return out;
    }
}
