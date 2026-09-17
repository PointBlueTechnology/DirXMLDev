package com.pointblue.dirxml.dev.model;

/**
 * The engine's policy sets, by the ids used in {@code <linkage-item policy-set=…>}
 * (exports) and {@code DirXML-Policies} values {@code <dn>#<order>#<setId>} (vault).
 * Subscriber sets are even, publisher sets odd, from 4 through 13.
 * Startup is 15 and Shutdown is 16 (IDM 4.0.2.3+; Understanding Policies Guide).
 */
public enum PolicySet {
    SCHEMA_MAPPING(0, "schema-mapping"),
    INPUT(1, "input"),
    OUTPUT(2, "output"),
    ECMASCRIPT(3, "ecmascript"),
    SUB_EVENT(4, "subscriber-event"),
    PUB_EVENT(5, "publisher-event"),
    SUB_MATCH(6, "subscriber-matching"),
    PUB_MATCH(7, "publisher-matching"),
    SUB_CREATE(8, "subscriber-create"),
    PUB_CREATE(9, "publisher-create"),
    SUB_COMMAND(10, "subscriber-command"),
    PUB_COMMAND(11, "publisher-command"),
    SUB_PLACEMENT(12, "subscriber-placement"),
    PUB_PLACEMENT(13, "publisher-placement"),
    GCV(14, "gcv"),
    STARTUP(15, "startup"),
    SHUTDOWN(16, "shutdown");

    public final int id;
    public final String key;

    PolicySet(int id, String key) {
        this.id = id;
        this.key = key;
    }

    public static PolicySet byId(int id) {
        PolicySet s = findById(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown policy-set id " + id);
        }
        return s;
    }

    /** {@link #byId(int)} or {@code null} when the engine id is not in this enum. */
    public static PolicySet findById(int id) {
        for (PolicySet s : values()) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    public static PolicySet byKey(String key) {
        for (PolicySet s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown policy-set key '" + key + "'");
    }

    /** Channel policy sets only (event through placement). GCV / Startup / Shutdown are driver-level. */
    public boolean isChannel() {
        return id >= SUB_EVENT.id && id <= PUB_PLACEMENT.id;
    }

    public boolean isSubscriber() {
        return isChannel() && id % 2 == 0;
    }

    public boolean isPublisher() {
        return isChannel() && id % 2 == 1;
    }
}
