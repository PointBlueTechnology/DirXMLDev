package com.pointblue.dirxml.dev.forms;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.edit.FormOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.DriverSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code idm form.edit <tree> <form> [--driver D] [--env E] [--locale L] [--no-wait] [--check] [--dry-run] [--force] [--json]}
 *
 * <p>Opens a form from the tree in the vendor form builder (Option A of docs/forms.md): a
 * scratch copy of the document is handed to the builder exactly as Designer does; when the
 * builder exits, a saved document is stored through {@code form.set-content} (pretty-printed,
 * packaged forms baselined + marked customized, PRD bindings re-synced) and the result printed.
 * {@code --no-wait} launches the builder on the tree file itself and returns; run
 * {@code idm form.sync <tree> <form>} afterwards. {@code --check} only reports which builder
 * would be used and the one-time fixes it needs. {@code --env E} enables the builder's online
 * features against {@code E.formsUrl} from environments.properties.
 */
public final class FormEditCli {

    private FormEditCli() {
    }

    public static int run(String[] args) {
        if (args.length < 3 && !(args.length >= 2 && hasFlag(args, "--check"))) {
            System.err.println("usage: form.edit <tree> <form> [--driver D] [--env E] [--locale L] [--no-wait] [--check] [--dry-run] [--force] [--json]");
            return 2;
        }
        Path tree = Paths.get(args[1]);
        String ref = args.length >= 3 && !args[2].startsWith("--") ? args[2] : null;
        Map<String, String> opts = new HashMap<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--") && !k.equals("no-wait") && !k.equals("check")
                    && !k.equals("dry-run") && !k.equals("force") && !k.equals("json")) {
                    opts.put(k, args[++i]);
                } else {
                    opts.put(k, "true");
                }
            }
        }
        boolean json = opts.containsKey("json");

        Map<String, String> props = new HashMap<>();
        String fb = System.getProperty("formbuilder");
        if (fb != null) {
            props.put("formbuilder", fb);
        }
        FormBuilderLocator.Status builder = FormBuilderLocator.resolve(props);
        if (opts.containsKey("check")) {
            System.out.println(builder.describe());
            return builder.ready() ? 0 : 1;
        }
        if (ref == null) {
            System.err.println("form.edit: which form? (name, kind/name or driver/kind/name)");
            return 2;
        }
        if (!builder.ready()) {
            System.err.println(builder.describe());
            return 1;
        }

        try {
            DriverSet ds = AsCodeReader.read(tree);
            FormOps.Found found = FormOps.find(ds, ref, opts.get("driver"));
            if (found == null) {
                System.err.println("form.edit: form '" + ref + "' not found in " + tree
                    + (opts.containsKey("driver") ? " on driver '" + opts.get("driver") + "'" : " (or on several drivers — say --driver)"));
                return 1;
            }
            String locale = opts.getOrDefault("locale", defaultLocale());
            Path work = Files.createTempDirectory("idm-form-edit");
            Path service = null;
            if (opts.containsKey("env")) {
                Environments envs = Environments.load();
                String url = envs.property(opts.get("env"), "formsUrl");
                if (url == null) {
                    System.err.println("form.edit: environment '" + opts.get("env") + "' has no " + opts.get("env")
                        + ".formsUrl (the Identity Applications base URL, e.g. https://host/) in " + envs.file());
                    return 1;
                }
                service = work.resolve("ServiceRegistry.json");
                Files.writeString(service, FormBuilderRunner.serviceRegistry(url), StandardCharsets.UTF_8);
            }

            if (opts.containsKey("no-wait")) {
                Path treeFile = tree.resolve("drivers").resolve(AsCodeWriter.fileSafe(found.driver.name))
                    .resolve("provisioning").resolve("forms").resolve(found.form.kind.dir)
                    .resolve(AsCodeWriter.fileSafe(found.form.name) + ".form.json");
                if (!Files.isRegularFile(treeFile)) {
                    System.err.println("form.edit: cannot find the tree file for '" + found.form.name + "' (expected " + treeFile + "); run without --no-wait");
                    return 1;
                }
                FormBuilderRunner.Outcome o = FormBuilderRunner.run(builder, treeFile, locale, service, false);
                System.err.println("form builder started (pid " + o.pid + ") on " + treeFile);
                System.err.println("when you have saved and closed it, run: idm form.sync " + tree + " \"" + ref + "\""
                    + (opts.containsKey("driver") ? " --driver \"" + opts.get("driver") + "\"" : ""));
                return 0;
            }

            Path scratch = work.resolve(found.form.name + ".form");
            Files.writeString(scratch, found.form.json, StandardCharsets.UTF_8);
            System.err.println("form builder: " + builder.executable + " (" + builder.source + ")");
            System.err.println("editing '" + found.form.name + "' (" + found.form.kind.dir + ", driver '" + found.driver.name
                + "'); save in the builder, then close it to continue …");
            FormBuilderRunner.Outcome o = FormBuilderRunner.run(builder, scratch, locale, service, true);
            if (!o.changed()) {
                System.err.println("form builder closed without saving: nothing changed");
                return 0;
            }
            Transaction tx = Transaction.open(tree);
            Result r = tx.run(new FormOps.SetContent(opts.get("driver"), ref, o.afterText()),
                opts.containsKey("dry-run"), opts.containsKey("force"));
            System.out.println(json ? r.json() : r.text());
            return r.ok() ? 0 : 1;
        } catch (IOException | InterruptedException e) {
            System.err.println("form.edit: " + e.getMessage());
            return 1;
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (a.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    /** The builder expects Designer's {@code ll_CC} form; {@code en_US} maps to "en" inside the builder. */
    static String defaultLocale() {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage().isEmpty() ? "en" : l.getLanguage();
        String country = l.getCountry().isEmpty() ? (lang.equals("en") ? "US" : lang.toUpperCase(Locale.ROOT)) : l.getCountry();
        return lang + "_" + country;
    }
}
