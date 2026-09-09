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
