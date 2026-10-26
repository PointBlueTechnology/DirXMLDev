package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.EnvironmentCompare;
import com.pointblue.dirxml.dev.deploy.Overrides;
import com.pointblue.dirxml.dev.deploy.ServerMap;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The two trees of a pair, side by side in the target's terms: the source's drivers and servers renamed through the
 * pair's map, and its identity (the driver set's name and DN, each driver's DN) the target's, since those never move.
 * Each side is held twice: as it is (its real values) and with every value an override could carry set aside
 * ({@link EnvironmentCompare#values}), as the files the tree writer makes of it. Comparing the second shows what
 * differs in logic; values are compared on their own.
 */
final class Sides {

    final Path targetTree;
    final Path sourceTree;
    final DriverSet target;
    final DriverSet source;
    /** Neutral files (values set aside) by tree path: what is compared and fingerprinted. */
    final Map<String, byte[]> targetFiles;
    final Map<String, byte[]> sourceFiles;
    final Map<String, String> targetValues;
    final Map<String, String> sourceValues;
    /** Each side's simulator cases directory, or null: its files compare as {@code cases/<path>}, with the trees' files. */
    Path targetCases;
    Path sourceCases;
    /** The target's driver name to the source's, for the drivers the map renames. */
    final Map<String, String> sourceNames = new TreeMap<>();
    final List<String> notes = new ArrayList<>();

    private Sides(Path targetTree, Path sourceTree, DriverSet target, DriverSet source, Map<String, byte[]> targetFiles,
                  Map<String, byte[]> sourceFiles) {
        this.targetTree = targetTree;
        this.sourceTree = sourceTree;
        this.target = target;
        this.source = source;
        this.targetFiles = targetFiles;
        this.sourceFiles = sourceFiles;
        this.targetValues = EnvironmentCompare.values(target);
        this.sourceValues = EnvironmentCompare.values(source);
    }

    static Sides read(Path targetTree, Path sourceTree, Map<String, String> map) throws IOException {
        return read(targetTree, sourceTree, map, null, null);
    }

    /**
     * With each side's cases: a case's files compare beside the tree's, as {@code cases/<path>}, its
     * {@code case.properties} naming the driver as the target does. Either cases directory may be null (no cases).
     */
    static Sides read(Path targetTree, Path sourceTree, Map<String, String> map, Path targetCases, Path sourceCases) throws IOException {
        DriverSet target = AsCodeReader.read(targetTree);
        DriverSet source = AsCodeReader.read(sourceTree);
        List<String> notes = new ArrayList<>();
        Map<String, String> renamed = translate(source, target, map, notes);
        DriverSet targetNeutral = AsCodeReader.read(targetTree);
        DriverSet sourceNeutral = AsCodeReader.read(sourceTree);
        translate(sourceNeutral, targetNeutral, map, new ArrayList<>());
        neutral(targetNeutral);
        neutral(sourceNeutral);
        Sides s = new Sides(targetTree, sourceTree, target, source, files(targetNeutral), files(sourceNeutral));
        s.notes.addAll(notes);
        renamed.forEach((from, to) -> s.sourceNames.put(to, from));
        if (targetCases != null && sourceCases != null) {
            s.targetCases = targetCases;
            s.sourceCases = sourceCases;
            s.targetFiles.putAll(cases(targetCases, Map.of()));
            s.sourceFiles.putAll(cases(sourceCases, renamed));
        }
        return s;
    }

    /** A tree path's prefix for the files of the simulator's cases, which live beside the tree. */
    static final String CASES = "cases/";

    /** Every file under a cases directory, as {@code cases/<path>}; a case's {@code driver=} renamed through {@code renames}. */
    static Map<String, byte[]> cases(Path dir, Map<String, String> renames) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        for (Map.Entry<String, byte[]> e : read(dir).entrySet()) {
            if (e.getKey().startsWith(".") || e.getKey().contains("/.")) {
                continue;
            }
            byte[] b = e.getValue();
            if (e.getKey().endsWith("/" + CASE_FILE) || e.getKey().equals(CASE_FILE)) {
                b = renameDriver(new String(b, java.nio.charset.StandardCharsets.UTF_8), renames).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            out.put(CASES + e.getKey(), b);
        }
        return out;
    }

    static final String CASE_FILE = "case.properties";

    /** A case's {@code driver=} line given the target's name; the rest of the file as it is. */
    static String renameDriver(String caseProperties, Map<String, String> renames) {
        if (renames.isEmpty()) {
            return caseProperties;
        }
        String[] lines = caseProperties.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\s*driver\\s*[=:]\\s*)(.*?)(\\s*\r?)$").matcher(lines[i]);
            if (m.matches() && renames.containsKey(m.group(2))) {
                lines[i] = m.group(1) + renames.get(m.group(2)) + m.group(3);
            }
        }
        return String.join("\n", lines);
    }

    /** The driver a case's file belongs to: its case's {@code driver=}, as the target names it; null when none says. */
    String caseDriver(String path) {
        String dir = path.substring(0, path.lastIndexOf('/'));
        while (dir.startsWith(CASES) || dir.equals("cases")) {
            for (Map<String, byte[]> side : List.of(sourceFiles, targetFiles)) {
                byte[] b = side.get(dir + "/" + CASE_FILE);
                if (b != null) {
                    java.util.Properties p = new java.util.Properties();
                    try {
                        p.load(new java.io.StringReader(new String(b, java.nio.charset.StandardCharsets.UTF_8)));
                    } catch (IOException e) {
                        return null;
                    }
                    String d = p.getProperty("driver");
                    return d == null || d.isBlank() ? null : d.trim();
                }
            }
            int slash = dir.lastIndexOf('/');
            if (slash < 0) {
                break;
            }
            dir = dir.substring(0, slash);
        }
        return null;
    }

    /**
     * The source as the target names it, in place: drivers renamed and servers mapped by the pair's map, and the
     * target's identity taken. Returns the renames applied (source's name to the target's); problems go to notes.
     */
    static Map<String, String> translate(DriverSet source, DriverSet target, Map<String, String> map, List<String> notes) {
        Map<String, String> renamed = new TreeMap<>();
        for (Map.Entry<String, String> r : Overrides.renames(map).entrySet()) {
            if (source.driver(r.getKey()) == null) {
                notes.add("drivers/" + r.getKey() + "." + Overrides.NAME + ": the source has no driver '" + r.getKey() + "'");
            } else if (source.renameDriver(r.getKey(), r.getValue())) {
                renamed.put(r.getKey(), r.getValue());
            } else if (!r.getKey().equals(r.getValue())) {
                notes.add("drivers/" + r.getKey() + "." + Overrides.NAME + ": the source already has a driver '" + r.getValue() + "'");
            }
        }
        boolean serversMapped = map.keySet().stream().anyMatch(ServerMap::isKey);
        if (serversMapped) {
            notes.addAll(ServerMap.apply(source, map));
        } else if (!lower(source.servers).equals(lower(target.servers)) && !source.servers.isEmpty()) {
            notes.add("the source's servers (" + String.join("; ", source.servers) + ") aren't mapped: add "
                + ServerMap.PREFIX + "<source server's name> = <the target's server's DN> lines to the pair's map");
        }
        String sourceDn = source.dn;
        source.name = target.name;
        source.dn = target.dn;
        for (Driver d : source.drivers) {
            Driver mine = target.driver(d.name);
            d.dn = mine != null ? mine.dn : resuffix(d.dn, sourceDn, target.dn);
            if (d.provisioning != null) {
                d.provisioning.dn = mine != null && mine.provisioning != null ? mine.provisioning.dn : resuffix(d.provisioning.dn, sourceDn, target.dn);
            }
        }
        return renamed;
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** {@code dn} under {@code from} moved under {@code to}; as it is otherwise. */
    static String resuffix(String dn, String from, String to) {
        if (dn == null || from == null || to == null) {
            return dn;
        }
        String tail = "," + from;
        if (dn.length() > tail.length() && dn.regionMatches(true, dn.length() - tail.length(), tail, 0, tail.length())) {
            return dn.substring(0, dn.length() - tail.length()) + "," + to;
        }
        return dn;
    }

    /** Every value an override could carry set aside (empty), in place. */
    static void neutral(DriverSet ds) {
        for (String key : EnvironmentCompare.values(ds).keySet()) {
            Overrides.Target t = Overrides.resolve(ds, key);
            if (t.resolved()) {
                t.set("");
            }
        }
    }

    /** The files the tree writer makes of {@code ds}, by tree path, but for what a pair never compares ({@link #compared}). */
    static Map<String, byte[]> files(DriverSet ds) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : written(ds).entrySet()) {
            if (compared(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Every file the tree writer makes of {@code ds}, by tree path. */
    static Map<String, byte[]> written(DriverSet ds) throws IOException {
        Path dir = Files.createTempDirectory("idm-promote");
        try {
            AsCodeWriter.write(ds, dir);
            return read(dir);
        } finally {
            delete(dir);
        }
    }

    /** Overrides, known differences and the pair's own files are each project's; package baselines go with their file. */
    static boolean compared(String path) {
        return !path.startsWith(Overrides.DIR + "/") && !path.startsWith(Pair.DIR + "/") && !path.startsWith(".");
    }

    static Map<String, byte[]> read(Path dir) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** The driver a tree path or value key belongs to, by the target's name; null for the set's own. */
    String driverOf(String pathOrKey) {
        if (pathOrKey.startsWith(CASES)) {
            return caseDriver(pathOrKey);
        }
        if (!pathOrKey.startsWith("drivers/")) {
            return null;
        }
        String rest = pathOrKey.substring("drivers/".length());
        String best = null;
        for (DriverSet ds : List.of(target, source)) {
            for (Driver d : ds.drivers) {
                for (String prefix : List.of(AsCodeWriter.fileSafe(d.name) + "/", d.name + ".")) {
                    if (rest.startsWith(prefix) && (best == null || d.name.length() > best.length())) {
                        best = d.name;
                    }
                }
            }
        }
        return best;
    }

    /** A target tree path as the source's tree names it (its driver's directory under the source's name). */
    String sourcePath(String path) {
        String driver = driverOf(path);
        String from = driver == null ? null : sourceNames.get(driver);
        if (from == null) {
            return path;
        }
        String prefix = "drivers/" + AsCodeWriter.fileSafe(driver) + "/";
        return path.startsWith(prefix) ? "drivers/" + AsCodeWriter.fileSafe(from) + "/" + path.substring(prefix.length()) : path;
    }
}
