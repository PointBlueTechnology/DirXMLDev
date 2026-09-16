package com.pointblue.dirxml.dev.flow;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code idm prd.flow <tree> <prd> [--driver D] [--format text|mermaid] [--lang en] [--out FILE]}
 *
 * <p>Renders a PRD's {@link Flow} for human review without the Identity Applications or
 * Designer: {@code text} (default) walks the graph breadth-first from the start activity in
 * link order, one line per activity with its key attributes and outgoing links, then a
 * "Data items" section; {@code mermaid} emits a {@code flowchart TD} a diagram tool can
 * render. An activity the walk never reaches (a dangling piece {@link
 * com.pointblue.dirxml.dev.validate.FlowCheck} would flag) is still listed, appended in
 * document order and marked {@code (unreachable)}.
 */
public final class FlowView {

    private FlowView() {
    }

    public static int run(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: prd.flow <tree> <prd> [--driver D] [--format text|mermaid] [--lang en] [--out FILE]");
            return 2;
        }
        Path tree = Paths.get(args[1]);
        String name = args[2];
        String driverName = flag(args, "--driver");
        String format = flag(args, "--format");
        if (format == null) {
            format = "text";
        }
        String lang = flag(args, "--lang");
        if (lang == null) {
            lang = "en";
        }
        String out = flag(args, "--out");

        try {
            DriverSet ds = AsCodeReader.read(tree);
            Driver owner = null;
            Prd prd = null;
            for (Driver d : driversOf(ds, driverName)) {
                if (d.provisioning == null) {
                    continue;
                }
                Prd p = d.provisioning.prd(name);
                if (p != null) {
                    owner = d;
                    prd = p;
                    break;
                }
            }
            if (prd == null) {
                System.err.println("no PRD '" + name + "'" + (driverName == null ? "" : " on driver '" + driverName + "'"));
                return 1;
            }
            Flow flow = Flow.of(prd);
            if (flow == null) {
                System.err.println("PRD '" + name + "' has no <process> (classic PRD with no workflow, or unreadable)");
                return 1;
            }
            String rendered;
            if ("mermaid".equals(format)) {
                rendered = mermaid(flow, lang);
            } else if ("text".equals(format)) {
                rendered = text(owner, prd, flow, lang);
            } else {
                System.err.println("prd.flow: unknown --format '" + format + "' (want text or mermaid)");
                return 2;
            }
            if (out != null) {
                Files.writeString(Paths.get(out), rendered, StandardCharsets.UTF_8);
                System.out.println("wrote " + out);
            } else {
                System.out.print(rendered);
            }
            return 0;
        } catch (IOException e) {
            System.err.println("prd.flow: " + e.getMessage());
            return 1;
        }
    }

    // ---- text ---------------------------------------------------------------------------------

    static String text(Driver owner, Prd prd, Flow flow, String lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(owner.name).append('/').append(prd.name).append('\n');
        sb.append("  process id=").append(str(flow.id)).append('\n');
        sb.append("  version=").append(str(flow.version))
            .append(" process-type=").append(str(flow.processType))
            .append(" flow-strategy=").append(str(flow.flowStrategy))
            .append(" status=").append(str(prd.property("status")))
            .append('\n');
        sb.append('\n');

        List<Flow.Activity> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Flow.Activity start = flow.start();
        if (start != null && start.id != null) {
            Deque<Flow.Activity> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start.id);
            while (!queue.isEmpty()) {
                Flow.Activity cur = queue.poll();
                ordered.add(cur);
                for (Flow.Link l : flow.outgoing(cur.id)) {
                    Flow.Activity next = flow.byId(l.target);
                    if (next != null && next.id != null && visited.add(next.id)) {
                        queue.add(next);
                    }
                }
            }
        }
        List<Flow.Activity> unreached = new ArrayList<>();
        for (Flow.Activity a : flow.activities) {
            if (a.id == null || !visited.contains(a.id)) {
                unreached.add(a);
            }
        }

        for (Flow.Activity a : ordered) {
            sb.append(activityLine(flow, a, lang, false)).append('\n');
        }
        for (Flow.Activity a : unreached) {
            sb.append(activityLine(flow, a, lang, true)).append('\n');
        }

        sb.append("\nData items:\n");
        for (Map.Entry<String, List<Flow.DataItem>> e : flow.dataItemsByActivity.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            sb.append("  ").append(str(e.getKey())).append(":\n");
            for (Flow.DataItem di : e.getValue()) {
                sb.append("    ").append(di.name).append(": ")
                    .append(di.source != null ? di.source : "")
                    .append(" \u2192 ")
                    .append(di.target != null ? di.target : "")
                    .append('\n');
            }
        }
        return sb.toString();
    }

    private static String activityLine(Flow flow, Flow.Activity a, String lang, boolean unreachable) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(str(a.id)).append("  ").append(elementName(a)).append("  \"")
            .append(str(a.displayName(lang))).append('"');
        String attrs = keyAttrs(a);
        if (!attrs.isEmpty()) {
            sb.append("  ").append(attrs);
        }
        if (a.id != null) {
            List<Flow.Link> out = flow.outgoing(a.id);
            if (!out.isEmpty()) {
                sb.append("  \u2192 ");
                boolean first = true;
                for (Flow.Link l : out) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(l.type).append(": ").append(l.target);
                }
            }
        }
        if (unreachable) {
            sb.append("  (unreachable)");
        }
        return sb.toString();
    }

    private static String keyAttrs(Flow.Activity a) {
        StringBuilder sb = new StringBuilder();
        switch (a.kind) {
            case USER:
                append(sb, "timeout", a.attr("timeout"));
                append(sb, "ontimeout", a.attr("ontimeout"));
                append(sb, "approver-type", a.attr("approver-type"));
                List<String> addressees = addresseeExprs(a.element);
                if (!addressees.isEmpty()) {
                    append(sb, "addressee", String.join(" | ", addressees));
                }
                String notifyTemplate = firstTemplate(a.element, "notify");
                if (notifyTemplate != null) {
                    append(sb, "notify", notifyTemplate);
                }
                break;
            case CONDITION:
                append(sb, "expression", textOfChild(a.element, "expression"));
                break;
            case PROVISION:
                append(sb, "category", a.attr("category"));
                append(sb, "entity-type", a.attr("entity-type"));
                append(sb, "operation", a.attr("operation"));
                break;
            case MERGE:
                append(sb, "branch-activity-id", a.attr("branch-activity-id"));
                break;
            case LOG:
                append(sb, "message", textOfChild(a.element, "message"));
                break;
            case BIND_ROLE:
            case BIND_RESOURCE_STATUS:
                append(sb, "action", a.attr("action"));
                break;
            case REST:
                appendRaw(sb, str(a.attr("method")) + " " + str(a.attr("protocol")) + "://"
                    + str(a.attr("host")) + ":" + str(a.attr("port")) + str(a.attr("path")));
                break;
            case ROLE_REQUEST: {
                String action = textOfChild(a.element, "action");
                List<String> roles = childTexts(a.element, "roles");
                List<String> targets = childTexts(a.element, "targets");
                appendRaw(sb, (action != null ? action + " " : "") + String.join(", ", roles) + " → " + String.join(", ", targets));
                break;
            }
            case RESOURCE_REQUEST: {
                String action = textOfChild(a.element, "action");
                String resource = textOfChild(a.element, "target-resource");
                List<String> users = childTexts(a.element, "target-user");
                appendRaw(sb, (action != null ? action + " " : "") + str(resource) + " → " + String.join(", ", users));
                break;
            }
            case START_CORRELATED_FLOW: {
                String processId = textOfChild(a.element, "processId");
                List<String> recipients = childTexts(a.element, "recipient");
                appendRaw(sb, str(processId) + " → " + String.join(", ", recipients));
                break;
            }
            default:
                break;
        }
        return sb.toString();
    }

    private static void appendRaw(StringBuilder sb, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }

    private static List<String> childTexts(Element parent, String name) {
        List<String> out = new ArrayList<>();
        for (Element c : Xds.childrenByName(parent, name)) {
            String t = Xds.text(c);
            if (t != null && !t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(key).append('=').append(value);
    }

    private static List<String> addresseeExprs(Element activity) {
        List<String> out = new ArrayList<>();
        for (Element ae : Xds.childrenByName(activity, "addressee")) {
            String v = Flow.attr(ae, "value");
            if (v == null) {
                v = Xds.text(ae);
            }
            if (v != null && !v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String firstTemplate(Element activity, String childName) {
        List<Element> els = Xds.childrenByName(activity, childName);
        if (els.isEmpty()) {
            return null;
        }
        return Flow.attr(els.get(0), "template");
    }

    private static String textOfChild(Element parent, String childName) {
        List<Element> els = Xds.childrenByName(parent, childName);
        if (els.isEmpty()) {
            return null;
        }
        return Xds.text(els.get(0));
    }

    private static String elementName(Flow.Activity a) {
        String ln = a.element.getLocalName();
        return ln != null ? ln : a.element.getNodeName();
    }

    // ---- mermaid --------------------------------------------------------------------------------

    static String mermaid(Flow flow, String lang) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");
        for (Flow.Activity a : flow.activities) {
            if (a.id == null) {
                continue;
            }
            String nid = sanitize(a.id);
            // #quot; (not a literal ") keeps the surrounding Mermaid node label a single valid
            // quoted string while still rendering as id\n"display name" per docs/workflows.md.
            String label = escapeMermaid(a.id) + "\\n#quot;" + escapeMermaid(str(a.displayName(lang))) + "#quot;";
            switch (a.kind) {
                case START:
                case FINISH:
                    sb.append("  ").append(nid).append("([\"").append(label).append("\"])\n");
                    break;
                case CONDITION:
                    sb.append("  ").append(nid).append("{\"").append(label).append("\"}\n");
                    break;
                case BRANCH:
                case MERGE:
                    sb.append("  ").append(nid).append("[/\"").append(label).append("\"/]\n");
                    break;
                case USER:
                    sb.append("  ").append(nid).append("[\"").append(label).append("\"]\n");
                    break;
                default:
                    sb.append("  ").append(nid).append("[[\"").append(label).append("\"]]\n");
                    break;
            }
        }
        for (Flow.Link l : flow.links) {
            if (l.source == null || l.target == null) {
                continue;
            }
            sb.append("  ").append(sanitize(l.source)).append(" -- ").append(str(l.type)).append(" --> ").append(sanitize(l.target)).append('\n');
        }
        return sb.toString();
    }

    private static String sanitize(String id) {
        String s = id.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) {
            s = "n_" + s;
        }
        return s;
    }

    private static String escapeMermaid(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "'");
    }

    // ---- small CLI helpers (duplicated to avoid a flow -> edit dependency) ------------------------

    private static List<Driver> driversOf(DriverSet ds, String driverName) {
        if (driverName == null) {
            return ds.drivers;
        }
        Driver d = ds.driver(driverName);
        return d == null ? List.of() : List.of(d);
    }

    private static String flag(String[] argv, String name) {
        for (int i = 0; i < argv.length - 1; i++) {
            if (argv[i].equals(name)) {
                return argv[i + 1];
            }
        }
        return null;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
