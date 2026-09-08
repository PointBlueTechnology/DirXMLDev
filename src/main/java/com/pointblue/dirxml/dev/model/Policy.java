package com.pointblue.dirxml.dev.model;

import org.w3c.dom.Element;

/**
 * A policy object: the raw content element ({@code <policy>}, an XSLT stylesheet, or
 * an {@code <attr-name-map>}) plus its derived kind. Content is preserved as-is; the
 * canonical serializer decides its on-disk form.
 */
public final class Policy extends Artifact {

    public enum Kind {
        DIRXML_SCRIPT, XSLT, SCHEMA_MAP, OTHER;

        public static Kind of(Element content) {
            if (content == null) {
                return OTHER;
            }
            String ln = content.getLocalName() != null ? content.getLocalName() : content.getNodeName();
            String ns = content.getNamespaceURI();
            if ("http://www.w3.org/1999/XSL/Transform".equals(ns)
                || "stylesheet".equals(ln) || "transform".equals(ln) || "style-sheet".equals(ln)) {
                return XSLT;
            }
            if ("attr-name-map".equals(ln) || "schema-mapping".equals(ln)) {
                return SCHEMA_MAP;
            }
            if ("policy".equals(ln)) {
                return DIRXML_SCRIPT;
            }
            return OTHER;
        }
    }

    public Element content;

    public Policy(String name, Scope scope, String driver, Element content) {
        super(name, scope, driver);
        this.content = content;
    }

    public Kind policyKind() {
        return Kind.of(content);
    }

    @Override
    public String kind() {
        return "policy";
    }
}
