package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.DriverSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.naming.directory.SearchControls;

/**
 * The LDIF a vault holds after an initial deploy of a model: the tree is written, deployed into
 * a fake vault that starts with only the driver-set object, and every entry under the set is
 * dumped in DN order — the same path a real deploy takes. Lives in this package for the
 * deployer's test constructor; {@code SyntheticDriverSet} is the caller.
 */
public final class SyntheticLdif {

    private SyntheticLdif() {
    }

    public static String of(DriverSet model, String dsDn) throws IOException {
        Path tree = Files.createTempDirectory("synth-tree");
        AsCodeWriter.write(model, tree);
        FakeVault vault = new FakeVault();
        Vault.Entry root = new Vault.Entry("");
        root.attrs.put("dsaName", Vault.value("cn=synth1,ou=servers,o=synth"));
        vault.seed(root);
        Vault.Entry set = new Vault.Entry(dsDn);
        set.attrs.put("objectClass", List.of("Top".getBytes(StandardCharsets.UTF_8), "DirXML-DriverSet".getBytes(StandardCharsets.UTF_8)));
        set.attrs.put("cn", Vault.value(model.name));
        vault.seed(set);
        Deployer.Options o = new Deployer.Options();
        o.tree = tree;
        o.env = new Environments.Environment("synth", "ldaps://synth:636", "cn=admin,o=synth", "pw", dsDn,
            Environments.Tier.DEV, null, null, true, null, null);
        o.yes = true;
        Deployer.Result r = new Deployer(o, vault).run();
        if (!r.ok) {
            StringBuilder why = new StringBuilder("the synthetic deploy failed: " + r.text());
            for (java.lang.reflect.Field f : r.getClass().getFields()) {
                try {
                    why.append("\n  ").append(f.getName()).append(" = ").append(f.get(r));
                } catch (IllegalAccessException ignore) {
                    // skip
                }
            }
            throw new IllegalStateException(why.toString());
        }
        List<Vault.Entry> entries = new ArrayList<>(vault.reopen().search(dsDn, "(objectClass=*)", SearchControls.SUBTREE_SCOPE));
        entries.sort((a, b) -> depth(a.dn) != depth(b.dn) ? Integer.compare(depth(a.dn), depth(b.dn)) : a.dn.compareToIgnoreCase(b.dn));
        StringBuilder sb = new StringBuilder("version: 1\n# " + entries.size()
            + " entries: the synthetic driver set as a vault holds it after a deploy (SyntheticDriverSet)\n\n");
        for (Vault.Entry e : entries) {
            sb.append(Ldif.entry(e)).append('\n');
        }
        return sb.toString();
    }

    private static int depth(String dn) {
        return dn.split("(?<!\\\\),").length;
    }
}
