# Spike: a W3 REST activity, live — PASS after two fixes (2026-10-14, idm254)

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
