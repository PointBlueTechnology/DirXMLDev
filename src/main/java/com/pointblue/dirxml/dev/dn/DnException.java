package com.pointblue.dirxml.dev.dn;

/** A DN, RDN, dot name or filter value that cannot be parsed or built. The message names the position. */
public final class DnException extends IllegalArgumentException {
    public DnException(String message) {
        super(message);
    }
}
