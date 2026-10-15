package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.AppCategories;
import com.pointblue.dirxml.dev.model.AppCategories.Category;
import com.pointblue.dirxml.dev.model.AppCategories.For;
import static com.pointblue.dirxml.dev.model.AppCategories.keyOf;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Editing the role and resource category lists ({@link AppCategories}; issue #70, docs/appconfig.md §9). */
public final class CategoryOps {

    private CategoryOps() {
    }

    static For forOf(String s) throws Operation.Refusal {
        For f = For.of(s);
        if (f == null) {
            throw new Operation.Refusal("--for must be role or resource");
        }
        return f;
    }

    /**
     * The keys as the list has them (matched without regard to case), sorted. An unknown key is refused,
     * naming the list's keys. When the tree has no list the keys are taken as given (lower-cased) and a
     * note says they were not checked.
     */
    static List<String> resolve(Provisioning p, For f, List<String> given, Transaction tx) throws Operation.Refusal {
        List<Category> cats = AppCategories.of(p, f);
        List<String> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String g : given) {
            String key = null;
            if (cats == null) {
                key = g.trim().toLowerCase(Locale.ROOT);
            } else {
                for (Category c : cats) {
                    if (c.key().equalsIgnoreCase(g.trim())) {
                        key = c.key();
                    }
                }
            }
            if (key == null) {
                unknown.add(g.trim());
            } else if (!out.contains(key)) {
                out.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (Category c : cats) {
                keys.add(c.key());
            }
            throw new Operation.Refusal("no " + f.key + " categor" + (unknown.size() == 1 ? "y '" : "ies '") + String.join("', '", unknown)
                + "' in " + f.path() + " (" + (keys.isEmpty() ? "it has none" : "its keys: " + String.join(", ", keys))
                + "); add one with category.add --for " + f.key);
        }
        if (cats == null) {
            tx.note("category keys not checked: the tree has no " + f.path());
        }
        out.sort(null);
        return out;
    }

    /** Every role or resource that names {@code key}, by path. */
    static List<String> users(Provisioning p, For f, String key) {
        List<String> out = new ArrayList<>();
        for (AppObject o : p.objects) {
            if (o.kind() == f.kind) {
                for (String v : o.all(f.attr)) {
                    if (v.equalsIgnoreCase(key)) {
                        out.add(o.path());
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static AppObject listOrRefuse(Provisioning p, For f) throws Operation.Refusal {
        AppObject choice = p.object(f.path());
        if (choice == null) {
            throw new Operation.Refusal("the tree has no " + f.path() + " (import-live to adopt the directory model first)");
        }
        return choice;
    }

    private static Document parse(AppObject choice) throws Operation.Refusal {
        String xml = choice.first("XmlData");
        try {
            Document doc = CanonicalXml.parse(xml == null ? "<list-items/>" : xml);
            if (!doc.getDocumentElement().getNodeName().equals("list-items")) {
                throw new Operation.Refusal(choice.path() + " XmlData is not a <list-items> document");
            }
            return doc;
        } catch (RuntimeException ex) {
            throw new Operation.Refusal(choice.path() + " XmlData does not parse: " + ex.getMessage());
        }
    }

    private static Element item(Document doc, String key) {
        for (Element item : Xds.childrenByName(doc.getDocumentElement(), "list-item")) {
            if (keyOf(item).equalsIgnoreCase(key)) {
                return item;
            }
        }
        return null;
    }

    private static void store(Transaction tx, Driver d, AppObject choice, Document doc, String before) {
        choice.put("XmlData", List.of(CanonicalXml.serialize(doc)));
        AppConfigOps.customize(tx, d, choice, before);
        tx.touched(AppConfigOps.path(d, choice));
    }

    /** {@code category.add}: a new key in the role or resource list, with its labels. */
    public static final class Add implements Operation {
        private final String driver;
        private final String forWhat;
        private final String key;
        private final List<String> display;

        public Add(String driver, String forWhat, String key, String display) {
            this.driver = driver;
            this.forWhat = forWhat;
            this.key = key == null ? null : key.trim();
            this.display = AppConfigOps.list(display);
        }

        @Override
        public String name() {
            return "category.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            For f = forOf(forWhat);
            if (key == null || key.isEmpty()) {
                throw new Operation.Refusal("--key is required");
            }
            if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new Operation.Refusal("--key '" + key + "': letters, digits, '.', '_' and '-' only (it is stored on every role or resource in the category)");
            }
            Driver d = AppConfigOps.driverFor(ds, driver);
            AppObject choice = listOrRefuse(d.provisioning, f);
            String before = AppConfigOps.snapshot(choice);
            Document doc = parse(choice);
            Element existing = item(doc, key);
            if (existing != null) {
                throw new Operation.Refusal(f.path() + " already has the key '" + keyOf(existing) + "'");
            }
            Element it = doc.createElementNS(null, "list-item");
            Element k = doc.createElementNS(null, "key");
            k.appendChild(doc.createTextNode(key));
            it.appendChild(k);
            doc.getDocumentElement().appendChild(it);
            AppConfigOps.setDisplays(doc, it, display.isEmpty() ? List.of(key) : display);
            store(tx, d, choice, doc, before);
            tx.note("added " + f.key + " category '" + key + "' to " + f.path());
        }
    }

    /** {@code category.set}: change a key's labels (merged per language). */
    public static final class Set implements Operation {
        private final String driver;
        private final String forWhat;
        private final String key;
        private final List<String> display;

        public Set(String driver, String forWhat, String key, String display) {
            this.driver = driver;
            this.forWhat = forWhat;
            this.key = key == null ? null : key.trim();
            this.display = AppConfigOps.list(display);
        }

        @Override
        public String name() {
            return "category.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            For f = forOf(forWhat);
            if (key == null || key.isEmpty() || display.isEmpty()) {
                throw new Operation.Refusal("--key and --display are required");
            }
            Driver d = AppConfigOps.driverFor(ds, driver);
            AppObject choice = listOrRefuse(d.provisioning, f);
            String before = AppConfigOps.snapshot(choice);
            Document doc = parse(choice);
            Element it = item(doc, key);
            if (it == null) {
                throw new Operation.Refusal(f.path() + " has no key '" + key + "'");
            }
            String was = CanonicalXml.serialize(doc);
            AppConfigOps.setDisplays(doc, it, display);
            if (CanonicalXml.serialize(doc).equals(was)) {
                throw new Operation.Refusal("nothing to change on category '" + keyOf(it) + "'");
            }
            store(tx, d, choice, doc, before);
            tx.note("changed the labels of " + f.key + " category '" + keyOf(it) + "'");
        }
    }

    /** {@code category.remove}: delete a key; refused while a role or resource names it. */
    public static final class Remove implements Operation {
        private final String driver;
        private final String forWhat;
        private final String key;

        public Remove(String driver, String forWhat, String key) {
            this.driver = driver;
            this.forWhat = forWhat;
            this.key = key == null ? null : key.trim();
        }

        @Override
        public String name() {
            return "category.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            For f = forOf(forWhat);
            if (key == null || key.isEmpty()) {
                throw new Operation.Refusal("--key is required");
            }
            Driver d = AppConfigOps.driverFor(ds, driver);
            AppObject choice = listOrRefuse(d.provisioning, f);
            String before = AppConfigOps.snapshot(choice);
            Document doc = parse(choice);
            Element it = item(doc, key);
            if (it == null) {
                throw new Operation.Refusal(f.path() + " has no key '" + key + "'");
            }
            List<String> using = users(d.provisioning, f, keyOf(it));
            if (!using.isEmpty()) {
                StringBuilder sb = new StringBuilder(f.key + " category '" + keyOf(it) + "' is still used by:");
                for (String u : using) {
                    sb.append("\n  ").append(u);
                }
                throw new Operation.Refusal(sb.toString());
            }
            it.getParentNode().removeChild(it);
            store(tx, d, choice, doc, before);
            tx.note("removed " + f.key + " category '" + keyOf(it) + "' from " + f.path());
        }
    }
}
