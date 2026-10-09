package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Job;
import java.util.Locale;

/**
 * Jobs ({@code DirXML-Job}, docs/console-gaps.md §1), on by default in {@link Validator#standard}.
 *
 * <p>Codes: {@code job-name-blank} (E), {@code job-no-document} (E), {@code job-wrong-root} (E),
 * {@code job-no-class} (W), {@code job-no-server} (W: it will run nowhere), {@code job-unknown-server}
 * (W: a server the driver set does not list), {@code job-disabled} (I).
 */
public final class JobCheck implements Check {

    @Override
    public String name() {
        return "jobs";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        for (Job j : ds.jobs) {
            check(ds, null, j, r);
        }
        for (Driver d : ds.drivers) {
            for (Job j : d.jobs) {
                check(ds, d, j, r);
            }
        }
    }

    private static void check(DriverSet ds, Driver d, Job j, Report r) {
        String path = (d == null ? "jobs/" : "drivers/" + d.name + "/jobs/") + j.name;
        if (j.name == null || j.name.isBlank()) {
            r.add(Finding.error("job-name-blank", path, "job has a blank name"));
        }
        if (j.definition == null) {
            r.add(Finding.error("job-no-document", path, "job has no configuration document"));
            return;
        }
        String root = j.definition.getLocalName() != null ? j.definition.getLocalName() : j.definition.getNodeName();
        if (!"job-aggregation".equals(root) && !"job-definition".equals(root)) {
            r.add(Finding.error("job-wrong-root", path, "job document's root is <" + root + ">, not <job-aggregation> or <job-definition>"));
            return;
        }
        if (j.javaClass() == null) {
            r.add(Finding.warning("job-no-class", path, "job names no <java-class>"));
        }
        if (j.servers.isEmpty()) {
            r.add(Finding.warning("job-no-server", path, "job lists no server to run on"));
        } else if (!ds.servers.isEmpty()) {
            for (String s : j.servers) {
                boolean known = false;
                for (String known1 : ds.servers) {
                    if (known1.equalsIgnoreCase(s) || leaf(known1).equalsIgnoreCase(leaf(s))) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    r.add(Finding.warning("job-unknown-server", path, "job runs on '" + s + "', which the driver set does not list"));
                }
            }
        }
        if (j.disabled()) {
            r.add(Finding.info("job-disabled", path, "job is disabled"));
        }
    }

    private static String leaf(String dn) {
        return dn.replaceFirst("^[^=]+=", "").replaceFirst(",.*$", "").toLowerCase(Locale.ROOT);
    }
}
