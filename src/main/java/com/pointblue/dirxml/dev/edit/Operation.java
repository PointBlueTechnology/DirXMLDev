package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.DriverSet;

/**
 * One edit to a driver set, applied inside a {@link Transaction}. An operation
 * mutates the model and tells the transaction which artifacts it touched (so
 * packaged ones get their baseline snapshot) and which it renamed (so pre-existing
 * validation errors follow the artifact instead of counting as new). It refuses by
 * throwing {@link Refusal}; anything else it throws is a bug.
 */
public interface Operation {

    /** The registered name, e.g. {@code policy.add}. */
    String name();

    void apply(DriverSet ds, Transaction tx) throws Refusal, java.io.IOException;

    /** Why an operation would not do what was asked — reported, never a stack trace. */
    final class Refusal extends Exception {
        public Refusal(String message) {
            super(message);
        }
    }
}
