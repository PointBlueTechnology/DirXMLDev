package com.pointblue.dirxml.dev.deploy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory {@link VaultAccess} fake — the same seam {@code Operate.Engine} gives the operate
 * tests, here for {@link PlanTest} ({@code --delete-driver} needs a driver's run state and a
 * subtree search at plan time) and {@link DeployerTest} (the whole {@link Deployer#run()} flow
 * without a live, final {@link Vault}). DNs are matched case-insensitively, like the real vault.
 */
final class FakeVault implements VaultAccess {

    final Map<String, Vault.Entry> byDn = new LinkedHashMap<>();
    final Map<String, Integer> driverStates = new HashMap<>();
    final Map<String, Integer> driverStartOptions = new HashMap<>();
    final List<String> restarted = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();
    final Map<String, List<String>> namedPasswords = new HashMap<>();
    boolean closed;

    /** Seeds an entry as-is (its {@code objectClass} attribute must already be set). */
    void seed(Vault.Entry e) {
        byDn.put(key(e.dn), e);
    }

    void setDriverState(String driverDn, int state) {
        driverStates.put(key(driverDn), state);
    }

    @Override
    public Vault.Entry read(String dn) {
        return byDn.get(key(dn));
    }

    @Override
    public boolean exists(String dn) {
        return byDn.containsKey(key(dn));
    }

    @Override
    public List<Vault.Entry> search(String base, String filter, int scope) {
        String b = key(base);
        List<Vault.Entry> out = new ArrayList<>();
        for (Map.Entry<String, Vault.Entry> e : byDn.entrySet()) {
            if (e.getKey().equals(b) || e.getKey().endsWith("," + b)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    @Override
    public void add(String dn, List<String> objectClasses, Map<String, List<byte[]>> attrs) {
        Vault.Entry e = new Vault.Entry(dn);
        List<byte[]> oc = new ArrayList<>();
        for (String c : objectClasses) {
            oc.add(c.getBytes(StandardCharsets.UTF_8));
        }
        e.attrs.put("objectClass", oc);
        e.attrs.putAll(attrs);
        byDn.put(key(dn), e);
    }

    @Override
    public void addObjectClasses(String dn, List<String> classes) {
        Vault.Entry e = byDn.get(key(dn));
        if (e == null) {
            throw new Vault.VaultException("add object classes " + dn + ": no such object", null);
        }
        List<byte[]> oc = new ArrayList<>(e.attrs.getOrDefault("objectClass", List.of()));
        for (String c : classes) {
            if (!e.hasClass(c)) {
                oc.add(c.getBytes(StandardCharsets.UTF_8));
            }
        }
        e.attrs.put("objectClass", oc);
    }

    @Override
    public void replace(String dn, String attr, List<byte[]> values) {
        Vault.Entry e = byDn.get(key(dn));
        if (e == null) {
            throw new Vault.VaultException("modify " + dn + " " + attr + ": no such object", null);
        }
        if (values.isEmpty()) {
            e.attrs.remove(attr);
        } else {
            e.attrs.put(attr, values);
        }
    }

    @Override
    public void delete(String dn) {
        if (byDn.remove(key(dn)) == null) {
            throw new Vault.VaultException("delete " + dn + ": no such object", null);
        }
        deleted.add(dn);
    }

    @Override
    public int driverState(String driverDn) {
        return driverStates.getOrDefault(key(driverDn), Vault.STATE_STOPPED);
    }

    @Override
    public int driverStartOption(String driverDn) {
        return driverStartOptions.getOrDefault(key(driverDn), Vault.START_MANUAL);
    }

    @Override
    public void restartDriver(String driverDn) {
        restarted.add(driverDn);
        driverStates.put(key(driverDn), Vault.STATE_RUNNING);
    }

    @Override
    public void setDriverStartOption(String driverDn, int option) {
        driverStartOptions.put(key(driverDn), option);
    }

    @Override
    public String waitForState(String driverDn, int wanted, int seconds) {
        return Vault.stateName(driverStates.getOrDefault(key(driverDn), Vault.STATE_STOPPED));
    }

    @Override
    public List<String> namedPasswords(String dn) {
        return namedPasswords.getOrDefault(key(dn), List.of());
    }

    @Override
    public void setNamedPassword(String dn, String name, String displayName, char[] value) {
        namedPasswords.computeIfAbsent(key(dn), k -> new ArrayList<>()).add(name);
    }

    @Override
    public void close() {
        closed = true;
    }

    private static String key(String dn) {
        return dn.toLowerCase(Locale.ROOT);
    }
}
