package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Vault;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Replaces a person's names on the way into a bundle (docs/vault-clone.md §6, Jerry: first
 * and last name, full name, and the local part of mail addresses), so a customer's people
 * never enter a lab. Deterministic within one export — the same real value always maps to
 * the same fake, so a full name rebuilt from a given name and a surname agrees with them and
 * two accounts for one person stay one person — and keyed with a random salt, so the mapping
 * cannot be recomputed from the bundle. Nothing is kept: the mapping lives in memory only.
 *
 * <p>Attributes: {@code givenName}, {@code sn}, {@code fullName} and {@code displayName}
 * (rebuilt as fake given + fake surname when they were built from the real ones, else mapped
 * as a whole), {@code mail} (local part → {@code fake.given.fake.surname}, domain kept, a
 * short suffix when two people would collide). {@code cn} — the login name, the RDN — is left
 * alone: a clone whose users cannot be found by name is no use, and the policy is names, not
 * identifiers.
 */
public final class Pseudonymiser {

    static final List<String> GIVEN = List.of(
        "Alma", "Amir", "Anika", "Arlo", "Asha", "Beatriz", "Bruno", "Camila", "Cyrus", "Dara", "Dev", "Eero",
        "Elif", "Emeka", "Esme", "Farah", "Felix", "Greta", "Hana", "Hugo", "Ida", "Imani", "Isak", "Jonas",
        "Juno", "Kai", "Kenji", "Lara", "Leo", "Lina", "Luca", "Maia", "Malik", "Mara", "Mateo", "Mila",
        "Nadia", "Nico", "Nia", "Noor", "Omar", "Orla", "Otto", "Priya", "Rafael", "Ravi", "Remi", "Rosa",
        "Sami", "Sana", "Selin", "Soren", "Tamar", "Tariq", "Tess", "Theo", "Uma", "Vera", "Wren", "Yara",
        "Yusuf", "Zara", "Zeke", "Ada", "Ansel", "Bea", "Cato", "Dagny", "Elio", "Freya", "Gus", "Hollis",
        "Ines", "Jude", "Kira", "Lior", "Milo", "Nell", "Oskar", "Pia", "Quinn", "Ruth", "Sol", "Tove",
        "Ulla", "Vik", "Willa", "Xavi", "Yael", "Zuri", "Anouk", "Bram", "Clio", "Dov", "Ewa", "Finn");

    static final List<String> SURNAME = List.of(
        "Abara", "Adler", "Aldana", "Arbeit", "Bakke", "Banerjee", "Baptiste", "Beck", "Bergström", "Bianchi",
        "Blau", "Calder", "Castillo", "Chen", "Dahl", "Delgado", "Demir", "Dorsey", "Ekwueme", "Elmi",
        "Farrow", "Fischer", "Fontaine", "Galanis", "Ganz", "Haddad", "Halvorsen", "Hartley", "Ibarra", "Iyer",
        "Jansen", "Jelinek", "Kaur", "Kimura", "Kowalski", "Lindqvist", "Lombardi", "Lund", "Madsen", "Mahler",
        "Marsh", "Mbeki", "Moreau", "Nakamura", "Novak", "Okafor", "Olsen", "Ortega", "Paredes", "Petrov",
        "Quist", "Rahman", "Reyes", "Rinaldi", "Rowe", "Saito", "Sandoval", "Schreiber", "Sekar", "Silva",
        "Tanaka", "Thorne", "Toller", "Ueda", "Valente", "Varga", "Vos", "Wagner", "Whitlock", "Xu",
        "Yilmaz", "Zhang", "Ziegler", "Amundsen", "Brandt", "Cruz", "Dunne", "Egan", "Frost", "Gale",
        "Hale", "Ivers", "Joshi", "Keane", "Lang", "Mora", "Nyman", "Oduya", "Pratt", "Rask", "Stroud", "Tamm");

    private final byte[] salt = new byte[16];
    private final Map<String, String> given = new HashMap<>();
    private final Map<String, String> surname = new HashMap<>();
    private final Map<String, String> whole = new HashMap<>();
    private final Map<String, String> mailLocal = new HashMap<>();
    private final Set<String> usedLocals = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private int people;

    public Pseudonymiser() {
        new SecureRandom().nextBytes(salt);
    }

    /** For tests: a fixed salt makes the mapping repeatable. */
    Pseudonymiser(byte[] fixedSalt) {
        System.arraycopy(fixedSalt, 0, salt, 0, Math.min(16, fixedSalt.length));
    }

    public int people() {
        return people;
    }

    public String givenName(String real) {
        return pick(given, real, GIVEN, "given");
    }

    public String surname(String real) {
        return pick(surname, real, SURNAME, "surname");
    }

    /**
     * A full or display name: rebuilt from the mapped given name and surname whenever both are
     * known — in the shape the real one had ({@code Surname, Given} stays that way), middle names
     * and suffixes dropped — so it always agrees with the parts; mapped as a whole only when a
     * part is missing.
     */
    public String fullName(String real, String realGiven, String realSn) {
        if (real == null) {
            return null;
        }
        String r = real.trim();
        if (realGiven != null && realSn != null) {
            String g = realGiven.trim();
            String s = realSn.trim();
            String lower = r.toLowerCase(Locale.ROOT);
            if (lower.startsWith(s.toLowerCase(Locale.ROOT) + ",")) {
                return surname(s) + ", " + givenName(g);
            }
            if (lower.startsWith(s.toLowerCase(Locale.ROOT) + " ") && lower.endsWith(" " + g.toLowerCase(Locale.ROOT))) {
                return surname(s) + " " + givenName(g);
            }
            return givenName(g) + " " + surname(s);
        }
        return whole.computeIfAbsent(r.toLowerCase(Locale.ROOT), k -> pick(given, r, GIVEN, "given") + " " + pick(surname, r, SURNAME, "surname"));
    }

    /** {@code local@domain} → a fake local part built from the person's fake names, the domain kept. */
    public String mail(String real, String realGiven, String realSn) {
        if (real == null || real.isBlank()) {
            return real;
        }
        int at = real.indexOf('@');
        String local = at < 0 ? real : real.substring(0, at);
        String domain = at < 0 ? "" : real.substring(at);
        String key = local.toLowerCase(Locale.ROOT);
        String fake = mailLocal.get(key);
        if (fake == null) {
            String g = realGiven != null ? givenName(realGiven) : pick(given, local, GIVEN, "given");
            String s = realSn != null ? surname(realSn) : pick(surname, local, SURNAME, "surname");
            String base = ascii(g) + "." + ascii(s);
            fake = base;
            int n = 2;
            while (usedLocals.contains(fake)) {
                fake = base + n++;
            }
            usedLocals.add(fake);
            mailLocal.put(key, fake);
        }
        return fake + domain;
    }

    /** Rewrites a person's name attributes in place; other entries are untouched. */
    public void apply(Vault.Entry e) {
        if (!ClonePolicy.isData(e) || !hasClass(e, "Person")) {
            return;
        }
        String realGiven = e.string("givenName");
        String realSn = e.string("sn");
        String realFull = e.string("fullName");
        String realDisplay = e.string("displayName");
        if (realGiven != null) {
            e.attrs.put("givenName", List.of(givenName(realGiven).getBytes(StandardCharsets.UTF_8)));
        }
        if (realSn != null) {
            e.attrs.put("sn", List.of(surname(realSn).getBytes(StandardCharsets.UTF_8)));
        }
        if (realFull != null) {
            e.attrs.put("fullName", List.of(fullName(realFull, realGiven, realSn).getBytes(StandardCharsets.UTF_8)));
        }
        if (realDisplay != null) {
            e.attrs.put("displayName", List.of(fullName(realDisplay, realGiven, realSn).getBytes(StandardCharsets.UTF_8)));
        }
        List<byte[]> mails = e.attrs.get("mail");
        if (mails != null) {
            List<byte[]> out = new java.util.ArrayList<>();
            for (byte[] m : mails) {
                out.add(mail(new String(m, StandardCharsets.UTF_8), realGiven, realSn).getBytes(StandardCharsets.UTF_8));
            }
            e.attrs.put("mail", out);
        }
        people++;
    }

    private static boolean hasClass(Vault.Entry e, String c) {
        for (String oc : e.objectClasses()) {
            if (oc.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    private String pick(Map<String, String> memo, String real, List<String> from, String kind) {
        if (real == null) {
            return null;
        }
        String key = real.trim().toLowerCase(Locale.ROOT);
        return memo.computeIfAbsent(key, k -> from.get((int) (hash(kind + ":" + k) % from.size())));
    }

    private long hash(String s) {
        CRC32 crc = new CRC32();
        crc.update(salt);
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        long a = crc.getValue();
        crc.reset();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        crc.update(salt);
        return (a * 31 + crc.getValue()) & 0x7fffffffffffffffL;
    }

    private static String ascii(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
