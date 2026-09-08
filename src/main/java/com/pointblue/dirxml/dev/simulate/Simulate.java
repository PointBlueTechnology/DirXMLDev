package com.pointblue.dirxml.dev.simulate;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.sim.BatchRunner;
import com.pointblue.dirxml.sim.Case;
import com.pointblue.dirxml.sim.ChannelSimulator;
import com.pointblue.dirxml.sim.Comparer;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The simulate gate: run a regression corpus against an IDM-as-code tree, and
 * optionally diff every case against the same corpus run on another tree (the
 * one before the edit). {@code validate} proves the engine will load the tree;
 * this proves it still does what the corpus recorded — or shows exactly what
 * changed.
 *
 * <p>The simulator reads a driver's configuration from a single-driver export
 * ({@code export=} in a case), not from a tree, so each case's driver is written
 * out of the tree as such an export (by the {@link Exporter} — the
 * {@code ExportWriter}) into a scratch directory, and the case is re-rendered
 * there with its config source swapped to that file: the case's other keys are
 * kept, path-valued ones absolutized against the original case directory (the
 * simulator's own {@code compare} does the same). Cases are never modified.
 *
 * <p>A case names its driver with {@code driver=} (project / LDIF / live
 * sources) or implicitly through its {@code export=} file (a single-driver
 * export carries the name); the gate reads it either way.
 */
public final class Simulate {

    /** Writes one driver of a driver set as a single-driver export the simulator loads. */
    public interface Exporter {
        void writeDriver(DriverSet ds, String driverName, Path file) throws IOException;
    }

    /** Keys that name a case's config source; replaced by {@code export=}. */
    static final Set<String> SOURCE_KEYS = Set.of("export", "project", "ldifConfig", "ldapConfig");
    /** Keys whose values are paths relative to the case directory. */
    static final Set<String> PATH_KEYS = Set.of("export", "project", "ldifConfig", "schema", "ldif");

    public static final class CaseOutcome {
        public final String name;
        public final BatchRunner.Outcome outcome;
        public final String detail;
        public final Comparer.Comparison against;   // null without --against

        CaseOutcome(String name, BatchRunner.Outcome outcome, String detail, Comparer.Comparison against) {
            this.name = name;
            this.outcome = outcome;
            this.detail = detail;
            this.against = against;
        }
    }

    public static final class Outcome {
        public final List<CaseOutcome> cases = new ArrayList<>();

        public int count(BatchRunner.Outcome o) {
            int n = 0;
            for (CaseOutcome c : cases) {
                if (c.outcome == o) {
                    n++;
                }
            }
            return n;
        }

        public int changed() {
            int n = 0;
            for (CaseOutcome c : cases) {
                if (c.against != null && !c.against.finalSame) {
                    n++;
                }
            }
            return n;
        }

        /** Green when nothing failed or errored, and (with --against) nothing changed. */
        public boolean ok() {
            return count(BatchRunner.Outcome.FAIL) == 0 && count(BatchRunner.Outcome.ERROR) == 0 && changed() == 0;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (CaseOutcome c : cases) {
                sb.append(String.format("%-6s %s%n", c.outcome, c.name));
                if (c.detail != null && !c.detail.isBlank() && c.outcome != BatchRunner.Outcome.PASS) {
                    for (String line : c.detail.strip().split("\n")) {
                        sb.append("       ").append(line).append('\n');
                    }
                }
                if (c.against != null) {
                    if (c.against.finalSame) {
                        sb.append("       unchanged vs --against\n");
                    } else {
                        sb.append("       CHANGED vs --against — first diverges at stage '")
                            .append(c.against.firstDivergesAt).append("'\n");
                        for (Comparer.StageDiff s : c.against.stages) {
                            if (!s.same) {
                                sb.append("         ").append(s.name).append(": ").append(s.detail.strip().replace("\n", "\n           ")).append('\n');
                            }
                        }
                        sb.append("         final: ").append(c.against.finalDetail.strip().replace("\n", "\n           ")).append('\n');
                    }
                }
            }
            sb.append(summary()).append('\n');
            return sb.toString();
        }

        public String summary() {
            return (ok() ? "OK" : "FAIL") + ": " + cases.size() + " case(s) — "
                + count(BatchRunner.Outcome.PASS) + " pass, " + count(BatchRunner.Outcome.FAIL) + " fail, "
                + count(BatchRunner.Outcome.ERROR) + " error, " + count(BatchRunner.Outcome.SKIP) + " skip"
                + (hasAgainst() ? ", " + changed() + " changed vs --against" : "");
        }

        private boolean hasAgainst() {
            for (CaseOutcome c : cases) {
                if (c.against != null) {
                    return true;
                }
            }
            return false;
        }

        public String json() {
            StringBuilder sb = new StringBuilder("{\"ok\":").append(ok());
            sb.append(",\"pass\":").append(count(BatchRunner.Outcome.PASS))
                .append(",\"fail\":").append(count(BatchRunner.Outcome.FAIL))
                .append(",\"error\":").append(count(BatchRunner.Outcome.ERROR))
                .append(",\"skip\":").append(count(BatchRunner.Outcome.SKIP))
                .append(",\"changed\":").append(changed());
            sb.append(",\"cases\":[");
            boolean first = true;
            for (CaseOutcome c : cases) {
                sb.append(first ? "" : ",").append("{\"name\":").append(q(c.name))
                    .append(",\"outcome\":").append(q(c.outcome.name().toLowerCase()))
                    .append(",\"detail\":").append(q(c.detail == null ? "" : c.detail));
                if (c.against != null) {
                    sb.append(",\"changed\":").append(!c.against.finalSame);
                    sb.append(",\"firstDivergesAt\":").append(c.against.firstDivergesAt == null ? "null" : q(c.against.firstDivergesAt));
                    sb.append(",\"stages\":[");
                    boolean f2 = true;
                    for (Comparer.StageDiff s : c.against.stages) {
                        sb.append(f2 ? "" : ",").append("{\"name\":").append(q(s.name)).append(",\"same\":").append(s.same)
                            .append(",\"detail\":").append(q(s.detail)).append('}');
                        f2 = false;
                    }
                    sb.append("],\"finalDetail\":").append(q(c.against.finalDetail));
                }
                sb.append('}');
                first = false;
            }
            return sb.append("]}").toString();
        }
    }

    private final Exporter exporter;

    public Simulate(Exporter exporter) {
        this.exporter = exporter;
    }

    /** Run the corpus under {@code cases} against {@code tree}; with {@code against} (another tree), diff each case. */
    public Outcome run(Path tree, Path cases, Path against) throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        DriverSet other = against == null ? null : AsCodeReader.read(against);
        Path scratch = Files.createTempDirectory("idm-simulate");
        Outcome out = new Outcome();
        try {
            Map<String, Path> exports = new LinkedHashMap<>();
            Map<String, Path> otherExports = new LinkedHashMap<>();
            for (Path caseDir : BatchRunner.discover(cases)) {
                String name = cases.relativize(caseDir).toString().replace('\\', '/');
                Properties p = load(caseDir);
                String driver = driverOf(p, caseDir);
                if (driver == null) {
                    out.cases.add(new CaseOutcome(name, BatchRunner.Outcome.ERROR,
                        "cannot tell which driver the case is for: no driver= and no export= naming one", null));
                    continue;
                }
                if (ds.driver(driver) == null) {
                    out.cases.add(new CaseOutcome(name, BatchRunner.Outcome.ERROR,
                        "the tree has no driver '" + driver + "'", null));
                    continue;
                }
                Path export = export(ds, driver, scratch.resolve("a"), exports);
                Path rendered = render(caseDir, p, scratch.resolve("cases-a").resolve(name), export);
                BatchRunner.CaseResult r = BatchRunner.run(scratch.resolve("cases-a"), rendered);
                Comparer.Comparison cmp = null;
                if (other != null) {
                    if (other.driver(driver) == null) {
                        out.cases.add(new CaseOutcome(name, r.outcome, r.detail + "\n(--against tree has no driver '" + driver + "')", null));
                        continue;
                    }
                    Path exportB = export(other, driver, scratch.resolve("b"), otherExports);
                    Path renderedB = render(caseDir, p, scratch.resolve("cases-b").resolve(name), exportB);
                    try {
                        ChannelSimulator.Result ra = Case.load(rendered).run();
                        ChannelSimulator.Result rb = Case.load(renderedB).run();
                        cmp = Comparer.diff(rb, ra);   // B = before, A = the edited tree
                    } catch (RuntimeException e) {
                        out.cases.add(new CaseOutcome(name, BatchRunner.Outcome.ERROR,
                            "comparison failed: " + rootCause(e), null));
                        continue;
                    }
                }
                out.cases.add(new CaseOutcome(name, r.outcome, r.detail, cmp));
            }
        } finally {
            deleteRecursively(scratch);
        }
        return out;
    }

    private Path export(DriverSet ds, String driver, Path dir, Map<String, Path> cache) throws IOException {
        Path f = cache.get(driver);
        if (f == null) {
            Files.createDirectories(dir);
            f = dir.resolve(safe(driver) + ".xml");
            exporter.writeDriver(ds, driver, f);
            cache.put(driver, f);
        }
        return f;
    }

    static Properties load(Path caseDir) throws IOException {
        Properties p = new Properties();
        Path f = caseDir.resolve("case.properties");
        if (Files.exists(f)) {
            try (StringReader r = new StringReader(Files.readString(f, StandardCharsets.UTF_8))) {
                p.load(r);
            }
        }
        return p;
    }

    /** {@code driver=} if set; else the name of the single-driver export the case points at. */
    static String driverOf(Properties p, Path caseDir) {
        String d = p.getProperty("driver");
        if (d != null && !d.isBlank()) {
            return d.trim();
        }
        String export = p.getProperty("export");
        if (export == null || export.isBlank()) {
            return null;
        }
        Path f = caseDir.resolve(export.trim());
        if (!Files.exists(f)) {
            return null;
        }
        try {
            DriverSet ds = com.pointblue.dirxml.dev.source.ExportReader.read(f);
            return ds.drivers.size() == 1 ? ds.drivers.get(0).name : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A copy of the case with its config source swapped to {@code export}: every
     * file copied, {@code case.properties} re-rendered with path-valued keys made
     * absolute against the original directory (so {@code ldif=}, {@code schema=}
     * and friends still resolve) and the source keys replaced.
     */
    static Path render(Path caseDir, Properties p, Path target, Path export) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> s = Files.list(caseDir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(f) && !f.getFileName().toString().equals("case.properties")) {
                    Files.copy(f, target.resolve(f.getFileName().toString()));
                } else if (Files.isDirectory(f) && !Files.exists(f.resolve("input.xds"))) {
                    copyTree(f, target.resolve(f.getFileName().toString()));
                }
            }
        }
        StringBuilder sb = new StringBuilder("# rendered by idm simulate — config source swapped to the tree\n");
        List<String> keys = new ArrayList<>(p.stringPropertyNames());
        keys.sort(null);
        for (String k : keys) {
            if (SOURCE_KEYS.contains(k)) {
                continue;
            }
            String v = p.getProperty(k);
            if (PATH_KEYS.contains(k) && v != null && !v.isBlank()) {
                Path resolved = caseDir.resolve(v.trim());
                if (Files.exists(resolved)) {
                    v = resolved.toAbsolutePath().normalize().toString();
                }
            }
            sb.append(k).append('=').append(escape(v)).append('\n');
        }
        sb.append("export=").append(escape(export.toAbsolutePath().toString())).append('\n');
        Files.writeString(target.resolve("case.properties"), sb.toString(), StandardCharsets.UTF_8);
        return target;
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\");
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(f, t);
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

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    private static String q(String s) {
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

    /** True if {@code d} has both channels' worth of policies — a hint that the export is complete. */
    static boolean hasPolicies(Driver d) {
        return !d.policies.isEmpty() || !d.subscriber.policies.isEmpty() || !d.publisher.policies.isEmpty();
    }
}
