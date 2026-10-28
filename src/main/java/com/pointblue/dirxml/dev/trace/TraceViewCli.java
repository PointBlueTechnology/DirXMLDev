package com.pointblue.dirxml.dev.trace;

import com.pointblue.dirxml.dev.Args;
import com.pointblue.dirxml.dev.json.Json;

import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code trace.view}: a driver trace as transactions (docs/trace-view.md). This class reads a file;
 * the operate CLI fetches a driver's trace from an environment and hands the lines to {@link #print}.
 */
public final class TraceViewCli {

    public static final String USAGE = "usage: trace.view --file F [--driver NAME] | --env E --driver D [--lines N | --since MIN | --ldap [--seconds N]]\n"
        + "       [--channel subscriber|publisher] [--status success|warning|retry|error|fatal] [--vetoed] [--text RE]\n"
        + "       [--trace-driver NAME] [--last N] [--transaction N] [--json]";

    private TraceViewCli() {
    }

    /** False (having said how to get it) when the trace parser jar is not on the class path. */
    public static boolean parserPresent() {
        try {
            Class.forName("com.pointbluetech.dirxml.trace.model.TraceParser");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            System.err.println("trace.view needs the trace parser, dirxml-trace-core, in ~/.m2 at the version pom.xml pins "
                + "(tracecore.version): take it from a DirXMLTraceViewer release and install it as docs/install.md says");
            return false;
        }
    }

    /**
     * The filters a {@code trace.view} command line can be refused for before any trace is read
     * (the numbers are the operate CLI's): {@code --text}, and {@code --status} and {@code --channel}
     * when the trace parser is present (without it, {@link #print} says how to get it).
     */
    public static void check(Args a) throws Args.UsageError {
        String re = a.first("text");
        if (re != null) {
            try {
                Pattern.compile(re);
            } catch (PatternSyntaxException e) {
                throw new Args.UsageError("--text is not a regular expression: " + e.getDescription());
            }
        }
        if (!parserOnPath()) {
            return;   // TraceView needs the parser; print says how to get it
        }
        String status = a.first("status");
        if (status != null && !TraceView.LEVELS.contains(status.toLowerCase(java.util.Locale.ROOT))) {
            throw new Args.UsageError("--status must be one of " + String.join(", ", TraceView.LEVELS));
        }
        String ch = a.first("channel");
        if (ch != null && TraceView.channel(ch) == null) {
            throw new Args.UsageError("--channel must be subscriber, publisher, service or other");
        }
    }

    private static boolean parserOnPath() {
        try {
            Class.forName("com.pointbluetech.dirxml.trace.model.TraceParser");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** {@code trace.view --file F …}. */
    public static int file(Map<String, List<String>> opts, boolean json) throws IOException {
        if (!parserPresent()) {
            return 1;
        }
        String file = first(opts, "file");
        if (file == null || file.isBlank()) {
            System.err.println(USAGE);
            return 2;
        }
        Path p = Paths.get(file);
        if (!Files.isRegularFile(p)) {
            System.err.println("no file " + file);
            return 1;
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        List<String> lines = new ArrayList<>();
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(Files.newInputStream(p), decoder))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
            }
        }
        String only = first(opts, "trace-driver") != null ? first(opts, "trace-driver") : first(opts, "driver");
        return print("file " + p.toAbsolutePath(), lines, opts, json, only);
    }

    /**
     * Prints the view of {@code lines}. {@code driverFilter} keeps one driver's transactions (a file
     * can hold several; a driver's own trace file needs none, so the operate CLI passes {@code --trace-driver}).
     */
    public static int print(String source, List<String> lines, Map<String, List<String>> opts, boolean json, String driverFilter) {
        if (!parserPresent()) {
            return 1;
        }
        TraceView.Filter f = new TraceView.Filter();
        f.driver = driverFilter;
        String ch = first(opts, "channel");
        if (ch != null) {
            f.channel = TraceView.channel(ch);
            if (f.channel == null) {
                System.err.println("--channel must be subscriber, publisher, service or other");
                return 2;
            }
        }
        String status = first(opts, "status");
        if (status != null && !TraceView.LEVELS.contains(status.toLowerCase(java.util.Locale.ROOT))) {
            System.err.println("--status must be one of " + String.join(", ", TraceView.LEVELS));
            return 2;
        }
        f.status = status;
        f.vetoed = opts.containsKey("vetoed");
        String re = first(opts, "text");
        if (re != null) {
            try {
                f.text = Pattern.compile(re);
            } catch (PatternSyntaxException e) {
                System.err.println("--text is not a regular expression: " + e.getDescription());
                return 2;
            }
        }
        int last = opts.containsKey("last") ? Integer.parseInt(first(opts, "last")) : 20;
        Integer only = opts.containsKey("transaction") ? Integer.valueOf(first(opts, "transaction")) : null;
        TraceView.Result r = TraceView.of(TraceView.records(lines));
        if (json) {
            System.out.println(Json.pretty(TraceView.json(source, r, f, last, only)));
            return 0;
        }
        if (only != null) {
            if (only < 1 || only > r.transactions.size()) {
                System.err.println("no transaction " + only + " (" + r.transactions.size() + " in view)");
                return 1;
            }
            System.out.print(TraceView.text(r.transactions.get(only - 1)));
            return 0;
        }
        System.out.print(TraceView.text(source, r, f, last));
        return 0;
    }

    static String first(Map<String, List<String>> opts, String key) {
        List<String> v = opts.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
