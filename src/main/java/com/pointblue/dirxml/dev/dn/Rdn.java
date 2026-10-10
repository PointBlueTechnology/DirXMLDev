package com.pointblue.dirxml.dev.dn;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A relative distinguished name: one AVA, or several joined by {@code +} (a multi-valued RDN such as
 * {@code cn=A+uid=B}). Equality follows eDirectory's naming: types and values case-ignored, and the
 * order of a multi-valued RDN's AVAs does not matter.
 */
public record Rdn(List<Ava> avas) {

    public Rdn {
        if (avas == null || avas.isEmpty()) {
            throw new DnException("an RDN needs at least one attribute value");
        }
        avas = List.copyOf(avas);
    }

    public static Rdn of(String type, String value) {
        return new Rdn(List.of(Ava.of(type, value)));
    }

    /** The first AVA's type ({@code cn}, {@code ou}, …). */
    public String type() {
        return avas.get(0).type();
    }

    /** The first AVA's unescaped value: what a person calls the object. */
    public String value() {
        return avas.get(0).value();
    }

    public boolean multiValued() {
        return avas.size() > 1;
    }

    String key() {
        return avas.stream().map(Ava::key).sorted().collect(Collectors.joining("+"));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rdn r && key().equals(r.key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    /** RFC 4514 string form. */
    @Override
    public String toString() {
        return avas.stream().map(Ava::toString).collect(Collectors.joining("+"));
    }
}
