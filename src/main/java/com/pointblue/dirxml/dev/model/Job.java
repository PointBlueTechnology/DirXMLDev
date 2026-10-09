package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * A scheduled job of the engine ({@code DirXML-Job}, docs/console-gaps.md §1): under a driver
 * (a subscriber-channel trigger, a driver health check) or the driver set (statistics, telemetry
 * is the engine's own). What the vault holds (edir3, idm254, ig4, read 2026-10-09):
 * <pre>
 *   XmlData               the job's configuration document: &lt;job-aggregation&gt;&lt;job-definition …&gt;
 *   DirXML-ServerList     the servers that run it (multi-valued DNs)
 *   DirXML-Scope          what it runs over: "&lt;dn&gt;#&lt;type&gt;#&lt;scope-def …/&gt;" (multi-valued)
 *   DirXML-TraceLevel, DirXML-TraceFile, DirXML-TraceSizeLimit   its own trace settings (meta)
 * </pre>
 * In the tree: {@code drivers/<driver>/jobs/<name>.xml} or {@code jobs/<name>.xml} at the set,
 * the document; the rest in the manifest.
 */
public final class Job {

    public final String name;
    /** The {@code <job-aggregation>} (or Designer's {@code <job-definition>}) document, or null when the source had none. */
    public Element definition;
    /** {@code DirXML-ServerList}: the servers the job runs on. */
    public final List<String> servers = new ArrayList<>();
    /** {@code DirXML-Scope} values, as the vault holds them. */
    public final List<String> scopes = new ArrayList<>();
    /** Trace settings ({@code trace-level}, {@code trace-file}, {@code trace-size-limit}), DN, Designer id, package stamps, and other source-specific extras. */
    public final Map<String, String> meta = new LinkedHashMap<>();

    public Job(String name, Element definition) {
        this.name = name;
        this.definition = definition;
    }

    /** {@code <job-definition disabled="true">}: the job is defined but off. */
    public boolean disabled() {
        Element d = jobDefinition();
        return d != null && "true".equalsIgnoreCase(d.getAttribute("disabled"));
    }

    /** The job's Java class, from {@code <java-class>}; null when the document does not say. */
    public String javaClass() {
        Element d = jobDefinition();
        if (d == null) {
            return null;
        }
        for (org.w3c.dom.Node c = d.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && "java-class".equals(localName(c))) {
                String t = text(c);
                return t.isBlank() ? null : t.trim();
            }
        }
        return null;
    }

    /** The job's display name from the definition, with Designer's {@code xlfid(...)} prefix stripped; null when absent. */
    public String displayName() {
        Element d = jobDefinition();
        if (d == null) {
            return null;
        }
        String v = d.getAttribute("display-name");
        if (v == null || v.isEmpty()) {
            return null;
        }
        return v.replaceFirst("^xlfid\\([^)]*\\)", "");
    }

    /** The {@code <job-definition>} element: the document itself, or the first one under {@code <job-aggregation>}. */
    public Element jobDefinition() {
        if (definition == null) {
            return null;
        }
        if ("job-definition".equals(localName(definition))) {
            return definition;
        }
        for (org.w3c.dom.Node c = definition.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && "job-definition".equals(localName(c))) {
                return (Element) c;
            }
        }
        return null;
    }

    /** The text of an element from its text children: the engine's DOM is Level 2, with no {@code getTextContent}. */
    private static String text(org.w3c.dom.Node n) {
        StringBuilder sb = new StringBuilder();
        for (org.w3c.dom.Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == org.w3c.dom.Node.TEXT_NODE || c.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static String localName(org.w3c.dom.Node n) {
        String l = n.getLocalName();
        return l != null ? l : n.getNodeName();
    }
}
