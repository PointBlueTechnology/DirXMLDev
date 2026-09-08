package com.pointblue.dirxml.dev.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A policy or resource: something with a name, a scope, and an identity
 * <b>path</b> — {@code library/<name>}, {@code drivers/<driver>/<name>},
 * {@code drivers/<driver>/subscriber/<name>}, {@code drivers/<driver>/publisher/<name>}.
 * Paths are what links refer to, so they must be unique; names are unique within a
 * scope (eDir enforces cn uniqueness per container).
 */
public abstract class Artifact {

    public final String name;
    public final Scope scope;
    /** Owning driver name; null for library scope. */
    public final String driver;
    /** Source-specific extras preserved losslessly (package ids, Designer ids, DN…). */
    public final Map<String, String> meta = new LinkedHashMap<>();

    protected Artifact(String name, Scope scope, String driver) {
        this.name = Objects.requireNonNull(name, "name");
        this.scope = Objects.requireNonNull(scope, "scope");
        if (scope == Scope.LIBRARY) {
            this.driver = null;
        } else {
            this.driver = Objects.requireNonNull(driver, "driver (required for scope " + scope + ")");
        }
    }

    /** The artifact's identity path (see class doc). */
    public String path() {
        return path(scope, driver, name);
    }

    public static String path(Scope scope, String driver, String name) {
        switch (scope) {
            case LIBRARY:    return "library/" + name;
            case DRIVER:     return "drivers/" + driver + "/" + name;
            case SUBSCRIBER: return "drivers/" + driver + "/subscriber/" + name;
            case PUBLISHER:  return "drivers/" + driver + "/publisher/" + name;
            default:         throw new IllegalStateException();
        }
    }

    /** "policy" or "resource". */
    public abstract String kind();

    @Override
    public String toString() {
        return kind() + " " + path();
    }
}
