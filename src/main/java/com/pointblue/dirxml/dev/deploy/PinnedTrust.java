package com.pointblue.dirxml.dev.deploy;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Trust for a vault's LDAPS certificate (docs: DirXMLDevWeb connections-ui.md, Certificates): what the JVM trusts,
 * host name included, as before; or else the one certificate whose SHA-256 is the environment's pin
 * ({@code <env>.certSha256}). A pinned certificate is the vault's identity, so its host name need not match the URL
 * (a vault reached by IP or through a tunnel). Anything else is refused with an {@link Untrusted} that carries the
 * certificate, so a caller can show it and offer to pin it. {@code trustAll} stays a separate opt-out.
 */
public final class PinnedTrust extends X509ExtendedTrustManager {

    /** A certificate neither the JVM nor the pin trusts: the refusal, with the certificate presented. */
    public static final class Untrusted extends CertificateException {
        public final transient X509Certificate certificate;
        public final String sha256;
        public final String pinned;   // null when there is no pin

        Untrusted(X509Certificate certificate, String pinned, String why) {
            super(message(certificate, pinned, why));
            this.certificate = certificate;
            this.sha256 = sha256(certificate);
            this.pinned = pinned;
        }

        private static String message(X509Certificate c, String pinned, String why) {
            String fp = sha256(c);
            if (pinned != null) {
                return "the vault's certificate changed: it presents SHA-256 " + fp + ", the environment trusts " + pinned
                    + " (" + c.getSubjectX500Principal().getName() + ")";
            }
            return "certificate not trusted (" + why + "): " + c.getSubjectX500Principal().getName() + ", SHA-256 " + fp;
        }
    }

    private final X509ExtendedTrustManager system;
    private final String pin;

    /** {@code pin}: a SHA-256 in hex (colons and case ignored), or null for the JVM's trust alone. */
    public PinnedTrust(String pin) throws GeneralSecurityException {
        this.system = systemTrust();
        this.pin = normalize(pin);
    }

    /** An SSL socket factory trusting the JVM's roots or {@code pin}. */
    public static SSLSocketFactory socketFactory(String pin) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {new PinnedTrust(pin)}, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }

    /** Lower-case hex without separators, or null for blank. */
    public static String normalize(String pin) {
        if (pin == null) {
            return null;
        }
        String p = pin.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
        return p.isEmpty() ? null : p;
    }

    /** The certificate's SHA-256, lower-case hex. */
    public static String sha256(X509Certificate c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (CertificateEncodingException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when {@code c} is the pinned certificate. */
    public static boolean matches(String pin, X509Certificate c) {
        String p = normalize(pin);
        return p != null && c != null && p.equals(sha256(c));
    }

    static X509ExtendedTrustManager systemTrust() throws GeneralSecurityException {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init((KeyStore) null);
        for (TrustManager t : f.getTrustManagers()) {
            if (t instanceof X509ExtendedTrustManager x) {
                return x;
            }
        }
        throw new GeneralSecurityException("no X509 trust manager in the JVM");
    }

    private void decide(X509Certificate[] chain, CertificateException failure) throws CertificateException {
        X509Certificate leaf = chain == null || chain.length == 0 ? null : chain[0];
        if (leaf != null && pin != null && pin.equals(sha256(leaf))) {
            return;   // the pinned certificate: trusted whatever its issuer or host name
        }
        if (leaf == null) {
            throw failure;
        }
        Untrusted u = new Untrusted(leaf, pin, failure.getMessage());
        u.initCause(failure);
        throw u;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType, socket);
        } catch (CertificateException e) {
            decide(chain, e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType, engine);
        } catch (CertificateException e) {
            decide(chain, e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            decide(chain, e);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        system.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        system.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        system.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return system.getAcceptedIssuers();
    }

    /** For callers holding an {@link X509TrustManager} slot: this one. */
    public X509TrustManager asTrustManager() {
        return this;
    }
}
