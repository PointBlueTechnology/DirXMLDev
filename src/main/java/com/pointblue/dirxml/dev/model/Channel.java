package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.List;

/** A driver's Subscriber or Publisher container and the policies that live in it. */
public final class Channel {

    public final Scope scope;   // SUBSCRIBER or PUBLISHER
    public final List<Policy> policies = new ArrayList<>();

    public Channel(Scope scope) {
        if (scope != Scope.SUBSCRIBER && scope != Scope.PUBLISHER) {
            throw new IllegalArgumentException("channel scope must be subscriber|publisher");
        }
        this.scope = scope;
    }
}
