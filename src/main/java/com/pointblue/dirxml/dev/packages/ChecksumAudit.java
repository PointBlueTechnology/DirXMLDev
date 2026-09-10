package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Recomputes every stored checksum of a package jar and reports matches and
 * mismatches — spike 7a's instrument, and the integrity check {@code package.fetch}
 * runs before accepting a jar.
 */
public final class ChecksumAudit {

    public static final class Line {
        public String kind;      // content | directive | folder | package | package-directive
        public String objectClass;
        public String name;
        public String contentType;
        public String stored;
        public long recomputed;
        public boolean match;

        @Override
        public String toString() {
            return (match ? "MATCH    " : "MISMATCH ") + kind + " " + (objectClass == null ? "" : objectClass + " ")
                + "'" + name + "'" + (contentType == null ? "" : " [" + contentType + "]") + " stored=" + stored + " recomputed=" + recomputed;
        }
    }

    public final List<Line> lines = new ArrayList<>();

    public static ChecksumAudit of(PackageJar p) {
        ChecksumAudit a = new ChecksumAudit();
        Map<Integer, Map<String, String>> folders = new TreeMap<>();
        for (PackageJar.Item it : p.items) {
            long c;
            if (PackageChecksum.GCV_DEF.equals(it.objectClass)) {
                // a package GCV object's definitions live in its installation directive
                Element cv = null;
                if (it.directive != null) {
                    cv = PackageChecksum.child(NxslCanonical.parse(it.directive).getDocumentElement(), "configuration-values");
                }
                if (cv == null && it.content != null) {
                    cv = it.content;
                }
                c = PackageChecksum.gcv(it.name, cv, List.of());
            } else if ("DirXML-idPolicy".equals(it.objectClass)) {
                Element o = it.dsObject;
                c = PackageChecksum.idPolicy(it.name, PackageJar.dsAttrText(o, "DirXML-idPolPrefix"),
                    PackageJar.dsAttrText(o, "DirXML-idPolArea"), PackageJar.dsAttrText(o, "DirXML-idPolACL"),
                    PackageJar.dsAttrText(o, "DirXML-idPolMin"), PackageJar.dsAttrText(o, "DirXML-idPolMax"),
                    PackageJar.dsAttrText(o, "DirXML-idPolFill"), PackageJar.dsAttrText(o, "DirXML-idPolAreaEI"),
                    PackageJar.dsAttrText(o, "DirXML-idPolAccessControl"));
            } else if ("DirXML-Job".equals(it.objectClass)) {
                Element o = it.dsObject;
                String tf = PackageJar.dsAttrText(o, "DirXML-TraceFile");
                String te = PackageJar.dsAttrText(o, "DirXML-TraceFileEncoding");
                String tn = PackageJar.dsAttrText(o, "DirXML-TraceName");
                String tl = PackageJar.dsAttrText(o, "DirXML-TraceLevel");
                String ts = PackageJar.dsAttrText(o, "DirXML-TraceSizeLimit");
                long l2 = PackageChecksum.job(it.name, it.content, tf, te, tn, tl, ts, true);
                long l0 = PackageChecksum.job(it.name, it.content, tf, te, tn, tl, ts, false);
                c = it.storedContentChecksum != null && it.storedContentChecksum.trim().equals("" + l0) ? l0 : l2;
            } else if ("notfMergeTemplate".equals(it.objectClass)) {
                c = PackageChecksum.template(it.name, it.content, it.text, PackageJar.dsAttrText(it.dsObject, "notfMergeTemplateSubject"));
            } else {
                c = PackageChecksum.content(it.objectClass, it.name, it.content, it.text, it.contentType, List.of());
            }
            a.add("content", it.objectClass, it.name, it.storedContentChecksum, c);
            a.lines.get(a.lines.size() - 1).contentType = it.contentType;
            if (it.directive != null) {
                a.add("directive", it.objectClass, it.name, it.storedDirectiveChecksum, PackageChecksum.directive(it.directive));
            }
            if (it.assocId != null && it.storedContentChecksum != null) {
                folders.computeIfAbsent(it.folderId, k -> new LinkedHashMap<>()).put(it.assocId, it.storedContentChecksum);
            }
        }
        Map<Integer, Long> folderChecksums = new TreeMap<>();
        for (Map.Entry<Integer, String> f : p.folderNames.entrySet()) {
            long fc = PackageChecksum.folder(folders.getOrDefault(f.getKey(), Map.of()), p.folderProvisioningData.get(f.getKey()));
            folderChecksums.put(f.getKey(), fc);
        }
        a.add("package", null, p.shortName + "_" + p.version, p.pkg.getAttribute("checksum"), PackageChecksum.pkg(folderChecksums));
        if (p.directive != null) {
            a.add("package-directive", null, p.shortName + "_" + p.version, p.pkg.getAttribute("directive-checksum"),
                PackageChecksum.directive(p.directive));
        }
        return a;
    }

    private void add(String kind, String cls, String name, String stored, long recomputed) {
        Line l = new Line();
        l.kind = kind;
        l.objectClass = cls;
        l.name = name;
        l.stored = stored;
        l.recomputed = recomputed;
        l.match = stored != null && stored.trim().equals("" + recomputed);
        lines.add(l);
    }

    public boolean allMatch() {
        return lines.stream().allMatch(l -> l.match);
    }

    public List<Line> mismatches() {
        return lines.stream().filter(l -> !l.match).toList();
    }
}
