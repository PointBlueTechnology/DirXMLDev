package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.DeployLog;
import com.pointblue.dirxml.dev.deploy.Overrides;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.KnownDifference;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Moving differences from one project into another (docs/promotion.md): {@code promote.plan} and
 * {@code promote.apply}, plus {@code promote.pair} (the first record) and {@code promote.accept} /
 * {@code promote.forget} (known differences).
 *
 * <p>A promotion copies the chosen files of the source, as the target names them, into the target; values follow
 * the target unless chosen, so a promoted policy brings its logic, not the source's URL. The result is read back and
 * validated: a promotion that would add a validation error, or a file the target's model wouldn't hold (a policy
 * whose manifest entry wasn't promoted with it), is refused with the reason. Then the target's tree is written (only
 * the files that changed) and the record takes the promoted objects' fingerprints. Committing, the branch and the pull
 * request are the caller's: the result gives the commit trailer.
 */
public final class Promote {

    private Promote() {
    }

    /** What to promote. */
    public static final class Selection {
        /** Tree paths of files, or of directories (every differing file under one: {@code drivers/HR Feed}). */
        public final List<String> objects = new ArrayList<>();
        /** Overrides keys of values to take from the source. */
        public final List<String> values = new ArrayList<>();
        /** Every pending file (values are never moved unless named). */
        public boolean pending;
        /** A file's content as the person merged it, line by line, in place of the source's: chooses that file too. */
        public final Map<String, String> contents = new LinkedHashMap<>();
    }

    public static final class Result {
        public final String op;
        public String refusal;
        public boolean dryRun;
        public boolean written;
        public final List<String> promoted = new ArrayList<>();
        public final List<String> values = new ArrayList<>();
        public final List<String> changedFiles = new ArrayList<>();
        public final List<String> deletedFiles = new ArrayList<>();
        public final List<Finding> newErrors = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public String sourceCommit;
        public String trailer;

        Result(String op) {
            this.op = op;
        }

        public boolean ok() {
            return refusal == null;
        }

        public String text() {
            StringBuilder sb = new StringBuilder(op).append(dryRun ? " (dry run)" : "").append(": ");
            if (refusal != null) {
                sb.append("refused: ").append(refusal).append('\n');
            } else {
                sb.append(written ? "written" : "would write").append('\n');
            }
            for (String n : notes) {
                sb.append("note: ").append(n).append('\n');
            }
            for (Finding f : newErrors) {
                sb.append("  new error: ").append(f.code).append(' ').append(f.path).append(": ").append(f.message).append('\n');
            }
            list(sb, "promoted", promoted);
            list(sb, "values from the source", values);
            list(sb, "changed", changedFiles);
            list(sb, "deleted", deletedFiles);
            if (trailer != null && refusal == null) {
                sb.append("commit trailer: ").append(trailer).append('\n');
            }
            return sb.toString();
        }

        private static void list(StringBuilder sb, String what, List<String> l) {
            if (!l.isEmpty()) {
                sb.append(what).append(":\n");
                for (String s : l) {
                    sb.append("  ").append(s).append('\n');
                }
            }
        }

        public String json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op);
            m.put("ok", ok());
            m.put("refusal", refusal);
            m.put("dryRun", dryRun);
            m.put("written", written);
            m.put("promoted", new ArrayList<Object>(promoted));
            m.put("values", new ArrayList<Object>(values));
            m.put("changedFiles", new ArrayList<Object>(changedFiles));
            m.put("deletedFiles", new ArrayList<Object>(deletedFiles));
            List<Object> errors = new ArrayList<>();
            for (Finding f : newErrors) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("code", f.code);
                e.put("path", f.path);
                e.put("message", f.message);
                errors.add(e);
            }
            m.put("newErrors", errors);
            m.put("notes", new ArrayList<Object>(notes));
            m.put("sourceCommit", sourceCommit);
            m.put("trailer", trailer);
            return Json.compact(m);
        }
    }

    // ---- plan / apply ----

    /** {@code promote.apply}, or with {@code dryRun} {@code promote.plan}: everything but the writing. */
    public static Result run(Path target, Path source, String pairName, Selection sel, boolean dryRun, boolean force, String by)
            throws IOException {
        return run(target, source, pairName, sel, dryRun, force, by, Validator.standard());
    }

    static Result run(Path target, Path source, String pairName, Selection sel, boolean dryRun, boolean force, String by, Validator validator)
            throws IOException {
        Result r = new Result(dryRun ? "promote.plan" : "promote.apply");
        r.dryRun = dryRun;
        Pair pair = Pair.read(target, pairName);
        Sides s = Sides.read(target, source, pair.map);
        ProjectCompare cmp = ProjectCompare.of(s, pair);
        r.notes.addAll(s.notes);

        // what was chosen
        Set<String> chosen = new TreeSet<>();
        for (String o : sel.objects) {
            String p = o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
            List<String> hit = new ArrayList<>();
            for (ProjectCompare.Item i : cmp.items) {
                if (!i.value && (i.path.equals(p) || i.path.startsWith(p + "/"))) {
                    hit.add(i.path);
                }
            }
            if (hit.isEmpty()) {
                r.refusal = "nothing differs at '" + o + "'";
                return r;
            }
            chosen.addAll(hit);
        }
        if (sel.pending) {
            for (ProjectCompare.Item i : cmp.in(ProjectCompare.Bucket.PENDING)) {
                if (!i.value) {
                    chosen.add(i.path);
                }
            }
        }
        for (String p : sel.contents.keySet()) {
            ProjectCompare.Item i = cmp.item(p);
            if (i == null || i.value) {
                r.refusal = "nothing differs at '" + p + "' (merged content is for a file that differs)";
                return r;
            }
            chosen.add(p);
        }
        Set<String> values = new TreeSet<>();
        for (String k : sel.values) {
            ProjectCompare.Item i = cmp.item(k);
            if (i == null || !i.value) {
                r.refusal = "no value differs at '" + k + "' (a value both projects have, by its overrides key)";
                return r;
            }
            values.add(k);
        }
        if (chosen.isEmpty() && values.isEmpty()) {
            r.refusal = "nothing chosen: name files (--object), values (--value) or --pending";
            return r;
        }
        r.promoted.addAll(chosen);
        r.values.addAll(values);
        r.sourceCommit = DeployLog.treeCommit(source);
        if (r.sourceCommit == null) {
            r.notes.add("the source isn't a git checkout: the record names no commit");
        } else if (DeployLog.treeDirty(source)) {
            r.notes.add("the source has uncommitted changes: they are promoted, but the record names its last commit");
        }
        r.trailer = "Promoted-from: " + pair.name + "@" + (r.sourceCommit == null ? "uncommitted" : r.sourceCommit);

        Path sourceWritten = Files.createTempDirectory("idm-promote-src");
        Path work = Files.createTempDirectory("idm-promote-work");
        Path out = Files.createTempDirectory("idm-promote-out");
        try {
            AsCodeWriter.write(s.source, sourceWritten);
            copyTree(target, work);
            Set<String> present = new HashSet<>();
            for (String p : chosen) {
                Path w = work.resolve(p);
                Path from = sourceWritten.resolve(p);
                if (sel.contents.containsKey(p)) {
                    Files.createDirectories(w.getParent());
                    Files.writeString(w, sel.contents.get(p), StandardCharsets.UTF_8);
                    present.add(p);
                } else if (Files.isRegularFile(from)) {
                    Files.createDirectories(w.getParent());
                    Files.copy(from, w, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    present.add(p);
                } else {
                    Files.deleteIfExists(w);
                }
                // a packaged file's baseline goes with it: the source's, or none
                Path baseline = work.resolve(AsCodeWriter.BASELINE_DIR).resolve(p);
                Path sourceBaseline = source.resolve(AsCodeWriter.BASELINE_DIR).resolve(s.sourcePath(p));
                if (present.contains(p) && Files.isRegularFile(sourceBaseline)) {
                    Files.createDirectories(baseline.getParent());
                    Files.copy(sourceBaseline, baseline, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(baseline);
                }
            }

            DriverSet after;
            try {
                after = AsCodeReader.read(work);
            } catch (RuntimeException | IOException e) {
                r.refusal = "the promoted tree can't be read: " + e.getMessage();
                return r;
            }
            // values: the target's, but for the ones chosen
            for (Map.Entry<String, String> e : s.targetValues.entrySet()) {
                Overrides.Target t = Overrides.resolve(after, e.getKey());
                if (!t.resolved()) {
                    continue;
                }
                String want = values.contains(e.getKey()) ? s.sourceValues.get(e.getKey()) : e.getValue();
                if (!Objects.equals(t.value(), want)) {
                    t.set(want == null && t.definition != null ? "" : want);
                }
            }

            AsCodeWriter.write(after, out);
            Map<String, byte[]> produced = Sides.read(out);
            for (String p : chosen) {
                boolean wanted = present.contains(p);
                if (wanted && !produced.containsKey(p)) {
                    r.refusal = p + " isn't part of the target's model once promoted: " + manifestOf(p) + " doesn't list it. Promote "
                        + manifestOf(p) + " (or its lines for it) too";
                    return r;
                }
                if (!wanted && produced.containsKey(p)) {
                    r.refusal = p + " is removed in the source, but the target's model still holds it: promote " + manifestOf(p) + " too";
                    return r;
                }
            }

            Report before = validator.validate(s.target);
            Report afterReport = validator.validate(after);
            r.newErrors.addAll(newErrors(before, afterReport));
            if (!r.newErrors.isEmpty() && !force) {
                r.refusal = "the promotion would introduce " + r.newErrors.size() + " validation error(s); nothing written (--force writes anyway)";
                return r;
            }

            // write the target: the files whose bytes change, the model's files it no longer makes, the baselines
            Map<String, byte[]> producedBefore = Sides.written(s.target);
            for (Map.Entry<String, byte[]> e : produced.entrySet()) {
                Path t = target.resolve(e.getKey());
                if (!Files.isRegularFile(t) || !Arrays.equals(Files.readAllBytes(t), e.getValue())) {
                    r.changedFiles.add(e.getKey());
                    if (!dryRun) {
                        Files.createDirectories(t.getParent());
                        Files.write(t, e.getValue());
                    }
                }
            }
            for (String p : producedBefore.keySet()) {
                if (!produced.containsKey(p) && Files.isRegularFile(target.resolve(p))) {
                    r.deletedFiles.add(p);
                    if (!dryRun) {
                        Files.delete(target.resolve(p));
                    }
                }
            }
            for (String p : chosen) {
                String rel = AsCodeWriter.BASELINE_DIR + "/" + p;
                Path w = work.resolve(rel);
                Path t = target.resolve(rel);
                if (Files.isRegularFile(w)) {
                    if (!Files.isRegularFile(t) || !Arrays.equals(Files.readAllBytes(t), Files.readAllBytes(w))) {
                        r.changedFiles.add(rel);
                        if (!dryRun) {
                            Files.createDirectories(t.getParent());
                            Files.copy(w, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } else if (Files.isRegularFile(t)) {
                    r.deletedFiles.add(rel);
                    if (!dryRun) {
                        Files.delete(t);
                    }
                }
            }

            // the record: each promoted object as the source has it and as the target has it now
            DriverSet afterNeutral = AsCodeReader.read(out);
            Sides.neutral(afterNeutral);
            Map<String, byte[]> afterFiles = Sides.files(afterNeutral);
            Map<String, String> afterValues = com.pointblue.dirxml.dev.deploy.EnvironmentCompare.values(after);
            String at = now();
            for (String p : chosen) {
                pair.record.objects.put(p, new PromotionRecord.Entry(PromotionRecord.fingerprint(s.sourceFiles.get(p)),
                    PromotionRecord.fingerprint(afterFiles.get(p)), r.sourceCommit, at, by));
            }
            for (String k : values) {
                pair.record.values.put(k, new PromotionRecord.Entry(ProjectCompare.valuePrint(s.sourceValues.get(k)),
                    ProjectCompare.valuePrint(afterValues.get(k)), r.sourceCommit, at, by));
            }
            // a value both now hold that the record lacks (a promoted file brought its definition): recorded as it is
            for (Map.Entry<String, String> e : afterValues.entrySet()) {
                if (s.sourceValues.containsKey(e.getKey()) && !pair.record.values.containsKey(e.getKey())) {
                    pair.record.values.put(e.getKey(), new PromotionRecord.Entry(ProjectCompare.valuePrint(s.sourceValues.get(e.getKey())),
                        ProjectCompare.valuePrint(e.getValue()), r.sourceCommit, at, by));
                }
            }
            String recordFile = Pair.DIR + "/" + pair.name + ".json";
            r.changedFiles.add(recordFile);
            if (!dryRun) {
                pair.write(target);
            }
            r.written = !dryRun;
            return r;
        } finally {
            Sides.delete(sourceWritten);
            Sides.delete(work);
            Sides.delete(out);
        }
    }

    /** The manifest that lists a tree file. */
    static String manifestOf(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 3 && parts[0].equals("drivers")) {
            return "drivers/" + parts[1] + "/" + AsCodeWriter.DRIVER_MANIFEST;
        }
        if (parts.length >= 2 && parts[0].equals("library")) {
            return "library/" + AsCodeWriter.LIBRARY_MANIFEST;
        }
        return AsCodeWriter.DRIVERSET_MANIFEST;
    }

    private static List<Finding> newErrors(Report before, Report after) {
        Set<String> had = new HashSet<>();
        for (Finding f : before.of(Finding.Severity.ERROR)) {
            had.add(f.code + "|" + f.path + "|" + f.message);
        }
        List<Finding> out = new ArrayList<>();
        for (Finding f : after.of(Finding.Severity.ERROR)) {
            if (!had.contains(f.code + "|" + f.path + "|" + f.message)) {
                out.add(f);
            }
        }
        return out;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path rel = from.relativize(p);
                if (rel.toString().isEmpty() || rel.getName(0).toString().equals(".git")) {
                    continue;
                }
                Path t = to.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else if (Files.isRegularFile(p)) {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t);
                }
            }
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // ---- pairing and known differences ----

    /**
     * {@code promote.pair}: the first record, every object and value of both sides as they are now, so from here on
     * only new changes show. Refused when the pair has a record already, unless {@code force} (which starts it over).
     */
    public static Result pair(Path target, Path source, String pairName, boolean dryRun, boolean force, String by) throws IOException {
        Result r = new Result("promote.pair");
        r.dryRun = dryRun;
        Pair pair = Pair.read(target, pairName);
        if (!pair.record.isEmpty() && !force) {
            r.refusal = "'" + pair.name + "' is paired already (" + Pair.DIR + "/" + pair.name + ".json); --force starts its record over";
            return r;
        }
        Sides s = Sides.read(target, source, pair.map);
        r.notes.addAll(s.notes);
        r.sourceCommit = DeployLog.treeCommit(source);
        pair.record.objects.clear();
        pair.record.values.clear();
        String at = now();
        TreeSet<String> paths = new TreeSet<>(s.targetFiles.keySet());
        paths.addAll(s.sourceFiles.keySet());
        int differ = 0;
        for (String p : paths) {
            byte[] src = s.sourceFiles.get(p);
            byte[] tgt = s.targetFiles.get(p);
            pair.record.objects.put(p, new PromotionRecord.Entry(PromotionRecord.fingerprint(src), PromotionRecord.fingerprint(tgt), r.sourceCommit, at, by));
            if (src == null || tgt == null || !Arrays.equals(src, tgt)) {
                differ++;
            }
        }
        for (Map.Entry<String, String> e : s.targetValues.entrySet()) {
            if (s.sourceValues.containsKey(e.getKey())) {
                pair.record.values.put(e.getKey(), new PromotionRecord.Entry(ProjectCompare.valuePrint(s.sourceValues.get(e.getKey())),
                    ProjectCompare.valuePrint(e.getValue()), r.sourceCommit, at, by));
            }
        }
        r.notes.add(paths.size() + " file(s) and " + pair.record.values.size() + " value(s) recorded; " + differ
            + " file(s) differ and are taken as they are");
        r.changedFiles.add(Pair.DIR + "/" + pair.name + ".json");
        if (!dryRun) {
            pair.write(target);
        }
        r.written = !dryRun;
        return r;
    }

    /** {@code promote.accept}: a difference accepted as it is, until either side changes it. */
    public static Result accept(Path target, Path source, String pairName, String path, String decision, String note, String by, boolean dryRun)
            throws IOException {
        Result r = new Result("promote.accept");
        r.dryRun = dryRun;
        String d = decision == null ? "intended" : decision;
        if (!d.equals("intended") && !d.equals("later")) {
            r.refusal = "--decision is intended (different on purpose) or later (to decide)";
            return r;
        }
        Pair pair = Pair.read(target, pairName);
        ProjectCompare cmp = ProjectCompare.of(Sides.read(target, source, pair.map), pair);
        ProjectCompare.Item i = cmp.item(path);
        if (i == null) {
            r.refusal = "nothing differs at '" + path + "'";
            return r;
        }
        pair.known.removeIf(k -> k.path.equals(path));
        pair.known.add(new KnownDifference(pair.name, path, i.fingerprint, d, (i.value ? "value " : "") + path + ": " + i.state(), note,
            by == null ? "" : by, now()));
        r.promoted.add(path);
        r.changedFiles.add(Pair.DIR + "/" + pair.name + ".known.json");
        if (!dryRun) {
            pair.write(target);
        }
        r.written = !dryRun;
        return r;
    }

    /** {@code promote.forget}: a known difference no longer accepted; it shows again. */
    public static Result forget(Path target, String pairName, String path, boolean dryRun) throws IOException {
        Result r = new Result("promote.forget");
        r.dryRun = dryRun;
        Pair pair = Pair.read(target, pairName);
        if (!pair.known.removeIf(k -> k.path.equals(path))) {
            r.refusal = "no known difference '" + path + "' for '" + pair.name + "'";
            return r;
        }
        r.promoted.add(path);
        r.changedFiles.add(Pair.DIR + "/" + pair.name + ".known.json");
        if (!dryRun) {
            pair.write(target);
        }
        r.written = !dryRun;
        return r;
    }
}
