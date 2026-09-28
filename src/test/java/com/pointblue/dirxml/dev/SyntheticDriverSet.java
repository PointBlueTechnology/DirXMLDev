package com.pointblue.dirxml.dev;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.source.ExportWriter;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/**
 * A small driver set that stands in for the private fixtures (the RFI and JFW exports, the UA
 * LDIFs, the AD driver export) in the tests that used to skip without them: one library policy
 * linked into a driver's chain, one mapping table the chain reads, driver-set and driver GCVs,
 * a shim configuration, engine control values, a filter and schema map, one entitlement, two
 * forms and a PRD bound to one of them. Everything is invented; nothing came from a client.
 *
 * <p>The committed files under {@code src/test/resources/fixtures/synthetic/} are this model
 * written three ways — a Designer driver-set export, a single-driver export, and the LDIF a
 * vault holds after a deploy of it — so reader tests run against files, as the real ones do.
 * {@link #main} regenerates them; {@code SyntheticFixturesTest} fails when they drift from
 * the generator, which is the reminder to regenerate after a writer change:
 * <pre>
 *   mvn -q -o test-compile
 *   java -cp "target/test-classes:target/classes:lib/*:$HOME/.m2/repository/com/pointblue/dirxml/dirxml-simulator/&lt;ver&gt;/dirxml-simulator-&lt;ver&gt;.jar:&lt;junit-4 jar&gt;:&lt;hamcrest-core jar&gt;" com.pointblue.dirxml.dev.SyntheticDriverSet
 * </pre>
 */
public final class SyntheticDriverSet {

    public static final String DS_DN = "cn=SynthSet,o=synth";
    public static final String DRIVER = "Loop";
    public static final String LIBRARY_POLICY = "lib-NormalizeTitle";
    public static final String TABLE = "TitleMap";
    public static final String ENTITLEMENT = "GroupMembership";
    public static final String REQUEST_FORM = "Request Access";
    public static final String APPROVAL_FORM = "Approve Access";
    public static final String PRD = "Request Access";

    public static final String EXPORT = "driverset-export.xml";
    public static final String DRIVER_EXPORT = "loop-driver-export.xml";
    public static final String LDIF = "driverset.ldif";

    private static final String RESOURCE_DIR = "fixtures/synthetic/";

    private SyntheticDriverSet() {
    }

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    /** The model, built fresh each call. */
    public static DriverSet model() {
        DriverSet ds = new DriverSet("SynthSet");
        ds.dn = DS_DN;
        ds.configValues = xml("<configuration-values><definitions>"
            + "<definition display-name=\"Company name\" name=\"synth.company\" type=\"string\"><value>Synth Corp</value></definition>"
            + "</definitions></configuration-values>");

        Resource table = new Resource(TABLE, Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        table.content = xml("<mapping-table><col-def name=\"code\" type=\"nocase\"/><col-def name=\"title\" type=\"nocase\"/>"
            + "<row><col>eng</col><col>Engineer</col></row><row><col>mgr</col><col>Manager</col></row></mapping-table>");
        ds.library.resources.add(table);
        Policy lib = new Policy(LIBRARY_POLICY, Scope.LIBRARY, null, xml("<policy><rule>"
            + "<description>Normalize the title from its code</description>"
            + "<conditions><and><if-op-attr name=\"Title\" op=\"changing\"/></and></conditions>"
            + "<actions><do-reformat-op-attr name=\"Title\"><arg-value type=\"string\">"
            + "<token-map dest=\"title\" src=\"code\" table=\"..\\\\Library\\\\" + TABLE + "\"><token-local-variable name=\"current-value\"/></token-map>"
            + "</arg-value></do-reformat-op-attr></actions></rule></policy>"));
        ds.library.policies.add(lib);

        Driver d = new Driver(DRIVER);
        d.dn = "cn=" + DRIVER + "," + DS_DN;
        d.shimClass = "com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim";
        d.config.put(Driver.SHIM_CONFIG_INFO, xml("<driver-config name=\"" + DRIVER + "\"><driver-options>"
            + "<configuration-values><definitions>"
            + "<definition display-name=\"Heartbeat interval (minutes)\" name=\"pub-heartbeat-interval\" type=\"integer\"><value>1</value></definition>"
            + "</definitions></configuration-values></driver-options>"
            + "<subscriber-options/><publisher-options/></driver-config>"));
        d.config.put(Driver.DRIVER_FILTER, xml("<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
            + "<filter-attr attr-name=\"Surname\" publisher=\"sync\" subscriber=\"sync\"/>"
            + "<filter-attr attr-name=\"Title\" publisher=\"sync\" subscriber=\"sync\"/>"
            + "</filter-class></filter>"));
        d.config.put(Driver.CONFIG_VALUES, xml("<configuration-values><definitions>"
            + "<definition display-name=\"Users container\" name=\"drv.users\" type=\"dn\"><value>ou=users,o=synth</value></definition>"
            + "<definition display-name=\"Veto surname changes\" name=\"drv.veto.surname\" type=\"boolean\"><value>true</value></definition>"
            + "</definitions></configuration-values>"));
        d.config.put(Driver.ENGINE_CONTROL_VALUES, xml("<configuration-values><definitions>"
            + "<definition display-name=\"Subscriber channel retry interval in seconds\" name=\"dirxml.engine.retry-interval\" type=\"integer\"><value>30</value></definition>"
            + "</definitions></configuration-values>"));
        d.policies.add(new Policy("smp", Scope.DRIVER, DRIVER, xml("<attr-name-map><class-name><app-name>user</app-name><nds-name>User</nds-name>"
            + "<attr-name><app-name>sn</app-name><nds-name>Surname</nds-name></attr-name>"
            + "<attr-name><app-name>title</app-name><nds-name>Title</nds-name></attr-name>"
            + "</class-name></attr-name-map>")));
        d.subscriber.policies.add(new Policy("sub-ctp-VetoSurname", Scope.SUBSCRIBER, DRIVER, xml("<policy><rule>"
            + "<description>Veto a surname change when the GCV says so</description>"
            + "<conditions><and><if-global-variable name=\"drv.veto.surname\" op=\"equal\">true</if-global-variable>"
            + "<if-op-attr name=\"Surname\" op=\"changing\"/></and></conditions>"
            + "<actions><do-veto/></actions></rule></policy>")));
        d.publisher.policies.add(new Policy("pub-etp-Trace", Scope.PUBLISHER, DRIVER, xml("<policy><rule>"
            + "<description>Trace every publisher event</description><conditions/>"
            + "<actions><do-trace-message level=\"3\"><arg-string><token-text>event for </token-text><token-src-dn/></arg-string></do-trace-message>"
            + "</actions></rule></policy>")));
        d.links.add(new PolicyLink(PolicySet.SCHEMA_MAPPING, "drivers/" + DRIVER + "/smp", 0));
        d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/" + DRIVER + "/subscriber/sub-ctp-VetoSurname", 0));
        d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "library/" + LIBRARY_POLICY, 1));
        d.links.add(new PolicyLink(PolicySet.PUB_EVENT, "drivers/" + DRIVER + "/publisher/pub-etp-Trace", 0));

        d.entitlements.add(new Entitlement(ENTITLEMENT, xml("<entitlement conflict-resolution=\"union\" "
            + "description=\"Membership of a synthetic group\" display-name=\"Group Membership\">"
            + "<values multi-valued=\"true\"><value>cn=synth-users,ou=groups,o=synth</value></values></entitlement>")));

        Provisioning p = new Provisioning();
        p.forms.add(new Form(Form.Kind.REQUEST, REQUEST_FORM, form("Request Access", "reason", "Reason")));
        p.forms.add(new Form(Form.Kind.APPROVAL, APPROVAL_FORM, form("Approve Access", "comment", "Comment")));
        Prd prd = new Prd(PRD);
        prd.definition = xml("<prov-req-defn status=\"Active\"><description>Request access to a synthetic group</description></prov-req-defn>");
        prd.request = xml("<provision-request formSrc=\"1\" version=\"3.6.1\">"
            + "<form-binding form-id=\"" + REQUEST_FORM + "\"><content>"
            + "<field data-type=\"string\" name=\"reason\"><control control-type=\"textfield\"/></field>"
            + "<field data-type=\"button\" name=\"submit\"><control control-type=\"button\"/></field>"
            + "</content></form-binding>"
            + "<request-data-items>"
            + "<data-item data-type=\"string\" name=\"reason\" target=\"flowdata.Start/" + REQUEST_FORM + "/reason\" target-type=\"single-value\"/>"
            + "</request-data-items></provision-request>");
        prd.process = xml("<process formSrc=\"1\" id=\"cn=Request Access\" version=\"4.5.0\">"
            + "<form-binding activity-id=\"Approval\" form-id=\"" + APPROVAL_FORM + "\"/>"
            + "<data-items activity-id=\"Approval\">"
            + "<data-item data-type=\"string\" name=\"comment\" source=\"flowdata.get('Start/" + REQUEST_FORM + "/reason')\" target-type=\"single-value\"/>"
            + "</data-items>"
            + "<start-activity activity-id=\"Start\"><display-name>Start</display-name></start-activity>"
            + "<user-activity activity-id=\"Approval\"><display-name>Approval</display-name><addressee>'cn=synth-approvers,ou=groups,o=synth'</addressee></user-activity>"
            + "<finish-activity activity-id=\"Finish\"><display-name>Finish</display-name></finish-activity>"
            + "<link source=\"Start\" target=\"Approval\" type=\"forward\"/>"
            + "<link source=\"Approval\" target=\"Finish\" type=\"approved\"/>"
            + "<link source=\"Approval\" target=\"Finish\" type=\"denied\"/>"
            + "</process>");
        // what srvprvRequest requires (eDirectory -609 without them), as a real PRD carries them
        prd.properties.put("status", new ArrayList<>(List.of("Active")));
        prd.properties.put("flow-strategy", new ArrayList<>(List.of("SingleFlow")));
        prd.properties.put("grant", new ArrayList<>(List.of("TRUE")));
        prd.properties.put("revoke", new ArrayList<>(List.of("FALSE")));
        prd.properties.put("category-key", new ArrayList<>(List.of("accounts")));
        prd.properties.put("localized-names", new ArrayList<>(List.of("en~Request Access")));
        prd.properties.put("localized-descrs", new ArrayList<>(List.of("en~Request access to a synthetic group")));
        prd.properties.put("process-type", new ArrayList<>(List.of("Normal")));
        p.prds.add(prd);
        d.provisioning = p;

        ds.drivers.add(d);
        return ds;
    }

    private static String form(String title, String key, String label) {
        return "{\"components\":["
            + "{\"key\":\"" + key + "\",\"type\":\"textfield\",\"label\":\"" + label + "\"},"
            + "{\"key\":\"submit\",\"type\":\"button\",\"label\":\"Submit\"}"
            + "],\"title\":\"" + title + "\",\"display\":\"form\",\"inlinescripts\":\"\","
            + "\"localization\":{\"en\":{\"" + label + "\":\"" + label + "\",\"Submit\":\"Submit\"}},\"externalScripts\":[]}";
    }

    // ---- the three renderings ----

    /** The driver-set export, as the export writer renders the model. */
    public static String exportXml() {
        return ExportWriter.toXml(model());
    }

    /** The single-driver export of {@link #DRIVER}. */
    public static String driverExportXml() {
        return ExportWriter.toDriverXml(model(), DRIVER);
    }

    /** The LDIF a vault holds after an initial deploy of the model ({@link com.pointblue.dirxml.dev.deploy.SyntheticLdif}). */
    public static String ldif() throws IOException {
        return com.pointblue.dirxml.dev.deploy.SyntheticLdif.of(model(), DS_DN);
    }

    // ---- the committed files ----

    /** Copies the committed fixture into {@code dir} and returns it (tests read files, as they do the real fixtures). */
    public static Path copy(String name, Path dir) throws IOException {
        Path out = dir.resolve(name);
        try (InputStream in = SyntheticDriverSet.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                throw new IOException("missing test resource " + RESOURCE_DIR + name + " — run SyntheticDriverSet.main to generate it");
            }
            Files.copy(in, out);
        }
        return out;
    }

    /** The committed text of a fixture. */
    public static String committed(String name) throws IOException {
        try (InputStream in = SyntheticDriverSet.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Regenerates the three committed fixtures under {@code src/test/resources/fixtures/synthetic/}. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/" + RESOURCE_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(EXPORT), exportXml(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(DRIVER_EXPORT), driverExportXml(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(LDIF), ldif(), StandardCharsets.UTF_8);
        System.out.println("wrote " + dir.resolve(EXPORT) + ", " + dir.resolve(DRIVER_EXPORT) + ", " + dir.resolve(LDIF));
    }
}
