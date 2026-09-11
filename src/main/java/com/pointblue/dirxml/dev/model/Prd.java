package com.pointblue.dirxml.dev.model;

import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A provisioning request definition ({@code srvprvRequest}). The vault splits one
 * PRD over three attributes:
 * <pre>
 *   XmlData            {@link #definition}: the whole {@code &lt;prov-req-defn&gt;} —
 *                       display names/descriptions, {@code &lt;xml-data&gt;} (design
 *                       params) and, empirically (checked on the test vault's
 *                       HelpdeskTicket PRD), the {@code &lt;process&gt;} activity graph
 *                       too. Never the {@code &lt;provision-request&gt;} child.
 *   srvprvRequestXML   {@link #request}: the {@code &lt;provision-request&gt;} element
 *                       (form bindings + data items for the request form). Null for
 *                       a PRD with no request-form binding.
 *   srvprvProcessXML   a redundant copy of the {@code &lt;process&gt;} already inside
 *                       {@link #definition} — verified semantically identical (via
 *                       {@code CanonicalXml}) on the test vault, differing only in
 *                       whitespace and a trailing copyright comment that
 *                       {@code srvprvProcessXML} alone carries. So {@link #process}
 *                       simply points at {@link #definition}'s own {@code &lt;process&gt;}
 *                       child (same DOM node) rather than holding a second copy; a
 *                       reader falls back to parsing {@code srvprvProcessXML} on its
 *                       own only if the definition has no embedded {@code &lt;process&gt;}.
 * </pre>
 *
 * <p>The plain {@code srvprv*} attributes (status, flow strategy, grant/revoke,
 * category, localized names/descriptions, …) live in {@link #properties},
 * lower-cased and without the {@code srvprv} prefix, holding whatever the source
 * gave — the vault's raw {@code lang~text|…} strings for the localized ones, or
 * the Designer project's derived equivalent built to match that same shape so the
 * two sources compare equal.
 */
public final class Prd {

    public final String name;
    /** {@code <prov-req-defn>}, without the {@code <provision-request>} child. */
    public Element definition;
    /** {@code <provision-request>}, or null. */
    public Element request;
    /** {@code <process>} — usually the same node as the child of {@link #definition}; see class doc. */
    public Element process;
    public final Map<String, List<String>> properties = new LinkedHashMap<>();
    public final Map<String, String> meta = new LinkedHashMap<>();

    public Prd(String name) {
        this.name = name;
    }

    /** True for an IDM 4.8+ JSON-forms PRD ({@code <provision-request formSrc="1">}); false for a classic XForms PRD. */
    public boolean isJsonForms() {
        return request != null && "1".equals(request.getAttribute("formSrc"));
    }

    public String property(String key) {
        List<String> v = properties.get(key);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    /**
     * Every form binding this PRD declares: the request-form binding (if any,
     * {@code activityId} null, taken directly from {@link #request}) followed by
     * every approval binding found anywhere under {@link #process} (a recursive
     * search — approval bindings sit on activities nested inside the process
     * graph; see {@code docs/spikes/json-forms-format.md} §3).
     */
    public List<FormBinding> bindings() {
        List<FormBinding> out = new ArrayList<>();
        if (request != null) {
            for (Element fb : Xds.childrenByName(request, "form-binding")) {
                out.add(bindingOf(fb, null));
            }
        }
        if (process != null) {
            for (Element fb : Xds.descendantsByName(process, "form-binding")) {
                String activityId = fb.getAttribute("activity-id");
                out.add(bindingOf(fb, activityId.isEmpty() ? null : activityId));
            }
        }
        return out;
    }

    private static FormBinding bindingOf(Element fb, String activityId) {
        List<Field> fields = new ArrayList<>();
        for (Element content : Xds.childrenByName(fb, "content")) {
            for (Element f : Xds.childrenByName(content, "field")) {
                List<Element> controls = Xds.childrenByName(f, "control");
                String controlType = controls.isEmpty() ? null : controls.get(0).getAttribute("control-type");
                fields.add(new Field(f.getAttribute("name"), f.getAttribute("data-type"), controlType));
            }
        }
        return new FormBinding(activityId, fb.getAttribute("form-id"), fields);
    }

    @Override
    public String toString() {
        return "prd " + name;
    }

    /** One {@code <form-binding>}: the request binding ({@code activityId} null) or an approval binding under a named workflow activity. */
    public static final class FormBinding {
        public final String activityId;
        public final String formId;
        public final List<Field> fields;

        public FormBinding(String activityId, String formId, List<Field> fields) {
            this.activityId = activityId;
            this.formId = formId;
            this.fields = fields;
        }

        @Override
        public String toString() {
            return (activityId == null ? "request" : "activity " + activityId) + " -> " + formId;
        }
    }

    /** One {@code <field name data-type><control control-type/></field>} of a binding's copied field list. */
    public static final class Field {
        public final String name;
        public final String dataType;
        public final String controlType;

        public Field(String name, String dataType, String controlType) {
            this.name = name;
            this.dataType = dataType;
            this.controlType = controlType;
        }

        @Override
        public String toString() {
            return name + ":" + dataType + "(" + controlType + ")";
        }
    }
}
