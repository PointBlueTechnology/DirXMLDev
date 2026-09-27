package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.junit.Test;

/** The extended-op LDAPS socket verifies the certificate's host name (the JNDI channel gets that from the JVM). */
public class VaultTlsTest {

    @Test
    public void endpointCheckingSocketsAskForHostNameVerification() throws Exception {
        javax.net.ssl.SSLSocketFactory plain = SSLContext.getDefault().getSocketFactory();
        try (SSLSocket raw = (SSLSocket) plain.createSocket()) {
            assertNull("a raw JSSE socket does not check the host name", raw.getSSLParameters().getEndpointIdentificationAlgorithm());
        }
        try (SSLSocket checked = (SSLSocket) Vault.endpointChecking(plain).createSocket()) {
            assertEquals("HTTPS", checked.getSSLParameters().getEndpointIdentificationAlgorithm());
        }
    }
}
