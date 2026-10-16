package com.pointblue.dirxml.dev.operate;

import com.novell.ldap.events.edir.EdirEventConstant;
import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointbluetech.dirxml.trace.ldap.CertificateTrust;
import com.pointbluetech.dirxml.trace.ldap.ConnectionSettings;
import com.pointbluetech.dirxml.trace.ldap.LdapTraceEventSource;
import com.pointbluetech.dirxml.trace.model.TraceParser;
import com.pointbluetech.dirxml.trace.model.TraceRecord;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code driver.trace tail --ldap} and {@code trace.view --ldap}: the engine's DirXML trace events
 * over the environment's own LDAPS connection, so no SSH host, trace file or {@code DirXML-TraceFile}
 * is needed. The stream and its parsing are {@code dirxml-trace-core}'s, as in the trace viewer and
 * the web, over open-source JLDAP: no simulator and no engine jar. Each message is printed as a trace
 * file has it ({@code [timestamp]:Driver ST:text}, an XML document's lines after it), so
 * {@code trace.view} and the trace viewer read it; a continuation belongs to the driver before it.
 * <p>
 * Certificates: one the JVM does not trust is accepted for this run when it is the environment's pinned
 * certificate ({@code certSha256}) or the environment says {@code trustAll} (a lab, a tunnel), and refused
 * otherwise — the core's vault connections do the same.
 */
public final class LdapTrace {

    /** host:port of the servers whose environment says trustAll. */
    private static final Set<String> TRUSTED = ConcurrentHashMap.newKeySet();

    /** host:port → the certificate SHA-256 its environment pins ({@code certSha256}). */
    private static final java.util.Map<String, String> PINS = new ConcurrentHashMap<>();

    static {
        CertificateTrust.setPrompt(LdapTrace::decide);
    }

    /** Trusted for this run: the server's environment says trustAll, or the certificate is the one it pins. */
    static CertificateTrust.Decision decide(CertificateTrust.Request req) {
        String key = req.host() + ":" + req.port();
        if (TRUSTED.contains(key) || com.pointblue.dirxml.dev.deploy.PinnedTrust.matches(PINS.get(key), req.certificate())) {
            return CertificateTrust.Decision.ONCE;
        }
        return CertificateTrust.Decision.REJECT;
    }

    private final String driver;
    private final Pattern grep;
    private final boolean engineToo;

    /** {@code driver} null = every driver; {@code grep} null = every message; {@code engineToo} adds the engine's own channel. */
    public LdapTrace(String driver, String grep, boolean engineToo) {
        this.driver = driver;
        this.grep = grep == null || grep.isBlank() ? null : Pattern.compile(grep);
        this.engineToo = engineToo;
    }

    /** The message's lines as printed, or an empty list when it is filtered out. */
    public List<String> accept(TraceRecord r) {
        if (r.eventType() == EdirEventConstant.EVT_DB_DIRXML && !engineToo) {
            return List.of();   // the engine's channel (DSTrace "DirXML"), not a driver's
        }
        if (driver != null && (r.driverName() == null ? !engineToo : !r.driverName().equalsIgnoreCase(driver))) {
            return List.of();
        }
        if (grep != null && !grep.matcher(r.text()).find()) {
            return List.of();
        }
        String text = r.text().endsWith("\n") ? r.text().substring(0, r.text().length() - 1) : r.text();
        return List.of(text.split("\n", -1));
    }

    /** The library's connection settings from an environment. */
    static ConnectionSettings settings(Environments.Environment env) {
        URI u = URI.create(env.url.contains("://") ? env.url : "ldaps://" + env.url);
        boolean ssl = !"ldap".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : (ssl ? 636 : 389);
        return new ConnectionSettings(u.getHost(), port, ssl, true, env.bindDn,
            env.password == null ? new char[0] : env.password.toCharArray(), "");
    }

    /** Stream for {@code seconds} (0 = until the thread is interrupted), each printed line to {@code sink}; the count of lines passed on. */
    public static long stream(Environments.Environment env, String driver, String grep, boolean engineToo, long seconds, Consumer<String> sink) throws Exception {
        LdapTrace filter = new LdapTrace(driver, grep, engineToo);
        ConnectionSettings settings = settings(env);
        if (env.trustAll) {
            TRUSTED.add(settings.host() + ":" + settings.port());
        }
        if (env.certSha256 != null) {
            PINS.put(settings.host() + ":" + settings.port(), env.certSha256);
        }
        TraceParser parser = new TraceParser();
        long[] count = {0};
        List<String> errors = new ArrayList<>();
        LdapTraceEventSource source = new LdapTraceEventSource(settings, env.name);
        try {
            source.start(ev -> {
                List<String> lines;
                synchronized (parser) {
                    TraceRecord r = parser.parse(ev);
                    lines = r == null ? List.of() : filter.accept(r);
                }
                synchronized (sink) {
                    for (String line : lines) {
                        count[0]++;
                        sink.accept(line);
                    }
                }
            }, e -> {
                synchronized (errors) {
                    errors.add(e.getMessage());
                }
            });
            if (seconds > 0) {
                Thread.sleep(seconds * 1000L);
            } else {
                Thread.currentThread().join();
            }
        } finally {
            source.close();
        }
        if (!errors.isEmpty()) {
            sink.accept("stream error: " + errors.get(0));
        }
        return count[0];
    }

    /** Collect for {@code seconds} and return the printed lines. */
    public static List<String> collect(Environments.Environment env, String driver, String grep, boolean engineToo, long seconds) throws Exception {
        List<String> out = new ArrayList<>();
        stream(env, driver, grep, engineToo, seconds, out::add);
        return out;
    }
}
