# Spike W4 — an authored workflow, end to end on idm254 (2026-09-15)

Question: does a workflow authored entirely with the `flow.*` operations (no
Designer) deploy through the normal path, show up in the Identity
Applications, run through the engine with its condition and two approvals,
and complete — and what does the dashboard need beyond what the engine needs?

System: `idm254` (IDM 4.10.1 / Identity Applications CE 25.4.2; eDirectory
`idm254-engine`, apps on k3s), tier `dev`, user `uaadmin` as requester,
recipient and approver (the lab's only user). Decisions in
[../workflows.md](../workflows.md) §7: no entitlement (none on the lab),
approver = uaadmin.

## Result: PASS (happy path and denied path)

**Authoring** (tree `~/IdeaProjects/DirXMLDev-e2e/tree-idm254`, 6 commands):
`form.add` "DirXMLDev W4 Form" from "Request Form"; `prd.add` "DirXMLDev W4"
from `NoApproval` (`--category accounts --map-all --display-name`);
`flow.activity.remove --id prov`; `flow.activity.add --kind condition --id
has_reason --after Activity --expression "flowdata.get('Start/DirXMLDev_W4_Form/reason') != null"`;
two `flow.activity.add --kind approval` (`approval_1` after `has_reason
--via true`, `approval_2` after `approval_1 --via approved`, `--addressee
"'cn=uaadmin,ou=sa,o=data'"`); `flow.activity.add --kind log --id log_done
--after approval_2 --via approved`. Every step validated clean (0 errors);
`prd.flow` showed start → Workflow Status → Reason given? → (true)
approval_1 → (approved) approval_2 → (approved) log_done → Finish, with the
`false`/`denied` links to Finish.

**Deploy**: `vault.deploy` = 5 steps (containers ensured, form added, PRD
added), verify OK. After the approval-form fix below: 1 change, **2 modify
steps only** (`XmlData`, `srvprvProcessXML` — the W2 trimmed deploy), verify OK.

**Pickup**: no cache flush. The PRD appeared under Access → Request → New
Request (search "DirXMLDev", category Accounts) immediately. Picking it
opened the JSON request form ("DirXMLDev W4 Form": recipient, required
reason) — the form renderer works on this lab as of today (it did not on
2026-09-11). Submit created the request (confirmation `20260915-1`).

**Engine** (identityapplications pod log, process `…:9`): `Workflow_Started`
(Start) → `Workflow_Forwarded` Workflow Status → `Workflow_Forwarded` Reason
given? → task at `approval_1` → `Workflow_Claimed`/`Workflow_Approved`
(approval_1) → task at `approval_2` → `Workflow_Claimed`/`Workflow_Approved`
→ `Workflow_Ended` (Finish). The request's comment trail in the dashboard
and over REST (`POST /requests/history/item`) carries both approvals with
their comments.

**Denied path**: the first request (`20260915-1`) was denied at First
approval (REST `POST /tasks {"action":"deny"}`); the engine ended it, no
Second approval task ever appeared.

## What the dashboard needs beyond the engine (found here, fixed in W2)

Clicking the task did nothing. Cause: the dashboard first calls
`POST /IDMProv/rest/access/tasks/item` (task details) and that failed with
"Failed to get task details" — server log:
`WfRestController.getTaskDetails → AFFormGenerator.generateFormXML`. The
authored `user-activity` had **no approval form bound**; the engine does not
require one, the applications do. Fix (ec3d936): `flow.activity.add --kind
approval` binds the driver's stock `Approval Form` by default (`--form` to
choose), writing the `<form form-id>` declaration, the `<form-binding>` and
the stock data items (`title`/`recipient`/`reason` from the request form's
flowdata paths, `initiator`, `requestDate` = `process.getTimestamp()`);
`flow.activity.set --form` repairs an existing activity. After redeploying,
task details returned 200 with the five data items, and both approvals
went through the dashboard (select → Approve → comment → Approve).

## Request status on the denied path (follow-up)

Request History shows **both** runs as "Approved" — the denied one too.
`NoApproval`'s process sets `flowdata.IDM_COMPLETED_APPROVAL_STATUS` to
`'approved'` in its "Workflow Status" mapping activity before anything else
runs, and our `denied` links go straight to Finish, so nothing ever sets
`denied`. The stock approval templates route `denied` through a second
mapping activity ("Workflow Status Denied"). **Fixed the same day:**
`flow.activity.add --kind approval` defaults `--on-denied` to the process's
denied status mapping, creating `status_denied` (→ finish) when absent, and
`flow.activity.add --kind mapping --status approved|denied` creates one
explicitly. **Re-verified live** (2026-09-15): the W4 PRD got
`status_denied` (`flow.activity.add --kind mapping --status denied --after
approval_1 --via denied`, `flow.link.remove`/`flow.link.add` for
`approval_2`), redeployed (2 modify steps); a new request (`20260915-6`)
denied at First approval went Start → Workflow Status → Reason given? →
approval_1 → **Workflow Status Denied** → Finish and Request History shows
it as **Denied** (the two earlier runs still read Approved).

## Other facts

- **REST cannot submit a JSON-form PRD.** `POST /requests/permissions/item`
  (with or without `permReqParams`) answers "Internal exception occurred
  processing REST service" for a PRD whose request form has mapped fields;
  only the form renderer's submit (`/WFHandler`, browser session) works.
  Approving/denying over REST (`POST /tasks`) works fine.
- Clicking a task name in the Tasks list never opened the approval-form
  window in the app's browser pane (the dashboard opens it with
  `window.open` from the task-details callback); the row's bulk
  Approve/Deny buttons do the same job with a comment. Not investigated
  whether a normal browser opens the popup.
- A PRD shows under Access → Request only when Active **and** the user has
  directory rights to it (Jerry); uaadmin has all rights.
- No layout data anywhere; Designer will auto-layout the authored flow (W5).
- The Java-side LDAPS connections from this Mac to the lab subnet fail with
  "No route to host" while ldapsearch/curl/python work (cause not found);
  the deploys ran through an SSH tunnel via the k3s host
  (`idm254tun` environment, `ldaps://127.0.0.1:6636`, JNDI endpoint
  identification off). Environment-only, nothing in the tool.

## W5 — Designer acceptance: PASS (Jerry, 2026-09-15)

`Designer-modernized` imported the User Application driver's provisioning
objects from the Identity Vault and "DirXMLDev W4" opened fine — the
auto-laid-out diagram, both approvals with the Approval Form, the status
mapping. No layout data was needed (§1.3 of the design note).

## Cleanup

The PRD was removed from the tree by hand (its `prds/DirXMLDev W4/`
directory and the manifest's `<prd>` entry — there is no `prd.delete`
operation yet, a follow-up), then `form.delete` (which refuses while a PRD
binds the form), `vault.deploy` (two deletes), `vault.diff` empty.
