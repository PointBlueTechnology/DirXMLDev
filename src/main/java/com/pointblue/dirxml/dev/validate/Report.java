package com.pointblue.dirxml.dev.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The findings from one validation run, with text and JSON renderings. A report is
 * {@link #ok()} when it has no {@code ERROR}s — warnings and infos never fail a run.
 */
public final class Report {

    private final List<Finding> findings = new ArrayList<>();

    public void add(Finding f) {
        findings.add(f);
    }

    public List<Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    public List<Finding> of(Finding.Severity s) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.severity == s) {
                out.add(f);
            }
        }
        return out;
    }

    public int count(Finding.Severity s) {
        return of(s).size();
    }

    public boolean ok() {
        return count(Finding.Severity.ERROR) == 0;
    }

    /** Findings with a given code (for tests and for callers that gate on one class of problem). */
    public List<Finding> withCode(String code) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.code.equals(code)) {
                out.add(f);
            }
        }
        return out;
    }

    /** Human-readable rendering: one line per finding (errors first), then a summary. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Finding.Severity s : Finding.Severity.values()) {
            for (Finding f : of(s)) {
                sb.append(f).append('\n');
                if (f.detail != null && !f.detail.isBlank()) {
                    for (String line : f.detail.strip().split("\n")) {
                        sb.append("        ").append(line).append('\n');
                    }
                }
            }
        }
        sb.append(summary()).append('\n');
        return sb.toString();
    }

    public String summary() {
        return (ok() ? "OK" : "FAIL") + ": " + count(Finding.Severity.ERROR) + " error(s), "
            + count(Finding.Severity.WARNING) + " warning(s), " + count(Finding.Severity.INFO) + " info";
    }

    /** Machine-readable rendering: {@code {"ok":…, "counts":{…}, "findings":[{severity,code,path,message,detail}…]}}. */
    public String json() {
        Map<Finding.Severity, Integer> counts = new EnumMap<>(Finding.Severity.class);
        for (Finding.Severity s : Finding.Severity.values()) {
            counts.put(s, count(s));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(ok());
        sb.append(",\"counts\":{\"error\":").append(counts.get(Finding.Severity.ERROR))
            .append(",\"warning\":").append(counts.get(Finding.Severity.WARNING))
            .append(",\"info\":").append(counts.get(Finding.Severity.INFO)).append('}');
        sb.append(",\"findings\":[");
        boolean first = true;
        for (Finding.Severity s : Finding.Severity.values()) {
            for (Finding f : of(s)) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"severity\":").append(quote(f.severity.name().toLowerCase()))
                    .append(",\"code\":").append(quote(f.code))
                    .append(",\"path\":").append(quote(f.path))
                    .append(",\"message\":").append(quote(f.message));
                if (f.detail != null) {
                    sb.append(",\"detail\":").append(quote(f.detail));
                }
                sb.append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
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
}
