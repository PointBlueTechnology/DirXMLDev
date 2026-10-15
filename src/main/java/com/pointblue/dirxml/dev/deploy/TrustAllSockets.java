package com.pointblue.dirxml.dev.deploy;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

/**
 * The socket factory JNDI loads by name ({@code java.naming.ldap.factory.socket}, so it needs a static
 * {@code getDefault()}) when an environment says {@code trustAll}: every certificate accepted, for a lab
 * or a tunnel only (docs/vault-deploy.md). Here, not in the simulator, so the vault path needs no
 * simulator jar (docs/vendor-free-vault.md).
 */
public final class TrustAllSockets extends SSLSocketFactory {

    static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private final SSLSocketFactory delegate;

    public TrustAllSockets() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {TRUST_ALL}, new java.security.SecureRandom());
            this.delegate = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("could not initialise a trust-all TLS context", e);
        }
    }

    /** JNDI's entry point. */
    public static SocketFactory getDefault() {
        return new TrustAllSockets();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
