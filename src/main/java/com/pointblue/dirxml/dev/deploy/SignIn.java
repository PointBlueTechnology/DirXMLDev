package com.pointblue.dirxml.dev.deploy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * What a person setting up or using a vault connection needs to know, in words a page can show
 * (DirXMLDevWeb docs/connections-ui.md): the certificate the vault presents and whether it is trusted
 * ({@link #probe}), and whether a sign-in works, and if not, why ({@link #check}): a rejected password,
 * an unreachable vault and an untrusted certificate are different problems with different fixes. A
 * sign-in that works also says when the account's password expires, where eDirectory lets the account
 * read that on itself.
 */
public final class SignIn {

    private SignIn() {
    }

    // ---- the certificate ----

    /** The certificate a vault presents: who it names, who issued it, when it expires, its SHA-256, and whether this JVM trusts it. */
    public static final class Certificate {
        public String subject;
        public String issuer;
        public String notBefore;
        public String notAfter;
        public String sha256;
        /** The JVM trusts it for this host (chain and host name), with no pin. */
        public boolean trusted;
        /** Why it is not trusted, when it is not. */
        public String problem;

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", subject);
            m.put("issuer", issuer);
            m.put("notBefore", notBefore);
            m.put("notAfter", notAfter);
            m.put("sha256", sha256);
            m.put("trusted", trusted);
            m.put("problem", problem);
            return m;
        }

        static Certificate of(X509Certificate c) {
            Certificate out = new Certificate();
            out.subject = c.getSubjectX500Principal().getName();
            out.issuer = c.getIssuerX500Principal().getName();
            out.notBefore = c.getNotBefore().toInstant().toString();
            out.notAfter = c.getNotAfter().toInstant().toString();
            out.sha256 = PinnedTrust.sha256(c);
            return out;
        }
    }

    /**
     * The certificate at an {@code ldaps://} URL, without signing in. Two handshakes: one that accepts
     * anything to see the certificate, one with the JVM's trust and the host name checked to say whether
     * it is trusted as is. Throws when the vault cannot be reached at all.
     */
    public static Certificate probe(String url, int timeoutMs) throws Exception {
        com.pointblue.dirxml.dev.LegacyTls.enable();
        URI u = URI.create(url.contains("://") ? url : "ldaps://" + url);
        if (!"ldaps".equalsIgnoreCase(u.getScheme())) {
            throw new IllegalArgumentException("a certificate needs an ldaps:// address");
        }
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 636;
        X509Certificate[][] seen = new X509Certificate[1][];
        X509TrustManager capture = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                seen[0] = chain;
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        handshake(host, port, capture, false, timeoutMs);
        if (seen[0] == null || seen[0].length == 0) {
            throw new IllegalStateException("the vault presented no certificate");
        }
        Certificate c = Certificate.of(seen[0][0]);
        try {
            handshake(host, port, new PinnedTrust(null), true, timeoutMs);
            c.trusted = true;
        } catch (javax.net.ssl.SSLException e) {
            Throwable t = e;
            while (t.getCause() != null && !(t instanceof CertificateException)) {
                t = t.getCause();
            }
            c.trusted = false;
            c.problem = plainProblem(t.getMessage());
        }
        return c;
    }

    private static void handshake(String host, int port, TrustManager tm, boolean checkHost, int timeoutMs) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, new java.security.SecureRandom());
        try (java.net.Socket plain = new java.net.Socket()) {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            plain.setSoTimeout(timeoutMs);
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, false)) {
                if (checkHost) {
                    SSLParameters p = s.getSSLParameters();
                    p.setEndpointIdentificationAlgorithm("LDAPS");
                    s.setSSLParameters(p);
                }
                s.startHandshake();
            }
        }
    }

    private static String plainProblem(String m) {
        if (m == null) {
            return "not trusted";
        }
        if (m.contains("No subject alternative") || m.contains("No name matching")) {
            return "it names a different host";
        }
        if (m.contains("unable to find valid certification path") || m.contains("PKIX")) {
            return "issued by a certificate authority this server doesn't trust";
        }
        if (m.contains("expired") || m.contains("NotAfter")) {
            return "it has expired";
        }
        return m.replaceFirst("^certificate not trusted \\((.*)\\):.*$", "$1");
    }

    // ---- the sign-in ----

    public enum State { OK, REJECTED, CERTIFICATE, UNREACHABLE, ERROR }

    /** A sign-in's outcome, for a page: the state, a sentence, and the details each state has. */
    public static final class Check {
        public State state;
        public String message;
        /** REJECTED: eDirectory's reason, when it gave one (wrong password, expired, locked out…). */
        public String reason;
        /** CERTIFICATE: the certificate presented, and the pin it failed against (null: no pin). */
        public String sha256;
        public String subject;
        public String pinned;
        /** OK: when the account's password expires, and how many grace logins are left once it has. */
        public String passwordExpires;
        public Long daysLeft;
        public Integer graceRemaining;

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state.name().toLowerCase());
            m.put("message", message);
            m.put("reason", reason);
            m.put("sha256", sha256);
            m.put("subject", subject);
            m.put("pinned", pinned);
            m.put("passwordExpires", passwordExpires);
            m.put("daysLeft", daysLeft);
            m.put("graceRemaining", graceRemaining);
            return m;
        }
    }

    /** Sign in with {@code c} and say how it went; never throws. */
    public static Check check(Vault.Config c) {
        Check out = new Check();
        Vault v;
        try {
            v = Vault.connect(c);
        } catch (RuntimeException e) {
            Check failed = classify(e, c);
            if (failed.state != State.REJECTED && failed.state != State.CERTIFICATE && !c.trustAll && c.url != null && c.url.startsWith("ldaps")) {
                // JNDI reports a refused certificate as a closed connection ("simple bind failed"): ask the vault
                // for its certificate directly, and say so when that is what stopped the sign-in
                try {
                    Certificate cert = probe(c.url, 5000);
                    if (!cert.trusted && !cert.sha256.equals(PinnedTrust.normalize(c.certSha256))) {
                        return certificateCheck(cert.sha256, cert.subject, PinnedTrust.normalize(c.certSha256));
                    }
                } catch (Exception unreachable) {
                    // the probe can't reach it either: the first answer stands
                }
            }
            return failed;
        }
        try (v) {
            out.state = State.OK;
            out.message = "Signed in as " + c.bindDn;
            try {
                Vault.Entry me = v.read(c.bindDn, "passwordExpirationTime", "loginGraceRemaining");
                if (me != null) {
                    expiry(out, me.string("passwordExpirationTime"), me.string("loginGraceRemaining"), Instant.now());
                }
            } catch (RuntimeException ignored) {
                // the account may not read these on itself: nothing to say, nothing guessed
            }
        }
        return out;
    }

    /** The expiry details from the account's own attributes (eDirectory's GeneralizedTime and an integer). */
    static void expiry(Check out, String expires, String grace, Instant now) {
        if (expires != null && !expires.isBlank()) {
            try {
                Instant at = LocalDateTime.parse(expires.trim().replace("Z", ""), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .toInstant(ZoneOffset.UTC);
                out.passwordExpires = at.toString();
                out.daysLeft = ChronoUnit.DAYS.between(now, at);
            } catch (RuntimeException ignored) {
                // an unreadable time: say nothing
            }
        }
        if (grace != null && !grace.isBlank() && out.daysLeft != null && out.daysLeft < 0) {
            try {
                out.graceRemaining = Integer.parseInt(grace.trim());
            } catch (NumberFormatException ignored) {
                // not a number: say nothing
            }
        }
    }

    private static Check certificateCheck(String sha256, String subject, String pinned) {
        Check out = new Check();
        out.state = State.CERTIFICATE;
        out.sha256 = sha256;
        out.subject = subject;
        out.pinned = pinned;
        out.message = pinned != null
            ? "The vault's certificate changed. Trust the new one only if you expected it to change."
            : "The vault's certificate isn't trusted by this server. Check its fingerprint, then trust it.";
        return out;
    }

    private static final Pattern NDS = Pattern.compile("\\((-\\d{3,4})\\)");

    /** The state a failed {@link Vault#connect} is in, from its exception chain. */
    static Check classify(Throwable e, Vault.Config c) {
        Check out = new Check();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PinnedTrust.Untrusted u) {
                return certificateCheck(u.sha256, u.certificate == null ? null : u.certificate.getSubjectX500Principal().getName(), u.pinned);
            }
            if (t instanceof javax.naming.AuthenticationException) {
                out.state = State.REJECTED;
                out.reason = rejectedReason(t.getMessage());
                out.message = "The vault rejected the sign-in for " + c.bindDn + (out.reason == null ? "" : ": " + out.reason) + ".";
                return out;
            }
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                || t instanceof java.net.SocketTimeoutException || t instanceof java.net.NoRouteToHostException
                || t instanceof javax.naming.CommunicationException && !(t.getCause() instanceof javax.net.ssl.SSLException)) {
                out.state = State.UNREACHABLE;
                out.message = "Can't reach the vault at " + c.url + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()) + ".";
                if (!(t instanceof javax.naming.CommunicationException)) {
                    return out;
                }
            }
        }
        if (out.state == State.UNREACHABLE) {
            return out;
        }
        out.state = State.ERROR;
        out.message = e.getMessage();
        return out;
    }

    /** eDirectory's reason for a refused bind, in words; null when it gave none we know. */
    static String rejectedReason(String m) {
        if (m == null) {
            return null;
        }
        Matcher n = NDS.matcher(m);
        if (n.find()) {
            switch (n.group(1)) {
                case "-669": return "wrong password";
                case "-222": return "the password has expired and no grace logins are left";
                case "-197": return "the account is locked after too many failed sign-ins";
                case "-220": return "the account has expired";
                case "-218": return "sign-in isn't allowed at this time";
                case "-217": return "the account has reached its maximum concurrent sign-ins";
                case "-601": return "no such account";
                case "-215": return "the new password was used before";
                case "-216": return "the password is too short";
                default: return "eDirectory error " + n.group(1);
            }
        }
        if (m.contains("data 52e")) {
            return "wrong password";
        }
        if (m.contains("data 532")) {
            return "the password has expired";
        }
        if (m.contains("data 775")) {
            return "the account is locked";
        }
        if (m.contains("error code 49")) {
            return "invalid credentials";
        }
        return null;
    }
}
