package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.List;

/** The driver set's Library: shared policies and resources (mapping tables, ECMAScript). */
public final class Library {

    public final List<Policy> policies = new ArrayList<>();
    public final List<Resource> resources = new ArrayList<>();

    public List<Artifact> artifacts() {
        List<Artifact> all = new ArrayList<>(policies);
        all.addAll(resources);
        return all;
    }
}
