package com.pointblue.dirxml.dev.model;

import java.util.LinkedHashMap;
import java.util.Map;

import org.w3c.dom.Element;

/**
 * An e-mail notification template ({@code notfMergeTemplate}, docs/console-gaps.md §12): one of the
 * objects in the vault's notification collection ({@code cn=Default Notification Collection,cn=Security},
 * a {@code notfTemplateCollection}, which also holds the SMTP server settings). What the vault holds:
 * <pre>
 *   notfMergeTemplateSubject   the subject line
 *   notfMergeTemplateData      the body: an &lt;html&gt; document with form:token-descriptions and $token$ markers
 *   DirXML-pkg*                package stamps when a package installed it (most are)
 * </pre>
 * In the tree: {@code templates/<name>.xml} holds the body; the manifest's {@code <template>} the subject.
 */
public final class NotificationTemplate {
    public static final String DEFAULT_COLLECTION_DN = "cn=Default Notification Collection,cn=Security";

    public final String name;
    public String subject;
    /** The body document, or null when the source had none. */
    public Element data;
    /** DN, package stamps, and other source-specific extras. */
    public final Map<String, String> meta = new LinkedHashMap<>();

    public NotificationTemplate(String name, Element data) {
        this.name = name;
        this.data = data;
    }
}
