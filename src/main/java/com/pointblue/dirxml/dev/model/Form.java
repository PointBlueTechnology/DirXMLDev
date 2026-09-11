package com.pointblue.dirxml.dev.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JSON (Form.io + NetIQ extensions) provisioning form: an {@code srvprvJSONForm}
 * object's {@code srvprvJSONData} — one document, held verbatim as read (UTF-8
 * text; the vault, Designer and the Identity Applications all pass it around
 * byte-for-byte — see {@code docs/spikes/json-forms-format.md}). Referenced by
 * name from a {@link Prd}'s {@link Prd.FormBinding#formId}.
 */
public final class Form {

    /** Which of the three {@code WorkflowForms} containers a form lives in. */
    public enum Kind {
        REQUEST("WorkflowRequestForms", "request", "srvprvJSONRequestForm"),
        APPROVAL("WorkflowApprovalForms", "approval", "srvprvJSONApprovalForm"),
        TEMPLATE("WorkflowTemplateForms", "template", "srvprvJSONTemplateForm");

        /** The vault container's {@code cn}, directly under {@code cn=WorkflowForms}. */
        public final String container;
        /** The as-code tree's subdirectory name under {@code provisioning/forms/}. */
        public final String dir;
        /** The Designer digest item's {@code type} attribute. */
        public final String digestType;

        Kind(String container, String dir, String digestType) {
            this.container = container;
            this.dir = dir;
            this.digestType = digestType;
        }

        public static Kind byContainer(String cn) {
            for (Kind k : values()) {
                if (k.container.equalsIgnoreCase(cn)) {
                    return k;
                }
            }
            return null;
        }

        public static Kind byDir(String dir) {
            for (Kind k : values()) {
                if (k.dir.equals(dir)) {
                    return k;
                }
            }
            return null;
        }

        public static Kind byDigestType(String type) {
            for (Kind k : values()) {
                if (k.digestType.equals(type)) {
                    return k;
                }
            }
            return null;
        }
    }

    public final Kind kind;
    public final String name;
    /** The document exactly as read (UTF-8 text) — see class doc. */
    public String json;
    /** Source-specific extras preserved losslessly (dn, objectClass, package stamps). */
    public final Map<String, String> meta = new LinkedHashMap<>();

    public Form(Kind kind, String name, String json) {
        this.kind = kind;
        this.name = name;
        this.json = json;
    }

    /** Parses {@link #json} into a {@link FormDocument} (title, components, scripts, languages). */
    public FormDocument document() {
        return FormDocument.parse(json);
    }

    @Override
    public String toString() {
        return "form " + kind.dir + "/" + name;
    }
}
