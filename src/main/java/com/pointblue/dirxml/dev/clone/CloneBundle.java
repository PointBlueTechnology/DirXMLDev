package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Ldif;
import com.pointblue.dirxml.dev.deploy.Vault;
import com.pointblue.dirxml.dev.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A clone on disk (docs/vault-clone.md §3): the handoff between {@code vault.export-clone},
 * run against the source, and {@code vault.import-clone}, run against the lab — possibly
 * from another workstation, days later. Self-contained: the whole source schema travels
 * with it so the import computes the delta against any target without the source.
 *
 * <pre>
 *   manifest.json         source tree, server DN, counts, policy, date
 *   10-schema.ldif        cn=schema: every attributeTypes / objectClasses of the source
 *   20-containers.ldif    containers, parents first, without DN-bearing attributes
 *   30-objects.ldif       every other entry, parents first, likewise
 *   40-references.ldif    changetype: modify — the held-back DN-bearing attributes
 *   50-acls.ldif          changetype: modify — ACL values, last
 *   secrets-needed.txt    what the target must be given by hand (names only, never values)
 * </pre>
 */
public final class CloneBundle {

    public static final String MANIFEST = "manifest.json";
    public static final String SCHEMA = "10-schema.ldif";
    public static final String CONTAINERS = "20-containers.ldif";
    public static final String OBJECTS = "30-objects.ldif";
    public static final String REFERENCES = "40-references.ldif";
    public static final String ACLS = "50-acls.ldif";
    public static final String SECRETS = "secrets-needed.txt";
    public static final String SERVER_VALUES_DIR = "35-server-values";

    public final Map<String, Object> manifest = new LinkedHashMap<>();
    public final List<String> attributeTypes = new ArrayList<>();
    public final List<String> objectClasses = new ArrayList<>();
    public final List<Vault.Entry> containers = new ArrayList<>();
    public final List<Vault.Entry> objects = new ArrayList<>();
    /** dn -> attribute -> values, in write order. */
    public final Map<String, Map<String, List<byte[]>>> references = new LinkedHashMap<>();
    public final Map<String, Map<String, List<byte[]>>> acls = new LinkedHashMap<>();
    public final List<String> secretsNeeded = new ArrayList<>();
    /**
     * Server-specific ({@code X-NDS_NEVER_SYNC}) values of the driver-set subtree, per source
     * server: server DN -> entry DN -> attribute -> values. One file per server under
     * {@code 35-server-values/}, {@code changetype: modify} / {@code replace} records.
     */
    public final Map<String, Map<String, Map<String, List<byte[]>>>> serverValues = new LinkedHashMap<>();

    public Schema schema() {
        return Schema.of(attributeTypes, objectClasses);
    }

    public String sourceServerDn() {
        return Json.asString(manifest.get("sourceServerDn"));
    }

    /** The source servers as the manifest lists them: {@code dn, url, primary, reachable, error}. */
    public List<Map<String, Object>> servers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.asList(manifest.get("servers"))) {
            out.add(Json.asMap(o));
        }
        return out;
    }

    /** The server the export read everything from. */
    public String primaryServerDn() {
        for (Map<String, Object> s : servers()) {
            if (Boolean.TRUE.equals(s.get("primary"))) {
                return Json.asString(s.get("dn"));
            }
        }
        return sourceServerDn();
    }

    public boolean serverReachable(String dn) {
        for (Map<String, Object> s : servers()) {
            if (dn.equalsIgnoreCase(Json.asString(s.get("dn")))) {
                return !Boolean.FALSE.equals(s.get("reachable"));
            }
        }
        return false;
    }

    /** Where a driver ran at the source: {@code runsOn} plus per-server {@code state} / {@code startOption}. */
    public Map<String, Object> placement(String driverDn) {
        Map<String, Object> all = Json.asMap(manifest.get("driverPlacement"));
        if (all == null) {
            return null;
        }
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (e.getKey().equalsIgnoreCase(driverDn)) {
                return Json.asMap(e.getValue());
            }
        }
        return null;
    }

    public int entryCount() {
        return containers.size() + objects.size();
    }

    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Vault.Entry schema = new Vault.Entry("cn=schema");
        schema.attrs.put("objectClass", List.of("subschema".getBytes(StandardCharsets.UTF_8)));
        schema.attrs.put("attributeTypes", bytes(attributeTypes));
        schema.attrs.put("objectClasses", bytes(objectClasses));
        Files.writeString(dir.resolve(SCHEMA), Ldif.entry(schema) + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(CONTAINERS), entries(containers), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(OBJECTS), entries(objects), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(REFERENCES), modifies(references), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ACLS), modifies(acls), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(SECRETS), String.join("\n", secretsNeeded) + (secretsNeeded.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
        Path svDir = dir.resolve(SERVER_VALUES_DIR);
        Files.createDirectories(svDir);
        int i = 0;
        for (Map.Entry<String, Map<String, Map<String, List<byte[]>>>> s : serverValues.entrySet()) {
            List<String> blocks = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<byte[]>>> e : s.getValue().entrySet()) {
                if (!e.getValue().isEmpty()) {
                    blocks.add(Ldif.modify(e.getKey(), "replace", e.getValue()));
                }
            }
            Files.writeString(svDir.resolve(serverFile(i++, s.getKey())),
                "# server: " + s.getKey() + "\n" + (blocks.isEmpty() ? "" : String.join("\n\n", blocks) + "\n"), StandardCharsets.UTF_8);
        }
        Map<String, Object> m = new LinkedHashMap<>(manifest);
        List<Object> serverFiles = new ArrayList<>();
        i = 0;
        for (String s : serverValues.keySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("dn", s);
            f.put("file", SERVER_VALUES_DIR + "/" + serverFile(i++, s));
            serverFiles.add(f);
        }
        m.put("serverValueFiles", serverFiles);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("attributeTypes", attributeTypes.size());
        counts.put("objectClasses", objectClasses.size());
        counts.put("containers", containers.size());
        counts.put("objects", objects.size());
        counts.put("referenceEntries", references.size());
        counts.put("aclEntries", acls.size());
        counts.put("secretsNeeded", secretsNeeded.size());
        m.put("counts", counts);
        Files.writeString(dir.resolve(MANIFEST), Json.pretty(m) + "\n", StandardCharsets.UTF_8);
    }

    public static CloneBundle read(Path dir) throws IOException {
        CloneBundle b = new CloneBundle();
        b.manifest.putAll(Json.asMap(Json.parse(Files.readString(dir.resolve(MANIFEST), StandardCharsets.UTF_8))));
        for (Ldif.Record r : Ldif.parse(Files.readString(dir.resolve(SCHEMA), StandardCharsets.UTF_8))) {
            for (byte[] v : r.entry.attrs.getOrDefault("attributeTypes", List.of())) {
                b.attributeTypes.add(new String(v, StandardCharsets.UTF_8));
            }
            for (byte[] v : r.entry.attrs.getOrDefault("objectClasses", List.of())) {
                b.objectClasses.add(new String(v, StandardCharsets.UTF_8));
            }
        }
        for (Ldif.Record r : Ldif.parse(Files.readString(dir.resolve(CONTAINERS), StandardCharsets.UTF_8))) {
            b.containers.add(r.entry);
        }
        for (Ldif.Record r : Ldif.parse(Files.readString(dir.resolve(OBJECTS), StandardCharsets.UTF_8))) {
            b.objects.add(r.entry);
        }
        for (Ldif.Record r : Ldif.parse(Files.readString(dir.resolve(REFERENCES), StandardCharsets.UTF_8))) {
            b.references.put(r.dn, Ldif.adds(r));
        }
        for (Ldif.Record r : Ldif.parse(Files.readString(dir.resolve(ACLS), StandardCharsets.UTF_8))) {
            b.acls.put(r.dn, Ldif.adds(r));
        }
        for (Object o : Json.asList(b.manifest.get("serverValueFiles"))) {
            Map<String, Object> f = Json.asMap(o);
            Path file = dir.resolve(Json.asString(f.get("file")));
            Map<String, Map<String, List<byte[]>>> perEntry = new LinkedHashMap<>();
            if (Files.exists(file)) {
                for (Ldif.Record r : Ldif.parse(Files.readString(file, StandardCharsets.UTF_8))) {
                    perEntry.put(r.dn, Ldif.adds(r));
                }
            }
            b.serverValues.put(Json.asString(f.get("dn")), perEntry);
        }
        Path secrets = dir.resolve(SECRETS);
        if (Files.exists(secrets)) {
            for (String l : Files.readAllLines(secrets, StandardCharsets.UTF_8)) {
                if (!l.isBlank()) {
                    b.secretsNeeded.add(l);
                }
            }
        }
        return b;
    }

    private static String serverFile(int i, String dn) {
        String safe = dn.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        return i + "-" + (safe.length() > 60 ? safe.substring(0, 60) : safe) + ".ldif";
    }

    private static String entries(List<Vault.Entry> list) {
        List<String> blocks = new ArrayList<>();
        for (Vault.Entry e : list) {
            blocks.add(Ldif.entry(e));
        }
        return blocks.isEmpty() ? "" : String.join("\n\n", blocks) + "\n";
    }

    private static String modifies(Map<String, Map<String, List<byte[]>>> map) {
        List<String> blocks = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<byte[]>>> e : map.entrySet()) {
            if (!e.getValue().isEmpty()) {
                blocks.add(Ldif.modify(e.getKey(), "add", e.getValue()));
            }
        }
        return blocks.isEmpty() ? "" : String.join("\n\n", blocks) + "\n";
    }

    private static List<byte[]> bytes(List<String> values) {
        List<byte[]> out = new ArrayList<>();
        for (String v : values) {
            out.add(v.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }
}
