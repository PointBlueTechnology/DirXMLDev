package com.pointblue.dirxml.dev.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The edit operations' part of docs/commands.md, written from the {@link Registry}: per operation
 * its synopsis, every argument (required or not, a flag or a value) and an example. The page keeps
 * it between {@link #BEGIN} and {@link #END}; CommandsReferenceTest fails while it is stale and
 * rewrites it with {@code -Dcommands.write=true}.
 */
public final class OperationsDoc {

    public static final String BEGIN = "<!-- BEGIN edit operations: written from Registry.java and examples.properties by OperationsDoc; "
        + "do not edit by hand (mvn -o test -Dtest=CommandsReferenceTest -Dcommands.write=true) -->";
    public static final String END = "<!-- END edit operations -->";

    /** Sections in the order the page shows them; an operation goes in the first whose prefix it has. */
    private static final String[][] SECTIONS = {
        {"Edit: policies, resources and rules", "policy.", "artifact.", "rule."},
        {"Edit: mapping tables", "mapping-table."},
        {"Edit: GCVs, filter, schema map and driver settings", "gcv.", "filter.", "schema-map.", "config.", "driver."},
        {"Edit: environments", "override.", "environments.", "known."},
        {"Edit: packages", "package."},
        {"Edit: vault schema", "schema."},
        {"Edit: forms", "form."},
        {"Edit: workflows (PRDs)", "prd.", "flow."},
        {"Edit: entitlements", "entitlement."},
        {"Edit: AppConfig, roles and resources", "appconfig.", "role.", "resource.", "category.", "entity."},
    };

    private OperationsDoc() {
    }

    public static String markdown() {
        Map<String, List<Registry.Spec>> bySection = new LinkedHashMap<>();
        for (String[] s : SECTIONS) {
            bySection.put(s[0], new ArrayList<>());
        }
        for (Registry.Spec spec : Registry.all()) {
            String section = sectionOf(spec.name);
            if (section == null) {
                throw new IllegalStateException("operation '" + spec.name + "' has no section in OperationsDoc.SECTIONS");
            }
            bySection.get(section).add(spec);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(BEGIN).append("\n\n");
        for (Map.Entry<String, List<Registry.Spec>> e : bySection.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            sb.append("## ").append(e.getKey()).append("\n\n");
            for (Registry.Spec spec : e.getValue()) {
                entry(sb, spec);
            }
        }
        sb.append(END).append('\n');
        return sb.toString();
    }

    private static String sectionOf(String op) {
        for (String[] s : SECTIONS) {
            for (int i = 1; i < s.length; i++) {
                if (op.startsWith(s[i])) {
                    return s[0];
                }
            }
        }
        return null;
    }

    private static void entry(StringBuilder sb, Registry.Spec spec) {
        sb.append("### `").append(spec.name).append("`\n\n");
        sb.append(sentence(spec.help)).append("\n\n");
        sb.append("```\nbin/idm ").append(spec.name).append(" <tree>");
        for (Registry.Arg a : spec.args) {
            String one = a.flag ? "--" + a.name : "--" + a.name + " <" + a.name + ">";
            sb.append(a.required ? " " + one : " [" + one + "]");
        }
        sb.append(" [--dry-run] [--force] [--json]\n```\n\n");
        sb.append("| Argument | Required | Values |\n|---|---|---|\n");
        sb.append("| `<tree>` | yes | the IDM-as-code tree to change |\n");
        for (Registry.Arg a : spec.args) {
            sb.append("| `--").append(a.name).append("` | ")
                .append(a.required ? "yes" : a.flag ? "no, a flag" : "no").append(" | ")
                .append(cell(a.flag ? flagHelp(a.help) : a.help)).append(" |\n");
        }
        sb.append('\n');
        String example = Registry.example(spec.name);
        if (example != null) {
            sb.append("```bash\nbin/idm ").append(spec.name).append(" tree ").append(example).append("\n```\n\n");
        }
    }

    /** The help without the word that marks a flag, which the Required column now says. */
    static String flagHelp(String help) {
        String h = help.replaceFirst("^flag:\\s*", "").replace(" (flag)", "").replace("(flag; ", "(");
        return h.isEmpty() ? help : h;
    }

    /** A registry help line as a sentence: capital first letter, a full stop. */
    static String sentence(String help) {
        String h = help.isEmpty() ? help : Character.toUpperCase(help.charAt(0)) + help.substring(1);
        h = h.replace("<", "&lt;").replace(">", "&gt;");
        return h.endsWith(".") ? h : h + ".";
    }

    /** Text for a table cell: a pipe would end the cell, and an angle bracket would read as HTML. */
    static String cell(String text) {
        return text.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;");
    }
}
