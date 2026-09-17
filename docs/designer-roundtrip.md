# Phase 6 design — Designer round-trip, agent workflows, documentation

Status: **design confirmed, building** (2026-09-09). Follows [plan.md](plan.md) Phase 6; builds on
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
| artifact content changed | rewrite `<ID>_contents.xml` (canonical form); for a packaged object the tree marks customized, set `Idm:ContentChecksum` in the CObject meta to Designer's own checksum recipe (`packages/InstalledChecksum` — the same one the vault stamp and `package.status` use) so it differs from `_initial_state.xml`'s baseline — Designer's "modified" test is inequality (spike 2) |
| artifact added | mint an id in Designer's form (8 characters from `[0-9A-Z]`, unique in the project), write `<ID>.<Type>_` (type from the artifact kind: ScriptPolicy / StylesheetPolicy / MappingPolicy / MappingTableResource / ECMAScriptResource / GlobalConfig) with the attributes the reader knows (`name`, and for resources the content type) and `<ID>_contents.xml`; add the `Idm:Policies` / `Idm:Resources` **Child** relation on the owner (driver, channel, Library) |
| artifact removed | delete both files; remove every relation that references `#<ID>.<Type>_` |
| artifact renamed | the reader sees remove + add; the writer sees a removed id and an added name with identical content and treats it as a rename — same id, `name` attribute updated |
| linkage changed | rewrite the owner's ordered **Reference** relations for that set (`Idm:EventPolicies` … on the channel; `Idm:MappingPolicies` / `InputPolicies` / `OutputPolicies` / `ExtensionFunctions` on the driver; `Idm:GlobalConfigs` on the driver for set 14), keeping the `#<ID>.<Type>_` keys |
| driver setting / config blob changed | the attribute in the driver's CObject (`DirXML-JavaModule`, `DirXML-ShimAuthServer`, `DirXML-ShimAuthID`; `DirXML-ShimConfigInfo` / `DirXML-EngineControlValues` as the inline escaped-XML CString the reader decodes), the filter's `_contents.xml`, the driver's `<ID>_<ServerID>_DirXML-ConfigValues.xml` (per server: every server file the project has) |
| driver icon added / changed / removed | the `icon` `CHeavyData` attribute on the driver's CObject and the sibling `<ID>_icon.<ext>` that holds the bytes, moved together; a format change (`gif` → `png`) replaces the old file. New bytes in the same format leave the CObject alone. Nothing is deployed for it — the vault has no icon (`docs/designer-new-project.md` §7.2c) |
| driver-set GCVs / linkage | the driver set's config-values file(s) and its `Idm:GlobalConfigs` |
| driver added | a **non-packaged** driver (no package meta on it) is written: `<ID>.Driver_` with its attributes, `Subscriber_` / `Publisher_` / `Filter_` children and relations, its artifacts, the driver set's `Idm:Drivers` relation — Designer's verdict in spike 6a decides whether that is enough. A **packaged** driver is refused with a clear message: the project needs Designer's `IdmPackage` installation records, which only Designer produces — deploy it to the vault and let Designer *Import from the Identity Vault* |
| driver removed | **refused** (as deploy refuses): a driver is removed from a project deliberately, in Designer |

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
imports from the vault instead), packaged-driver creation in a project (the
package catalog is Designer's), the workspace's `.metadata`, and
`_initial_state.xml` maintenance for objects the tree didn't touch.
*2026-09-17: the first two are proposed as the next track —
[designer-new-project.md](designer-new-project.md).*

### Provisioning (JSON forms + PRDs — Track P step 6, 2026-09-11)

`ProjectWriter.update` also carries a driver's forms and PRDs into an
existing project, for the `Model/Provisioning/<AppConfig dir>/` the reader
already ties to that driver (see `ProjectReader#attachProvisioning`): a form
is added/removed/changed as its compact document plus a minted digest item;
a PRD is added/removed/changed as the union `.prd` (`<provision-request>`
re-inserted before `<process>`) plus a digest with localized display/descr,
category key and one `digest-dependency` per form binding. This follows the
same rules as everything above — only the files the diff calls for, an
unchanged object's bytes untouched — with one addition: creating a whole new
AppConfig is out of scope, so a driver the project has no AppConfig for
refuses only its own provisioning changes, not the rest of the update.
Verified the same way as the artifact writer: round-trip equality on a
synthetic project skeleton and, guarded, on `test11`
(`ProvisioningProjectWriterTest`); see `docs/forms.md` §6 for the human spike.

## 4. Creating drivers in the tree — `driver.add`

New drivers are authored in the tree and created in the vault by the deployer
(Phase 4: stopped, start option manual, secrets from the inventory). Phase 3
lacked the tree operation; it lands here:

| form | what it does |
|---|---|
| `driver.add --name N --from-export <export.xml> [--source-driver D]` | a vendor / packaged driver arrives as the configuration Designer exported once (a single-driver export with *include referenced policies*, or a driver-set export with `--source-driver`); the export's driver — artifacts, linkage, filter, GCVs, shim config — is merged into the tree as driver N, its Library artifacts added to the Library when absent and kept when present (matched by name). The transaction validates the result: a driver whose policies read GCVs the target driver set lacks is refused with the missing names — set them first (`gcv.set --define`) |
| `driver.add --name N --copy-of D` | clone driver D (artifacts, links re-pointed to the clone, config blobs), then `gcv.set` / `driver.set` the differences — the dev→test→prod pattern |
| `driver.add --name N --shim-class C [--auth-server S] [--auth-id I]` | a blank driver: empty filter, no policies, no GCVs — for hand-built shims |

Each is a transaction like every other operation (validate → refuse on a new
error → write). A driver that came from an export keeps its package meta, so
the deployer will create it in the vault and the project writer will refuse to
write it into a project (see the table above).

## Build order

0. ✅ **`driver.add`** (three forms) in the edit registry; tests; a from-export
   run on a real driver export.
1. ✅ **Docs generation** (`docs` package; Sonnet) — renderer over the model +
   `ModelDiff`; deterministic; tests on the synthetic set and RFI.
2. ✅ **The skill** (me) — `skill/dirxml-dev/`, installable; walked through once
   end to end on the test vault by an agent session following only the skill.
3. ✅ **`ProjectWriter`** — id minting, CObject writer, relation editing on the
   existing files (DOM-level, preserving Designer's formatting where it
   matters), the change table above; tests against `test11` and a copy of the
   Amica project with the untouched-file invariant.
4. ✅ **Spike 6a with Jerry in Designer** — passed 2026-09-09; findings in
   [spikes/designer-writer.md](spikes/designer-writer.md); scope unchanged.
5. ✅ `idm export-project` CLI, agent guide + skill updated. **Phase 6 closed 2026-09-09.**

Built 2026-09-09 (`source.ProjectWriter`, `source.CObjectXml`, 13 tests on copies
of `test11` and the Amica project; 203 tests green). Proven by hand: on a copy
of `test11`, `policy.add` on Querytest → `export-project` created the CObject and
contents files, changed exactly one existing file (the `Subscriber_`), and
`import-project` of the result equals the tree (`tree.diff`: no differences).
Known limits: `ARTIFACT_KIND_CHANGED` is reported, not written; driver-set-level
GCV linkage (`DRIVERSET_LINKAGE`) is not rewritten because the model does not
record whether a `library/*` GCV object is owned by the `Library_` or the
driver set; a touched CObject file is re-indented in 2-space block style and
embedded XML in attribute values escapes control characters as decimal
references — same content, different bytes than Designer writes. Spike 6a
input: `~/designer_workspace/test11rt` (the copy above; a first copy named test11-roundtrip kept `test11.proj` and Designer reported "No valid .proj file" — the `.proj`/`.cproj` files and their `name`/`cprojectURI` must match the project folder name).

## Decisions (confirmed 2026-09-09)

1. ✅ **Order: docs → skill → writer.** The writer is last because it's the one
   piece whose acceptance test needs a human with Designer.
2. ✅ **New drivers are authored in the tree (`driver.add`) and created in the
   vault by the deployer.** The project writer attempts non-packaged drivers
   (Designer's verdict in spike 6a) and refuses packaged ones, pointing at
   Designer's vault import — the package catalog is Designer's.
3. ✅ **Designer's own verdict decides the writer's scope** (spike 6a); nothing
   is patched around what Designer rejects.
4. ✅ **Documentation is Markdown-first, deterministic, and lives in the client
   repo** next to the tree.
