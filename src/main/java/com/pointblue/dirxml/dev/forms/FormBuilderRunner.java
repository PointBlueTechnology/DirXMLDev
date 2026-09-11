package com.pointblue.dirxml.dev.forms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Runs the vendor form builder on one file, the way Designer does, and tells whether the file
 * changed. The builder loads the file on start and writes the JSON back to the same path on every
 * save (compact, {@code JSON.stringify(schema)}); it saves nothing on quit. Callers hand it a
 * scratch copy or the tree file itself and validate/sync afterwards.
 */
public final class FormBuilderRunner {

    /** What happened. */
    public static final class Outcome {
        public final List<String> command;
        public final Integer exitCode;     // null when not waited for
        public final byte[] before;
        public final byte[] after;         // null when not waited for
        public final long pid;

        Outcome(List<String> command, Integer exitCode, byte[] before, byte[] after, long pid) {
            this.command = command;
            this.exitCode = exitCode;
            this.before = before;
            this.after = after;
            this.pid = pid;
        }

        /** True when the builder wrote the file (bytes differ, whitespace included). */
        public boolean changed() {
            return after != null && !Arrays.equals(before, after);
        }

        public String afterText() {
            return after == null ? null : new String(after, StandardCharsets.UTF_8);
        }
    }

    private FormBuilderRunner() {
    }

    /**
     * Launch the builder on {@code formFile}. With {@code wait}, block until the builder exits and
     * report whether the file changed; otherwise return right after the launch.
     */
    public static Outcome run(FormBuilderLocator.Status builder, Path formFile, String locale, Path serviceRegistry, boolean wait)
            throws IOException, InterruptedException {
        Objects.requireNonNull(builder, "builder");
        if (!builder.ready()) {
            throw new IllegalStateException(builder.describe());
        }
        byte[] before = Files.readAllBytes(formFile);
        List<String> cmd = FormBuilderLocator.command(builder, formFile, locale, serviceRegistry);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        if (!wait) {
            return new Outcome(cmd, null, before, null, p.pid());
        }
        int rc = p.waitFor();
        byte[] after = Files.readAllBytes(formFile);
        return new Outcome(cmd, rc, before, after, p.pid());
    }

    /** A {@code ServiceRegistry.json} for online mode, pointing the builder at a workflow engine. */
    public static String serviceRegistry(String formsBackendUrl) {
        String url = formsBackendUrl.endsWith("/") ? formsBackendUrl.substring(0, formsBackendUrl.length() - 1) : formsBackendUrl;
        if (!url.endsWith("/WFHandler")) {
            url = url + "/WFHandler";
        }
        return "{\n  \"FormsBackendUrl\": \"" + url.replace("\"", "\\\"") + "\"\n}\n";
    }
}
