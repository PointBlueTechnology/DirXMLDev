package com.pointblue.dirxml.dev;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EngineJarsTest {

    @Test
    public void namesTheJarAClassComesFrom() {
        assertEquals("xp.jar", EngineJars.jarFor("com.jclark.xml.sax.CommentDriver"));
        assertEquals("xp.jar", EngineJars.jarFor("com/jclark/xml/sax/CommentDriver"));
        assertEquals("nxsl.jar", EngineJars.jarFor("com.novell.xml.dom.DocumentImpl"));
        assertEquals("js.jar", EngineJars.jarFor("com.novell.soa.script.mozilla.javascript.Context"));
        assertEquals("jclient.jar", EngineJars.jarFor("novell.jclient.JCContext"));
        assertEquals("dirxml_misc.jar", EngineJars.jarFor("com.novell.nds.dirxml.util.XMLUtil"));
        assertEquals("dirxml.jar or dirxml_misc.jar", EngineJars.jarFor("com.novell.nds.dirxml.engine.gcv.GCDefinitions"));
        assertNull(EngineJars.jarFor("org.example.Thing"));
        assertNull(EngineJars.jarFor(null));
    }

    /** web#186: the class alone told nobody that xp.jar was missing. */
    @Test
    public void explainsAMissingClassAnywhereInTheCauses() {
        Throwable t = new IllegalStateException("could not load the policy",
            new RuntimeException(new ClassNotFoundException("com.jclark.xml.sax.CommentDriver")));
        String hint = EngineJars.explain(t);
        assertTrue(hint, hint.contains("com.jclark.xml.sax.CommentDriver"));
        assertTrue(hint, hint.contains("it is in xp.jar"));
        assertTrue(hint, hint.contains("xp-1.0.0.jar: copy that as xp.jar"));
        assertTrue(hint, hint.contains("lib/"));

        assertTrue(EngineJars.explain(new NoClassDefFoundError("novell/jclient/JCContext")).contains("jclient.jar"));
        assertNull(EngineJars.explain(new IllegalStateException("something else")));
        assertNull("not an engine class", EngineJars.explain(new ClassNotFoundException("org.example.Thing")));
    }

    @Test
    public void addsTheHintToTheBatchRunnersText() {
        String detail = "ClassNotFoundException: com.jclark.xml.sax.CommentDriver";
        String out = EngineJars.explain(detail);
        assertTrue(out, out.startsWith(detail + "\n"));
        assertTrue(out, out.contains("xp.jar"));
        assertEquals("unchanged otherwise", "assert FAIL [x]: y", EngineJars.explain("assert FAIL [x]: y"));
        assertNull(EngineJars.explain((String) null));
    }
}
