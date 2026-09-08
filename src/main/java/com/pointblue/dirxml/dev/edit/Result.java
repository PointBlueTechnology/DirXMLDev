package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an operation did (or would do): whether the tree was written, the files
 * that changed, the artifacts touched, packaged artifacts now customized, the
 * validation report of the result, and — when refused — why.
 */
public final class Result {

    public final String operation;
    public boolean dryRun;
    public boolean written;
    public String refusal;                                  // null unless refused
    public final List<String> touched = new ArrayList<>();  // artifact paths
    public final Map<String, String> renamed = new LinkedHashMap<>();   // old path -> new path
    public final List<String> changedFiles = new ArrayList<>();
    public final List<String> deletedFiles = new ArrayList<>();
    public final List<String> customized = new ArrayList<>();   // packaged artifacts marked customized by this op
    public Report report;                                   // validation of the result (null if refused before validating)
    public final List<Finding> newErrors = new ArrayList<>();

    Result(String operation) {
        this.operation = operation;
    }

    public boolean ok() {
        return refusal == null;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append(operation).append(dryRun ? " (dry run)" : "").append(": ");
        if (refusal != null) {
            sb.append("REFUSED — ").append(refusal).append('\n');
        } else {
            sb.append(written ? "written" : "not written").append('\n');
        }
        for (Map.Entry<String, String> r : renamed.entrySet()) {
            sb.append("  renamed  ").append(r.getKey()).append(" -> ").append(r.getValue()).append('\n');
        }
        for (String t : touched) {
            sb.append("  touched  ").append(t).append('\n');
        }
        for (String c : customized) {
            sb.append("  customized (packaged; baseline kept) ").append(c).append('\n');
        }
        for (String f : changedFiles) {
            sb.append("  wrote    ").append(f).append('\n');
        }
        for (String f : deletedFiles) {
            sb.append("  deleted  ").append(f).append('\n');
        }
        if (!newErrors.isEmpty()) {
            sb.append("  new validation error(s) introduced by this operation:\n");
            for (Finding f : newErrors) {
                sb.append("    ").append(f).append('\n');
            }
        }
        if (report != null) {
            sb.append("  validate: ").append(report.summary()).append('\n');
        }
        return sb.toString();
    }

    public String json() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"operation\":").append(q(operation));
        sb.append(",\"ok\":").append(ok());
        sb.append(",\"dryRun\":").append(dryRun);
        sb.append(",\"written\":").append(written);
        if (refusal != null) {
            sb.append(",\"refusal\":").append(q(refusal));
        }
        sb.append(",\"touched\":").append(arr(touched));
        sb.append(",\"renamed\":{");
        boolean first = true;
        for (Map.Entry<String, String> r : renamed.entrySet()) {
            sb.append(first ? "" : ",").append(q(r.getKey())).append(':').append(q(r.getValue()));
            first = false;
        }
        sb.append('}');
        sb.append(",\"customized\":").append(arr(customized));
        sb.append(",\"changedFiles\":").append(arr(changedFiles));
        sb.append(",\"deletedFiles\":").append(arr(deletedFiles));
        sb.append(",\"newErrors\":[");
        first = true;
        for (Finding f : newErrors) {
            sb.append(first ? "" : ",").append("{\"code\":").append(q(f.code)).append(",\"path\":").append(q(f.path))
                .append(",\"message\":").append(q(f.message)).append('}');
            first = false;
        }
        sb.append(']');
        if (report != null) {
            sb.append(",\"validate\":").append(report.json());
        }
        return sb.append('}').toString();
    }

    private static String arr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(items.get(i)));
        }
        return sb.append(']').toString();
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
}
