package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One object of a User Application driver's {@code cn=AppConfig} subtree that the
 * tree does not model as a typed thing (forms and PRDs are {@link Form}/{@link Prd}):
 * a directory-abstraction entity, choice list or relationship, a role, resource,
 * attestation or SoD, a report, a navigation item, an authorization type, a web
 * application configuration, the two configuration objects, and the containers.
 * Held generically — its object classes and every attribute as text — in the
 * {@code ds-object} shape Designer's {@code .appconfig}, its digests and the User
 * Application Base package all share ({@code docs/appconfig.md} §1).
 *
 * <p>Identity is the {@link #path}: the RDN values below {@code cn=AppConfig},
 * outermost first, joined by {@code /} — {@code DirectoryModel/EntityDefs/user},
 * {@code RoleConfig/RoleDefs/Level20/System/provManager}. Attribute names are the
 * schema's spelling ({@link AppConfigPolicy#canonicalAttribute}); XML-valued
 * attributes ({@link AppConfigPolicy#isXmlAttribute}) hold the canonical
 * serialization so two sources compare equal.
 */
public final class AppObject {

    /** What an object is, from its structural class ({@link AppConfigPolicy#kindOf}). */
    public enum Kind {
        ENTITY("entity"), CHOICE("choice"), RELATIONSHIP("relationship"), DM_CONFIG("dm-config"),
        ROLE("role"), RESOURCE("resource"), ATTESTATION("attestation"), SOD("sod"), ROLE_CONFIG("role-config"),
        REPORT("report"), NAV_ITEM("nav-item"), AUTH_TYPE("auth-type"), WEB_APP_CONFIG("web-app-config"),
        /** A PRD or JSON form held generically (a package's provisioning document); the tree types them as {@link Prd}/{@link Form}. */
        PRD("prd"), FORM("form"),
        CONTAINER("container"), OTHER("other");

        public final String key;

        Kind(String key) {
            this.key = key;
        }

        /** The plural {@code --delete-all} takes and the mass-deletion guard reports ({@code roles}, {@code nav-items} …). */
        public String plural() {
            switch (this) {
                case ENTITY: return "entities";
                case PRD: return "prds";
                default: return key + "s";
            }
        }

        public static Kind byPlural(String plural) {
            for (Kind k : values()) {
                if (k.plural().equals(plural)) {
                    return k;
                }
            }
            return null;
        }

        public static Kind byKey(String key) {
            for (Kind k : values()) {
                if (k.key.equals(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** RDN values below {@code cn=AppConfig}, outermost first; the last is the object's own name. */
    public final List<String> segments;
    /** Object classes as the source lists them (structural, auxiliaries, {@code Top}). */
    public final List<String> classes = new ArrayList<>();
    /** Attributes in canonical spelling, values as text (XML attributes canonical), in a deterministic order. */
    public final Map<String, List<String>> attrs = new LinkedHashMap<>();
    /** Source extras kept losslessly: {@code dn}, package stamps (vault vocabulary), {@code objectClass}. */
    public final Map<String, String> meta = new LinkedHashMap<>();

    public AppObject(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("an AppConfig object needs at least its own name");
        }
        this.segments = List.copyOf(segments);
    }

    public static AppObject ofPath(String path) {
        return new AppObject(List.of(path.split("/")));
    }

    /** {@code DirectoryModel/EntityDefs/user}. */
    public String path() {
        return String.join("/", segments);
    }

    /** The object's own name (its {@code cn}). */
    public String name() {
        return segments.get(segments.size() - 1);
    }

    /** The parent's path, or {@code ""} for a direct child of AppConfig. */
    public String parentPath() {
        return segments.size() == 1 ? "" : String.join("/", segments.subList(0, segments.size() - 1));
    }

    /** The structural class this object is known by (never {@code Top} or an auxiliary). */
    public String structuralClass() {
        return AppConfigPolicy.structuralClass(classes);
    }

    public Kind kind() {
        return AppConfigPolicy.kindOf(structuralClass());
    }

    public boolean isContainer() {
        return kind() == Kind.CONTAINER;
    }

    public String first(String attr) {
        List<String> v = attrs.get(AppConfigPolicy.canonicalAttribute(attr));
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    public List<String> all(String attr) {
        List<String> v = attrs.get(AppConfigPolicy.canonicalAttribute(attr));
        return v == null ? List.of() : v;
    }

    public void put(String attr, List<String> values) {
        String name = AppConfigPolicy.canonicalAttribute(attr);
        List<String> vs = new ArrayList<>();
        for (String v : values) {
            vs.add(AppConfigPolicy.normalizeValue(name, v));
        }
        attrs.put(name, vs);
    }

    /** A display name: the English localized name when the object has one, else the cn. */
    public String displayName() {
        String names = first("nrfLocalizedNames");
        if (names == null) {
            names = first("srvprvLocalizedNames");
        }
        String en = AppConfigPolicy.localized(names).get("en");
        return en == null || en.isBlank() ? name() : en;
    }

    @Override
    public String toString() {
        return kind().key + " " + path();
    }
}
