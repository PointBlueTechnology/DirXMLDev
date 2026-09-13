# Spike 6a: Designer opens a project the writer updated (2026-09-09)

**Question.** Does Designer accept a project that `idm export-project` modified
— a minted CObject id, a new `ScriptPolicy_` + `_contents.xml`, and a rewritten
`Idm:CommandPolicies` relation list on the `Subscriber_` — without repair
prompts or errors?

**Setup.** Copy of `test11` → `import-project` → `policy.add` on Querytest
(subscriber, `Agent-Test-Policy`, linked last in subscriber-command) →
`export-project` (created `TORZBK6V.ScriptPolicy_` + `TORZBK6V_contents.xml`,
changed only `YUIL1L8I.Subscriber_`) → copied to
`~/designer_workspace/test11rt` → Jerry imported it from the file system.

**Result (Jerry).** Import succeeded; the project opens; the Querytest driver
shows the new policy in the subscriber command set; the policy opens with no
rules (as authored — `policy.add` creates an empty policy); "all appears well".

**Lesson unrelated to the writer.** A first copy named `test11-roundtrip`
still contained `test11.proj`/`test11.cproj`; Designer listed the project as
"No valid .proj file". The `.proj`/`.cproj` file names, their `name` /
`cprojectURI` attributes, the Eclipse `.project` name and the
`ModelerNodes_` `href="../../<name>.proj#/"` must all carry the folder name.
The writer never touches these; anyone copying a project must.

**Consequence.** The writer's scope stands as designed: content, added /
removed / renamed artifacts, linkage, driver settings and GCVs on an existing
project; new non-packaged drivers attempted (not yet exercised in Designer —
the next spike input when a client needs it); packaged drivers refused. The
2-space re-indentation of touched CObject files and decimal character
references in attribute values did not bother Designer.

# Spike 6b: Designer opens a project the writer added a form and a PRD to (2026-09-13)

**Question.** Does Designer accept the provisioning objects `idm
export-project` writes — a JSON request form (`.formRequest`, the vendor
builder's compact document) with a minted digest, and a PRD (`.prd`, the
union layout with `<provision-request>` right before `<process>`) with a
digest carrying display names, category key and a `digest-dependency` on the
form — and does the form open in the vendor form builder?

**Setup.** Copy of `test11` → `import-project` → `form.add --kind request
--name "DirXMLDev Writer Test" --from "Request Form"` + `field.add` of a
required textarea `justification` + `form.localize --sync` → `prd.add --name
"DirXMLDev Writer PRD" --from-template NoApproval --request-form "DirXMLDev
Writer Test"` + `prd.map justification` → `export-project` (exactly four new
files: the form, its digest, the PRD, its digest; nothing else touched;
`import-project` of the result diffs empty against the tree) → copied to
`~/designer_workspace/test11pf` → Jerry imported it from the file system.

**Result (Jerry).** Import succeeded; every view is populated; the new
request form appears under Provisioning → Workflow Forms → Request Forms and
opens in the form builder. "Everything shows up now. Form opened in form
editor." The PRD's Active/binding panel and the Deploy offer were not
reported separately.

**Lesson repeated from 6a, with the second half.** The first attempt
imported "successfully" but showed no System Model, a blank developer view
and no provisioning items: the copy's `.proj`/`.cproj`/`.project` had been
renamed but still *contained* `cprojectURI="test11/test11.cproj"`,
`name="test11"`, `<adapterProject href="test11.cproj#/"/>` and the old
project name. A silent empty import is the symptom when only the file names
are changed; "No valid .proj file" (6a) is the symptom when the names are
not. Rewrite both, delete the broken import from the workspace keeping the
contents, import again.

**Consequence.** The provisioning half of the writer ships as designed
(forms and PRDs added, removed or changed on an existing project; container
digests untouched). Track P has no open human check.
