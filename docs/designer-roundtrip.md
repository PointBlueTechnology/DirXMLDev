# Phase 6 design — Designer round-trip, agent workflows, documentation

Status: **design** (2026-09-09). Follows [plan.md](plan.md) Phase 6; builds on
everything before it — the reader for Designer projects
(`source.ProjectReader`), the tree operations, the deployer and the operate
surface. This is the last phase of the driver-development plan; Track P
(provisioning forms) is separate.

## What Phase 6 delivers

Three things, in order of risk:

1. **Documentation from the model** — `idm docs tree/ --out docs/` renders a
   driver set as a client-ready document set (drivers, chains, policies with
   their descriptions, GCVs, filters, mapping tables, packages, customizations),
   from the same model everything else uses. Low risk, immediate value.
2. **The agent workflow skill** — a Claude Code skill for DirXMLDev (as the
   simulator has one) that turns the CLI surface into the loop the plan
   describes: *implement requirement X* → edit → validate → simulate → diff →
   deploy STG → verify → promote PRD, with the safeguards as rules the agent
   follows, plus the recipes for investigating a misbehaving driver and for
   onboarding a client's vault into a repo.
3. **The Designer-format writer** — take a Designer project on disk and update
   it to match a tree: changed content rewritten, new objects minted with
   Designer's ids and relations, deleted objects removed, packaged
   customizations marked — so a team that keeps Designer opens the project and
   sees the agent's work. Highest risk (the format is Designer's, undocumented,
   and only Designer can say whether a project still opens), so it comes with a
   human-in-the-loop spike and a strict scope.

## 1. Documentation from the model

`idm docs tree/ --out <dir> [--driver D…] [--format md|html]`:

- `README.md` — the driver set: drivers (name, shim, auth server, state
  columns left for `driverset.status` output if `--env` is given), Library
  contents, driver-set GCVs, packages present, customized packaged objects.
- `drivers/<name>.md` — per driver: what it connects to (shim class, auth
  server/id), the filter as a table (class × attr × sub/pub/merge), the schema
  map, GCVs in scope with values (password-ref values never shown), both
  channel chains in execution order with each policy's rules listed by
  description (a DirXML Script rule's `<description>` is its documentation —
  and every rule's conditions summarized in one line: *if class-name = User and
  op-attr Title changing*), Library policies the driver links, mapping tables
  it uses, named passwords it needs (`SecretInventory`), ECMAScript functions
  it calls.
- `library.md` — shared policies, tables (rendered as tables), GCV objects.
- `changes.md` when `--since <commit>` is given — the `ModelDiff` of the tree
  at that commit vs now, in prose: what changed, which drivers were affected.

Markdown is the source; `--format html` runs it through a minimal converter so
the same content can be handed over as a single self-contained page. Renders
are deterministic (so they can live in the client repo and diff cleanly).

## 2. The agent workflow skill

`skill/dirxml-dev/SKILL.md` (+ `reference/`), installable the way the
simulator's skill is. It teaches the loop, not the commands (the commands are
in the agent guide):

- **When to use** — asked to change, deploy, or operate an IDM driver set;
  asked what a driver does; asked why a driver is misbehaving.
- **The loop** — `import-live` (or `import-project`) into a git repo → orient
  (`validate`, `query`) → change (file edits + operations, one commit each) →
  `validate` → `simulate --against` (harvest a corpus first if the client has
  none: `bin/sim harvest` from the Event Logger DB, or `driver.cache view
  --out`) → `vault.diff --env stg` → `vault.deploy --env stg --dry-run` → show
  the plan and **ask** → deploy → `vault.verify` → `driver.submit --tree` for the
  canary when a subscriber policy changed → commit the audit log → PRD only when
  a human says so, with `--confirm`.
- **Rules the agent follows**: never `--force`; never `--capture-drift` on
  production without saying what the drift is; never touch a secret's value in
  a message; never `driver.stop` a real connector without confirming the cache
  will keep queueing; read every refusal as information (the agent guide's
  table); when `validate` errors on a tree from a running vault, report a
  validator bug rather than working around it.
- **Recipes**: *implement requirement X*; *promote STG → PRD*; *why is driver D
  misbehaving* (`driver.status` → `driver.trace tail --since` → `driver.cache
  view --out` → simulate the cached event against the tree → fix → deploy →
  canary); *onboard a client vault* (environments file, secrets file, import,
  git, harvest, docs).
- **Missing-inputs table** (as the simulator skill has): no environments file →
  ask for host/bind DN/driver set; no secrets → which; no SSH → trace tail
  unavailable, say so; no corpus → harvest or author; a Designer project but no
  vault → tree from the project, deploy later.

## 3. The Designer-format writer

### Scope: update a project the reader has read

`idm export-project tree/ <projectDir> [--dry-run]`: the project directory
must be one `import-project` can read (a real Designer project — `.project`,
`*.proj`, `Model/`). The writer diffs the model read from that project against
the tree (`ModelDiff`, the same engine as everything else) and applies the
changes to the project's files:

| change | what the writer does |
|---|---|
| artifact content changed | rewrite `<ID>_contents.xml` (canonical form); for a packaged object the tree marks customized, set `Idm:ContentChecksum` in the CObject meta to a content-derived integer so it differs from `_initial_state.xml`'s baseline — Designer's "modified" test is inequality (spike 2) |
| artifact added | mint an id in Designer's form (8 characters from `[0-9A-Z]`, unique in the project), write `<ID>.<Type>_` (type from the artifact kind: ScriptPolicy / StylesheetPolicy / MappingPolicy / MappingTableResource / ECMAScriptResource / GlobalConfig) with the attributes the reader knows (`name`, and for resources the content type) and `<ID>_contents.xml`; add the `Idm:Policies` / `Idm:Resources` **Child** relation on the owner (driver, channel, Library) |
| artifact removed | delete both files; remove every relation that references `#<ID>.<Type>_` |
| artifact renamed | the reader sees remove + add; the writer sees a removed id and an added name with identical content and treats it as a rename — same id, `name` attribute updated |
| linkage changed | rewrite the owner's ordered **Reference** relations for that set (`Idm:EventPolicies` … on the channel; `Idm:MappingPolicies` / `InputPolicies` / `OutputPolicies` / `ExtensionFunctions` on the driver; `Idm:GlobalConfigs` on the driver for set 14), keeping the `#<ID>.<Type>_` keys |
| driver setting / config blob changed | the attribute in the driver's CObject (`DirXML-JavaModule`, `DirXML-ShimAuthServer`, `DirXML-ShimAuthID`; `DirXML-ShimConfigInfo` / `DirXML-EngineControlValues` as the inline escaped-XML CString the reader decodes), the filter's `_contents.xml`, the driver's `<ID>_<ServerID>_DirXML-ConfigValues.xml` (per server: every server file the project has) |
| driver-set GCVs / linkage | the driver set's config-values file(s) and its `Idm:GlobalConfigs` |
| driver added / removed | **out of scope** — a new driver in Designer needs the Application, Server association, package installation records and more that the model doesn't carry; the writer refuses and says so (create the driver in Designer, or deploy to the vault and let Designer import it) |

Everything the model doesn't carry — packages, jobs, entitlements, servers,
notification templates, the workspace metadata, `_initial_state.xml` files,
checksums of untouched objects — is left byte-for-byte as it was. **The writer
never touches a file it has no reason to.**

### Verification

- **Round trip on the model**: `import-project` of the written project equals
  the tree (byte-identical as-code, modulo the Designer ids on new objects).
- **Untouched-file invariant**: every file the diff didn't call for is
  byte-identical before and after (tests assert it over the whole project).
- **Designer itself — spike 6a, human in the loop**: Jerry opens a round-tripped
  copy of the Amica project (one content change, one new policy linked into a
  chain, one rename, one packaged customization) in Designer: does it open, does
  it show the four changes, does the customized policy show as modified, does
  *Deploy* of that driver work? The findings decide what ships; anything Designer
  rejects is narrowed out of scope, not patched around.

### Not in scope

Generating a project from nothing (a vault with no Designer origin → Designer
imports from the vault instead), provisioning objects (Track P), the
workspace's `.metadata`, and `_initial_state.xml` maintenance for objects the
tree didn't touch.

## Build order

1. **Docs generation** (`docs` package; Sonnet) — renderer over the model +
   `ModelDiff`; deterministic; tests on the synthetic set and RFI.
2. **The skill** (me) — `skill/dirxml-dev/`, installable; walked through once
   end to end on the test vault by an agent session following only the skill.
3. **`ProjectWriter`** — id minting, CObject writer, relation editing on the
   existing files (DOM-level, preserving Designer's formatting where it
   matters), the change table above; tests against `test11` and a copy of the
   Amica project with the untouched-file invariant.
4. **Spike 6a with Jerry in Designer**; findings → `spikes/designer-writer.md`;
   scope adjusted.
5. `idm export-project` CLI, agent guide + skill updated, plan closed.

## Decisions to confirm

1. **Order: docs → skill → writer.** The writer is last because it's the one
   piece whose acceptance test needs a human with Designer.
2. **The writer updates an existing project only; new drivers are out of
   scope** (Designer's own vault import covers that case).
3. **Designer's own verdict decides the writer's scope** (spike 6a); nothing is
   patched around what Designer rejects.
4. **Documentation is Markdown-first, deterministic, and lives in the client
   repo** next to the tree.
