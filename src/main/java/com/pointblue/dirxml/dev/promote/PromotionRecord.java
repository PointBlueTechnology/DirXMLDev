package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What was last promoted from one project into another ({@code promotions/<source>.json} in the target;
 * docs/promotion.md): per object, a fingerprint of the object as the source had it and as the target had it
 * afterwards. A file's fingerprint is of its content with every value set aside, so a value changed on one side
 * never makes its file look changed; a value has its own entry. A null fingerprint is an object that side
 * didn't have.
 *
 * <p>It stands in for the history two repositories don't share: comparing the fingerprints now with the
 * recorded ones tells which side changed an object since ({@link ProjectCompare.Bucket}).
 */
public final class PromotionRecord {

    /** One object's fingerprints at its last promotion (or at pairing), with where and when. */
    public static final class Entry {
        public final String source;
        public final String target;
        public final String commit;
        public final String at;
        public final String by;

        public Entry(String source, String target, String commit, String at, String by) {
            this.source = source;
            this.target = target;
            this.commit = commit;
            this.at = at;
            this.by = by;
        }
    }

    /** A file neither side had when the record was taken (pairing records every file either side has). */
    public static final Entry ABSENT = new Entry(null, null, null, null, null);

    /** The source project's name, as the pair's files are named. */
    public final String source;
    /** Files, by the target's tree path. */
    public final Map<String, Entry> objects = new TreeMap<>();
    /** Values, by their overrides key ({@code drivers/<d>.gcv.<n>}, …) as the target names the driver. */
    public final Map<String, Entry> values = new TreeMap<>();

    public PromotionRecord(String source) {
        this.source = source;
    }

    public boolean isEmpty() {
        return objects.isEmpty() && values.isEmpty();
    }

    public static PromotionRecord parse(String source, String text) {
        PromotionRecord r = new PromotionRecord(source);
        Map<String, Object> root = Json.asMap(Json.parse(text));
        read(Json.asMap(root.get("objects")), r.objects);
        read(Json.asMap(root.get("values")), r.values);
        return r;
    }

    private static void read(Map<String, Object> in, Map<String, Entry> out) {
        if (in == null) {
            return;
        }
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Map<String, Object> m = Json.asMap(e.getValue());
            out.put(e.getKey(), new Entry(Json.asString(m.get("source")), Json.asString(m.get("target")),
                Json.asString(m.get("commit")), Json.asString(m.get("at")), Json.asString(m.get("by"))));
        }
    }

    public String format() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", source);
        root.put("objects", write(objects));
        root.put("values", write(values));
        return Json.pretty(root) + "\n";
    }

    private static Map<String, Object> write(Map<String, Entry> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : in.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", e.getValue().source);
            m.put("target", e.getValue().target);
            m.put("commit", e.getValue().commit);
            m.put("at", e.getValue().at);
            m.put("by", e.getValue().by);
            out.put(e.getKey(), m);
        }
        return out;
    }

    /** A fingerprint: the first 12 bytes of a SHA-256, in hex; null for null (the side doesn't have it). */
    public static String fingerprint(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fingerprint(String text) {
        return text == null ? null : fingerprint(text.getBytes(StandardCharsets.UTF_8));
    }
}
