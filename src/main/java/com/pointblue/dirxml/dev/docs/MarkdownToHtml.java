package com.pointblue.dirxml.dev.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, self-contained Markdown -&gt; HTML converter for {@code idm docs
 * --format html}: headings (with stable, slug-based ids), paragraphs,
 * bold/italic/inline-code, fenced code blocks, pipe tables, unordered lists and
 * links. Not a full CommonMark implementation — just enough to turn the
 * generator's own Markdown output into one self-contained page with no external
 * resources.
 */
public final class MarkdownToHtml {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("_([^_]+)_");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]*)\\)");

    private MarkdownToHtml() {
    }

    /** {@code convert(markdown, "")}. */
    public static String convert(String markdown) {
        return convert(markdown, "");
    }

    /**
     * Converts {@code markdown} to an HTML fragment. Heading ids are
     * {@code slug(text)}, or {@code idPrefix + "-" + slug(text)} when
     * {@code idPrefix} is non-blank — lets a caller concatenating several
     * documents keep heading ids unique across them.
     */
    public static String convert(String markdown, String idPrefix) {
        String prefix = idPrefix == null || idPrefix.isBlank() ? "" : idPrefix + "-";
        String[] lines = markdown == null ? new String[0] : markdown.split("\n", -1);
        StringBuilder html = new StringBuilder();
        List<String> para = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("```")) {
                flushParagraph(html, para);
                List<String> code = new ArrayList<>();
                i++;
                while (i < lines.length && !lines[i].startsWith("```")) {
                    code.add(lines[i]);
                    i++;
                }
                html.append("<pre><code>").append(escapeHtml(String.join("\n", code))).append("</code></pre>\n");
                i++; // skip closing fence (or EOF)
                continue;
            }
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                flushParagraph(html, para);
                int level = h.group(1).length();
                String text = h.group(2).trim();
                html.append("<h").append(level).append(" id=\"").append(prefix).append(slug(text)).append("\">")
                    .append(inline(text)).append("</h").append(level).append(">\n");
                i++;
                continue;
            }
            if (line.startsWith("|")) {
                flushParagraph(html, para);
                List<String> tableLines = new ArrayList<>();
                while (i < lines.length && lines[i].startsWith("|")) {
                    tableLines.add(lines[i]);
                    i++;
                }
                html.append(renderTable(tableLines));
                continue;
            }
            if (line.startsWith("- ")) {
                flushParagraph(html, para);
                html.append("<ul>\n");
                while (i < lines.length && lines[i].startsWith("- ")) {
                    html.append("<li>").append(inline(lines[i].substring(2))).append("</li>\n");
                    i++;
                }
                html.append("</ul>\n");
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(html, para);
                i++;
                continue;
            }
            para.add(line);
            i++;
        }
        flushParagraph(html, para);
        return html.toString();
    }

    private static void flushParagraph(StringBuilder html, List<String> para) {
        if (!para.isEmpty()) {
            html.append("<p>").append(inline(String.join(" ", para))).append("</p>\n");
            para.clear();
        }
    }

    private static String renderTable(List<String> lines) {
        if (lines.size() < 2) {
            return "";
        }
        List<String> header = splitRow(lines.get(0));
        StringBuilder sb = new StringBuilder("<table>\n<thead><tr>");
        for (String h : header) {
            sb.append("<th>").append(inline(h)).append("</th>");
        }
        sb.append("</tr></thead>\n<tbody>\n");
        for (int i = 2; i < lines.size(); i++) {
            List<String> row = splitRow(lines.get(i));
            sb.append("<tr>");
            for (String c : row) {
                sb.append("<td>").append(inline(c)).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n");
        return sb.toString();
    }

    private static List<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String c : t.split("\\|", -1)) {
            out.add(c.trim());
        }
        return out;
    }

    private static String inline(String s) {
        String out = escapeHtml(s);
        out = CODE_SPAN.matcher(out).replaceAll("<code>$1</code>");
        out = BOLD.matcher(out).replaceAll("<strong>$1</strong>");
        out = ITALIC_STAR.matcher(out).replaceAll("<em>$1</em>");
        out = ITALIC_UNDERSCORE.matcher(out).replaceAll("<em>$1</em>");
        out = LINK.matcher(out).replaceAll("<a href=\"$2\">$1</a>");
        return out;
    }

    /** HTML-escapes {@code &}, {@code <} and {@code >}. */
    public static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A stable, URL-safe heading id: lower-cased, non-alphanumeric runs become one hyphen. */
    public static String slug(String text) {
        String s = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "section" : s;
    }
}
