package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * Designer's <em>installed</em> content checksum of an artifact in a tree:
 * the catalog recipe plus the names of the policy sets the object is linked
 * into, in Designer's {@code addPolicySetRefs} order. What {@code DirXML-pkgChecksum}
 * holds; inequality with the stored stamp means "customized".
 */
public final class InstalledChecksum {

    private InstalledChecksum() {
    }

    public static long of(DriverSet ds, Driver owner, Artifact a) {
        List<String> sets = linkedSetNames(ds, owner, a);
        if (a instanceof Policy) {
            Policy pol = (Policy) a;
            String cls = pol.policyKind() == Policy.Kind.XSLT ? PackageChecksum.STYLESHEET : PackageChecksum.RULE;
            return PackageChecksum.content(cls, a.name, PackageInstall.toNxsl(pol.content), null, null, sets);
        }
        Resource r = (Resource) a;
        if (r.isGcvDef()) {
            return PackageChecksum.gcv(a.name, PackageInstall.toNxsl(r.content), sets);
        }
        return PackageChecksum.content(PackageChecksum.RESOURCE, a.name, r.content == null ? null : PackageInstall.toNxsl(r.content),
            r.text, r.contentType, sets);
    }

    /** Set names in Designer's enumeration order; a Library object counts the sets of every driver linking it. */
    public static List<String> linkedSetNames(DriverSet ds, Driver owner, Artifact a) {
        List<String> out = new ArrayList<>();
        List<Driver> drivers = owner != null ? List.of(owner) : ds.drivers;
        for (PolicySet s : PackageInstall.ADD_POLICY_SET_REFS_ORDER) {
            for (Driver drv : drivers) {
                for (PolicyLink l : drv.links(s)) {
                    if (l.ref.equals(a.path())) {
                        out.add(PackageInstall.DESIGNER_SET_NAMES.get(s));
                    }
                }
            }
        }
        return out;
    }
}
