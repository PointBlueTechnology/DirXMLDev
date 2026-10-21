package com.pointblue.dirxml.dev.model;

/**
 * A difference between two environments accepted as it is ({@code docs/existing-environments.md}, D1): kept in
 * {@code overrides/<env>.known.json}, so comparisons show only new ones. {@code fingerprint} is of the change itself;
 * when the object changes again the difference shows as new.
 */
public final class KnownDifference {
    /** The other environment of the pair. */
    public final String with;
    /** The changed object's tree path, as a comparison names it. */
    public final String path;
    public final String fingerprint;
    /** {@code intended} (different on purpose) or {@code later} (to decide). */
    public final String decision;
    public final String summary;
    public final String note;
    public final String by;
    public final String at;

    public KnownDifference(String with, String path, String fingerprint, String decision, String summary, String note, String by, String at) {
        this.with = with;
        this.path = path;
        this.fingerprint = fingerprint;
        this.decision = decision;
        this.summary = summary;
        this.note = note;
        this.by = by;
        this.at = at;
    }
}
