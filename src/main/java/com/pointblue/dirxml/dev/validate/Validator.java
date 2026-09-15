package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs every {@link Check} over a driver set (or an IDM-as-code tree) and collects
 * the findings into a {@link Report}. The offline gate before anything is deployed:
 * an as-code tree that validates with no errors is one the engine will load.
 */
public final class Validator {

    private final List<Check> checks;

    public Validator(List<Check> checks) {
        this.checks = new ArrayList<>(checks);
    }

    /** The standard check set, in the order the findings are most useful. */
    public static Validator standard() {
        return new Validator(Arrays.asList(
            new LinkCheck(),
            new CompileCheck(),
            new GcvCheck(),
            new MappingTableCheck(),
            new EcmaScriptCheck(),
            new FilterCheck(),
            new FormCheck(),
            new FlowCheck(),
            new EntitlementCheck()));
    }

    public Report validate(DriverSet ds) {
        Report r = new Report();
        for (Check c : checks) {
            try {
                c.run(ds, r);
            } catch (RuntimeException e) {
                r.add(Finding.error("check-failed", "driverset",
                    "check '" + c.name() + "' threw " + e, stackTop(e)));
            }
        }
        return r;
    }

    /**
     * Validate an IDM-as-code tree. Every {@code .xml} file is parsed first so a
     * malformed file is reported as a {@code xml-not-well-formed} error (with the
     * parser's position) instead of aborting the load; if any file fails, the
     * model-level checks are skipped — they'd only report the consequences.
     */
    public Report validate(Path asCodeDir) throws IOException {
        Report r = new Report();
        try (Stream<Path> s = Files.walk(asCodeDir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()::iterator) {
                try {
                    CanonicalXml.parse(Files.readString(p, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    r.add(Finding.error("xml-not-well-formed", asCodeDir.relativize(p).toString().replace('\\', '/'),
                        "not well-formed XML: " + e.getMessage()));
                }
            }
        }
        if (!r.ok()) {
            return r;
        }
        DriverSet ds;
        try {
            ds = AsCodeReader.read(asCodeDir);
        } catch (Exception e) {
            r.add(Finding.error("ascode-unreadable", "driverset", "cannot load as-code tree: " + e.getMessage()));
            return r;
        }
        Report model = validate(ds);
        for (Finding f : model.findings()) {
            r.add(f);
        }
        return r;
    }

    private static String stackTop(Throwable t) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(5, st.length); i++) {
            sb.append("at ").append(st[i]).append('\n');
        }
        return sb.toString();
    }
}
