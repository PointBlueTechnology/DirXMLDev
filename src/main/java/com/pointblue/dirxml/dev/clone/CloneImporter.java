package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Ldif;
import com.pointblue.dirxml.dev.deploy.Vault;
import com.pointblue.dirxml.dev.deploy.VaultAccess;
import com.pointblue.dirxml.dev.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * {@code vault.import-clone}: a {@link CloneBundle} into a lab tree (docs/vault-clone.md
 * §4–§5, §9). Plan first — the schema delta against the target, which entries already
 * exist, the DN map, where each driver's server-specific values come from — then, with
 * {@code --yes}, write in order: schema, entries parents-first, the held-back references,
 * the ACLs; then read everything back and compare. A DN value naming an object neither the
 * bundle creates nor the target has is dropped with a note, never invented. Re-runnable:
 * existing entries are skipped unless {@code replace}.
 *
 * <p><b>Servers.</b> By default every source server is merged onto one lab server
 * ({@code --server}): a driver takes the server-specific values of the server it ran on at
 * the source (the bundle's placement; {@code --driver-server} overrides), the driver set and
 * everything else take the primary's. With {@code --map} naming a lab server for every
 * source server, the lab keeps the same shape: each lab server gets its source server's
 * values through its own connection ({@code targetConnector}).
 */
public final class CloneImporter {

    public static final class Options {
        public String serverDn;                           // merge mode: the lab server every source server maps to
        public final Map<String, String> dnMap = new LinkedHashMap<>();   // source DN (any case) -> target DN
        /** Driver DN -> source server DN whose server-specific values it takes (merge mode override). */
        public final Map<String, String> driverServer = new LinkedHashMap<>();
        /** One-to-one mode: lab server DN -> LDAP URL for every lab server but the primary connection's. */
        public final Map<String, String> targetServerUrls = new LinkedHashMap<>();
        public Function<String, VaultAccess> targetConnector;
        public boolean replace;                           // delete-and-recreate leaf entries that exist
        public boolean replaceDriverSet;                  // allow an existing driver set of the same DN to be replaced
        public boolean dryRun = true;
        public Path logDir;                               // where the before-LDIF and the import log go (optional)
        public String envName;
        /** One lab password for every cloned person (the clone carries none); null = no password set. */
        public char[] userPassword;
    }

    public static final class Result {
        public boolean ok = true;
        public boolean applied;
        public final List<String> refusals = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();
        public final List<String> verification = new ArrayList<>();
        public int schemaAttributes;
        public int schemaClasses;
        public int schemaConflicts;
        public int toAdd;
        public int existing;
        public int replaced;
        public int added;
        public int referenceValues;
        public int droppedReferenceValues;
        public int aclValues;
        public int droppedAclValues;
        public int serverValuesWritten;
        public int passwordsSet;
        public String passwordRefused;      // the server's first answer when the lab password broke a password policy
        public int passwordsRefused;
        public int verified;
        public int verifyMismatches;
        public String plan = "";

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (String r : refusals) {
                sb.append("REFUSED  ").append(r).append('\n');
            }
            sb.append(plan);
            for (String f : failures) {
                sb.append("  failed   ").append(f).append('\n');
            }
            for (String n : notes) {
                sb.append("  note     ").append(n).append('\n');
            }
            if (applied) {
                sb.append("verify: ").append(verified).append(" entries read back, ").append(verifyMismatches).append(" mismatch(es)\n");
                for (String v : verification) {
                    sb.append("  ").append(v).append('\n');
                }
            }
            sb.append(ok ? (applied ? "OK\n" : "(dry run — nothing written)\n") : "FAILED\n");
            return sb.toString();
        }

        public String json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("applied", applied);
            m.put("refusals", refusals);
            m.put("schema", Map.of("attributes", schemaAttributes, "classes", schemaClasses, "conflicts", schemaConflicts));
            m.put("entries", Map.of("toAdd", toAdd, "existing", existing, "replaced", replaced, "added", added));
            m.put("references", Map.of("values", referenceValues, "dropped", droppedReferenceValues));
            m.put("acls", Map.of("values", aclValues, "dropped", droppedAclValues));
            m.put("serverValuesWritten", serverValuesWritten);
            m.put("passwordsSet", passwordsSet);
            m.put("passwordsRefused", passwordsRefused);
            m.put("verify", Map.of("entries", verified, "mismatches", verifyMismatches, "lines", verification));
            m.put("failures", failures);
            m.put("notes", notes);
            return Json.compact(m);
        }
    }

    private final CloneBundle bundle;
    private final VaultAccess target;
    private final Options o;
    private final Map<String, Boolean> existsCache = new HashMap<>();
    private Set<String> bundleDns;
    /** entry DN -> source server whose server-specific values the entry gets on the primary lab connection. */
    private final Map<String, String> chosenServer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** The target's schema — attributes it marks operational / NO-USER-MODIFICATION cannot be written by a client. */
    private Schema targetSchema = Schema.of(List.of(), List.of());
    private final Set<String> unwritable = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    /**
     * The drivers' start option belongs to the engine: on eDirectory 9.3 the schema says so
     * ({@code NO-USER-MODIFICATION}), and wherever an engine is loaded it refuses the attribute on
     * an add with {@code -672 no access} whatever the schema says (edir-test3, 2026-09-23: the clone
     * brought a 9.2.8 definition without the flag onto a tree whose engine was already running).
     * So it is never written as an attribute when the engine answers, and set through the engine's
     * extended operation instead.
     */
    private boolean startOptionEngineOwned;
    private boolean engineAnswers;

    public CloneImporter(CloneBundle bundle, VaultAccess target, Options options) {
        this.bundle = bundle;
        this.target = target;
        this.o = options;
    }

    public Result run() throws IOException {
        Result r = new Result();
        bundleDns = CloneExporter.dns(bundle);
        Schema source = bundle.schema();
        Vault.Entry targetSchemaEntry = target.read("cn=schema", "attributeTypes", "objectClasses");
        targetSchema = targetSchemaEntry == null ? Schema.of(List.of(), List.of())
            : Schema.of(targetSchemaEntry.strings("attributeTypes"), targetSchemaEntry.strings("objectClasses"));
        engineAnswers = engineAnswers();
        startOptionEngineOwned = targetSchema.isOperational(ClonePolicy.START_OPTION_ATTR) || engineAnswers;
        Schema.Delta delta = Schema.delta(source, targetSchema);
        r.schemaAttributes = delta.attributes.size();
        r.schemaClasses = delta.classes.size();
        r.schemaConflicts = delta.conflicts.size();

        // ---- servers: merge onto one, or one to one ----
        List<String> sourceServers = new ArrayList<>();
        for (Object s : Json.asList(bundle.manifest.get("sourceServers"))) {
            sourceServers.add(String.valueOf(s));
        }
        String primarySource = bundle.primaryServerDn();
        Map<String, String> serverMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);   // source server -> lab server
        for (String s : sourceServers) {
            String mapped = o.dnMap.get(s);
            if (mapped == null) {
                for (Map.Entry<String, String> e : o.dnMap.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(s)) {
                        mapped = e.getValue();
                    }
                }
            }
            if (mapped != null) {
                serverMap.put(s, mapped);
            }
        }
        boolean oneToOne = !sourceServers.isEmpty() && serverMap.size() == sourceServers.size()
            && new LinkedHashSet<>(serverMap.values()).size() > 1;
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(o.dnMap);
        String labPrimary = null;
        if (oneToOne) {
            Vault.Entry root = target.read("", "dsaName");
            labPrimary = root == null ? null : root.string("dsaName");
            if (labPrimary == null) {
                r.refusals.add("one-to-one server mapping needs the lab's own server name (root DSE dsaName) and got none");
            } else if (!containsIgnoreCase(serverMap.values(), labPrimary)) {
                r.refusals.add("one-to-one server mapping: none of the lab servers in --map is the one this connection talks to (" + labPrimary + ")");
            }
            for (String lab : new LinkedHashSet<>(serverMap.values())) {
                if (!lab.equalsIgnoreCase(labPrimary) && o.targetServerUrls.get(lab) == null && (o.targetConnector == null || !o.targetServerUrls.containsKey(lab))) {
                    r.refusals.add("one-to-one server mapping: no LDAP URL for lab server " + lab + " (give <env>.servers=" + lab + "=ldaps://host:port)");
                }
            }
        } else {
            if (o.serverDn == null && !sourceServers.isEmpty()) {
                r.refusals.add("the bundle names a source server (" + primarySource + ") and no --server was given: "
                    + "the driver set and every job must be associated with the lab server");
            }
            for (String s : sourceServers) {
                if (o.serverDn != null) {
                    map.put(s, o.serverDn);
                }
            }
        }
        // which source server's values each entry gets on the primary lab connection
        String primaryLabSource = null;    // one-to-one: the source server mapped to the lab primary
        if (oneToOne) {
            for (Map.Entry<String, String> e : serverMap.entrySet()) {
                if (e.getValue().equalsIgnoreCase(labPrimary)) {
                    primaryLabSource = e.getKey();
                }
            }
        }
        for (Map<String, Map<String, List<byte[]>>> perEntry : bundle.serverValues.values()) {
            for (String dn : perEntry.keySet()) {
                chosenServer.putIfAbsent(dn, chooseServer(dn, primarySource, oneToOne ? primaryLabSource : null, r));
            }
        }

        // ---- which entries exist already ----
        List<Vault.Entry> all = new ArrayList<>(bundle.containers);
        all.addAll(bundle.objects);
        List<Vault.Entry> toAdd = new ArrayList<>();
        List<Vault.Entry> existing = new ArrayList<>();
        for (Vault.Entry e : all) {
            if (exists(e.dn)) {
                existing.add(e);
            } else {
                toAdd.add(e);
            }
        }
        for (Vault.Entry e : existing) {
            if (e.hasClass("DirXML-DriverSet") && !o.replaceDriverSet) {
                // the lab's own driver set, or this clone's from an earlier run? Foreign drivers decide.
                List<String> foreign = new ArrayList<>();
                for (Vault.Entry child : target.search(e.dn, "(objectClass=DirXML-Driver)", javax.naming.directory.SearchControls.ONELEVEL_SCOPE)) {
                    if (!bundleDns.contains(child.dn)) {
                        foreign.add(child.dn);
                    }
                }
                if (!foreign.isEmpty()) {
                    r.refusals.add("driver set " + e.dn + " already exists on the target with drivers the clone does not carry ("
                        + String.join(", ", foreign) + "); --replace-driverset to replace it (snapshot first)");
                } else {
                    r.notes.add("driver set " + e.dn + " already exists on the target and holds only drivers the clone carries — continuing (a re-run converges)");
                }
            }
        }
        r.toAdd = toAdd.size();
        r.existing = existing.size();

        StringBuilder plan = new StringBuilder();
        plan.append("import-clone ").append(bundle.manifest.get("sourceTree")).append(" -> ").append(o.envName).append('\n');
        plan.append("  schema: +").append(delta.attributes.size()).append(" attributes, +").append(delta.classes.size())
            .append(" classes; ").append(delta.conflicts.size()).append(" defined differently on the target (left alone)\n");
        int shown = 0;
        for (String c : delta.conflicts.keySet()) {
            if (shown++ < 8) {
                plan.append("    differs: ").append(c).append('\n');
            }
        }
        plan.append("  entries: ").append(toAdd.size()).append(" to add, ").append(existing.size()).append(" already there (")
            .append(o.replace ? "replaced" : "skipped").append(")\n");
        int refValues = 0;
        for (Map<String, List<byte[]>> m : bundle.references.values()) {
            for (List<byte[]> v : m.values()) {
                refValues += v.size();
            }
        }
        int aclValues = 0;
        for (Map<String, List<byte[]>> m : bundle.acls.values()) {
            for (List<byte[]> v : m.values()) {
                aclValues += v.size();
            }
        }
        plan.append("  references: ").append(refValues).append(" values on ").append(bundle.references.size()).append(" entries; ACLs: ")
            .append(aclValues).append(" values on ").append(bundle.acls.size()).append(" entries\n");
        plan.append("  servers: ").append(oneToOne ? "one to one" : "merged onto " + o.serverDn).append('\n');
        for (Map<String, Object> s : bundle.servers()) {
            String dn = Json.asString(s.get("dn"));
            plan.append("    ").append(dn).append(Boolean.TRUE.equals(s.get("primary")) ? " (primary)" : "")
                .append(Boolean.FALSE.equals(s.get("reachable")) ? " — not read at export" : "")
                .append(" -> ").append(oneToOne ? serverMap.get(dn) : o.serverDn).append('\n');
        }
        Map<String, Integer> perServerDrivers = new TreeMap<>();
        for (Map.Entry<String, String> c : chosenServer.entrySet()) {
            Map<String, Object> pl = bundle.placement(c.getKey());
            if (pl != null) {
                perServerDrivers.merge(c.getValue(), 1, Integer::sum);
            }
        }
        if (!perServerDrivers.isEmpty()) {
            plan.append("  driver settings taken from: ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : perServerDrivers.entrySet()) {
                parts.add(e.getKey() + " (" + e.getValue() + " driver" + (e.getValue() == 1 ? "" : "s") + ")");
            }
            plan.append(String.join(", ", parts)).append('\n');
        }
        plan.append("  DN map: ");
        if (map.isEmpty()) {
            plan.append("(none)\n");
        } else {
            plan.append('\n');
            for (Map.Entry<String, String> e : map.entrySet()) {
                plan.append("    ").append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
            }
        }
        Object dataEntries = bundle.manifest.get("dataEntries");
        if (dataEntries != null && Json.asInt(dataEntries, 0) > 0) {
            plan.append("  identity data: ").append(dataEntries).append(" entries")
                .append(Boolean.TRUE.equals(bundle.manifest.get("pseudonymised")) ? ", pseudonymised" : ", real names")
                .append(o.userPassword != null ? ", one lab password for every person" : ", no passwords").append('\n');
        }
        if (!bundle.secretsNeeded.isEmpty()) {
            plan.append("  secrets to set afterwards: ").append(bundle.secretsNeeded.size()).append(" (see secrets-needed.txt)\n");
        }
        r.plan = plan.toString();
        if (!r.refusals.isEmpty()) {
            r.ok = false;
            return r;
        }
        if (o.dryRun) {
            return r;
        }

        // ---- apply ----
        r.applied = true;
        Path log = null;
        if (o.logDir != null) {
            Files.createDirectories(o.logDir);
            String ts = Instant.now().toString().replace(':', '-');
            if (!existing.isEmpty()) {
                StringBuilder before = new StringBuilder();
                for (Vault.Entry e : existing) {
                    Vault.Entry cur = target.read(e.dn);
                    if (cur != null) {
                        before.append(Ldif.entry(cur)).append("\n\n");
                    }
                }
                Files.writeString(o.logDir.resolve("import-" + o.envName + "-" + ts + "-before.ldif"), before.toString(), StandardCharsets.UTF_8);
            }
            log = o.logDir.resolve("import-" + o.envName + "-" + ts + ".log");
        }

        // 1. schema: attributes, then classes in rounds (a class needs its superclass and its
        //    containment classes first; one the target cannot complete gets its missing optional attributes stripped)
        Set<String> targetAttrs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Schema.Def t : targetSchema.attributes()) {
            targetAttrs.addAll(t.names);
        }
        List<String> substituted = new ArrayList<>();
        List<String> refusedSyntax = new ArrayList<>();
        int aliasesSkipped = 0;
        for (Schema.Def d : delta.attributes) {
            if (d.name().contains(";")) {
                aliasesSkipped++;          // userCertificate;binary and kin: transfer aliases of attributes the target has
                continue;
            }
            String raw = d.raw;
            if (Schema.SYNTAX_NDS_UNKNOWN.equals(d.syntax)) {
                raw = raw.replace("SYNTAX " + Schema.SYNTAX_NDS_UNKNOWN, "SYNTAX " + Schema.SYNTAX_OCTET_STRING);
                substituted.add(d.name());
            }
            try {
                target.addValues("cn=schema", "attributeTypes", List.of(raw.getBytes(StandardCharsets.UTF_8)));
                targetAttrs.addAll(d.names);
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("-641") && "2.16.840.1.113719.1.1.5.1.13".equals(d.syntax)) {
                    refusedSyntax.add(d.name());   // NetIQ tagged data: the LDAP server refuses to define it; the clone never carries values of it
                } else {
                    r.failures.add("schema attribute " + d.name() + ": " + msg);
                }
            }
        }
        if (!refusedSyntax.isEmpty()) {
            r.notes.add(refusedSyntax.size() + " attribute(s) of a NetIQ syntax the LDAP server refuses to define (tagged data; the clone carries no values of them) not created: "
                + String.join(", ", refusedSyntax));
        }
        if (aliasesSkipped > 0) {
            r.notes.add(aliasesSkipped + " schema attribute alias(es) with ';' (transfer options such as userCertificate;binary) not created — the base attributes exist");
        }
        if (!substituted.isEmpty()) {
            r.notes.add(substituted.size() + " attribute(s) of NetIQ 'Unknown' syntax (IDM rights pseudo-attributes, never valued, named by ACLs) created as Octet String because eDirectory refuses that syntax over LDAP: "
                + String.join(", ", substituted));
        }
        List<Schema.Def> pendingClasses = new ArrayList<>(delta.classes);
        Set<String> stripped = new TreeSet<>();
        for (int round = 0; round < 12 && !pendingClasses.isEmpty(); round++) {
            List<Schema.Def> next = new ArrayList<>();
            for (Schema.Def d : pendingClasses) {
                Set<String> missing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (String a : d.must) {
                    if (!targetAttrs.contains(a)) {
                        missing.add(a);
                    }
                }
                for (String a : d.may) {
                    if (!targetAttrs.contains(a)) {
                        missing.add(a);
                    }
                }
                String raw = missing.isEmpty() ? d.raw : Schema.withoutAttributes(d.raw, missing);
                try {
                    target.addValues("cn=schema", "objectClasses", List.of(raw.getBytes(StandardCharsets.UTF_8)));
                    if (!missing.isEmpty()) {
                        stripped.add(d.name() + " (" + String.join(", ", missing) + ")");
                    }
                } catch (RuntimeException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("-604") || msg.contains("no such class")) {
                        next.add(d);               // its superclass or a containment class is not there yet
                    } else {
                        r.failures.add("schema class " + d.name() + ": " + msg);
                    }
                }
            }
            if (next.size() == pendingClasses.size()) {
                for (Schema.Def d : next) {
                    r.failures.add("schema class " + d.name() + ": a superclass or containment class never became available");
                }
                break;
            }
            pendingClasses = next;
        }
        if (!stripped.isEmpty()) {
            r.notes.add(stripped.size() + " class(es) created without optional attributes the target could not define: " + String.join("; ", stripped));
        }
        Set<String> failedDns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        // 2. entries, parents first (the bundle is already ordered), with the chosen server's values merged in
        Set<String> written = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Vault.Entry e : all) {
            boolean there = exists(e.dn);
            if (there && !o.replace) {
                continue;
            }
            if (underFailed(e.dn, failedDns)) {
                failedDns.add(e.dn);
                continue;
            }
            try {
                if (there) {
                    target.delete(e.dn);
                    r.replaced++;
                }
                Map<String, List<byte[]>> attrs = entryAttributes(e, source, map, r);
                boolean withPassword = o.userPassword != null && isPerson(e) && (r.passwordsSet > 0 || r.passwordsRefused < 3);
                if (withPassword) {
                    attrs.put("userPassword", List.of(new String(o.userPassword).getBytes(StandardCharsets.UTF_8)));
                }
                try {
                    target.add(e.dn, e.objectClasses(), attrs);
                } catch (RuntimeException ex) {
                    if (attrs.containsKey(ClonePolicy.START_OPTION_ATTR) && isNoAccess(ex)) {
                        // an engine that did not answer GetVersion still owns the start option: drop it and set it through the engine below
                        startOptionEngineOwned = true;
                        attrs.remove(ClonePolicy.START_OPTION_ATTR);
                        if (!withPassword) {
                            target.add(e.dn, e.objectClasses(), attrs);
                        } else {
                            try {
                                target.add(e.dn, e.objectClasses(), attrs);
                            } catch (RuntimeException ex2) {
                                if (!isPasswordRefusal(ex2)) {
                                    throw ex2;
                                }
                                if (r.passwordRefused == null) {
                                    r.passwordRefused = ex2.getMessage();
                                }
                                r.passwordsRefused++;
                                withPassword = false;
                                attrs.remove("userPassword");
                                target.add(e.dn, e.objectClasses(), attrs);
                            }
                        }
                        existsCache.put(e.dn.toLowerCase(Locale.ROOT), Boolean.TRUE);
                        written.add(e.dn);
                        r.added++;
                        if (withPassword) {
                            r.passwordsSet++;
                        }
                        continue;
                    }
                    if (!withPassword || !isPasswordRefusal(ex)) {
                        throw ex;
                    }
                    // a password policy (cloned with the tree) refuses the lab password: create the person
                    // without one and go on — policies differ by container, so others may accept it; after
                    // three refusals and no success, stop trying. A re-run with a compliant password sets them.
                    if (r.passwordRefused == null) {
                        r.passwordRefused = ex.getMessage();
                    }
                    r.passwordsRefused++;
                    withPassword = false;
                    attrs.remove("userPassword");
                    target.add(e.dn, e.objectClasses(), attrs);
                }
                if (withPassword) {
                    r.passwordsSet++;
                }
                existsCache.put(e.dn.toLowerCase(Locale.ROOT), Boolean.TRUE);
                written.add(e.dn);
                r.added++;
            } catch (RuntimeException ex) {
                failedDns.add(e.dn);
                r.failures.add("add " + e.dn + ": " + ex.getMessage());
            }
        }

        // 2a. the lab password on people that already existed (a re-run after a refused password, or a
        //     clone imported before a password was chosen): set, not skipped — passwords never verify
        if (o.userPassword != null) {
            for (Vault.Entry e : all) {
                if (!isPerson(e) || written.contains(e.dn) || failedDns.contains(e.dn) || !exists(e.dn)) {
                    continue;
                }
                if (r.passwordsSet == 0 && r.passwordsRefused >= 3) {
                    break;                          // nobody takes it: the policy, not the person
                }
                try {
                    target.replace(e.dn, "userPassword", List.of(new String(o.userPassword).getBytes(StandardCharsets.UTF_8)));
                    r.passwordsSet++;
                } catch (RuntimeException ex) {
                    if (isPasswordRefusal(ex)) {
                        if (r.passwordRefused == null) {
                            r.passwordRefused = ex.getMessage();
                        }
                        r.passwordsRefused++;
                        continue;
                    }
                    r.failures.add("password " + e.dn + ": " + ex.getMessage());
                }
            }
        }

        // 2b. the drivers' start option: where the target's schema lets only the server write the
        //     attribute (eDirectory 9.3), it is set the way deploy sets it — through the engine's
        //     DirXML extended operation. That answers only once the engine on the lab server has
        //     loaded the driver set, which needs the association written above and then a restart
        //     or module load — so this runs on every cloned driver on every run, and a re-run
        //     after the restart finishes what the first run could not.
        boolean startOptionServerOwned = targetSchema.isOperational(ClonePolicy.START_OPTION_ATTR);
        String why = startOptionServerOwned ? "the attribute is server-owned on the target" : "an engine answers on the target and owns the attribute";
        if (startOptionEngineOwned) {
            unwritable.add(ClonePolicy.START_OPTION_ATTR);
            int set = 0;
            String engineError = null;
            for (Vault.Entry e : all) {
                if (!e.hasClass("DirXML-Driver") || !exists(e.dn)) {
                    continue;
                }
                try {
                    target.setDriverStartOption(e.dn, Vault.START_MANUAL);
                    set++;
                } catch (RuntimeException ex) {
                    engineError = ex.getMessage();
                    break;
                }
            }
            if (engineError != null) {
                r.notes.add("start option: " + why + ", and the engine's DirXML extended operation did not answer ("
                    + engineError + "). The driver set is associated with the lab server now; restart the engine there (or load the IDM module), "
                    + "then run this import again — it sets every cloned driver to manual start through the engine. Until then the drivers keep the engine's default.");
            } else if (set > 0) {
                r.notes.add("start option set to manual on " + set + " driver(s) through the engine (" + why + ")");
                unwritable.remove(ClonePolicy.START_OPTION_ATTR);
            }
        }

        // 3. references — on every entry of the clone that exists now, adding only what is missing
        //    (a re-run converges; an entry the lab already had keeps what it has and gains the rest;
        //    and the server itself may have added values on creation, so a new entry is read too)
        for (Map.Entry<String, Map<String, List<byte[]>>> e : bundle.references.entrySet()) {
            String dn = e.getKey();
            if (failedDns.contains(dn) || !exists(dn)) {
                continue;
            }
            Vault.Entry current = target.read(dn);
            for (Map.Entry<String, List<byte[]>> a : e.getValue().entrySet()) {
                if (targetSchema.isOperational(a.getKey())) {
                    unwritable.add(a.getKey());
                    continue;
                }
                List<byte[]> values = mapDnValues(source, a.getKey(), a.getValue(), map, dn, r, false);
                values = missingValues(values, current == null ? null : current.attrs.get(a.getKey()), true);
                r.referenceValues += values.size();
                if (values.isEmpty()) {
                    continue;
                }
                try {
                    target.addValues(dn, a.getKey(), values);
                } catch (RuntimeException ex) {
                    r.failures.add("reference " + dn + " " + a.getKey() + ": " + ex.getMessage());
                }
            }
        }

        // 4. ACLs, the same way — eDirectory grants every new user its default ACLs on creation, and
        //    the clone carries those same values from the source, so only what is missing is added
        for (Map.Entry<String, Map<String, List<byte[]>>> e : bundle.acls.entrySet()) {
            String dn = e.getKey();
            if (failedDns.contains(dn) || !exists(dn)) {
                continue;
            }
            Vault.Entry current = target.read(dn, "ACL");
            for (Map.Entry<String, List<byte[]>> a : e.getValue().entrySet()) {
                List<byte[]> values = new ArrayList<>();
                for (byte[] v : a.getValue()) {
                    String s = new String(v, StandardCharsets.UTF_8);
                    String[] f = s.split("#", -1);        // privileges#scope#trustee#protected name
                    if (f.length >= 3) {
                        String trustee = f[2];
                        if (!trustee.startsWith("[")) {
                            String mapped = mapDn(trustee, map);
                            if (!willExist(mapped)) {
                                r.droppedAclValues++;
                                r.notes.add(dn + ": ACL trustee " + trustee + " is not in the clone and not on the target — value dropped");
                                continue;
                            }
                            f[2] = mapped;
                            s = String.join("#", f);
                        }
                    }
                    values.add(s.getBytes(StandardCharsets.UTF_8));
                }
                values = missingValues(values, current == null ? null : current.attrs.get(a.getKey()), true);
                r.aclValues += values.size();
                if (values.isEmpty()) {
                    continue;
                }
                try {
                    target.addValues(dn, a.getKey(), values);
                } catch (RuntimeException ex) {
                    r.failures.add("ACL " + dn + ": " + ex.getMessage());
                }
            }
        }

        // 5. one to one: every other lab server gets its source server's values through its own connection
        if (oneToOne) {
            for (Map.Entry<String, String> sm : serverMap.entrySet()) {
                String src = sm.getKey();
                String lab = sm.getValue();
                if (lab.equalsIgnoreCase(labPrimary)) {
                    continue;
                }
                Map<String, Map<String, List<byte[]>>> values = bundle.serverValues.get(src);
                if (values == null || values.isEmpty()) {
                    r.notes.add("lab server " + lab + ": no server-specific values for " + src + " in the bundle (not read at export) — nothing written there");
                    continue;
                }
                String url = o.targetServerUrls.get(lab);
                try (VaultAccess other = o.targetConnector.apply(url)) {
                    for (Map.Entry<String, Map<String, List<byte[]>>> e : values.entrySet()) {
                        if (failedDns.contains(e.getKey()) || (!written.contains(e.getKey()) && !exists(e.getKey()))) {
                            continue;
                        }
                        for (Map.Entry<String, List<byte[]>> a : e.getValue().entrySet()) {
                            if (targetSchema.isOperational(a.getKey())) {
                                unwritable.add(a.getKey());
                                continue;
                            }
                            List<byte[]> v = source.carriesDn(a.getKey()) ? mapDnValues(source, a.getKey(), a.getValue(), map, e.getKey(), r, true) : a.getValue();
                            try {
                                other.replace(e.getKey(), a.getKey(), v);
                                r.serverValuesWritten += v.size();
                            } catch (RuntimeException ex) {
                                r.failures.add("server " + lab + " " + e.getKey() + " " + a.getKey() + ": " + ex.getMessage());
                            }
                        }
                    }
                } catch (Exception ex) {
                    r.failures.add("lab server " + lab + " at " + url + ": " + ex.getMessage());
                }
            }
        }

        // 6. verify: everything written on the primary connection, read back and compared
        for (Vault.Entry e : all) {
            if (!written.contains(e.dn)) {
                continue;
            }
            Vault.Entry back = target.read(e.dn);
            r.verified++;
            if (back == null) {
                r.verifyMismatches++;
                r.verification.add(e.dn + ": not found after import");
                continue;
            }
            Map<String, List<byte[]>> expected = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            expected.putAll(entryAttributes(e, source, map, null));
            Map<String, List<byte[]>> refs = bundle.references.get(e.dn);
            if (refs != null) {
                for (Map.Entry<String, List<byte[]>> a : refs.entrySet()) {
                    expected.put(a.getKey(), mapDnValues(source, a.getKey(), a.getValue(), map, e.dn, null, false));
                }
            }
            List<String> diffs = compare(expected, back, source);
            if (!diffs.isEmpty()) {
                r.verifyMismatches++;
                r.verification.add(e.dn + ": " + String.join("; ", diffs));
            }
        }
        if (r.passwordsSet > 0) {
            r.notes.add("the lab password was set on " + r.passwordsSet + " cloned person(s)");
        }
        if (r.passwordRefused != null) {
            r.notes.add("the lab password was refused for " + r.passwordsRefused + " person(s) by a password policy — the clone carries the source's policies "
                + "and their assignments (check nspmMaximumLength and the character rules on the cloned policy) — first answer: " + r.passwordRefused
                + ". Those people have no password. Choose one the cloned policy accepts and run the import again: it sets it on every cloned person");
        }
        if (!unwritable.isEmpty()) {
            r.notes.add("not written — the target's schema lets only the server set these: " + String.join(", ", unwritable)
                + (unwritable.contains(ClonePolicy.START_OPTION_ATTR) ? " (so the drivers' start option is the engine's default there; check it before an engine runs this driver set)" : ""));
        }
        if (!r.failures.isEmpty()) {
            r.ok = false;
        }
        if (oneToOne) {
            r.notes.add("the driver set is associated with " + String.join(", ", new LinkedHashSet<>(serverMap.values())) + " (DirXML-ServerList); each engine picks its drivers up on restart");
        } else if (o.serverDn != null) {
            r.notes.add("the driver set is associated with " + o.serverDn + " (DirXML-ServerList); the engine on that server picks it up on restart");
        }
        if (log != null) {
            Files.writeString(log, r.text(), StandardCharsets.UTF_8);
        }
        return r;
    }

    // ---- helpers ----

    /** The source server whose never-sync values this entry gets on the primary lab connection. */
    private String chooseServer(String dn, String primarySource, String primaryLabSource, Result r) {
        if (primaryLabSource != null) {
            return primaryLabSource;              // one to one: the lab primary mirrors its mapped source server
        }
        for (Map.Entry<String, String> ov : o.driverServer.entrySet()) {
            if (ov.getKey().equalsIgnoreCase(dn) || dn.toLowerCase(Locale.ROOT).startsWith("cn=" + ov.getKey().toLowerCase(Locale.ROOT) + ",")) {
                return ov.getValue();
            }
        }
        Map<String, Object> pl = bundle.placement(dn);
        String runsOn = pl == null ? null : Json.asString(pl.get("runsOn"));
        if (runsOn != null && bundle.serverValues.containsKey(runsOn) && bundle.serverValues.get(runsOn).containsKey(dn)) {
            return runsOn;
        }
        if (runsOn != null && !runsOn.equalsIgnoreCase(primarySource) && r != null) {
            r.notes.add(dn + ": ran on " + runsOn + ", whose values the export could not read — the primary's are used");
        }
        return primarySource;
    }

    /** The entry's attributes as written: its own, plus the chosen server's never-sync values, DN values mapped. */
    private Map<String, List<byte[]>> entryAttributes(Vault.Entry e, Schema source, Map<String, String> map, Result r) {
        Map<String, List<byte[]>> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, List<byte[]>> a : e.attrs.entrySet()) {
            if (a.getKey().equalsIgnoreCase("objectClass")) {
                continue;
            }
            attrs.put(a.getKey(), a.getValue());
        }
        String server = chosenServer.get(e.dn);
        if (server != null) {
            Map<String, Map<String, List<byte[]>>> perEntry = bundle.serverValues.get(server);
            Map<String, List<byte[]>> mine = perEntry == null ? null : perEntry.get(e.dn);
            if (mine != null) {
                attrs.putAll(mine);
            }
        }
        Set<String> allowed = source.allowedAttributes(e.objectClasses());
        Map<String, List<byte[]>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<byte[]>> a : attrs.entrySet()) {
            if (targetSchema.isOperational(a.getKey())
                || (startOptionEngineOwned && a.getKey().equalsIgnoreCase(ClonePolicy.START_OPTION_ATTR))) {
                unwritable.add(a.getKey());        // the target's server owns it (e.g. DirXML-DriverStartOption on eDirectory 9.3, or wherever an engine runs)
                continue;
            }
            if (!allowed.isEmpty() && !allowed.contains(a.getKey())) {
                if (r != null) {
                    r.notes.add(e.dn + ": " + a.getKey() + " belongs to none of the entry's remaining classes (a dropped class such as Partition) — not written");
                }
                continue;
            }
            List<byte[]> values = source.carriesDn(a.getKey()) ? mapDnValues(source, a.getKey(), a.getValue(), map, e.dn, r, true) : a.getValue();
            if (!values.isEmpty()) {
                out.put(a.getKey(), values);
            }
        }
        return out;
    }

    /** {@code wanted} minus what {@code current} already holds (case-insensitive for DN-bearing values). */
    private static List<byte[]> missingValues(List<byte[]> wanted, List<byte[]> current, boolean caseInsensitive) {
        if (current == null || current.isEmpty()) {
            return wanted;
        }
        Set<String> have = new LinkedHashSet<>();
        for (byte[] v : current) {
            have.add(key(v, caseInsensitive));
        }
        List<byte[]> out = new ArrayList<>();
        for (byte[] v : wanted) {
            if (!have.contains(key(v, caseInsensitive))) {
                out.add(v);
            }
        }
        return out;
    }

    /** NMAS refusing a password (−16000 and neighbours) rather than the object being wrong. */
    /** The engine's refusal of a write it owns: NDS -672 "no access". */
    private static boolean isNoAccess(RuntimeException ex) {
        String m = ex.getMessage();
        return m != null && (m.contains("-672") || m.toLowerCase(Locale.ROOT).contains("no access"));
    }

    private boolean engineAnswers() {
        try {
            target.engineVersion();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isPasswordRefusal(RuntimeException ex) {
        String m = ex.getMessage() == null ? "" : ex.getMessage();
        return m.contains("-16000") || m.contains("-1696") || m.contains("-1697") || m.contains("password");
    }

    private static boolean isPerson(Vault.Entry e) {
        for (String oc : e.objectClasses()) {
            if (oc.equalsIgnoreCase("Person")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(java.util.Collection<String> values, String x) {
        for (String v : values) {
            if (v.equalsIgnoreCase(x)) {
                return true;
            }
        }
        return false;
    }

    private boolean exists(String dn) {
        return existsCache.computeIfAbsent(dn.toLowerCase(Locale.ROOT), k -> target.exists(dn));
    }

    /** True when the DN is created by this import (any phase) or already on the target. */
    private boolean willExist(String dn) {
        return bundleDns.contains(dn) || exists(dn);
    }

    private static boolean underFailed(String dn, Set<String> failed) {
        String l = dn.toLowerCase(Locale.ROOT);
        for (String f : failed) {
            if (l.endsWith("," + f.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String mapDn(String dn, Map<String, String> map) {
        String m = map.get(dn.trim());
        if (m != null) {
            return m;
        }
        String l = dn.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : map.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (l.endsWith("," + k)) {
                return dn.substring(0, dn.length() - k.length()) + e.getValue();
            }
        }
        return dn;
    }

    /**
     * DN-bearing values with the map applied to the DN part (the whole value for DN syntax;
     * up to the first {@code #} for Path and Typed Name), distinct (several source servers may
     * map to one lab server), dropping — with a note — a value whose object will not exist.
     */
    private List<byte[]> mapDnValues(Schema schema, String attr, List<byte[]> values, Map<String, String> map,
                                     String owner, Result r, boolean onEntry) {
        String syntax = schema.syntaxOf(attr);
        boolean plainDn = Schema.SYNTAX_DN.equals(syntax);
        Set<String> seen = new LinkedHashSet<>();
        List<byte[]> out = new ArrayList<>();
        for (byte[] v : values) {
            String s = new String(v, StandardCharsets.UTF_8);
            String dnPart = plainDn ? s : s.contains("#") ? s.substring(0, s.indexOf('#')) : s;
            String mapped = mapDn(dnPart, map);
            if (!willExist(mapped)) {
                if (r != null) {
                    if (onEntry) {
                        r.notes.add(owner + ": " + attr + " names " + dnPart + ", which will not exist — value dropped");
                    } else {
                        r.droppedReferenceValues++;
                        if (r.notes.size() < 200) {
                            r.notes.add(owner + ": " + attr + " names " + dnPart + ", which is not in the clone and not on the target — value dropped");
                        }
                    }
                }
                continue;
            }
            String rebuilt = plainDn ? mapped : s.contains("#") ? mapped + s.substring(s.indexOf('#')) : mapped;
            if (seen.add(rebuilt.toLowerCase(Locale.ROOT))) {
                out.add(rebuilt.getBytes(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** Differences between what was written and what is there: missing attributes, values not read back. */
    private static List<String> compare(Map<String, List<byte[]>> expected, Vault.Entry back, Schema schema) {
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, List<byte[]>> a : expected.entrySet()) {
            if (a.getValue().isEmpty()) {
                continue;
            }
            List<byte[]> have = back.attrs.get(a.getKey());
            if (have == null) {
                diffs.add(a.getKey() + " missing");
                continue;
            }
            boolean ci = schema.carriesDn(a.getKey()) || schema.isAcl(a.getKey());
            Set<String> want = new LinkedHashSet<>();
            for (byte[] v : a.getValue()) {
                want.add(key(v, ci));
            }
            Set<String> got = new LinkedHashSet<>();
            for (byte[] v : have) {
                got.add(key(v, ci));
            }
            if (!got.containsAll(want)) {
                Set<String> missing = new LinkedHashSet<>(want);
                missing.removeAll(got);
                diffs.add(a.getKey() + ": " + missing.size() + " of " + want.size() + " value(s) not read back");
            }
        }
        return diffs;
    }

    private static String key(byte[] v, boolean caseInsensitive) {
        if (caseInsensitive) {
            return new String(v, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).replaceAll("\\s*,\\s*", ",");
        }
        return Arrays.equals(v, new byte[0]) ? "" : Base64.getEncoder().encodeToString(v);
    }
}
