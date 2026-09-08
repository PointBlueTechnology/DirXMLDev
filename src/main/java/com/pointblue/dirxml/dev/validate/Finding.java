package com.pointblue.dirxml.dev.validate;

import java.util.Objects;

/**
 * One validation finding: a severity, a stable machine-readable {@code code}, the
 * artifact (or driver / driver set) it concerns, and a message. {@code detail} is
 * optional free text (the engine's full diagnostic, a list of names, …).
 *
 * <p>Codes are the contract with agents and CI: see {@code docs/validation.md}.
 * Paths are artifact paths ({@code library/X}, {@code drivers/D/subscriber/X}),
 * {@code drivers/D} for a driver-level finding, or {@code driverset}.
 */
public final class Finding {

    public enum Severity { ERROR, WARNING, INFO }

    public final Severity severity;
    public final String code;
    public final String path;
    public final String message;
    public final String detail;

    public Finding(Severity severity, String code, String path, String message, String detail) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = Objects.requireNonNull(code, "code");
        this.path = path == null ? "driverset" : path;
        this.message = Objects.requireNonNull(message, "message");
        this.detail = detail;
    }

    public static Finding error(String code, String path, String message) {
        return new Finding(Severity.ERROR, code, path, message, null);
    }

    public static Finding error(String code, String path, String message, String detail) {
        return new Finding(Severity.ERROR, code, path, message, detail);
    }

    public static Finding warning(String code, String path, String message) {
        return new Finding(Severity.WARNING, code, path, message, null);
    }

    public static Finding warning(String code, String path, String message, String detail) {
        return new Finding(Severity.WARNING, code, path, message, detail);
    }

    public static Finding info(String code, String path, String message) {
        return new Finding(Severity.INFO, code, path, message, null);
    }

    public static Finding info(String code, String path, String message, String detail) {
        return new Finding(Severity.INFO, code, path, message, detail);
    }

    @Override
    public String toString() {
        return String.format("%-7s %-28s %s: %s", severity, code, path, message);
    }
}
