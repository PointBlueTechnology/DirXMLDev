package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mapping table as CSV (RFC 4180): the first line names the columns, the first column is the key,
 * each further line is a row. {@link #write} gives a table's CSV; {@link Import} replaces a table's
 * rows (and columns) from one, or with {@code merge} adds and updates rows by key.
 */
public final class MappingTableCsv {

    private MappingTableCsv() {
    }

    /** The table as CSV: a header of column names, then one line per row; CRLF line ends, quoted where needed. */
    public static String write(Element table) {
        StringBuilder sb = new StringBuilder();
        List<String> cols = ConfigOps.columns(table);
        line(sb, cols);
        for (Element row : Xds.childrenByName(table, "row")) {
            List<String> cells = new ArrayList<>();
            List<Element> rc = Xds.childrenByName(row, "col");
            for (int i = 0; i < cols.size(); i++) {
                cells.add(i < rc.size() ? Xds.text(rc.get(i)) : "");
            }
            line(sb, cells);
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String c = cells.get(i) == null ? "" : cells.get(i);
            if (c.indexOf(',') >= 0 || c.indexOf('"') >= 0 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0
                || (!c.isEmpty() && (Character.isWhitespace(c.charAt(0)) || Character.isWhitespace(c.charAt(c.length() - 1))))) {
                sb.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append("\r\n");
    }

    /**
     * RFC 4180 records: commas, double quotes (a doubled quote inside a quoted field), CRLF or LF line
     * ends, line breaks inside quoted fields; a leading byte-order mark is dropped and blank lines skipped.
     */
    public static List<List<String>> parse(String csv) throws IllegalArgumentException {
        String s = csv.startsWith("﻿") ? csv.substring(1) : csv;
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean sawQuoted = false;
        int i = 0;
        int line = 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                i++;
                continue;
            }
            if (c == '"') {
                if (field.length() > 0) {
                    throw new IllegalArgumentException("line " + line + ": a quote inside an unquoted field");
                }
                quoted = true;
                sawQuoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                sawQuoted = false;
            } else if (c == '\r' || c == '\n') {
                record.add(field.toString());
                field.setLength(0);
                if (!(record.size() == 1 && record.get(0).isEmpty() && !sawQuoted)) {
                    records.add(record);
                }
                record = new ArrayList<>();
                sawQuoted = false;
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                line++;
            } else {
                if (sawQuoted) {
                    throw new IllegalArgumentException("line " + line + ": text after a closing quote");
                }
                field.append(c);
            }
            i++;
        }
        if (quoted) {
            throw new IllegalArgumentException("line " + line + ": a quoted field is not closed");
        }
        if (field.length() > 0 || !record.isEmpty() || sawQuoted) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    /**
     * {@code mapping-table.import-csv}: the CSV's header names the columns, its first column the key. Without
     * {@code merge} the table becomes the CSV: its columns in the header's order (a column already in the table
     * keeps its type, a new one is {@code nocase}) and exactly its rows. With {@code merge} every header column
     * must exist or is added at the end, the rows are added or updated by key, and the other rows stay. A
     * duplicate key, an empty key or a row with more fields than the header is refused.
     */
    public static final class Import implements Operation {
        private final String path;
        private final String csv;
        private final boolean merge;

        public Import(String path, String csv, boolean merge) {
            this.path = path;
            this.csv = csv;
            this.merge = merge;
        }

        @Override
        public String name() {
            return "mapping-table.import-csv";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Resource t = ConfigOps.tableOrRefuse(ds, path);
            if (csv == null) {
                throw new Refusal("--csv-file is required");
            }
            List<List<String>> records;
            try {
                records = parse(csv);
            } catch (IllegalArgumentException e) {
                throw new Refusal("not CSV: " + e.getMessage());
            }
            if (records.isEmpty()) {
                throw new Refusal("the CSV is empty: its first line names the columns");
            }
            List<String> header = new ArrayList<>();
            for (String h : records.get(0)) {
                header.add(h.trim());
            }
            Set<String> seenCols = new HashSet<>();
            for (String h : header) {
                if (h.isEmpty()) {
                    throw new Refusal("the header has an empty column name");
                }
                if (!seenCols.add(h)) {
                    throw new Refusal("the header names column '" + h + "' twice");
                }
            }
            List<List<String>> rows = records.subList(1, records.size());
            Set<String> keys = new HashSet<>();
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (row.size() > header.size()) {
                    throw new Refusal("row " + (r + 2) + " has " + row.size() + " fields, the header " + header.size());
                }
                String key = row.isEmpty() ? "" : row.get(0).trim();
                if (key.isEmpty()) {
                    throw new Refusal("row " + (r + 2) + " has no key (the first column, '" + header.get(0) + "')");
                }
                if (!keys.add(key)) {
                    throw new Refusal("key '" + key + "' appears twice (row " + (r + 2) + ")");
                }
            }
            Element table = t.content;
            Document doc = table.getOwnerDocument();
            List<String> existing = ConfigOps.columns(table);
            if (merge && !existing.isEmpty() && !existing.get(0).equals(header.get(0))) {
                throw new Refusal("merging needs the key column first: the table's is '" + existing.get(0) + "', the CSV's '" + header.get(0) + "'");
            }
            tx.touch(t);
            Map<String, String> types = new HashMap<>();
            for (Element def : Xds.childrenByName(table, "col-def")) {
                types.put(def.getAttribute("name"), def.getAttribute("type"));
            }
            if (!merge) {
                for (Element e : new ArrayList<>(Xds.childrenByName(table, "col-def"))) {
                    table.removeChild(e);
                }
                for (Element e : new ArrayList<>(Xds.childrenByName(table, "row"))) {
                    table.removeChild(e);
                }
                for (String h : header) {
                    Element def = doc.createElementNS(null, "col-def");
                    def.setAttribute("name", h);
                    String ty = types.get(h);
                    def.setAttribute("type", ty == null || ty.isBlank() ? "nocase" : ty);
                    table.appendChild(def);
                }
                for (List<String> row : rows) {
                    Element re = doc.createElementNS(null, "row");
                    for (int c = 0; c < header.size(); c++) {
                        Element col = doc.createElementNS(null, "col");
                        col.setTextContent(c < row.size() ? (c == 0 ? row.get(c).trim() : row.get(c)) : "");
                        re.appendChild(col);
                    }
                    table.appendChild(re);
                }
                return;
            }
            for (String h : header) {
                if (!ConfigOps.columns(table).contains(h)) {
                    new ConfigOps.TableAddColumn(path, h, null).apply(ds, tx);
                }
            }
            for (List<String> row : rows) {
                Map<String, String> values = new java.util.LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    values.put(header.get(c), c < row.size() ? (c == 0 ? row.get(c).trim() : row.get(c)) : "");
                }
                new ConfigOps.TableSetRow(path, null, values).apply(ds, tx);
            }
        }
    }
}
