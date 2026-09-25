package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.sim.EdirTraceStream;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code driver.trace tail} over LDAP: one driver's lines out of the engine's DirXML debug
 * events ({@link EdirTraceStream}, the simulator), so no SSH host, no trace file and no
 * {@code DirXML-TraceFile} are needed — only the environment's LDAPS connection. A continuation
 * line (a stack trace's next line, an XML document's) carries no driver prefix and is attributed
 * to the last driver line seen, the way it reads in a trace file.
 */
public final class LdapTrace {

    private final String driver;
    private final Pattern grep;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");
    private String lastDriver;

    /** {@code driver} null = every driver; {@code grep} null = every line. */
    public LdapTrace(String driver, String grep) {
        this.driver = driver;
        this.grep = grep == null || grep.isBlank() ? null : Pattern.compile(grep);
    }

    /** The line as printed ({@code [HH:mm:ss.mmm] [ST: ]text}, the driver named only when every driver is shown), or null when filtered out. */
    public synchronized String accept(EdirTraceStream.Line line) {
        String owner = line.driver != null ? line.driver : lastDriver;
        if (line.driver != null) {
            lastDriver = line.driver;
        }
        if (driver != null && (owner == null || !owner.equalsIgnoreCase(driver))) {
            return null;
        }
        if (grep != null && !grep.matcher(line.text).find()) {
            return null;
        }
        String stamp = "[" + time.format(new Date(line.receivedAt)) + "." + String.format("%03d", line.millis) + "] ";
        if (driver == null) {
            return stamp + line.format();
        }
        return stamp + (line.driver == null ? line.text : (line.channel == null ? "" : line.channel + ": ") + line.text);
    }

    /** The stream's connection from an environment: its URL, bind identity, password and trust setting. */
    public static EdirTraceStream.Config config(Environments.Environment env) {
        EdirTraceStream.Config c = EdirTraceStream.Config.fromUrl(env.url, env.bindDn, env.password);
        c.trustAllCerts = env.trustAll;
        return c;
    }

    /** Stream for {@code seconds} (0 = until the thread is interrupted), each printed line to {@code sink}; the count of lines passed on. */
    public static long stream(Environments.Environment env, String driver, String grep, boolean engineToo, long seconds, Consumer<String> sink) throws InterruptedException {
        LdapTrace filter = new LdapTrace(driver, grep);
        long[] count = {0};
        try (EdirTraceStream stream = new EdirTraceStream(config(env))) {
            stream.start(engineToo, line -> {
                String printed = filter.accept(line);
                if (printed != null) {
                    count[0]++;
                    sink.accept(printed);
                }
            });
            if (seconds > 0) {
                Thread.sleep(seconds * 1000L);
            } else {
                Thread.currentThread().join();
            }
            List<Throwable> errors = stream.errors();
            if (!errors.isEmpty()) {
                sink.accept("stream error: " + errors.get(0).getMessage());
            }
        }
        return count[0];
    }

    /** Collect for {@code seconds} and return the printed lines. */
    public static List<String> collect(Environments.Environment env, String driver, String grep, boolean engineToo, long seconds) throws InterruptedException {
        List<String> out = new ArrayList<>();
        stream(env, driver, grep, engineToo, seconds, out::add);
        return out;
    }
}
