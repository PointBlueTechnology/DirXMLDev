package com.pointblue.dirxml.dev.validate;

import java.util.List;
import java.util.Map;

/**
 * What DirXML-Script requires of each element, as Designer's policy builder checks it: the
 * attributes its DTD (dirxmlscript 4.7.5, shipped with Designer) marks {@code #REQUIRED}. The
 * engine's compiler is laxer — it loaded ig4's {@code do-send-email-from-template} with no
 * {@code template-dn} — but Designer's importer fails on such a policy and drops it, so a tree
 * that must round-trip through Designer cannot carry one. Elements not listed here require
 * nothing.
 */
public final class ScriptGrammar {

    /** Element name → the attributes it must carry. */
    public static final Map<String, List<String>> REQUIRED = Map.ofEntries(
        Map.entry("arg-component", List.of("name")),
        Map.entry("arg-match-attr", List.of("name")),
        Map.entry("component", List.of("name")),
        Map.entry("do-add-dest-attr-value", List.of("name")),
        Map.entry("do-add-dest-object", List.of("class-name")),
        Map.entry("do-add-resource", List.of("resource-id", "id", "url")),
        Map.entry("do-add-role", List.of("role-id", "id", "url")),
        Map.entry("do-add-src-attr-value", List.of("name")),
        Map.entry("do-add-src-object", List.of("class-name")),
        Map.entry("do-append-xml-element", List.of("name", "expression")),
        Map.entry("do-append-xml-text", List.of("expression")),
        Map.entry("do-clear-dest-attr-value", List.of("name")),
        Map.entry("do-clear-op-property", List.of("name")),
        Map.entry("do-clear-src-attr-value", List.of("name")),
        Map.entry("do-clear-sso-credential", List.of("store-def-dn", "app-id")),
        Map.entry("do-clone-op-attr", List.of("src-name", "dest-name")),
        Map.entry("do-clone-xpath", List.of("src-expression", "dest-expression")),
        Map.entry("do-create-resource", List.of("resource-name", "id", "url")),
        Map.entry("do-create-role", List.of("role-name", "id", "url")),
        Map.entry("do-generate-event", List.of("id")),
        Map.entry("do-generate-xdas-event", List.of("name")),
        Map.entry("do-reformat-op-attr", List.of("name")),
        Map.entry("do-remove-dest-attr-value", List.of("name")),
        Map.entry("do-remove-resource", List.of("resource-id", "id", "url")),
        Map.entry("do-remove-role", List.of("role-id", "id", "url")),
        Map.entry("do-remove-src-attr-value", List.of("name")),
        Map.entry("do-rename-op-attr", List.of("src-name", "dest-name")),
        Map.entry("do-send-email", List.of("server")),
        Map.entry("do-send-email-from-template", List.of("notification-dn", "template-dn")),
        Map.entry("do-set-default-attr-value", List.of("name")),
        Map.entry("do-set-dest-attr-value", List.of("name")),
        Map.entry("do-set-local-variable", List.of("name")),
        Map.entry("do-set-op-property", List.of("name")),
        Map.entry("do-set-src-attr-value", List.of("name")),
        Map.entry("do-set-sso-credential", List.of("store-def-dn", "app-id")),
        Map.entry("do-set-sso-passphrase", List.of("store-def-dn")),
        Map.entry("do-set-xml-attr", List.of("name", "expression")),
        Map.entry("do-start-workflow", List.of("url", "id", "workflow-id")),
        Map.entry("do-status", List.of("level")),
        Map.entry("do-strip-op-attr", List.of("name")),
        Map.entry("do-strip-xpath", List.of("expression")),
        Map.entry("do-veto-if-op-attr-not-available", List.of("name")),
        Map.entry("if-association", List.of("op")),
        Map.entry("if-attr", List.of("op", "name")),
        Map.entry("if-class-name", List.of("op")),
        Map.entry("if-dest-attr", List.of("op", "name")),
        Map.entry("if-dest-dn", List.of("op")),
        Map.entry("if-entitlement", List.of("op", "name")),
        Map.entry("if-global-variable", List.of("op", "name")),
        Map.entry("if-local-variable", List.of("op", "name")),
        Map.entry("if-named-password", List.of("op", "name")),
        Map.entry("if-op-attr", List.of("op", "name")),
        Map.entry("if-op-property", List.of("op", "name")),
        Map.entry("if-operation", List.of("op")),
        Map.entry("if-password", List.of("op")),
        Map.entry("if-src-attr", List.of("op", "name")),
        Map.entry("if-src-dn", List.of("op")),
        Map.entry("if-xml-attr", List.of("op", "name")),
        Map.entry("if-xpath", List.of("op")),
        Map.entry("include", List.of("name")),
        Map.entry("token-added-entitlement", List.of("name")),
        Map.entry("token-attr", List.of("name")),
        Map.entry("token-char", List.of("value")),
        Map.entry("token-convert-time", List.of("src-format", "dest-format")),
        Map.entry("token-dest-attr", List.of("name")),
        Map.entry("token-entitlement", List.of("name")),
        Map.entry("token-global-variable", List.of("name")),
        Map.entry("token-local-variable", List.of("name")),
        Map.entry("token-map", List.of("table", "src", "dest")),
        Map.entry("token-map-source-col", List.of("name")),
        Map.entry("token-named-password", List.of("name")),
        Map.entry("token-op-attr", List.of("name")),
        Map.entry("token-op-property", List.of("name")),
        Map.entry("token-removed-attr", List.of("name")),
        Map.entry("token-removed-entitlement", List.of("name")),
        Map.entry("token-replace-all", List.of("regex", "replace-with")),
        Map.entry("token-replace-first", List.of("regex", "replace-with")),
        Map.entry("token-resolve", List.of("datastore")),
        Map.entry("token-split", List.of("delimiter")),
        Map.entry("token-src-attr", List.of("name")),
        Map.entry("token-time", List.of("format")));

    private ScriptGrammar() {
    }
}
