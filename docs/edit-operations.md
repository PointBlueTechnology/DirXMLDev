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

## The surface: CLI only (decided 2026-09-08)

Everything is a `bin/idm` command with `--json`, driven from a shell — the way
agents drive the simulator today. No MCP server in this phase. What that gives
up, and why it's acceptable now:

| MCP would add | why it doesn't earn a second surface yet |
|---|---|
| a JSON schema per tool the agent reads | the skill file + `idm help <op>` do the same job, as they do for `bin/sim` |
| `destructiveHint` so the client confirms | Claude Code already confirms shell commands, and the real safeguards are the operations' own (dry-run, refuse-on-error, Phase 4's snapshot/env gating/`--yes`) — a hint the client *may* honour isn't one |
| a resident model between calls | reloading a 50-driver tree costs a second or two per command; cacheable later if it bites |
| reach into clients with no shell (Cursor, Claude Desktop) | not the audience today |

What keeps MCP cheap later: **one operation registry**. Every operation is
registered once — name, argument spec, handler, `readOnly`/`destructive` flag —
and the CLI is a dispatcher over it (`idm <op> [args] [--json] [--dry-run]`). An
MCP server, if a client ever needs one, is a thin adapter over that same
registry (the official Java SDK, stdio), not a second implementation.

| command | kind | |
|---|---|---|
| `idm summary <tree>` | read | driver set, drivers, counts, unresolved links |
| `idm query <tree> …` | read | list / describe artifacts, links, **references to an artifact**, a driver's chain in execution order, GCVs in scope, tables in reach |
| `idm show <tree> <path>` | read | the content of one artifact |
| `idm validate <tree>` | read | the report |
| `idm simulate <tree> --cases …` | read (runs the simulator) | the gate result |
| `idm policy.* / rule.* / resource.* / gcv.* / filter.* / schema-map.* / driver.set / mapping-table.*` | **write** | as above; every one accepts `--dry-run` |
| `idm package.diff <tree> <path>` | read | customization vs the package baseline |

Nothing in this phase talks to a vault; Phase 4 adds `vault.*` and `driver.*`
with the gating plan.md describes.

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

With `--json` each result is structured for the agent rather than printed.

## Build order

1. ✅ **Operation core** (`edit` package, 2026-09-08): `Registry` (name, args,
   help, factory — the CLI dispatches from it); `Refs` (the reverse-reference
   index: links, driver-set linkage, `<include>`s, Map tokens); `Transaction`
   (load → validate-before → apply → validate-after → refuse on a *new* error →
   sync-write: only changed files, managed-path deletions, `.git`/baselines/
   client files untouched; `--dry-run`, `--force`); `ArtifactOps`
   `policy.add` / `resource.add` / `artifact.set-content` / `artifact.rename` /
   `artifact.delete` / `policy.link` / `policy.unlink` / `policy.reorder`
   (orders renumbered 0..n); `Packages` (baseline snapshot + `customized` mark
   on the first edit of a packaged artifact); `idm refs`. 13 tests, plus a
   rename + add + link across a copy of the real RFI tree: every reference
   rewritten, `validate` still 0 errors.
2. **`ExportWriter`** + round-trip test against `ExportReader` on RFI/JFW; then a
   simulator smoke test: a case whose `export=` is the written file runs.
3. **`simulate`** (`BatchRunner` + `Comparer` over the swapped source).
4. **Rule and configuration operations** (`rule.*`, `gcv.*`, `filter.*`,
   `schema-map.*`, `driver.set`, `mapping-table.*`) through the registry.
5. **Read commands** (`summary`, `query`, `show`, `package.diff`) and the skill /
   agent guide for the whole surface.

Delegation: 2 (writer, spec = the reader + real exports) and 4 (well-specified
operations against the finished core) are good subagent work; the core, the
transaction semantics and the simulate gate are not.

## Decisions (confirmed 2026-09-08)

1. **Content edits stay file edits** (operations only for structure). ✅
2. **Package baseline in the tree** (`.package-baseline/`, `package.customized`
   meta) as the Phase 3 truth; server-side checksum handled by the deployer /
   Designer writer later. ✅
3. **`ExportWriter` as the simulator bridge** (rather than teaching the simulator
   to read as-code trees). ✅
4. **CLI only; no MCP server** — the registry keeps an adapter cheap if a client
   ever needs one. ✅
