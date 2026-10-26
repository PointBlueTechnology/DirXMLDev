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
import java.util.TreeMap;
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
        return run(Projects.of(target, source, pairName), sel, dryRun, force, by);
    }

    public static Result run(Projects projects, Selection sel, boolean dryRun, boolean force, String by) throws IOException {
        return run(projects, sel, dryRun, force, by, Validator.standard());
    }

    static Result run(Projects projects, Selection sel, boolean dryRun, boolean force, String by, Validator validator) throws IOException {
        Path target = projects.target();
        Path source = projects.source();
        Result r = new Result(dryRun ? "promote.plan" : "promote.apply");
        r.dryRun = dryRun;
        Pair pair = projects.readPair();
        Sides s = projects.sides(pair);
        ProjectCompare cmp = ProjectCompare.of(s, pair);
        r.notes.addAll(s.notes);

        // what was chosen
        Set<String> chosen = new TreeSet<>();
        for (String o : sel.objects) {
            String p = o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
            // a driver's directory brings the driver's cases too
            String driver = p.matches("drivers/[^/]+") ? s.driverOf(p + "/") : null;
            List<String> hit = new ArrayList<>();
            for (ProjectCompare.Item i : cmp.items) {
                if (!i.value && (i.path.equals(p) || i.path.startsWith(p + "/")
                    || (driver != null && i.path.startsWith(Sides.CASES) && driver.equals(i.driver)))) {
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
                if (p.startsWith(Sides.CASES)) {
                    continue;   // cases live beside the tree: written below
                }
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
            restoreValues(after, s, values);

            AsCodeWriter.write(after, out);
            Map<String, byte[]> produced = Sides.read(out);
            for (String p : chosen) {
                if (p.startsWith(Sides.CASES)) {
                    continue;
                }
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
            // a case's files, into the target's cases: the source's (its driver= as the target names it), or none
            Map<String, byte[]> caseFiles = new TreeMap<>();
            for (String p : chosen) {
                if (!p.startsWith(Sides.CASES)) {
                    continue;
                }
                byte[] bytes = sel.contents.containsKey(p) ? sel.contents.get(p).getBytes(StandardCharsets.UTF_8) : s.sourceFiles.get(p);
                caseFiles.put(p, bytes);
                Path t = s.targetCases.resolve(p.substring(Sides.CASES.length()));
                if (bytes != null) {
                    if (!Files.isRegularFile(t) || !Arrays.equals(Files.readAllBytes(t), bytes)) {
                        r.changedFiles.add(p);
                        if (!dryRun) {
                            Files.createDirectories(t.getParent());
                            Files.write(t, bytes);
                        }
                    }
                } else if (Files.isRegularFile(t)) {
                    r.deletedFiles.add(p);
                    if (!dryRun) {
                        Files.delete(t);
                    }
                }
            }
            for (String p : chosen) {
                if (p.startsWith(Sides.CASES)) {
                    continue;
                }
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
                byte[] now = p.startsWith(Sides.CASES) ? caseFiles.get(p) : afterFiles.get(p);
                pair.record.objects.put(p, new PromotionRecord.Entry(PromotionRecord.fingerprint(s.sourceFiles.get(p)),
                    PromotionRecord.fingerprint(now), r.sourceCommit, at, by));
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

    /** The target's values back in a promoted model, but for the ones chosen, which take the source's. */
    private static void restoreValues(DriverSet after, Sides s, Set<String> chosen) {
        for (Map.Entry<String, String> e : s.targetValues.entrySet()) {
            Overrides.Target t = Overrides.resolve(after, e.getKey());
            if (!t.resolved()) {
                continue;
            }
            String want = chosen.contains(e.getKey()) ? s.sourceValues.get(e.getKey()) : e.getValue();
            if (!Objects.equals(t.value(), want)) {
                t.set(want == null && t.definition != null ? "" : want);
            }
        }
    }

    /** A comparison with, for each differing file, its bytes in the target now and once promoted (null: that side has none). */
    public static final class Preview {
        public final ProjectCompare compare;
        public final Map<String, byte[]> before = new TreeMap<>();
        public final Map<String, byte[]> after = new TreeMap<>();

        Preview(ProjectCompare compare) {
            this.compare = compare;
        }
    }

    /**
     * What a review shows: every differing file as the target has it and as a promotion of it writes it, the source's
     * logic with the target's values (no value chosen). A file whose promotion depends on its manifest is shown as the
     * whole of the source's files promoted together write it.
     */
    public static Preview preview(Path target, Path source, String pairName) throws IOException {
        return preview(Projects.of(target, source, pairName));
    }

    public static Preview preview(Projects projects) throws IOException {
        Path target = projects.target();
        Pair pair = projects.readPair();
        Sides s = projects.sides(pair);
        Preview pv = new Preview(ProjectCompare.of(s, pair));
        Path sourceWritten = Files.createTempDirectory("idm-promote-src");
        Path work = Files.createTempDirectory("idm-promote-work");
        try {
            AsCodeWriter.write(s.source, sourceWritten);
            copyTree(target, work);
            for (ProjectCompare.Item i : pv.compare.items) {
                if (i.value) {
                    continue;
                }
                if (i.path.startsWith(Sides.CASES)) {
                    pv.before.put(i.path, s.targetFiles.get(i.path));   // a case's files are its own: no values to set aside
                    pv.after.put(i.path, s.sourceFiles.get(i.path));
                    continue;
                }
                Path t = target.resolve(i.path);
                pv.before.put(i.path, Files.isRegularFile(t) ? Files.readAllBytes(t) : null);
                Path from = sourceWritten.resolve(i.path);
                Path w = work.resolve(i.path);
                if (Files.isRegularFile(from)) {
                    Files.createDirectories(w.getParent());
                    Files.copy(from, w, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(w);
                }
            }
            DriverSet after = AsCodeReader.read(work);
            restoreValues(after, s, Set.of());
            Map<String, byte[]> produced = Sides.written(after);
            for (ProjectCompare.Item i : pv.compare.items) {
                if (!i.value && !i.path.startsWith(Sides.CASES)) {
                    pv.after.put(i.path, produced.get(i.path));
                }
            }
            return pv;
        } finally {
            Sides.delete(sourceWritten);
            Sides.delete(work);
        }
    }

    /** The manifest that lists a tree file. */
    static String manifestOf(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 3 && parts[0].equals("drivers") && !(parts.length == 3 && parts[2].equals(AsCodeWriter.DRIVER_MANIFEST))) {
            return "drivers/" + parts[1] + "/" + AsCodeWriter.DRIVER_MANIFEST;
        }
        if (parts.length >= 2 && parts[0].equals("library") && !path.equals("library/" + AsCodeWriter.LIBRARY_MANIFEST)) {
            return "library/" + AsCodeWriter.LIBRARY_MANIFEST;
        }
        return AsCodeWriter.DRIVERSET_MANIFEST;   // the drivers, the Library and the set's own objects
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
        return pair(Projects.of(target, source, pairName), new Pairing(), dryRun, force, by);
    }

    /** What a person settled when pairing: the map to write, the differences left pending, those accepted as known. */
    public static final class Pairing {
        /** Written as {@code promotions/<source>.properties} when not null (else the pair's file, as it is, is used). */
        public Map<String, String> map;
        /** The map as a person wrote it, comments and all: written as it is, and read for {@link #map}. */
        public String mapText;
        /** Differences (files or values) left to promote: recorded as the target has them, the source's side unknown. */
        public final Set<String> pending = new TreeSet<>();
        /** Differences accepted as they are: path to {@code {decision, note}}. */
        public final Map<String, String[]> known = new LinkedHashMap<>();
    }

    /**
     * {@code promote.pair}: the first record. Every file and value of both sides is recorded as it is now, so only new
     * changes show from here on, but for the differences {@code choices} leaves pending, which show as pending, and the
     * ones it accepts as known. With a map in {@code choices}, the map is written too and the comparison uses it.
     */
    public static Result pair(Projects projects, Pairing choices, boolean dryRun, boolean force, String by) throws IOException {
        Path target = projects.target();
        Result r = new Result("promote.pair");
        r.dryRun = dryRun;
        if (choices.mapText != null) {
            choices.map = Overrides.parse(choices.mapText);
        }
        Projects p = choices.map == null ? projects : projects.withMap(choices.map);
        Pair pair = p.readPair();
        if (!pair.record.isEmpty() && !force) {
            r.refusal = "'" + pair.name + "' is paired already (" + Pair.DIR + "/" + pair.name + ".json); --force starts its record over";
            return r;
        }
        Sides s = p.sides(pair);
        ProjectCompare cmp = ProjectCompare.of(s, pair);
        for (String path : choices.pending) {
            if (cmp.item(path) == null) {
                r.refusal = "nothing differs at '" + path + "' to leave pending";
                return r;
            }
        }
        r.notes.addAll(s.notes);
        r.sourceCommit = DeployLog.treeCommit(projects.source());
        pair.record.objects.clear();
        pair.record.values.clear();
        String at = now();
        TreeSet<String> paths = new TreeSet<>(s.targetFiles.keySet());
        paths.addAll(s.sourceFiles.keySet());
        int differ = 0;
        for (String path : paths) {
            byte[] src = s.sourceFiles.get(path);
            byte[] tgt = s.targetFiles.get(path);
            // left pending: the source's side as before pairing (no fingerprint matches it), so its change shows
            String sourcePrint = choices.pending.contains(path) ? BEFORE_PAIRING : PromotionRecord.fingerprint(src);
            pair.record.objects.put(path, new PromotionRecord.Entry(sourcePrint, PromotionRecord.fingerprint(tgt), r.sourceCommit, at, by));
            if (src == null || tgt == null || !Arrays.equals(src, tgt)) {
                differ++;
            }
        }
        for (Map.Entry<String, String> e : s.targetValues.entrySet()) {
            if (s.sourceValues.containsKey(e.getKey())) {
                String sourcePrint = choices.pending.contains(e.getKey()) ? BEFORE_PAIRING : ProjectCompare.valuePrint(s.sourceValues.get(e.getKey()));
                pair.record.values.put(e.getKey(), new PromotionRecord.Entry(sourcePrint, ProjectCompare.valuePrint(e.getValue()), r.sourceCommit, at, by));
            }
        }
        String refusal = acceptInto(pair, cmp, choices.known, by, r);
        if (refusal != null) {
            r.refusal = refusal;
            return r;
        }
        r.notes.add(paths.size() + " file(s) and " + pair.record.values.size() + " value(s) recorded; " + differ + " file(s) differ: "
            + choices.pending.size() + " left pending, " + choices.known.size() + " accepted as known, the rest taken as they are");
        if (choices.map != null) {
            r.changedFiles.add(Pair.DIR + "/" + pair.name + Overrides.EXT);
        }
        r.changedFiles.add(Pair.DIR + "/" + pair.name + ".json");
        if (!choices.known.isEmpty()) {
            r.changedFiles.add(Pair.DIR + "/" + pair.name + ".known.json");
        }
        if (!dryRun) {
            if (choices.map != null) {
                Files.createDirectories(target.resolve(Pair.DIR));
                Files.writeString(target.resolve(Pair.DIR).resolve(pair.name + Overrides.EXT),
                    choices.mapText != null ? choices.mapText : Pair.formatMap(choices.map), StandardCharsets.UTF_8);
            }
            pair.write(target);
        }
        r.written = !dryRun;
        return r;
    }

    /** A source fingerprint no content has: a difference left pending at pairing. */
    static final String BEFORE_PAIRING = "before-pairing";

    /** Each decision into the pair's known differences; the refusal, or null. */
    private static String acceptInto(Pair pair, ProjectCompare cmp, Map<String, String[]> decisions, String by, Result r) {
        for (Map.Entry<String, String[]> e : decisions.entrySet()) {
            String path = e.getKey();
            String d = e.getValue().length > 0 && e.getValue()[0] != null ? e.getValue()[0] : "intended";
            String note = e.getValue().length > 1 ? e.getValue()[1] : null;
            if (!d.equals("intended") && !d.equals("later")) {
                return "a decision is intended (different on purpose) or later (to decide), not '" + d + "'";
            }
            ProjectCompare.Item i = cmp.item(path);
            if (i == null) {
                return "nothing differs at '" + path + "'";
            }
            pair.known.removeIf(k -> k.path.equals(path));
            pair.known.add(new KnownDifference(pair.name, path, i.fingerprint, d, (i.value ? "value " : "") + path + ": " + i.state(), note,
                by == null ? "" : by, now()));
            r.promoted.add(path);
        }
        return null;
    }

    /** {@code promote.accept}: a difference accepted as it is, until either side changes it. */
    public static Result accept(Path target, Path source, String pairName, String path, String decision, String note, String by, boolean dryRun)
            throws IOException {
        Map<String, String[]> one = new LinkedHashMap<>();
        one.put(path, new String[] {decision, note});
        return accept(Projects.of(target, source, pairName), one, by, dryRun);
    }

    /** {@code promote.accept} for several differences at once: path to {@code {decision, note}}. */
    public static Result accept(Projects projects, Map<String, String[]> decisions, String by, boolean dryRun) throws IOException {
        Result r = new Result("promote.accept");
        r.dryRun = dryRun;
        Pair pair = projects.readPair();
        ProjectCompare cmp = ProjectCompare.of(projects.sides(pair), pair);
        String refusal = acceptInto(pair, cmp, decisions, by, r);
        if (refusal != null) {
            r.refusal = refusal;
            return r;
        }
        r.changedFiles.add(Pair.DIR + "/" + pair.name + ".known.json");
        if (!dryRun) {
            pair.write(projects.target());
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
