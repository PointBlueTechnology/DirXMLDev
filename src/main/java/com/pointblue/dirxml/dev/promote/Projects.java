package com.pointblue.dirxml.dev.promote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The two projects of a comparison or a promotion (docs/promotion.md): the target's and the source's trees, the pair's
 * name, each side's simulator cases (or none), and a map to use in place of the pair's file (a map proposed and not yet
 * written), or null.
 */
public record Projects(Path target, Path source, String pair, Path targetCases, Path sourceCases, Map<String, String> map) {

    public static Projects of(Path target, Path source, String pair) {
        return new Projects(target, source, pair, null, null, null);
    }

    public Projects withCases(Path targetCases, Path sourceCases) {
        return new Projects(target, source, pair, targetCases, sourceCases, map);
    }

    public Projects withMap(Map<String, String> map) {
        return new Projects(target, source, pair, targetCases, sourceCases, map);
    }

    /** A tree's cases by the usual layouts: {@code <tree>/cases}, or {@code cases/} beside a {@code tree/} directory; null when neither exists. */
    public static Path casesOf(Path tree) {
        Path inside = tree.resolve("cases");
        if (Files.isDirectory(inside)) {
            return inside;
        }
        Path abs = tree.toAbsolutePath().normalize();
        if (abs.getFileName() != null && abs.getFileName().toString().equals("tree") && abs.getParent() != null) {
            Path beside = abs.getParent().resolve("cases");
            if (Files.isDirectory(beside)) {
                return beside;
            }
        }
        return null;
    }

    Pair readPair() throws IOException {
        Pair p = Pair.read(target, pair);
        return map == null ? p : p.withMap(map);
    }

    Sides sides(Pair p) throws IOException {
        return Sides.read(target, source, p.map, targetCases, sourceCases);
    }
}
