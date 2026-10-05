# A web Designer: assessment and design

Status: **decided 2026-10-05; the W0 spike is built** in its own repository, DirXMLDevWeb, where this note continues as `docs/design.md`. Jerry's decisions: local first (and hosted second, one server for both), full editing, a structured and an XML-first policy editor, no wasm viewer for now, its own repository, the name DirXMLDevWeb.

The question: build a web version of Designer, perhaps with WebAssembly.
The answer in one paragraph: **yes to a web workbench, no to porting Designer,
and WebAssembly is a side road, not the route.** The fastest and safest web
Designer is a browser UI over the DirXMLDev core running as a small local
service (`idm serve`), because that core already does what Designer's editors
do, as typed operations with JSON output. Compiling Java to wasm cannot remove
the two things a browser can never do for this product — speak LDAP to a vault
and carry the proprietary engine jars — so it earns a place only for narrow,
optional pieces.

## 1. Three readings of the idea

### 1.1 Port Designer itself to the browser — not viable

Measured in the DesignerModernPlatform project: Designer is 86 authored
bundles and 10,996 classes on Eclipse RCP, with 456,097 references into the
Eclipse namespace. That project showed the *desktop* move to current Eclipse
and JDK 21 is a packaging exercise. None of that carries to the web:

- **SWT is native widgets through JNI.** There is no SWT for wasm. CheerpJ, the
  one JVM-in-wasm that runs unmodified jars, supports AWT/Swing, not SWT.
- **Eclipse RAP** is the only way to put RCP code in a browser. It is alive
  (4.8 in September 2026) but it is server-side: one JVM session per user
  rendering widgets remotely. GEF support has always been partial, and
  Designer's largest surfaces are GEF editors — the workflow editor alone
  (`com.novell.soa.eai.ui.editor`) is 20,491 classes, the integration
  activities another 10,830, the modeler and policy builders on top.
- **It is OpenText's bytecode.** Re-hosting it as a service is a licensing
  question we do not get to answer, and every fix would be a binary patch.

### 1.2 Our own core compiled to wasm, everything in the browser — partly possible, wrong first step

DirXMLDev is about 51,000 lines of Java 21. The options today:

| Route | State (Oct 2026) | Fit |
|---|---|---|
| CheerpJ 4.3 (a JVM in wasm, runs unmodified jars, full OpenJDK class library) | Java 8, 11, 17; Java 21 "planned for 2026"; commercial licence for commercial use | Closest: no port. Blocked on Java 21 or a `release 17` build of our three repos |
| GraalVM Web Image (native-image → wasm) | experimental | Not something to build a product on yet |
| TeaVM (Java → WasmGC/JS) | Java 21 language support; small emulated class library | A port: 68 of our files use `org.w3c.dom`, 63 use `java.nio.file`; neither exists there |

Whatever the compiler, three limits stay:

1. **No LDAP from a browser.** Browsers have no raw sockets. Import-live, diff,
   deploy, driver start/stop, cache, named passwords and the LDAP trace all
   need a process outside the page.
2. **The engine jars.** The compile check, the Java-class check and the whole
   simulator run OpenText's engine (24 of our files import it, 39 the
   simulator). We may not serve those jars from a site. The most a browser
   could do is load the user's own copies locally.
3. **Credentials.** Vault passwords live in the Keychain or a secrets file
   today. A page has nowhere equivalent to keep them.

So a pure-wasm build would be a tree viewer and offline editor with no vault,
no compile check and no simulator: the least valuable third of the product,
reached by the most porting.

### 1.3 A web UI over the core, running locally — viable now, recommended

Everything Designer's editors do to a project already exists in DirXMLDev as a
typed operation: 67 edit operations (each one loads the tree, applies, validates,
and writes unless it introduced an error; each has `--dry-run` and `--json`),
plus the reads (`query`, `show`, `refs`, `fishbone`, `validate`, `tree.diff`,
`vault.diff`, the deploy plan) and the driver operations. The MCP server already
exposes 39 of them with schemas, and the VS Code extension already renders the
policy-flow fishbone from the CLI's JSON. A web workbench is a third client of
the same surface.

## 2. What a web Designer has to cover

Backend = what DirXMLDev has today. UI = the new work, sized S/M/L.

| Designer surface | Backend today | UI work |
|---|---|---|
| Project outline, search, references | `query`, `show`, `refs`, `check` | S |
| Modeler / policy flow diagram | fishbone JSON; renderer exists in the extension (TypeScript) | S–M |
| **Policy Builder** (DirXML Script rules, conditions, actions, tokens) | rule and policy ops; engine compile check; `ScriptGrammar` (required attributes from the DTD) | **L** — the centrepiece |
| XSLT, ECMAScript, raw XML editing | artifact ops; compile and ECMAScript checks | S (Monaco) |
| Filter, schema map, GCVs, shim and engine-control values, mapping tables | typed ops for each; overrides per stage | M |
| Entitlements, jobs | ops and checks | S–M |
| Packages (catalog, install, upgrade, build, strip, status) | complete | M |
| Compare and deploy, per stage | `vault.diff`, plan, deploy, verify, rollback, production gate | M |
| Live: driver state, start/stop, cache, named passwords | `driver.*` | S |
| Trace | LDAP trace stream, SSH tail; a Swing viewer today | M |
| Simulator | corpus runner, `--against`, `--env` | M |
| **Workflow / PRD designer** | `flow.*` ops, `FlowCheck`, bindings | **L** |
| Forms | the vendor's form builder is already a web app we launch | S (embed) |
| Roles, resources, DAL entities | `role.*`, `resource.*`, `entity.*`, `appconfig.*` | M |
| Document generator, project checker | `docs`, `validate` | S |
| Version control | the tree is files in git; nothing to build | — |

Two large UI builds (the policy builder and the workflow designer), a handful of
medium ones, and no new backend to speak of.

## 3. Architecture

```
 browser (SPA)  ──HTTP/JSON, SSE──►  idm serve (127.0.0.1)  ──►  tree on disk (git)
   outline, editors, diagrams          the DirXMLDev JVM:         vault over LDAPS
   diff and plan review                same jars, same            engine jars, simulator
   trace, simulator                    environments, Keychain     Keychain / secrets
        ▲                                     ▲
        └── an agent (CLI or MCP) edits the same tree; a file watcher tells the page
```

- **`idm serve`.** The existing JVM with an HTTP listener (the JDK's own
  `com.sun.net.httpserver`; no framework). It binds to loopback, prints a URL
  with a one-time token, and opens the browser — the Jupyter model. The engine
  jars, the environments file and the Keychain stay exactly where they are.
- **The API is the operation catalog, not a new design.** `POST /api/op/<name>`
  with the operation's declared arguments; `dryRun: true` returns the diff and
  the validation report the CLI's `--dry-run --json` returns. Reads are GETs
  over the same JSON the CLI prints. The catalog (names, arguments, read or
  write) is emitted by the Java side once and consumed by three clients: this
  UI, the MCP server (which hand-maintains 39 entries today) and the CLI help.
- **The tree on disk stays the truth.** No server-side project store. A file
  watcher pushes "tree changed" over SSE and the page refetches. That gives the
  thing Designer cannot do: a person in the browser and an agent in a terminal
  working on one tree at the same moment, each seeing the other's edits, with
  git as the history.
- **Writes keep their gates.** An edit is a dry run first, shown as a diff,
  then applied. A deploy is plan → read → confirm; production still requires
  typing the environment name. Each write carries the tree revision it was
  planned against and is refused if the tree moved.
- **Front end.** TypeScript and React; Monaco for XML, XSLT, ECMAScript and
  JSON; a graph library (React Flow or similar) for the fishbone, chains and
  workflows; the extension's fishbone renderer moves over as is.
- **Live data.** Trace lines and deploy progress over SSE; driver state polled.
- **Local server hygiene.** Loopback only, token on every request, `Host` and
  `Origin` checked (DNS-rebinding and cross-site requests), secrets redacted in
  every payload by the same rules the MCP server uses.

### Why local first

- **Licence:** the engine jars never leave the workstation, as today.
- **Credentials:** the Keychain, never browser storage, never a server.
- **Network:** the vault is reached from where Designer reaches it now.
- **Platforms:** a browser UI has no arm64, Rosetta or SWT problem — the reason
  DesignerModernPlatform exists disappears for this tool.
- **Operations:** nothing to host, patch or multi-tenant.

### Local and hosted: one server, two modes

Jerry's requirement (2026-10-05): it must run locally as well as on a server.
The design gives both from one binary and one front end; only four seams differ,
and each is an interface with two implementations from the first phase on.

| Seam | Local (`idm serve`) | Hosted (`idm serve --hosted`) |
|---|---|---|
| Who is the user | one person; a one-time token on loopback | OIDC sign-in; a session per user |
| Where the tree is | a directory on the workstation | a workspace per user or branch: a server-side clone of the team's git repository |
| Vault credentials | the Keychain or secrets file, as today | per-environment secrets in the server's store (or each user's own bind), never sent to the page |
| Engine jars | the workstation's `lib/` | supplied by the customer on their own server; we never distribute them |

Everything else is the same code: the operation catalog, validation, diff,
plan, deploy gates, trace, simulator. Local is built and proved first because
it has no new security surface. Hosted adds authentication, workspaces and an
audit trail per user, and it is the mode where "an agent and a person on one
tree" becomes "a team on one repository" — commits and pull requests from the
UI rather than a shared working directory. A customer runs the hosted mode
inside their own network, next to their vault; a public multi-tenant service is
not proposed.

### Where WebAssembly does fit

1. **A zero-install read-only viewer** of a tree or an export: the model, the
   fishbone and structural validation in the page, published as a static site.
   CheerpJ once it runs Java 21 (or a `release 17` build), or a small TypeScript
   reader of the as-code format, which is plain XML and JSON.
2. **The simulator in the page with the user's own engine jars**, loaded from
   local disk and never uploaded. Worth one spike when CheerpJ's Java 21 lands;
   the engine jars themselves are old bytecode and should run.
3. **Editor assists** (XPath and regex checks while typing) compiled once and
   used by both sides.

None of these is on the critical path.

## 4. Phases

Each is usable on its own and stops cleanly.

| Phase | Deliverable |
|---|---|
| **W0 spike** | `idm serve` and a read-only explorer: outline, artifact view, fishbone, validation panel, references. Proves the catalog-as-API, the watcher and the token model |
| **W1 configuration editing** | GCVs, filter, schema map, mapping tables, link order by drag, stage overrides; raw XML editing with validate-on-save; every write previewed as a diff |
| **W2 policy builder** | Rules, conditions, actions and tokens as structured editors over the DirXML Script grammar, with the engine's compile check live |
| **W3 compare, deploy, operate** | Vault diff per stage, plan review, confirm, progress, verify, rollback; driver state and control; live trace |
| **W4 simulator** | Cases, run, stage-by-stage output, comparison against another tree or stage |
| **W5 provisioning** | PRD flow diagram (read, then edit through `flow.*`), form builder embedded, roles and resources |
| **W6 packages** | Status, install, upgrade, build |
| **W7 hosted mode** | OIDC, workspaces as git clones, server-side secrets, per-user audit, commit and pull request from the UI. Can move earlier: the four seams exist from W0 |
| optional | The static viewer; the CheerpJ simulator spike |

W0 through W3 already replace the daily Designer loop for driver work. W2 and
W5 are where most of the effort sits.

## 5. Risks and open points

- **Policy builder scope.** It is the feature people mean by "Designer". A
  structured editor for every action and token is large; a hybrid (structured
  rule list, XML for the body with completion from the grammar) ships sooner.
- **Parity expectations.** The position stays "Designer optional": a tree still
  exports to a Designer project for teams that want it.
- **Two writers.** Revision checks make a stale write fail cleanly; they do not
  merge. Git remains the merge tool.
- **Name.** "Designer" is OpenText's product name; ours needs its own.
- **CheerpJ** is commercially licensed and does not run Java 21 yet; nothing in
  the recommended path depends on it.
- **Audience.** Internal to Point Blue, or shipped to customers? That decides
  packaging, support and how much polish W0–W1 need.

## 6. Decisions for Jerry

1. Both modes are in the design. Build local first and hosted second
   (recommended), or the reverse?
2. Version one scope: the read-only explorer with compare and deploy review
   (W0 + W3), or editing first (W0 + W1)?
3. Policy builder: full structured editor, or the hybrid?
4. Is a zero-install static viewer worth a wasm spike now, or later?
5. Where it lives: `web/` in this repository, or its own?
6. Audience, and a name.

## Sources

- DesignerModernPlatform, `designs/phase0-findings.md` and
  `designs/sample-output/summary.md` (bundle and class counts; local).
- CheerpJ roadmap and 4.3 notes: https://cheerpj.com/our-roadmap-for-modern-java-in-the-browser/ , https://labs.leaningtech.com/blog/cheerpj-4.3
- GraalVM Web Image: https://thenewstack.io/graalvm-finally-gets-java-for-webassembly/ , https://phoronix.com/news/GraalVM-Community-25.1.3
- TeaVM release notes: https://teavm.org/docs/release-notes/0.9.0.html , https://teavm.org/docs/release-notes/0.11.0.html
- Eclipse RAP: https://projects.eclipse.org/projects/rt.rap/governance , https://wiki.eclipse.org/RAP/
