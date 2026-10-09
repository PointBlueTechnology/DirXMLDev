package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.EntitlementPolicy;

/** Entitlement policies through ModelDiff → Plan (docs/console-gaps.md §9), mirroring JobDeployTest. */
public class RbeDeployTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String SET = "cn=Entitlement Policies," + DS;

    private Path tree() throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = DS;
        Driver es = new Driver("Entitlements Service");
        es.dn = "cn=Entitlements Service," + DS;
        es.shimClass = EntitlementPolicy.SERVICE_SHIM_CLASS;
        ds.drivers.add(es);
        Driver ad = new Driver("AD");
        ad.dn = "cn=AD," + DS;
        ds.drivers.add(ad);
        Path t = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    private static EntitlementPolicy policy(String name, int priority) {
        EntitlementPolicy p = new EntitlementPolicy(name);
        p.priority = priority;
        p.description = "d";
        p.memberQuery = "ldap:///o=data??sub?(employeeType=x)?x-sparse";
        p.entitlementRefs.add("cn=UserAccount,cn=AD," + DS + "#0#<ref/>");
        return p;
    }

    private static List<String> ops(Plan p) {
        List<String> out = new java.util.ArrayList<>();
        for (Plan.Step s : p.steps) {
            out.add(s.op + " " + s.dn + (s.attr == null ? "" : " " + s.attr));
        }
        return out;
    }

    @Test
    public void anAddedPolicyEnsuresTheContainerAddsThePolicyRewritesThePriorityAndRestartsTheServiceDriver() throws Exception {
        Path t = tree();
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        to.rbePolicies.add(policy("Contractors", 1));
        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(1, diff.changes().size());
        assertEquals(ModelDiff.Kind.RBE_ADDED, diff.changes().get(0).kind);
        assertEquals(List.of("Entitlements Service"), diff.affectedDrivers());
        Plan p = Plan.of(diff, to, DS, Secrets.none(), "none", null, true);
        List<String> ops = ops(p);
        assertTrue(ops.toString(), ops.contains("ENSURE_CONTAINER " + SET));
        assertTrue(ops.toString(), ops.contains("ADD cn=Contractors," + SET));
        assertTrue(ops.toString(), ops.contains("MODIFY " + SET + " DirXML-SPPriority"));
        assertTrue(ops.toString(), ops.indexOf("ADD cn=Contractors," + SET) < ops.indexOf("MODIFY " + SET + " DirXML-SPPriority"));
        Plan.Step prio = p.steps.stream().filter(s -> "DirXML-SPPriority".equals(s.attr)).findFirst().get();
        assertEquals("cn=Contractors," + SET + "#1#0", new String(prio.values.get("DirXML-SPPriority").get(0), "UTF-8"));
        Plan.Step add = p.steps.stream().filter(s -> s.op == Plan.Op.ADD).findFirst().get();
        assertEquals(List.of("Top", "DirXML-SharedProfile"), add.objectClasses);
        assertTrue(add.values.containsKey("memberQueryURL"));
        assertTrue(!add.values.containsKey("Member"));   // empty attributes are not sent on an add
        assertTrue(p.restart.toString(), p.restart.contains("Entitlements Service"));
    }

    @Test
    public void aChangedPolicyModifiesEveryAttributeClearingWhatTheTreeDropped() throws Exception {
        Path t = tree();
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        EntitlementPolicy a = policy("Contractors", 1);
        a.members.add("cn=bob,o=data");
        from.rbePolicies.add(a);
        EntitlementPolicy b = policy("Contractors", 1);
        b.memberQuery = "ldap:///o=data??sub?(employeeType=y)?x-sparse";
        to.rbePolicies.add(b);
        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(1, diff.changes().size());
        assertEquals(ModelDiff.Kind.RBE_CHANGED, diff.changes().get(0).kind);
        assertTrue(diff.changes().get(0).detail, diff.changes().get(0).detail.contains("employeetype=y"));
        Plan p = Plan.of(diff, to, DS, Secrets.none(), "none", null, true);
        Plan.Step member = p.steps.stream().filter(s -> "Member".equals(s.attr)).findFirst().get();
        assertTrue(member.values.get("Member").isEmpty());
        assertTrue(ops(p).toString(), ops(p).contains("MODIFY cn=Contractors," + SET + " memberQueryURL"));
    }

    @Test
    public void aPriorityOnlyChangeIsSettingsAndARemovalIsGuarded() throws Exception {
        Path t = tree();
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        from.rbePolicies.add(policy("A", 1));
        from.rbePolicies.add(policy("B", 2));
        to.rbePolicies.add(policy("A", 2));
        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(2, diff.changes().size());
        ModelDiff.Change changed = diff.changes().stream().filter(c -> c.kind == ModelDiff.Kind.RBE_CHANGED).findFirst().get();
        assertEquals("settings", changed.what);
        ModelDiff.Change removed = diff.changes().stream().filter(c -> c.kind == ModelDiff.Kind.RBE_REMOVED).findFirst().get();
        assertEquals("rbe-policies/B", removed.path);
        assertEquals("rbe-policies", ModelDiff.removalKind(removed));
        Plan p = Plan.of(diff, to, DS, Secrets.none(), "none", null, true);
        assertTrue(ops(p).toString(), ops(p).contains("DELETE cn=B," + SET));
        assertTrue(ops(p).toString(), ops(p).stream().noneMatch(o -> o.startsWith("MODIFY cn=A,")));   // priority only: the container is rewritten, the policy untouched
        assertTrue(ops(p).toString(), ops(p).contains("MODIFY " + SET + " DirXML-SPPriority"));
        // the tree still has a policy, so the guard does not hold the delete back
        assertTrue(p.notes.toString(), p.notes.stream().noneMatch(n -> n.contains("rbe-policies")));
    }
}
