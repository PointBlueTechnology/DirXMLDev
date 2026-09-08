package com.pointblue.dirxml.dev.model;

import org.w3c.dom.Element;

/**
 * A DirXML resource: a mapping table, an ECMAScript library, or any other typed
 * content. XML resources keep their content element; text resources (ECMAScript)
 * keep their text. {@code contentType} is the IDM MIME type
 * (e.g. {@code application/vnd.novell.dirxml.mapping-table+xml}, {@code text/ecmascript}).
 */
public final class Resource extends Artifact {

    public static final String MAPPING_TABLE = "application/vnd.novell.dirxml.mapping-table+xml";
    public static final String ECMASCRIPT = "text/ecmascript";

    public String contentType;
    /** XML content, or null for a text resource. */
    public Element content;
    /** Text content (ECMAScript), or null for an XML resource. */
    public String text;

    public Resource(String name, Scope scope, String driver, String contentType) {
        super(name, scope, driver);
        this.contentType = contentType;
    }

    public boolean isMappingTable() {
        return contentType != null && contentType.contains("mapping-table");
    }

    public boolean isEcmaScript() {
        return contentType != null && contentType.contains("ecmascript");
    }

    public boolean isText() {
        return text != null;
    }

    @Override
    public String kind() {
        return "resource";
    }
}
