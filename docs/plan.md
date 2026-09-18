# Plan: fully agent-driven IDM development (Designer-optional)

Status: **plan** (2026-09-08). The next horizon beyond the simulator: let an agent do
the **whole** IDM development loop — read, design, edit, validate, test, diff,
deploy, operate — without Designer in the loop, while still being able to hand a
Designer-compatible project to a human team when they want one.

## Framing: what "eliminate Designer" realistically means

Designer does five jobs. Two we've already replaced or exceeded; three are the work:

| Designer job | Status |
|---|---|
| **Read/model** a driver set (policies, GCVs, filters, schema map, resources, mapping tables, packages) | ✅ done — `DesignerProject` / `DriverExport` / `LdifDriverSource` / live LDAP, plus the `dirxml-designer-workspace` skill |
| **Test** policies (Policy Simulator) | ✅ exceeded — real-engine simulator, regression corpus, compare, coverage |
| **Edit/author** policies and config, keeping the project's cross-references intact | ❌ the core of this plan |
| **Deploy / compare** against the live vault | ❌ read is done; write + diff + safeguards are the work |
| **Operate** drivers (start/stop/restart, migrate, cache, trace, passwords) | ◐ cache read + state done; the rest is wiring ops we already have |

The realistic target is **Designer-*optional***, not Designer-forbidden: the agent
can do everything end-to-end, and the artifacts stay interoperable (import into
Designer from the vault, or — later — a faithful Designer-format writer) for teams
that keep using it. Driver/policy development comes first; **provisioning forms
(the form builder for PRDs) are in scope** as their own track (below); full
workflow-activity design is a later follow-on.

## The four hard problems (the ones you named)

### 1. Communication method — solved by protocol, not by Designer's code

Designer/iManager talk to the vault over **LDAP** (objects and attributes — the
`DirXML-*` classes, `XmlData`, `DirXML-Policies` linkage, `DirXML-ConfigValues`,
`DirXML-DriverFilter`, resources in `DirXML-Data`) plus **DirXML LDAP extended
operations** for engine actions. We already use both:

- LDAP read of the whole driver set (`JndiLdapSearch.readDriverConfig`) and schema.
- Extended ops via `dirxml_misc.jar` + `ldap.jar` (`DxCacheReader`).

`dirxml_misc.jar` carries the **full engine-control surface**: `StartDriver`,
`StopDriver`, `RestartDriver`, `GetDriverState`, `Set/GetDriverSet`,
`InitDriverObject`, `MigrateApp`, `DriverResync`, `SubmitEvent`/`SubmitCommand`,
`Set/Get/ListNamedPassword`, `GetDriverGCV`, `SetDriverStartOption`,
`DeleteCacheEntries`, jobs, activation, key management. So **deploy = LDAP writes
of the config objects + `RestartDriver` (or `InitDriverObject`) to pick them up**,
and operations = the ops above. No Designer code, no licensing entanglement beyond
the IDM jars we already depend on. (Designer's own deploy uses the same objects;
reusing its classes would add coupling and legal risk for no capability gain.)

*Spike to confirm:* LDAP-write a policy's `XmlData`, add a linkage to
`DirXML-Policies`, set a GCV in `DirXML-ConfigValues`, restart the driver, and
verify the engine runs the new policy — against the test vault. Expected to work;
the open detail is which objects require a restart vs are re-read live (mapping
tables have an `UpdateWatcher`; policies are loaded at driver start).

### 2. Safeguards — the simulator makes them *strong*, not just procedural

An agent writing to a production vault needs gates that a human clicking Deploy
doesn't have. Non-negotiable set:

- **Validate before anything**: DTD validation (`dirxmlscript4.10.dtd`,
  `dirxmlfilter.dtd`, `Driver.dtd`, entitlements, jobs — all on disk), XSLT
  compiles, ECMAScript parses, every policy-set linkage resolves, GCV references
  defined, mapping-table references present, schema-map/filter names exist in the
  schema. Most of these the simulator already detects at stage-build; make them a
  first-class `validate` step.
- **Prove before deploy**: run the regression corpus (`test-all` over a `harvest`ed
  baseline) and `compare` old-vs-new — the agent can *demonstrate* a change alters
  exactly the intended cases. This is the safeguard Designer can't offer.
- **Diff and dry-run against the vault**: structured, per-object diff of the model
  vs the live tree; a deploy *plan* you approve before any write.
- **Snapshot + rollback**: export the affected subtree (LDIF) before writing;
  `rollback` restores it. Every deploy is reversible.
- **Environment gating**: STG and PRD are distinct targets; PRD deploy requires an
  explicit confirmation and a green STG run; MCP tools carry destructive
  annotations so the client prompts.
- **Package awareness (overrides are the supported method)**: editing packaged
  content in place is the *normal* customization path — IDM tracks it with a
  **modified/customized flag** on the object (backed by the package baseline:
  `_initial_state.xml` in a project, `DirXML-pkgInitialState`/`DirXML-pkgChecksum`
  in the vault) so package upgrades know what to preserve. The tool must make an
  override **easy** and **set that flag correctly** every time — never a silent
  edit that an upgrade would later clobber. It should also report, on upgrade,
  which customized objects a new package version touches. (Exact flag attribute to
  confirm in the Phase 0 Designer-diff spike.)
- **Least privilege + audit**: a dedicated LDAP identity per environment; an
  append-only log of every write (who/what/when/diff), plus post-deploy re-read
  proving diff = empty.
- **Ground truth**: DxCMD Phase 2 (`SubmitEvent` to the live engine) to confirm the
  deployed policy behaves as the simulator predicted for a canary event.

### 3. Formatting — canonical serialization so diffs and Designer both stay happy

Every write goes through one canonical XML serializer: stable attribute order,
indentation, UTF-8 with declaration, consistent entity escaping, DirXML Script
element order per the DTD. Benefits: clean git diffs, byte-stable round-trips, and
output Designer parses without complaint. `XmlCompare.canonical` is the seed; the
writer is its counterpart.

### 4. References inside the project — an object model, not text edits

Designer's on-disk format is a graph: CObject metadata (`<ID>.<Type>_`) with
`relations` referencing other objects by `#ID.<Type>_`, `_contents.xml` payloads,
package association GUIDs, `_initial_state.xml`, checksums. Adding a policy means
updating the channel's relations, minting an ID Designer's scheme accepts, and
keeping package state coherent. Editing that as text is how you corrupt a project.

The answer is a **typed in-memory model** (driver set → drivers → channels → policy
sets → policies; filters, GCVs, resources, mapping tables, schema map, packages)
with reference-aware operations (`addPolicy(channel, set, order, policy)` updates
the linkage; `renamePolicy` fixes every reference; `deletePolicy` refuses if
referenced). The model loads from **every source we already read** and serializes
to (a) our own on-disk format and (b) the vault. A Designer-format writer is a
third serializer, added once the model is proven — the riskiest piece, so it comes
last, not first.

## Architecture

```
            ┌────────────────────────── sources (all exist) ──────────────────────────┐
            │ Designer project · driver export · LDIF dump · live LDAP (ldapConfig=) │
            └───────────────────────────────┬─────────────────────────────────────────┘
                                            ▼
                               ┌─────────────────────────┐
                               │   IDM model (typed)     │  reference-aware edits
                               │  driverset/driver/…     │  canonical serializer
                               └────┬──────────┬─────────┘
                    ┌───────────────┘          └────────────────┐
                    ▼                                           ▼
        ┌────────────────────┐                         ┌────────────────────┐
        │  IDM-as-code repo  │  git-versioned files    │   Vault deployer   │  LDAP writes +
        │  (source of truth) │  one file per object    │   diff/dry-run/    │  DirXML ext. ops
        └─────────┬──────────┘                         │   snapshot/rollback│  (restart etc.)
                  ▼                                    └─────────┬──────────┘
        ┌────────────────────┐                                   ▼
        │ validate + simulate│  DTDs · linkage · GCVs ·   ┌────────────────┐
        │ (simulator, tests) │  regression corpus         │  live vault(s) │  STG → PRD
        └────────────────────┘                            └────────────────┘

            surfaced to the agent as:  CLI (`bin/idm …`)  +  MCP server (typed tools)
```

- **IDM-as-code** is the source of truth: one readable file per object (policy XML,
  filter, GCVs, mapping tables, schema map, ECMAScript), a manifest for structure
  (channels, policy-set order, packages). Git gives history, review, and branches
  for free; the agent edits files it can read and diff.
- **Designer** becomes an import/export target: import a project into as-code
  (done — the reader), export as-code back to a Designer project (later phase), or
  simply have Designer *import from the vault* after a deploy (works today, no
  writer needed).
- **Two surfaces**: the Java core + CLI (testable, transparent, like `bin/sim`), and
  an **MCP server** wrapping it — typed arguments, schema-validated, destructive
  tools annotated so the client confirms `deploy`/`rollback`/`driver.stop`. Tools
  along the lines of `model.load`, `model.query`, `policy.edit`, `policy.add`,
  `gcv.set`, `validate`, `simulate`, `vault.diff`, `vault.snapshot`,
  `vault.deploy(dryRun)`, `vault.rollback`, `driver.status/start/stop/restart`,
  `driver.submitEvent`.

## Phases

**Phase 0 — Spikes (de-risk the two unknowns, days)** — ✅ **complete** (2026-09-08;
findings in [spikes/](spikes/): LDAP write PASS, engine pickup PASS via trace,
modified state = checksum pair, checksum not reproducible, extended-op API).
1. *LDAP write path*: write `XmlData` on a test policy, add a `DirXML-Policies`
   linkage, set a GCV, `RestartDriver`; confirm the engine runs it. Learn which
   changes need a restart.
2. *Designer write fidelity + the modified flag*: make small changes in Designer
   (add a rule; add a policy; **edit a packaged policy**) and diff the project files
   to learn exactly what it touches (IDs, relations, GUIDs, initial_state,
   checksums) and **which attribute marks a packaged object as modified** — the
   flag our override path must set. Repeat once against the vault
   (`DirXML-pkg*` attributes) so the deployer sets it identically. Decides how hard
   the Designer-format writer is — deliberately **not** on the critical path.
3. *Extended-op inventory*: exercise `Start/Stop/RestartDriver`, `GetDriverState`,
   `SubmitEvent` (DxCMD Phase 2 blueprint) against the test vault.

**Phase 1 — The model + IDM-as-code (foundation)** — ✅ **complete** (2026-09-08;
spec: [model.md](model.md))
- Typed model populated from all four sources; canonical serializer; as-code
  import/export; a manifest. Round-trip tests: source → model → as-code → model
  must be byte-stable.
- Delivered: model, `CanonicalXml`, as-code writer/reader, readers for a
  driver/driver-set **export**, a Designer **project**, an **LDIF** dump and the
  **live vault**; `bin/idm import|import-project|import-ldif|import-live|check`;
  GCV-definition objects modeled so policy-set-14 links resolve. Validated on real
  data from every source (RFI export: 19 drivers; IG4 live: 19; Amica PRD project:
  50 drivers / 1,774 files) — byte-idempotent round trips; the only unresolved
  links are dangling references present in the sources themselves.

**Phase 2 — Validation (offline safeguards)** — ✅ **complete** (2026-09-08;
spec: [validation.md](validation.md))
- `validate`: DTD, XSLT/ECMAScript compile, linkage integrity, GCV/mapping-table
  references, schema-map/filter vs schema, package-discipline check. Wire the
  simulator's existing diagnostics into it; `--json` output.
- Done: `bin/idm validate <dir> [--json]`; well-formedness pre-pass; links;
  **compile through the engine's own compilers** in the driver's context (GCVs
  substituted into the policy text as the engine does at load — an undefined
  `~gcv~` is fatal at driver start; mapping tables and `<include>`s resolvable);
  GCV token references; mapping-table reach and columns. Calibrated: RFI export
  and IG4 live vault validate with 0 errors; Amica PRD errors all trace to the
  project itself. Simulator 1.5.2 carries the engine-fidelity fixes this needed
  (`~gcv~` substitution, GCV precedence, includes).
- Also done: ECMAScript (Rhino — the engine's own — parse + `es:` call
  resolution against the driver's set-3 resources) and filter / schema-map
  checks. 76 tests. Moved to later phases: schema-map/filter vs the *vault*
  schema (needs a live schema read — Phase 4), package-discipline (needs the
  edit operations' baseline — Phase 3).

**Phase 3 — Edit operations (CLI)** — ✅ **complete** (2026-09-08;
[edit-operations.md](edit-operations.md), [agent-guide.md](agent-guide.md))
- Reference-aware operations on the model, each validated; dry-run everywhere.
  Simulation as a gate (`simulate` = `test-all` + `compare`). **CLI only** by
  decision — one operation registry; an MCP adapter only if a client ever
  needs one.
- Delivered: content edits are file edits (+ `validate`); operations for
  structure — `policy.*`, `resource.*`, `artifact.*`, `rule.*`, `gcv.*`,
  `filter.*`, `schema-map.*`, `driver.set`, `mapping-table.*` — as transactions
  (load → apply → validate → refuse on a *new* error → write only changed
  files); packaged artifacts keep a `.package-baseline/` snapshot and are marked
  `customized` on first edit; `ExportWriter` (driver-set form for Designer
  import and the Phase 4 diff; single-driver form for the simulator);
  `idm simulate <tree> --cases <dir> [--against <tree>]`; read commands
  (`show`, `query`, `refs`, `package.diff`). 108 tests; exercised on the real
  RFI and JFW trees.

**Phase 4 — Vault deploy with safeguards** — ✅ **built and proven on the test
vault** (2026-09-08; `--delete-driver` implemented 2026-09-16;
[vault-deploy.md](vault-deploy.md) records what landed and what is
deliberately deferred: the Remote Loader password, `simulate` inside the
production gate)
- Structured `vault.diff`; deploy plan; LDIF snapshot + `rollback`; LDAP writes +
  `RestartDriver`; environment gating; audit log; post-deploy verification
  (re-read → diff empty; optional canary `SubmitEvent` vs simulator prediction).
- Design: a pure `ModelDiff` (also `tree.diff`) drives a printed, ordered plan
  (Library → driver → channel objects → attributes/linkage → driver set →
  restarts); snapshot of every touched object + driver state before any write;
  writes stop at the first failure; verify = re-read → diff empty + drivers
  running; audit line per deploy; environments with tiers, `--confirm` for
  PRD, and PRD requiring a green STG deploy of the same commit. Deploy never
  deletes a driver; new drivers are created stopped. Deploys run step by step (diff → confirm → write → verify per change) or
  automated after a backup and one confirmation; a production change must
  start from a state the repo knows (no drift, or `--capture-drift` first).
  Secrets the tree can't carry (shim / Remote Loader / named passwords)
  come from a gitignored per-environment source, are required on a driver's
  initial deploy and only re-set when forced. All five decisions confirmed.

**Phase 5 — Operate** — ✅ **built and proven on the test vault** (2026-09-09;
[operate.md](operate.md))
- Driver lifecycle, cache view/clear, migrate/resync, named passwords, trace
  level, jobs — via the existing extended ops. (Much of this is `DxCacheReader`
  generalized.)
- Design: `driverset.status` / `driver.status|start|stop|restart|cache view|clear|
  migrate|resync|secrets|trace show|set|reset|tail` / `engine.version|stats`
  behind the deploy's environments, tiers and audit log; gating by what an
  operation can break; a cleared cache is saved first; trace tail over the
  environment's SSH host, and start/restart verified from the driver's trace;
  `driver.submit` only if spike 5c shows the engine runs a submitted event.
  The DxCMD Phase 2 canary is real: `driver.submit --tree` compares what the
  live engine handed the shim with the simulator's prediction (MATCH on the
  test vault). Decisions confirmed.

**Phase 6 — Designer round-trip + workflows** — ✅ **complete** (2026-09-09;
[designer-roundtrip.md](designer-roundtrip.md)): `idm docs`, the `dirxml-dev`
skill, `driver.add`, `export-project` (spike 6a: Designer opened a
writer-updated project cleanly — [spikes/designer-writer.md](spikes/designer-writer.md)).
- Designer-format writer (from the Phase 0 findings) for teams that need it.
- Agent workflows/skills: "implement requirement X" → edit → validate → simulate →
  diff → deploy STG → verify → promote PRD, with package-aware overrides and docs
  generation from the model.
- Design: docs generation from the model first (`idm docs`), then the agent
  workflow skill (the loop, the rules, the recipes), then the Designer writer as
  an *update of an existing project* (content, new/removed/renamed artifacts,
  linkage, driver config; non-packaged new drivers attempted, packaged ones
  refused — Designer's package catalog) verified by round trip, an
  untouched-file invariant, and a human-in-the-loop spike in Designer; plus
  `driver.add` (from an export, a copy, or blank) so new drivers are authored
  in the tree and created by the deployer. Decisions confirmed.

**Phase 7 — Packages: our own package management** — ✅ **complete** (2026-09-10;
[packages.md](packages.md); Designer's verdict in
[spikes/designer-package-acceptance.md](spikes/designer-package-acceptance.md):
a package we built imports from our site, a driver we installed and deployed
shows as packaged with nothing modified, and our package installs in Designer)
Jerry's decision: no Designer at runtime; package definitions fetched from
the update site (live at `https://nu.novell.com/designer/packages/idm/updatesite{1,2}_0_0/`)
and kept in a git catalog (jars + a diffable unpacked form, renderable as an
update site for Designer users); `package.fetch|import|list|show|diff|resolve`,
`package.install` / `driver.add --base` as tree transactions (prompts as XSLT,
Designer's weight rule, filter-ext merge, stamps, installed record in the
manifest), `package.upgrade|downgrade|uninstall` with customizations kept
(Designer's own model: no merge, baseline moves), `package.status|adopt`,
deployer writes every `DirXML-pkg*` attribute, and **`package.build`** — a
package from a tree's customized configuration (Jerry, 2026-09-09) — plus
`package.site`. Checksums verified by recomputation against Designer's
catalog ([spikes/designer-package-layer.md](spikes/designer-package-layer.md));
**build steps 1 and 3 done 2026-09-10: `packages.PackageChecksum` reproduces the
whole catalog ([spikes/package-checksums.md](spikes/package-checksums.md));
`package.install` / `driver.add --packages` reproduce Designer's install
([spikes/package-install-parity.md](spikes/package-install-parity.md));
step 2 (catalog: fetch/import/list/show/diff/resolve) merged; step 4
(vault stamps, proven live: [spikes/package-vault-stamps.md](spikes/package-vault-stamps.md))
done; `package.status|adopt`, `package.build` (hand-made artifacts + the GCVs they
read → a Designer-valid jar), `package.site` done 2026-09-10; upgrade/downgrade/
uninstall merged; every build step done — 7c with Jerry is the acceptance test;**
vault/jar/site facts in [spikes/package-format.md](spikes/package-format.md).
The PDT analysis ([spikes/pdt-analysis.md](spikes/pdt-analysis.md)) and the
headless spike stay as reference; the headless route is not pursued.

**Track P — Provisioning forms (the form builder)** — ✅ **built and proven**
(2026-09-11): design note [forms.md](forms.md) (JSON forms only; A = vendor
builder launcher `form.edit`, B = typed operations + `FormCheck` + `prd.map`/
`prd.add`, C = `form.preview`; deploy through the normal path); findings in
[spikes/json-forms-format.md](spikes/json-forms-format.md), live proof in
[spikes/forms-deploy-live.md](spikes/forms-deploy-live.md) (idm254: untouched
vault diffs empty, scratch form + PRD add/modify/delete verified, PRD picked up
by the Identity Applications with no cache flush and its form served like a
stock one); Designer acceptance of the project writer passed 2026-09-13
(`test11pf` imported, the written form opens in the vendor builder —
[spikes/designer-writer.md](spikes/designer-writer.md) spike 6b).

**Track W — Workflow design (the PRD's `<process>`)** — ✅ **COMPLETE 2026-09-16: W1–W5 and W4b shipped** (flow model, engine-faithful `FlowCheck`, `prd.flow`, twelve
`flow.*` operations, live proof on idm254 in [spikes/workflow-live.md](spikes/workflow-live.md);
Designer accepted the authored PRD); **W4b model shipped 2026-09-15**
(entitlements as-code — model, live/LDIF/project readers, as-code + project
writers, diff/deploy, `entitlement.*` operations, `EntitlementCheck` +
`FlowCheck`'s two new codes; 505 tests green, 11 skipped — see
[entitlements.md](entitlements.md) §2); the Loopback driver + live grant
proof on idm254 (§3) and the Designer acceptance check (§4) are pending. W3
integration activities remain. Design note
written 2026-09-13 ([workflows.md](workflows.md): engine grammar and the
engine's ten pickup checks recovered from `workflow.jar`, Designer needs no
layout data, `design-params` is legacy; options A/B/C, recommended B = typed
flow operations on the vault XML + an engine-faithful `FlowCheck` +
`prd.flow` view; build order W1–W5; decisions pending). Roles and resources
are out of scope: they are managed in the Identity Applications.
Runs alongside Phases 3–4 once the model exists; it is a distinct object model
(`Model/Provisioning/` in a project; `srvprv*` objects under the User Application
driver's AppConfig in the vault) so it gets its own reader/writer.
- **P1 Read/model**: provisioning request definitions (PRDs) and their **request /
  approval forms** — form XML (fields, widgets, data items, validation, ECMAScript
  event handlers, localized labels) — loaded from a project and from the vault
  (the `dirxml-designer-workspace` skill already maps the project side).
- **P2 Edit**: typed form operations (add/remove/reorder fields, set widget type
  and data binding, attach events/validation, localization) with a schema check
  against the form DTD/XSD and the PRD's data items; canonical serialization.
- **P3 Validate/preview**: static checks (every field bound to a data item, events
  parse, required/visibility rules consistent) and a rendered **preview** so the
  agent and a human can see the form before deploying.
- **P4 Deploy**: write the PRD/form objects to the vault (LDAP) and trigger the User
  Application's refresh, with the same diff/snapshot/rollback/gating as drivers.
- Later: workflow activities/flow design and roles/resources modeling.

## What it takes

- **Reuse**: the reader stack, simulator, regression tooling, live-LDAP client,
  extended-op plumbing, canonical compare — roughly the read and test halves are
  done, which is the larger share of the risk.
- **New**: the typed model + serializer (the heart), validation as a product,
  reference-aware edit ops, the vault deployer with snapshot/rollback/diff, the MCP
  surface, and — last — the Designer writer.
- **Sequencing rule**: nothing writes to a vault until validate + simulate + diff +
  snapshot exist; nothing writes Designer format until the model is proven via the
  as-code + vault path. This keeps every phase shippable and safe on its own.

## Decisions (made 2026-09-08)

1. **Source of truth: IDM-as-code.** Git-native, agent-native; Designer is an
   import/export target, with the Designer-format writer in Phase 6.
2. **A new repo/product** that depends on the simulator as a library; the
   simulator stays the test engine it is.
3. **Scope:** driver/policy development first, **plus provisioning forms (the form
   builder) as Track P**; full workflow-activity design is a later follow-on.
4. **Package overrides are the supported customization method** — make them easy
   and always set the modified/customized flag correctly; never refuse by default.

## Proposed next track (design note written, awaiting decisions)

- **A fresh Designer project from a tree** — `export-project --new`:
  skeleton, drivers, the UA driver's `AppConfig` with forms/PRDs/entitlements,
  and the packaged drivers' `IdmPackage_` catalog entries from the git
  catalog, so a team gets a complete project without Designer reading the
  vault → [designer-new-project.md](designer-new-project.md) (2026-09-17;
  decisions in its §5).

## Follow-ups (small, not scheduled)

- ~~**Two package-stamp vocabularies, and the deploy side reads only one**~~ — **done
  2026-09-18** (`docs/designer-new-project.md` §7.2f, `docs/model.md` "Package stamps"):
  the tree's vocabulary is the vault's, every reader writes it, `PackageStamps` reads
  either. Kept for the record (found 2026-09-18 while verifying driver icons, §7.2e).
  A tree from the vault carries `dirxml-pkgguid` (`id;symbolicName;version;name;short`),
  `dirxml-pkgassociationid`, `dirxml-pkgchecksum`, `dirxml-pkglinkages`; a tree
  from a Designer project or an export carries `package-id`, `pkg-assoc-id`,
  `checksum`. The edit layer accepts both (`Packages.isPackaged`), but
  `VaultMapping.packageAttributes` and `ModelDiff`'s `STAMP_KEYS` know only the
  vault's. Consequences: `vault.diff` of a project-imported tree (test11pf vs ig4)
  reports "package stamps changed → (none)" on every packaged item, and a deploy
  from such a tree would create packaged artifacts **unstamped** (Designer would
  then see them as unpackaged). Nothing is lost on the way — `import-live` →
  `export-project --new` → `import-project` re-keys 218 items into the project
  vocabulary, which is why that round trip shows 221 stamp "changes" against the
  live tree. Fix: one normalization (project keys + the tree's package catalog →
  the vault's full `dirxml-pkgguid`) used by both the diff and the mapping.
- **EntitlementConfiguration resource** for hand-built drivers with
  entitlements (the applications warn without it; the role/resource catalog
  needs it). Jerry (2026-09-16): it is usually built by hand in Designer,
  and some drivers ship startup policies that create or update it. So the
  tool needs both: `entitlement.add`/`set` maintain the resource for a
  hand-built driver, and a packaged driver's startup policy is left to do
  its own (never overwritten by ours). Priority: low — the applications read
  it only when entitlement binding (roles/resources) is configured, which
  is outside the tool's scope today; a workflow's grant works without it.
- ~~**Start a PRD over REST**~~ — done 2026-09-16: `POST
  /requests/permissions/v2` (the JSON form renderer's call), scripted in
  `bin/apps` with tasks/approve/history; reference with required fields and
  accepted values in [idapps-rest.md](idapps-rest.md), proof in
  [spikes/prd-rest-live.md](spikes/prd-rest-live.md). Open check: whether
  `POST /index/permissions ADD_OR_MODIFY` makes a just-deployed PRD
  requestable before the index's 10-minute interval.
- **`bin/idm query <tree> fishbone <driver> --json`** — the JSON the VS Code /
  Cursor fishbone viewer (`extensions/dirxmldev-visual/`, PR #1) builds itself from `driver.xml`; serving it from the CLI keeps
  one reader of the manifest and lets the viewer follow model changes
  (`docs/vscode-extension-v1.md` proposes it).
- **`package.fetch` refuses every download with `REFUSED <short>_<ver>: null`**
  (2026-09-17, both update sites, `--dry-run` lists the versions fine, `curl`
  downloads the same URL): the download step throws with a null message that
  the CLI swallows. Print the exception class, and find the cause (Java HTTPS
  from this Mac, or the site's redirect). Workaround: `curl` the jar and
  `package.import` it.
- **`bin/idm` usage omits `vault.*`, `driver.submit` and `driver.trace tail`**
  (they run, and each prints its own usage when called wrong); add them to
  the top-level listing. Found 2026-09-16 while writing the walkthrough.
- **`form.field.add --json '{…}'`** is swallowed by the global `--json`
  output flag ("unexpected argument"); rename the field's extra-properties
  flag (e.g. `--props`) and keep `--json` for output. Found 2026-09-16.
- **A live run of a W3 integration activity** (REST, role, resource,
  entity) — the grammar is decompiled from `workflow.jar`'s binding classes
  and checked, but never executed on a lab; needs a REST endpoint or a role
  on idm254.
- **Entitlements in Designer's export format** (`ExportReader`/`ExportWriter`)
  if a "Export to Configuration File" turns out to carry them — none seen so far.
- ~~`PkgTest7` on ig4~~ — removed 2026-09-16 with `vault.deploy --delete-driver`
  (see the incident in [vault-deploy.md](vault-deploy.md)).

## Non-goals (for now)

- Reusing Designer's Java code or UI.
- Workflow-activity/flow design and roles/resources modeling (after Track P).
- Native-shim / Remote Loader *installation* (RL config attributes are LDAP and in
  scope; the OS-level install isn't).
