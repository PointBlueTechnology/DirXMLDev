# Recipes

Each recipe assumes the client repo layout from the skill (tree, cases,
environments and secrets files) and the DirXMLDev checkout on the path as
`idm`.

## Implement a requirement

Say the requirement is *"on the AD driver, normalize `Title` to title case
before it reaches AD"*.

1. **Orient.** `idm validate tree/` (0 errors), `idm query tree/ chain "AD Driver"
   sub` (where a command transform belongs), `idm query tree/ gcvs "AD Driver"`
   (anything configurable), `idm show tree/ "drivers/AD Driver/subscriber/…"` for
   the neighbouring policies' style.
2. **Create the policy where the chain needs it.**
   `idm policy.add tree/ --driver "AD Driver" --scope subscriber --name sub-ctp-NormalizeTitle --link subscriber-command --at "after:drivers/AD Driver/subscriber/sub-ctp-Transform"`
3. **Write the rules** in the file the operation created (DirXML Script; match
   the neighbours' conventions; one `<description>` per rule that says what it
   does — the docs generator and the coverage report use it).
4. **Validate.** `idm validate tree/` — the engine's compiler is the judge.
5. **Prove.** `idm simulate tree/ --cases cases/ --against tree-before/` (a
   worktree or `git worktree add` of the previous commit). Read what changed;
   if the corpus has no case that exercises Title, add one (an event with a
   Title, an expected output) and say the corpus was extended.
6. **Commit** the tree with the operation's result in the message.
7. **Deploy to staging.** `idm vault.diff tree/ --env stg` → `idm vault.deploy
   tree/ --env stg --driver "AD Driver" --dry-run` → show the plan → on a yes,
   `--yes` → `idm vault.verify tree/ --env stg`.
8. **Canary.** `idm driver.submit --env stg --driver "AD Driver" --xds case/input.xds --yes --tree tree/`
   — the live engine's Subscriber channel hands the shim what the simulator
   predicted, or you have a real discrepancy to explain.
9. **Commit `deploy-log/`.** Promotion is a separate step (below).

## Promote staging to production

1. The tree commit that went to staging is on record: `deploy-log/stg.jsonl`
   has an `ok` deploy with its `treeCommit`. Check out exactly that commit.
2. `idm vault.diff tree/ --env prd` — what production will get. If it shows
   more than the change (drift), stop and show it: production changed outside
   the tool. The human decides between fixing production, capturing the drift
   (`--capture-drift`, then rebasing the change onto the captured state), or
   waiting.
3. `idm vault.deploy tree/ --env prd --driver "AD Driver" --dry-run --confirm prd`
   — the plan. Read it to the human.
4. On an explicit yes: `--yes --confirm prd` (or `--step --confirm prd` to
   confirm and verify each change). The deployer snapshots first; the snapshot
   path is in the result — keep it in the conversation.
5. `idm vault.verify tree/ --env prd`; `idm driver.status --env prd --driver
   "AD Driver"`; if the driver runs, `idm driver.trace tail --env prd --driver
   "AD Driver" --since 5` to see it load the new policy (`Found DirXMLScript
   policy …`) and process events.
6. Commit `deploy-log/`. If anything is wrong: `idm vault.rollback --env
   prd --snapshot <path> --yes`, verify, and say what happened.

## Investigate a misbehaving driver

*"The AD driver stopped creating accounts yesterday."*

1. `idm driverset.status --env prd` — is it running? is its cache growing
   (bytes) — events queueing means the subscriber isn't draining.
2. `idm driver.status --env prd --driver "AD Driver"` — start option, trace
   level and file, recent audit (did someone deploy or change trace?).
3. `idm driver.trace tail --env prd --driver "AD Driver" --since 60 --grep
   "Code\(|error|veto|Discarding"` — the engine's own account of what it did;
   widen the window or drop the grep as needed. If the level is 0, `idm
   driver.trace set --level 3 --file …` (audited; `reset` afterwards).
4. If events are queued: `idm driver.cache view --env prd --driver "AD Driver"
   --out cases/ad-stuck` — the real events as a simulator case. Run them
   against the tree (`bin/sim step cases/ad-stuck` in the simulator, or `idm
   simulate`) and read the stage where the create is vetoed, filtered, or fails.
5. Compare the vault with the tree: `idm vault.diff tree/ --env prd` — a
   change made outside the tool shows up here.
6. Fix in the tree (a rule, a GCV, a filter entry), validate, simulate the
   stuck event, deploy to staging, canary with the same event, then production
   as above.

## Onboard a client vault

1. From the client: the vault's LDAPS host, a bind DN with rights on the driver
   set, the driver-set DN, and — for traces — key-based SSH to the engine host.
   Put them in `environments.properties` (gitignored); secrets the tree can't
   carry go in `secrets-<env>.properties` (gitignored).
2. `IDM_JAVA_OPTS="-Dldap.url=… -Dldap.bindDn=… -Dldap.password=…" idm import-live
   "cn=driverset1,o=system" tree/`; `git init`; commit "as imported". The import
   writes `.gitattributes` (`* -text`) — keep it, vault content is bytes.
3. `idm validate tree/` — 0 errors expected; anything else is a real finding
   (or a validator bug) to report before any change.
4. `idm vault.diff tree/ --env prd` — empty, by construction; from now on it
   detects changes made outside the tool.
5. Record the known state: `idm vault.deploy tree/ --env prd --confirm prd
   --capture-drift` (with nothing to deploy it records the commit the vault
   matches); commit `deploy-log/`.
6. A corpus: `bin/sim harvest` from the Event Logger DB if the client runs it,
   else `idm driver.cache view --out` on stopped drivers, else author cases for
   the drivers that matter. `idm simulate tree/ --cases cases/` must be green
   before the tree is used as a baseline.
7. `idm docs tree/ --out docs/` — the driver set documented from the model;
   commit it with the tree.

## Add a driver

- **From a vendor package** — ask for the driver's configuration exported from
  Designer with *include referenced policies* (a package-based driver's content
  only exists in Designer's catalog). `idm driver.add tree/ --name "AD Driver 2"
  --from-export ad-driver.xml`, then `gcv.set` / `driver.set` the environment
  values, `validate`, deploy: the deployer creates it stopped with start option
  manual and sets the secrets the plan lists (`MISSING SECRET` lines are what to
  ask for). Start it deliberately (`driver.start`) once the secrets are in.
- **A copy** — `idm driver.add tree/ --name "AD Driver TEST" --copy-of "AD Driver"`
  then change what differs.
- **Hand-built shim** — `idm driver.add tree/ --name Loop --shim-class com.example.Shim`,
  then filter, policies, GCVs through the operations.
- A Designer user gets a tool-created driver via Designer's *Import from the
  Identity Vault* after the deploy.

## Change a provisioning form

*"Add a required Priority field to the Help-desk request and pass it to the workflow."*

1. **Orient.** `idm form.list tree/ --driver "User Application Driver"`,
   `idm form.show tree/ "Help-desk Request Form"` (fields, scripts, languages,
   the PRDs that bind it), `idm prd.show tree/ HelpdeskTicket` (bindings).
2. **Add the field.** `idm form.field.add tree/ --form "Help-desk Request Form" --key priority --type select --label Priority --required --props '{"data":{"values":[{"label":"High","value":"high"},{"label":"Low","value":"low"}]}}'`
   — the request binding's field list is rebuilt automatically; a stock form
   is marked customized with its baseline kept.
3. **Map it.** `idm prd.map tree/ --prd HelpdeskTicket --field priority` — mappings
   are never invented by the sync, so this step is always explicit (`--activity`
   maps an approval activity's data item instead).
4. **Localize.** `idm form.localize tree/ "Help-desk Request Form" --lang en --set "Priority=Priority"`
   then `--sync` for every declared language.
5. **Check and look.** `idm validate tree/` (FormCheck: 0 errors); `idm form.preview
   tree/ "Help-desk Request Form" --out preview.html` for the layout; a person
   can instead open it in the vendor builder with `idm form.edit`.
6. **Deploy** the normal way (`vault.diff` → `vault.deploy --dry-run` → `--yes`
   → verify); no driver restart, no cache flush needed. Then prove it (below).

## Author a workflow

*"A request that needs the manager's approval, then a second approver, and
grants nothing yet."* Every step is a transaction; `validate` runs after each
and refuses a change the engine would reject.

1. **Start from a template.** `idm prd.add tree/ --name "Widget Access" --from-template NoApproval --request-form "Widget Request Form" --category accounts --display-name "en~Widget access" --map-all`
   (`--map-all` maps every request field; the PRD is Active; `NoApproval`'s
   entitlement step still carries `{enter Entitlement DN here}` — a warning
   until it is set or removed).
2. **Shape the flow.** `idm flow.activity.remove tree/ --prd "Widget Access" --id prov`
   (or `flow.activity.set --id prov --entitlement-dn "cn=…"` to keep it);
   `idm flow.activity.add … --kind condition --id has_reason --after Activity --expression "flowdata.get('Start/Widget_Request_Form/reason') != null"`;
   `idm flow.activity.add … --kind approval --id approval_1 --after has_reason --via true --addressee "IDVault.get(recipient,'user','manager')"`;
   `idm flow.activity.add … --kind approval --id approval_2 --after approval_1 --via approved --addressee "'cn=…'"`;
   `idm flow.activity.add … --kind log --id log_done --after approval_2 --via approved`.
   Each approval binds the stock Approval Form and routes `denied` to the
   `status_denied` mapping (created once) — both required by the dashboard.
3. **Read it back.** `idm prd.flow tree/ "Widget Access"` (text) or
   `--format mermaid --out flow.mmd`; `idm validate tree/` = 0 errors and no
   `flow-placeholder` for this PRD.
4. **Deploy** (`vault.diff` → `vault.deploy`): a new PRD is an add; a changed
   process writes only `XmlData` + `srvprvProcessXML`. Then prove it (below).
5. **Designer** sees it via *Import from the Identity Vault* (or
   `export-project`); the diagram lays itself out.

## Prove it in the Identity Applications

1. **Visibility.** Sign in as a user with rights to the PRD (an admin on a lab);
   Access → Request → New Request → search the display name. Missing means:
   not Active, no directory rights, or the display name differs.
2. **Request.** Over REST (no browser): `bin/apps --env <env> request "<PRD name>" --data reason="…" [--recipient DN]`
   — `POST /requests/permissions/v2`, the JSON form renderer's own call
   (`docs/idapps-rest.md` §2). A PRD deployed less than ~10 minutes ago is not
   yet in the applications' permission index and the call answers
   `success:false`; `bin/apps permission "<PRD>"` says whether it is indexed,
   `bin/apps index "<PRD>"` asks the index to add it. In the browser instead:
   picking the PRD opens the JSON request form in a new window; fill it and
   Submit. (`/requests/permissions/item` is the legacy-form/role/resource call
   and fails for a JSON-form PRD.)
3. **Approve.** `bin/apps --env <env> tasks` then `bin/apps … approve <taskId> --comment "…"`
   (or `--process "<PRD name>"` for every task of that PRD; `deny` likewise) —
   `GET /tasks/list` + `POST /tasks`. In the browser: Tasks → select the task →
   Approve/Deny with a comment (the bulk buttons; opening the task itself needs
   the approval form bound).
4. **Verify.** `bin/apps … history` (`GET /requests/historylist`; a completed
   approved request reads requestState 2 / processState 3) or Request History
   in the browser; the applications' log shows
   `[Workflow_Started] … [Workflow_Ended]` with every activity in between
   (`kubectl logs` on the identityapplications pod, or the Tomcat log).
5. **Clean up** scratch forms and PRDs by deleting them from the tree and
   deploying; `vault.diff` must come back empty.
