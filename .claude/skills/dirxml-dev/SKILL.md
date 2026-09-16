---
name: dirxml-dev
description: >-
  Develop, deploy and operate NetIQ / OpenText Identity Manager (DirXML) driver
  sets without Designer, using the DirXMLDev CLI (bin/idm) in this repo. Use when
  asked to change a driver's policies, filter, GCVs, schema map or mapping tables;
  to add or clone a driver; to review what a driver does; to deploy a change to an
  Identity Vault (staging or production); to check, start, stop or restart a
  driver, read its trace or its cache, or find out why it is misbehaving; or to
  bring a client's vault or Designer project under version control as IDM-as-code;
  to add or change a JSON provisioning form, a provisioning request definition
  (PRD) or the workflow it runs, and to prove it in the Identity Applications.
  Every change is validated by the engine's own compilers, proven against a
  regression corpus, diffed against the vault, snapshotted before deploy, and
  audited. For testing policies against sample events, the dirxml-policy-testing
  skill (the simulator) is the tool; this skill is the loop around it.
---

# DirXML development with `bin/idm`

The tree is the source of truth, the CLI keeps it consistent, the validator is
the engine's verdict, the vault is a deploy target, and git is the history.
`bin/idm` with no arguments lists every command; [reference/commands.md](reference/commands.md)
groups them; [`docs/agent-guide.md`](../../../docs/agent-guide.md) explains each
step in depth. **Before deriving how the engine, Designer, the Identity
Applications or packages behave, read [reference/facts.md](reference/facts.md)** —
every fact we have measured, with the spike that proves it; a question
answered there is never re-spiked. This skill is about *the loop* and *the rules*.

## Where to run

`bin/idm` lives in the DirXMLDev checkout; run it from there or by full path.
Client work lives in the **client's repo**: the tree (`tree/` or a directory
per driver set), `cases/` (the regression corpus), `deploy-log/` (committed),
`deploy-snapshots/` (gitignored), `environments.properties` and
`secrets-<env>.properties` (gitignored — never commit, never print). Vault
credentials, SSH hosts and secrets come from those files; if they are missing,
**ask** (see the table at the end) — never guess a host or a password.

## The loop

```
import-live / import-project → git            the vault (or project) as a tree, committed
validate                                      0 errors, or the errors are the first finding
query · show · refs                           orient: chains, GCVs in scope, who references what
edit (files) + operations                     content = file edits; structure = operations; one commit each
validate                                      the engine will load it
simulate --cases cases/ --against <before>    what the corpus says changed — never hidden
vault.diff --env stg                          exactly what will change in the vault
vault.deploy --env stg --dry-run              the plan — SHOW IT and ASK before writing
vault.deploy --env stg --yes | --step         snapshot → write → restart → verify → audit
vault.verify · driver.submit --tree           vault == tree; the live engine agrees with the simulator
git commit (tree + deploy-log)                the deployment history travels with the tree
vault.deploy --env prd --confirm prd …        only when a human says so, from a known state
```

Two kinds of change (the agent guide, "Two kinds of change"):

- **Content** — rules, stylesheets, scripts, table rows: edit the file, then
  `validate`. DirXML Script is the language; the engine's compiler judges it.
- **Structure** — add / rename / delete / link / unlink / reorder artifacts,
  rules, GCVs, filter, schema map, driver settings, table rows, new drivers:
  an operation. Each is a transaction that refuses rather than leave the tree
  invalid, and prints what it changed.

## Rules

1. **Show before you write.** A vault deploy is preceded by `vault.diff` and a
   `--dry-run` plan in the conversation, and a human's yes. Production needs
   `--confirm <env>` typed by the human, a committed tree, and a vault that
   matches the last recorded deploy — if it doesn't, say what the drift is and
   let the human choose `--capture-drift`; never run it silently. **Read the
   whole dry-run before `--yes`** — a plan with many deletes of one kind
   (entitlements, forms, PRDs) usually means an out-of-date tree, not an
   intentional wipe; the plan itself refuses to empty a kind and names the fix
   (re-import with `import-live`, or `--delete-all <kind>` when the wipe is
   really wanted — docs/vault-deploy.md, "Deploy never empties a kind").
2. **Never `--force`.** A refused operation is information (the agent guide's
   refusal table). Fix the cause, or report it.
3. **Never print a secret.** Secrets come from the environment's secrets file
   or `--stdin`; the audit log and the conversation carry names only.
4. **A running vault validates clean.** If `validate` reports errors on a tree
   imported from a running vault, report a validator bug (with the finding) —
   don't edit the tree to silence it.
5. **Stopping a real connector queues its events.** `driver.stop` is safe for
   the vault but the cache grows; say so, and never `driver.cache clear`
   without showing the count and the first/last event (the command does; read
   them to the human).
6. **One operation, one commit**, with the operation's result in the message;
   `deploy-log/` is committed after every deploy.
7. **Packaged content is customized, not avoided.** Editing a packaged policy
   is the supported method; the tool keeps the baseline (`.package-baseline/`)
   and marks it — mention it in the commit.
8. **A workflow is engine-valid and dashboard-usable, or it is not done.**
   `validate` (FlowCheck) is the engine's verdict; the dashboard adds three
   rules the engine does not enforce — every approval binds an approval form,
   every denied path sets the completed status through a status mapping, and
   the PRD is Active with directory rights for its requesters. The `flow.*`
   operations do the first two by default; never remove them to "simplify".
9. **A closed spike adds its facts to `reference/facts.md`.** Findings that
   stay only in a spike note get re-derived.

## Recipes

- **Implement requirement X** — [reference/recipes.md#implement](reference/recipes.md#implement-a-requirement)
- **Promote a change STG → PRD** — [reference/recipes.md#promote](reference/recipes.md#promote-staging-to-production)
- **Why is driver D misbehaving?** — [reference/recipes.md#investigate](reference/recipes.md#investigate-a-misbehaving-driver)
- **Onboard a client vault** — [reference/recipes.md#onboard](reference/recipes.md#onboard-a-client-vault)
- **Add a driver** — [reference/recipes.md#add-driver](reference/recipes.md#add-a-driver)
- **Change a provisioning form** — [reference/recipes.md#form](reference/recipes.md#change-a-provisioning-form)
- **Author a workflow (PRD)** — [reference/recipes.md#workflow](reference/recipes.md#author-a-workflow)
- **Prove a form or workflow in the Identity Applications** — [reference/recipes.md#prove-idapps](reference/recipes.md#prove-it-in-the-identity-applications)
- **Hand the work to a Designer user** — `idm export tree/ set.xml` (Designer
  imports a driver-set configuration), or let Designer *Import from the
  Identity Vault* after a deploy; `idm export-project` (updating an existing
  project) when it lands.

## When something is missing

| You need | Sign | Ask for |
|---|---|---|
| a vault target | `no environments file` / `no environment 'x'` | host (`ldaps://…:636`), bind DN, driver-set DN, tier (dev/stg/prd); it goes in `environments.properties`, gitignored |
| a secret (new driver, rotated password) | `MISSING SECRET: <driver>.…` in the plan | the value, into `secrets-<env>.properties` (or an env var / command) — never into the chat |
| the engine host for traces | `environment has no sshHost` | the host and user with key-based SSH to the engine; `driver.trace tail` is unavailable until then |
| a regression corpus | `simulate` finds no cases | harvest from the Event Logger DB (`bin/sim harvest`) or a stopped driver's cache (`driver.cache view --out`), or author cases; a change without a corpus is deployable but unproven — say so |
| a GCV / Library policy / mapping table a policy references | `validate` reports `gcv-undefined`, `link-unresolved`, `mapping-table-missing` on a tree from an export | a full driver-set source (live vault, LDIF, project) or the missing objects — a single-driver export may omit them |
| the driver's package origin | `package.diff` says not package-managed for something the client calls packaged | a Designer project or the vault (exports and LDIFs carry package meta; hand-made objects don't) |
| a Designer project on disk | asked to update the project rather than the vault | the project directory (`.project`, `Model/`) |
| the Identity Applications | a form or workflow must be proven at runtime | the dashboard URL (`https://host/idmdash`), a user with rights to the PRD (an admin for a lab), and — for the engine log — `kubectl`/SSH to the applications host; the JSON request form only submits from a browser, so a person or the app's browser pane submits it |
| a vendor form builder | `form.edit --check` says none found | a Designer 4.8+ install (`IDM_FORMBUILDER` for another location); one-time quarantine/exec-bit fixes are printed, never run |
