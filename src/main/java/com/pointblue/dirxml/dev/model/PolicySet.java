package com.pointblue.dirxml.dev.model;

/**
 * The engine's policy sets, by the ids used in {@code <linkage-item policy-set=…>}
 * (exports) and {@code DirXML-Policies} values {@code <dn>#<order>#<setId>} (vault).
 * Subscriber sets are even, publisher sets odd, from 4 up.
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
    GCV(14, "gcv");

    public final int id;
    public final String key;

    PolicySet(int id, String key) {
        this.id = id;
        this.key = key;
    }

    public static PolicySet byId(int id) {
        for (PolicySet s : values()) {
            if (s.id == id) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown policy-set id " + id);
    }

    public static PolicySet byKey(String key) {
        for (PolicySet s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown policy-set key '" + key + "'");
    }

    public boolean isSubscriber() {
        return id >= 4 && id % 2 == 0 && id != GCV.id;
    }

    public boolean isPublisher() {
        return id >= 5 && id % 2 == 1;
    }
}
