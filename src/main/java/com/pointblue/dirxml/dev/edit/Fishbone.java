package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Designer-style policy-flow fishbone of one driver, as the JSON the VS Code / Cursor
 * extension ({@code extensions/dirxmldev-visual}) renders — built from the model, so the tree's
 * manifests have one reader ({@code AsCodeReader}) and the picture follows model changes
 * (docs/vscode-extension-v1.md §6.3). The shape is the extension's {@code FishboneModel}:
 * <pre>
 *   { treeRoot, driverSet: {name, dn}, driver: {name, dn, shimClass, dir},
 *     publisher: [bone…], subscriber: [bone…], spine: [bone…], resources: [bone…], filter?: {id, file} }
 *   bone   = { id: "bone:&lt;key&gt;", key, label, channel, setId, policies: [policy…] }
 *   policy = { id: "policy:&lt;key&gt;:&lt;order&gt;:&lt;ref&gt;", ref, name, order, kind, file?, unresolved }
 * </pre>
 * Keys are {@link PolicySet}'s, labels Designer's; {@code file} is the content file relative to
 * the tree root, named the way {@link AsCodeWriter} names it, and absent when the link does not
 * resolve to an artifact of the tree.
 */
public final class Fishbone {

    /** One row of the picture: a policy set, where it is drawn, and Designer's name for it. */
    public static final class Bone {
        public final PolicySet set;
        public final String label;
        public final String channel;

        Bone(PolicySet set, String label, String channel) {
            this.set = set;
            this.label = label;
            this.channel = channel;
        }
    }

    /** Aligned ribs, Identity Vault → Application (Designer's overview). */
    public static final List<Bone> PUBLISHER = List.of(
        new Bone(PolicySet.PUB_EVENT, "Event Transformation", "publisher"),
        new Bone(PolicySet.PUB_MATCH, "Matching", "publisher"),
        new Bone(PolicySet.PUB_CREATE, "Creation", "publisher"),
        new Bone(PolicySet.PUB_PLACEMENT, "Placement", "publisher"),
        new Bone(PolicySet.PUB_COMMAND, "Command Transformation", "publisher"));
    public static final List<Bone> SUBSCRIBER = List.of(
        new Bone(PolicySet.SUB_EVENT, "Event Transformation", "subscriber"),
        new Bone(PolicySet.SUB_MATCH, "Matching", "subscriber"),
        new Bone(PolicySet.SUB_CREATE, "Creation", "subscriber"),
        new Bone(PolicySet.SUB_PLACEMENT, "Placement", "subscriber"),
        new Bone(PolicySet.SUB_COMMAND, "Command Transformation", "subscriber"));
    public static final List<Bone> SPINE = List.of(
        new Bone(PolicySet.SCHEMA_MAPPING, "Schema Mapping", "spine"),
        new Bone(PolicySet.INPUT, "Input Transformation", "spine"),
        new Bone(PolicySet.OUTPUT, "Output Transformation", "spine"));
    public static final List<Bone> RESOURCES = List.of(
        new Bone(PolicySet.ECMASCRIPT, "ECMAScript", "driver"),
        new Bone(PolicySet.GCV, "GCVs", "driver"),
        new Bone(PolicySet.STARTUP, "Startup", "driver"),
        new Bone(PolicySet.SHUTDOWN, "Shutdown", "driver"));

    public static final String FILTER_NODE = "config:driver-filter";

    private Fishbone() {
    }

    /** The fishbone of {@code d} as JSON-ready maps and lists ({@code root} is the tree the model came from). */
    public static Map<String, Object> model(DriverSet ds, Path root, Driver d) {
        Map<String, String> files = AsCodeWriter.files(ds);
        Map<String, Artifact> index = ds.index();
        String dir = "drivers/" + AsCodeWriter.fileSafe(d.name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treeRoot", root == null ? "" : root.toAbsolutePath().normalize().toString());
        m.put("driverSet", map("name", ds.name, "dn", ds.dn));
        m.put("driver", map("name", d.name, "dn", d.dn, "shimClass", d.shimClass, "dir", dir));
        m.put("publisher", bones(d, PUBLISHER, index, files));
        m.put("subscriber", bones(d, SUBSCRIBER, index, files));
        m.put("spine", bones(d, SPINE, index, files));
        m.put("resources", bones(d, RESOURCES, index, files));
        if (d.config.get(Driver.DRIVER_FILTER) != null) {
            m.put("filter", map("id", FILTER_NODE, "file", dir + "/" + Driver.DRIVER_FILTER + ".xml"));
        }
        return m;
    }

    private static List<Object> bones(Driver d, List<Bone> defs, Map<String, Artifact> index, Map<String, String> files) {
        List<Object> out = new ArrayList<>();
        for (Bone b : defs) {
            List<PolicyLink> links = new ArrayList<>(d.links(b.set));
            links.sort(Comparator.comparingInt(l -> l.order));
            List<Object> policies = new ArrayList<>();
            for (PolicyLink l : links) {
                Artifact a = index.get(l.ref);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", "policy:" + b.set.key + ":" + l.order + ":" + l.ref);
                p.put("ref", l.ref);
                p.put("name", a == null ? leaf(l.ref) : a.name);
                p.put("order", l.order);
                p.put("kind", a == null ? "unresolved" : kind(a));
                String file = a == null ? null : files.get(a.path());
                if (file != null) {
                    p.put("file", file);
                }
                p.put("unresolved", a == null);
                policies.add(p);
            }
            Map<String, Object> bone = new LinkedHashMap<>();
            bone.put("id", "bone:" + b.set.key);
            bone.put("key", b.set.key);
            bone.put("label", b.label);
            bone.put("channel", b.channel);
            bone.put("setId", b.set.id);
            bone.put("policies", policies);
            out.add(bone);
        }
        return out;
    }

    /** {@code policy} / {@code resource}, as the manifest's {@code kind} attribute says. */
    private static String kind(Artifact a) {
        return a instanceof Policy ? "policy" : a.kind();
    }

    /** The drivers of a tree, for a picker: name, dn, shim class, directory. */
    public static Map<String, Object> drivers(DriverSet ds, Path root) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treeRoot", root == null ? "" : root.toAbsolutePath().normalize().toString());
        m.put("driverSet", map("name", ds.name, "dn", ds.dn));
        List<Object> list = new ArrayList<>();
        for (Driver d : ds.drivers) {
            list.add(map("name", d.name, "dn", d.dn, "shimClass", d.shimClass, "dir", "drivers/" + AsCodeWriter.fileSafe(d.name)));
        }
        m.put("drivers", list);
        return m;
    }

    /** The same picture as text: every bone with its policies in order, unresolved ones marked. */
    public static String text(Map<String, Object> model) {
        StringBuilder sb = new StringBuilder();
        Map<?, ?> driver = (Map<?, ?>) model.get("driver");
        sb.append("fishbone of ").append(driver.get("name")).append('\n');
        for (String section : List.of("publisher", "spine", "subscriber", "resources")) {
            sb.append(section).append(":\n");
            for (Object o : (List<?>) model.get(section)) {
                Map<?, ?> bone = (Map<?, ?>) o;
                List<?> policies = (List<?>) bone.get("policies");
                sb.append(String.format("  %-24s %s%n", bone.get("label") + " (" + bone.get("key") + ")",
                    policies.isEmpty() ? "-" : policies.size() + " policy(ies)"));
                for (Object po : policies) {
                    Map<?, ?> p = (Map<?, ?>) po;
                    sb.append(String.format("    %2s  %-10s %s%s%n", p.get("order"), p.get("kind"), p.get("ref"),
                        Boolean.TRUE.equals(p.get("unresolved")) ? "  (unresolved)" : ""));
                }
            }
        }
        if (model.get("filter") != null) {
            sb.append("filter: ").append(((Map<?, ?>) model.get("filter")).get("file")).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return m;
    }

    private static String leaf(String ref) {
        int i = ref.lastIndexOf('/');
        return i < 0 ? ref : ref.substring(i + 1);
    }
}
