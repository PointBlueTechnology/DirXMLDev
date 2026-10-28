package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.Args;
import com.pointblue.dirxml.dev.deploy.Environments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 *   viewer.check                                   where the DirXML Trace Viewer is, or how to get it
 *   viewer.install [--version TAG] [--source] [--dir DIR]   download the latest release (or TAG) from GitHub into
 *                                                  ~/.idm/trace-viewer, or --source: clone the repository and build it
 *   driver.trace view --env E [--driver D]         open the viewer connected to the environment's vault, D selected
 *   driver.trace view --file FILE                  open the viewer on a trace file
 * </pre>
 */
public final class ViewerCli {

    private ViewerCli() {
    }

    /** What each command takes (docs/commands.md). */
    static final Map<String, Args.Spec> SPECS = Map.of(
        "viewer.check", Args.Spec.of("", ""),
        "viewer.install", Args.Spec.of("source", "version dir"));

    static final String USAGE = "usage: viewer.check | viewer.install [--version TAG] [--source] [--dir DIR]";

    public static int run(String[] argv) throws Exception {
        String cmd = argv[0];
        Args.Spec spec = SPECS.get(cmd);
        if (spec == null) {
            System.err.println(USAGE);
            return 2;
        }
        Args opts;
        try {
            opts = Args.parse(cmd, argv, 1, spec);
            if (!opts.positional.isEmpty()) {
                throw new Args.UsageError(cmd + " takes no positional argument, not '" + opts.positional.get(0) + "'");
            }
        } catch (Args.UsageError e) {
            System.err.println(e.getMessage());
            return 2;
        }
        switch (cmd) {
            case "viewer.check": {
                TraceViewer.Status st = TraceViewer.locate();
                System.out.println(st.describe());
                return st.ready() ? 0 : 1;
            }
            case "viewer.install": {
                Path dir = opts.has("dir") ? Paths.get(opts.first("dir")) : TraceViewer.defaultDir();
                String tag = opts.first("version");
                List<String> notes = new ArrayList<>();
                TraceViewer.Installed done = opts.has("source")
                    ? TraceViewer.installFromSource(dir, tag, TraceViewer.REPO_URL + ".git", notes::add)
                    : TraceViewer.installRelease(dir, tag, "https://api.github.com/repos/" + TraceViewer.REPO, notes::add);
                for (String n : notes) {
                    System.out.println("  " + n);
                }
                System.out.println("installed " + done.jar + " (" + done.how + ")");
                if (!dir.equals(TraceViewer.defaultDir())) {
                    System.out.println("  set " + TraceViewer.ENV + "=" + done.jar + " so driver.trace view finds it");
                }
                return 0;
            }
            default:
                System.err.println(USAGE);
                return 2;
        }
    }

    /** {@code driver.trace view}: opened before the operate CLI opens a vault connection, since the viewer makes its own. */
    static int view(Map<String, List<String>> opts, boolean json) throws Exception {
        TraceViewer.Status st = TraceViewer.locate();
        if (!st.ready()) {
            System.err.println(st.describe());
            return 1;
        }
        String file = first(opts, "file");
        List<String> args;
        String password = null;
        String what;
        if (file != null && !file.isBlank()) {
            Path f = Paths.get(file);
            if (!Files.isRegularFile(f)) {
                System.err.println("driver.trace view: no such file " + f);
                return 2;
            }
            args = TraceViewer.openArgs(f);
            what = "file " + f.toAbsolutePath();
        } else {
            String envName = first(opts, "env");
            if (envName == null) {
                System.err.println("usage: driver.trace view --env E [--driver D]   |   driver.trace view --file FILE");
                return 2;
            }
            Environments.Environment env = Environments.load().get(envName);
            String driver = first(opts, "driver");
            args = TraceViewer.connectArgs(env, driver);
            password = env.password;
            what = env.url + (driver == null ? "" : ", driver '" + driver + "'");
        }
        long pid = TraceViewer.launch(st.path, args, password);
        if (json) {
            System.out.println("{\"viewer\":" + com.pointblue.dirxml.dev.deploy.DeployLog.q(st.path.toString())
                + ",\"pid\":" + pid + ",\"target\":" + com.pointblue.dirxml.dev.deploy.DeployLog.q(what) + "}");
        } else {
            System.out.println("trace viewer started (pid " + pid + ") on " + what + "; it stays open after this command returns"
                + (password != null ? ". An untrusted server certificate is shown in the viewer for you to accept." : ""));
        }
        return 0;
    }

    private static String first(Map<String, List<String>> opts, String key) {
        List<String> v = opts.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
