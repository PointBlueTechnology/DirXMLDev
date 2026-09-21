package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.AppConfigPolicy;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The generic AppConfig objects ({@code docs/appconfig.md} §2). Codes:
 * {@code appconfig-xml-invalid} (E: an XML-valued attribute does not parse),
 * {@code appconfig-entity-key-duplicate} (E: two attributes of an entity share a key),
 * {@code appconfig-role-level-mismatch} (E: {@code nrfRoleLevel} disagrees with the Level container),
 * {@code appconfig-ref-missing} (E: a DN-valued attribute names an object the tree does not have),
 * {@code appconfig-ref-outside} (I: a DN outside this driver's AppConfig, not checkable here),
 * {@code appconfig-localized-unparsable} (W: a localized string is not {@code lang~text|…}).
 */
public final class AppConfigCheck implements Check {

    /** DN-valued attributes that must name an object under the same AppConfig. */
    private static final Set<String> DN_ATTRS = Set.of(
        "nrfRequestDef", "nrfStdRequestDef", "nrfStdSODRequestDef", "nrfResourceGrantRequestDef",
        "nrfResourceRevokeRequestDef", "nrfRequestContainer", "nrfRolesContainer", "nrfResourcesContainer",
        "nrfResourceRequestContainer", "nrfReportContainer", "nrfSODContainer", "nrfPCRSRequestContainer");

    @Override
    public String name() {
        return "appconfig";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        for (Driver d : ds.drivers) {
            Provisioning p = d.provisioning;
            if (p == null || p.objects.isEmpty()) {
                continue;
            }
            for (AppObject o : p.objects) {
                check(d, p, o, r);
            }
        }
    }

    private static void check(Driver d, Provisioning p, AppObject o, Report r) {
        String path = "drivers/" + d.name + "/provisioning/objects/" + o.path();
        for (Map.Entry<String, List<String>> e : o.attrs.entrySet()) {
            String attr = e.getKey();
            for (String v : e.getValue()) {
                if (AppConfigPolicy.isXmlAttribute(attr)) {
                    Element root = parse(v);
                    if (root == null) {
                        r.add(Finding.error("appconfig-xml-invalid", path, attr + " is not well-formed XML"));
                    } else if (o.kind() == AppObject.Kind.ENTITY && attr.equalsIgnoreCase("XmlData")) {
                        checkEntity(root, path, r);
                    }
                }
                if (attr.endsWith("LocalizedNames") || attr.endsWith("LocalizedDescrs")) {
                    if (!AppConfigPolicy.isWellFormedLocalized(v)) {
                        r.add(Finding.warning("appconfig-localized-unparsable", path,
                            attr + " is not a lang~text|… string: " + abbreviate(v)));
                    }
                }
                if (DN_ATTRS.contains(attr)) {
                    checkRef(p, o, attr, v, path, r);
                }
            }
        }
        if (o.kind() == AppObject.Kind.ROLE) {
            String level = o.first("nrfRoleLevel");
            String container = o.segments.size() >= 4 ? o.segments.get(2) : "";
            if (level != null && container.toLowerCase(Locale.ROOT).startsWith("level")
                && !container.equalsIgnoreCase("Level" + level.trim())) {
                r.add(Finding.error("appconfig-role-level-mismatch", path,
                    "nrfRoleLevel " + level + " but the role sits in " + container));
            }
        }
    }

    private static void checkEntity(Element root, String path, Report r) {
        Element attrs = Xds.firstByName(root, "attributes");
        if (attrs == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Element a : Xds.childrenByName(attrs, "attribute")) {
            String key = a.getAttribute("key");
            if (!key.isEmpty() && !seen.add(key)) {
                r.add(Finding.error("appconfig-entity-key-duplicate", path, "entity attribute key '" + key + "' appears twice"));
            }
        }
    }

    private static void checkRef(Provisioning p, AppObject o, String attr, String dn, String path, Report r) {
        String appConfig = p.dn == null ? null : p.dn.toLowerCase(Locale.ROOT);
        String lower = dn.toLowerCase(Locale.ROOT);
        if (appConfig == null || !lower.endsWith("," + appConfig)) {
            r.add(Finding.info("appconfig-ref-outside", path, attr + " names " + dn + " (outside this AppConfig; not checked)"));
            return;
        }
        String below = dn.substring(0, dn.length() - appConfig.length() - 1);
        String[] comps = below.split(",");
        StringBuilder target = new StringBuilder();
        for (int i = comps.length - 1; i >= 0; i--) {
            String c = comps[i].trim();
            int eq = c.indexOf('=');
            if (target.length() > 0) {
                target.append('/');
            }
            target.append(eq < 0 ? c : c.substring(eq + 1));
        }
        String t = target.toString();
        if (p.object(t) != null) {
            return;
        }
        if (t.regionMatches(true, 0, "RequestDefs/", 0, "RequestDefs/".length())) {
            String prdName = t.substring("RequestDefs/".length());
            for (Prd prd : p.prds) {
                if (prd.name.equalsIgnoreCase(prdName)) {
                    return;
                }
            }
        }
        r.add(Finding.error("appconfig-ref-missing", path, attr + " names " + dn + " but the tree has no such object"));
    }

    private static Element parse(String xml) {
        try {
            return CanonicalXml.parse(xml).getDocumentElement();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String abbreviate(String v) {
        return v.length() > 60 ? v.substring(0, 60) + "…" : v;
    }
}
