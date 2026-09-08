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
            if (args.length >= 3 && args[0].equals("import-live")) {
                // import-live <driverSetDN> <outDir>  (connection from -Dldap.url/-Dldap.bindDn/-Dldap.password)
                com.pointblue.dirxml.sim.JndiLdapSearch.Config c = new com.pointblue.dirxml.sim.JndiLdapSearch.Config();
                c.url = need("ldap.url");
                c.bindDn = need("ldap.bindDn");
                c.bindPassword = need("ldap.password");
                c.trustAllCerts = !"false".equals(System.getProperty("ldap.trustAll"));
                System.exit(write(com.pointblue.dirxml.dev.source.LdifReader.readLive(c, args[1]), Paths.get(args[2])));
            }
            if (args.length >= 2 && args[0].equals("check")) {
                System.exit(doCheck(Paths.get(args[1])));
            }
            if (args.length >= 1 && com.pointblue.dirxml.dev.edit.EditCli.isOperation(args[0])) {
                System.exit(com.pointblue.dirxml.dev.edit.EditCli.run(args));
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
        report(ds);
        System.out.println("wrote " + out);
        return ds.unresolvedLinks().isEmpty() ? 0 : 1;
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
        System.err.println("  import-live <driverSetDN> <outDir>    read the live vault (IDM_JAVA_OPTS=-Dldap.url/.bindDn/.password)");
        System.err.println("  check  <asCodeDir>                    load an as-code tree and report it (exit 1 on broken links)");
        System.err.println("  validate <asCodeDir> [--json]         run every validation check (exit 1 on any error)");
        System.err.println("  refs <asCodeDir> <artifactPath>       everything that references an artifact");
        System.err.println("edit operations (each: load → apply → validate → write unless a new error; --dry-run, --force, --json):");
        System.err.print(com.pointblue.dirxml.dev.edit.EditCli.usage());
    }
}
