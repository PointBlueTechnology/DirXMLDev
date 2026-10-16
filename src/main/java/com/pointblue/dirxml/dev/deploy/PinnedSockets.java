package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * The socket factory JNDI loads by name ({@code java.naming.ldap.factory.socket}) for an LDAPS vault that is not
 * {@code trustAll}: {@link PinnedTrust}, the JVM's trust or the environment's pinned certificate. JNDI calls the
 * static {@link #getDefault()} while it opens the connection, on the thread that asked for it, so {@code Vault.connect}
 * hands the pin over in {@link #PIN} for exactly that call.
 */
public final class PinnedSockets extends SSLSocketFactory {

    /** The pin for the connection being opened on this thread (null: the JVM's trust alone). */
    static final ThreadLocal<String> PIN = new ThreadLocal<>();

    private final SSLSocketFactory delegate;

    public PinnedSockets() {
        try {
            this.delegate = PinnedTrust.socketFactory(PIN.get());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SocketFactory getDefault() {
        return new PinnedSockets();
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
    public Socket createSocket() throws IOException {
        return delegate.createSocket();
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
