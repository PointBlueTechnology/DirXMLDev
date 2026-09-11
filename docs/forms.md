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

### Option B — typed form operations (agent path, the core)

The agent needs to author and change forms without a screen. A typed model of
the document with reference-aware operations, exactly like policies:

```
form.add       <tree> --kind request|approval|template --name N [--from <form>|--template blank|request|approval]
form.show      <tree> <form>                  outline: fields (key, type, label, required, hidden, conditional), scripts, languages
form.field.add <tree> <form> --key k --type textfield|textarea|select|checkbox|radio|datetime|button|dn_display|dynamic_entity|… [--label --required --after <key> --in <panel/column>] [--json '<extra props>']
form.field.set / .remove / .move            change any property, delete, reorder/reparent
form.set       <tree> <form> --title|--display|--inline-script <file>|--external-script <url>
form.localize  <tree> <form> --lang fr --set "Title=Titre" …   (or --sync: add missing keys from labels)
form.rename    <tree> <form> --to N          rewrites every PRD binding that references it
form.delete    <tree> <form>                 refused while a PRD binds it (--force)
```

- Components are created with the builder's defaults for that type (captured
  from the stock forms as templates in `resources/forms/components/<type>.json`),
  so a form we author round-trips through the vendor builder unchanged.
- **PRD binding sync** is part of every form transaction: for each PRD whose
  `provision-request`/`process` binds the form, rewrite the `<form-binding>`
  field list (`name`, `data-type`, `control-type`) and the
  `request-data-items`/activity `data-items` (`flowdata.<activity>/<form>/<key>`)
  the way Designer does; report the changes as notes. This is the only PRD
  write in Track P.
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
  never delete a bound form without `--force`.
- Stock forms (the 11 in the base package) are customizable like any packaged
  artifact — marked, baselined, never refused.
- Scripts inside forms are code: they go through the ECMAScript check and are
  shown in `form.show`; `form.set --inline-script` takes a file so the agent
  reviews it as a file.
- Nothing here touches workflow activities beyond the binding/data-item lines
  that reference the form; workflow design stays out of scope.

## 6. Build order

1. **P1 read/model** — `Provisioning`/`Form`/`Prd` model; live + LDIF + project
   readers; as-code writer/reader; `import-live` of the UA driver's AppConfig;
   round trip byte-exact on test11/XFDEMO1 and the test vault. (delegable)
2. **P2a form.edit** — the launcher (Option A) + post-edit validate/sync. (me)
3. **P2b typed ops** — Option B commands + `FormCheck` + PRD binding sync;
   parity test: a form authored by us re-saved by the vendor builder is
   semantically identical. (partly delegable once the model exists)
4. **P4 deploy** — object kinds in the mapping/plan/deployer; spike P4a on the
   test vault (needs Tomcat running) for runtime pickup. (me)
5. **P3 preview** — Option C. (delegable)
6. Designer writer/reader parity (`export-project` adds forms + PRD digests),
   docs, skill recipes ("add a field to the request form of PRD X").

## 7. Decisions for Jerry

1. **A + B + C in that order** as above? (Recommended: yes; A first because it
   is small and immediately useful, B is the substance.)
2. **PRD scope = binding sync + whole-object deploy only** (no PRD authoring,
   no workflow edits). A `prd.add --from-template NoApproval|SingleStepApproval
   --request-form X [--approval-form Y]` is the one authoring command worth
   considering — cheap because the templates are in every vault; in or out?
3. **Pretty-printed forms in the tree, compact on the wire** (semantic diffs)
   — or byte-preserving like policies? (Recommended: pretty.)
4. **Runtime pickup spike needs the Identity Applications up on `idm-ig4`.**
   Tomcat is stopped there; do you want to start it, or should I (the
   `netiq-tomcat` service) when the spike is due?
5. Gatekeeper fix for `FormBuilder.app`: run once by hand (the command is in
   the spike note) — the tool prints it, never runs it.
