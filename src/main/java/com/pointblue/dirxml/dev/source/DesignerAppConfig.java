package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.DsObjectXml;
import com.pointblue.dirxml.dev.model.AppConfigPolicy;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Designer's own shapes for the AppConfig objects beyond forms and PRDs ({@code docs/appconfig.md}
 * §1, §8), in both directions:
 *
 * <ul>
 *   <li><b>inline</b>: objects nested as {@code ds-object}s in {@code .appconfig} (the stock
 *       entities, reports, nav items, auth types, web-app configs, and every container); XML-valued
 *       attributes are bare base64 there;</li>
 *   <li><b>{@code XmlData} files</b>: {@code .entity}, {@code .choice}, {@code .relation},
 *       {@code .configuration}, {@code .locale} — the attribute's document verbatim;</li>
 *   <li><b>{@code .attestation}</b>: a ds-object document;</li>
 *   <li><b>XMI</b>: {@code .role20} ({@code role:Role}) and {@code .rsrc} ({@code resource:ProvResource}),
 *       Designer's dialect for roles and resources, mapped attribute for attribute;</li>
 *   <li><b>{@code .roleconfig}</b>: {@code configuration:rolesConfig}, one element per attribute of
 *       {@code nrfConfiguration}.</li>
 * </ul>
 * Each file item has a {@code .digest} beside it; each container a {@code <container>} digest. The
 * digest {@code type} is the class, except Designer's pseudo types for the role catalog
 * ({@code nrfRoleLevel20}, {@code nrfRoleDefsLevel20}, {@code nrfRoleDefsLevel20-System},
 * {@code nrfResourceDefs-System}) and {@code srvprvLocales} for the locale configuration.
 */
public final class DesignerAppConfig {

    private DesignerAppConfig() {
    }

    public static final String ROLE_NS = "http://www.novell.com/prov/role";
    public static final String RESOURCE_NS = "http://www.novell.com/prov/resource";
    public static final String XMI_NS = "http://www.omg.org/XMI";
    public static final String ROLECONFIG_NS = "urn:roleConfiguration";

    /** Observed defaults the XMI files do not carry (idm254 and ig4, every stock role and resource). */
    public static final String DEFAULT_ROLE_STATUS = "50";
    public static final String DEFAULT_RESOURCE_ACTIVE = "FALSE";
    public static final String DEFAULT_RESOURCE_APR_OVERRIDE = "FALSE";

    /** File kinds Designer keeps as their own file beside a digest; everything else lives inline in {@code .appconfig}. */
    public static String fileExtension(AppObject o) {
        switch (o.kind()) {
            // Designer keeps the stock system definitions (srvprvEntityType 'S') inline and the packaged or
            // customer ones ('P') as files — the type is not in the file, so the storage tells it apart
            case ENTITY: return "S".equalsIgnoreCase(o.first("srvprvEntityType")) ? null : "entity";
            case CHOICE: return "S".equalsIgnoreCase(o.first("srvprvEntityType")) ? null : "choice";
            case RELATIONSHIP: return "relation";
            case DM_CONFIG: return "configuration";
            case ROLE: return "role20";
            case RESOURCE: return "rsrc";
            case ATTESTATION: return "attestation";
            case ROLE_CONFIG: return "roleconfig";
            case WEB_APP_CONFIG: return o.name().equalsIgnoreCase("locale-configuration") ? "locale" : null;
            default: return null;
        }
    }

    /** The file extensions this class reads (a directory scan uses it). */
    public static final List<String> FILE_EXTENSIONS = List.of(
        "entity", "choice", "relation", "configuration", "locale", "role20", "rsrc", "attestation", "roleconfig");

    /** A digest item type → the vault class (Designer's pseudo types for the role catalog included). */
    public static String classOfItemType(String type) {
        if (type == null) {
            return null;
        }
        if (type.startsWith("nrfRoleLevel")) {
            return "nrfRole";
        }
        if (type.equals("srvprvLocales")) {
            return "srvprvWebAppConfig";
        }
        return type;
    }

    /** A container digest type → the vault class. */
    public static String classOfContainerType(String type) {
        if (type == null) {
            return null;
        }
        if (type.startsWith("nrfRoleDefsLevel")) {
            return "nrfRoleDefs";
        }
        if (type.startsWith("nrfResourceDefs-")) {
            return "nrfResourceDefs";
        }
        return type;
    }

    /** The digest item type Designer gives an object: its class, or the role catalog's pseudo type. */
    public static String itemType(AppObject o) {
        if (o.kind() == AppObject.Kind.ROLE) {
            String level = o.first("nrfRoleLevel");
            return "nrfRoleLevel" + (level == null ? "" : level.trim());
        }
        if (o.kind() == AppObject.Kind.WEB_APP_CONFIG && o.name().equalsIgnoreCase("locale-configuration")) {
            return "srvprvLocales";
        }
        return o.structuralClass();
    }

    /** The container digest type Designer gives a container: the class, or the role catalog's pseudo type. */
    public static String containerType(AppObject c) {
        String cls = c.structuralClass();
        List<String> s = c.segments;
        if ("nrfRoleDefs".equalsIgnoreCase(cls) && s.size() >= 3 && s.get(2).toLowerCase(Locale.ROOT).startsWith("level")) {
            String level = s.get(2).substring("level".length());
            return s.size() == 3 ? "nrfRoleDefsLevel" + level : "nrfRoleDefsLevel" + level + "-" + s.get(3);
        }
        if ("nrfResourceDefs".equalsIgnoreCase(cls) && s.size() >= 3) {
            return "nrfResourceDefs-" + s.get(2);
        }
        return cls;
    }

    // ------------------------------------------------------------------------------------
    // inline (.appconfig)
    // ------------------------------------------------------------------------------------

    /** Every object nested below the AppConfig root of a {@code .appconfig} document, forms and PRDs excluded. */
    public static List<AppObject> readInline(Document appConfigDoc) {
        List<AppObject> out = new ArrayList<>();
        Element root = appConfigDoc.getDocumentElement();
        Element appConfig = root.getNodeName().equals("ds-object") ? root : Xds.firstByName(root, "ds-object");
        if (appConfig == null) {
            return out;
        }
        walkInline(appConfig, new ArrayList<>(), out);
        return out;
    }

    private static void walkInline(Element dsObject, List<String> parent, List<AppObject> out) {
        for (Element child : Xds.childrenByName(dsObject, "ds-object")) {
            String cls = child.getAttribute("ds-object-class");
            if (isFormsMachinery(cls)) {
                continue;
            }
            List<String> segments = new ArrayList<>(parent);
            segments.add(child.getAttribute("ds-object-name"));
            AppObject o = inlineObject(child, segments);
            o.meta.put("project.storage", "inline");
            out.add(o);
            walkInline(child, segments, out);
        }
    }

    static boolean isFormsMachinery(String cls) {
        return cls.equalsIgnoreCase("srvprvRequest") || cls.equalsIgnoreCase("srvprvRequestDefs")
            || cls.equalsIgnoreCase("srvprvJSONForm") || cls.equalsIgnoreCase("srvprvJSONForms")
            || cls.equalsIgnoreCase("srvprvJSONRequestForm") || cls.equalsIgnoreCase("srvprvJSONApprovalForm")
            || cls.equalsIgnoreCase("srvprvJSONTemplateForm");
    }

    /** One inline ds-object: XML-valued attributes decoded from base64 when they are, names canonical. */
    static AppObject inlineObject(Element dsObject, List<String> segments) {
        AppObject o = new AppObject(segments);
        String cls = dsObject.getAttribute("ds-object-class");
        o.classes.add("Top");
        o.classes.add(cls);
        Element attrs = Xds.firstByName(dsObject, "ds-attributes");
        if (attrs != null) {
            for (Element a : Xds.childrenByName(attrs, "ds-attribute")) {
                String name = AppConfigPolicy.canonicalAttribute(a.getAttribute("ds-attr-name"));
                if (AppConfigPolicy.isNotContent(name)) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (Element v : Xds.childrenByName(a, "ds-value")) {
                    values.add(decodeValue(name, v));
                }
                if (!values.isEmpty()) {
                    o.put(name, values);
                }
            }
        }
        for (String stamp : new String[] {"package-id", "pkg-assoc-id", "checksum", "guid"}) {
            String v = dsObject.getAttribute(stamp);
            if (!v.isEmpty()) {
                o.meta.put(stamp.equals("guid") ? "project.guid" : stamp.equals("checksum") ? "pkg-checksum" : stamp, v);
            }
        }
        o.meta.put("objectClass", cls);
        // Designer keeps no ACLs inline (the vault's application permissions on nav items and reports), and no
        // entity type on a choice list: neither is an opinion of the project's
        o.meta.put(AppConfigPolicy.ABSENT_ATTRS_META, cls.equalsIgnoreCase("srvprvChoice") ? "ACL,srvprvEntityType" : "ACL");
        return o;
    }

    /** A stamped object is a {@code DirXML-PkgItemAux} one in the vault; a project records the stamps, not the class. */
    public static void completeClasses(AppObject o) {
        if (com.pointblue.dirxml.dev.model.PackageStamps.isPackaged(o.meta)
            && o.classes.stream().noneMatch(c -> c.equalsIgnoreCase("DirXML-PkgItemAux"))) {
            o.classes.add("DirXML-PkgItemAux");
            o.classes.sort(String.CASE_INSENSITIVE_ORDER);
        }
    }

    /** A ds-value's text, base64-decoded when marked or when an XML attribute's value is bare base64. */
    static String decodeValue(String attr, Element dsValue) {
        String text = textOf(dsValue);
        boolean marked = "true".equalsIgnoreCase(dsValue.getAttribute("base64-encoded"));
        if (marked || (AppConfigPolicy.isXmlAttribute(attr) && text.indexOf('<') < 0 && !text.isBlank())) {
            try {
                String decoded = new String(Base64.getDecoder().decode(text.replaceAll("\\s", "")), StandardCharsets.UTF_8);
                String xml = AppConfigPolicy.isXmlAttribute(attr) ? DsObjectXml.asXml(decoded) : null;
                return xml != null ? xml : decoded.replace("\r\n", "\n");
            } catch (IllegalArgumentException e) {
                // not base64 after all: the text as it is
            }
        }
        String xml = AppConfigPolicy.isXmlAttribute(attr) ? DsObjectXml.asXml(text) : null;
        return xml != null ? xml : text;
    }

    private static String textOf(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                return CanonicalXml.serialize((Element) n);
            }
        }
        return sb.toString();
    }

    /**
     * Finds (or, with {@code create}, makes — parents included, from {@code allObjects} when the tree
     * knows the container's class) the inline ds-object for {@code segments} under the AppConfig root.
     */
    public static Element inlineElement(Document doc, List<String> segments, boolean create, List<AppObject> allObjects) {
        Element root = doc.getDocumentElement();
        Element cur = root.getNodeName().equals("ds-object") ? root : Xds.firstByName(root, "ds-object");
        if (cur == null) {
            return null;
        }
        List<String> sofar = new ArrayList<>();
        for (String seg : segments) {
            sofar.add(seg);
            Element next = null;
            for (Element c : Xds.childrenByName(cur, "ds-object")) {
                if (c.getAttribute("ds-object-name").equalsIgnoreCase(seg)) {
                    next = c;
                    break;
                }
            }
            if (next == null) {
                if (!create) {
                    return null;
                }
                String cls = "Top";
                if (allObjects != null) {
                    for (AppObject o : allObjects) {
                        if (o.segments.equals(sofar)) {
                            cls = o.structuralClass();
                            break;
                        }
                    }
                }
                next = doc.createElementNS(null, "ds-object");
                next.setAttribute("ds-object-class", cls);
                next.setAttribute("ds-object-name", seg);
                next.appendChild(doc.createElementNS(null, "ds-attributes"));
                cur.appendChild(next);
            }
            cur = next;
        }
        return cur;
    }

    /** Writes {@code o}'s class and attributes into its inline ds-object (replacing what was there); children stay. */
    public static void writeInline(Document doc, Element dsObject, AppObject o) {
        dsObject.setAttribute("ds-object-class", o.structuralClass());
        Element attrs = Xds.firstByName(dsObject, "ds-attributes");
        if (attrs == null) {
            attrs = doc.createElementNS(null, "ds-attributes");
            dsObject.insertBefore(attrs, dsObject.getFirstChild());
        }
        while (attrs.getFirstChild() != null) {
            attrs.removeChild(attrs.getFirstChild());
        }
        for (Map.Entry<String, List<String>> e : o.attrs.entrySet()) {
            if (AppConfigPolicy.isOperational(e.getKey())) {
                continue;
            }
            Element a = doc.createElementNS(null, "ds-attribute");
            a.setAttribute("ds-attr-name", designerAttrName(e.getKey()));
            for (String v : e.getValue()) {
                Element dv = doc.createElementNS(null, "ds-value");
                if (AppConfigPolicy.isXmlAttribute(e.getKey())) {
                    String xml = DsObjectXml.asXml(v);
                    dv.appendChild(doc.createTextNode(Base64.getEncoder().encodeToString((xml != null ? xml : v).getBytes(StandardCharsets.UTF_8))));
                } else if (needsBase64(v)) {
                    dv.setAttribute("base64-encoded", "true");
                    dv.appendChild(doc.createCDATASection(Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))));
                } else {
                    dv.appendChild(doc.createTextNode(v));
                }
                a.appendChild(dv);
            }
            attrs.appendChild(a);
        }
        for (String stamp : new String[] {"package-id", "pkg-assoc-id"}) {
            String v = stamp.equals("package-id") ? com.pointblue.dirxml.dev.model.PackageStamps.packageId(o.meta)
                : com.pointblue.dirxml.dev.model.PackageStamps.assocId(o.meta);
            if (v != null) {
                dsObject.setAttribute(stamp, v);
            }
        }
        String checksum = com.pointblue.dirxml.dev.model.PackageStamps.checksum(o.meta);
        if (checksum != null) {
            dsObject.setAttribute("checksum", checksum);
        }
    }

    /** Designer spells {@code description} as {@code Description} in ds-objects (the NDS name). */
    static String designerAttrName(String canonical) {
        return canonical.equalsIgnoreCase("description") ? "Description" : canonical;
    }

    private static boolean needsBase64(String v) {
        return v.startsWith(" ") || v.startsWith("\n") || v.endsWith(" ") || v.endsWith("\n") || v.indexOf('\r') >= 0;
    }

    // ------------------------------------------------------------------------------------
    // file items
    // ------------------------------------------------------------------------------------

    /** Reads a file item; {@code digestType} is the digest's {@code type}; null when the extension is not one of ours. */
    public static AppObject readItem(String ext, String fileText, String digestType, List<String> segments, Element digest) {
        AppObject o;
        switch (ext) {
            case "entity":
            case "choice":
            case "relation":
            case "configuration":
            case "locale": {
                o = new AppObject(segments);
                String cls = classOfItemType(digestType);
                if (cls == null || cls.isEmpty()) {
                    cls = ext.equals("entity") ? "srvprvEntity" : ext.equals("choice") ? "srvprvChoice"
                        : ext.equals("relation") ? "srvprvRelationship" : ext.equals("configuration") ? "srvprvDirectoryModelConfig"
                        : "srvprvWebAppConfig";
                }
                o.classes.add("Top");
                o.classes.add(cls);
                String xml = DsObjectXml.asXml(fileText);
                o.put("XmlData", List.of(xml != null ? xml : fileText));
                if (ext.equals("entity")) {
                    o.put("srvprvEntityType", List.of("P"));   // a file entity is a packaged/custom one (the vault's 'P'; inline stock ones are 'S')
                }
                break;
            }
            case "attestation": {
                // a ds-object document with Designer's inline conventions (XmlData as bare base64)
                o = inlineObject(CanonicalXml.parse(fileText).getDocumentElement(), segments);
                o.meta.remove(AppConfigPolicy.ABSENT_ATTRS_META);
                break;
            }
            case "role20":
                o = roleFromXmi(fileText, segments);
                break;
            case "rsrc":
                o = resourceFromXmi(fileText, segments);
                break;
            case "roleconfig":
                o = roleConfigFromXml(fileText, segments);
                break;
            default:
                return null;
        }
        o.meta.put("project.storage", "file");
        o.meta.put("objectClass", o.structuralClass());
        // what the file form cannot carry: the vault's description of an XmlData item, a choice's entity
        // type, and the ACLs on anything but a role (whose trustees are its ACL)
        switch (ext) {
            case "entity":
                o.meta.put(AppConfigPolicy.ABSENT_ATTRS_META, "description,ACL");
                break;
            case "choice":
                o.meta.put(AppConfigPolicy.ABSENT_ATTRS_META, "description,ACL,srvprvEntityType");
                break;
            case "relation":
            case "configuration":
            case "locale":
                o.meta.put(AppConfigPolicy.ABSENT_ATTRS_META, "description,ACL");
                break;
            case "role20":
                break;
            default:
                o.meta.put(AppConfigPolicy.ABSENT_ATTRS_META, "ACL");
        }
        return o;
    }

    private static String digestDescr(Element digest) {
        String en = null;
        String any = null;
        for (Element d : Xds.childrenByName(digest, "descr")) {
            String lang = d.getAttribute("xml:lang");
            String t = d.getTextContent();
            if (any == null) {
                any = t;
            }
            if ("en".equals(lang)) {
                en = t;
            }
        }
        return en != null ? en : any;
    }

    /** The file content for a file-kind object, or null when the kind lives inline. */
    public static String writeItem(AppObject o) {
        String ext = fileExtension(o);
        if (ext == null) {
            return null;
        }
        switch (ext) {
            case "entity":
            case "choice":
            case "relation":
            case "configuration":
            case "locale": {
                String xml = o.first("XmlData");
                return xml == null ? "" : xml;
            }
            case "attestation":
                return attestationDoc(o);
            case "role20":
                return roleToXmi(o);
            case "rsrc":
                return resourceToXmi(o);
            case "roleconfig":
                return roleConfigToXml(o);
            default:
                return null;
        }
    }

    private static String attestationDoc(AppObject o) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ds-object ds-object-class=\"")
            .append(esc(o.structuralClass())).append("\" ds-object-name=\"").append(esc(o.name())).append("\">\n  <ds-attributes>\n");
        sb.append("    <ds-attribute ds-attr-name=\"CN\"><ds-value>").append(esc(o.name())).append("</ds-value></ds-attribute>\n");
        for (Map.Entry<String, List<String>> e : o.attrs.entrySet()) {
            if (AppConfigPolicy.isOperational(e.getKey())) {
                continue;
            }
            sb.append("    <ds-attribute ds-attr-name=\"").append(esc(e.getKey())).append("\">");
            for (String v : e.getValue()) {
                String xml = AppConfigPolicy.isXmlAttribute(e.getKey()) ? DsObjectXml.asXml(v) : null;
                if (xml != null) {
                    sb.append("<ds-value>").append(stripDecl(xml)).append("</ds-value>");
                } else {
                    sb.append("<ds-value>").append(esc(v)).append("</ds-value>");
                }
            }
            sb.append("</ds-attribute>\n");
        }
        return sb.append("  </ds-attributes>\n</ds-object>\n").toString();
    }

    // ---- roles (.role20) ----

    /**
     * {@code role:Role} → {@code nrfRole}: {@code id="cn=X"}, {@code roleLevel="Level20"} → {@code nrfRoleLevel},
     * {@code localizedName/Description(label, locale)} → the {@code lang~text|…} strings, {@code categoryKey} →
     * {@code nrfRoleCategoryKey}, {@code owner dn} → {@code owner}, {@code implicitGroup dn} → {@code nrfImplicitGroups},
     * {@code trustee dn="T#right"} → the ACL value {@code 4#entry#T#right}, {@code nrfStatus} defaulted to 50.
     */
    static AppObject roleFromXmi(String xml, List<String> segments) {
        Element root = CanonicalXml.parse(xml).getDocumentElement();
        AppObject o = new AppObject(segments);
        o.classes.add("Top");
        o.classes.add("nrfRole");
        String level = root.getAttribute("roleLevel");
        if (level.toLowerCase(Locale.ROOT).startsWith("level")) {
            level = level.substring("level".length());
        }
        if (!level.isEmpty()) {
            o.put("nrfRoleLevel", List.of(level));
        }
        localizedFromXmi(root, o, "nrfLocalizedNames", "nrfLocalizedDescrs");
        putChildTexts(root, "categoryKey", o, "nrfRoleCategoryKey");
        putChildDns(root, "owner", o, "owner");
        putChildDns(root, "implicitGroup", o, "nrfImplicitGroups");
        putChildDns(root, "implicitContainer", o, "nrfImplicitContainers");
        putChildDns(root, "approver", o, "nrfApprovers");
        putChildDns(root, "childRole", o, "nrfChildRoles");
        List<String> acl = new ArrayList<>();
        for (Element t : childrenLocal(root, "trustee")) {
            String dn = t.getAttribute("dn");
            int hash = dn.lastIndexOf('#');
            if (hash > 0) {
                acl.add("4#entry#" + dn.substring(0, hash) + "#" + dn.substring(hash + 1));
            }
        }
        if (!acl.isEmpty()) {
            acl.sort(null);
            o.put("ACL", acl);
        }
        String quorum = childText(root, "quorum");
        if (quorum != null) {
            o.put("nrfQuorum", List.of(quorum));
        }
        o.put("nrfStatus", List.of(DEFAULT_ROLE_STATUS));
        return o;
    }

    static String roleToXmi(AppObject o) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<role:Role id=\"cn=")
            .append(esc(o.name())).append("\" roleLevel=\"Level").append(esc(nz(o.first("nrfRoleLevel"))))
            .append("\" xmi:version=\"2.0\" xmlns:role=\"").append(ROLE_NS).append("\" xmlns:xmi=\"").append(XMI_NS).append("\">");
        localizedToXmi(sb, o.first("nrfLocalizedDescrs"), "localizedDescription");
        localizedToXmi(sb, o.first("nrfLocalizedNames"), "localizedName");
        for (String v : o.all("ACL")) {
            String[] p = v.split("#", 4);
            if (p.length == 4 && p[1].equalsIgnoreCase("entry") && p[3].startsWith("nrfAccess")) {
                sb.append("<trustee dn=\"").append(esc(p[2] + "#" + p[3])).append("\"/>");
            }
        }
        for (String v : o.all("owner")) {
            sb.append("<owner dn=\"").append(esc(v)).append("\"/>");
        }
        for (String v : o.all("nrfImplicitGroups")) {
            sb.append("<implicitGroup dn=\"").append(esc(v)).append("\"/>");
        }
        for (String v : o.all("nrfImplicitContainers")) {
            sb.append("<implicitContainer dn=\"").append(esc(v)).append("\"/>");
        }
        for (String v : o.all("nrfApprovers")) {
            sb.append("<approver dn=\"").append(esc(v)).append("\"/>");
        }
        for (String v : o.all("nrfChildRoles")) {
            sb.append("<childRole dn=\"").append(esc(v)).append("\"/>");
        }
        String quorum = o.first("nrfQuorum");
        if (quorum != null) {
            sb.append("<quorum>").append(esc(quorum)).append("</quorum>");
        }
        for (String v : o.all("nrfRoleCategoryKey")) {
            sb.append("<categoryKey>").append(esc(v)).append("</categoryKey>");
        }
        return sb.append("</role:Role>\n").toString();
    }

    // ---- resources (.rsrc) ----

    /**
     * {@code resource:ProvResource} → {@code nrfResource}: {@code allowMultipleAssignment} → {@code nrfAllowMulti},
     * {@code categoryKey} → {@code nrfCategoryKey}, {@code resourceParameter(binding, codeMapKey, key, type,
     * localizedDisplay)} → the {@code nrfResourceParms} document, {@code entitlement(dn, referenceXML)} →
     * {@code nrfEntitlementRef} ({@code dn#0#<ref>}), {@code owner dn} → {@code owner};
     * {@code nrfActive}/{@code nrfAllowAprOveride} defaulted to FALSE.
     */
    static AppObject resourceFromXmi(String xml, List<String> segments) {
        Element root = CanonicalXml.parse(xml).getDocumentElement();
        AppObject o = new AppObject(segments);
        o.classes.add("Top");
        o.classes.add("nrfResource");
        localizedFromXmi(root, o, "nrfLocalizedNames", "nrfLocalizedDescrs");
        putChildTexts(root, "categoryKey", o, "nrfCategoryKey");
        putChildDns(root, "owner", o, "owner");
        putChildDns(root, "approver", o, "nrfApprovers");
        o.put("nrfAllowMulti", List.of("true".equalsIgnoreCase(root.getAttribute("allowMultipleAssignment")) ? "TRUE" : "FALSE"));
        o.put("nrfAllowAprOveride", List.of("true".equalsIgnoreCase(root.getAttribute("allowApprovalOverride")) ? "TRUE" : DEFAULT_RESOURCE_APR_OVERRIDE));
        o.put("nrfActive", List.of("true".equalsIgnoreCase(root.getAttribute("active")) ? "TRUE" : DEFAULT_RESOURCE_ACTIVE));
        List<Element> params = childrenLocal(root, "resourceParameter");
        if (!params.isEmpty()) {
            StringBuilder sb = new StringBuilder("<parameters>");
            for (Element p : params) {
                sb.append("<parameter binding=\"").append(esc(attrOr(p, "binding", "static")))
                    .append("\" hide=\"").append(esc(attrOr(p, "hide", "false")))
                    .append("\" instance=\"").append(esc(attrOr(p, "instance", "false")))
                    .append("\" multivalue=\"").append(esc(attrOr(p, "multivalue", "false")))
                    .append("\" scope=\"").append(esc(attrOr(p, "scope", "request"))).append("\">");
                sb.append("<key>").append(esc(p.getAttribute("key"))).append("</key>");
                for (Element d : childrenLocal(p, "localizedDisplay")) {
                    sb.append("<display xml:lang=\"").append(esc(d.getAttribute("locale"))).append("\"><label>")
                        .append(esc(d.getAttribute("label"))).append("</label></display>");
                }
                if (!p.getAttribute("type").isEmpty()) {
                    sb.append("<type>").append(esc(p.getAttribute("type"))).append("</type>");
                }
                if (!p.getAttribute("codeMapKey").isEmpty()) {
                    sb.append("<code-map-key>").append(esc(p.getAttribute("codeMapKey"))).append("</code-map-key>");
                }
                if (!p.getAttribute("value").isEmpty()) {
                    sb.append("<value>").append(esc(p.getAttribute("value"))).append("</value>");
                }
                sb.append("</parameter>");
            }
            sb.append("</parameters>");
            o.put("nrfResourceParms", List.of(CanonicalXml.canonicalize(sb.toString())));
        }
        List<String> refs = new ArrayList<>();
        for (Element e : childrenLocal(root, "entitlement")) {
            String ref = e.getAttribute("referenceXML");
            String refXml = DsObjectXml.asXml(ref);
            refs.add(e.getAttribute("dn") + "#0#" + (refXml != null ? stripDecl(refXml).trim() : ref));
        }
        if (!refs.isEmpty()) {
            o.put("nrfEntitlementRef", refs);
        }
        return o;
    }

    static String resourceToXmi(AppObject o) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resource:ProvResource");
        if ("TRUE".equalsIgnoreCase(o.first("nrfAllowMulti"))) {
            sb.append(" allowMultipleAssignment=\"true\"");
        }
        if ("TRUE".equalsIgnoreCase(o.first("nrfAllowAprOveride"))) {
            sb.append(" allowApprovalOverride=\"true\"");
        }
        if ("TRUE".equalsIgnoreCase(o.first("nrfActive"))) {
            sb.append(" active=\"true\"");
        }
        sb.append(" id=\"cn=").append(esc(o.name())).append("\" xmi:version=\"2.0\" xmlns:resource=\"").append(RESOURCE_NS)
            .append("\" xmlns:xmi=\"").append(XMI_NS).append("\">");
        localizedToXmi(sb, o.first("nrfLocalizedDescrs"), "localizedDescription");
        localizedToXmi(sb, o.first("nrfLocalizedNames"), "localizedName");
        for (String v : o.all("nrfEntitlementRef")) {
            int a = v.indexOf('#');
            int b = a < 0 ? -1 : v.indexOf('#', a + 1);
            String dn = a < 0 ? v : v.substring(0, a);
            String ref = b < 0 ? "" : v.substring(b + 1);
            String param = "";
            try {
                Element r = CanonicalXml.parse(ref).getDocumentElement();
                param = nz(childText(r, "param"));
            } catch (RuntimeException e) {
                // no parameter to show
            }
            sb.append("<entitlement dn=\"").append(esc(dn)).append("\" driver=\"\" parameter=\"").append(esc(param))
                .append("\" referenceXML=\"").append(esc("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + ref + "\n")).append("\"/>");
        }
        for (String v : o.all("owner")) {
            sb.append("<owner dn=\"").append(esc(v)).append("\"/>");
        }
        for (String v : o.all("nrfApprovers")) {
            sb.append("<approver dn=\"").append(esc(v)).append("\"/>");
        }
        for (String v : o.all("nrfCategoryKey")) {
            sb.append("<categoryKey>").append(esc(v)).append("</categoryKey>");
        }
        String parms = o.first("nrfResourceParms");
        if (parms != null) {
            try {
                Element ps = CanonicalXml.parse(parms).getDocumentElement();
                for (Element p : Xds.childrenByName(ps, "parameter")) {
                    sb.append("<resourceParameter");
                    for (String a : new String[] {"binding"}) {
                        if (!p.getAttribute(a).isEmpty()) {
                            sb.append(' ').append(a).append("=\"").append(esc(p.getAttribute(a))).append('"');
                        }
                    }
                    String cmk = childText(p, "code-map-key");
                    if (cmk != null) {
                        sb.append(" codeMapKey=\"").append(esc(cmk)).append('"');
                    }
                    sb.append(" key=\"").append(esc(nz(childText(p, "key")))).append('"');
                    String type = childText(p, "type");
                    if (type != null) {
                        sb.append(" type=\"").append(esc(type)).append('"');
                    }
                    String value = childText(p, "value");
                    if (value != null) {
                        sb.append(" value=\"").append(esc(value)).append('"');
                    }
                    sb.append('>');
                    for (Element d : Xds.childrenByName(p, "display")) {
                        sb.append("<localizedDisplay label=\"").append(esc(nz(childText(d, "label"))))
                            .append("\" locale=\"").append(esc(d.getAttribute("xml:lang"))).append("\"/>");
                    }
                    sb.append("</resourceParameter>");
                }
            } catch (RuntimeException e) {
                // an unparsable parameter document: nothing Designer could show
            }
        }
        return sb.append("</resource:ProvResource>\n").toString();
    }

    // ---- role configuration (.roleconfig) ----

    /** {@code configuration:rolesConfig}: every child element is the attribute of that name; role levels and the entitlement default are structured. */
    static AppObject roleConfigFromXml(String xml, List<String> segments) {
        Element root = CanonicalXml.parse(xml).getDocumentElement();
        AppObject o = new AppObject(segments);
        o.classes.add("Top");
        o.classes.add("nrfConfiguration");
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            String name = local(e);
            if (name.equals("cn")) {
                continue;
            }
            if (name.equals("nrfRoleLevels")) {
                StringBuilder sb = new StringBuilder("<xml>\n");
                Element lx = firstLocal(e, "localizedXML");
                if (lx != null) {
                    for (Element d : childrenLocal(lx, "display-name")) {
                        sb.append("<display-name xml:lang=\"").append(esc(d.getAttribute("xml:lang"))).append("\">")
                            .append(esc(d.getTextContent())).append("</display-name>\n");
                    }
                    for (Element d : childrenLocal(lx, "description")) {
                        sb.append("<description xml:lang=\"").append(esc(d.getAttribute("xml:lang"))).append("\">")
                            .append(esc(d.getTextContent())).append("</description>\n");
                    }
                }
                sb.append("</xml>\n");   // the vault's value ends with a newline
                values.computeIfAbsent("nrfRoleLevels", k -> new ArrayList<>())
                    .add(nz(childText(e, "containerDN")) + "#" + nz(childText(e, "roleLevel")) + "#" + sb);
            } else if (name.equals("nrfEntitlementConfigDefault")) {
                StringBuilder sb = new StringBuilder("<xml xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"roles.xsd\"><query-config>");
                String rr = childText(e, "refresh-rate");
                if (rr != null) {
                    sb.append("<refresh-rate>").append(esc(rr)).append("</refresh-rate>");
                }
                String qt = childText(e, "query-timeout");
                if (qt != null) {
                    sb.append("<nds-timeout>").append(esc(qt)).append("</nds-timeout>");
                }
                sb.append("<codemap-display concat=\"").append(esc(nz(childText(e, "concat")).isEmpty() ? "false" : childText(e, "concat"))).append("\">");
                for (Element r : childrenLocal(e, "codemap-display-result-element")) {
                    sb.append("<result-element>").append(esc(r.getTextContent())).append("</result-element>");
                }
                sb.append("</codemap-display></query-config></xml>");
                values.put("nrfEntitlementConfigDefault", List.of(CanonicalXml.canonicalize(sb.toString())));
            } else {
                values.computeIfAbsent(AppConfigPolicy.canonicalAttribute(name), k -> new ArrayList<>()).add(e.getTextContent());
            }
        }
        for (Map.Entry<String, List<String>> v : values.entrySet()) {
            List<String> vs = v.getValue();
            if (vs.size() > 1) {
                vs.sort(null);
            }
            o.put(v.getKey(), vs);
        }
        return o;
    }

    static String roleConfigToXml(AppObject o) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<configuration:rolesConfig xmlns:configuration=\"")
            .append(ROLECONFIG_NS).append("\"><configuration:cn>cn=").append(esc(o.name())).append("</configuration:cn>");
        for (Map.Entry<String, List<String>> e : o.attrs.entrySet()) {
            String name = e.getKey();
            if (AppConfigPolicy.isOperational(name)) {
                continue;
            }
            for (String v : e.getValue()) {
                if (name.equals("nrfRoleLevels")) {
                    int a = v.indexOf('#');
                    int b = a < 0 ? -1 : v.indexOf('#', a + 1);
                    sb.append("<configuration:nrfRoleLevels><configuration:containerDN>").append(esc(a < 0 ? v : v.substring(0, a)))
                        .append("</configuration:containerDN><configuration:roleLevel>").append(esc(b < 0 ? "" : v.substring(a + 1, b)))
                        .append("</configuration:roleLevel><configuration:localizedXML>");
                    if (b >= 0) {
                        try {
                            Element x = CanonicalXml.parse(v.substring(b + 1)).getDocumentElement();
                            for (Element d : Xds.childrenByName(x, "display-name")) {
                                sb.append("<configuration:display-name xml:lang=\"").append(esc(d.getAttribute("xml:lang"))).append("\">")
                                    .append(esc(d.getTextContent())).append("</configuration:display-name>");
                            }
                            for (Element d : Xds.childrenByName(x, "description")) {
                                sb.append("<configuration:description xml:lang=\"").append(esc(d.getAttribute("xml:lang"))).append("\">")
                                    .append(esc(d.getTextContent())).append("</configuration:description>");
                            }
                        } catch (RuntimeException ex) {
                            // no display names to carry
                        }
                    }
                    sb.append("</configuration:localizedXML></configuration:nrfRoleLevels>");
                } else if (name.equals("nrfEntitlementConfigDefault")) {
                    sb.append("<configuration:nrfEntitlementConfigDefault>");
                    try {
                        Element x = CanonicalXml.parse(v).getDocumentElement();
                        Element q = Xds.firstByName(x, "query-config");
                        Element base = q == null ? x : q;
                        String rr = childText(base, "refresh-rate");
                        if (rr != null) {
                            sb.append("<configuration:refresh-rate>").append(esc(rr)).append("</configuration:refresh-rate>");
                        }
                        String nt = childText(base, "nds-timeout");
                        if (nt != null) {
                            sb.append("<configuration:query-timeout>").append(esc(nt)).append("</configuration:query-timeout>");
                        }
                        Element cm = Xds.firstByName(base, "codemap-display");
                        if (cm != null) {
                            sb.append("<configuration:concat>").append(esc(attrOr(cm, "concat", "false"))).append("</configuration:concat>");
                            for (Element r : Xds.childrenByName(cm, "result-element")) {
                                sb.append("<configuration:codemap-display-result-element>").append(esc(r.getTextContent()))
                                    .append("</configuration:codemap-display-result-element>");
                            }
                        }
                    } catch (RuntimeException ex) {
                        // unparsable: nothing to carry
                    }
                    sb.append("</configuration:nrfEntitlementConfigDefault>");
                } else {
                    sb.append("<configuration:").append(name).append('>').append(esc(v)).append("</configuration:").append(name).append('>');
                }
            }
        }
        return sb.append("</configuration:rolesConfig>\n").toString();
    }

    // ---- digests ----

    /** Display names for a digest: the localized-names attribute, else the XmlData's {@code display/label}s, else the description. */
    public static Map<String, String> displayNames(AppObject o) {
        String names = o.first("nrfLocalizedNames");
        if (names == null) {
            names = o.first("srvprvLocalizedNames");
        }
        if (names != null) {
            return AppConfigPolicy.localized(names);
        }
        Map<String, String> out = new LinkedHashMap<>();
        String xml = o.first("XmlData");
        if (xml != null) {
            try {
                Element root = CanonicalXml.parse(xml).getDocumentElement();
                Element scope = root;
                Element inner = Xds.firstByName(root, "entity");
                if (inner != null && Xds.childrenByName(inner, "display").size() > 0) {
                    scope = inner;
                }
                for (Element d : Xds.childrenByName(scope, "display")) {
                    String label = childText(d, "label");
                    if (label == null) {
                        label = d.getTextContent().trim();
                    }
                    if (!label.isEmpty()) {
                        out.put(d.getAttribute("xml:lang"), label);
                    }
                }
            } catch (RuntimeException e) {
                // no display in the document
            }
        }
        if (out.isEmpty()) {
            String d = o.first("description");
            out.put("en", d == null ? o.name() : d);
        }
        return out;
    }

    public static Map<String, String> descriptions(AppObject o) {
        String d = o.first("nrfLocalizedDescrs");
        if (d == null) {
            d = o.first("srvprvLocalizedDescrs");
        }
        if (d != null) {
            return AppConfigPolicy.localized(d);
        }
        Map<String, String> out = new LinkedHashMap<>();
        String descr = o.first("description");
        if (descr != null) {
            out.put("en", descr);
        }
        return out;
    }

    // ---- helpers ----

    private static void localizedFromXmi(Element root, AppObject o, String namesAttr, String descrsAttr) {
        StringBuilder names = new StringBuilder();
        for (Element e : childrenLocal(root, "localizedName")) {
            names.append(names.length() == 0 ? "" : "|").append(e.getAttribute("locale")).append('~').append(e.getAttribute("label"));
        }
        StringBuilder descrs = new StringBuilder();
        for (Element e : childrenLocal(root, "localizedDescription")) {
            descrs.append(descrs.length() == 0 ? "" : "|").append(e.getAttribute("locale")).append('~').append(e.getAttribute("label"));
        }
        if (names.length() > 0) {
            o.put(namesAttr, List.of(names.toString()));
        }
        if (descrs.length() > 0) {
            o.put(descrsAttr, List.of(descrs.toString()));
        }
    }

    private static void localizedToXmi(StringBuilder sb, String value, String element) {
        for (Map.Entry<String, String> e : AppConfigPolicy.localized(value).entrySet()) {
            sb.append('<').append(element).append(" label=\"").append(esc(e.getValue())).append("\" locale=\"").append(esc(e.getKey())).append("\"/>");
        }
    }

    private static void putChildTexts(Element root, String local, AppObject o, String attr) {
        List<String> vs = new ArrayList<>();
        for (Element e : childrenLocal(root, local)) {
            vs.add(e.getTextContent());
        }
        if (!vs.isEmpty()) {
            vs.sort(null);
            o.put(attr, vs);
        }
    }

    private static void putChildDns(Element root, String local, AppObject o, String attr) {
        List<String> vs = new ArrayList<>();
        for (Element e : childrenLocal(root, local)) {
            vs.add(e.getAttribute("dn"));
        }
        if (!vs.isEmpty()) {
            vs.sort(null);
            o.put(attr, vs);
        }
    }

    static List<Element> childrenLocal(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && local((Element) n).equals(local)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static Element firstLocal(Element parent, String local) {
        List<Element> c = childrenLocal(parent, local);
        return c.isEmpty() ? null : c.get(0);
    }

    static String childText(Element parent, String local) {
        Element c = firstLocal(parent, local);
        return c == null ? null : c.getTextContent();
    }

    static String local(Element e) {
        String n = e.getLocalName();
        if (n == null) {
            n = e.getNodeName();
            int colon = n.indexOf(':');
            n = colon < 0 ? n : n.substring(colon + 1);
        }
        return n;
    }

    private static String attrOr(Element e, String name, String dflt) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? dflt : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String stripDecl(String xml) {
        return xml.startsWith("<?xml") ? xml.substring(xml.indexOf("?>") + 2).trim() : xml.trim();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
