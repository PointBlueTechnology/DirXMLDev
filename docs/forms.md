# Track P — Provisioning forms (design note, 2026-09-11)

Scope set by Jerry (2026-09-11): **JSON forms only** (IDM 4.8+ Form.io forms;
classic XForms PRDs are read but never edited), and we may let people use the
existing form builder — explore the options. Evidence:
[spikes/json-forms-format.md](spikes/json-forms-format.md).

## 1. What a form is, in our terms

A JSON form is one document (`srvprvJSONData`) that Designer, the vault and
the Identity Applications all pass around byte-for-byte. It is a Form.io
definition plus NetIQ's custom component types, an `inlinescripts` string,
`externalScripts`, and a `localization` map. It is referenced **by name** from
a PRD (`form-binding form-id`), and the PRD keeps a *copy* of the form's field
list and derives its flowdata mappings from it. So "editing a form" has two
halves: the JSON document, and keeping the PRD binding consistent — Designer
does the second half silently when a form is saved.

## 2. The three ways to edit, and what we do with each

### Option A — the vendor form builder as an external editor (human path)

Designer itself only does `FormBuilder --filepath=<file> --locale=… [--service=…]`
and reads the file back. We can do exactly that from the tree:

```
idm form.edit <tree> <form>            launches the builder on the tree's copy; on return validates + syncs PRD bindings
```

- Builder location: `IDM_FORMBUILDER` env / `formbuilder=` in the tree's
  `idm.properties`, defaulting to the Designer plugin path on this OS
  (`com.mf.{mac.cocoa,win.win32,linux.gtk}.formbuilder_*/lib/…`). No
  Designer *runtime* is involved, only its installed files; a standalone copy
  of `FormBuilder.app` works the same.
- `--service` gets a `ServiceRegistry.json` we generate from
  `environments.properties` (`<env>.formsUrl`) when the user asks for online
  features; offline otherwise.
- One-time Gatekeeper fix per install (`xattr -dr com.apple.quarantine`,
  `chmod -R a+x`) — documented, printed by the command when the launch fails
  with "cannot be verified"; we do not do it silently.
- Pros: pixel-identical to what Designer users get; zero UI work; keeps the
  vendor's custom components and their property panels. Cons: a GUI step, so
  not agent-driven; needs a Designer install or the extracted app.

**Verdict: build it — it is a thin launcher (a day) and the answer to "let
people keep the builder".**

#### What is required to have the builder available (all three platforms)

The builder is OpenText's, shipped only as Designer plugins; we cannot
redistribute it. Each workstation needs one of:

1. **Designer 4.8.x or later installed** (the plugin is part of every
   install), or
2. **a copy of the plugin's `lib` directory** taken from that user's own
   Designer install (self-contained; ~1 GB), placed anywhere and named by
   `IDM_FORMBUILDER` or `formbuilder=` in `idm.properties`.

The launch contract is the same everywhere — Designer's own code
(`FormCreateWizard`) passes `--filepath=<file> --locale=<lang>
[--service=<ServiceRegistry.json>]` on all platforms; only the executable
path and one OS quirk each differ:

| OS | Plugin dir under `<Designer>/plugins/` | Executable | Quirk |
|---|---|---|---|
| macOS | `com.mf.mac.cocoa.formbuilder_<ver>/lib/` | `FormBuilder.app/Contents/MacOS/FormBuilder` | Unsigned + quarantined by Gatekeeper: once per install `xattr -dr com.apple.quarantine FormBuilder.app` and `chmod -R a+x FormBuilder.app` (Designer sets the execute bits itself but cannot clear quarantine; Jerry hit "cannot be verified"). **Intel-only binary**: every Mach-O in the bundle (FormBuilder, Electron Framework 23.0.0 / Chrome 110, the helpers) is `x86_64`, not universal, so on Apple silicon it runs under Rosetta 2 — macOS 27 shows the Rosetta deprecation warning on launch (Designer itself and its bundled JRE are `x86_64` too). The `app.asar` payload is pure JS (no native `.node` modules), so a native-arch Electron 23 runtime hosts it unchanged: Jerry's DesignerModernPlatform project (`phase1/swap-formbuilder.sh`, branch `formbuilder-arm64`) rebuilds the bundle in place on the official arm64 Electron 23.0.0 — same plugin path, so `form.edit` needs no change. |
| Windows | `com.mf.win.win32.formbuilder_<ver>/lib/` | `FormBuilder.exe` | Unsigned: SmartScreen may show "Windows protected your PC" on first run → *More info → Run anyway* (no admin rights needed). |
| Linux | `com.mf.linux.gtk.formbuilder_<ver>/lib/` | `formbuilder` | Needs execute bits (`chmod -R a+x lib/`) and `--no-sandbox` (Designer adds it; Electron refuses to run as root without it). |

Default Designer locations we search: macOS `/Applications/Designer`,
Windows `C:\netiq\idm\apps\Designer` (and `%USERPROFILE%\designer`), Linux
`/opt/netiq/idm/apps/Designer` and `~/designer`; the newest plugin version
wins. `idm form.edit --check` (also run automatically before a launch)
reports which executable will be used, whether it is executable/quarantined,
and prints the one-time fix commands — it never runs them.

Optional, for online features (entity lookups, preview against a live
workflow engine): a `ServiceRegistry.json` with
`{"FormsBackendUrl": "https://<identity apps host>:<port>/WFHandler"}`;
we generate it from `<env>.formsUrl` in `environments.properties` when
`--env` is given, otherwise the builder runs offline (its toggle shows
"Offline"). Locale comes from `--locale` or the JVM default (`en_US` → `en`).

### Option B — typed form operations (agent path, the core)

The agent needs to author and change forms without a screen. A typed model of
the document with reference-aware operations, exactly like policies:

```
form.add       <tree> --kind request|approval|template --name N [--from <form>|--template blank|request|approval]
form.show      <tree> <form>                  outline: fields (key, type, label, required, hidden, conditional), scripts, languages
form.field.add <tree> <form> --key k --type textfield|textarea|select|checkbox|radio|datetime|button|dn_display|dynamic_entity|… [--label --required --after <key> --in <panel/column>] [--json '<extra props>']
form.field.set / .remove / .move            change any property, delete, reorder/reparent
form.set       <tree> <form> --title|--display|--inline-script <file>|--external-script <url>
form.localize  <tree> <form> --lang fr --set "Title=Titre" …   (or --sync: top up every language with everything any language or the components declare)
form.rename    <tree> <form> --to N          rewrites every PRD binding that references it
form.delete    <tree> <form>                 refused while a PRD binds it (--force)
```

- Components are created with the builder's defaults for that type (captured
  from the stock forms as templates in `resources/forms/components/<type>.json`),
  so a form we author round-trips through the vendor builder unchanged.
- **PRD binding sync** is part of every form transaction (`forms.BindingSync`,
  built 2026-09-11 from Designer's decompiled PRD editor and calibrated so that
  syncing an untouched stock form is a no-op on 4.8.7, 4.10.1 and the test11
  project — 9 of 11 stock forms exact; the other two are stale stock bindings
  Designer's own code would rewrite the same way): the request form's
  `<form-binding><content>` field list is rebuilt from every component that has
  a `key` and a `type` in Designer's `FormDataConfig.json` map (`data-type`
  from the map, `control-type` = the type, `apwaComment` skipped, already-bound
  buttons kept); approval-activity bindings are bare references and are left
  alone; data items (`request-data-items`, activity `<data-items>`) are the
  *persisted mappings* — kept while their field exists (`target-type` refreshed
  from `multiple`), removed when it vanishes, never invented. Mapping a new
  field to flowdata is an explicit operation (`prd.map`, step P2b). Changes are
  reported as notes; packaged PRDs touched this way are marked customized and
  baselined. This is the only PRD write in Track P besides `prd.add`.
- Validation (a `FormCheck` in the existing validator): JSON well-formed;
  unique keys; every input has a key and a type the renderer knows; buttons
  present (submit/cancel) on request forms; `conditional`/`logic` refer to
  existing keys; `inlinescripts` and `externalScripts` parse (Rhino, as for
  ECMAScript resources); localization has every label for every declared
  language (warning); every PRD binding matches its form (error); custom
  types carry the properties the renderer needs (`dn_display`/`dynamic_entity`
  entity + attributes).

**Verdict: build it — this is the deliverable that makes forms agent-driven.**

### Option C — preview without the Identity Applications

The vendor renderer needs the workflow engine (Tomcat, down on the test box).
Cheapest honest preview: `idm form.preview <tree> <form> --out page.html`
writes a self-contained page using the open-source Form.io renderer
(`formiojs`, MIT, pinned version vendored into `resources/`) with small stubs
for the NetIQ custom types (rendered as labelled placeholders). Good enough for
"does it look like a form, are the fields in the right place, does the
conditional hide what it should"; not a substitute for a test on the real
stack. The agent can screenshot it headlessly later if we want a visual gate.

**Verdict: build after A and B; keep the stubs explicit so nobody mistakes it
for the vendor renderer.**

## 3. Tree layout and model

Forms and PRDs hang off the User Application driver, under its `AppConfig`:

```
drivers/<UA driver>/provisioning/
  forms/request/<name>.form.json
  forms/approval/<name>.form.json
  forms/template/<name>.form.json
  prds/<name>/definition.xml        XmlData          (prov-req-defn, without <provision-request>/<process>)
  prds/<name>/request.xml           srvprvRequestXML
  prds/<name>/process.xml           srvprvProcessXML
  prds/<name>/prd.properties        cn, status, flow strategy, grant/revoke, category, process type, localized names/descrs
  provisioning.xml                  manifest: objects, package stamps (guid/assoc/checksum), customized marks, baselines
```

- Form files are stored **pretty-printed** (2-space, key order preserved) so
  git diffs are readable; the deployer and the Designer writer emit the
  compact single-line form the vendor tools produce. `vault.diff` compares
  forms as parsed JSON, never as bytes.
- The model gains `Provisioning` (per driver) with `Form` (kind, name,
  document) and `Prd` (the three XML parts + properties + the bindings it
  declares, resolved to `Form` references). Readers: live vault, LDIF, Designer
  project (`.formRequest`/`.prd` + digests); writers: as-code, export,
  Designer project (files + digest items, same id-minting as Phase 6), vault.
- Packaged stock forms/PRDs keep their `DirXML-pkg*` stamps; editing a
  packaged form goes through the same baseline/customized mechanism as a
  packaged policy (`.package-baseline/`, `DirXML-pkgChecksum` recomputed as
  CRC32 of the bytes we write, which is what the vault compares).

## 4. Deploy

Same plan/deployer/snapshot/rollback/gating as drivers, with three new object
kinds: `srvprvJSONForm` (`srvprvJSONData` bytes), `srvprvRequest` (its
attributes; `srvprvLocalizedNames`/`Descrs` in the `lang~text|…` form) and the
`srvprvJSONForms` containers. Order: forms before PRDs; a PRD that binds a
form the vault lacks is refused. **Open question for a spike:** whether the
Identity Applications/workflow engine pick up a changed form or PRD without a
cache flush or restart, and what Designer triggers on deploy — needs Tomcat up
on `idm-ig4`. Until measured, `vault.deploy` prints "restart or flush the
Identity Applications cache" after touching provisioning objects.

## 5. Safeguards specific to forms

- Never edit a form in place under a PRD binding without syncing the binding;
  **`form.delete` refuses a bound form outright — `--force` never overrides
  that** (P2b's actual spec, correcting this line's original "never delete a
  bound form without `--force`": `form.delete` never removes bindings, so
  deleting a bound form would leave a dangling reference no matter what).
  `--force` only overrides the *packaged* caution (deleting one of the 11
  stock forms when it's unbound).
- Stock forms (the 11 in the base package) are customizable like any packaged
  artifact — marked, baselined, never refused (except the bound-form case
  above, which isn't about packaging at all).
- Scripts inside forms are code: they go through the ECMAScript check and are
  shown in `form.show`; `form.set --inline-script` takes a file so the agent
  reviews it as a file. **Form scripts compile against `Context.VERSION_ES6`
  (Rhino), not the engine's default language version** — unlike driver policy
  ECMAScript, a form's `inlinescripts`/`calculateValue`/button `custom`/etc.
  run in the Identity Applications forms renderer's browser, and the real
  stock "Help-desk Request Form" submit button's script uses `let`, which the
  engine's default Rhino version rejects but `VERSION_ES6` accepts (found
  calibrating `FormCheck` against the test vault, 2026-09-11).
- Nothing here touches workflow activities beyond the binding/data-item lines
  that reference the form; workflow design stays out of scope.
- **Designer tolerates a duplicate key across non-bindable (layout) components**
  (`column`, `columns`, `panel`) — the stock forms have several — and inside
  `display: "workflowWizard"` forms ("Create Workflow Form"), whose per-activity
  builder panels intentionally repeat the same field keys; neither is a real
  ambiguity since layout types are never bound and a workflowWizard form is
  never referenced by a PRD's `form-binding`. `FormCheck`'s `form-duplicate-key`
  only fires when a shared key includes a genuinely bindable type.
- **Classic (non-`formSrc="1"`) PRDs can carry `form-binding`-shaped elements
  that don't resolve to any real JSON form** (several `_TA`/`_TD` template PRDs
  on the test vault do) — vestigial, out of scope, and not a `prd-binding-stale`
  finding; `FormCheck` skips a PRD's bindings entirely when `isJsonForms()` is
  false.
- **A handful of stock PRDs' request-binding field order doesn't match what a
  fresh `form.sync` would produce** — same fields, different order (HelpdeskTicket,
  Resource Approval, Resource Provisioning, Role Approval, SoD Conflict
  Approval, Template2SerialApproval_JSONFORMS, Template5ParallelApproval_JSONFORMS
  on the test vault) — a real, harmless `prd-binding-fields-drift` warning
  beyond the two originally documented in §2, not a bug in the check.

## 6. Build order

1. ✅ **P1 read/model** (2026-09-11) — `Provisioning`/`Form`/`Prd`/`FormDocument`
   model; LDIF/live + project readers; as-code writer/reader; `form.list|show`,
   `prd.list|show`; round trips byte-exact on test11 and the two vault dumps.
2. ✅ **P2a form.edit** (2026-09-11) — `forms.FormBuilderLocator` (per-OS
   discovery, checks, fix commands) + `FormBuilderRunner` + `form.edit`,
   `form.set-content`, `form.sync` (`edit.FormOps`) with `forms.BindingSync`;
   verified on this Mac against the real builder and on the stock forms.
3. ✅ **P2b typed ops** (2026-09-11) — the 11 Option B commands (`forms.FormEditor`
   + `edit.FormOps`) and `validate.FormCheck` (12 codes); calibrated to 0
   FormCheck errors on test11 and both vault dumps (`FormOpsGuardedTest`),
   with the two known stock exceptions staying `prd-binding-fields-drift`
   warnings. The vendor-builder round-trip parity test is not done — the
   builder can't be driven from a test (see `docs/forms.md` §2 Option A); the
   round-trip guarantee for now is `form.field.add`'s captured templates plus
   `Json.compact` idempotence (`FormOpsGuardedTest`), not an actual builder run.
4. ◐ **P4 deploy** (2026-09-11) — forms/PRDs in `ModelDiff`/`VaultMapping`/
   `Plan`/`Deployer` (`ENSURE_CONTAINER` step, compact JSON on the wire, PRD
   attributes, stamps + content checksum for customized objects, no driver
   restart); **live on idm254: untouched vault diffs empty; scratch form
   add/modify/delete verified; a scratch PRD from `prd.add` picked up by the
   Identity Applications with no cache flush, its form served exactly like a
   stock one** ([spikes/forms-deploy-live.md](spikes/forms-deploy-live.md)). ✅
5. ✅ **P3 preview** (2026-09-11) — `form.preview` (`forms.FormPreview`):
   self-contained page with the vendored open-source Form.io renderer 4.21.7
   (MIT) and placeholder components for the NetIQ types; live data sources
   neutralized; verified on the stock Help-desk request form.
6. ✅ **Designer writer/reader parity** (2026-09-11) — `ProjectWriter.update`
   carries a driver's forms/PRDs into an existing project's
   `Model/Provisioning/<AppConfig dir>/`: a form is written as the vendor
   builder's own compact document plus a minted `<name>.digest` item (no
   `dirguid`/`dirrev`/package elements unless copied from packaged meta); a
   PRD is the union `.prd` (`<provision-request>` re-inserted right before
   `<process>`, matching Designer's own layout) plus a `<name>.digest` with
   localized display/descr, category key and one `digest-dependency` per form
   binding; container digests (`WorkflowForms.digest`, `RequestDefs.digest`,
   …) hold only their own description on every project sampled, so adding or
   removing an item never touches them. Unchanged forms/PRDs are never
   rewritten (bytes untouched); a driver whose project has no AppConfig
   refuses only its own provisioning, not the whole update. Verified on a
   synthetic project skeleton and, guarded, on a copy of `test11`: `form.add`
   + `prd.add` → `export-project` created exactly the new form + digest and
   the new PRD + digest (nothing else touched), and `import-project` of the
   result was byte-for-byte identical to the tree that produced it
   (`tree.diff`: no differences) — confirmed both via the JUnit suite
   (`ProvisioningProjectWriterTest`) and by hand with the `idm` CLI end to
   end. Docs and skill recipes updated (`docs/designer-roundtrip.md`,
   `.claude/skills/dirxml-dev/reference/commands.md`). **Human spike passed
   (2026-09-13):** Designer imported the round-tripped copy of `test11` and
   opened the written form in its builder — see §"human spike" below.

### Human spike (step 6) — PASSED 2026-09-13

Setup: `~/designer_workspace/test11pf`, a copy of `test11` that
`export-project` updated from `~/IdeaProjects/DirXMLDev-e2e/tree-test11pf`
(`form.add` of "DirXMLDev Writer Test" from "Request Form" + a required
textarea `justification` + `form.localize --sync`; `prd.add` of "DirXMLDev
Writer PRD" from `NoApproval` with `justification` mapped). Exactly four files
differ from `test11`: the form, the PRD and their two digests.

Result (Jerry, Designer on the Mac): the project imported from the
file system, every view is populated, the new request form appears under the
User Application driver's Provisioning → Workflow Forms → Request Forms and
**opens in the vendor form builder**. The writer's provisioning output is
therefore Designer-valid as designed; nothing in the writer needed changing.
Not separately reported: the PRD's Active/binding view and the Deploy offer
(the import itself and the form opening were the checks that could fail on
the writer's output; the PRD file is the vendor union layout byte-for-byte).
Details in [spikes/designer-writer.md](spikes/designer-writer.md) (spike 6b).

Lesson (not about the writer): the first import showed no System Model, a
blank developer view and no provisioning items because the copy had only its
`.proj`/`.cproj`/`.project` **file names** changed. Their **contents** must
carry the folder name too — `.proj`'s `cprojectURI`, `name` and
`adapterProject href`, the `.cproj`'s project name and `.project`'s `<name>`.
Deleting the broken import from the workspace (keeping the contents) and
importing again after the rewrite fixed it.

## 7. Decisions for Jerry

1. **A + B + C in that order** as above? (Recommended: yes; A first because it
   is small and immediately useful, B is the substance.)
2. **PRD scope = binding sync + whole-object deploy only** (no PRD authoring,
   no workflow edits). A `prd.add --from-template NoApproval|SingleStepApproval
   --request-form X [--approval-form Y] [--map-all]` is the one authoring
   command worth considering — cheap because the templates are in every vault;
   `--map-all` maps every bindable field of the bound form(s), sparing the
   per-field `prd.map` calls a template with no mappings (e.g. `NoApproval`)
   otherwise needs; in or out?
3. **Pretty-printed forms in the tree, compact on the wire** (semantic diffs)
   — or byte-preserving like policies? (Recommended: pretty.)
4. **Runtime pickup spike needs the Identity Applications up on `idm-ig4`.**
   Tomcat is stopped there; do you want to start it, or should I (the
   `netiq-tomcat` service) when the spike is due?
5. Gatekeeper fix for `FormBuilder.app`: run once by hand (the command is in
   the spike note) — the tool prints it, never runs it.

## 8. Decisions (Jerry, 2026-09-11)

1. **A → B → C in that order.** Confirmed.
2. **PRD scope = binding sync + whole-object deploy, plus `prd.add --from-template`.** Confirmed (in).
3. **Storage format** — explained, default = pretty in the tree, compact on the wire:
   - *Byte-preserving* (like policies): the tree holds the vault's exact bytes,
     one line of 10–200 KB per form. `vault.diff` stays a byte compare and
     Designer/vault round trips are trivially exact, but `git diff` shows one
     changed line per edit, code review and merges are hopeless, and the agent
     reads a 200 KB line to change one label.
   - *Pretty in the tree, compact on the wire* (recommended): the tree holds
     2-space JSON with the builder's key order; the deployer and the Designer
     writer emit exactly what the vendor builder emits (`JSON.stringify(schema)`,
     compact — verified in the builder's bundle). `vault.diff` compares forms
     as parsed JSON; an unchanged form is never rewritten, so untouched bytes
     and package checksums stay untouched. Cost: one JSON-aware diff path.
   - A third option, *pretty everywhere*, would make every deploy rewrite
     every form once and change the bytes Designer sees; rejected.
   Unless overridden, P1 stores pretty.
4. **Tomcat on `idm-ig4`:** I may start it when the runtime-pickup spike is due.
5. Gatekeeper: done by hand 2026-09-11; the standalone launch was then verified
   (builder opened the tree's copy of `Help-desk Request Form`, offline, full palette).
