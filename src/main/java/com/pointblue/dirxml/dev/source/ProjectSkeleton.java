package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.DriverSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Milestone N1 of {@code docs/designer-new-project.md}: the empty Designer project
 * an {@code export-project --new} run then fills with the tree
 * ({@link ProjectWriter#create}). Every shape here is copied from the real project
 * {@code ~/designer_workspace/test11pf} (Designer 4.8.7) — see that note's §1 for the
 * file-by-file reading.
 *
 * <p>What it writes, in order:
 * <pre>
 *   .project                        Eclipse descriptor, nature com.novell.idm.DesignerProjectNature
 *   &lt;name&gt;.proj                     com.novell.idm.model:Project — the one file that ties everything together
 *   &lt;name&gt;.cproj                    IdmAdapterProject with the three Roots
 *   Model/IdentityManager.CRoot_    Idm:DevRootDomain -&gt; Domain_, ModelerNodes -&gt; ModelerNodes_
 *   Model/EdirOrphan.CRoot_         (empty root; the vault-side objects live in its directory)
 *   Model/Project.CRoot_            ProjectData -&gt; ProjectData_
 *   Model/IdentityManager/&lt;D&gt;.Domain_                 "Modeler Workspace", Idm:DomainItems -&gt; the vault
 *   Model/IdentityManager/&lt;D&gt;/&lt;V&gt;.IdentityVault_      host/user only when given; never a password
 *   Model/EdirOrphan/&lt;S&gt;.DriverSet_                   name/DSetContext from the tree, empty of drivers
 *   Model/EdirOrphan/&lt;S&gt;/&lt;L&gt;.Library_                 empty
 *   Model/EdirOrphan/&lt;X&gt;.Server_                      only with --server
 *   Model/Project/&lt;P&gt;.ProjectData_ + &lt;C&gt;.IdmCatalog_ + six IdmCategory_   the (empty) package catalog
 * </pre>
 *
 * <p>The {@code ModelerNodes_} and {@code Model/Provisioning/.provisioning} are
 * <i>not</i> written here: both need the {@code Application_} objects the content
 * pass creates, so {@link ProjectWriter} writes them when it finishes (their ids are
 * minted here so {@code <name>.proj} can reference them).
 *
 * <p><b>The descriptor coupling</b> (spike 6a/6b, {@code docs/spikes/designer-writer.md}):
 * the folder name must appear in {@code .project}'s {@code <name>}, in the
 * {@code .proj}/{@code .cproj} file names <i>and</i> in their {@code name} /
 * {@code cprojectURI} / {@code adapterProject} attributes, and in the
 * {@code ModelerNodes_}'s {@code <project href>}. A mismatch gives "No valid .proj
 * file" or a silent, empty import.
 */
final class ProjectSkeleton {

    private static final String DECL_UTF8 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    private static final String DECL_UPPER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String CROOT_NS = "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\"";
    private static final String COBJECT_NS =
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
        + "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\"";

    /** Designer's stock package-catalog categories, in the order {@code test11pf}'s catalog lists them. */
    private static final String[] CATEGORIES = {"Notification", "Common", "Service", "Tool", "Directory", "Provisioning"};

    /** The ten modeler ports every node carries (five out, five in). */
    private static final String[] PORT_SIDES = {"top", "bottom", "left", "right", "center"};

    private ProjectSkeleton() {
    }

    /** The ids the skeleton minted, for the content pass and the finalizing writes. */
    static final class Plan {
        final String projectName;
        final Set<String> usedIds = new LinkedHashSet<>();
        String projectGuid;
        String domainId;
        String vaultId;
        String modelerNodesId;
        String driverSetId;
        String libraryId;
        String projectDataId;
        String catalogId;
        /** The token every per-server file is named with ({@code <id>_<serverToken>_DirXML-ConfigValues.xml}). */
        String serverToken;
        /** True when a real {@code Server_} object was written (i.e. {@code --server} was given). */
        boolean serverWritten;

        Plan(String projectName) {
            this.projectName = projectName;
        }

        /** {@code EdirOrphan/<id>.Server_}, the {@code objectURI} a per-server attribute set points at. */
        String serverObjectUri() {
            return serverWritten ? "EdirOrphan/" + serverToken + ".Server_" : "";
        }

        String mint() {
            String id = ProjectWriter.mintId(usedIds);
            return id;
        }
    }

    /**
     * Writes the skeleton under {@code root} (which must already exist and be empty)
     * and records every file it created in {@code result}.
     */
    static Plan write(Path root, String projectName, DriverSet tree, NewProject opts, ProjectWriter.Result result)
        throws IOException {
        Plan plan = new Plan(projectName);
        plan.projectGuid = plan.mint();
        plan.domainId = plan.mint();
        plan.vaultId = plan.mint();
        plan.modelerNodesId = plan.mint();
        plan.driverSetId = plan.mint();
        plan.libraryId = plan.mint();
        plan.projectDataId = plan.mint();
        plan.catalogId = plan.mint();
        plan.serverToken = plan.mint();
        plan.serverWritten = opts.serverName != null;

        write(root, result, ".project", eclipseDescriptor(projectName));
        write(root, result, projectName + ".proj", projFile(plan));
        write(root, result, projectName + ".cproj", cprojFile(projectName));

        write(root, result, "Model/IdentityManager.CRoot_",
            croot("IdentityManager",
                relation("Idm:DevRootDomain", "Child", plan.domainId, "Domain")
                + relation("ModelerNodes", "Child", plan.modelerNodesId, "ModelerNodes")));
        write(root, result, "Model/EdirOrphan.CRoot_", croot("EdirOrphan", ""));
        write(root, result, "Model/Project.CRoot_",
            croot("Project", relation("ProjectData", "Child", plan.projectDataId, "ProjectData")));

        write(root, result, "Model/IdentityManager/" + plan.domainId + ".Domain_",
            cobject("Modeler Workspace", "Domain", "",
                relation("Idm:DomainItems", "Child", plan.vaultId, "IdentityVault")));
        write(root, result, "Model/IdentityManager/" + plan.domainId + "/" + plan.vaultId + ".IdentityVault_",
            identityVault(plan, tree, opts));

        write(root, result, "Model/EdirOrphan/" + plan.driverSetId + ".DriverSet_", driverSet(plan, tree));
        write(root, result, "Model/EdirOrphan/" + plan.driverSetId + "/" + plan.libraryId + ".Library_",
            cobject("Library", "Library", attr("LibraryContext", dn(tree), "CString"), ""));
        if (plan.serverWritten) {
            write(root, result, "Model/EdirOrphan/" + plan.serverToken + ".Server_", server(plan, opts));
        }

        write(root, result, "Model/Project/" + plan.projectDataId + ".ProjectData_",
            cobject("ProjectData", "ProjectData", "",
                relation("Idm:Catalog", "Child", plan.catalogId, "IdmCatalog")));
        StringBuilder catalogRelations = new StringBuilder();
        List<String> categoryIds = new ArrayList<>();
        for (String ignored : CATEGORIES) {
            String id = plan.mint();
            categoryIds.add(id);
            catalogRelations.append(relation("Idm:Categories", "Child", id, "IdmCategory"));
        }
        String catalogDir = "Model/Project/" + plan.projectDataId + "/";
        write(root, result, catalogDir + plan.catalogId + ".IdmCatalog_",
            cobject("Package Catalog", "IdmCatalog", "", catalogRelations.toString()));
        for (int i = 0; i < CATEGORIES.length; i++) {
            // test11pf's IdmCategory_ objects carry a name and no type attribute — copied as-is.
            write(root, result, catalogDir + plan.catalogId + "/" + categoryIds.get(i) + ".IdmCategory_",
                DECL_UTF8 + "\n<com.novell.designer.model:CObject " + CROOT_NS
                    + " name=\"" + CObjectXml.esc(CATEGORIES[i]) + "\"/>\n");
        }
        return plan;
    }

    // ---- the three coupled descriptors -------------------------------------------------

    private static String eclipseDescriptor(String name) {
        return DECL_UPPER + "\n"
            + "<projectDescription>\n"
            + "\t<name>" + CObjectXml.esc(name) + "</name>\n"
            + "\t<comment></comment>\n"
            + "\t<projects>\n"
            + "\t</projects>\n"
            + "\t<buildSpec>\n"
            + "\t</buildSpec>\n"
            + "\t<natures>\n"
            + "\t\t<nature>com.novell.idm.DesignerProjectNature</nature>\n"
            + "\t</natures>\n"
            + "</projectDescription>\n";
    }

    /** {@code productID}/{@code version} are 4.7 — what a 4.8.7 Designer writes (§1.1). */
    private static String projFile(Plan plan) {
        String n = CObjectXml.esc(plan.projectName);
        return DECL_UPPER + "<com.novell.idm.model:Project cprojectURI=\"" + n + "/" + n + ".cproj\""
            + " domainURI=\"IdentityManager/" + plan.domainId + ".domain\""
            + " guid=\"" + plan.projectGuid + "\" name=\"" + n + "\" packageLinkagesMigrated=\"true\""
            + " productID=\"4.7\" type=\"Project\" version=\"4.7\""
            + " xmlns:com.novell.idm.model=\"http://com.novell.idm.model\">\n"
            + "  <modelerNodes href=\"Model/IdentityManager/" + plan.modelerNodesId + ".ModelerNodes_#/\"/>\n"
            + "  <adapterProject href=\"" + n + ".cproj#/\"/>\n"
            + "</com.novell.idm.model:Project>\n";
    }

    private static String cprojFile(String name) {
        return DECL_UTF8 + "\n"
            + "<com.novell.idm.model:IdmAdapterProject xmlns:com.novell.idm.model=\"http://com.novell.idm.model\" name=\""
            + CObjectXml.esc(name) + "\">\n"
            + "  <relations name=\"Roots\" type=\"Child\" key=\"#IdentityManager.CRoot_\"/>\n"
            + "  <relations name=\"Roots\" type=\"Child\" key=\"#Project.CRoot_\"/>\n"
            + "  <relations name=\"Roots\" type=\"Child\" key=\"#EdirOrphan.CRoot_\"/>\n"
            + "</com.novell.idm.model:IdmAdapterProject>\n";
    }

    // ---- the objects --------------------------------------------------------------------

    /**
     * The vault object. {@code IdentityVaultSavePassword} is always {@code false} and no
     * {@code IdentityVaultPassword} attribute is ever written — Designer asks for the
     * password on its first connect.
     */
    private static String identityVault(Plan plan, DriverSet tree, NewProject opts) {
        StringBuilder attrs = new StringBuilder();
        if (opts.vaultHost != null) {
            attrs.append(attr("IdentityVaultHost", opts.vaultHost, "CString"));
        }
        if (opts.vaultUser != null) {
            attrs.append(attr("IdentityVaultUsername", opts.vaultUser, "CString"));
        }
        attrs.append("<attributes xsi:type=\"com.novell.designer.model:CStructure\" attrName=\"ContactInfo\"/>");
        attrs.append(attr("IdentityVaultSavePassword", "false", "CBoolean"));
        if (plan.serverWritten) {
            attrs.append(attr("IdmParameter:Modeler.DefaultServerAdded", "true", "CString"));
        }
        StringBuilder rels = new StringBuilder();
        rels.append(relation("Idm:DriverSets", "ContainedReference", plan.driverSetId, "DriverSet"));
        if (plan.serverWritten) {
            rels.append(relation("Idm:Servers", "ContainedReference", plan.serverToken, "Server"));
        }
        return cobject(vaultName(opts, tree), "IdentityVault", attrs.toString(), rels.toString());
    }

    /**
     * {@code --vault-name}, else a neutral default: the model records the driver set's DN
     * but never the eDirectory <i>tree</i> name, so there is nothing better to infer.
     */
    private static String vaultName(NewProject opts, DriverSet tree) {
        return opts.vaultName != null ? opts.vaultName : "Identity Vault";
    }

    private static String driverSet(Plan plan, DriverSet tree) {
        StringBuilder attrs = new StringBuilder();
        // per-server heavy data, the way test11pf's driver set holds its DirXML-ConfigValues
        attrs.append("<associatedAttrSets objectURI=\"").append(CObjectXml.esc(plan.serverObjectUri())).append("\">")
            .append("<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"DirXML-ConfigValues\"/>")
            .append("</associatedAttrSets>");
        attrs.append(attr("DSetContext", context(tree), "CString"));
        StringBuilder rels = new StringBuilder();
        rels.append(relation("Idm:Libraries", "Child", plan.libraryId, "Library"));
        if (plan.serverWritten) {
            rels.append(relation("Idm:Servers", "Reference", plan.serverToken, "Server"));
        }
        rels.append(relation("Idm:IdentityVaults", "BackReference", plan.vaultId, "IdentityVault"));
        return cobject(tree.name, "DriverSet", attrs.toString(), rels.toString());
    }

    private static String server(Plan plan, NewProject opts) {
        StringBuilder attrs = new StringBuilder();
        if (opts.serverContext != null) {
            attrs.append(attr("ServerContext", opts.serverContext, "CString"));
        }
        // test11pf's Server_ carries a name and no type attribute.
        return DECL_UTF8 + "\n<com.novell.designer.model:CObject " + COBJECT_NS
            + " name=\"" + CObjectXml.esc(opts.serverName) + "\">" + attrs + "</com.novell.designer.model:CObject>\n";
    }

    // ---- ModelerNodes / .provisioning (written by ProjectWriter once the apps exist) -----

    /**
     * The modeler diagram: the domain node (no geometry), the vault node, then one node
     * per application laid out on a simple grid — Designer re-lays out on request, and the
     * node is what makes an application appear on the canvas at all.
     */
    static String modelerNodes(Plan plan, List<String> applicationIds) {
        StringBuilder sb = new StringBuilder();
        sb.append(DECL_UTF8).append('\n')
            .append("<com.novell.idm.model:ModelerNodes xmlns:com.novell.idm.model=\"http://com.novell.idm.model\" "
                + "name=\"Modeler Diagram\">\n");
        sb.append("  <modelerNodes objectURI=\"#").append(plan.domainId).append(".Domain_\"/>\n");
        sb.append(node("height=\"110\" width=\"170\" x=\"200\" y=\"200\"", plan.vaultId + ".IdentityVault_"));
        for (int i = 0; i < applicationIds.size(); i++) {
            int col = i % 8;
            int row = i / 8;
            String geom = "height=\"44\" width=\"55\" x=\"" + (20 + col * 100) + "\" y=\"" + (420 + row * 120) + "\"";
            sb.append(node(geom, applicationIds.get(i) + ".Application_"));
        }
        sb.append("  <project href=\"../../").append(CObjectXml.esc(plan.projectName)).append(".proj#/\"/>\n");
        sb.append("</com.novell.idm.model:ModelerNodes>\n");
        return sb.toString();
    }

    private static String node(String geometry, String objectKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <modelerNodes ").append(geometry).append(" objectURI=\"#").append(objectKey).append("\">\n");
        for (String side : PORT_SIDES) {
            sb.append("    <sourcePorts name=\"source-").append(side).append("\"/>\n");
        }
        for (String side : PORT_SIDES) {
            sb.append("    <targetPorts name=\"target-").append(side).append("\"/>\n");
        }
        sb.append("  </modelerNodes>\n");
        return sb.toString();
    }

    /** {@code Model/Provisioning/.provisioning}: one {@code <application>} per AppConfig folder. */
    static String provisioningIndex(List<String[]> folderAndGuid) {
        StringBuilder sb = new StringBuilder(DECL_UPPER).append("\n<root>");
        for (String[] fg : folderAndGuid) {
            sb.append("\n    <application folder=\"").append(CObjectXml.esc(fg[0]))
                .append("\" guid=\"").append(CObjectXml.esc(fg[1])).append("\"/>");
        }
        return sb.append("</root>\n").toString();
    }

    // ---- small helpers -------------------------------------------------------------------

    /** The driver set's own DN as the tree records it (or a synthesized one). */
    private static String dn(DriverSet tree) {
        return (tree.dn != null && !tree.dn.isEmpty()) ? tree.dn : "cn=" + tree.name + ",o=system";
    }

    /** {@code DSetContext}: the driver set's parent container. */
    private static String context(DriverSet tree) {
        String d = dn(tree);
        int comma = d.indexOf(',');
        return comma > 0 ? d.substring(comma + 1).trim() : "o=system";
    }

    private static String croot(String name, String relationsXml) {
        return DECL_UTF8 + "\n<com.novell.designer.model:CRoot " + CROOT_NS + " name=\"" + CObjectXml.esc(name) + "\""
            + (relationsXml.isEmpty() ? "/>" : ">" + relationsXml + "</com.novell.designer.model:CRoot>") + "\n";
    }

    private static String cobject(String name, String type, String attrsXml, String relationsXml) {
        return CObjectXml.cobject(name, type, attrsXml, relationsXml);
    }

    private static String attr(String attrName, String value, String xsiType) {
        return CObjectXml.attr(attrName, value, xsiType);
    }

    private static String relation(String name, String type, String id, String typeSuffix) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + id + "." + typeSuffix + "_\"/>";
    }

    private static void write(Path root, ProjectWriter.Result result, String relative, String content) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        result.createdFiles.add(relative);
    }
}
