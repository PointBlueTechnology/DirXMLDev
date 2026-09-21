package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A driver's provisioning configuration — the {@code cn=AppConfig} subtree of a
 * User Application driver: JSON forms and provisioning request definitions
 * (PRDs). Attached to {@link Driver#provisioning}; null when the driver has no
 * AppConfig. See {@code docs/forms.md} and {@code docs/spikes/json-forms-format.md}.
 */
public final class Provisioning {

    /** The {@code cn=AppConfig,<driver dn>} DN, when known. */
    public String dn;
    public final List<Form> forms = new ArrayList<>();
    public final List<Prd> prds = new ArrayList<>();
    /** Everything else under AppConfig ({@link AppObject}: entities, roles, resources, reports, nav items, containers …). */
    public final List<AppObject> objects = new ArrayList<>();
    public final Map<String, String> meta = new LinkedHashMap<>();

    public Form form(Form.Kind kind, String name) {
        for (Form f : forms) {
            if (f.kind == kind && f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** The first form with this name, regardless of kind (form names are unique in practice). */
    public Form formByName(String name) {
        for (Form f : forms) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }

    public Prd prd(String name) {
        for (Prd p : prds) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** The object at this path under AppConfig ({@code DirectoryModel/EntityDefs/user}), or null. */
    public AppObject object(String path) {
        for (AppObject o : objects) {
            if (o.path().equalsIgnoreCase(path)) {
                return o;
            }
        }
        return null;
    }

    /** Objects whose own name matches (any container), for lookups by name alone. */
    public List<AppObject> objectsNamed(String name) {
        List<AppObject> out = new ArrayList<>();
        for (AppObject o : objects) {
            if (o.name().equalsIgnoreCase(name)) {
                out.add(o);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "provisioning (" + forms.size() + " forms, " + prds.size() + " PRDs, " + objects.size() + " objects)";
    }
}
