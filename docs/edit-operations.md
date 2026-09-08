# Phase 3 design — edit operations, the simulate gate, and the MCP server

Status: **design** (2026-09-08). Follows [plan.md](plan.md) Phase 3; builds on the
model ([model.md](model.md)) and the validator ([validation.md](validation.md)).

## What Phase 3 delivers

An agent can *change* a driver set — add a policy, link it, edit a rule, set a
GCV, adjust the filter, override a packaged policy — without ever hand-editing
Designer's project graph, and without being able to leave the tree in a state
the engine would refuse. Every change runs through the same gate:

```
load as-code tree → apply operation to the model → validate → (simulate) → write tree
```

Three deliverables:

1. **Edit operations** — reference-aware mutations on the model, applied to an
   IDM-as-code tree as a transaction (all files written, or none).
2. **The simulate gate** — run the regression corpus against the edited tree and
   diff the result against the tree before the edit, using the simulator as-is.
3. **The MCP server** — the operations, validator and simulator as typed tools,
   with the destructive ones annotated so the client confirms them.

Not in Phase 3: writing to a vault (Phase 4), driver lifecycle (Phase 5), the
Designer-format writer (Phase 6), provisioning forms (Track P).

## Principle: files are the source of truth; operations exist for what text edits get wrong

The as-code tree is plain XML an agent can read, diff and edit directly, and
that stays true. A policy's *content* — its rules, conditions, actions — is best
edited as a file: the agent writes DirXML Script, runs `validate`, and the
engine's own compiler is the judge. Wrapping every `<do-set-dest-attr-value>` in
a typed operation would only put a worse language in front of a good one.

What a text edit gets wrong is *structure*: the linkage in `driver.xml`, the
order within a policy set, a rename that must touch every reference, a delete
that leaves a dangling link, the package bookkeeping on an override. Those are
the operations. So there are two kinds of change, and both end in `validate`:

| Change | How | Gate |
|---|---|---|
| Policy / stylesheet / ECMAScript / mapping-table **content** | edit the file (or `policy.set-content` for an agent that can't) | `validate` |
| **Structure** — add/rename/delete/link/unlink/reorder artifacts; GCV, filter, schema-map, driver-config, package marks | an operation | the operation refuses an invalid result; then `validate` |

## The operations

Each operation: loads the tree, mutates the model, runs the validator on the
result, and writes the tree only if the result has no *new* errors (errors that
were already present stay; `--force` writes anyway and says so). It returns a
structured result — what changed (artifact paths and files), the validation
report, and for package-managed artifacts the override state. Dry-run (`--dry-run`
/ `dryRun: true`) does everything but write.

### Artifacts

| operation | what it does | refuses when |
|---|---|---|
| `policy.add` `driver? scope name kind content? link?` | creates a policy (DirXML Script skeleton, XSLT skeleton, schema map, or given content) at driver/subscriber/publisher/library scope; optionally links it (`set`, `order`, or `after: <path>` / `before: <path>` / `first` / `last`) | name exists in that scope; content fails compile |
| `policy.set-content` `path content` | replaces the content of an existing policy (what an agent that can't write files uses) | fails compile |
| `policy.rename` `path newName` | renames the artifact and rewrites every link to it (all drivers' sets, driver-set linkage meta), every `<include>` that names it, and mapping-table `table=` references when it's a table | new name exists |
| `policy.delete` `path` | removes the artifact and its file | anything still links or includes it (list them); `--unlink` removes the links first |
| `policy.link` `path driver set order?` | adds a link (order defaults to last; `after`/`before`) | wrong kind for the set (`link-kind`); already linked in that set |
| `policy.unlink` `path driver set` | removes the link | not linked |
| `policy.reorder` `driver set order: [paths…]` | sets the full order of a set | a path isn't in the set; a set member is missing from the list |
| `resource.add / set-content / rename / delete` | the same for mapping tables, ECMAScript, GCV-definition resources | as above; a mapping table still referenced by a `Map` token |

`rename` and `delete` are the reason the model is reference-aware: the model
already indexes every link (`DriverSet.index()`, `unresolvedLinks()`); Phase 3
adds the reverse index (who references *this* artifact — links, includes, Map
tokens, `es:` calls into a resource) and uses it for both.

### Rules inside a DirXML Script policy

Content edits are file edits, but four structural rule operations are worth
having because agents do them constantly and get the XML wrong:

| operation | |
|---|---|
| `rule.add` `path rule-xml position` | insert a `<rule>` (validated in isolation first) at `first` / `last` / `after: <rule description>` |
| `rule.delete` `path rule` | remove a rule by description (or index) |
| `rule.move` `path rule position` | reorder |
| `rule.disable` / `rule.enable` `path rule` | `<rule disabled="true">` — what the engine honours, what Designer's checkbox sets |

### Configuration

| operation | |
|---|---|
| `gcv.set` `driver? name value` | sets a GCV's value in the driver's (or driver set's) config-values; `--define type=… display-name=…` creates the definition when absent (the engine rejects a definition with no `display-name`) |
| `gcv.delete` `driver? name` | refuses if any policy references it (token or `~name~`) |
| `filter.set-class` `driver class publisher subscriber …` / `filter.set-attr` `driver class attr publisher subscriber merge-authority …` / `filter.remove-class` / `filter.remove-attr` | edits the driver filter with the enumerations the validator checks |
| `schema-map.set` `driver ndsClass appClass? ndsAttr? appAttr?` / `schema-map.remove` | edits the driver's `<attr-name-map>` |
| `driver.set` `driver key value` | shim class, auth server / id, trace level / file, a shim-config-info or engine-control parameter by name |
| `mapping-table.set-row` / `delete-row` / `add-column` | row-level table edits by key column |

### Packages: overrides that mark themselves

Overriding a packaged policy is *the* supported customization method, so it must
be the easy path, never a refusal. What the tool guarantees:

- Every artifact that came from a package carries its identity in the model
  (`meta`: `designer.package.guid`, `designer.package.assoc-guid`, the package
  name/version, and the checksum pair the readers captured —
  `Idm:ContentChecksum` vs the `_initial_state.xml` baseline, or
  `DirXML-pkgChecksum` in the vault).
- **The first edit to a packaged artifact snapshots its pre-edit content** to
  `.package-baseline/<artifact path>` in the tree and sets
  `package.customized = true` in its manifest entry. From then on
  `package.diff <path>` shows exactly what was customized against the package,
  and a future package upgrade (Phase 6 tooling) knows what to preserve.
- The result of any operation that touched a packaged artifact says so:
  `packaged: {package, version, customized: true, baseline: ".package-baseline/…"}`.
- What we **cannot** do, and don't pretend to: reproduce Designer's checksum
  (Phase 0 spike: not a function of the content). The Designer-format writer
  (Phase 6) and the vault deployer (Phase 4) will set the *server-side* mark
  by writing the object and letting the server stamp it, then verifying the
  pair differs from the baseline. Phase 3 records the truth in the tree; it
  doesn't fake a number.

## The simulate gate

`validate` proves the engine will load the tree. `simulate` proves the edited
tree still does what the regression corpus says it did — or shows exactly what
changed. It is the simulator's existing `test-all` + `compare`, pointed at the
tree.

The simulator reads driver configuration from an export, a Designer project, an
LDIF or a live vault — not from an as-code tree, and it must not depend on this
repo. So Phase 3 adds the **`ExportWriter`**: model → Designer driver-set export
XML (`<driver-set-configuration>` with `<driver-set-attributes>`, the library, one
`<driver-configuration>` per driver, policies, linkage, resources, GCVs, filter).
The reader for that format is already proven on real exports, so the writer is
round-trip-testable: `ExportReader.read(ExportWriter.write(model))` must equal the
model, and the simulator must load the written export exactly as it loads
Designer's. The export is also what Designer imports and what a vault diff will
be computed from, so it earns its place three times over.

`idm simulate <tree> --cases <dir> [--against <tree-or-export>] [--json]` then:

1. writes the tree to a temporary driver-set export;
2. runs the simulator's `BatchRunner` over the case directory with each case's
   config source swapped to that export (the same `CaseProps.render` swap
   `compare` uses) — PASS/FAIL per case against the recorded goldens;
3. with `--against`, runs `Comparer` case by case: the edited tree vs the
   previous one, reporting per-stage and final divergence.

The result is the gate the agent reads: cases that still pass, cases whose output
changed (with the diff), cases that error. An edit that changes an output is not
wrong — it may be the point — but it is never silent.

Cases live where the client's tests live (`cases/` in the client repo, as the
Amica project is set up), harvested from the Event Logger DB or traces with the
simulator's `harvest`.

## The MCP server

A stdio MCP server (`bin/idm mcp`) exposing the same core as the CLI, with typed
JSON arguments and structured results. One process per tree; the tree path is
the server's working set (`--tree <dir>`), so tools take artifact paths, not file
paths.

| tool | annotations | |
|---|---|---|
| `model.summary` | read-only | driver set, drivers, counts, unresolved links |
| `model.query` | read-only | list / describe artifacts, links, references to an artifact, a driver's policy chain in execution order, GCVs in scope, tables in reach |
| `artifact.read` | read-only | the content of one artifact |
| `validate` | read-only | the report, `--json` shape |
| `simulate` | read-only (runs the simulator) | the gate result |
| `policy.*`, `rule.*`, `resource.*`, `gcv.*`, `filter.*`, `schema-map.*`, `driver.set`, `mapping-table.*` | **destructive**, idempotent where the operation is | as above; every one accepts `dryRun` |
| `package.diff` | read-only | customization vs the package baseline |
| `tree.status` | read-only | git status of the tree, last validate/simulate results |

Destructive tools carry `destructiveHint: true` so a client confirms them;
read-only ones `readOnlyHint: true`. Nothing in this phase talks to a vault, so
there is no `deploy` tool yet — Phase 4 adds `vault.*` and `driver.*` with the
gating plan.md describes.

Implementation: the official Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`,
Apache-2) over stdio, tools registered from a table so the CLI and the server
share one operation registry. The SDK is a Maven Central dependency — the first
non-system-scope dependency besides JUnit and the simulator — fetched once with
the network on, then offline as today.

## How an agent works with it

```
idm import-live cn=driverset1,o=system tree/        # or import-project / import
idm validate tree/                                  # 0 errors: the baseline is sound
idm harvest … cases/                                # (simulator) regression corpus, or reuse the client's

idm policy.add tree/ --driver AD --scope subscriber --name sub-ctp-NormalizeTitle \
    --link subscriber-command --after "drivers/AD/subscriber/sub-ctp-Transform"
# edit tree/drivers/AD/subscriber/sub-ctp-NormalizeTitle.policy.xml
idm validate tree/
idm simulate tree/ --cases cases/ --against tree@HEAD   # what changed, case by case
git commit                                          # the tree is the source of truth
# Phase 4: idm vault.diff → deploy STG → verify → promote
```

Through MCP the same flow is `policy.add` → the client's file edit → `validate` →
`simulate`, with each result structured for the agent rather than printed.

## Build order

1. **Operation core** (`edit` package): the reverse-reference index; `Transaction`
   (load → mutate → validate → write-or-refuse); `policy.add/rename/delete/link/
   unlink/reorder`, `resource.*`; package baseline snapshot + `customized` mark.
   Tests: each operation on the synthetic driver set from `ValidatorTest`, plus
   the refusal cases; a rename across a real tree (RFI) leaves `validate` at 0
   errors and every reference resolved.
2. **`ExportWriter`** + round-trip test against `ExportReader` on RFI/JFW; then a
   simulator smoke test: a case whose `export=` is the written file runs.
3. **`simulate`** (`BatchRunner` + `Comparer` over the swapped source).
4. **Rule and configuration operations** (`rule.*`, `gcv.*`, `filter.*`,
   `schema-map.*`, `driver.set`, `mapping-table.*`).
5. **CLI** for all of the above (one registry, `--json` everywhere).
6. **MCP server** over the registry; annotated; a client smoke test (Claude Code
   `claude mcp add`) doing the agent flow above end-to-end on the IG4 tree.

Delegation: 2 (writer, spec = the reader + real exports), 4 (well-specified
operations against the finished core) and the MCP wiring in 6 are good subagent
work; the core, the transaction semantics and the simulate gate are not.

## Decisions to confirm

1. **Content edits stay file edits** (operations only for structure) — yes unless
   you want a rule-builder DSL, which I'd argue against.
2. **Package baseline in the tree** (`.package-baseline/`, `package.customized`
   meta) as the Phase 3 truth; server-side checksum handled by the deployer /
   Designer writer later.
3. **`ExportWriter` as the simulator bridge** (rather than teaching the simulator
   to read as-code trees) — keeps the simulator independent and gives Designer
   import + vault diff the same artifact.
4. **MCP via the official Java SDK** (a Maven Central dependency).
