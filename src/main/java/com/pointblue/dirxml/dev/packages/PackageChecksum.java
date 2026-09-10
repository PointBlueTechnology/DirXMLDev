package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Designer's package checksums (docs/spikes/designer-package-layer.md §1),
 * reimplemented: CRC32 over the UTF-8 bytes of a concatenation of strings.
 * <ul>
 *   <li>content: object name + canonical XML (+ content type for resources; +
 *       the names of the policy sets a policy / ECMAScript / GCV object is
 *       linked into when <em>installed</em>);</li>
 *   <li>directive: the stored installation-directive string byte-for-byte;</li>
 *   <li>folder: children's stored content checksums as decimal strings, sorted by association id;</li>
 *   <li>package: folder checksums 1..8 (9, Global Configurations, excluded).</li>
 * </ul>
 * Values are unsigned 32-bit, always written as decimal strings.
 */
public final class PackageChecksum {

    public static final String RULE = "DirXML-Rule";
    public static final String STYLESHEET = "DirXML-StyleSheet";
    public static final String RESOURCE = "DirXML-Resource";
    public static final String GCV_DEF = "DirXML-GlobalConfigDef";

    /** What Designer's serializer yields for an empty document (verified: MFAZUREBASE-UpgradeSettings). */
    public static final String EMPTY_DOCUMENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private PackageChecksum() {
    }

    public static long crc(List<String> parts) {
        CRC32 c = new CRC32();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                c.update(p.getBytes(StandardCharsets.UTF_8));
            }
        }
        return c.getValue();
    }

    public static long crc(String... parts) {
        return crc(Arrays.asList(parts));
    }

    /** True if Designer treats the content type as XML (canonicalized) rather than text (CRLF folded). */
    public static boolean isXmlContentType(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("xml");
    }

    /**
     * Content checksum of a policy, stylesheet or resource.
     *
     * @param objectClass  {@code DirXML-Rule}, {@code DirXML-StyleSheet} or {@code DirXML-Resource}
     * @param name         the object name exactly as stored
     * @param content      the XmlData element (XML content) — canonicalized Designer's way
     * @param text         the raw text for non-XML resources (ECMAScript, …); ignored when {@code content} is given
     * @param contentType  the resource content type (resources only; null otherwise)
     * @param linkedSets   the names of the policy sets the object is linked into, in Designer's enumeration order
     *                     (empty for a catalog item; policies, ECMAScript and GCV objects only)
     */
    public static long content(String objectClass, String name, Element content, String text, String contentType,
                               List<String> linkedSets) {
        List<String> parts = new ArrayList<>();
        parts.add(name);
        if (RESOURCE.equals(objectClass)) {
            if (isXmlContentType(contentType)) {
                if (content != null) {
                    parts.add(NxslCanonical.canonical(content));
                } else if (text != null && !text.isBlank()) {
                    parts.add(NxslCanonical.canonical(text));
                } else {
                    parts.add(EMPTY_DOCUMENT);   // an XML resource with no content: the declaration alone
                }
            } else if (text != null) {
                parts.add(text.replace("\r\n", "\n"));
            } else if (content != null) {
                parts.add(NxslCanonical.canonical(content));
            }
            parts.add(contentType);
            if (contentType != null && contentType.toLowerCase().startsWith("text/ecmascript")) {
                parts.addAll(linkedSets);
            }
        } else {
            if (content != null) {
                parts.add(NxslCanonical.canonical(content));
            } else if (text != null) {
                parts.add(text);
            }
            parts.addAll(linkedSets);
        }
        return crc(parts);
    }

    /**
     * Content checksum of a GCV object, computed the way Designer does it — with the engine's own
     * {@code GCDefinitions}/{@code GCValue} classes ({@code dirxml_misc.jar}, the same ones Designer links):
     * name, linked set names, {@code definitions/@display-name}, then every definition sorted by name
     * (name, {@code GCValue.getType()} code, mandatory, type-specific extras, children recursively).
     * Values never take part.
     *
     * @param configurationValues the {@code <configuration-values>} document: a package item's installation
     *                            directive carries it; an installed object's is its (merged) {@code DirXML-ConfigValues}
     */
    public static long gcv(String name, Element configurationValues, List<String> linkedSets) {
        List<String> parts = new ArrayList<>();
        parts.add(name);
        parts.addAll(linkedSets);
        if (configurationValues != null) {
            Element defsEl = "definitions".equals(configurationValues.getNodeName())
                ? configurationValues : child(configurationValues, "definitions");
            if (defsEl != null) {
                parts.add(defsEl.getAttribute("display-name"));
            }
            try {
                org.w3c.dom.Document doc = com.novell.xml.dom.DocumentFactory.newDocument();
                doc.appendChild(doc.importNode(configurationValues, true));
                com.novell.nds.dirxml.engine.gcv.GCDefinitions defs =
                    com.novell.nds.dirxml.engine.gcv.GCDefinitions.construct(doc);
                for (com.novell.nds.dirxml.engine.gcv.GCValue v : sorted(defs.iterator())) {
                    gcValue(v, parts);
                }
            } catch (com.novell.nds.dirxml.engine.gcv.GCVException e) {
                // Designer swallows a GCVException here too: the checksum is then name + sets + display-name
            }
        }
        return crc(parts);
    }

    @SuppressWarnings("unchecked")
    private static List<com.novell.nds.dirxml.engine.gcv.GCValue> sorted(java.util.Iterator<?> it) {
        List<com.novell.nds.dirxml.engine.gcv.GCValue> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add((com.novell.nds.dirxml.engine.gcv.GCValue) it.next());
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private static void gcValue(com.novell.nds.dirxml.engine.gcv.GCValue v, List<String> parts) {
        parts.add(v.getName());
        parts.add("" + v.getType());
        parts.add(Boolean.toString(v.getMandatory()));
        if (v instanceof com.novell.nds.dirxml.engine.gcv.GCStructuredValue) {
            for (com.novell.nds.dirxml.engine.gcv.GCValue d
                : sorted(((com.novell.nds.dirxml.engine.gcv.GCStructuredValue) v).getTemplate().iterator())) {
                gcValue(d, parts);
            }
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCDnRefValue) {
            parts.add(((com.novell.nds.dirxml.engine.gcv.GCDnRefValue) v).getAttrName());
            parts.add(((com.novell.nds.dirxml.engine.gcv.GCDnRefValue) v).getAuxClassName());
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCDNValue) {
            parts.add(((com.novell.nds.dirxml.engine.gcv.GCDNValue) v).getDelims());
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCEnumValue) {
            List<String> choices = new ArrayList<>();
            for (java.util.Iterator<?> it = ((com.novell.nds.dirxml.engine.gcv.GCEnumValue) v).iterator(); it.hasNext();) {
                choices.add(((com.novell.nds.dirxml.engine.gcv.GCEnumValue.EnumChoice) it.next()).getValue());
            }
            choices.sort(null);
            parts.addAll(choices);
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCIntegerValue) {
            parts.add("" + ((com.novell.nds.dirxml.engine.gcv.GCIntegerValue) v).getRangeHi());
            parts.add("" + ((com.novell.nds.dirxml.engine.gcv.GCIntegerValue) v).getRangeLo());
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCListValue) {
            parts.add(((com.novell.nds.dirxml.engine.gcv.GCListValue) v).getValue());
            parts.add(((com.novell.nds.dirxml.engine.gcv.GCListValue) v).getSeparator());
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCParent) {
            for (com.novell.nds.dirxml.engine.gcv.GCValue d : sorted(((com.novell.nds.dirxml.engine.gcv.GCParent) v).iterator())) {
                gcValue(d, parts);
            }
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCRealValue) {
            parts.add("" + ((com.novell.nds.dirxml.engine.gcv.GCRealValue) v).getRangeHi());
            parts.add("" + ((com.novell.nds.dirxml.engine.gcv.GCRealValue) v).getRangeLo());
        } else if (v instanceof com.novell.nds.dirxml.engine.gcv.GCStringValue) {
            parts.add("" + ((com.novell.nds.dirxml.engine.gcv.GCStringValue) v).getMultiline());
        }
    }

    /** Content checksum of a notification template: name, canonical XML (raw text with CRLF folded if unparsable), subject. */
    public static long template(String name, Element content, String rawText, String subject) {
        List<String> parts = new ArrayList<>();
        parts.add(name);
        if (content != null) {
            parts.add(NxslCanonical.canonical(content));
        } else if (rawText != null) {
            parts.add(rawText.replace("\r\n", "\n"));
        }
        parts.add(subject);
        return crc(parts);
    }

    /** Content checksum of an ID policy: name, prefix, area, acl, min, max, fill, areaEI, accessControl (nulls skipped, booleans/ints as strings). */
    public static long idPolicy(String name, String prefix, String area, String acl, String min, String max,
                                String fill, String areaEI, String accessControl) {
        List<String> parts = new ArrayList<>();
        parts.add(name);
        parts.add(prefix);
        parts.add(area);
        parts.add(acl);
        parts.add("" + parseIntOr(min, 0));
        parts.add("" + parseIntOr(max, 0));
        parts.add("" + "true".equalsIgnoreCase(fill));
        parts.add("" + "true".equalsIgnoreCase(areaEI));
        parts.add("" + "true".equalsIgnoreCase(accessControl));
        return crc(parts);
    }

    private static int parseIntOr(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    /**
     * Content checksum of a job (Designer's {@code JobImpl.calculateChecksum(set, 2)}): name + canonical XML, then
     * {@code job-definition/@auto-delete, @disabled, @schedule, @scope-required}, each {@code result-processing}
     * element that has an {@code audit} child (serialized), trace file / encoding / name / level / size when present.
     * Scopes and templates (project-side relations) are absent on a catalog item. Inferred; spike 7a measures it.
     */
    public static long job(String name, Element content, String traceFile, String traceEncoding, String traceName,
                           String traceLevel, String traceSize, boolean level2) {
        List<String> parts = new ArrayList<>();
        parts.add(name);
        String canon = content == null ? null : NxslCanonical.canonical(content);
        parts.add(canon);
        if (level2 && canon != null) {
            Element root = NxslCanonical.parse(canon).getDocumentElement();
            Element jd = child(root, "job-definition");
            if (jd != null) {
                parts.add(jd.getAttribute("auto-delete"));
                parts.add(jd.getAttribute("disabled"));
                parts.add(jd.getAttribute("schedule"));
                parts.add(jd.getAttribute("scope-required"));
            }
            for (Element rp : children(root, "result-processing")) {
                if (child(rp, "audit") != null) {
                    String x = NxslCanonical.serialize(rp);
                    parts.add(x.startsWith("<?xml") ? x.substring(x.indexOf("?>") + 2) : x);
                }
            }
        }
        parts.add(traceFile);
        parts.add(traceEncoding);
        parts.add(traceName);
        parts.add(traceLevel);
        parts.add(traceSize);
        return crc(parts);
    }

    /** Directive checksum: the stored directive string, byte-for-byte. */
    public static long directive(String storedDirective) {
        return crc(storedDirective);
    }

    /** Folder checksum: children (assocId → stored content checksum) sorted by assocId; empty folder = 0. */
    public static long folder(Map<String, String> assocToStoredChecksum, String provisioningData) {
        if (assocToStoredChecksum.isEmpty() && provisioningData == null) {
            return 0;
        }
        List<String> parts = new ArrayList<>(new TreeMap<>(assocToStoredChecksum).values());
        if (provisioningData != null) {
            parts.add(provisioningData);
        }
        return crc(parts);
    }

    /** Package checksum: folder checksums 1..8 as decimal strings ("0" for an absent/empty folder). */
    public static long pkg(Map<Integer, Long> folderChecksums) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < 9; i++) {
            parts.add("" + folderChecksums.getOrDefault(i, 0L));
        }
        return crc(parts);
    }

    static Element child(Element e, String name) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && c.getNodeName().equals(name)) {
                return (Element) c;
            }
        }
        return null;
    }

    static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && c.getNodeName().equals(name)) {
                out.add((Element) c);
            }
        }
        return out;
    }
}
