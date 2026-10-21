package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The environments an object is deployed to ({@code environments="QA, PROD"} on its manifest entry): a driver, a job,
 * a role-based entitlement policy, a notification template. Empty means every environment
 * ({@code docs/existing-environments.md}).
 */
public final class EnvironmentList {

    private EnvironmentList() {
    }

    /** True when a deploy to {@code env} gets the object: no list, no environment named, or {@code env} in it (any case). */
    public static boolean includes(List<String> environments, String env) {
        if (environments.isEmpty() || env == null) {
            return true;
        }
        for (String e : environments) {
            if (e.equalsIgnoreCase(env)) {
                return true;
            }
        }
        return false;
    }

    /** The attribute's value, or null for every environment (no attribute written). */
    public static String format(List<String> environments) {
        return environments.isEmpty() ? null : String.join(", ", environments);
    }

    /** The attribute's value as a list, trimmed, duplicates (any case) dropped; null or blank = every environment. */
    public static List<String> parse(String value) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String e : value.split(",")) {
                String t = e.trim();
                if (!t.isEmpty() && out.stream().noneMatch(x -> x.equalsIgnoreCase(t))) {
                    out.add(t);
                }
            }
        }
        return out;
    }
}
