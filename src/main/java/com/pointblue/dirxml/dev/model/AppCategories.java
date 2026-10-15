package com.pointblue.dirxml.dev.model;

import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Role and resource categories (issue #70, docs/appconfig.md §9). A category is a key in a choice list
 * of the Directory Abstraction Layer, {@code DirectoryModel/ChoiceDefs/roles-category} or
 * {@code resources-category}: a {@code <list-item>} with a {@code <key>} and a {@code <display>} per
 * language, in the object's XmlData. A role carries its keys in {@code nrfRoleCategoryKey}, a resource
 * in {@code nrfCategoryKey}. The container a role or resource sits in is a different thing.
 */
public final class AppCategories {

    private AppCategories() {
    }

    /** Which list: the roles' or the resources'. */
    public enum For {
        ROLE("role", "roles-category", "nrfRoleCategoryKey", AppObject.Kind.ROLE),
        RESOURCE("resource", "resources-category", "nrfCategoryKey", AppObject.Kind.RESOURCE);

        public final String key;
        public final String choice;
        public final String attr;
        public final AppObject.Kind kind;

        For(String key, String choice, String attr, AppObject.Kind kind) {
            this.key = key;
            this.choice = choice;
            this.attr = attr;
            this.kind = kind;
        }

        public String path() {
            return "DirectoryModel/ChoiceDefs/" + choice;
        }

        /** {@code role}/{@code roles} or {@code resource}/{@code resources}; null for anything else. */
        public static For of(String s) {
            if (s != null) {
                for (For f : values()) {
                    if (f.key.equalsIgnoreCase(s.trim()) || (f.key + "s").equalsIgnoreCase(s.trim())) {
                        return f;
                    }
                }
            }
            return null;
        }

        /** The list a role or resource takes its keys from; null for other kinds. */
        public static For of(AppObject.Kind kind) {
            for (For f : values()) {
                if (f.kind == kind) {
                    return f;
                }
            }
            return null;
        }
    }

    /** One category: its key and its label per language, in the list's order. */
    public record Category(String key, Map<String, String> labels) {
        /** The English label, else the first, else the key. */
        public String label() {
            String en = labels.get("en");
            return en != null ? en : labels.isEmpty() ? key : labels.values().iterator().next();
        }
    }

    /** The categories in the list, or null when the tree has no such list (or it does not parse). */
    public static List<Category> of(Provisioning p, For f) {
        AppObject choice = p == null ? null : p.object(f.path());
        if (choice == null || choice.first("XmlData") == null) {
            return null;
        }
        Document doc;
        try {
            doc = CanonicalXml.parse(choice.first("XmlData"));
        } catch (RuntimeException e) {
            return null;
        }
        List<Category> out = new ArrayList<>();
        for (Element item : Xds.childrenByName(doc.getDocumentElement(), "list-item")) {
            out.add(new Category(keyOf(item), labels(item)));
        }
        return out;
    }

    /** A {@code <list-item>}'s key, trimmed ("" when it has none). */
    public static String keyOf(Element item) {
        Element k = Xds.firstByName(item, "key");
        return k == null ? "" : k.getTextContent().trim();
    }

    private static Map<String, String> labels(Element item) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Element d : Xds.childrenByName(item, "display")) {
            Element label = Xds.firstByName(d, "label");
            m.put(d.getAttribute("xml:lang"), (label != null ? label.getTextContent() : d.getTextContent()).trim());
        }
        return m;
    }
}
