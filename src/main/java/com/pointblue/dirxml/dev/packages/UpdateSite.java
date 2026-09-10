package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Element;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Eclipse classic update site (designer-package-layer.md §5,
 * docs/spikes/package-format.md): a flat {@code site.xml} of
 * {@code <feature id="SHORT.feature" url="features/SHORT.feature_<ver>.jar" version="<ver>"/>}
 * entries; the package jar itself is {@code plugins/<SHORT>_<ver>.jar} under
 * the same base URL. Fetched jars are verified and added to the catalog the
 * same way {@code package.import} adds a local one.
 */
public final class UpdateSite {

    public static final class FetchResult {
        public final List<String> added = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public final List<String> refused = new ArrayList<>();
        public String error;
    }

    private static final class Entry {
        String shortName;
        String version;
    }

    public static FetchResult fetch(Catalog catalog, String siteBaseUrl, List<String> shortSpecs,
                                     boolean allVersions, boolean dryRun) throws IOException, InterruptedException {
        FetchResult r = new FetchResult();
        String base = siteBaseUrl.endsWith("/") ? siteBaseUrl : siteBaseUrl + "/";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        String siteXml;
        try {
            siteXml = get(client, base + "site.xml");
        } catch (Exception e) {
            r.error = "could not read " + base + "site.xml: " + e.getMessage();
            return r;
        }
        Map<String, List<Entry>> byShort = new LinkedHashMap<>();
        for (Entry e : parseSite(siteXml)) {
            byShort.computeIfAbsent(e.shortName, k -> new ArrayList<>()).add(e);
        }

        for (String spec : shortSpecs) {
            String[] sv = Resolver.splitSpec(spec);
            List<Entry> candidates = byShort.getOrDefault(sv[0], List.of());
            if (candidates.isEmpty()) {
                r.refused.add(spec + ": not found on " + base);
                continue;
            }
            List<Entry> chosen;
            if (sv[1] != null) {
                chosen = candidates.stream().filter(e -> e.version.equals(sv[1])).toList();
                if (chosen.isEmpty()) {
                    r.refused.add(spec + ": version " + sv[1] + " not found on " + base);
                    continue;
                }
            } else if (allVersions) {
                chosen = candidates;
            } else {
                Entry newest = candidates.stream().max(Comparator.comparing(e -> PackageVersion.parse(e.version))).orElse(null);
                chosen = newest == null ? List.of() : List.of(newest);
            }
            for (Entry e : chosen) {
                fetchOne(catalog, client, base, e, dryRun, r);
            }
        }
        mirrorDeprecations(client, base, catalog);
        return r;
    }

    private static void fetchOne(Catalog catalog, HttpClient client, String base, Entry e, boolean dryRun, FetchResult r) {
        String pluginUrl = base + "plugins/" + e.shortName + "_" + e.version + ".jar";
        if (dryRun) {
            r.added.add(e.shortName + "_" + e.version + " (dry-run, from " + pluginUrl + ")");
            return;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("pkg-", ".jar");
            downloadTo(client, pluginUrl, tmp);
            Catalog.AddResult ar = catalog.add(tmp, "fetch:" + pluginUrl);
            if (!ar.ok()) {
                r.refused.add(e.shortName + "_" + e.version + ": " + ar.refusal);
            } else if (ar.added) {
                r.added.add(ar.shortName + "_" + ar.version);
            } else {
                r.skipped.add(ar.shortName + "_" + ar.version + " (already in catalog)");
            }
        } catch (Exception ex) {
            r.refused.add(e.shortName + "_" + e.version + ": " + ex.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignore) {
                    // best effort
                }
            }
        }
    }

    private static void mirrorDeprecations(HttpClient client, String base, Catalog catalog) {
        try {
            String text = get(client, base + "deprecations/deprecated.properties");
            Files.writeString(catalog.dir.resolve("deprecations.properties"), text);
        } catch (Exception ignore) {
            // no deprecation list published, or unreachable — recorded, not required
        }
    }

    private static List<Entry> parseSite(String xml) {
        List<Entry> out = new ArrayList<>();
        Element root = NxslCanonical.parse(xml).getDocumentElement();
        for (Element f : PackageChecksum.children(root, "feature")) {
            String id = f.getAttribute("id");   // "<SHORT>.feature"
            Entry e = new Entry();
            e.shortName = id.endsWith(".feature") ? id.substring(0, id.length() - ".feature".length()) : id;
            e.version = f.getAttribute("version");
            out.add(e);
        }
        return out;
    }

    private static String get(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException(url + ": HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static void downloadTo(HttpClient client, String url, Path out) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(out,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        if (resp.statusCode() != 200) {
            throw new IOException(url + ": HTTP " + resp.statusCode());
        }
    }
}
