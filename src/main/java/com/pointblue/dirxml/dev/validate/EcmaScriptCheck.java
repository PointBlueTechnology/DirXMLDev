package com.pointblue.dirxml.dev.validate;

import com.novell.soa.script.mozilla.javascript.CompilerEnvirons;
import com.novell.soa.script.mozilla.javascript.Context;
import com.novell.soa.script.mozilla.javascript.EvaluatorException;
import com.novell.soa.script.mozilla.javascript.Parser;
import com.novell.soa.script.mozilla.javascript.Token;
import com.novell.soa.script.mozilla.javascript.ast.Assignment;
import com.novell.soa.script.mozilla.javascript.ast.AstNode;
import com.novell.soa.script.mozilla.javascript.ast.AstRoot;
import com.novell.soa.script.mozilla.javascript.ast.FunctionNode;
import com.novell.soa.script.mozilla.javascript.ast.Name;
import com.novell.soa.script.mozilla.javascript.ast.VariableInitializer;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ECMAScript resources and the {@code es:} calls that use them, checked with
 * Rhino — the engine's own ECMAScript runtime ({@code lib/js.jar}, shaded under
 * {@code com.novell.soa.script.mozilla.javascript} in this build rather than the
 * upstream {@code org.mozilla.javascript}).
 *
 * <p>Every ECMAScript resource (Library and driver scope) is compiled with
 * {@link Context#compileString}; a syntax error is Rhino's own diagnostic with
 * line/column. The functions a resource defines (top-level {@code function
 * name(…)} and {@code var name = function} / {@code name = function}) are read
 * from Rhino's AST ({@link Parser}, {@link AstRoot}); if the parse itself fails,
 * a regex over the text is used instead, so a syntax error in one resource
 * doesn't also cascade into spurious undefined-function findings elsewhere.
 *
 * <p>For each driver, the functions available to its policies are those defined
 * by the ECMAScript resources linked in its policy set 3. Every {@code es:name(…)}
 * call — found with a regex over every attribute value and text node of a
 * policy's content, which covers {@code token-xpath/@expression}, {@code
 * if-xpath} text, and XSLT {@code select}/{@code test} — that names an
 * undefined function is reported once per distinct name per policy. A Library
 * policy is checked once per driver that links it (its available functions
 * depend on that driver's own set-3 links), attributed to the Library policy
 * with "(as loaded by driver 'X')", and repeated verbatim findings are reported
 * once.
 *
 * <p>Codes: {@code ecmascript-empty} (W), {@code ecmascript-syntax} (E),
 * {@code ecmascript-function-undefined} (E), {@code ecmascript-unlinked} (W).
 */
public final class EcmaScriptCheck implements Check {

    private static final Pattern ES_CALL = Pattern.compile("\\bes:([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern FN_DECL = Pattern.compile("\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern FN_ASSIGN = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*function\\b");

    @Override
    public String name() {
        return "ecmascript";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        Map<String, Artifact> index = ds.index();

        List<Resource> allEcma = new ArrayList<>();
        for (Resource res : ds.library.resources) {
            if (res.isEcmaScript()) {
                allEcma.add(res);
            }
        }
        for (Driver d : ds.drivers) {
            for (Resource res : d.resources) {
                if (res.isEcmaScript()) {
                    allEcma.add(res);
                }
            }
        }
        Map<String, Set<String>> functionsByPath = new HashMap<>();
        for (Resource res : allEcma) {
            functionsByPath.put(res.path(), checkResource(res, r));
        }

        // Driver-scope resources no driver's set 3 links (Library resources are shared by design).
        Set<String> linkedPaths = new HashSet<>();
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links(PolicySet.ECMASCRIPT)) {
                linkedPaths.add(l.ref);
            }
        }
        for (Driver d : ds.drivers) {
            for (Resource res : d.resources) {
                if (res.isEcmaScript() && !linkedPaths.contains(res.path())) {
                    r.add(Finding.warning("ecmascript-unlinked", res.path(),
                        "ECMAScript resource is not linked into any driver's policy set 3"));
                }
            }
        }

        // es: calls that name an undefined function.
        Set<String> seen = new HashSet<>();
        for (Driver d : ds.drivers) {
            List<Resource> ecmaResources = Model.linkedResources(ds, d, PolicySet.ECMASCRIPT, index);
            Set<String> available = new LinkedHashSet<>();
            for (Resource res : ecmaResources) {
                available.addAll(functionsByPath.getOrDefault(res.path(), Set.of()));
            }
            boolean anyLinked = !ecmaResources.isEmpty();

            for (Policy p : Model.policies(d)) {
                checkEsCalls(p, available, anyLinked, r, seen, null);
            }
            Set<String> libRefs = new LinkedHashSet<>();
            for (PolicyLink l : d.links) {
                if (l.ref.startsWith("library/")) {
                    libRefs.add(l.ref);
                }
            }
            for (String ref : libRefs) {
                Artifact a = index.get(ref);
                if (a instanceof Policy) {
                    checkEsCalls((Policy) a, available, anyLinked, r, seen, d.name);
                }
            }
        }
    }

    /** Compiles the resource with Rhino, reports empty/syntax findings, and returns the functions it defines. */
    private static Set<String> checkResource(Resource res, Report r) {
        String path = res.path();
        String src = res.text;
        if (src == null || src.isBlank()) {
            r.add(Finding.warning("ecmascript-empty", path, "ECMAScript resource has no content"));
            return Set.of();
        }
        Context cx = Context.enter();
        try {
            cx.compileString(src, res.name, 1, null);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (e instanceof EvaluatorException) {
                EvaluatorException ee = (EvaluatorException) e;
                if (ee.getLineNumber() > 0) {
                    msg = msg + " (line " + ee.getLineNumber() + ", column " + ee.getColumnNumber() + ")";
                }
            }
            r.add(Finding.error("ecmascript-syntax", path, msg));
        } finally {
            Context.exit();
        }
        return definedFunctions(src);
    }

    /** Top-level function names an ECMAScript source defines, from Rhino's AST (regex fallback if it won't parse). */
    private static Set<String> definedFunctions(String src) {
        try {
            Parser parser = new Parser(new CompilerEnvirons());
            AstRoot root = parser.parse(src, null, 1);
            Set<String> names = new LinkedHashSet<>();
            root.visitAll(node -> collectTopLevelFunction(node, names));
            return names;
        } catch (RuntimeException e) {
            return regexFunctionNames(src);
        }
    }

    /** Records a top-level function's name, if this node is one, and returns whether to descend into its children. */
    private static boolean collectTopLevelFunction(AstNode node, Set<String> names) {
        if (node instanceof FunctionNode) {
            String n = ((FunctionNode) node).getName();
            if (n != null && !n.isEmpty()) {
                names.add(n);
            }
            return false;   // don't treat functions nested inside this one as top-level
        }
        if (node instanceof VariableInitializer) {
            VariableInitializer vi = (VariableInitializer) node;
            if (vi.getTarget() instanceof Name && vi.getInitializer() instanceof FunctionNode) {
                names.add(((Name) vi.getTarget()).getIdentifier());
                return false;
            }
        }
        if (node instanceof Assignment) {
            Assignment a = (Assignment) node;
            if (a.getOperator() == Token.ASSIGN && a.getLeft() instanceof Name && a.getRight() instanceof FunctionNode) {
                names.add(((Name) a.getLeft()).getIdentifier());
                return false;
            }
        }
        return true;
    }

    private static Set<String> regexFunctionNames(String src) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = FN_DECL.matcher(src);
        while (m.find()) {
            names.add(m.group(1));
        }
        m = FN_ASSIGN.matcher(src);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static void checkEsCalls(Policy p, Set<String> available, boolean anyLinked, Report r,
                                      Set<String> seen, String linkingDriver) {
        if (p.content == null) {
            return;
        }
        Set<String> calls = new LinkedHashSet<>();
        collectEsCalls(p.content, calls);
        if (calls.isEmpty()) {
            return;
        }
        String where = linkingDriver == null ? "" : " (as loaded by driver '" + linkingDriver + "')";
        for (String name : calls) {
            if (available.contains(name)) {
                continue;
            }
            String avail;
            if (!anyLinked) {
                avail = "no ECMAScript resources are linked in this driver's policy set 3";
            } else if (available.isEmpty()) {
                avail = "no functions are defined by the ECMAScript resource(s) linked in this driver's policy set 3";
            } else {
                avail = "available: " + String.join(", ", available);
            }
            Finding f = Finding.error("ecmascript-function-undefined", p.path(),
                "calls es:" + name + "(), which is not defined; " + avail + where);
            if (seen.add(f.code + "|" + f.path + "|" + f.message)) {
                r.add(f);
            }
        }
    }

    /** Every {@code es:name(} call found in an attribute value or text node under {@code node}. */
    private static void collectEsCalls(Node node, Set<String> out) {
        short type = node.getNodeType();
        if (type == Node.ELEMENT_NODE) {
            NamedNodeMap attrs = ((Element) node).getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                scanForEsCalls(attrs.item(i).getNodeValue(), out);
            }
        } else if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
            scanForEsCalls(node.getNodeValue(), out);
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectEsCalls(children.item(i), out);
        }
    }

    private static void scanForEsCalls(String text, Set<String> out) {
        if (text == null) {
            return;
        }
        Matcher m = ES_CALL.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
    }
}
