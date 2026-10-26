package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.deploy.EnvironmentCompare;
import com.pointblue.dirxml.dev.deploy.ModelDiff;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.KnownDifference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * {@code idm tree.compare <target> --with <source>} (docs/promotion.md): what differs between two projects, file by
 * file and value by value, in the target's terms ({@link Sides}), each difference sorted by the pair's promotion
 * record ({@link PromotionRecord}) into a {@link Bucket}.
 */
public final class ProjectCompare {

    /** Where a difference stands, from the record: which side changed it since its last promotion. */
    public enum Bucket {
        /** The source changed it, the target didn't: waiting to be promoted. */
        PENDING("pending"),
        /** The target changed it, the source didn't: left alone unless chosen. */
        OWN("target's own"),
        /** Both changed it: to settle side by side. */
        BOTH("both changed"),
        /** Accepted as it is ({@code promotions/<source>.known.json}) and unchanged since. */
        KNOWN("known"),
        /** Neither side changed it since the record: as it was left at pairing or the last promotion. */
        UNCHANGED("unchanged since the record"),
        /** No record of it yet: a plain difference. */
        UNPAIRED("not paired yet");

        public final String label;

        Bucket(String label) {
            this.label = label;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One file or one value that differs. */
    public static final class Item {
        /** A tree path (a file) or an overrides key (a value), as the target names it. */
        public final String path;
        public final boolean value;
        /** The driver it belongs to, by the target's name; null for the set's own or the library. */
        public final String driver;
        public final Bucket bucket;
        /** Fingerprints now: of the neutral file, or the value; null where that side doesn't have it. */
        public final String sourceFingerprint;
        public final String targetFingerprint;
        /** The difference's own fingerprint, which accepting it as known records. */
        public final String fingerprint;
        /** Values only: each side's. */
        public final String sourceValue;
        public final String targetValue;
        /** Files only: the changed lines with a little context, target ({@code -}) to source ({@code +}). */
        public final String detail;
        /** It was accepted as known once and has changed since. */
        public final boolean changedSinceKnown;

        Item(String path, boolean value, String driver, Bucket bucket, String sourceFingerprint, String targetFingerprint, String fingerprint,
             String sourceValue, String targetValue, String detail, boolean changedSinceKnown) {
            this.path = path;
            this.value = value;
            this.driver = driver;
            this.bucket = bucket;
            this.sourceFingerprint = sourceFingerprint;
            this.targetFingerprint = targetFingerprint;
            this.fingerprint = fingerprint;
            this.sourceValue = sourceValue;
            this.targetValue = targetValue;
            this.detail = detail;
            this.changedSinceKnown = changedSinceKnown;
        }

        /** {@code only in the source}, {@code only in the target} or {@code differs}. */
        public String state() {
            if (targetFingerprint == null) {
                return "only in the source";
            }
            if (sourceFingerprint == null) {
                return "only in the target";
            }
            return "differs";
        }
    }

    public final String pair;
    public final Path target;
    public final Path source;
    public final List<Item> items = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    /** Drivers only one side holds that look like one another under other names: candidates for the map. */
    public final List<EnvironmentCompare.Match> matches = new ArrayList<>();
    public final boolean paired;

    private ProjectCompare(String pair, Path target, Path source, boolean paired) {
        this.pair = pair;
        this.target = target;
        this.source = source;
        this.paired = paired;
    }

    public static ProjectCompare of(Path target, Path source, String pairName) throws IOException {
        Pair pair = Pair.read(target, pairName);
        return of(Sides.read(target, source, pair.map), pair);
    }

    static ProjectCompare of(Sides s, Pair pair) {
        ProjectCompare c = new ProjectCompare(pair.name, s.targetTree, s.sourceTree, !pair.record.isEmpty());
        c.notes.addAll(s.notes);
        TreeSet<String> paths = new TreeSet<>(s.targetFiles.keySet());
        paths.addAll(s.sourceFiles.keySet());
        for (String path : paths) {
            byte[] src = s.sourceFiles.get(path);
            byte[] tgt = s.targetFiles.get(path);
            if (src != null && tgt != null && java.util.Arrays.equals(src, tgt)) {
                continue;
            }
            String sf = PromotionRecord.fingerprint(src);
            String tf = PromotionRecord.fingerprint(tgt);
            PromotionRecord.Entry rec = pair.record.objects.get(path);
            if (rec == null && c.paired) {
                rec = PromotionRecord.ABSENT;   // the record holds every file both had: this one neither had
            }
            c.add(pair, rec, path, false, s.driverOf(path), sf, tf, null, null,
                detail(tgt == null ? "" : new String(tgt, StandardCharsets.UTF_8), src == null ? "" : new String(src, StandardCharsets.UTF_8)));
        }
        for (Map.Entry<String, String> e : s.targetValues.entrySet()) {
            String key = e.getKey();
            if (!s.sourceValues.containsKey(key) || Objects.equals(e.getValue(), s.sourceValues.get(key))) {
                continue;   // a value only one side has comes with its file
            }
            String sv = s.sourceValues.get(key);
            c.add(pair, pair.record.values.get(key), key, true, s.driverOf(key), valuePrint(sv), valuePrint(e.getValue()), sv, e.getValue(), null);
        }
        c.matches.addAll(EnvironmentCompare.matches(s.source, s.target, pair.name, Pair.nameOf(s.targetTree)));
        return c;
    }

    /** A value's fingerprint: a value the model holds as none is a value too. */
    static String valuePrint(String v) {
        return PromotionRecord.fingerprint(v == null ? "\u0000none" : v);
    }

    private void add(Pair pair, PromotionRecord.Entry rec, String path, boolean value, String driver, String sf, String tf,
                     String sv, String tv, String detail) {
        String fingerprint = PromotionRecord.fingerprint(path + "\n" + sf + "\n" + tf);
        boolean changedSinceKnown = false;
        Bucket bucket = null;
        for (KnownDifference k : pair.known) {
            if (k.path.equals(path)) {
                if (fingerprint.equals(k.fingerprint)) {
                    bucket = Bucket.KNOWN;
                } else {
                    changedSinceKnown = true;
                }
            }
        }
        if (bucket == null) {
            bucket = bucket(rec, sf, tf);
        }
        items.add(new Item(path, value, driver, bucket, sf, tf, fingerprint, sv, tv, detail, changedSinceKnown));
    }

    static Bucket bucket(PromotionRecord.Entry rec, String sourceNow, String targetNow) {
        if (rec == null) {
            return Bucket.UNPAIRED;
        }
        boolean sourceChanged = !Objects.equals(rec.source, sourceNow);
        boolean targetChanged = !Objects.equals(rec.target, targetNow);
        if (sourceChanged && targetChanged) {
            return Bucket.BOTH;
        }
        if (sourceChanged) {
            return Bucket.PENDING;
        }
        return targetChanged ? Bucket.OWN : Bucket.UNCHANGED;
    }

    /** The changed lines with two lines of context around each run; null when nothing changed. */
    static String detail(String targetText, String sourceText) {
        String all = ModelDiff.textDiff(targetText, sourceText);
        if (all == null) {
            return null;
        }
        String[] lines = all.split("\n", -1);
        boolean[] keep = new boolean[lines.length];
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("  ")) {
                any = true;
                for (int j = Math.max(0, i - 2); j <= Math.min(lines.length - 1, i + 2); j++) {
                    keep[j] = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < lines.length; i++) {
            if (keep[i]) {
                if (gap && sb.length() > 0) {
                    sb.append("  …\n");
                }
                sb.append(lines[i]).append('\n');
                gap = false;
            } else {
                gap = true;
            }
        }
        return sb.toString().stripTrailing();
    }

    public List<Item> in(Bucket b) {
        return items.stream().filter(i -> i.bucket == b).toList();
    }

    /** Differences someone has to look at: everything but known and unchanged-since-the-record. */
    public boolean hasOpen() {
        return items.stream().anyMatch(i -> i.bucket != Bucket.KNOWN && i.bucket != Bucket.UNCHANGED);
    }

    public Item item(String path) {
        for (Item i : items) {
            if (i.path.equals(path)) {
                return i;
            }
        }
        return null;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append(target).append(" compared with ").append(source).append(" (pair '").append(pair).append("'")
          .append(paired ? "" : ", no promotion record yet: every difference is shown plain").append(")\n");
        for (String n : notes) {
            sb.append("note: ").append(n).append('\n');
        }
        if (items.isEmpty()) {
            sb.append("no differences\n");
        }
        for (Bucket b : List.of(Bucket.PENDING, Bucket.BOTH, Bucket.OWN, Bucket.UNPAIRED)) {
            List<Item> list = in(b);
            if (list.isEmpty()) {
                continue;
            }
            sb.append(b.label).append(" (").append(list.size()).append("):\n");
            for (Item i : list) {
                sb.append("  ").append(i.path).append("  ");
                if (i.value) {
                    sb.append("value: source=").append(i.sourceValue == null ? "(none)" : i.sourceValue)
                      .append("  target=").append(i.targetValue == null ? "(none)" : i.targetValue);
                } else {
                    sb.append(i.state());
                }
                sb.append(i.changedSinceKnown ? "  (accepted once, changed since)" : "").append('\n');
            }
        }
        int known = in(Bucket.KNOWN).size();
        int unchanged = in(Bucket.UNCHANGED).size();
        if (known + unchanged > 0) {
            sb.append(known).append(" known, ").append(unchanged).append(" unchanged since the record (not listed)\n");
        }
        if (!matches.isEmpty()) {
            sb.append("drivers only one side has that look alike (map them with drivers/<source's name>.name = <the target's>):\n");
            for (EnvironmentCompare.Match m : matches) {
                sb.append("  ").append(m.a).append(" (source) ~ ").append(m.b).append(" (target), ").append(m.common).append(" of ").append(m.of)
                  .append(" policies in common\n");
            }
        }
        return sb.toString();
    }

    public String json() {
        return Json.compact(toMap());
    }

    Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pair", pair);
        root.put("target", target.toString());
        root.put("source", source.toString());
        root.put("paired", paired);
        List<Object> list = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", i.path);
            m.put("kind", i.value ? "value" : "file");
            m.put("driver", i.driver);
            m.put("bucket", i.bucket.key());
            m.put("state", i.state());
            m.put("fingerprint", i.fingerprint);
            if (i.value) {
                m.put("source", i.sourceValue);
                m.put("target", i.targetValue);
            } else {
                m.put("detail", i.detail);
            }
            m.put("changedSinceKnown", i.changedSinceKnown);
            list.add(m);
        }
        root.put("items", list);
        List<Object> ms = new ArrayList<>();
        for (EnvironmentCompare.Match m : matches) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("source", m.a);
            o.put("target", m.b);
            o.put("common", m.common);
            o.put("of", m.of);
            ms.add(o);
        }
        root.put("matches", ms);
        root.put("notes", new ArrayList<Object>(notes));
        return root;
    }
}
