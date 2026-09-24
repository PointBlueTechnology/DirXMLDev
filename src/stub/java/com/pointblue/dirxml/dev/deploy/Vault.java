package com.pointblue.dirxml.dev.deploy;

/**
 * Stand-in used only by {@code bin/ci-portable.sh} (the {@code idm.portable} Maven profile).
 * The real {@code Vault} imports the proprietary LDAP client and is not on this compile.
 * {@code Doctor}'s default probe calls {@link #connect}; tests pass their own probe and never bind.
 */
public final class Vault implements AutoCloseable {

    public static final class Config {
        public String url;
        public String bindDn;
        public String password;
        public boolean trustAll;
    }

    public static Vault connect(Config config) {
        return new Vault();
    }

    @Override
    public void close() {
    }
}
