package com.pointblue.dirxml.dev.forms;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.edit.FormOps;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.FormDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code idm form.preview <tree> <form> [--driver D] [--out page.html] [--lang en]}
 *
 * <p>Writes a self-contained HTML page that renders the form with the open-source Form.io
 * renderer (vendored, MIT) and placeholder components for the NetIQ custom types
 * (docs/forms.md, Option C). It answers "does it look like a form, are the fields in the
 * right place, does the conditional hide what it should" offline; it is not the vendor
 * renderer and says so on the page. Field outline and binding notes are printed alongside.
 */
public final class FormPreview {

    private FormPreview() {
    }

    public static int run(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: form.preview <tree> <form> [--driver D] [--out page.html] [--lang en]");
            return 2;
        }
        Path tree = Paths.get(args[1]);
        String ref = args[2];
        Map<String, String> opts = new HashMap<>();
        for (int i = 3; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) {
                opts.put(args[i].substring(2), args[i + 1]);
            }
        }
        try {
            DriverSet ds = AsCodeReader.read(tree);
            FormOps.Found found = FormOps.find(ds, ref, opts.get("driver"));
            if (found == null) {
                System.err.println("form.preview: form '" + ref + "' not found" + (opts.containsKey("driver") ? "" : " (or on several drivers — say --driver)"));
                return 1;
            }
            String html = html(found.driver, found.form, opts.getOrDefault("lang", "en"));
            Path out = Paths.get(opts.getOrDefault("out", safe(found.form.name) + ".preview.html"));
            Files.writeString(out, html, StandardCharsets.UTF_8);
            System.out.println("wrote " + out.toAbsolutePath() + " (" + html.length() / 1024 + " KB; open it in a browser)");
            return 0;
        } catch (IOException e) {
            System.err.println("form.preview: " + e.getMessage());
            return 1;
        }
    }

    /** The page: vendored renderer + stubs inline, the document inline, an outline beside it. */
    public static String html(Driver d, Form f, String lang) throws IOException {
        Object parsed = Json.parse(f.json);
        String schema = Json.compact(parsed);
        FormDocument doc = f.document();
        StringBuilder outline = new StringBuilder();
        for (FormDocument.Component c : doc.components()) {
            outline.append("<li><code>").append(esc(c.key)).append("</code> <span class=\"t\">").append(esc(c.type)).append("</span>")
                .append(c.required ? " <span class=\"req\">required</span>" : "")
                .append(c.hidden ? " <span class=\"hid\">hidden</span>" : "")
                .append(c.conditional != null && !c.conditional.isEmpty() ? " <span class=\"cond\">conditional</span>" : "")
                .append(c.label == null || c.label.isEmpty() ? "" : " — " + esc(c.label))
                .append("</li>\n");
        }
        List<String> langs = doc.languages();
        String i18n = "{}";
        if (parsed instanceof Map && ((Map<?, ?>) parsed).get("localization") instanceof Map) {
            i18n = Json.compact(((Map<?, ?>) parsed).get("localization"));
        }
        return "<!doctype html>\n<html lang=\"" + esc(lang) + "\">\n<head>\n<meta charset=\"utf-8\">\n<title>"
            + esc(f.name) + " — form preview</title>\n<style>\n" + resource("formio.full.min.css") + "\n"
            + "body{margin:0;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;background:#f4f5f7;color:#222}\n"
            + ".bar{background:#1d2b3a;color:#fff;padding:.6rem 1rem;font-size:14px}.bar b{font-weight:600}.bar .warn{color:#ffd166;margin-left:1rem}\n"
            + ".wrap{display:grid;grid-template-columns:minmax(0,1fr) 340px;gap:1rem;padding:1rem}\n"
            + ".card{background:#fff;border:1px solid #d9dde3;border-radius:6px;padding:1rem}\n"
            + ".side h3{margin:.2rem 0 .6rem;font-size:14px}.side ul{list-style:none;padding:0;margin:0;font-size:12.5px;line-height:1.7}\n"
            + ".t{color:#666}.req{color:#b00020}.hid{color:#888;font-style:italic}.cond{color:#0a6}\n"
            + ".idm-stub-badge{display:inline-block;font-size:10px;background:#ffe9a8;color:#5a4300;border-radius:3px;padding:0 4px;margin-right:4px;vertical-align:middle}\n"
            + ".idm-title{margin:.4rem 0 .8rem;color:#1a73a8;font-weight:500}\n"
            + "@media(max-width:900px){.wrap{grid-template-columns:1fr}}\n</style>\n</head>\n<body>\n"
            + "<div class=\"bar\"><b>" + esc(f.name) + "</b> — " + esc(f.kind.dir) + " form of driver <b>" + esc(d.name) + "</b>"
            + " <span class=\"warn\">preview: open-source Form.io renderer with placeholders for NetIQ components — not the vendor renderer</span></div>\n"
            + "<div class=\"wrap\">\n<div class=\"card\"><div id=\"form\"></div></div>\n"
            + "<div class=\"card side\"><h3>Fields (" + doc.components().size() + ")</h3><ul>\n" + outline + "</ul>\n"
            + "<h3>Languages</h3><div style=\"font-size:12.5px\">" + esc(String.join(", ", langs)) + "</div>\n"
            + (doc.hasInlineScripts() ? "<h3>Scripts</h3><div style=\"font-size:12.5px\">inline scripts present (not executed here)</div>\n" : "")
            + "</div>\n</div>\n"
            + "<script>\n" + resource("formio.full.min.js") + "\n</script>\n"
            + "<script>\n" + resource("idm-components.js") + "\n</script>\n"
            + "<script>\nvar schema = " + schema + ";\nvar i18n = " + i18n + ";\n"
            + "schema.display = schema.display === 'workflowWizard' ? 'wizard' : (schema.display || 'form');\n"
            // live data sources (/WFHandler …) belong to the Identity Applications: show them as empty lists, never fetch
            + "(function neutralize(list) { (list || []).forEach(function (c) {\n"
            + "  if (c.dataSrc === 'url' || c.dataSrc === 'resource') { c.dataSrc = 'values'; c.data = {values: []}; c.placeholder = (c.placeholder || '') + ' [live data source — not fetched in preview]'; }\n"
            + "  neutralize(c.components); (c.columns || []).forEach(function (col) { neutralize(col.components); }); (c.rows || []).forEach(function (r) { r.forEach(function (cell) { neutralize(cell.components); }); });\n"
            + "}); })(schema.components);\n"
            + "Formio.createForm(document.getElementById('form'), schema, {language: " + Json.compact(lang) + ", i18n: i18n, noAlerts: true})\n"
            + "  .then(function (form) { window.idmForm = form; form.on('submit', function (s) { console.log('submit', s.data); form.emit('submitDone', s); }); })\n"
            + "  .catch(function (e) { document.getElementById('form').innerHTML = '<pre style=\"color:#b00020\">' + (e && e.message || e) + '</pre>'; });\n"
            + "</script>\n</body>\n</html>\n";
    }

    static String resource(String name) throws IOException {
        try (InputStream in = FormPreview.class.getResourceAsStream("/forms/preview/" + name)) {
            if (in == null) {
                throw new IOException("missing resource forms/preview/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
