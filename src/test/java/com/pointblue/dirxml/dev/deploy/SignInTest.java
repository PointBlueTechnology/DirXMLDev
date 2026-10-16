package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.net.ConnectException;
import java.time.Instant;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;

import org.junit.Test;

/** A sign-in's outcome in words: rejected, unreachable and certificate told apart; the password's expiry read. */
public class SignInTest {

    private static Vault.Config config() {
        Vault.Config c = new Vault.Config();
        c.url = "ldaps://vault01.example:636";
        c.bindDn = "cn=admin,ou=sa,o=system";
        return c;
    }

    private static Vault.VaultException wrapped(Throwable cause) {
        return new Vault.VaultException("connect " + config().url + " as " + config().bindDn + ": " + cause.getMessage(), cause);
    }

    @Test
    public void aRejectedPasswordIsItsOwnState() {
        SignIn.Check c = SignIn.classify(wrapped(new AuthenticationException("[LDAP: error code 49 - NDS error: failed authentication (-669)]")), config());
        assertEquals(SignIn.State.REJECTED, c.state);
        assertEquals("wrong password", c.reason);
        assertEquals("The vault rejected the sign-in for cn=admin,ou=sa,o=system: wrong password.", c.message);
        assertEquals("the password has expired and no grace logins are left",
            SignIn.classify(wrapped(new AuthenticationException("[LDAP: error code 49 - NDS error: password expired (-222)]")), config()).reason);
        assertEquals("the account is locked after too many failed sign-ins", SignIn.rejectedReason("NDS error: intruder lockout (-197)"));
        assertEquals("wrong password", SignIn.rejectedReason("[LDAP: error code 49 - 80090308: LdapErr: DSID-0C09042A, comment: AcceptSecurityContext error, data 52e, v3839]"));
        assertEquals("invalid credentials", SignIn.rejectedReason("[LDAP: error code 49 - Invalid Credentials]"));
    }

    @Test
    public void anUnreachableVaultIsNotARejectedPassword() {
        CommunicationException ce = new CommunicationException("vault01.example:636");
        ce.setRootCause(new ConnectException("Connection refused"));
        SignIn.Check c = SignIn.classify(wrapped(ce), config());
        assertEquals(SignIn.State.UNREACHABLE, c.state);
        assertNull(c.reason);
    }

    @Test
    public void expiryIsReadFromTheAccount() {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        SignIn.Check soon = new SignIn.Check();
        SignIn.expiry(soon, "20261021120000Z", "6", now);
        assertEquals(Long.valueOf(5), soon.daysLeft);
        assertEquals("2026-10-21T12:00:00Z", soon.passwordExpires);
        assertNull("grace logins only matter once it has expired", soon.graceRemaining);

        SignIn.Check expired = new SignIn.Check();
        SignIn.expiry(expired, "20261010120000Z", "2", now);
        assertEquals(Long.valueOf(-6), expired.daysLeft);
        assertEquals(Integer.valueOf(2), expired.graceRemaining);

        SignIn.Check none = new SignIn.Check();
        SignIn.expiry(none, null, null, now);
        assertNull(none.daysLeft);
    }

    @Test
    public void anEnvironmentsPinIsNormalised() {
        assertEquals("abcd01", PinnedTrust.normalize(" AB:CD:01 "));
        assertNull(PinnedTrust.normalize("  "));
    }
}
