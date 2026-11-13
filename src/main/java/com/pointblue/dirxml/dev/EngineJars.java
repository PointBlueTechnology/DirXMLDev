package com.pointblue.dirxml.dev;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which engine jar a class comes from, so a {@code ClassNotFoundException} or {@code
 * NoClassDefFoundError} out of the engine says what to copy into {@code lib/} instead of
 * naming a class nobody knows ({@code com.jclark.xml.sax.CommentDriver} is in {@code xp.jar}).
 * The jars are the ones {@link Doctor#REQUIRED_JARS} names, plus {@code ldap.jar}.
 */
public final class EngineJars {

    private EngineJars() {
    }

    /** Package prefix → the jar(s) holding it; the longest matching prefix wins. */
    private static final Map<String, String> BY_PREFIX = new LinkedHashMap<>();

    static {
        BY_PREFIX.put("com.jclark.", "xp.jar");
        BY_PREFIX.put("novell.jclient.", "jclient.jar");
        BY_PREFIX.put("com.novell.soa.script.", "js.jar");
        BY_PREFIX.put("com.novell.xml.", "nxsl.jar");
        BY_PREFIX.put("com.novell.xsl.", "nxsl.jar");
        BY_PREFIX.put("com.novell.io.", "nxsl.jar");
        BY_PREFIX.put("com.novell.nds.events.", "dhutil.jar");
        BY_PREFIX.put("com.novell.nds.dhutil.", "dhutil.jar");
        BY_PREFIX.put("com.novell.nds.io.", "dhutil.jar");
        BY_PREFIX.put("com.novell.nds.dstrace.", "dhutil.jar");
        BY_PREFIX.put("com.novell.nds.wire.", "dhutil.jar or dirxml_misc.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.engine.", "dirxml.jar or dirxml_misc.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.driver.", "dirxml.jar, XDS.jar or CommonDriverShim.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.io.", "dirxml.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.job.", "dirxml.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.ldap.", "dirxml_misc.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.soap.", "dirxml_misc.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.util.", "dirxml_misc.jar");
        BY_PREFIX.put("com.novell.nds.dirxml.", "dirxml.jar or dirxml_misc.jar");
        BY_PREFIX.put("com.microfocus.idm.rest.", "dirxml.jar");
        BY_PREFIX.put("com.novell.ldap.", "ldap.jar");
        BY_PREFIX.put("org.ietf.ldap.", "ldap.jar");
    }

    /** The engine jar(s) {@code className} comes from, or null if it is not an engine class. */
    public static String jarFor(String className) {
        if (className == null) {
            return null;
        }
        String c = className.trim().replace('/', '.');
        String best = null;
        String jar = null;
        for (Map.Entry<String, String> e : BY_PREFIX.entrySet()) {
            if (c.startsWith(e.getKey()) && (best == null || e.getKey().length() > best.length())) {
                best = e.getKey();
                jar = e.getValue();
            }
        }
        return jar;
    }

    /**
     * What to tell someone whose case failed for want of an engine class anywhere in {@code t}'s
     * causes; null when that is not why it failed.
     */
    public static String explain(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof ClassNotFoundException || c instanceof NoClassDefFoundError) {
                String hint = hint(c.getMessage());
                if (hint != null) {
                    return hint;
                }
            }
        }
        return null;
    }

    private static final Pattern IN_TEXT =
        Pattern.compile("(?:ClassNotFoundException|NoClassDefFoundError)(?::\\s*|\\s+)([A-Za-z_$][\\w$./]*)");

    /**
     * The same for a failure already turned into text ({@code "ClassNotFoundException: x.y.Z"}, as
     * the simulator's batch runner reports it): the text with the hint after it, or the text as it was.
     */
    public static String explain(String detail) {
        if (detail == null) {
            return null;
        }
        Matcher m = IN_TEXT.matcher(detail);
        if (m.find()) {
            String hint = hint(m.group(1));
            if (hint != null) {
                return detail + "\n" + hint;
            }
        }
        return detail;
    }

    private static String hint(String className) {
        String jar = jarFor(className);
        if (jar == null) {
            return null;
        }
        String c = className.trim().replace('/', '.');
        String copy = jar.contains(" or ") || jar.contains(", ") ? "Copy " + jar + " (whichever is missing)" : "Copy " + jar;
        String xp = jar.equals("xp.jar") ? " Engines from 4.10.2 on ship it as xp-1.0.0.jar: copy that as xp.jar." : "";
        return "The engine class " + c + " is missing: it is in " + jar + ". " + copy
            + " from an IDM engine's lib/dirxml/classes into the engine jar folder (lib/), then restart." + xp
            + " The jars the simulator needs: " + String.join(", ", Doctor.REQUIRED_JARS) + ".";
    }
}
