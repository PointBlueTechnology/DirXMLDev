# Spike: start a PRD over REST (no browser) — PASS (2026-09-16, idm254)

Question: Jerry (2026-09-15): "you should be able to initiate a PRD using
REST" — the W4 attempts with `POST /requests/permissions/item` had failed for a
JSON-form PRD. Which call starts a JSON-form PRD, with what body, and can a
whole live proof (request → approve → history) run without a browser?

Answer: **yes.** `POST /IDMProv/rest/access/requests/permissions/v2` with the
body the JSON form renderer builds (§2 of [../idapps-rest.md](../idapps-rest.md))
started the authored PRD; `POST /tasks` approved it; the engine ran Start →
Workflow Status → REST approval → log → Finish; `historylist` shows it
completed. Everything is scripted in `bin/apps`.

## How the body was found

The vendor swagger's body schemas are empty. The dashboard is an Angular app
served from `/idmdash/`; its lazily-loaded chunks list every REST path it uses
(`…/requests/permissions/item`, `/permissions/item`, `/tasks`, …) and show that
a JSON-form PRD is opened in a separate renderer app at `/forms/`. That
renderer's bundle contains the *Request* button's action —
`util.post('IDM','/rest/access/requests/permissions/v2', requestPayload, …)` —
and the two helpers that build `requestPayload`:
`{reqPermissions:[{id: <pid from the URL>, entityType:"PRD", proxyFor}],
data: <every form value as {key, value:[…]}>, recipients:[{dn, type:"user"}]}`.
The server side (`IDMAccessRest.jar`, `PermissionRequestService`, and the
permission index in `IDMcis.jar`) was read to learn the required fields, the
enumerations and the failure modes. The decompiled sources live only in the
session scratchpad, as always; the reference page records behaviour, not code.

## Run

1. Scratch objects: `form.add --kind request "DirXMLDev REST Form"` +
   `form.field.add --form … --key reason --type textfield --required`;
   `prd.add "DirXMLDev REST" --from-template NoApproval --request-form … --map-all`;
   `flow.activity.remove --id prov`; `flow.activity.add --kind approval --id
   approval_1 --after Activity --addressee "'cn=uaadmin,ou=sa,o=data'"`;
   `flow.activity.add --kind log --id log_done --after approval_1 --via approved`;
   `prd.map --prd … --field reason` (request form and `--activity approval_1`);
   `validate` 0 errors. Deploy through the tunnel: 5 steps (3 ensure_container,
   2 adds), verify OK (snapshot `deploy-snapshots/idm254tun/2026-09-16T17-19-51.ldif`).
2. **First submit, 1 minute after deploy: 200 `{"success": false}`** with no
   `Fault` and nothing in the log. `POST /permissions/item` for the PRD: 489
   `PermissionIndexException` "Permission with id […] does not exist."
   Cause (server): the request handler resolves the PRD in the **permission
   index**, whose PRD provider re-reads the vault every
   `com.netiq.idm.cis.rbpm.updateInterval[.prd]` minutes (default 10); a null
   lookup returns an empty result → `success:false`, unlogged.
3. **Second submit, 7 minutes later: 200 `{"success": true, … "requestId":
   "361da2b03d4c…"}`.** Engine log: `Workflow_Started` (uaadmin) →
   `Workflow_Forwarded` ×2 → task `a563c8e9…` "REST approval" listed by
   `GET /tasks/list` → `POST /tasks {"action":"approve"}` → `Workflow_Claimed`,
   `Workflow_Approved`, `Workflow_Forwarded`, `Workflow_Ended`.
   `GET /requests/historylist`: `DirXMLDev REST | 20260916-2 | requestState 2
   processState 3`.
4. `POST /index/permissions` with `ADD_OR_MODIFY` and with `REFRESH` (both
   undocumented — the vendor text says REMOVE only) returned `{"success":
   true}`; not yet proven to make a just-deployed PRD requestable at once
   (run it right after the next scratch deploy and re-submit).
5. Cross-checks: `POST /requests/permissions/item` with `dataItems` for the
   same PRD still answers "Internal exception occurred processing REST
   service" (legacy-form path — stays "not for JSON forms"); `GET
   /requests/history` answered 489 today (worked 2026-09-15) — `historylist`
   is the reliable one; the index search `GET /permissions?q=*&type=PRD`
   returns 0 rows for uaadmin on this lab even for stock PRDs.
6. Clean-up: `prd.delete`, `form.delete`, `vault.deploy` (2 deletes), `vault.diff`
   empty. The request and its task stay in the applications' history, as the
   W4 ones do.

## Facts recorded

→ `reference/facts.md` "Identity Applications REST" (the v2 body, the index
interval, `/index/permissions` operations, `item` vs `v2`, `historylist`);
`docs/idapps-rest.md` is the full reference with required fields and accepted
values. Recipe "Prove it in the Identity Applications" now runs over REST.

## Tool gaps found on the way

- `form.field.add --json '{…}'` is parsed as the global `--json` output flag
  ("unexpected argument"); the field's extra-properties flag needs another
  name (`--props`?) — follow-up in `docs/plan.md`.
