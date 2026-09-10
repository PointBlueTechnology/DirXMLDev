package com.pointblue.dirxml.dev.spike;

import com.pointblue.dirxml.dev.packages.ChecksumAudit;
import com.pointblue.dirxml.dev.packages.PackageJar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Spike 7a: recompute every stored checksum of every package jar in a
 * directory (Designer's catalog) and tally matches by kind and object class.
 * <pre>java -cp … com.pointblue.dirxml.dev.spike.ChecksumSpike /Applications/Designer/packages/eclipse/plugins [maxMismatchesToPrint]</pre>
 */
public final class ChecksumSpike {

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        Map<String, int[]> tally = new TreeMap<>();   // key → {match, mismatch}
        int jars = 0;
        int failed = 0;
        int printed = 0;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.toString().endsWith(".jar")).sorted().toList();
        }
        for (Path jar : files) {
            jars++;
            ChecksumAudit a;
            try {
                a = ChecksumAudit.of(PackageJar.read(jar));
            } catch (Exception e) {
                failed++;
                System.out.println("FAILED " + jar.getFileName() + ": " + e);
                continue;
            }
            for (ChecksumAudit.Line l : a.lines) {
                String key = l.kind + (l.objectClass == null ? "" : " " + l.objectClass) + (l.contentType == null ? "" : " " + l.contentType);
                int[] t = tally.computeIfAbsent(key, k -> new int[2]);
                t[l.match ? 0 : 1]++;
                if (!l.match && printed < max) {
                    printed++;
                    System.out.println(jar.getFileName() + ": " + l);
                }
            }
        }
        System.out.println();
        System.out.println("jars: " + jars + " (" + failed + " failed to read)");
        System.out.printf("%-45s %8s %8s%n", "kind / class", "match", "mismatch");
        for (Map.Entry<String, int[]> e : tally.entrySet()) {
            System.out.printf("%-45s %8d %8d%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
    }
}
