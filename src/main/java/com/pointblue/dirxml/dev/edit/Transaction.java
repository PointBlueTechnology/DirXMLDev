package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * One edit to an IDM-as-code tree, as a transaction:
 * <pre>
 *   load tree → validate (the "before") → apply operation → validate (the "after")
 *   → refuse if the operation introduced an error → write the tree (all files or none)
 * </pre>
 * Errors that were already there stay there — an operation is judged only on the
 * errors it adds, so a tree with a pre-existing problem can still be edited
 * elsewhere. {@code force} writes despite new errors (the result says so);
 * {@code dryRun} does everything but write.
 *
 * <p>Writing is a sync: the model is serialized to a scratch directory and only
 * files whose bytes differ are copied into the tree; files under the managed
 * paths ({@code driverset.xml}, {@code config-values.xml}, {@code library/},
 * {@code drivers/}) that the model no longer produces are deleted. Everything
 * else in the tree ({@code .git}, {@code .package-baseline/}, a client's own
 * files) is untouched. The result lists exactly what changed, which is what a
 * commit message wants.
 */
public final class Transaction {

    private static final List<String> MANAGED = Arrays.asList(
        AsCodeWriter.DRIVERSET_MANIFEST, AsCodeWriter.CONFIG_VALUES_FILE, "library", "drivers");

    private final Path tree;
    private final DriverSet ds;
    private final Validator validator;
    private final Set<String> touched = new LinkedHashSet<>();
    private final Map<String, String> renamed = new LinkedHashMap<>();
    private final Map<Path, String> pendingBaselines = new LinkedHashMap<>();
    private final Set<String> customizedNow = new LinkedHashSet<>();

    private Transaction(Path tree, DriverSet ds, Validator validator) {
        this.tree = tree;
        this.ds = ds;
        this.validator = validator;
    }

    public static Transaction open(Path tree) throws IOException {
        return open(tree, Validator.standard());
    }

    public static Transaction open(Path tree, Validator validator) throws IOException {
        return new Transaction(tree, AsCodeReader.read(tree), validator);
    }

    public DriverSet model() {
        return ds;
    }

    public Path tree() {
        return tree;
    }

    // ---- what operations tell the transaction ----

    /** An artifact this operation changed (content, name, or meta). Packaged ones get their baseline kept. */
    public void touch(Artifact a) throws IOException {
        touched.add(a.path());
        if (Packages.customize(tree, a, this)) {
            customizedNow.add(a.path());
        }
    }

    /** An artifact this operation renamed, so its pre-existing findings follow it. */
    public void renamed(String oldPath, String newPath) {
        renamed.put(oldPath, newPath);
        touched.remove(oldPath);
        touched.add(newPath);
    }

    void pendingBaseline(Path file, String content) {
        pendingBaselines.put(file, content);
    }

    // ---- run ----

    /** Apply the operation and write (unless dryRun); never throws for a refusal or a new error. */
    public Result run(Operation op, boolean dryRun, boolean force) throws IOException {
        Result r = new Result(op.name());
        r.dryRun = dryRun;
        Report before = validator.validate(ds);
        try {
            op.apply(ds, this);
        } catch (Operation.Refusal e) {
            r.refusal = e.getMessage();
            return r;
        }
        r.touched.addAll(touched);
        r.renamed.putAll(renamed);
        r.customized.addAll(customizedNow);
        Report after = validator.validate(ds);
        r.report = after;
        r.newErrors.addAll(newErrors(before, after));
        if (!r.newErrors.isEmpty() && !force) {
            r.refusal = "the operation would introduce " + r.newErrors.size()
                + " validation error(s); nothing written (use --force to write anyway)";
            return r;
        }
        Path scratch = Files.createTempDirectory("idm-tx");
        try {
            AsCodeWriter.write(ds, scratch);
            sync(scratch, dryRun, r);
            for (Map.Entry<Path, String> b : pendingBaselines.entrySet()) {
                r.changedFiles.add(tree.relativize(b.getKey()).toString().replace('\\', '/'));
                if (!dryRun) {
                    Files.createDirectories(b.getKey().getParent());
                    Files.writeString(b.getKey(), b.getValue(), StandardCharsets.UTF_8);
                }
            }
        } finally {
            deleteRecursively(scratch);
        }
        r.written = !dryRun;
        return r;
    }

    /** Errors in {@code after} that aren't in {@code before} (renamed artifacts' errors follow the rename). */
    private List<Finding> newErrors(Report before, Report after) {
        Set<String> known = new HashSet<>();
        for (Finding f : before.of(Finding.Severity.ERROR)) {
            String path = f.path;
            for (Map.Entry<String, String> rn : renamed.entrySet()) {
                if (path.equals(rn.getKey())) {
                    path = rn.getValue();
                } else if (path.startsWith(rn.getKey() + "/")) {
                    path = rn.getValue() + path.substring(rn.getKey().length());
                }
            }
            known.add(key(f.code, path, f.message.replace(f.path, path)));
        }
        List<Finding> out = new java.util.ArrayList<>();
        for (Finding f : after.of(Finding.Severity.ERROR)) {
            if (!known.contains(key(f.code, f.path, f.message))) {
                out.add(f);
            }
        }
        return out;
    }

    private static String key(String code, String path, String message) {
        return code + "|" + path + "|" + message;
    }

    /** Copy changed files from scratch into the tree; delete managed files the model no longer produces. */
    private void sync(Path scratch, boolean dryRun, Result r) throws IOException {
        Set<String> produced = new TreeSet<>();
        try (Stream<Path> s = Files.walk(scratch)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = scratch.relativize(p).toString().replace('\\', '/');
                produced.add(rel);
                Path target = tree.resolve(rel);
                byte[] bytes = Files.readAllBytes(p);
                if (!Files.exists(target) || !Arrays.equals(bytes, Files.readAllBytes(target))) {
                    r.changedFiles.add(rel);
                    if (!dryRun) {
                        Files.createDirectories(target.getParent());
                        Files.write(target, bytes);
                    }
                }
            }
        }
        for (String managed : MANAGED) {
            Path root = tree.resolve(managed);
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                continue;   // manifests are always produced
            }
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    String rel = tree.relativize(p).toString().replace('\\', '/');
                    if (!produced.contains(rel)) {
                        r.deletedFiles.add(rel);
                        if (!dryRun) {
                            Files.delete(p);
                        }
                    }
                }
            }
            if (!dryRun) {
                pruneEmptyDirs(root);
            }
        }
    }

    private static void pruneEmptyDirs(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> dirs = s.filter(Files::isDirectory).sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path d : dirs) {
                if (d.equals(root)) {
                    continue;
                }
                try (Stream<Path> kids = Files.list(d)) {
                    if (kids.findAny().isEmpty()) {
                        Files.delete(d);
                    }
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
