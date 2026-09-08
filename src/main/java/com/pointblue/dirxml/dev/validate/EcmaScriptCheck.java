package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.DriverSet;

/** ECMAScript resources compile; {@code es:} calls resolve. See docs/validation.md. (Stub — implementation pending.) */
public final class EcmaScriptCheck implements Check {

    @Override
    public String name() {
        return "ecmascript";
    }

    @Override
    public void run(DriverSet ds, Report r) {
    }
}
