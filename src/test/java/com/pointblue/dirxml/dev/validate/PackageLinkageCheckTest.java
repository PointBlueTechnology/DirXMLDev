package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A packaged, linked artifact without its package linkage record. */
public class PackageLinkageCheckTest {

    private static Report run(DriverSet ds) {
        return new Validator(Arrays.asList(new PackageLinkageCheck())).validate(ds);
    }

    @Test
    public void cleanTreeAndUnpackagedLinksAreSilent() {
        assertTrue(run(ValidatorTest.clean()).findings().isEmpty());
    }

    @Test
    public void packagedLinkedPolicyWithoutARecordIsAWarning() {
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");
        Policy p = ad.subscriber.policies.get(0);                    // sub-ctp, linked in subscriber-command
        p.meta.put("dirxml-pkgguid", "PKG-1;com.netiqcorporation.novladbase;4.1.2;Active Directory Base;NOVLADBASE");
        p.meta.put("dirxml-pkgassociationid", "ASSOC-1");

        Report r = run(ds);
        assertEquals(r.text(), 1, r.findings().size());
        Finding f = r.findings().get(0);
        assertEquals("package-linkage-missing", f.code);
        assertEquals(Finding.Severity.WARNING, f.severity);
        assertEquals("drivers/AD/subscriber/sub-ctp", f.path);
        assertTrue(f.message, f.message.contains("subscriber-command"));
        assertTrue(f.detail, f.detail.contains("weight -1"));
        assertTrue(f.detail, f.detail.contains("PKG-1"));

        // the older project vocabulary counts as packaged too
        p.meta.clear();
        p.meta.put("package-id", "PKG-1");
        p.meta.put("pkg-assoc-id", "ASSOC-1");
        assertEquals(1, run(ds).withCode("package-linkage-missing").size());

        // with the record: nothing
        p.meta.put("dirxml-pkglinkages", "<policy-linkage><policy-set name=\"command\" channel=\"subscriber\" order=\"Weight\" value=\"200\"/></policy-linkage>");
        assertTrue(run(ds).text(), run(ds).findings().isEmpty());
    }

    @Test
    public void packagedButUnlinkedIsNotReported() {
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");
        Policy extra = new Policy("sub-extra", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(
            "<policy><rule><description>x</description><conditions/><actions/></rule></policy>"));
        extra.meta.put("dirxml-pkgguid", "PKG-1");
        ad.subscriber.policies.add(extra);                          // packaged, linked nowhere
        assertTrue(run(ds).text(), run(ds).findings().isEmpty());
    }

    @Test
    public void gcvObjectWithoutARecordIsInformation() {
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");
        Resource gcv = new Resource("NOVLADBASE-GCVs", Scope.DRIVER, "AD", Resource.GCV_DEF);
        gcv.content = ValidatorTest.xml("<configuration-values><definitions/></configuration-values>");
        gcv.meta.put("dirxml-pkgguid", "PKG-1");
        ad.resources.add(gcv);
        ad.links.add(new PolicyLink(PolicySet.GCV, "drivers/AD/NOVLADBASE-GCVs", 0));

        Report r = run(ds);
        assertEquals(r.text(), 1, r.findings().size());
        assertEquals("package-linkage-missing-gcv", r.findings().get(0).code);
        assertEquals(Finding.Severity.INFO, r.findings().get(0).severity);
        assertTrue(r.ok());
    }
}
