package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.sim.UnsupportedFeatures;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What secrets a driver needs, read off the model (docs/vault-deploy.md,
 * "Secrets"): the shim authentication password when the driver authenticates
 * (has a shim auth id or server), a Remote Loader password when its shim
 * config names a remote loader, and every named password its policies read
 * (the same scan the validator's {@code named-password} finding uses) or its
 * password-ref GCVs and shim parameters name.
 */
public final class SecretInventory {

    /** One secret a driver needs. */
    public static final class Need {
        public final String key;       // the Secrets key, e.g. "AD.named.svc"
        public final String kind;      // shim-auth | remote-loader | named
        public final String because;   // where the need comes from

        Need(String key, String kind, String because) {
            this.key = key;
            this.kind = kind;
            this.because = because;
        }

        @Override
        public String toString() {
            return key + " (" + kind + ": " + because + ")";
        }
    }

    private SecretInventory() {
    }

    public static List<Need> forDriver(DriverSet ds, Driver d) {
        List<Need> out = new ArrayList<>();
        if ((d.shimAuthId != null && !d.shimAuthId.isBlank()) || (d.shimAuthServer != null && !d.shimAuthServer.isBlank())) {
            out.add(new Need(Secrets.shimAuth(d.name), "shim-auth",
                "the driver authenticates as '" + (d.shimAuthId == null ? "" : d.shimAuthId) + "'"));
        }
        Element shim = d.config.get(Driver.SHIM_CONFIG_INFO);
        if (shim != null && usesRemoteLoader(shim)) {
            out.add(new Need(Secrets.remoteLoader(d.name), "remote-loader", "shim-config-info names a Remote Loader"));
        }
        Set<String> named = new LinkedHashSet<>();
        List<Policy> policies = new ArrayList<>(d.policies);
        policies.addAll(d.subscriber.policies);
        policies.addAll(d.publisher.policies);
        for (Policy p : policies) {
            if (p.content != null) {
                for (String n : UnsupportedFeatures.referencedNamedPasswords(p.content)) {
                    if (named.add(n)) {
                        out.add(new Need(Secrets.named(d.name, n), "named", "read by " + p.path()));
                    }
                }
            }
        }
        for (Policy p : ds.library.policies) {
            if (p.content != null && linksLibrary(d, p.path())) {
                for (String n : UnsupportedFeatures.referencedNamedPasswords(p.content)) {
                    if (named.add(n)) {
                        out.add(new Need(Secrets.named(d.name, n), "named", "read by " + p.path()));
                    }
                }
            }
        }
        for (String n : passwordRefs(d.config.get(Driver.CONFIG_VALUES))) {
            if (named.add(n)) {
                out.add(new Need(Secrets.named(d.name, n), "named", "password-ref GCV"));
            }
        }
        for (String n : passwordRefs(shim)) {
            if (named.add(n)) {
                out.add(new Need(Secrets.named(d.name, n), "named", "password-ref shim parameter"));
            }
        }
        return out;
    }

    private static boolean linksLibrary(Driver d, String path) {
        for (var l : d.links) {
            if (l.ref.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /** Names of {@code type="password-ref"} definitions whose value is a named-password name. */
    static List<String> passwordRefs(Element configValues) {
        List<String> out = new ArrayList<>();
        if (configValues == null) {
            return out;
        }
        for (Element def : Xds.descendantsByName(configValues, "definition")) {
            if ("password-ref".equals(def.getAttribute("type"))) {
                List<Element> v = Xds.childrenByName(def, "value");
                String name = v.isEmpty() ? def.getAttribute("name") : Xds.text(v.get(0)).trim();
                if (!name.isBlank()) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** True if the shim config carries Remote Loader connection parameters. */
    static boolean usesRemoteLoader(Element shimConfig) {
        for (Element def : Xds.descendantsByName(shimConfig, "definition")) {
            String n = def.getAttribute("name").toLowerCase();
            if (n.contains("remote-loader") || n.contains("remoteloader")) {
                List<Element> v = Xds.childrenByName(def, "value");
                if (!v.isEmpty() && !Xds.text(v.get(0)).isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }
}
