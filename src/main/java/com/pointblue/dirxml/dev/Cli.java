package com.pointblue.dirxml.dev;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.source.ExportReader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point:
 * <pre>
 *   import &lt;export.xml&gt; &lt;outDir&gt;   read a driver / driver-set export, write IDM-as-code
 *   check  &lt;asCodeDir&gt;             load an as-code tree, report it, exit 1 on broken links
 *   validate &lt;asCodeDir&gt; [--json]  run every validation check, exit 1 on any error
 *   version [--check]                the running version, and the latest GitHub release
 *   doctor [--json] [--env NAME]     JDK 21, simulator, lib jars, environments (no secrets)
 * </pre>
 */
public final class Cli {

    /** What each of Cli's own commands takes (docs/commands.md); the others are read by their CLIs (#104, #105). */
    static final java.util.Map<String, Args.Spec> SPECS = java.util.Map.ofEntries(
        java.util.Map.entry("version", Args.Spec.of("check", "")),
        java.util.Map.entry("import", Args.Spec.of("", "")),
        java.util.Map.entry("import-project", Args.Spec.of("", "")),
        java.util.Map.entry("import-ldif", Args.Spec.of("", "")),
        java.util.Map.entry("import-live", Args.Spec.of("mark-new-drivers", "env")),
        java.util.Map.entry("check", Args.Spec.of("", "")),
        java.util.Map.entry("simulate", Args.Spec.of("", "cases against env")),
        java.util.Map.entry("export", Args.Spec.of("", "")),
        java.util.Map.entry("export-project", Args.Spec.of("dry-run new",
            "vault-name vault-host vault-user server server-context catalog designer")),
        java.util.Map.entry("mapping-table.export-csv", Args.Spec.of("", "")),
        java.util.Map.entry("refs", Args.Spec.of("", "")),
        java.util.Map.entry("validate", Args.Spec.of("", "")),
        java.util.Map.entry("tree.diff", Args.Spec.of("", "")),
        java.util.Map.entry("docs", Args.Spec.of("", "out driver since format")));

    /** A command's body, which may refuse its command line. */
    interface Body {
        int run() throws Exception;
    }

    /** Runs {@code body}; a usage error prints its message and is exit 2. */
    static int usageErrors(Body body) throws Exception {
        try {
            return body.run();
        } catch (Args.UsageError e) {
            System.err.println(e.getMessage());
            return 2;
        }
    }

    /** {@code args} read against {@code cmd}'s spec, with between {@code min} and {@code max} positionals ({@code usage} otherwise). */
    static Args parse(String[] args, int min, int max, String usage) throws Args.UsageError {
        Args a = Args.parse(args[0], args, 1, SPECS.get(args[0]));
        if (a.positional.size() < min) {
            throw new Args.UsageError(usage);
        }
        if (a.positional.size() > max) {
            throw new Args.UsageError(args[0] + ": unexpected argument '" + a.positional.get(max) + "'; " + usage);
        }
        return a;
    }

    /** {@code version [--check]}: the running version; with {@code --check}, the latest GitHub release beside it. */
    static int version(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 0, 0, "usage: version [--check]");
            String current = Version.current();
            System.out.println("DirXMLDev " + (current == null ? "(version unknown: built without resources)" : current));
            if (a.has("check")) {
                ReleaseCheck.Latest latest = ReleaseCheck.latest(ReleaseCheck.LATEST_URL, System.getenv());
                if (latest == null) {
                    System.out.println("latest release: unknown (GitHub not reachable within 2 s)");
                    return 1;
                }
                String n = ReleaseCheck.notice(current, latest.version, latest.url);
                System.out.println(n == null ? "latest release: " + latest.version + (current == null ? "" : " — you are current") : n);
            }
            return 0;
        });
    }

    public static void main(String[] args) {
        LegacyTls.enable();   // first thing: the JDK reads its TLS settings once
        ReleaseCheck.schedule(args, System.getenv(), System.console() != null, Version.current(), ReleaseCheck.cacheFile());
        try {
            if (args.length >= 1 && args[0].equals("version")) {
                System.exit(version(args));
            }
            if (args.length >= 3 && args[0].equals("import")) {
                System.exit(importExport(args));
            }
            if (args.length >= 3 && args[0].equals("import-project")) {
                System.exit(importProject(args));
            }
            if (args.length >= 3 && args[0].equals("import-ldif")) {
                System.exit(importLdif(args));
            }
            if (args.length >= 2 && args[0].equals("import-live")) {
                System.exit(importLive(args));
            }
            if (args.length >= 2 && args[0].equals("check")) {
                System.exit(check(args));
            }
            if (args.length >= 1 && com.pointblue.dirxml.dev.edit.EditCli.isOperation(args[0])) {
                System.exit(com.pointblue.dirxml.dev.edit.EditCli.run(args));
            }
            if (args.length >= 2 && args[0].equals("simulate")) {
                System.exit(simulate(args));
            }
            if (args.length >= 3 && args[0].equals("export")) {
                System.exit(export(args));
            }
            if (args.length >= 3 && args[0].equals("export-project")) {
                System.exit(exportProject(args));
            }
            if (args.length >= 1 && args[0].equals("show")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.show(args));
            }
            if (args.length >= 1 && args[0].equals("query")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.query(args));
            }
            if (args.length >= 1 && (args[0].equals("events.case") || args[0].equals("events.describe"))) {
                System.exit(com.pointblue.dirxml.dev.events.EventsCli.command(args));
            }
            if (args.length >= 1 && args[0].equals("form.list")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.formList(args));
            }
            if (args.length >= 1 && args[0].equals("form.show")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.formShow(args));
            }
            if (args.length >= 1 && args[0].equals("prd.list")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.prdList(args));
            }
            if (args.length >= 1 && args[0].equals("prd.show")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.prdShow(args));
            }
            if (args.length >= 1 && args[0].equals("appconfig.list")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.appConfigList(args));
            }
            if (args.length >= 1 && args[0].equals("category.list")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.categoryList(args));
            }
            if (args.length >= 1 && args[0].equals("appconfig.show")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.appConfigShow(args));
            }
            if (args.length >= 1 && args[0].equals("entitlement.list")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.entitlementList(args));
            }
            if (args.length >= 1 && args[0].equals("entitlement.show")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.entitlementShow(args));
            }
            if (args.length >= 1 && args[0].equals("prd.flow")) {
                System.exit(com.pointblue.dirxml.dev.flow.FlowView.run(args));
            }
            if (args.length >= 1 && args[0].equals("form.edit")) {
                System.exit(com.pointblue.dirxml.dev.forms.FormEditCli.run(args));
            }
            if (args.length >= 1 && args[0].equals("form.preview")) {
                System.exit(com.pointblue.dirxml.dev.forms.FormPreview.run(args));
            }
            if (args.length >= 1 && args[0].equals("package.diff") && !hasCatalogFlag(args)) {
                // package.diff <tree> <artifactPath>: a customized packaged artifact vs its baseline in a tree.
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.packageDiff(args));
            }
            if (args.length >= 3 && args[0].equals("mapping-table.export-csv")) {
                System.exit(mappingTableExportCsv(args));
            }
            if (args.length >= 3 && args[0].equals("refs")) {
                System.exit(refs(args));
            }
            if (args.length >= 1 && args[0].equals("doctor")) {
                System.exit(Doctor.run(args));
            }
            if (args.length >= 2 && args[0].equals("validate")) {
                System.exit(validate(args));
            }
            if (args.length >= 2 && !args[0].equals("vault.email-server") && (args[0].startsWith("vault.") || args[0].equals("schema.diff"))) {
                System.exit(com.pointblue.dirxml.dev.deploy.DeployCli.run(args));
            }
            if (args.length >= 2 && args[0].equals("package.status")) {
                System.exit(com.pointblue.dirxml.dev.packages.PackageStatus.cli(args));
            }
            if (args.length >= 1 && args[0].startsWith("package.")) {
                // package.diff without --catalog is handled above (a tree/artifact diff); everything else,
                // and package.diff --catalog …, is the catalog's own command set.
                System.exit(com.pointblue.dirxml.dev.packages.PackageCli.run(args));
            }
            if (args.length >= 1 && args[0].startsWith("viewer.")) {
                System.exit(com.pointblue.dirxml.dev.operate.ViewerCli.run(args));
            }
            if (args.length >= 1 && (args[0].startsWith("driver.") || args[0].startsWith("driverset.") || args[0].startsWith("engine.") || args[0].startsWith("job.") || args[0].startsWith("rbe.") || args[0].startsWith("workorder.") || args[0].equals("object.inspect") || args[0].equals("vault.email-server") || args[0].equals("trace.view"))) {
                System.exit(com.pointblue.dirxml.dev.operate.OperateCli.run(args));
            }
            if (args.length >= 1 && com.pointblue.dirxml.dev.promote.PromoteCli.handles(args[0])) {
                System.exit(com.pointblue.dirxml.dev.promote.PromoteCli.run(args));
            }
            if (args.length >= 3 && args[0].equals("tree.diff")) {
                System.exit(treeDiff(args));
            }
            if (args.length >= 2 && args[0].equals("docs")) {
                System.exit(docs(args));
            }
            usage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(3);
        }
    }

    /** {@code import <export.xml> <outDir>}. */
    static int importExport(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 2, 2, "usage: import <export.xml> <outDir>");
            return doImport(Paths.get(a.positional.get(0)), Paths.get(a.positional.get(1)));
        });
    }

    /** {@code import-project <projectDir> <outDir>}. */
    static int importProject(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 2, 2, "usage: import-project <projectDir> <outDir>");
            return write(com.pointblue.dirxml.dev.source.ProjectReader.read(Paths.get(a.positional.get(0))), Paths.get(a.positional.get(1)));
        });
    }

    /** {@code import-ldif <dump.ldif> <outDir>}. */
    static int importLdif(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 2, 2, "usage: import-ldif <dump.ldif> <outDir>");
            return write(com.pointblue.dirxml.dev.source.LdifReader.read(Paths.get(a.positional.get(0))), Paths.get(a.positional.get(1)));
        });
    }

    private static final String IMPORT_LIVE_USAGE = "usage: import-live [<driverSetDN>] <outDir> --env NAME [--mark-new-drivers]   |   import-live <driverSetDN> <outDir> (IDM_JAVA_OPTS=-Dldap.url/.bindDn/.password)";

    /**
     * {@code import-live [<driverSetDN>] <outDir> --env NAME} (environments.properties: url, bindDn, password in any
     * form, driverSet), or {@code import-live <driverSetDN> <outDir>} (connection from -Dldap.url/-Dldap.bindDn/-Dldap.password).
     */
    static int importLive(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 1, 2, IMPORT_LIVE_USAGE);
            List<String> positional = a.positional;
            String envName = a.first("env");
            boolean markNewDrivers = a.has("mark-new-drivers");
            if (envName == null && positional.size() < 2) {
                throw new Args.UsageError(IMPORT_LIVE_USAGE);
            }
            com.pointblue.dirxml.dev.deploy.Vault.Config c;
            String driverSetDn;
            if (envName != null) {
                com.pointblue.dirxml.dev.deploy.Environments.Environment env =
                    com.pointblue.dirxml.dev.deploy.Environments.load().get(envName);
                c = env.vaultConfig();
                driverSetDn = positional.size() >= 2 ? positional.get(0) : env.driverSetDn;
            } else {
                c = new com.pointblue.dirxml.dev.deploy.Vault.Config();
                c.url = need("ldap.url");
                c.bindDn = need("ldap.bindDn");
                c.password = need("ldap.password");
                c.trustAll = "true".equals(System.getProperty("ldap.trustAll"));
                driverSetDn = positional.get(0);
            }
            Path outDir = Paths.get(positional.get(positional.size() - 1));
            // every attribute (package stamps included) — the same reader vault.diff / deploy use;
            // with --env, the other servers' own driver settings too (docs/vault-deploy.md, "Several servers")
            com.pointblue.dirxml.dev.model.DriverSet live = envName != null && driverSetDn.equals(
                com.pointblue.dirxml.dev.deploy.Environments.load().get(envName).driverSetDn)
                ? com.pointblue.dirxml.dev.deploy.VaultDiff.readLive(com.pointblue.dirxml.dev.deploy.Environments.load().get(envName), n -> System.err.println("note: " + n))
                : com.pointblue.dirxml.dev.deploy.VaultDiff.readLive(c, driverSetDn);
            // values that differ per stage (docs/vault-deploy.md): the environment's file takes the vault's
            // value, the tree's files keep the base value the existing tree holds
            // a shim auth id the environment's secrets file supplies never lands in the tree
            if (envName != null) {
                java.nio.file.Path secretsFile = com.pointblue.dirxml.dev.deploy.Environments.load().get(envName).secretsFile;
                if (secretsFile != null) {
                    for (String d : com.pointblue.dirxml.dev.deploy.Overrides.stripSecretShimAuthIds(live, com.pointblue.dirxml.dev.deploy.Secrets.load(secretsFile))) {
                        System.err.println("secret: '" + d + "'.shim-auth-id kept out of the tree (the secrets file supplies it)");
                    }
                }
            }
            // drivers named differently in this vault take the tree's names; the tree's drivers this environment
            // doesn't get stay; a driver new to the tree is marked as this environment's only with --mark-new-drivers
            com.pointblue.dirxml.dev.model.DriverSet base = envName != null && java.nio.file.Files.isRegularFile(outDir.resolve(com.pointblue.dirxml.dev.ascode.AsCodeWriter.DRIVERSET_MANIFEST))
                ? com.pointblue.dirxml.dev.ascode.AsCodeReader.read(outDir) : null;
            if (base != null) {
                for (String n : com.pointblue.dirxml.dev.deploy.Overrides.toTreeNames(live, base.overrides.get(envName))) {
                    System.err.println("note: " + n);
                }
                for (String n : com.pointblue.dirxml.dev.deploy.ServerMap.toTree(live, base.overrides.get(envName), base)) {
                    System.err.println("note: " + n);
                }
                for (String n : com.pointblue.dirxml.dev.deploy.DriverEnvironments.keepOnImport(live, envName, base, markNewDrivers, null)) {
                    System.err.println("note: " + n);
                }
            }
            if (envName != null && java.nio.file.Files.isDirectory(outDir.resolve(com.pointblue.dirxml.dev.deploy.Overrides.DIR))) {
                live.overrides.putAll(com.pointblue.dirxml.dev.deploy.Overrides.read(outDir));
                for (String n : com.pointblue.dirxml.dev.deploy.Overrides.foldBack(live, envName, base)) {
                    System.err.println("override: " + n);
                }
            }
            return write(live, outDir);
        });
    }

    /** {@code check <asCodeDir>}. */
    static int check(String[] args) throws Exception {
        return usageErrors(() -> doCheck(Paths.get(parse(args, 1, 1, "usage: check <asCodeDir>").positional.get(0))));
    }

    /** {@code simulate <asCodeDir> --cases <casesDir> [--against <asCodeDir>] [--env E] [--json]}. */
    static int simulate(String[] args) throws Exception {
        return usageErrors(() -> {
            String usage = "usage: simulate <asCodeDir> --cases <casesDir> [--against <asCodeDir>] [--env E] [--json]";
            Args a = parse(args, 1, 1, usage);
            if (a.first("cases") == null) {
                throw new Args.UsageError(usage);
            }
            Path tree = Paths.get(a.positional.get(0));
            Path cases = Paths.get(a.first("cases"));
            Path against = a.first("against") == null ? null : Paths.get(a.first("against"));
            com.pointblue.dirxml.dev.simulate.Simulate.Outcome o = new com.pointblue.dirxml.dev.simulate.Simulate(
                com.pointblue.dirxml.dev.source.ExportWriter::writeDriver).run(tree, cases, against, a.first("env"), n -> System.err.println(n));
            System.out.print(a.has("json") ? o.json() + "\n" : o.text());
            return o.ok() ? 0 : 1;
        });
    }

    /** {@code export <tree> <out.xml>}: a Designer driver-set export Designer can import. */
    static int export(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 2, 2, "usage: export <asCodeDir> <out.xml>");
            DriverSet ds = AsCodeReader.read(Paths.get(a.positional.get(0)));
            com.pointblue.dirxml.dev.source.ExportWriter.write(ds, Paths.get(a.positional.get(1)));
            report(ds);
            System.out.println("wrote " + a.positional.get(1));
            return 0;
        });
    }

    /**
     * {@code export-project <tree> <projectDir> [--new …] [--dry-run] [--json]}: update a Designer project to match a
     * tree, or (--new) write a whole new project for it.
     */
    static int exportProject(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 2, 2, "usage: export-project <tree> <projectDir> [--new [--vault-name NAME] "
                + "[--vault-host HOST] [--vault-user DN] [--server NAME --server-context DN] "
                + "[--catalog DIR] [--designer DIR]] [--dry-run] [--json]");
            boolean dryRun = a.has("dry-run");
            boolean fresh = a.has("new");
            String vaultName = a.first("vault-name");
            String vaultHost = a.first("vault-host");
            String vaultUser = a.first("vault-user");
            String serverName = a.first("server");
            String serverContext = a.first("server-context");
            String catalogDir = a.first("catalog");
            String designerRoot = a.first("designer");
            List<String> pos = a.positional;
            if (!fresh && (vaultName != null || vaultHost != null || vaultUser != null
                || serverName != null || serverContext != null || catalogDir != null || designerRoot != null)) {
                throw new Args.UsageError("export-project: --vault-* / --server* / --catalog / --designer only apply "
                    + "with --new (they describe a new project's IdentityVault_, Server_ and package catalog); "
                    + "an existing project keeps its own");
            }
            com.pointblue.dirxml.dev.source.ProjectWriter.Result r = fresh
                ? com.pointblue.dirxml.dev.source.ProjectWriter.create(Paths.get(pos.get(0)), Paths.get(pos.get(1)),
                    new com.pointblue.dirxml.dev.source.NewProject(
                        vaultName, vaultHost, vaultUser, serverName, serverContext,
                        catalogDir == null ? null : Paths.get(catalogDir),
                        designerRoot == null ? null : Paths.get(designerRoot)), dryRun)
                : com.pointblue.dirxml.dev.source.ProjectWriter.update(
                    Paths.get(pos.get(0)), Paths.get(pos.get(1)), dryRun);
            System.out.print(a.has("json") ? r.json() + "\n" : r.text());
            return r.ok ? 0 : 1;
        });
    }

    /** {@code mapping-table.export-csv <tree> <path>}: the table as CSV on stdout (mapping-table.import-csv reads it back). */
    static int mappingTableExportCsv(String[] args) throws Exception {
        return usageErrors(() -> {
            Args p = parse(args, 2, 2, "usage: mapping-table.export-csv <asCodeDir> <path>");
            DriverSet ds = AsCodeReader.read(Paths.get(p.positional.get(0)));
            String path = p.positional.get(1);
            com.pointblue.dirxml.dev.model.Artifact a = ds.resolve(path);
            if (!(a instanceof com.pointblue.dirxml.dev.model.Resource r) || !r.isMappingTable() || r.content == null) {
                System.err.println("'" + path + "' is not a mapping table");
                return 2;
            }
            System.out.print(com.pointblue.dirxml.dev.edit.MappingTableCsv.write(((com.pointblue.dirxml.dev.model.Resource) a).content));
            return 0;
        });
    }

    /** {@code refs <tree> <artifactPath>}: everything that references an artifact. */
    static int refs(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 2, 2, "usage: refs <asCodeDir> <artifactPath>");
            DriverSet ds = AsCodeReader.read(Paths.get(a.positional.get(0)));
            String path = a.positional.get(1);
            List<com.pointblue.dirxml.dev.edit.Refs.Ref> refs = com.pointblue.dirxml.dev.edit.Refs.withDrivers(ds, path);
            if (ds.resolve(path) == null) {
                System.out.println("(no artifact at '" + path + "')");
            }
            for (com.pointblue.dirxml.dev.edit.Refs.Ref ref : refs) {
                System.out.println(ref);
            }
            System.out.println(refs.size() + " reference(s)");
            return 0;
        });
    }

    /** {@code validate <asCodeDir> [--json]}: exactly one tree. */
    static int validate(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 1, 1, "usage: validate <asCodeDir> [--json]");
            com.pointblue.dirxml.dev.validate.Report rep =
                com.pointblue.dirxml.dev.validate.Validator.standard().validate(Paths.get(a.positional.get(0)));
            System.out.print(a.has("json") ? rep.json() + "\n" : rep.text());
            return rep.ok() ? 0 : 1;
        });
    }

    /** {@code tree.diff <fromDir> <toDir> [--json]}. */
    static int treeDiff(String[] args) throws Exception {
        return usageErrors(() -> {
            Args a = parse(args, 2, 2, "usage: tree.diff <fromDir> <toDir> [--json]");
            DriverSet from = AsCodeReader.read(Paths.get(a.positional.get(0)));
            DriverSet to = AsCodeReader.read(Paths.get(a.positional.get(1)));
            com.pointblue.dirxml.dev.deploy.ModelDiff diff = com.pointblue.dirxml.dev.deploy.ModelDiff.of(from, to);
            System.out.print(a.has("json") ? diff.json() + "\n" : diff.text());
            return diff.isEmpty() ? 0 : 1;
        });
    }

    /** {@code docs <asCodeDir> --out <dir> [--driver D…] [--since <commit>] [--format md|html]}. */
    static int docs(String[] args) throws Exception {
        return usageErrors(() -> {
            String usage = "usage: docs <asCodeDir> --out <dir> [--driver D…] [--since <commit>] [--format md|html]";
            Args a = parse(args, 1, 1, usage);
            if (a.first("out") == null) {
                throw new Args.UsageError(usage);
            }
            String format = a.first("format") == null ? "md" : a.first("format");
            if (!format.equalsIgnoreCase("md") && !format.equalsIgnoreCase("html")) {
                throw new Args.UsageError("--format is md or html, not '" + format + "'");
            }
            Path out = Paths.get(a.first("out"));
            com.pointblue.dirxml.dev.docs.DocsGenerator.generate(Paths.get(a.positional.get(0)), out,
                new ArrayList<>(a.all("driver")), a.first("since"), format);
            System.out.println("wrote " + out);
            return 0;
        });
    }

    private static int doImport(Path export, Path out) throws Exception {
        return write(ExportReader.read(export), out);
    }

    private static int write(DriverSet ds, Path out) throws Exception {
        AsCodeWriter.write(ds, out);
        // keep bytes exact under git: a CRLF in a vault object must stay a CRLF in the tree
        com.pointblue.dirxml.dev.deploy.DeployLog.writeAttributes(out);
        report(ds);
        System.out.println("wrote " + out);
        return ds.unresolvedLinks().isEmpty() ? 0 : 1;
    }

    private static boolean hasCatalogFlag(String[] args) {
        for (String a : args) {
            if (a.equals("--catalog") || a.startsWith("--catalog=")) {
                return true;
            }
        }
        return false;
    }

    private static String need(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing -D" + prop);
        }
        return v;
    }

    private static int doCheck(Path dir) throws Exception {
        DriverSet ds = AsCodeReader.read(dir);
        report(ds);
        return ds.unresolvedLinks().isEmpty() ? 0 : 1;
    }

    private static void report(DriverSet ds) {
        System.out.println(ds);
        for (com.pointblue.dirxml.dev.model.DuplicateArtifacts.Dropped d : ds.duplicates) {
            System.out.println("NOTE: " + d.note + ". " + d.advice + ".");
        }
        System.out.println("  library: " + ds.library.policies.size() + " policies, "
            + ds.library.resources.size() + " resources");
        for (Driver d : ds.drivers) {
            List<Artifact> a = d.artifacts();
            System.out.printf("  driver %-40s %2d driver-scope, %2d subscriber, %2d publisher, %2d resources, %3d links%n",
                d.name, d.policies.size(), d.subscriber.policies.size(), d.publisher.policies.size(),
                d.resources.size(), d.links.size());
        }
        List<PolicyLink> broken = ds.unresolvedLinks();
        if (!broken.isEmpty()) {
            System.out.println("WARNING: " + broken.size() + " link(s) resolve to nothing — the target isn't in "
                + "this source: a Library/shared policy left out of a single-driver export (re-export with "
                + "referenced policies, or use a driver-set export/LDIF/live), or a dangling reference "
                + "in the source itself:");
            for (PolicyLink l : broken) {
                System.out.println("    - " + l);
            }
        }
    }

    private static void usage() {
        System.err.print(usageText());
    }

    /** Every command with its arguments, as {@code bin/idm} with no arguments prints it (docs/commands.md has an entry for each). */
    static String usageText() {
        StringBuilder sb = new StringBuilder();
        sb.append("usage:\n");
        sb.append("  version [--check]                     the running version; --check asks GitHub for the latest release\n");
        sb.append("  doctor [--json] [--env NAME]          JDK 21, simulator jar, lib/*.jar, environments (names, tiers,\n");
        sb.append("                                        whether url/bind/password/driverSet are set — never the values);\n");
        sb.append("                                        --env opens one LDAPS connection and does not print the password\n");
        sb.append("  import <export.xml> <outDir>          read a driver / driver-set export, write IDM-as-code\n");
        sb.append("  import-project <projectDir> <outDir>  read a Designer project, write IDM-as-code\n");
        sb.append("  import-ldif <dump.ldif> <outDir>      read an LDIF of the driver-set subtree, write IDM-as-code\n");
        sb.append("  import-live <outDir> --env E [--mark-new-drivers]   read the live vault named in environments.properties (its driverSet; a DN before <outDir> overrides)\n");
        sb.append("  import-live <driverSetDN> <outDir>    read the live vault (IDM_JAVA_OPTS=-Dldap.url/.bindDn/.password)\n");
        sb.append("  vault.diff <tree> --env E [--driver D…] [--json]     the tree vs the live vault (exit 1 if they differ; docs/vault-deploy.md)\n");
        sb.append("  vault.verify <tree> --env E [--driver D…] [--json]   same diff, read after a deploy\n");
        sb.append("  vault.compare-envs [<tree>] --env A --with B [--json]  two environments' vaults: drivers in one only, values, other drift (exit 1 if they differ)\n");
        sb.append("  vault.deploy <tree> --env E [--driver D…] [--dry-run|--yes|--step] [--confirm E] [--no-restart] [--secrets none|missing|all]\n");
        sb.append("               [--allow-missing-secrets] [--capture-drift] [--capture-only] [--base-branch B] [--driver-server D=SERVER…] [--delete-driver D…] [--delete-all entitlements|jobs|rbe-policies|templates|forms|prds|roles|entities|<object kind>s…] [--json]\n");
        sb.append("                                        without --yes or --step, the plan only; plan → snapshot → write → restart → verify → audit line; never deletes a driver without --delete-driver\n");
        sb.append("  vault.rollback [<tree>] --env E --snapshot <file.ldif> [--server DN] [--yes] [--confirm E] [--json]   restore a deploy's snapshot\n");
        sb.append("  vault.schema <tree> --env E [--json]                  cn=schema → schema/vault.xml (a read of the vault; docs/schema.md)\n");
        sb.append("  vault.app-schema <tree> --env E --driver D [--json]   the application's schema from the shim → drivers/<driver>/app-schema.xml (driver stopped)\n");
        sb.append("  vault.deploy-schema <tree> --env E [--yes] [--confirm E] [--json]   push the tree's custom schema definitions (plan without --yes)\n");
        sb.append("  schema.diff <tree> --env E [--json] | schema.diff --env A --other B [--json]   the model vs an environment's live schema, or two environments' (exit 1 if not in sync)\n");
        sb.append("  events.describe <tree> --env E [--json]                the Event Logger's store: rows, newest, schema versions (docs/event-store.md)\n");
        sb.append("  query <tree> events|event --env E …                   rows of the store, one row (see docs/event-store.md)\n");
        sb.append("  events.case <tree> --env E --id N --name NAME [--driver D] [--channel C] [--dir DIR] [--replace] [--dry-run] [--json]   a row as a simulator case\n");
        sb.append("  vault.export-clone --env E --out DIR [--rbs] [--keep-driver-state] [--data C,…] [--pseudonymise]   read a vault into a clone bundle (docs/vault-clone.md)\n");
        sb.append("  vault.import-clone --env E --from DIR [--server DN] [--map src=dst] [--driver-server d=srv] [--user-password KEY] [--replace] [--replace-driverset] [--yes] [--json]   create it in a lab tree\n");
        sb.append("  export <asCodeDir> <out.xml>          write the tree as a Designer driver-set export (Designer imports it)\n");
        sb.append("  export-project <tree> <projectDir> [--dry-run] [--json]  update an existing Designer project to match a tree\n");
        sb.append("  export-project <tree> <newProjectDir> --new [--vault-name NAME] [--vault-host HOST] [--vault-user DN]\n");
        sb.append("                 [--server NAME --server-context DN] [--catalog DIR] [--designer DIR] [--dry-run] [--json]\n");
        sb.append("                                        write a whole new Designer project for the tree (the directory's\n");
        sb.append("                                        basename is the project name; it must not exist or be empty; no\n");
        sb.append("                                        vault password is ever written). --catalog is the git package\n");
        sb.append("                                        catalog: it fills the project's own package catalog and refuses\n");
        sb.append("                                        if a package the tree names is missing. --designer overrides the\n");
        sb.append("                                        Designer install driver icons are copied from (IDM_DESIGNER).\n");
        sb.append("  check  <asCodeDir>                    load an as-code tree and report it (exit 1 on broken links)\n");
        sb.append("  validate <asCodeDir> [--json]         run every validation check (exit 1 on any error)\n");
        sb.append("  tree.diff <fromDir> <toDir> [--json]  structured diff of two as-code trees (exit 1 if they differ)\n");
        sb.append("  tree.compare <target> --with <source> [--name <pair>] [--cases <dir>] [--source-cases <dir>] [--json]  two projects compared, each difference by the pair's promotion record (docs/promotion.md)\n");
        sb.append("  promote.plan|promote.apply <target> --from <source> --object <path>…|--value <key>…|--pending|--contents … [--name <pair>] [--by WHO] [--force] [--json]  move differences into the target\n");
        sb.append("  promote.map <target> --from <source> [--name <pair>]   a proposed map for the pair (drivers and servers by likeness); writes nothing\n");
        sb.append("  promote.pair|promote.accept|promote.forget <target> --from <source> …  the pair's first record, and known differences (options: docs/commands.md)\n");
        sb.append("  docs <asCodeDir> --out <dir> [--driver D…] [--since <commit>] [--format md|html]  generate documentation from the model\n");
        sb.append("  simulate <asCodeDir> --cases <dir> [--against <asCodeDir>] [--env E] [--json]  run the regression corpus against the tree (with E's overrides applied); diff vs another tree\n");
        sb.append("  refs <asCodeDir> <artifactPath>       everything that references an artifact (a mapping table: also the drivers that run a policy reading it)\n");
        sb.append("  mapping-table.export-csv <asCodeDir> <path>   the table as CSV on stdout (mapping-table.import-csv reads it back)\n");
        sb.append("  show <asCodeDir> <artifactPath>       an artifact's content\n");
        sb.append("  query <asCodeDir> artifacts [driver] | chain <driver> sub|pub | gcvs [driver] | tables [driver] | schema [driver]\n");
        sb.append("  query <asCodeDir> fishbone <driver> [--json] | drivers [--json]   the policy-flow fishbone the VS Code viewer draws; the drivers to pick from\n");
        sb.append("  package.diff <asCodeDir> <artifactPath>  a customized packaged artifact vs its package baseline\n");
        sb.append("provisioning (forms + PRDs; docs/forms.md):\n");
        sb.append("  form.list <asCodeDir> [--driver D]              every JSON form: kind, name, title, #fields, packaged mark\n");
        sb.append("  form.show <asCodeDir> <name-or-path> [--driver D] [--json]  a form's outline + which PRDs bind it\n");
        sb.append("  prd.list  <asCodeDir> [--driver D]              every PRD: status, category, json-forms/classic, bound forms\n");
        sb.append("  prd.show  <asCodeDir> <name> [--driver D] [--json]          a PRD's properties, bindings, activities\n");
        sb.append("  prd.flow  <asCodeDir> <name> [--driver D] [--format text|mermaid] [--lang en] [--out FILE]  render the workflow (text walk, or a Mermaid flowchart)\n");
        sb.append("  form.edit <asCodeDir> <form> [--driver D] [--env E] [--locale L] [--no-wait] [--check] [--dry-run] [--force] [--json]\n");
        sb.append("                                          open the form in the vendor form builder; on save, store it and re-sync its PRD bindings\n");
        sb.append("  form.preview <asCodeDir> <form> [--driver D] [--out page.html] [--lang en]\n");
        sb.append("                                          self-contained HTML preview (open-source Form.io renderer + placeholders for NetIQ components)\n");
        sb.append("AppConfig objects (docs/appconfig.md; entities, roles, resources, reports, nav items … under the UA driver's AppConfig):\n");
        sb.append("  appconfig.list <asCodeDir> [--driver D] [--kind entity|role|resource|…] [--containers] [--json]\n");
        sb.append("  appconfig.show <asCodeDir> <path-or-name> [--driver D] [--attr A] [--json]   an object's classes and attributes\n");
        sb.append("  category.list <asCodeDir> --for role|resource [--driver D] [--json]   the category keys, their labels and how many use each\n");
        sb.append("entitlements (docs/entitlements.md; DirXML-Entitlement objects hanging off a driver):\n");
        sb.append("  entitlement.list <asCodeDir> [--driver D]                    every entitlement: conflict, multi-valued, display-name, packaged mark\n");
        sb.append("  entitlement.show <asCodeDir> --name N [--driver D] [--json]    an entitlement's document + which PRDs reference it\n");
        sb.append("package catalog (docs/packages.md; a jars+unpacked-form repository kept in git):\n");
        sb.append("  package.fetch   --catalog DIR [--site NAME|URL] [--short SHORT[_ver]…] [--all-versions] [--dry-run] [--json]\n");
        sb.append("  package.import  --catalog DIR <jar|dir> [--json]\n");
        sb.append("  package.list    --catalog DIR [--driver-type ID] [--type 2|3|4] [--base] [--json]\n");
        sb.append("  package.show    --catalog DIR SHORT[_ver] [--json]\n");
        sb.append("  package.diff    --catalog DIR SHORT_v1 SHORT_v2 [--json]\n");
        sb.append("  package.resolve --catalog DIR --base SHORT[_ver] [--feature SHORT…] [--driver-set-has SHORT_ver…] [--vault-has SHORT_ver…] [--json]\n");
        sb.append("  package.build   --catalog DIR <tree> --short SHORT --name N [--driver D] [--vendor V] [--version M.m.r] [--description T] [--readme FILE] [--include PATH…] [--new-version-of SHORT_ver|jar]\n");
        sb.append("                  [--depends SHORT…] [--supported-driver ID…] [--gcvs referenced|all|none] [--customized keep] [--base] [--json]   build a package jar from a driver's artifacts\n");
        sb.append("  package.site    --catalog DIR --out DIR [--description …] [--json]   render the catalog as an Eclipse update site Designer can read\n");
        sb.append("  package.status  <tree> [--driver D] [--catalog DIR] [--strict] [--json]   installed packages per driver vs the catalog (upgrades available); --strict exits 1 on a customized packaged object or a version the catalog lacks\n");
        sb.append("vault writes (vault.deploy --yes|--step, vault.rollback --yes, vault.import-clone --yes, vault.deploy-schema --yes,\n");
        sb.append("    driver start|stop|restart, cache clear, migrate, resync, secrets set|remove, trace set|reset, submit)\n");
        sb.append("    also need IDM_AGENT_ALLOW_WRITE=1 or --confirm <env>. A --dry-run stays read-only.\n");
        sb.append("operate (docs/operate.md; environments.properties, tiers, deploy-log audit):\n");
        sb.append("  driverset.status --env E [--json]\n");
        sb.append("  driver.status --env E --driver D [--json] [--tree DIR]\n");
        sb.append("  driver.start|stop|restart --env E --driver D [--wait N] [--yes] [--confirm E]\n");
        sb.append("  driver.cache view --env E --driver D [--count N] [--out DIR] [--json]\n");
        sb.append("  driver.cache clear --env E --driver D --yes [--confirm E]\n");
        sb.append("  driver.migrate --env E --driver D --xds FILE --yes [--confirm E]   into the application (--direction app, the default)\n");
        sb.append("  driver.migrate --env E --driver D --direction vault --base DN --filter F --class C [--max N] [--dry-run] [--yes] [--confirm E]   from it into the vault\n");
        sb.append("  driver.resync --env E --driver D [--since ISO] --yes [--confirm E]\n");
        sb.append("  driver.secrets list|set|remove --env E --driver D [--kind named|shim-auth|remote-loader|key|keystore] [--name X] [--stdin] [--yes] [--confirm E]\n");
        sb.append("  driver.trace show|set|reset|tail --env E --driver D [--level N] [--file F] [--lines N] [--grep RE] [--since MIN] [--follow] [--ldap [--seconds N] [--engine]]\n");
        sb.append("  driver.trace view --env E [--driver D] | --file FILE   open the DirXML Trace Viewer (desktop) connected to the vault, or on a file\n");
        sb.append("  trace.view --file F [--driver NAME] | --env E --driver D [--lines N|--since MIN|--ldap [--seconds N]] [--trace-driver NAME] [--channel C] [--status L] [--vetoed] [--text RE] [--last N] [--transaction N] [--json]\n");
        sb.append("                                          a trace as transactions: event, policy sets, policies, rules, actions, vetoes, statuses (best effort; docs/trace-view.md)\n");
        sb.append("  viewer.check | viewer.install [--version TAG] [--source] [--dir DIR]   the viewer: where it is; download a release or build from https://github.com/PointBlueTechnology/DirXMLTraceViewer\n");
        sb.append("  driver.submit --env E --driver D --xds <file> [--mode command|event|queue] --yes [--confirm E] [--tree DIR]   SubmitCommand into the running driver; with --tree, the simulator canary first\n");
        sb.append("  driver.query --env E --driver D [--class C] [--scope subtree|subordinates|entry] [--dn DN] [--association A] [--search name=value…] [--read-attr A…|none]   ask the connected system through the running driver (the engine's query verb)\n");
        sb.append("  driver.health [clear] --env E --driver D [--yes]   the Driver Health job's last state per server, the health configuration, the set's health jobs; clear removes the recorded status\n");
        sb.append("  job.list --env E [--driver D] | job.status --env E --job J [--driver D] | job.start|abort --env E --job J [--driver D] [--yes]   the set's jobs with the engine's state; start or abort one\n");
        sb.append("  driverset.activation --env E [apply --file F --yes] | driverset.servers --env E [add|remove --server DN --yes] | engine.metrics --env E | driver.rights --env E --driver D --dn O | workorder.list --env E   the low console gaps (docs/console-gaps.md §13)\n");
        sb.append("  vault.email-server [set] --env E [--host H] [--port N] [--from A] [--user U] [--tls true|false] [--timeout N] [--protocol P] [--auth M] [--password-key K|--stdin]   the notification collection's SMTP settings (the password never shown); set writes them\n");
        sb.append("  rbe.list --env E | rbe.members --env E --policy P   the entitlement policies of the set: priority, members, grants; one policy's computed members\n");
        sb.append("  engine.version --env E\n");
        sb.append("  engine.stats --env E [--driver D…] [--json]\n");
        sb.append("  driver.log-level [set] --env E [--driver D] [--level errors|errors-and-warnings|last-log-time|off|specific-events] [--events id,…] [--limit N] [--events-type N] [--inherit] [--yes] [--confirm E]\n");
        sb.append("                                          the log level of a driver or the driver set; set writes it live\n");
        sb.append("  driver.start-option --env E --driver D --option auto|manual|disabled [--yes] [--confirm E]   the start option, live\n");
        sb.append("  driver.associations --env E --driver D [--state processed|disabled|pending|manual|migrate] [--base DN] [--limit N] [--json]   the objects associated with the driver, by state\n");
        sb.append("  driver.password-sync --env E --driver D [--json]   the driver set's sync timeout and the driver's password settings, live\n");
        sb.append("  object.inspect --env E --dn DN [--json]   an object's classes, its associations across drivers, its password-sync status\n");
        sb.append("edit operations (each: load → apply → validate → write unless a new error; --dry-run, --force, --json):\n");
        sb.append(com.pointblue.dirxml.dev.edit.EditCli.usage());
        return sb.toString();
    }
}
