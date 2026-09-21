package com.pointblue.dirxml.dev.validate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import java.util.List;
import org.junit.Test;

public class AppConfigCheckTest {

    private static final String APPCFG = "cn=AppConfig,cn=UA,cn=driverset1,o=system";

    private static DriverSet sample() {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";
        Driver ua = new Driver("UA");
        ua.dn = "cn=UA,cn=driverset1,o=system";
        Provisioning p = new Provisioning();
        p.dn = APPCFG;
        ua.provisioning = p;
        ds.drivers.add(ua);
        p.prds.add(new Prd("Role Approval"));

        AppObject entity = AppObject.ofPath("DirectoryModel/EntityDefs/user");
        entity.classes.addAll(List.of("Top", "srvprvEntity"));
        entity.put("XmlData", List.of("<entity-definition><attributes><attribute key=\"cn\"/><attribute key=\"cn\"/></attributes></entity-definition>"));
        p.objects.add(entity);

        AppObject broken = AppObject.ofPath("DirectoryModel/EntityDefs/broken");
        broken.classes.addAll(List.of("Top", "srvprvEntity"));
        broken.put("XmlData", List.of("<entity-definition><oops></entity-definition>"));
        p.objects.add(broken);

        AppObject role = AppObject.ofPath("RoleConfig/RoleDefs/Level20/System/x");
        role.classes.addAll(List.of("Top", "nrfRole"));
        role.put("nrfRoleLevel", List.of("30"));
        role.put("nrfLocalizedNames", List.of("no tilde here"));
        p.objects.add(role);

        AppObject cfg = AppObject.ofPath("RoleConfig/configuration");
        cfg.classes.addAll(List.of("Top", "nrfConfiguration"));
        cfg.put("nrfStdRequestDef", List.of("cn=Role Approval,cn=RequestDefs," + APPCFG));
        cfg.put("nrfStdSODRequestDef", List.of("cn=Missing PRD,cn=RequestDefs," + APPCFG));
        cfg.put("nrfRolesContainer", List.of("cn=RoleDefs,cn=RoleConfig," + APPCFG));
        cfg.put("nrfUADContainer", List.of("cn=UA,cn=driverset1,o=system"));
        p.objects.add(cfg);
        AppObject roleDefs = AppObject.ofPath("RoleConfig/RoleDefs");
        roleDefs.classes.addAll(List.of("Top", "nrfRoleDefs"));
        p.objects.add(roleDefs);
        return ds;
    }

    private static List<Finding> run() {
        Report r = new Report();
        new AppConfigCheck().run(sample(), r);
        return r.findings();
    }

    private static long count(List<Finding> f, String code) {
        return f.stream().filter(x -> x.code.equals(code)).count();
    }

    @Test
    public void codes() {
        List<Finding> f = run();
        assertEquals(1, count(f, "appconfig-entity-key-duplicate"));
        assertEquals(1, count(f, "appconfig-xml-invalid"));
        assertEquals(1, count(f, "appconfig-role-level-mismatch"));
        assertEquals(1, count(f, "appconfig-localized-unparsable"));
        assertEquals("the SoD PRD is missing; the role approval PRD and the RoleDefs container resolve",
            1, count(f, "appconfig-ref-missing"));
        assertTrue(f.stream().anyMatch(x -> x.code.equals("appconfig-ref-missing") && x.message.contains("Missing PRD")));
        assertEquals("nrfUADContainer is outside AppConfig: noted, not an error", 0,
            f.stream().filter(x -> x.message.contains("nrfUADContainer") && x.severity != Finding.Severity.INFO).count());
    }
}
