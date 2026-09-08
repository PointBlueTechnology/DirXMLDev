package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code Map} tokens: the table a {@code <token-map table="…">} names must exist in
 * the driver's reach (its own resources or the library), and the {@code src}/
 * {@code dest} columns it names must be columns of that table. A table reference
 * that goes through a local variable ({@code table="$dn$"}) is resolved by the engine
 * at run time and can't be checked statically — reported as {@code INFO}.
 *
 * <p>Codes: {@code mapping-table-missing} (E), {@code mapping-table-column} (E),
 * {@code mapping-table-dynamic} (I), {@code mapping-table-malformed} (E).
 */
public final class MappingTableCheck implements Check {

    @Override
    public String name() {
        return "mapping-tables";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        // The tables themselves
        List<Resource> all = new ArrayList<>(ds.library.resources);
        for (Driver d : ds.drivers) {
            all.addAll(d.resources);
        }
        for (Resource t : all) {
            if (t.isMappingTable()) {
                checkTable(t, r);
            }
        }
        // The references
        for (Policy p : ds.library.policies) {
            checkRefs(p, Model.mappingTables(ds, null), r);
        }
        for (Driver d : ds.drivers) {
            Map<String, Resource> tables = Model.mappingTables(ds, d);
            for (Policy p : Model.policies(d)) {
                checkRefs(p, tables, r);
            }
        }
    }

    private static void checkTable(Resource t, Report r) {
        if (t.content == null) {
            r.add(Finding.error("mapping-table-malformed", t.path(), "mapping table has no content"));
            return;
        }
        if (!"mapping-table".equals(t.content.getLocalName()) && !"mapping-table".equals(t.content.getNodeName())) {
            // The source labels it a mapping table but the content is something else
            // (seen in the wild: an <entitlement-configuration> resource with the
            // mapping-table content type). A Map token on it would fail; otherwise harmless.
            r.add(Finding.warning("resource-content-mismatch", t.path(),
                "content type says mapping table but the root element is <" + t.content.getNodeName() + ">"));
            return;
        }
        List<String> cols = columns(t.content);
        if (cols.isEmpty()) {
            r.add(Finding.error("mapping-table-malformed", t.path(), "mapping table defines no <col-def>"));
            return;
        }
        // The engine tolerates rows with too few / too many <col>s (missing cells
        // read as empty), so a ragged row loads — but it's almost always a mistake.
        List<String> ragged = new ArrayList<>();
        int rowNo = 0;
        for (Element row : Xds.childrenByName(t.content, "row")) {
            rowNo++;
            int n = Xds.childrenByName(row, "col").size();
            if (n != cols.size()) {
                ragged.add("row " + rowNo + " has " + n);
            }
        }
        if (!ragged.isEmpty()) {
            r.add(Finding.warning("mapping-table-ragged-row", t.path(),
                "table defines " + cols.size() + " column(s) but " + ragged.size() + " row(s) differ: "
                    + String.join(", ", ragged)));
        }
    }

    private static void checkRefs(Policy p, Map<String, Resource> tables, Report r) {
        if (p.content == null) {
            return;
        }
        List<String> dynamic = new ArrayList<>();
        for (Element map : Xds.descendantsByName(p.content, "token-map")) {
            String table = map.getAttribute("table");
            if (table == null || table.isBlank()) {
                r.add(Finding.error("mapping-table-missing", p.path(), "<token-map> has no table attribute"));
                continue;
            }
            if (table.contains("$")) {
                if (!dynamic.contains(table)) {
                    dynamic.add(table);
                }
                continue;
            }
            String name = Model.leafName(table);
            Resource t = tables.get(name);
            if (t == null) {
                r.add(Finding.error("mapping-table-missing", p.path(),
                    "<token-map> refers to table '" + table + "' (" + name + "), which is not in this driver's reach"
                        + (tables.isEmpty() ? " (no mapping tables in the set)" : "; available: " + String.join(", ", tables.keySet()))));
                continue;
            }
            if (t.content == null) {
                continue;   // reported by checkTable
            }
            List<String> cols = columns(t.content);
            String src = map.hasAttribute("src") ? map.getAttribute("src") : map.getAttribute("source");
            String dest = map.getAttribute("dest");
            for (String c : new String[] {src, dest}) {
                if (c != null && !c.isBlank() && !cols.contains(c)) {
                    r.add(Finding.error("mapping-table-column", p.path(),
                        "<token-map> names column '" + c + "' but table '" + name + "' has columns: " + String.join(", ", cols)));
                }
            }
        }
        if (!dynamic.isEmpty()) {
            r.add(Finding.info("mapping-table-dynamic", p.path(),
                "Map token table(s) resolved at run time, not checked here: " + String.join(", ", dynamic)));
        }
    }

    private static List<String> columns(Element table) {
        List<String> cols = new ArrayList<>();
        for (Element c : Xds.childrenByName(table, "col-def")) {
            cols.add(c.getAttribute("name"));
        }
        return cols;
    }
}
