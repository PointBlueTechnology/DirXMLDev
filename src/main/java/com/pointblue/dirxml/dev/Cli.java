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
 * </pre>
 */
public final class Cli {

    public static void main(String[] args) {
        try {
            if (args.length >= 3 && args[0].equals("import")) {
                System.exit(doImport(Paths.get(args[1]), Paths.get(args[2])));
            }
            if (args.length >= 3 && args[0].equals("import-project")) {
                System.exit(write(com.pointblue.dirxml.dev.source.ProjectReader.read(Paths.get(args[1])), Paths.get(args[2])));
            }
            if (args.length >= 3 && args[0].equals("import-ldif")) {
                System.exit(write(com.pointblue.dirxml.dev.source.LdifReader.read(Paths.get(args[1])), Paths.get(args[2])));
            }
            if (args.length >= 2 && args[0].equals("import-live")) {
                // import-live [<driverSetDN>] <outDir> --env NAME      (environments.properties: url, bindDn, password in any form, driverSet)
                // import-live <driverSetDN> <outDir>                    (connection from -Dldap.url/-Dldap.bindDn/-Dldap.password)
                java.util.List<String> positional = new java.util.ArrayList<>();
                String envName = null;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--env") && i + 1 < args.length) {
                        envName = args[++i];
                    } else {
                        positional.add(args[i]);
                    }
                }
                com.pointblue.dirxml.dev.deploy.Vault.Config c;
                String driverSetDn;
                if (envName != null) {
                    com.pointblue.dirxml.dev.deploy.Environments.Environment env =
                        com.pointblue.dirxml.dev.deploy.Environments.load().get(envName);
                    c = env.vaultConfig();
                    driverSetDn = positional.size() >= 2 ? positional.get(0) : env.driverSetDn;
                } else {
                    if (positional.size() < 2) {
                        System.err.println("usage: import-live [<driverSetDN>] <outDir> --env NAME   |   import-live <driverSetDN> <outDir> (IDM_JAVA_OPTS=-Dldap.url/.bindDn/.password)");
                        System.exit(2);
                    }
                    c = new com.pointblue.dirxml.dev.deploy.Vault.Config();
                    c.url = need("ldap.url");
                    c.bindDn = need("ldap.bindDn");
                    c.password = need("ldap.password");
                    c.trustAll = !"false".equals(System.getProperty("ldap.trustAll"));
                    driverSetDn = positional.get(0);
                }
                Path outDir = Paths.get(positional.get(positional.size() - 1));
                // every attribute (package stamps included) — the same reader vault.diff / deploy use
                System.exit(write(com.pointblue.dirxml.dev.deploy.VaultDiff.readLive(c, driverSetDn), outDir));
            }
            if (args.length >= 2 && args[0].equals("check")) {
                System.exit(doCheck(Paths.get(args[1])));
            }
            if (args.length >= 1 && com.pointblue.dirxml.dev.edit.EditCli.isOperation(args[0])) {
                System.exit(com.pointblue.dirxml.dev.edit.EditCli.run(args));
            }
            if (args.length >= 2 && args[0].equals("simulate")) {
                Path tree = Paths.get(args[1]);
                Path cases = null;
                Path against = null;
                boolean json = false;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--cases") && i + 1 < args.length) {
                        cases = Paths.get(args[++i]);
                    } else if (args[i].equals("--against") && i + 1 < args.length) {
                        against = Paths.get(args[++i]);
                    } else if (args[i].equals("--json")) {
                        json = true;
                    }
                }
                if (cases == null) {
                    System.err.println("usage: simulate <asCodeDir> --cases <casesDir> [--against <asCodeDir>] [--json]");
                    System.exit(2);
                }
                com.pointblue.dirxml.dev.simulate.Simulate.Outcome o = new com.pointblue.dirxml.dev.simulate.Simulate(
                    com.pointblue.dirxml.dev.source.ExportWriter::writeDriver).run(tree, cases, against);
                System.out.print(json ? o.json() + "\n" : o.text());
                System.exit(o.ok() ? 0 : 1);
            }
            if (args.length >= 3 && args[0].equals("export")) {
                // export <tree> <out.xml>: a Designer driver-set export Designer can import
                DriverSet ds = AsCodeReader.read(Paths.get(args[1]));
                com.pointblue.dirxml.dev.source.ExportWriter.write(ds, Paths.get(args[2]));
                report(ds);
                System.out.println("wrote " + args[2]);
                System.exit(0);
            }
            if (args.length >= 3 && args[0].equals("export-project")) {
                // export-project <tree> <projectDir> [--new …] [--dry-run] [--json]: update a Designer
                // project to match a tree, or (--new) write a whole new project for it
                boolean dryRun = false;
                boolean json = false;
                boolean fresh = false;
                String vaultName = null;
                String vaultHost = null;
                String vaultUser = null;
                String serverName = null;
                String serverContext = null;
                String catalogDir = null;
                String designerRoot = null;
                List<String> pos = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--dry-run")) {
                        dryRun = true;
                    } else if (args[i].equals("--json")) {
                        json = true;
                    } else if (args[i].equals("--new")) {
                        fresh = true;
                    } else if (args[i].equals("--vault-name") && i + 1 < args.length) {
                        vaultName = args[++i];
                    } else if (args[i].equals("--vault-host") && i + 1 < args.length) {
                        vaultHost = args[++i];
                    } else if (args[i].equals("--vault-user") && i + 1 < args.length) {
                        vaultUser = args[++i];
                    } else if (args[i].equals("--server") && i + 1 < args.length) {
                        serverName = args[++i];
                    } else if (args[i].equals("--server-context") && i + 1 < args.length) {
                        serverContext = args[++i];
                    } else if (args[i].equals("--catalog") && i + 1 < args.length) {
                        catalogDir = args[++i];
                    } else if (args[i].equals("--designer") && i + 1 < args.length) {
                        designerRoot = args[++i];
                    } else {
                        pos.add(args[i]);
                    }
                }
                if (pos.size() < 2) {
                    System.err.println("usage: export-project <tree> <projectDir> [--new [--vault-name NAME] "
                        + "[--vault-host HOST] [--vault-user DN] [--server NAME --server-context DN] "
                        + "[--catalog DIR] [--designer DIR]] [--dry-run] [--json]");
                    System.exit(2);
                }
                if (!fresh && (vaultName != null || vaultHost != null || vaultUser != null
                    || serverName != null || serverContext != null || catalogDir != null || designerRoot != null)) {
                    System.err.println("export-project: --vault-* / --server* / --catalog / --designer only apply "
                        + "with --new (they describe a new project's IdentityVault_, Server_ and package catalog); "
                        + "an existing project keeps its own");
                    System.exit(2);
                }
                com.pointblue.dirxml.dev.source.ProjectWriter.Result r = fresh
                    ? com.pointblue.dirxml.dev.source.ProjectWriter.create(Paths.get(pos.get(0)), Paths.get(pos.get(1)),
                        new com.pointblue.dirxml.dev.source.NewProject(
                            vaultName, vaultHost, vaultUser, serverName, serverContext,
                            catalogDir == null ? null : Paths.get(catalogDir),
                            designerRoot == null ? null : Paths.get(designerRoot)), dryRun)
                    : com.pointblue.dirxml.dev.source.ProjectWriter.update(
                        Paths.get(pos.get(0)), Paths.get(pos.get(1)), dryRun);
                System.out.print(json ? r.json() + "\n" : r.text());
                System.exit(r.ok ? 0 : 1);
            }
            if (args.length >= 1 && args[0].equals("show")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.show(args));
            }
            if (args.length >= 1 && args[0].equals("query")) {
                System.exit(com.pointblue.dirxml.dev.edit.ReadCli.query(args));
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
            if (args.length >= 3 && args[0].equals("refs")) {
                DriverSet ds = AsCodeReader.read(Paths.get(args[1]));
                List<com.pointblue.dirxml.dev.edit.Refs.Ref> refs = com.pointblue.dirxml.dev.edit.Refs.to(ds, args[2]);
                if (ds.resolve(args[2]) == null) {
                    System.out.println("(no artifact at '" + args[2] + "')");
                }
                for (com.pointblue.dirxml.dev.edit.Refs.Ref ref : refs) {
                    System.out.println(ref);
                }
                System.out.println(refs.size() + " reference(s)");
                System.exit(0);
            }
            if (args.length >= 2 && args[0].equals("validate")) {
                boolean json = false;
                Path dir = null;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--json")) {
                        json = true;
                    } else {
                        dir = Paths.get(args[i]);
                    }
                }
                if (dir == null) {
                    usage();
                    System.exit(2);
                }
                com.pointblue.dirxml.dev.validate.Report rep =
                    com.pointblue.dirxml.dev.validate.Validator.standard().validate(dir);
                System.out.print(json ? rep.json() + "\n" : rep.text());
                System.exit(rep.ok() ? 0 : 1);
            }
            if (args.length >= 2 && args[0].startsWith("vault.")) {
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
            if (args.length >= 1 && (args[0].startsWith("driver.") || args[0].startsWith("driverset.") || args[0].startsWith("engine."))) {
                System.exit(com.pointblue.dirxml.dev.operate.OperateCli.run(args));
            }
            if (args.length >= 3 && args[0].equals("tree.diff")) {
                boolean json = false;
                List<String> pos = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--json")) {
                        json = true;
                    } else {
                        pos.add(args[i]);
                    }
                }
                if (pos.size() < 2) {
                    System.err.println("usage: tree.diff <fromDir> <toDir> [--json]");
                    System.exit(2);
                }
                DriverSet from = AsCodeReader.read(Paths.get(pos.get(0)));
                DriverSet to = AsCodeReader.read(Paths.get(pos.get(1)));
                com.pointblue.dirxml.dev.deploy.ModelDiff diff = com.pointblue.dirxml.dev.deploy.ModelDiff.of(from, to);
                System.out.print(json ? diff.json() + "\n" : diff.text());
                System.exit(diff.isEmpty() ? 0 : 1);
            }
            if (args.length >= 2 && args[0].equals("docs")) {
                Path tree = Paths.get(args[1]);
                Path out = null;
                List<String> drivers = new ArrayList<>();
                String since = null;
                String format = "md";
                for (int i = 2; i < args.length; i++) {
                    switch (args[i]) {
                        case "--out":
                            out = Paths.get(args[++i]);
                            break;
                        case "--driver":
                            drivers.add(args[++i]);
                            break;
                        case "--since":
                            since = args[++i];
                            break;
                        case "--format":
                            format = args[++i];
                            break;
                        default:
                            break;
                    }
                }
                if (out == null) {
                    System.err.println("usage: docs <asCodeDir> --out <dir> [--driver D…] [--since <commit>] [--format md|html]");
                    System.exit(2);
                }
                com.pointblue.dirxml.dev.docs.DocsGenerator.generate(tree, out, drivers, since, format);
                System.out.println("wrote " + out);
                System.exit(0);
            }
            usage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(3);
        }
    }

    private static int doImport(Path export, Path out) throws Exception {
        return write(ExportReader.read(export), out);
    }

    private static int write(DriverSet ds, Path out) throws Exception {
        AsCodeWriter.write(ds, out);
        // keep bytes exact under git: a CRLF in a vault object must stay a CRLF in the tree
        Path attrs = out.resolve(".gitattributes");
        if (!java.nio.file.Files.exists(attrs)) {
            java.nio.file.Files.writeString(attrs, "# IDM-as-code: content is byte-exact vault data; never normalize line endings\n* -text\n");
        }
        report(ds);
        System.out.println("wrote " + out);
        return ds.unresolvedLinks().isEmpty() ? 0 : 1;
    }

    private static boolean hasCatalogFlag(String[] args) {
        for (String a : args) {
            if (a.equals("--catalog")) {
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
        System.err.println("usage:");
        System.err.println("  import <export.xml> <outDir>          read a driver / driver-set export, write IDM-as-code");
        System.err.println("  import-project <projectDir> <outDir>  read a Designer project, write IDM-as-code");
        System.err.println("  import-ldif <dump.ldif> <outDir>      read an LDIF of the driver-set subtree, write IDM-as-code");
        System.err.println("  import-live <outDir> --env E          read the live vault named in environments.properties (its driverSet; a DN before <outDir> overrides)");
        System.err.println("  vault.export-clone --env E --out DIR [--rbs] [--keep-driver-state]   read a vault into a clone bundle (docs/vault-clone.md)");
        System.err.println("  vault.import-clone --env E --from DIR [--server DN] [--map src=dst] [--replace] [--replace-driverset] [--yes]   create it in a lab tree");
        System.err.println("  import-live <driverSetDN> <outDir>    read the live vault (IDM_JAVA_OPTS=-Dldap.url/.bindDn/.password)");
        System.err.println("  export <asCodeDir> <out.xml>          write the tree as a Designer driver-set export (Designer imports it)");
        System.err.println("  export-project <tree> <projectDir> [--dry-run] [--json]  update an existing Designer project to match a tree");
        System.err.println("  export-project <tree> <newProjectDir> --new [--vault-name NAME] [--vault-host HOST] [--vault-user DN]");
        System.err.println("                 [--server NAME --server-context DN] [--catalog DIR] [--designer DIR] [--dry-run] [--json]");
        System.err.println("                                        write a whole new Designer project for the tree (the directory's");
        System.err.println("                                        basename is the project name; it must not exist or be empty; no");
        System.err.println("                                        vault password is ever written). --catalog is the git package");
        System.err.println("                                        catalog: it fills the project's own package catalog and refuses");
        System.err.println("                                        if a package the tree names is missing. --designer overrides the");
        System.err.println("                                        Designer install driver icons are copied from (IDM_DESIGNER).");
        System.err.println("  check  <asCodeDir>                    load an as-code tree and report it (exit 1 on broken links)");
        System.err.println("  validate <asCodeDir> [--json]         run every validation check (exit 1 on any error)");
        System.err.println("  tree.diff <fromDir> <toDir> [--json]  structured diff of two as-code trees (exit 1 if they differ)");
        System.err.println("  docs <asCodeDir> --out <dir> [--driver D…] [--since <commit>] [--format md|html]  generate documentation from the model");
        System.err.println("  simulate <asCodeDir> --cases <dir> [--against <asCodeDir>] [--json]  run the regression corpus against the tree; diff vs another tree");
        System.err.println("  refs <asCodeDir> <artifactPath>       everything that references an artifact");
        System.err.println("  show <asCodeDir> <artifactPath>       an artifact's content");
        System.err.println("  query <asCodeDir> artifacts [driver] | chain <driver> sub|pub | gcvs [driver] | tables [driver]");
        System.err.println("  package.diff <asCodeDir> <artifactPath>  a customized packaged artifact vs its package baseline");
        System.err.println("provisioning (forms + PRDs; docs/forms.md):");
        System.err.println("  form.list <asCodeDir> [--driver D]              every JSON form: kind, name, title, #fields, packaged mark");
        System.err.println("  form.show <asCodeDir> <name-or-path> [--driver D] [--json]  a form's outline + which PRDs bind it");
        System.err.println("  prd.list  <asCodeDir> [--driver D]              every PRD: status, category, json-forms/classic, bound forms");
        System.err.println("  prd.show  <asCodeDir> <name> [--driver D] [--json]          a PRD's properties, bindings, activities");
        System.err.println("  prd.flow  <asCodeDir> <name> [--driver D] [--format text|mermaid] [--lang en] [--out FILE]  render the workflow (text walk, or a Mermaid flowchart)");
        System.err.println("  form.edit <asCodeDir> <form> [--driver D] [--env E] [--locale L] [--no-wait] [--check] [--dry-run] [--json]");
        System.err.println("                                          open the form in the vendor form builder; on save, store it and re-sync its PRD bindings");
        System.err.println("  form.preview <asCodeDir> <form> [--driver D] [--out page.html] [--lang en]");
        System.err.println("                                          self-contained HTML preview (open-source Form.io renderer + placeholders for NetIQ components)");
        System.err.println("entitlements (docs/entitlements.md; DirXML-Entitlement objects hanging off a driver):");
        System.err.println("  entitlement.list <asCodeDir> [--driver D]                    every entitlement: conflict, multi-valued, display-name, packaged mark");
        System.err.println("  entitlement.show <asCodeDir> --driver D --name N [--json]    an entitlement's document + which PRDs reference it");
        System.err.println("package catalog (docs/packages.md; a jars+unpacked-form repository kept in git):");
        System.err.println("  package.fetch   --catalog DIR [--site NAME|URL] [--short SHORT[_ver]…] [--all-versions] [--dry-run] [--json]");
        System.err.println("  package.import  --catalog DIR <jar|dir> [--json]");
        System.err.println("  package.list    --catalog DIR [--driver-type ID] [--type 2|3|4] [--base] [--json]");
        System.err.println("  package.show    --catalog DIR SHORT[_ver] [--json]");
        System.err.println("  package.diff    --catalog DIR SHORT_v1 SHORT_v2 [--json]");
        System.err.println("  package.resolve --catalog DIR --base SHORT[_ver] [--feature SHORT…] [--driver-set-has SHORT_ver…] [--vault-has SHORT_ver…] [--json]");
        System.err.println("operate (docs/operate.md; environments.properties, tiers, deploy-log audit):");
        System.err.println("  driverset.status --env E [--json]");
        System.err.println("  driver.status --env E --driver D [--json] [--tree DIR]");
        System.err.println("  driver.start|stop|restart --env E --driver D [--wait N] [--yes] [--confirm E]");
        System.err.println("  driver.cache view --env E --driver D [--count N] [--out DIR] [--json]");
        System.err.println("  driver.cache clear --env E --driver D --yes [--confirm E]");
        System.err.println("  driver.migrate --env E --driver D --xds FILE --yes [--confirm E]");
        System.err.println("  driver.resync --env E --driver D [--since ISO] --yes [--confirm E]");
        System.err.println("  driver.secrets list|set|remove --env E --driver D [--name X] [--stdin]");
        System.err.println("  driver.trace show|set|reset --env E --driver D [--level N] [--file F]");
        System.err.println("  engine.version --env E");
        System.err.println("  engine.stats --env E [--driver D…] [--json]");
        System.err.println("edit operations (each: load → apply → validate → write unless a new error; --dry-run, --force, --json):");
        System.err.print(com.pointblue.dirxml.dev.edit.EditCli.usage());
    }
}
