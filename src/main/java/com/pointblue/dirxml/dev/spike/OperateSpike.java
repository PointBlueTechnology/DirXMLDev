package com.pointblue.dirxml.dev.spike;

import com.pointblue.dirxml.dev.deploy.Vault;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Phase 5 spikes on the TEST vault's side-effect-free Querytest driver:
 * <ul>
 *   <li><b>a</b> (driver stopped): engine version, JVM and driver stats, cache view,
 *       QueueEvent of a scratch event, then DeleteCacheEntries parameter forms until
 *       the cache is empty (5a);</li>
 *   <li><b>b</b> (starts the driver, stops it after): is a DirXML-TraceLevel change
 *       honoured on a running driver (5b); does SubmitEvent / SubmitCommand run the
 *       document through the channels — watched in the driver's trace over SSH (5c).</li>
 * </ul>
 * -Dspike.url/bindDn/password/driverSetDn/driver [-Dspike.phase=a|b] [-Dspike.ssh=root@host]
 */
public final class OperateSpike {

    private static final String EVENT =
        "<nds dtdversion=\"4.0\"><input>"
            + "<modify class-name=\"User\" src-dn=\"\\\\dirxmldev\\\\spike\\\\%s\" event-id=\"%s\">"
            + "<association>%s</association>"
            + "<modify-attr attr-name=\"Description\"><add-value><value>%s</value></add-value></modify-attr>"
            + "</modify></input></nds>";

    public static void main(String[] args) throws Exception {
        Vault.Config c = new Vault.Config();
        c.url = need("spike.url");
        c.bindDn = need("spike.bindDn");
        c.password = need("spike.password");
        String dsDn = need("spike.driverSetDn");
        String driverDn = need("spike.driver");
        String phase = System.getProperty("spike.phase", "a");
        String ssh = System.getProperty("spike.ssh");

        try (Vault v = Vault.connect(c)) {
            if (phase.equals("a")) {
                phaseA(v, driverDn);
            } else {
                phaseB(v, driverDn, ssh);
            }
        }
    }

    private static void phaseA(Vault v, String driverDn) throws Exception {
        System.out.println("== engine");
        int packed = v.engineVersion();
        int[] parsed;
        try {
            parsed = com.novell.nds.dirxml.util.DxConst.parseDirXMLVersion(packed);
            System.out.println("   GetVersion packed=" + packed + " parsed=" + java.util.Arrays.toString(parsed));
        } catch (Throwable t) {
            System.out.println("   GetVersion packed=" + packed + " (parse failed: " + t + ")");
        }
        for (int[] ab : new int[][] {{0, 0}, {1, 0}, {0, 1}}) {
            try {
                String s = v.jvmStats(ab[0], ab[1]);
                System.out.println("   GetJvmStats(" + ab[0] + "," + ab[1] + ") -> " + (s.isEmpty() ? "(empty)" : abbrev(s, 600)));
                if (!s.isEmpty()) {
                    break;
                }
            } catch (Exception e) {
                System.out.println("   GetJvmStats(" + ab[0] + "," + ab[1] + ") FAILED: " + e.getMessage());
            }
        }
        for (int a : new int[] {0, 1}) {
            try {
                String s = v.driverStats(driverDn, a);
                System.out.println("   GetDriverStats(" + a + ") -> " + (s.isEmpty() ? "(empty)" : abbrev(s, 800)));
                if (!s.isEmpty()) {
                    break;
                }
            } catch (Exception e) {
                System.out.println("   GetDriverStats(" + a + ") FAILED: " + e.getMessage());
            }
        }

        System.out.println("== 5a cache (driver " + Vault.stateName(v.driverState(driverDn)) + ")");
        Vault.CachePage p0 = v.viewCache(driverDn, 0, 50);
        System.out.println("   cache before: " + (p0.empty ? "empty" : count(p0.xds) + " event(s)") + " nextToken=" + p0.nextToken);
        if (!p0.empty) {
            System.out.println("   cache is not empty — not touching it; clean it manually first");
            return;
        }
        String stamp = String.valueOf(System.currentTimeMillis());
        for (int i = 1; i <= 2; i++) {
            v.queueEvent(driverDn, String.format(EVENT, stamp + "-" + i, "dirxmldev-5a-" + stamp + "-" + i, "assoc-" + i, "v" + i).getBytes(StandardCharsets.UTF_8));
        }
        Vault.CachePage p1 = v.viewCache(driverDn, 0, 50);
        System.out.println("   after QueueEvent x2: " + (p1.empty ? "EMPTY (queue did not land)" : count(p1.xds) + " event(s)") + " nextToken=" + p1.nextToken);
        if (!p1.empty) {
            System.out.println("   first page: " + abbrev(p1.xds, 400));
        }
        // parameter forms for DeleteCacheEntries, least specific first
        int[][] forms = {{0, 0}, {0, 1}, {0, 2}, {p1.nextToken, 0}, {0, 100}};
        boolean cleared = false;
        for (int[] f : forms) {
            if (cleared) {
                break;
            }
            for (String s : new String[] {"", null}) {
                try {
                    v.deleteCacheEntries(driverDn, f[0], f[1], s == null ? "" : s, 0);
                    Vault.CachePage after = v.viewCache(driverDn, 0, 50);
                    int n = after.empty ? 0 : count(after.xds);
                    System.out.println("   DeleteCacheEntries(" + f[0] + ", " + f[1] + ", \"" + (s == null ? "" : s) + "\", 0) -> ok; cache now " + n + " event(s)");
                    if (n == 0) {
                        cleared = true;
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("   DeleteCacheEntries(" + f[0] + ", " + f[1] + ", \"" + s + "\", 0) FAILED: " + e.getMessage());
                }
            }
        }
        if (!cleared) {
            System.out.println("   *** cache NOT cleared — " + count(v.viewCache(driverDn, 0, 50).xds) + " scratch event(s) remain on " + driverDn);
        }
    }

    private static void phaseB(Vault v, String driverDn, String ssh) throws Exception {
        Vault.Entry d = v.read(driverDn);
        String level0 = d.string(Vault.TRACE_LEVEL);
        String file0 = d.string(Vault.TRACE_FILE);
        System.out.println("== 5b/5c on " + driverDn + " (trace level=" + level0 + " file=" + file0 + ", state " + Vault.stateName(v.driverState(driverDn)) + ")");
        String traceFile = "/opt/novell/dirxmldev-5b.trace";
        boolean started = false;
        try {
            v.replace(driverDn, Vault.TRACE_LEVEL, "3");
            v.replace(driverDn, Vault.TRACE_FILE, traceFile);
            v.startDriver(driverDn);
            System.out.println("   start: " + v.waitForState(driverDn, Vault.STATE_RUNNING, 120));
            started = true;
            Thread.sleep(3000);
            long lines3 = traceLines(ssh, traceFile);
            System.out.println("   trace lines at level 3 after start: " + lines3);

            // 5b: raise the level live; does the file grow with level-5 detail without a restart?
            v.replace(driverDn, Vault.TRACE_LEVEL, "5");
            Thread.sleep(2000);
            // provoke engine activity: a query through the publisher isn't possible; use SubmitCommand below
            // 5c: submit a command (subscriber) and an event (publisher), then look for them in the trace
            String stamp = String.valueOf(System.currentTimeMillis());
            String cmd = String.format(EVENT, "5c-cmd-" + stamp, "dirxmldev-5c-cmd-" + stamp, "assoc-cmd", "cmd");
            String evt = String.format(EVENT, "5c-evt-" + stamp, "dirxmldev-5c-evt-" + stamp, "assoc-evt", "evt");
            try {
                String r1 = v.submitCommand(driverDn, cmd.getBytes(StandardCharsets.UTF_8));
                System.out.println("   SubmitCommand -> " + (r1.isEmpty() ? "(empty)" : abbrev(r1, 500)));
            } catch (Exception e) {
                System.out.println("   SubmitCommand FAILED: " + e.getMessage());
            }
            try {
                String r2 = v.submitEvent(driverDn, evt.getBytes(StandardCharsets.UTF_8));
                System.out.println("   SubmitEvent -> " + (r2.isEmpty() ? "(empty)" : abbrev(r2, 500)));
            } catch (Exception e) {
                System.out.println("   SubmitEvent FAILED: " + e.getMessage());
            }
            Thread.sleep(4000);
            long lines5 = traceLines(ssh, traceFile);
            System.out.println("   trace lines after level 5 + submits: " + lines5 + " (grew by " + (lines5 - lines3) + ")");
            System.out.println("   trace mentions 5c-cmd: " + traceGrep(ssh, traceFile, "dirxmldev-5c-cmd-" + stamp));
            System.out.println("   trace mentions 5c-evt: " + traceGrep(ssh, traceFile, "dirxmldev-5c-evt-" + stamp));
            System.out.println("   trace tail:\n" + traceTail(ssh, traceFile, 25));
            Vault.CachePage cache = v.viewCache(driverDn, 0, 10);
            System.out.println("   cache after submits: " + (cache.empty ? "empty" : count(cache.xds) + " event(s)"));
        } finally {
            if (started) {
                try {
                    v.stopDriver(driverDn);
                    System.out.println("   stop: " + v.waitForState(driverDn, Vault.STATE_STOPPED, 200));
                } catch (Exception e) {
                    System.out.println("   stop: " + e.getMessage());
                }
            }
            v.replace(driverDn, Vault.TRACE_LEVEL, level0 == null ? List.of() : Vault.value(level0));
            v.replace(driverDn, Vault.TRACE_FILE, file0 == null ? List.of() : Vault.value(file0));
            System.out.println("   trace settings restored (level=" + level0 + " file=" + file0 + ")");
        }
    }

    private static int count(String xds) {
        int n = 0;
        int i = 0;
        while ((i = xds.indexOf("<modify ", i)) >= 0) {
            n++;
            i++;
        }
        i = 0;
        while ((i = xds.indexOf("<add ", i)) >= 0) {
            n++;
            i++;
        }
        return n;
    }

    private static long traceLines(String ssh, String file) throws Exception {
        if (ssh == null) {
            return -1;
        }
        String out = run("ssh", ssh, "wc -l < " + file + " 2>/dev/null || echo 0");
        long n = -1;   // the last integer in the output: ssh banners may precede it
        for (String tok : out.strip().split("\\s+")) {
            if (tok.matches("\\d+")) {
                n = Long.parseLong(tok);
            }
        }
        return n;
    }

    private static String traceGrep(String ssh, String file, String needle) throws Exception {
        if (ssh == null) {
            return "(no ssh)";
        }
        String out = run("ssh", ssh, "grep -c -- '" + needle + "' " + file + " 2>/dev/null || true");
        return out.strip() + " line(s)";
    }

    private static String traceTail(String ssh, String file, int n) throws Exception {
        if (ssh == null) {
            return "(no ssh)";
        }
        return run("ssh", ssh, "tail -n " + n + " " + file + " 2>/dev/null | cut -c1-160");
    }

    private static String run(String... cmd) throws Exception {
        // stdout only: ssh writes its banners/warnings to stderr
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    private static String abbrev(String s, int n) {
        String t = s.replace("\n", " ").replaceAll("\\s+", " ");
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    private static String need(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing -D" + prop);
        }
        return v;
    }
}
