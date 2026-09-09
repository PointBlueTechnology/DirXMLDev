package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Vault;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.source.ExportWriter;
import com.pointblue.dirxml.sim.Case;
import com.pointblue.dirxml.sim.XmlCompare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code driver.submit}: run an XDS command through a running driver's
 * Subscriber channel ({@code SubmitCommand} — spike 5c: the document goes
 * through the channel and the shim's status comes back). As the DxCMD Phase 2
 * canary, the same command is run through the simulator from a tree, and the
 * document the live engine handed the shim (read from the driver's trace over
 * SSH) is compared with the simulator's final output: the engine's ground
 * truth against the harness's prediction, on one event.
 *
 * <p>{@code SubmitEvent} (the Publisher channel) is not offered: it delivered
 * nothing in spikes 1b and 5c.
 */
public final class Submit {

    public static final class Outcome {
        public String liveResult = "";      // the result document the engine returned (the shim's status)
        public String simulatorFinal;       // the simulator's final document, when a tree was given
        public String shimReceived;         // the document the live shim received, from the trace, when available
        public Boolean matches;             // shimReceived vs simulatorFinal (canonical), when both exist
        public String compareDetail = "";
        public final List<String> notes = new ArrayList<>();

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("live result:\n").append(indent(liveResult.isBlank() ? "(empty)" : liveResult)).append('\n');
            if (shimReceived != null) {
                sb.append("document the live shim received (from the trace):\n").append(indent(shimReceived)).append('\n');
            }
            if (simulatorFinal != null) {
                sb.append("simulator's final document:\n").append(indent(simulatorFinal)).append('\n');
            }
            if (matches != null) {
                sb.append("canary: ").append(matches ? "MATCH — the engine handed the shim what the simulator predicted"
                    : "MISMATCH — " + compareDetail).append('\n');
            }
            for (String n : notes) {
                sb.append("note: ").append(n).append('\n');
            }
            return sb.toString();
        }

        private static String indent(String s) {
            StringBuilder sb = new StringBuilder();
            for (String line : s.strip().split("\n")) {
                sb.append("    ").append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private Submit() {
    }

    /** Submit to the live driver; with {@code tree} also run the simulator; with SSH also read what the shim received. */
    public static Outcome run(Vault vault, Environments.Environment env, String driverName, String driverDn,
                              String xds, Path tree) throws IOException {
        Outcome o = new Outcome();
        int state = vault.driverState(driverDn);
        if (state != Vault.STATE_RUNNING) {
            throw new IOException("driver '" + driverName + "' is " + Vault.stateName(state) + "; SubmitCommand needs a running driver");
        }
        String traceFile = null;
        TraceTail tail = null;
        long traceSize = -1;
        if (env.sshHost != null) {
            Vault.Entry d = vault.read(driverDn);
            traceFile = d == null ? null : d.string(Vault.TRACE_FILE);
            String level = d == null ? null : d.string(Vault.TRACE_LEVEL);
            if (traceFile == null || traceFile.isBlank()) {
                o.notes.add("no DirXML-TraceFile on the driver; the shim's received document can't be read");
            } else if (level == null || level.isBlank() || Integer.parseInt(level.trim()) < 3) {
                o.notes.add("trace level is " + level + "; level 3 or higher is needed to see the submitted documents");
                traceFile = null;
            } else {
                tail = new TraceTail(env.sshUser, env.sshHost);
                traceSize = tail.size(traceFile);
            }
        }
        o.liveResult = vault.submitCommand(driverDn, xds.getBytes(StandardCharsets.UTF_8));
        if (tail != null && traceFile != null) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<String> lines = tail.since(traceFile, 2, null);
            o.shimReceived = shimReceived(lines);
            if (o.shimReceived == null) {
                o.notes.add("the trace grew " + (tail.size(traceFile) - traceSize) + " byte(s) but no 'Submitting document to subscriber shim' block was found");
            }
        }
        if (tree != null) {
            o.simulatorFinal = simulate(tree, driverName, xds);
            if (o.shimReceived != null) {
                XmlCompare.Diff diff = XmlCompare.compare(o.simulatorFinal, o.shimReceived);
                o.matches = diff.equal;
                o.compareDetail = diff.message == null ? "" : diff.message;
            } else {
                o.notes.add("no live shim document to compare the simulator's output with");
            }
        }
        return o;
    }

    /** The last document the trace shows being submitted to the subscriber shim, or null. */
    static String shimReceived(List<String> lines) {
        String doc = null;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Submitting document to subscriber shim")) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < lines.size(); j++) {
                    String l = lines.get(j);
                    if (TraceTail.timestamp(l) != null) {
                        // the document is logged as a bare-header line followed by the XML; the XML
                        // lines carry no timestamp; the next timestamped line ends it
                        if (sb.length() > 0) {
                            break;
                        }
                        continue;
                    }
                    sb.append(l).append('\n');
                }
                if (sb.length() > 0) {
                    doc = sb.toString().strip();
                }
            }
        }
        return doc;
    }

    /** The simulator's final subscriber-channel document for {@code xds}, from the tree's driver. */
    static String simulate(Path tree, String driverName, String xds) throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        if (ds.driver(driverName) == null) {
            throw new IOException("the tree has no driver '" + driverName + "'");
        }
        Path dir = Files.createTempDirectory("idm-submit");
        Path export = dir.resolve("driver.xml");
        ExportWriter.writeDriver(ds, driverName, export);
        Path caseDir = dir.resolve("case");
        Files.createDirectories(caseDir);
        Files.writeString(caseDir.resolve("input.xds"), xds, StandardCharsets.UTF_8);
        String slashDn = "\\[root]\\" + ds.name + "\\" + driverName;
        Files.writeString(caseDir.resolve("case.properties"),
            "export=" + export.toAbsolutePath().toString().replace("\\", "\\\\") + "\nchannel=subscriber\nfromNDS=true\n"
                + "driverDN=" + slashDn.replace("\\", "\\\\") + "\ntraceLevel=3\n", StandardCharsets.UTF_8);
        return Case.load(caseDir).run().finalXds;
    }
}
