package com.pointblue.dirxml.dev.model;

/** Where an artifact lives — determines its path and, in the vault, its container. */
public enum Scope {
    LIBRARY("library"),
    DRIVER("driver"),
    SUBSCRIBER("subscriber"),
    PUBLISHER("publisher");

    public final String key;

    Scope(String key) {
        this.key = key;
    }

    public static Scope byKey(String key) {
        for (Scope s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown scope '" + key + "'");
    }
}
