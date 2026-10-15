package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.EntitlementPolicy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which validation errors hold back a deploy scoped to some drivers. A scoped deploy writes those drivers, the
 * Library and the driver set's own settings, and the RBE policies when the Entitlements Service driver is among
 * them ({@link VaultDiff#narrow}); an error anywhere else (another driver, a driver-set job or template) cannot
 * reach the vault through it, so it is listed and does not refuse the deploy. An error blocks when it is in:
 * <ul>
 *   <li>a driver in scope ({@code drivers/<name>/…});</li>
 *   <li>a Library artifact a driver in scope links;</li>
 *   <li>an RBE policy, when the Entitlements Service driver is in scope;</li>
 *   <li>anything else that is not another driver's, a driver-set job or a template: the driver set's settings,
 *       the schema, a finding with no path. Unknown places block.</li>
 * </ul>
 * A deploy of the whole driver set has no scope: every error blocks, as before.
 */
public final class ValidationScope {

    /** The errors split into those that refuse the deploy and those outside its scope. */
    public static final class Split {
        public final List<Finding> blocking = new ArrayList<>();
        public final List<Finding> outside = new ArrayList<>();

        /** A line for the plan when the errors outside the scope do not block it; empty when there are none. */
        public String note() {
            if (outside.isEmpty()) {
                return "";
            }
            Set<String> where = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Finding f : outside) {
                where.add(owner(f.path));
            }
            return "validation: " + outside.size() + " error(s) outside this deploy's scope, not deployed by it ("
                + String.join(", ", where) + "); run idm validate to see them\n";
        }
    }

    private ValidationScope() {
    }

    /** Splits the report's errors for a deploy of {@code drivers}; reads the tree for the scoped drivers' links. */
    public static Split split(Report report, Path tree, Collection<String> drivers) throws IOException {
        DriverSet ds;
        try {
            ds = AsCodeReader.read(tree);
        } catch (RuntimeException | IOException e) {
            Split all = new Split();
            all.blocking.addAll(report.of(Finding.Severity.ERROR));
            return all;
        }
        return split(report, ds, drivers);
    }

    public static Split split(Report report, DriverSet ds, Collection<String> drivers) {
        Set<String> scoped = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        scoped.addAll(drivers);
        Set<String> linkedLibrary = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean rbeInScope = false;
        for (Driver d : ds.drivers) {
            if (!scoped.contains(d.name)) {
                continue;
            }
            for (PolicyLink l : d.links) {
                if (l.ref != null && l.ref.startsWith("library/")) {
                    linkedLibrary.add(l.ref);
                }
            }
            rbeInScope |= EntitlementPolicy.SERVICE_SHIM_CLASS.equals(d.shimClass);
        }
        Split s = new Split();
        for (Finding f : report.of(Finding.Severity.ERROR)) {
            (blocks(f.path, scoped, linkedLibrary, rbeInScope) ? s.blocking : s.outside).add(f);
        }
        return s;
    }

    static boolean blocks(String path, Set<String> scoped, Set<String> linkedLibrary, boolean rbeInScope) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("drivers/")) {
            String rest = p.substring("drivers/".length());
            int slash = rest.indexOf('/');
            return scoped.contains(slash < 0 ? rest : rest.substring(0, slash));
        }
        if (p.startsWith("library/")) {
            // "library/<name>" or a file under it: the artifact is the first two segments
            String[] seg = p.split("/");
            String artifact = seg.length >= 2 ? seg[0] + "/" + stripExtension(seg[1]) : p;
            return linkedLibrary.contains(artifact) || linkedLibrary.contains(p);
        }
        if (p.startsWith("rbe-policies/")) {
            return rbeInScope;
        }
        if (p.startsWith("jobs/") || p.startsWith("templates/")) {
            return false;   // the driver set's own jobs and templates: a scoped deploy does not write them
        }
        return true;
    }

    /** "drivers/AD/…" → "AD", "library/X" → "Library", "jobs/…" → "jobs", … for the note. */
    static String owner(String path) {
        if (path == null || path.isBlank()) {
            return "driver set";
        }
        String[] seg = path.replace('\\', '/').split("/");
        if (seg[0].equals("drivers") && seg.length > 1) {
            return seg[1];
        }
        return seg[0].equals("library") ? "Library" : seg[0];
    }

    private static String stripExtension(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** The findings one per line, as idm validate prints them, for the refusal. */
    public static String list(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            sb.append("  ").append(f.code).append("  ").append(f.path == null ? "" : f.path).append(": ").append(f.message).append('\n');
        }
        return sb.toString();
    }
}
