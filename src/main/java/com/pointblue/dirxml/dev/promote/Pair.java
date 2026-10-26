package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.deploy.Overrides;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.KnownDifference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One pair of projects, as the target keeps it ({@code docs/promotion.md}): under the target's tree,
 * <pre>
 *   promotions/&lt;source&gt;.properties   the map, in the overrides file's form: drivers/&lt;source's name&gt;.name = &lt;the target's&gt;,
 *                                    servers.&lt;source server's name&gt; = &lt;the target's server's DN&gt;
 *   promotions/&lt;source&gt;.known.json   differences accepted as they are
 *   promotions/&lt;source&gt;.json         the promotion record ({@link PromotionRecord})
 * </pre>
 * All three are committed with the target's tree, so they are reviewed and travel with it.
 */
public final class Pair {

    public static final String DIR = "promotions";

    public final String name;
    public final Map<String, String> map;
    public final List<KnownDifference> known;
    public final PromotionRecord record;

    private Pair(String name, Map<String, String> map, List<KnownDifference> known, PromotionRecord record) {
        this.name = name;
        this.map = map;
        this.known = known;
        this.record = record;
    }

    /** The name a source tree pairs under when none is given: its directory's, or its project's when the directory is {@code tree}. */
    public static String nameOf(Path source) {
        Path abs = source.toAbsolutePath().normalize();
        Path leaf = abs.getFileName();
        if (leaf != null && leaf.toString().equals("tree") && abs.getParent() != null && abs.getParent().getFileName() != null) {
            leaf = abs.getParent().getFileName();
        }
        return leaf == null ? "source" : leaf.toString();
    }

    /** True for a name the pair's files can carry. */
    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    public static Pair read(Path target, String name) throws IOException {
        if (!validName(name)) {
            throw new IllegalArgumentException("a pair's name is letters, digits, '.', '_' and '-' (got '" + name + "')");
        }
        Path dir = target.resolve(DIR);
        Path mapFile = dir.resolve(name + Overrides.EXT);
        Map<String, String> map = Files.isRegularFile(mapFile)
            ? Overrides.parse(Files.readString(mapFile, StandardCharsets.UTF_8)) : new LinkedHashMap<>();
        List<KnownDifference> known = new ArrayList<>();
        Path knownFile = dir.resolve(name + ".known.json");
        if (Files.isRegularFile(knownFile)) {
            for (Object o : Json.asList(Json.asMap(Json.parse(Files.readString(knownFile, StandardCharsets.UTF_8))).get("known"))) {
                Map<String, Object> m = Json.asMap(o);
                known.add(new KnownDifference(name, Json.asString(m.get("path")), Json.asString(m.get("fingerprint")),
                    Json.asString(m.get("decision")), Json.asString(m.get("summary")), Json.asString(m.get("note")),
                    Json.asString(m.get("by")), Json.asString(m.get("at"))));
            }
        }
        Path recordFile = dir.resolve(name + ".json");
        PromotionRecord record = Files.isRegularFile(recordFile)
            ? PromotionRecord.parse(name, Files.readString(recordFile, StandardCharsets.UTF_8)) : new PromotionRecord(name);
        return new Pair(name, map, known, record);
    }

    /** The files this pair keeps, tree-relative. */
    public static List<String> files(String name) {
        return List.of(DIR + "/" + name + Overrides.EXT, DIR + "/" + name + ".known.json", DIR + "/" + name + ".json");
    }

    public String formatKnown() {
        List<Object> list = new ArrayList<>();
        for (KnownDifference k : known) {
            Map<String, Object> m = new LinkedHashMap<>();
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
        root.put("source", name);
        root.put("known", list);
        return Json.pretty(root) + "\n";
    }

    /** Write the known differences and the record (the map is a person's: never rewritten); an empty one loses its file. */
    public void write(Path target) throws IOException {
        Path dir = target.resolve(DIR);
        Files.createDirectories(dir);
        Path knownFile = dir.resolve(name + ".known.json");
        if (known.isEmpty()) {
            Files.deleteIfExists(knownFile);
        } else {
            Files.writeString(knownFile, formatKnown(), StandardCharsets.UTF_8);
        }
        Path recordFile = dir.resolve(name + ".json");
        if (record.isEmpty()) {
            Files.deleteIfExists(recordFile);
        } else {
            Files.writeString(recordFile, record.format(), StandardCharsets.UTF_8);
        }
    }
}
