/**
 * Tiny XML reader for IDM-as-code manifests (driverset.xml, driver.xml,
 * library.xml). Not a general parser: no DTDs, no namespaces on lookup, no
 * external entities. Manifests from AsCodeWriter are element/attribute trees
 * with optional text in <meta>.
 */

export interface XmlElem {
  tag: string;
  attrs: Record<string, string>;
  children: XmlElem[];
  text: string;
}

export function parseXml(source: string): XmlElem {
  const stripped = source.replace(/<\?xml[^?]*\?>/i, "").replace(/<!--[\s\S]*?-->/g, "").trim();
  const { elem, next } = readElement(stripped, 0);
  if (!elem) {
    throw new Error("manifest is empty");
  }
  skipWs(stripped, next);
  return elem;
}

export function children(el: XmlElem, tag: string): XmlElem[] {
  return el.children.filter((c) => c.tag === tag);
}

export function child(el: XmlElem, tag: string): XmlElem | undefined {
  return el.children.find((c) => c.tag === tag);
}

export function attr(el: XmlElem, name: string): string {
  return el.attrs[name] ?? "";
}

function readElement(s: string, i: number): { elem: XmlElem | null; next: number } {
  i = skipWs(s, i);
  if (i >= s.length || s[i] !== "<" || s[i + 1] === "/") {
    return { elem: null, next: i };
  }
  i++;
  const nameEnd = matchName(s, i);
  const tag = s.slice(i, nameEnd);
  i = nameEnd;
  const attrs: Record<string, string> = {};
  while (true) {
    i = skipWs(s, i);
    if (i >= s.length) {
      throw new Error("unterminated start tag <" + tag);
    }
    if (s[i] === ">" ) {
      i++;
      const { children: kids, text, next } = readContent(s, i, tag);
      return { elem: { tag, attrs, children: kids, text }, next };
    }
    if (s[i] === "/" && s[i + 1] === ">") {
      return { elem: { tag, attrs, children: [], text: "" }, next: i + 2 };
    }
    const keyEnd = matchName(s, i);
    const key = s.slice(i, keyEnd);
    i = skipWs(s, keyEnd);
    if (s[i] !== "=") {
      throw new Error("expected '=' after attribute " + key);
    }
    i = skipWs(s, i + 1);
    const quote = s[i];
    if (quote !== '"' && quote !== "'") {
      throw new Error("expected quoted attribute value for " + key);
    }
    i++;
    let raw = "";
    while (i < s.length && s[i] !== quote) {
      raw += s[i++];
    }
    if (i >= s.length) {
      throw new Error("unterminated attribute " + key);
    }
    i++;
    attrs[key] = decode(raw);
  }
}

function readContent(s: string, i: number, tag: string): { children: XmlElem[]; text: string; next: number } {
  const kids: XmlElem[] = [];
  let text = "";
  while (i < s.length) {
    if (s[i] === "<") {
      if (s.startsWith("</", i)) {
        const close = i + 2;
        const nameEnd = matchName(s, close);
        const closeTag = s.slice(close, nameEnd);
        i = skipWs(s, nameEnd);
        if (s[i] !== ">") {
          throw new Error("malformed end tag </" + closeTag);
        }
        if (closeTag !== tag) {
          throw new Error("end tag </" + closeTag + "> does not match <" + tag + ">");
        }
        return { children: kids, text: decode(text).trim(), next: i + 1 };
      }
      const got = readElement(s, i);
      if (!got.elem) {
        throw new Error("expected child element in <" + tag + ">");
      }
      kids.push(got.elem);
      i = got.next;
    } else {
      text += s[i++];
    }
  }
  throw new Error("unterminated element <" + tag + ">");
}

function matchName(s: string, i: number): number {
  const start = i;
  while (i < s.length && /[:A-Za-z_]/.test(s[i]!)) {
    i++;
  }
  while (i < s.length && /[:A-Za-z0-9_.-]/.test(s[i]!)) {
    i++;
  }
  if (i === start) {
    throw new Error("expected name at " + i);
  }
  return i;
}

function skipWs(s: string, i: number): number {
  while (i < s.length && /\s/.test(s[i]!)) {
    i++;
  }
  return i;
}

function decode(raw: string): string {
  return raw
    .replace(/&#10;/g, "\n")
    .replace(/&#13;/g, "\r")
    .replace(/&#9;/g, "\t")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => String.fromCharCode(parseInt(n, 16)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}
