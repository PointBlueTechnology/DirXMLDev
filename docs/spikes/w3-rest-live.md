# Spike: W3 integration activities, live (2026-10-14/15, idm254)

Part 1 (2026-10-14): REST — PASS after two fixes. Part 2 (2026-10-15): start-flow PASS; role-request
and resource-request accepted, their grant blocked by the lab's workflow-administrator token.

Two open checks from docs/plan.md, run together on idm254 (Identity Manager 4.10.1, Identity
Applications 24.4): does `POST /index/permissions ADD_OR_MODIFY` make a just-deployed PRD
requestable at once, and does a `rest-activity` authored by `flow.activity.add` run?

## Run

1. A fresh `import-live` of driverset1 into a scratch tree; `vault.diff` "no differences".
2. Scratch objects: `form.add --kind request "DirXMLDev W3 Form"` with a required `reason`;
   `prd.add "DirXMLDev W3 REST" --from-template NoApproval --map-all`; the template's entitlement
   activity removed; `flow.activity.add --kind rest --id rest_1` GET
   `https://idm254.pointbluetech.com:443/osp/a/idm/auth/oauth2/.well-known/openid-configuration`
   (the lab's own public OpenID discovery document: nothing leaves the lab), a log activity on its
   forward link and another on an added error link. Deploy: 2 adds.
3. **Index.** Four seconds after the deploy, `POST /permissions/item` said "does not exist";
   `ADD_OR_MODIFY` answered success and the PRD still did not exist a second later; `REFRESH` at
   +1:43 likewise. The PRD appeared at **+7:57**, within the index's normal 10-minute interval.
   **Neither call shortens the wait.**
4. **First request:** `Workflow_Started` → `Workflow_Forwarded` → `EcmaError: ReferenceError:
   "https" is not defined` → `Workflow_Error` "Error initializing scripting engine or scripting
   context" → the error link → `Workflow_Ended`. **The engine evaluates `rest-activity`'s
   attributes as ECMAScript**; `protocol="https"` is a variable that does not exist.
5. With all six attributes quoted (`'https'`, `'idm254…'`, `'443'`, the path, `'GET'`,
   `'application/json'`) and the forward link going to an approval task (visible over REST, unlike a
   log activity), the PRD redeployed (2 modifies) and was requested **one second later: the new
   definition ran** (a changed PRD is picked up at once). The activity went forward — so every quoted
   attribute is evaluated (a literal host with quotes in it could not have resolved) — but the
   task's data showed empty outputs: `returnStatusCodeOutputMap` held `restStatus`.
6. With the output maps `flowdata.restStatus` / `flowdata.restBody`, the task's `reason` read
   **`status 200 body {"issuer":"https://idm254.pointbluetech.com/osp…`**. Proof complete.
7. Clean-up: both tasks approved; `prd.delete`, `form.delete`, deploy (2 deletes), `vault.diff`
   "no differences". The three requests stay in the applications' history.

## Fixed in the tool

- `flow.activity.add`/`.set` write a plain value for a rest attribute quoted and keep an
  expression; `--status-to`/`--content-to`/`--content-type-to` get the `flowdata.` prefix.
- `FlowCheck`: `flow-rest-unquoted` (a bare word in an expression attribute) and
  `flow-rest-output-map` (an output map that is not a flowdata path); the protocol and method enum
  checks look inside the quotes and skip a computed expression.
- `prd.flow` shows the literal values without quotes.
- Docs: workflows.md §4 W3, agent-guide.md, day-to-day.md, idapps-rest.md, the skill's facts.md,
  `bin/apps`' messages.

## Not covered

- The `<http-headers>` children (request headers by element) and `content` were not exercised; `content` is documented as an expression already, the header elements' values are likely evaluated the same way but are unproven.
- `role-request`, `resource-request` and `start-flow` are still unproven live.

## Found on the way

- `idm254-engine.pointbluetech.com` now resolves to DockerHost1 (192.168.103.99), which forwards
  LDAPS 636 to the idm254 engine (the certificate is `O=IDM254_TREE`); SSH by that name reaches
  DockerHost1. The Identity Applications (k3s) are at 192.168.103.98 (`nam-k3s`).
- A log activity's message does not appear in the applications' log at the default level; an
  approval task is the readable witness of a path taken.

## Part 2 (2026-10-15): role-request, resource-request, start-flow

Scratch objects on a fresh `import-live` (diff "no differences"): a request form; a role
(`role.add … --level 20 --category System`) and a resource (`resource.add … --category System`), no
entitlement; four PRDs from NoApproval: *Role Req* (`role-request`: the role's DN quoted, target
`recipient`, `GRANT`), *Resource Req* (`resource-request`, likewise), *Child* (an approval task) and
*Start* (`start-flow`: *Child*'s DN quoted, recipient `recipient`). In each, the forward link and an added
error link go to differently named approval tasks, so the path taken shows in the task list.

- **start-flow — PASS.** `Start_Correlated_Workflow` → *Child* started → *Start* went forward; tasks "W3
  start went forward" and "W3 child flow ran".
- **role-request, resource-request — accepted, grant blocked.** Both evaluated their children (the bare
  `GRANT`/`USER` are enum literals, unlike the REST attributes) and reached
  `IDMApplicationClientImpl.requestPermission`, which threw `NullPointerException` in
  `OAuthTokenManager.fetchToken` (`getWFAdminToken`) — before any request reached OSP — so the activity
  took its error link ("W3 role took the error link", "W3 resource took the error link"). Retried after the
  `oidpInstanceData` fix and a cache flush: the same. The workflow administrator's OAuth settings in the
  applications' configuration are the place to look; reading them from the pod was not permitted in this
  session. uaadmin was granted nothing (`nrfAssignedRoles`/`nrfAssignedResources` checked).
- **Found: sign-ins fill `oidpInstanceData`.** At 13:05 every uaadmin sign-in began failing (HTTP 500,
  OSP "Error writing instance data … -649 insufficient buffer"). The attribute held ~32 KB, filled by two
  days of one-sign-in-per-call runs and a 9-minute polling loop. Cleared (Jerry's go-ahead); sign-ins
  worked at once. `bin/apps` now reuses its token and has `permission --wait`.
- **Found: a cache flush makes a new PRD requestable at once.** `DELETE
  /IDMProv/rest/admin/cache/holder/items {"cacheHolderItems":[{"cacheHolderID":"All Cache"}]}` → 204;
  the PRDs deployed 62 s earlier were in the permission index immediately (`bin/apps flush`).
- **Found: `role.add`/`resource.add` take the category key from the container name.** A role's container
  (`RoleDefs/Level<n>/<container>`) and its category key (`nrfRoleCategoryKey`, a key of the Directory
  Abstraction Layer's `roles-category` choice list: `default`, `system` on idm254) are separate; the
  operations set the key to the container's name lower-cased, and nothing checks it against the list
  (follow-up issue).
- Clean-up: tasks approved or denied (the two error-path tasks would not approve, "Failed to get task
  details"; denied), every scratch object deleted (7, then 5 after the retry), `vault.diff` "no
  differences".
