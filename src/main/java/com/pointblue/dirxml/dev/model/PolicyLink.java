package com.pointblue.dirxml.dev.model;

import java.util.Objects;

/**
 * One entry of a driver's policy-set linkage: which artifact (by path) runs in
 * which set, at which position. The only cross-object reference in the model.
 */
public final class PolicyLink {

    public final PolicySet set;
    public String ref;      // artifact path, e.g. "drivers/AD/subscriber/sub-etp_Scoping"
    public int order;

    public PolicyLink(PolicySet set, String ref, int order) {
        this.set = Objects.requireNonNull(set, "set");
        this.ref = Objects.requireNonNull(ref, "ref");
        this.order = order;
    }

    @Override
    public String toString() {
        return set.key + "#" + order + " -> " + ref;
    }
}
