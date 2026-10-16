package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pinned certificates (DirXMLDevWeb docs/connections-ui.md): a TLS server on 127.0.0.1 with a self-signed
 * certificate naming another host ("vault01.example"): untrusted by the JVM and by name, so only a pin lets a
 * client in; a different certificate is refused naming both fingerprints; the probe says what it sees.
 */
public class PinnedTrustTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SSLServerSocket server;
    private Thread acceptor;
    private X509Certificate cert;
    private int port;

    @Before
    public void startServer() throws Exception {
        Path ks = tmp.getRoot().toPath().resolve("vault.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "vault", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=vault01.example", "-keystore", ks.toString(), "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks.toFile())) {
            store.load(in, "changeit".toCharArray());
        }
        cert = (X509Certificate) store.getCertificate("vault");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());
        port = server.getLocalPort();
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    s.setSoTimeout(3000);
                    try {
                        s.startHandshake();
                    } catch (Exception ignored) {
                        // a refused client
                    }
                } catch (Exception closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void stopServer() throws Exception {
        server.close();
    }

    private void connect(String pin) throws Exception {
        try (SSLSocket s = (SSLSocket) PinnedTrust.socketFactory(pin).createSocket("127.0.0.1", port)) {
            javax.net.ssl.SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("LDAPS");   // as JNDI asks: the host name is checked too
            s.setSSLParameters(p);
            s.startHandshake();
        }
    }

    private static PinnedTrust.Untrusted untrusted(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PinnedTrust.Untrusted u) {
                return u;
            }
        }
        throw new AssertionError("no Untrusted in " + e, e);
    }

    @Test
    public void thePinnedCertificateIsTrustedWhateverItsIssuerOrName() throws Exception {
        String fp = PinnedTrust.sha256(cert);
        connect(fp);
        connect(fp.toUpperCase().replaceAll("(..)(?!$)", "$1:"));   // AB:CD:… as tools print it
    }

    @Test
    public void withoutAPinItIsRefusedNamingTheCertificate() throws Exception {
        try {
            connect(null);
            fail("an untrusted certificate was accepted");
        } catch (Exception e) {
            PinnedTrust.Untrusted u = untrusted(e);
            assertEquals(PinnedTrust.sha256(cert), u.sha256);
            assertNull(u.pinned);
            assertTrue(u.getMessage(), u.getMessage().contains("CN=vault01.example") && u.getMessage().contains(u.sha256));
        }
    }

    @Test
    public void aChangedCertificateIsRefusedNamingBothFingerprints() throws Exception {
        String other = "00".repeat(32);
        try {
            connect(other);
            fail("a certificate other than the pinned one was accepted");
        } catch (Exception e) {
            PinnedTrust.Untrusted u = untrusted(e);
            assertEquals(other, u.pinned);
            assertTrue(u.getMessage(), u.getMessage().startsWith("the vault's certificate changed") && u.getMessage().contains(other)
                && u.getMessage().contains(PinnedTrust.sha256(cert)));
        }
    }

    @Test
    public void theProbeShowsTheCertificateAndWhyItIsNotTrusted() throws Exception {
        SignIn.Certificate c = SignIn.probe("ldaps://127.0.0.1:" + port, 3000);
        assertEquals(PinnedTrust.sha256(cert), c.sha256);
        assertEquals("CN=vault01.example", c.subject);
        assertFalse(c.trusted);
        assertNotNull(c.problem);
        assertTrue(Instant.parse(c.notAfter).isAfter(Instant.now()));
    }

    @Test
    public void aSignInRefusedForItsCertificateSaysSo() {
        Vault.Config c = new Vault.Config();
        c.url = "ldaps://127.0.0.1:" + port;
        c.bindDn = "cn=admin,o=system";
        c.password = "x";
        SignIn.Check check = SignIn.check(c);
        assertEquals(check.message, SignIn.State.CERTIFICATE, check.state);
        assertEquals(PinnedTrust.sha256(cert), check.sha256);
        assertNull(check.pinned);

        c.certSha256 = "00".repeat(32);
        SignIn.Check changed = SignIn.check(c);
        assertEquals(SignIn.State.CERTIFICATE, changed.state);
        assertEquals("00".repeat(32), changed.pinned);
        assertTrue(changed.message, changed.message.startsWith("The vault's certificate changed"));
    }
}
