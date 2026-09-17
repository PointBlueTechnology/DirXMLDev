# VS Code / Cursor extension v1 — policy-flow fishbone

Status: **v1 scaffold** (2026-09-17). A thin graphical presentation for
DirXMLDev: the classic Designer **fishbone** (policy flow) of one driver, read
from an IDM-as-code tree. Not a Designer GEF replacement. Not an editor on the
canvas.

The harness stays the CLI (`bin/idm`) and the as-code tree. The extension
**reads** that tree and **opens** files. Agents keep editing files / running
operations; the picture follows the disk.

## 1. Boundaries

```
  agent / human
       │  edits files, runs bin/idm (validate, operations, deploy)
       ▼
  IDM-as-code tree          ◄── source of truth (docs/model.md)
  (driverset.xml, driver.xml, *.policy.xml)
       │
       ├── CLI  bin/idm query chain | show | validate | …
       │         the engine-facing surface; one operation registry
       │
       └── extension  dirxmldev-visual
             reads manifests → fishbone JSON → webview
             click → vscode.open the content file
             refresh → re-read from disk
```

| Layer | Owns | Must not |
|---|---|---|
| **As-code tree** | Policies, linkage, names | — |
| **`bin/idm` / Java model** | Validate, structure ops, deploy, simulate | Draw UI; know VS Code |
| **Extension** | Discover a tree, render the fishbone, open files, reload | Write the tree; call the vault; invent a second policy-set vocabulary |
| **MCP (future / stub)** | Typed tools an agent can call (`fishbone.get` / `refresh` / `reveal`) | Duplicate edit operations; bypass validate → simulate → diff → snapshot |

There is **no MCP server in this repo today** (`docs/edit-operations.md`: CLI
only; the operation registry is the adapter point). v1 therefore:

- The extension talks to **disk**, not to a protocol server.
- Thin hooks (schema + a sentinel file + the three VS Code commands) are what a
  future MCP adapter, or Cursor’s command runner, would call. Stubs live next
  to the extension (`extensions/dirxmldev-visual/mcp/`).

Do not route visualization through `lib/` or the simulator. The manifests are
deliberately small XML; the extension parses them in TypeScript so F5 works
without JDK 21 or the proprietary jars.

## 2. Project markers (activation)

v1 activates when the workspace looks like DirXMLDev / IDM work, using markers
this repo actually uses — not a guessed `.idm-project` file.

| Marker | Meaning | What v1 does |
|---|---|---|
| `driverset.xml` | Root of an **IDM-as-code** tree (`docs/model.md`) | Primary: load drivers, draw fishbone |
| `drivers/*/driver.xml` | One driver’s manifest + `<linkage>` | Select this driver when the active editor is under it |
| `library/library.xml` | Library artifacts (shared policies, tables, ECMAScript) | Resolve `library/…` link refs to files |
| `bin/idm` | This (or a client) DirXMLDev checkout | Activate; still need a `driverset.xml` to draw |
| `.project` with nature `com.novell.idm.DesignerProjectNature` | Designer project on disk | Activate and say: import with `idm import-project` — v1 does not parse CObject `*.Driver_` files |

Walkthrough convention is `tree/driverset.xml` under a client repo. The
extension searches workspace folders for `**/driverset.xml` (skipping
`node_modules` / `target` / `.git`). If the active editor sits inside a tree,
that tree wins.

## 3. File types the fishbone reads

Only as-code layout (`AsCodeWriter` / `docs/model.md`):

```
<root>/
  driverset.xml                     drivers, dirs
  library/library.xml               optional; artifacts with @file
  library/<name>.policy.xml | .js | .mapping-table.xml | .gcv.xml
  drivers/<fileSafe(name)>/
    driver.xml                      artifacts + <linkage><set key="…">
    driver-filter.xml               optional spine node
    <name>.policy.xml               driver-scope (schema map, input, output)
    subscriber/<name>.policy.xml
    publisher/<name>.policy.xml
```

`driver.xml` is the registry. Content files are pure policy/resource XML (or
`.js`). Link `ref` values are artifact **paths** (`library/<name>`,
`drivers/<driver>/<name>`, `drivers/<driver>/subscriber/<name>`, …) — the same
strings `bin/idm show` and `bin/idm query chain` use.

The extension does **not** parse Designer `_contents.xml` / `*.ScriptPolicy_`
in v1.

## 4. Fishbone data model

The picture is the engine’s policy sets (`com.pointblue.dirxml.dev.model.PolicySet`
keys), laid out as Designer does — **not** a generic flowchart.

### 4.1 Sets (keys must match the Java enum)

| key | Designer label | Where on the fishbone |
|---|---|---|
| `publisher-event` | Event Transformation | Publisher rib |
| `publisher-matching` | Matching | Publisher rib |
| `publisher-create` | Creation | Publisher rib |
| `publisher-placement` | Placement | Publisher rib |
| `publisher-command` | Command Transformation | Publisher rib |
| `subscriber-event` | Event Transformation | Subscriber rib |
| `subscriber-matching` | Matching | Subscriber rib |
| `subscriber-create` | Creation | Subscriber rib |
| `subscriber-placement` | Placement | Subscriber rib |
| `subscriber-command` | Command Transformation | Subscriber rib |
| `schema-mapping` | Schema Mapping | Spine (shared) |
| `input` | Input Transformation | Publisher / application end |
| `output` | Output Transformation | Subscriber / application end |
| `ecmascript` | ECMAScript | Driver resources strip (not a channel bone) |
| `gcv` | GCVs | Driver resources strip |

Channel execution order is the same list `ReadCli` uses for
`idm query chain`:

- Publisher: `input` → `schema-mapping` → `publisher-event` → matching → create → placement → command
- Subscriber: `subscriber-event` → matching → create → placement → command → `schema-mapping` → `output`

The **drawing** order is Designer’s aligned ribs, Identity Vault on the left,
application on the right, so Event / Matching / Creation / Placement / Command
stack vertically on each side of the spine.

### 4.2 JSON (shared by the webview, the dump CLI, and MCP `fishbone.get`)

```json
{
  "treeRoot": "/abs/path/to/tree",
  "driverSet": { "name": "driverset1", "dn": "cn=driverset1,o=system" },
  "driver": { "name": "AD Driver", "dn": "…", "shimClass": "…", "dir": "drivers/AD Driver" },
  "spine": [
    {
      "id": "bone:schema-mapping",
      "key": "schema-mapping",
      "label": "Schema Mapping",
      "channel": "spine",
      "setId": 0,
      "policies": [
        {
          "id": "policy:schema-mapping:0:drivers/AD Driver/sch_Map",
          "ref": "drivers/AD Driver/sch_Map",
          "name": "sch_Map",
          "order": 0,
          "kind": "policy",
          "file": "drivers/AD Driver/sch_Map.policy.xml",
          "unresolved": false
        }
      ]
    }
  ],
  "publisher": [ { "id": "bone:publisher-event", "key": "publisher-event", "…": "…" } ],
  "subscriber": [ "…" ],
  "resources": [ { "key": "ecmascript", "channel": "driver" } ],
  "filter": { "id": "config:driver-filter", "file": "drivers/AD Driver/driver-filter.xml" }
}
```

`file` is always relative to `treeRoot`. Click / reveal opens that path. Empty
bones stay on the diagram (Designer shows empty policy sets) and do not open a
file.

Schema: `extensions/dirxmldev-visual/src/fishbone.ts` (`FishboneModel`). Dump:

```bash
node extensions/dirxmldev-visual/out/cli.js dump <tree> [driver]
```

## 5. First three commands

| Command id | Title | Behaviour |
|---|---|---|
| `dirxmldev.fishbone.show` | DirXMLDev: Show Policy Flow (Fishbone) | Resolve tree + driver (active editor, else Quick Pick) and open the webview |
| `dirxmldev.fishbone.reveal` | DirXMLDev: Reveal Fishbone Node Source | Open the content file for the **selected** bone/policy (last click in the webview). Multi-policy bone → Quick Pick |
| `dirxmldev.fishbone.refresh` | DirXMLDev: Refresh Fishbone from Disk | Re-read manifests and content paths; rebuild the SVG. No-op message if nothing is open (then equivalent to Show) |

Clicking a policy chip or a bone with exactly one policy runs the same path as
Reveal. The webview does not edit linkage.

## 6. MCP / agent hooks (thin)

Repo decision: no MCP server yet. Hooks an agent (or a future adapter on
`edit.Registry`) should use after it edits the tree:

### 6.1 Tools (stub schema)

Defined in `extensions/dirxmldev-visual/mcp/tools.json`:

| Tool | Args | Implementation today |
|---|---|---|
| `fishbone.get` | `tree` (dir with `driverset.xml`), `driver?` | `cli.js dump` / TypeScript `loadFishbone` |
| `fishbone.refresh` | `tree?`, `driver?` | Touch `<tree>/.dirxmldev/fishbone.refresh` **or** run command `dirxmldev.fishbone.refresh` |
| `fishbone.reveal` | `tree`, `ref` (artifact path) | Open `file` for that `ref` (VS Code command or `code -g`) |

`fishbone.refresh` is the important one: after `policy.*` / file edits, the
agent triggers a reload so the open diagram matches disk. The extension
watches:

- `**/driver.xml`, `**/driverset.xml`, `**/library.xml`
- `**/*.policy.xml`
- `**/.dirxmldev/fishbone.refresh`

### 6.2 Sentinel (works without MCP)

```bash
mkdir -p tree/.dirxmldev
date -Iseconds > tree/.dirxmldev/fishbone.refresh
```

`.dirxmldev/` is local tooling state; do not commit it (see the extension
README). Same effect as the Refresh command.

### 6.3 Future CLI (not in v1)

A natural harness command, once someone wants JSON from Java rather than from
the extension parser:

```
bin/idm query <tree> fishbone <driver> [--json]
```

It would be the same payload as §4.2, built from `Driver.links(PolicySet)` —
the data `query chain` already prints as text. v1 does not add it: the
TypeScript dump is enough to ship, and `lib/` is not required to open the
picture.

### 6.4 What agents must not do through these hooks

No deploy, no vault write, no silent package-content edit. Refresh is
read-only. Edits stay file edits + `bin/idm` operations + `validate`.

## 7. Non-goals (v1)

- Drag-reorder policies, add/remove bones on canvas (that is `policy.link` /
  `policy.unlink` / `policy.add`)
- Designer project writer/reader inside the webview
- Filter class/attribute GEF, schema map table editor, GCV forms, PRD flow
- Talking to a live vault

## 8. Layout in this repo

- Design: this file
- Extension: `extensions/dirxmldev-visual/`
- Sample as-code driver (the fishbone smoke target):
  `extensions/dirxmldev-visual/sample-tree/`
- Install / F5 / smoke: `extensions/dirxmldev-visual/README.md`
