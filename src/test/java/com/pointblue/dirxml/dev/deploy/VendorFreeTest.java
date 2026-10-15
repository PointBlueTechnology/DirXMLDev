package com.pointblue.dirxml.dev.deploy;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Containment (docs/vendor-free-vault.md, verification 5). The directory-console must run with no
 * NetIQ/OpenText jar, so from exactly the DirXMLDev methods it calls, this follows every method their
 * bytecode can call (an interface or virtual call reaches every implementation in this project), and
 * fails if any reached instruction, catch type or signature names a vendor class. Open-source JLDAP
 * ({@code com.novell.ldap}) is allowed. Classes load lazily, so what the console never calls — the
 * tree, the validator, the simulator — may still use the engine.
 */
public class VendorFreeTest {

    /**
     * The console's calls into DirXMLDev (directory-console main at 06e2035, 2026-10-15): owner, method name
     * ("*" = all). When the console calls something new, add it here.
     */
    static final String[][] ROOTS = {
        {"com/pointblue/dirxml/dev/operate/Operate", "vaultEngine"},
        {"com/pointblue/dirxml/dev/operate/Operate", "driverStatus"},
        {"com/pointblue/dirxml/dev/operate/Operate", "logLevelShow"},
        {"com/pointblue/dirxml/dev/operate/Operate", "jobList"},
        {"com/pointblue/dirxml/dev/operate/Operate", "engineVersion"},
        {"com/pointblue/dirxml/dev/operate/Operate", "driversetServers"},
        {"com/pointblue/dirxml/dev/operate/Operate", "startOption"},
        {"com/pointblue/dirxml/dev/operate/Operate", "resync"},
        {"com/pointblue/dirxml/dev/operate/Operate", "passwordSync"},
        {"com/pointblue/dirxml/dev/operate/Operate", "migrate"},
        {"com/pointblue/dirxml/dev/operate/Operate", "migrateIntoApp"},
        {"com/pointblue/dirxml/dev/operate/Operate", "logLevelSet"},
        {"com/pointblue/dirxml/dev/operate/Operate", "lifecycle"},
        {"com/pointblue/dirxml/dev/operate/Operate", "jobAction"},
        {"com/pointblue/dirxml/dev/operate/Operate", "engineStats"},
        {"com/pointblue/dirxml/dev/operate/Operate", "driversetStatus"},
        {"com/pointblue/dirxml/dev/operate/Operate", "driversetActivation"},
        {"com/pointblue/dirxml/dev/operate/Operate", "driverHealth"},
        {"com/pointblue/dirxml/dev/operate/Operate", "driverAssociations"},
        {"com/pointblue/dirxml/dev/operate/Operate", "cacheClear"},
        {"com/pointblue/dirxml/dev/operate/Operate$Engine", "*"},
        {"com/pointblue/dirxml/dev/operate/Operate$Result", "*"},
        {"com/pointblue/dirxml/dev/operate/Operate$CachePage", "*"},
        {"com/pointblue/dirxml/dev/deploy/Vault", "open"},
        {"com/pointblue/dirxml/dev/deploy/Vault", "connect"},   // the CLI's vault commands
        {"com/pointblue/dirxml/dev/deploy/Vault", "stateName"},
        {"com/pointblue/dirxml/dev/deploy/Vault$Entry", "*"},
        {"com/pointblue/dirxml/dev/deploy/Vault$JobState", "*"},
        {"com/pointblue/dirxml/dev/deploy/Vault$VaultException", "*"},
        {"com/pointblue/dirxml/dev/deploy/IdmOps", "*"},
        {"com/pointblue/dirxml/dev/deploy/IdmOps$Reply", "*"},
        {"com/pointblue/dirxml/dev/deploy/Environments", "load"},
        {"com/pointblue/dirxml/dev/deploy/Environments$Environment", "*"},
        {"com/pointblue/dirxml/dev/LegacyTls", "enable"},
        {"com/pointblue/dirxml/dev/json/Json", "*"},
    };

    static boolean vendor(String internalName) {
        return internalName.startsWith("com/novell/") && !internalName.startsWith("com/novell/ldap/")
            || internalName.startsWith("novell/") || internalName.startsWith("com/netiq/");
    }

    /**
     * A string constant that names a class to load by reflection (JNDI's socket factory, Class.forName):
     * a vendor class, or a simulator class (the console does not ship the simulator). Data that merely
     * looks like a class name is listed here.
     */
    private static final Pattern REFLECTIVE = Pattern.compile("(com\\.novell\\.(?!ldap\\.)|novell\\.|com\\.netiq\\.|com\\.pointblue\\.dirxml\\.sim\\.)[\\w.$]+");
    static final Set<String> DATA = Set.of(
        "com.novell.nds.dirxml.job.ckdrvhealth.CheckDriverHealthJob");   // a job's class, written to the vault, never loaded

    static boolean reflective(String s) {
        return REFLECTIVE.matcher(s).matches() && !DATA.contains(s);
    }

    static boolean ours(String internalName) {
        return internalName.startsWith("com/pointblue/");
    }

    // ---- a minimal class-file reader: hierarchy, methods, and what their code names -----------------

    record Ref(String owner, String name, String desc) {
    }

    static final class Method {
        final String name;
        final String desc;
        final List<Ref> calls = new ArrayList<>();
        final Set<String> classes = new LinkedHashSet<>();   // named by instructions, catch types, the signature
        final Set<String> strings = new LinkedHashSet<>();   // string constants: a class named for reflection

        Method(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }
    }

    static final class ClassInfo {
        String name;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        final Map<String, Method> methods = new LinkedHashMap<>();
    }

    private final Map<String, ClassInfo> cache = new HashMap<>();

    ClassInfo load(String internalName) throws IOException {
        if (cache.containsKey(internalName)) {
            return cache.get(internalName);
        }
        ClassInfo ci = null;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in != null) {
                ci = parse(in.readAllBytes());
            }
        }
        cache.put(internalName, ci);
        return ci;
    }

    private static final Pattern DESC_CLASS = Pattern.compile("L([a-zA-Z_$][\\w$/]*);");

    static ClassInfo parse(byte[] bytes) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(bytes));
        d.readInt();
        d.readUnsignedShort();
        d.readUnsignedShort();
        int n = d.readUnsignedShort();
        Object[] cp = new Object[n];
        int[] tag = new int[n];
        for (int i = 1; i < n; i++) {
            tag[i] = d.readUnsignedByte();
            switch (tag[i]) {
                case 1 -> cp[i] = d.readUTF();
                case 7, 8, 16, 19, 20 -> cp[i] = d.readUnsignedShort();
                case 3, 4 -> d.readInt();
                case 5, 6 -> {
                    d.readLong();
                    i++;
                }
                case 9, 10, 11, 12, 17, 18 -> cp[i] = new int[] {d.readUnsignedShort(), d.readUnsignedShort()};
                case 15 -> cp[i] = new int[] {d.readUnsignedByte(), d.readUnsignedShort()};
                default -> throw new IOException("constant pool tag " + tag[i]);
            }
        }
        ClassInfo ci = new ClassInfo();
        d.readUnsignedShort();
        ci.name = className(cp, d.readUnsignedShort());
        int sup = d.readUnsignedShort();
        ci.superName = sup == 0 ? null : className(cp, sup);
        int ifs = d.readUnsignedShort();
        for (int i = 0; i < ifs; i++) {
            ci.interfaces.add(className(cp, d.readUnsignedShort()));
        }
        int fields = d.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            d.skipBytes(6);
            int attrs = d.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                d.readUnsignedShort();
                d.skipBytes(d.readInt());
            }
        }
        int methods = d.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            d.readUnsignedShort();
            Method m = new Method((String) cp[d.readUnsignedShort()], (String) cp[d.readUnsignedShort()]);
            descClasses(m.desc, m.classes);
            int attrs = d.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                String an = (String) cp[d.readUnsignedShort()];
                byte[] body = d.readNBytes(d.readInt());
                if (an.equals("Code")) {
                    code(body, cp, tag, m);
                }
            }
            ci.methods.put(m.name + m.desc, m);
        }
        return ci;
    }

    private static String className(Object[] cp, int idx) {
        return (String) cp[(Integer) cp[idx]];
    }

    private static void descClasses(String desc, Set<String> into) {
        Matcher m = DESC_CLASS.matcher(desc);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    private static Ref memberRef(Object[] cp, int idx) {
        int[] r = (int[]) cp[idx];
        int[] nt = (int[]) cp[r[1]];
        return new Ref(className(cp, r[0]), (String) cp[nt[0]], (String) cp[nt[1]]);
    }

    private static String classRef(Object[] cp, int idx) {
        String c = className(cp, idx);
        return c.startsWith("[") ? c.replaceAll("^\\[+L?|;$", "") : c;
    }

    /** The calls and class references of one method's bytecode. */
    static void code(byte[] body, Object[] cp, int[] tag, Method m) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(body));
        d.readUnsignedShort();
        d.readUnsignedShort();
        byte[] c = d.readNBytes(d.readInt());
        int pc = 0;
        while (pc < c.length) {
            int op = c[pc] & 0xFF;
            int u2 = pc + 2 < c.length ? ((c[pc + 1] & 0xFF) << 8) | (c[pc + 2] & 0xFF) : 0;
            switch (op) {
                case 0xb2, 0xb3, 0xb4, 0xb5 -> {   // get/put static/field
                    Ref f = memberRef(cp, u2);
                    m.classes.add(f.owner());
                    descClasses(f.desc(), m.classes);
                    pc += 3;
                }
                case 0xb6, 0xb7, 0xb8, 0xb9 -> {   // invokevirtual/special/static/interface
                    Ref r = memberRef(cp, u2);
                    m.calls.add(r);
                    m.classes.add(r.owner());
                    descClasses(r.desc(), m.classes);
                    pc += op == 0xb9 ? 5 : 3;
                }
                case 0xba -> {                      // invokedynamic (lambda bodies are followed by name below)
                    int[] indy = (int[]) cp[u2];
                    descClasses((String) cp[((int[]) cp[indy[1]])[1]], m.classes);
                    pc += 5;
                }
                case 0xbb, 0xbd, 0xc0, 0xc1 -> {    // new, anewarray, checkcast, instanceof
                    m.classes.add(classRef(cp, u2));
                    pc += 3;
                }
                case 0xc5 -> {                      // multianewarray
                    m.classes.add(classRef(cp, u2));
                    pc += 4;
                }
                case 0x12 -> {                      // ldc
                    int i = c[pc + 1] & 0xFF;
                    if (tag[i] == 7) {
                        m.classes.add(classRef(cp, i));
                    } else if (tag[i] == 8) {
                        m.strings.add((String) cp[(Integer) cp[i]]);
                    }
                    pc += 2;
                }
                case 0x13, 0x14 -> {                // ldc_w, ldc2_w
                    if (tag[u2] == 7) {
                        m.classes.add(classRef(cp, u2));
                    } else if (tag[u2] == 8) {
                        m.strings.add((String) cp[(Integer) cp[u2]]);
                    }
                    pc += 3;
                }
                case 0xaa -> {                      // tableswitch
                    int p = (pc + 4) & ~3;
                    pc = p + 12 + 4 * (readInt(c, p + 8) - readInt(c, p + 4) + 1);
                }
                case 0xab -> {                      // lookupswitch
                    int p = (pc + 4) & ~3;
                    pc = p + 8 + 8 * readInt(c, p + 4);
                }
                case 0xc4 -> pc += (c[pc + 1] & 0xFF) == 0x84 ? 6 : 4;   // wide
                default -> pc += LENGTH[op];
            }
        }
        int handlers = d.readUnsignedShort();
        for (int i = 0; i < handlers; i++) {
            d.skipBytes(6);
            int t = d.readUnsignedShort();
            if (t != 0) {
                m.classes.add(classRef(cp, t));
            }
        }
    }

    private static int readInt(byte[] c, int p) {
        return ((c[p] & 0xFF) << 24) | ((c[p + 1] & 0xFF) << 16) | ((c[p + 2] & 0xFF) << 8) | (c[p + 3] & 0xFF);
    }

    /** Instruction lengths for the opcodes not decoded above. */
    private static final int[] LENGTH = new int[256];

    static {
        java.util.Arrays.fill(LENGTH, 1);
        for (int op : new int[] {0x10, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc}) {
            LENGTH[op] = 2;
        }
        for (int op = 0x99; op <= 0xa8; op++) {
            LENGTH[op] = 3;
        }
        for (int op : new int[] {0x11, 0x84, 0xc6, 0xc7}) {
            LENGTH[op] = 3;
        }
        LENGTH[0xc8] = 5;
        LENGTH[0xc9] = 5;
    }

    // ---- the walk ---------------------------------------------------------------------------------

    private boolean isSubtype(ClassInfo ci, String type) throws IOException {
        if (ci == null) {
            return false;
        }
        if (ci.name.equals(type)) {
            return true;
        }
        if (ci.superName != null && ours(ci.superName) && isSubtype(load(ci.superName), type)) {
            return true;
        }
        for (String i : ci.interfaces) {
            if (ours(i) && isSubtype(load(i), type)) {
                return true;
            }
        }
        return false;
    }

    /** This project's main classes (target/classes), for implementations of an interface or overrides. */
    private List<String> universe() throws Exception {
        List<String> out = new ArrayList<>();
        java.nio.file.Path root = java.nio.file.Paths.get(
            com.pointblue.dirxml.dev.deploy.Vault.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var s = java.nio.file.Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/').replaceAll("\\.class$", "")));
        }
        return out;
    }

    @Test
    public void theConsolesCallsReachNoVendorClass() throws Exception {
        List<String> universe = universe();
        Map<String, List<String>> subtypes = new HashMap<>();
        Deque<String[]> todo = new ArrayDeque<>();   // {owner, "name" | "name+desc" | "*", caller}
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> parent = new HashMap<>();
        TreeMap<String, Set<String>> bad = new TreeMap<>();
        for (String[] r : ROOTS) {
            todo.add(new String[] {r[0], r[1], null});
        }
        while (!todo.isEmpty()) {
            String[] t = todo.poll();
            List<String> targets = new ArrayList<>(List.of(t[0]));
            if (!t[1].startsWith("<")) {
                // a virtual or interface call reaches every override or implementation here (CHA)
                List<String> subs = subtypes.get(t[0]);
                if (subs == null) {
                    subs = new ArrayList<>();
                    for (String u : universe) {
                        if (!u.equals(t[0]) && isSubtype(load(u), t[0])) {
                            subs.add(u);
                        }
                    }
                    subtypes.put(t[0], subs);
                }
                targets.addAll(subs);
            }
            for (String target : targets) {
                for (ClassInfo k = load(target); k != null; k = k.superName != null && ours(k.superName) ? load(k.superName) : null) {
                    boolean found = false;
                    for (Method m : k.methods.values()) {
                        if (!(t[1].equals("*") || t[1].equals(m.name) || t[1].equals(m.name + m.desc))) {
                            continue;
                        }
                        found = true;
                        String key = k.name + "." + m.name + m.desc;
                        if (!seen.add(key)) {
                            continue;
                        }
                        if (t[2] != null) {
                            parent.putIfAbsent(key, t[2]);
                        }
                        for (String cls : m.classes) {
                            if (vendor(cls)) {
                                bad.computeIfAbsent(key, x -> new LinkedHashSet<>()).add(cls);
                            } else if (ours(cls)) {
                                todo.add(new String[] {cls, "<clinit>", key});
                            }
                        }
                        for (String str : m.strings) {
                            if (reflective(str)) {
                                bad.computeIfAbsent(key, x -> new LinkedHashSet<>()).add("\"" + str + "\" (by name)");
                            }
                        }
                        for (Ref call : m.calls) {
                            if (ours(call.owner())) {
                                todo.add(new String[] {call.owner(), call.name() + call.desc(), key});
                            }
                        }
                        for (Method l : k.methods.values()) {   // lambda bodies: lambda$<method>$<n>
                            if (l.name.startsWith("lambda$" + m.name + "$")) {
                                todo.add(new String[] {k.name, l.name + l.desc, key});
                            }
                        }
                    }
                    if (found || t[1].equals("*") || t[1].startsWith("<")) {
                        break;
                    }
                }
            }
        }
        for (String[] r : ROOTS) {
            boolean exists = seen.stream().anyMatch(k -> k.startsWith(r[0] + ".") && (r[1].equals("*") || k.startsWith(r[0] + "." + r[1] + "(")));
            assertTrue("the console calls " + r[0] + "." + r[1] + ", which the walk did not find (renamed?)", exists);
        }
        StringBuilder why = new StringBuilder();
        for (Map.Entry<String, Set<String>> b : bad.entrySet()) {
            StringBuilder chain = new StringBuilder(b.getKey());
            int hops = 0;
            for (String p = parent.get(b.getKey()); p != null && hops++ < 40; p = parent.get(p)) {
                chain.insert(0, p + "\n      -> ");
            }
            why.append("\n  ").append(chain).append("\n      names ").append(b.getValue());
        }
        assertTrue("reached " + seen.size() + " methods; these name vendor classes:" + why, bad.isEmpty());
        assertTrue("the walk is the console's whole surface, not a corner of it (" + seen.size() + " methods)", seen.size() > 300);
        assertTrue("the walk reached the extended operations",
            seen.stream().anyMatch(s -> s.startsWith("com/pointblue/dirxml/dev/deploy/IdmOps.request")));
    }
}
