package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code Application_} type Designer gives a driver — the string that picks the
 * modeler icon ({@code icons/iManager/&lt;type&gt;.gif}, see {@link DesignerInstall}) and the
 * palette entry.
 *
 * <p><b>Where the tables come from.</b> Both are read out of a Designer install's own
 * model-item definitions, not guessed:
 * {@code plugins/com.novell.idm_<ver>/defs/model_items/Drivers/*.xml} carry
 * {@code <driver type="…" primaryApp="…">} with a {@code <supported-shims>} list. The
 * driver-type table is that file set one-to-one (79 driver types in Designer 4.8.7 /
 * {@code com.novell.idm_4.0.0.202507091432}); the shim-class table holds only those Java
 * shim classes that map to exactly <i>one</i> {@code primaryApp} across the whole set —
 * {@code LDAPDriverShim}, {@code NullDriverShim}, {@code SOAPDriver} and the Remote
 * Loader proxy each serve several driver types, so they are deliberately absent.
 *
 * <p><b>Why a driver type at all.</b> A Remote Loader driver's {@code DirXML-JavaModule}
 * is the proxy {@code com.novell.nds.dirxml.remote.driver.DriverShimImpl} — the real shim
 * runs on the loader and the vault never records its class (checked on
 * {@code tree-test11pf}'s Active Directory driver: neither {@code shim-config-info.xml}
 * nor {@code engine-control-values.xml} names it). What the tree <i>does</i> record is the
 * Designer driver type ({@code designer.driver-type}, e.g. {@code AD-Driver}) when it came
 * from a project, and that is what types the application — {@code test11pf}'s AD driver is
 * an {@code ActiveDirectory} application, and {@code --new} now reproduces that instead of
 * falling back to {@code GenericApp}.
 *
 * <p><b>Deliberate exceptions</b> (observed on {@code test11pf}, which Designer itself
 * wrote): the User Application driver is {@code NProv} and the Role and Resource driver
 * {@code NrfApp} — neither has a driver definition of its own, and Designer's defs map
 * {@code ComposerDriverShim} to {@code PIVWorkflow}, which is not what it uses. And a SCIM
 * driver keeps {@code GenericApp}: {@code test11pf}'s {@code MITLL-Druva} is a
 * {@code SCIM-Driver} whose application Designer typed {@code GenericApp}, even though a
 * {@code SCIM} application type exists.
 */
final class ApplicationType {

    /** The Remote Loader proxy — never the application's own shim. */
    static final String REMOTE_LOADER_SHIM = "com.novell.nds.dirxml.remote.driver.DriverShimImpl";

    /** Designer's catch-all for a driver it does not recognize. */
    static final String GENERIC = "GenericApp";

    private static final Map<String, String> BY_SHIM = new HashMap<>();
    private static final Map<String, String> BY_DRIVER_TYPE = new HashMap<>();

    static {
        Map<String, String> m = BY_SHIM;
        // ---- the two Designer's own driver definitions do not cover (observed on test11pf) ----
        m.put("com.novell.idm.driver.ComposerDriverShim", "NProv");
        m.put("com.novell.nds.dirxml.driver.nrf.NRFDriverShim", "NrfApp");
        // ---- a Remote Loader shim class that identifies its application unambiguously ----
        m.put("com.novell.nds.dirxml.driver.ad.ADDriverShim", "ActiveDirectory");
        // ---- unambiguous Java shims from the Designer install's driver definitions ----
        m.put("be.opns.dirxml.driver.ars.arsremedydrivershim.ARSDriverShim", "Remedy");
        m.put("com.Omnibond.nds.dirxml.driver.MVSDriver.RACFDriver.RACFDriverShim", "RACF");
        m.put("com.Omnibond.nds.dirxml.driver.NxSettings.NxSettingsDriverShim", "Linux");
        m.put("com.microfocus.dirxml.driver.edm.EDMDriverShim", "EDM");
        m.put("com.netiq.dirxml.driver.saphana.SAPHanaDriverShim", "SAP-HANA");
        m.put("com.netiq.idm.driver.igdriver.IGDriverShim", "IGIM");
        m.put("com.netiq.nds.dirxml.driver.pum.PUMDriverShim", "NPUM");
        m.put("com.novell.gw.dirxml.driver.gw.GWdriverShim", "GroupWise");
        m.put("com.novell.idm.driver.idprovider.IDProviderShim", "IDProvider");
        m.put("com.novell.idm.driver.jms.JMSDriverShim", "JMS");
        m.put("com.novell.nds.dirxml.driver.SAPShim.SAPDriverShim", "SAP-HR");
        m.put("com.novell.nds.dirxml.driver.aicmsshim.AICMSDriverShim", "ActivIdentityCMS");
        m.put("com.novell.nds.dirxml.driver.arshim.AccessReviewDriverShim", "AccessReview");
        m.put("com.novell.nds.dirxml.driver.azure.azdrivershim", "AZURE");
        m.put("com.novell.nds.dirxml.driver.dcsshim.DCSShim", "IDMDCS");
        m.put("com.novell.nds.dirxml.driver.delimitedtext.DelimitedTextDriver", "DelimitedTextApp");
        m.put("com.novell.nds.dirxml.driver.ebs.hr.EBSHRDriver", "Oracle-EBSHR");
        m.put("com.novell.nds.dirxml.driver.ebs.tca.EBSTCADriver", "Oracle-EBSTCA");
        m.put("com.novell.nds.dirxml.driver.ebs.user.EBSUserDriver", "Oracle-EBSUser");
        m.put("com.novell.nds.dirxml.driver.edir.EDIRDriverShim", "eDirectory");
        m.put("com.novell.nds.dirxml.driver.entitlement.EntitlementServiceDriver", "Entitlement");
        m.put("com.novell.nds.dirxml.driver.gmailshim.GMailDriverShim", "GoogleApps");
        m.put("com.novell.nds.dirxml.driver.http.HTTPDriver", "HTTP-Server");
        m.put("com.novell.nds.dirxml.driver.jdbc.JDBCDriverShim", "GenericDatabase");
        m.put("com.novell.nds.dirxml.driver.legacynds.LegacyNDSDriverShim", "NDS");
        m.put("com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim", "LoopBack");
        m.put("com.novell.nds.dirxml.driver.manualtask.driver.ManualTaskDriver", "ManualTask");
        m.put("com.novell.nds.dirxml.driver.msgateway.MSGatewayDriverShim", "MSGATEWAY");
        m.put("com.novell.nds.dirxml.driver.nds.DriverShimImpl", "eDirectory");
        m.put("com.novell.nds.dirxml.driver.nisdriver.NISDriverShim", "NIS");
        m.put("com.novell.nds.dirxml.driver.notes.NotesDriverShim", "Notes");
        m.put("com.novell.nds.dirxml.driver.psoftshim.PSOFTDriverShim", "PeopleSoft");
        m.put("com.novell.nds.dirxml.driver.rest.RESTDriverShim", "REST");
        m.put("com.novell.nds.dirxml.driver.salesforce.SFDriverShim", "SalesForce");
        m.put("com.novell.nds.dirxml.driver.sap.bl.SAPBLShim", "SAP-BizLogic");
        m.put("com.novell.nds.dirxml.driver.sap.grcac.SAPGRCACShim", "SAP-GRCAC");
        m.put("com.novell.nds.dirxml.driver.sap.portal.SAPPortalShim", "SAP-Portal");
        m.put("com.novell.nds.dirxml.driver.sapumshim.SAPDriverShim", "SAP-User");
        m.put("com.novell.nds.dirxml.driver.sapusershim.SAPDriverShim", "SAP-User");
        m.put("com.novell.nds.dirxml.driver.sentinel.SentinelShim", "Sentinel");
        m.put("com.novell.nds.dirxml.driver.servicenow.SNDriverShim", "ServiceNow");
        m.put("com.novell.nds.dirxml.driver.sifagent.SIFShim", "SIF");
        m.put("com.novell.nds.dirxml.driver.sungardbannershim.SungardBannerDriverShim", "Banner");
        m.put("com.novell.nds.dirxml.driver.workflow.driver.WorkflowDriver", "Workflow");
        m.put("com.novell.nds.dirxml.driver.workorder.WorkOrderDriverShim", "WorkOrder");
        m.put("com.novell.netiq.dirxml.driver.workday.WDDriverShim", "Workday");
        m.put("com.pds.EpicEmpDriver.EpicEmpDriverShim", "EPICEMP");

        Map<String, String> t = BY_DRIVER_TYPE;
        t.put("ACF2-Driver", "ACF2");
        t.put("AD-Driver", "ActiveDirectory");
        t.put("ADAM-Driver", "ADAM");
        t.put("AZURE-Driver", "AZURE");
        t.put("AccessReview-Driver", "AccessReview");
        t.put("BlackboardREST-Driver", "BlackboardREST");
        t.put("EDIR-Driver", "eDirectory");
        t.put("EDIR2EDIR-Driver", "eDirectory");
        t.put("EDM-Driver", "EDM");
        t.put("EPICEMP-Driver", "EPICEMP");
        t.put("Entitlement-Driver", "Entitlement");
        t.put("Fanout-Driver", "Fanout");
        t.put("Generic-Driver", GENERIC);
        t.put("GoogleApps-Driver", "GoogleApps");
        t.put("GroupWise-Driver", "GroupWise");
        t.put("HTTP-Driver", "HTTP-Server");
        t.put("IAS-AICMS-Driver", "ActivIdentityCMS");
        t.put("IAS-HoneywellPACS-Driver", "HoneywellPACS");
        t.put("IAS-IWBioEnrollment-Driver", "HoneywellBioEnrollment");
        t.put("IAS-PIVLifeCycle-Driver", "PIVLifeCycle");
        t.put("IAS-PIVWorkflow-Driver", "PIVWorkflow");
        t.put("IDMDCS-Driver", "IDMDCS");
        t.put("IDProvider-Driver", "IDProvider");
        t.put("IG-Integration", "IGIM");
        t.put("JDBC-DB2-Driver", "GenericDatabase");
        t.put("JDBC-Generic-Driver", "GenericDatabase");
        t.put("JDBC-Informix-Driver", "GenericDatabase");
        t.put("JDBC-MySQL-Driver", "GenericDatabase");
        t.put("JDBC-Oracle-Driver", "GenericDatabase");
        t.put("JDBC-Postgres-Driver", "GenericDatabase");
        t.put("JDBC-SQLServer-Driver", "GenericDatabase");
        t.put("JDBC-Sybase-Driver", "GenericDatabase");
        t.put("JMS-Driver", "JMS");
        t.put("LDAP-Driver", "GenericDirectory");
        t.put("LinuxUnix-Driver", "Linux");
        t.put("LoopBack-Driver", "LoopBack");
        t.put("MDAD-Driver", "MultiDomainActiveDirectory");
        t.put("MSGATEWAY-Driver", "MSGATEWAY");
        t.put("ManualTask-Driver", "ManualTask");
        t.put("NDS-Driver", "NDS");
        t.put("NIS-Driver", "NIS");
        t.put("Notes-Driver", "Notes");
        t.put("Null-Driver", "Null");
        // present in Designer's defs (com.novell.idm and com.novell.prov.pal.integration) but absent from the
        // first cut of this table — test11pf types its SCIM drivers SCIM-Driver and the RRSD NrfDriver
        t.put("SCIM-Driver", "SCIM");
        t.put("NrfDriver", "NrfApp");
        t.put("NxSettings-Driver", "Linux");
        t.put("ORACLEEBSHR-Driver", "Oracle-EBSHR");
        t.put("ORACLEEBSTCA-Driver", "Oracle-EBSTCA");
        t.put("ORACLEEBSUSR-Driver", "Oracle-EBSUser");
        t.put("OS400-Driver", "OS400");
        t.put("Office365-Driver", "Office365");
        t.put("PUM-Driver", "NPUM");
        t.put("PeopleSoft-Driver", "PeopleSoft");
        t.put("RACF-Driver", "RACF");
        t.put("REST-Driver", "REST");
        t.put("Remedy-Driver", "Remedy");
        t.put("Remote-Driver", GENERIC);
        t.put("SAP-BizLogic-CMP-Driver", "SAP-BizLogic");
        t.put("SAP-GRCAC-CMP-Driver", "SAP-GRCAC");
        t.put("SAP-HANA-Driver", "SAP-HANA");
        t.put("SAP-HR-CMP-Driver", "SAP-HR");
        t.put("SAP-HR-Driver", "SAP-HR");
        t.put("SAP-Portal-CMP-Driver", "SAP-Portal");
        t.put("SAP-User-CMP-Driver", "SAP-User");
        t.put("SAP-User-Driver", "SAP-User");
        t.put("SIF-Driver", "SIF");
        t.put("SOAP-Driver", "SOAP");
        t.put("SUNGARDBANNER", "Banner");
        t.put("SalesForce-Driver", "SalesForce");
        t.put("Scripting-Driver", "Scripting");
        t.put("Sentinel-Driver", "Sentinel");
        t.put("ServiceNow-Driver", "ServiceNow");
        t.put("SharePoint-Driver", "SharePoint");
        t.put("StateMachine-Driver", "StateMachine");
        t.put("Text-Driver", "DelimitedTextApp");
        t.put("TopSecret-Driver", "TopSecret");
        t.put("WorkOrder-Driver", "WorkOrder");
        t.put("Workday-Driver", "Workday");
        t.put("Workflow-Driver", "Workflow");
        t.put("i5OS-Driver", "i5OS");
        // SCIM-Driver is deliberately absent — see the class comment.
    }

    private ApplicationType() {
    }

    /** The application type for a Designer driver type ({@code AD-Driver} → {@code ActiveDirectory}); null when unknown. */
    static String forDriverType(String driverType) {
        if (driverType == null || driverType.isEmpty()) {
            return null;
        }
        String t = BY_DRIVER_TYPE.get(driverType);
        if (t != null) {
            return t;
        }
        if (driverType.startsWith("NProv")) {
            return "NProv";
        }
        if (driverType.startsWith("Nrf")) {
            return "NrfApp";
        }
        return null;
    }

    /** The one Designer driver type with this application type, or null when none or several have it. */
    static String driverTypeForApp(String app) {
        if (app == null || GENERIC.equals(app)) {
            return null;
        }
        String found = null;
        for (Map.Entry<String, String> e : BY_DRIVER_TYPE.entrySet()) {
            if (app.equals(e.getValue())) {
                if (found != null) {
                    return null;
                }
                found = e.getKey();
            }
        }
        return found;
    }

    /**
     * True when the driver's own shim class cannot settle its type — the Remote Loader proxy
     * (the shim is elsewhere) or a SCIM connector (several Designer types list it) — so the
     * base package's {@code supported-drivers} is the authority, as Designer's importer uses it.
     */
    static boolean typeComesFromBasePackage(String shimClass) {
        return REMOTE_LOADER_SHIM.equals(shimClass) || isScimShim(shimClass);
    }

    /** True for a SCIM connector shim, which Designer types {@code GenericApp} anyway. */
    static boolean isScimShim(String shimClass) {
        if (shimClass == null) {
            return false;
        }
        String s = shimClass.toLowerCase(Locale.ROOT);
        return s.startsWith("com.microfocus.nds.dirxml.driver.scim")
            || s.startsWith("com.netiq.nds.dirxml.driver.scim")
            || s.startsWith("com.novell.nds.dirxml.driver.scim");
    }

    /**
     * The application type for a driver: its own shim class when that identifies the
     * application, else the Designer driver type the tree recorded (which is the only thing
     * a Remote Loader driver has), else {@code GenericApp}.
     */
    static String of(Driver d) {
        if (isScimShim(d.shimClass)) {
            return GENERIC;
        }
        String byShim = d.shimClass == null ? null : BY_SHIM.get(d.shimClass);
        if (byShim != null) {
            return byShim;
        }
        String driverType = d.meta.get("designer.driver-type");
        if (driverType != null && !driverType.isEmpty()) {
            String byType = BY_DRIVER_TYPE.get(driverType);
            if (byType != null) {
                return byType;
            }
            // "NProv Driver 4.8.0", "NProv Driver 3.5.1 - Roles", … — every User Application
            // driver type Designer has ever written starts this way (test11pf: 4.8.0).
            if (driverType.startsWith("NProv")) {
                return "NProv";
            }
            if (driverType.startsWith("Nrf")) {
                return "NrfApp";
            }
        }
        return GENERIC;
    }
}
