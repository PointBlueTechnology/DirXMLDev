package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.DriverSet;

/**
 * One validation pass over a driver set. Checks are independent: each adds its own
 * findings and never throws for a problem in the model — a problem is a finding.
 * (An exception from a check is a bug in the check, and {@link Validator} reports
 * it as a {@code check-failed} error rather than aborting the run.)
 */
public interface Check {

    /** A short stable id used in the {@code check-failed} finding and in logs. */
    String name();

    void run(DriverSet ds, Report report);
}
