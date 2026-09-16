# Identity Applications REST — the calls an agent needs, with their bodies

The vendor reference (`…/rest-api-documentation/4.10-docs/idmappsdoc/`, a
Swagger UI over `swagger.json`, 264 paths) names every endpoint but its body
schemas are empty: `SinglePermReqNode`, `RequestNode` and the v2 payload have
no fields, no required marks, no value lists, no examples. This page records,
for the endpoints the tool and its live proofs use, **what the server actually
reads**: which fields, which are required, the accepted values, and a body
that worked on idm254 (Identity Manager 4.10.1 / Identity Applications 24.4).
Source of each fact: **[live]** = exercised on idm254 on 2026-09-16
([spikes/prd-rest-live.md](spikes/prd-rest-live.md)); **[server]** = read from
the applications' request-handling code (field names are the JSON names it
binds; enumerations are the values it compares against); **[dashboard]** =
what the dashboard or the JSON form renderer sends.

`bin/apps` (Python 3, standard library) wraps the calls below — see
[`.claude/skills/dirxml-dev/reference/commands.md`](../.claude/skills/dirxml-dev/reference/commands.md#identity-applications-binapps).

## 1. Base URL, authentication, errors

| | |
|---|---|
| Base | `https://<apps host>/IDMProv/rest/access` for everything below (the dashboard's `_restAccess`). Catalog calls (`/prds`, roles, resources) live under `/IDMProv/rest/catalog`; caching/admin under `/IDMProv/rest/admin`. A path called under the wrong base is a Tomcat 404 **[live]**. |
| Token | `POST https://<apps host>/osp/a/idm/auth/oauth2/token`, header `Authorization: Basic base64(<client>:<client secret>)`, `Content-Type: application/x-www-form-urlencoded`, body `grant_type=password&username=<user>&password=<password>`. Response `{"access_token","token_type":"bearer","expires_in","refresh_token"}`. **Required:** all three body fields and the client credentials. Client ids that work: `rbpmrest` (the documented REST client) and `rbpm` (the dashboard's own); on the lab both accept the lab's client secret **[live]**. |
| Every call | `Authorization: Bearer <access_token>`, `Content-Type: application/json`, `Accept: application/json`. |
| Errors | HTTP **489** (application error) or **400** (input rejected by the XSS filter) with `{"Fault":{"Code":{"Value":"Sender","Subcode":{"Value":"<code>"}},"Reason":{"Text":"…"}}}`. Subcodes seen/read: `PermissionIndexException` (permission not in the index), `InternalExceptionOccured`, `InvalidInput`, `SecurityException`, `NotFound`, `InvalidEnumValue` (a bad task action), `XSSValidationFailure` (400) **[live/server]**. A 200 with `"success": false` and no `Fault` is also a failure — see §2. |

## 2. Start a PRD (JSON request form) — `POST /requests/permissions/v2` **[live]**

This is the call the JSON form renderer's *Request* button makes
(`util.post('IDM','/rest/access/requests/permissions/v2', requestPayload)`)
**[dashboard]**. It is the one that works for a PRD whose request form is a
JSON form; `…/permissions/item` (§8) does not.

```json
{
  "reqPermissions": [
    {"id": "cn=DirXMLDev REST,cn=RequestDefs,cn=AppConfig,cn=User Application Driver,cn=driverset1,o=system",
     "entityType": "PRD"}
  ],
  "data": [
    {"key": "reason", "value": ["REST live proof 2"]}
  ],
  "recipients": [
    {"dn": "cn=uaadmin,ou=sa,o=data", "type": "user"}
  ]
}
```

| Field | Required | Accepted values / meaning |
|---|---|---|
| `reqPermissions` | **yes**, non-empty (else 489 "permission list empty") | Only the **first** entry is used **[server]**. |
| `reqPermissions[0].id` | **yes** | The PRD's DN (`cn=<name>,cn=RequestDefs,cn=AppConfig,cn=<UA driver>,…`). Must be a valid LDAP DN (else 489 SecurityException) and **must be in the permission index** (§3), otherwise the response is 200 `{"success": false, "OperationNodes":[{"success": false, "userDn": …}]}` with nothing logged **[live/server]**. |
| `reqPermissions[0].entityType` | **yes** | `"PRD"` (compared case-insensitively). Roles/resources are not handled by this endpoint — use §8. |
| `reqPermissions[0].proxyFor` | no | A user DN: request *on behalf of* that user (the caller becomes the proxy). The renderer sends it only in proxy mode. |
| `data` | no, but required in practice | One entry per request-form field: `key` = the form field's key (matched **case-insensitively** against the PRD's request data items; unknown keys are ignored), `value` = **always a list** (a multi-valued field sends several elements) **[server/dashboard]**. Omitted items keep the PRD's default. |
| `recipients` | no | `[{"dn": <DN>, "type": "user"|"group"|"team"}]`. Absent → the caller is the recipient. `group` expands to the group's members (or, for a role admin with `groupAssignment`, assigns to the group); `team` expands to the team's members the caller manages **[server]**. One workflow instance starts **per recipient**. |

Response 200:

```json
{"success": true,
 "OperationNodes": [{"success": true, "userDn": "cn=uaadmin,ou=sa,o=data",
                     "succeeded": [{"id": "<PRD DN>", "requestId": "361da2b03d4c44a5990b1745b5c6a78d"}]}]}
```

`requestId` is the workflow **process id**: the same value the applications
log prints in `[Workflow_Started] … Process ID:` and that `historylist` (§7)
returns as `id` **[live]**.

## 3. The permission index — why a fresh PRD is "not there" **[live/server]**

The request endpoints and `POST /permissions/item` look the PRD up in an
in-memory **permission index** (roles, resources, PRDs), not in the vault. The
index is loaded in the background at start-up and each provider re-reads the
vault on a timer: `com.netiq.idm.cis.rbpm.updateInterval.prd` (or the generic
`…updateInterval`), in **minutes, default 10**. So a PRD deployed by
`vault.deploy` is requestable over REST only once that interval has passed (the
dashboard, which opens the form through its own session, showed it at once in
the W4 proof — the search path differs). Two ways to know / hurry it:

- `POST /permissions/item {"id": <PRD DN>, "entityType": "prd"}` → 200 with
  the detail (§4) when indexed, 489 `PermissionIndexException` "Permission with
  id […] does not exist." when not **[live]**.
- `POST /index/permissions` — the vendor doc says "only REMOVE is supported";
  the server accepts three operations **[server]**:

  ```json
  {"permIndexChanges": [{"operation": "ADD_OR_MODIFY", "permId": "<PRD DN>", "permType": "PRD"}]}
  ```

  | Field | Required | Accepted values |
  |---|---|---|
  | `operation` | **yes** | `ADD_OR_MODIFY`, `REMOVE`, `REFRESH` (upper-cased before comparing). `REFRESH` re-reads every permission of `permType` (all types when `permType` is absent — needs domain-admin rights for all three). |
  | `permId` | for `ADD_OR_MODIFY`/`REMOVE` | the permission's DN |
  | `permType` | for `ADD_OR_MODIFY`/`REMOVE` (else "invalid permtype") | `PRD`, `ROLE`, `RESOURCE` (case-insensitive) |
  | `eventId`, `timestamp`, `newPermId` | no | bookkeeping; `newPermId` for a rename |

  Rights: the caller must be a domain administrator for that type
  (Provisioning for PRDs). Response `{"success": true}` or
  `{"success": false, "failed": [{"reason": …}]}`. Both `ADD_OR_MODIFY` and
  `REFRESH` returned success on idm254 **[live]**; that they shortcut the
  10-minute wait for a *just-deployed* PRD is not yet proven (the test ran
  after the interval had elapsed) — check it on the next scratch deploy.

`GET /permissions?q=*&type=PRD&nextIndex=1&size=50` (the index search; `type`
repeats, values `ROLE`/`RESOURCE`/`PRD`; `column` selects response attributes;
`sortByName`; `proxyUser`) returned `arraySize 0` for `uaadmin` on idm254 even
for stock PRDs, on three occasions — do not use it as the "is it indexed?"
probe; use `/permissions/item` **[live]**.

## 4. PRD detail — `POST /permissions/item` **[live]**

Body `{"id": "<DN>", "entityType": "prd"}` (both required; `entityType` one of
`prd`/`role`/`resource`, case-insensitive; the detail is only implemented for
`prd` and `resource`). Response for a PRD: `id`, `name`, `desc`, `entityType`,
`categories` (display names), `requestFormId` (the JSON form's DN),
`isNewForm` (`true` = JSON form → the renderer at `/forms/`, `false` = legacy
`requestForm.do`), `edition` (index generation), `bulkRequestable`,
`multiAssignable`, `excluded`, `isExpirationRequired`, `link`, and for legacy
forms `dataItems[]` (`name`, `dataType`, `valueType`, `readOnly`,
`multiValued`, `values`, `target`, `source`).

## 5. Tasks — list, detail **[live]**

`GET /tasks/list` — query parameters, all optional:

| Parameter | Default | Accepted values |
|---|---|---|
| `fromIndex` | `1` | 1-based start (`0` also worked) |
| `size` | `10` | page size |
| `q` | `*` | search text |
| `sortOrder` | `asc` | `asc`, `desc` (case-insensitive; anything else → 489 "invalid sort order") |
| `sortBy` | `createTime` | a task-list column (checked against the dashboard's task columns; `createTime` is safe) |
| `status` | | task status filter, e.g. `Claimed` |
| `assignStatus` | | assignment filter (`assignedTo` / recipient-is-me style values) |
| `recipient`, `assignedTo` | | DNs |
| `expireWithin` + `expireUnit` | | number + unit |
| `proxyUser` | | act as a proxied user |
| `onlyHelpdeskTask`, `delegatedTasks` | `false` | booleans |

Response `{"tasks": [ … ], "nextIndex", "arraySize"}`; each task: `taskId`,
`processName` (the PRD's display name), `processId`, `activityName`,
`createTime`/`expirationTime` (epoch ms), `recipient`, `recipientName`,
`recipientType`, `initiator`, `initiatorName`, `approvalForm`,
`approvalFormId`, `isNewForm`, `bulkApprovable`, `taskClaimed`, `claimedby`,
`addressee`, `addresseetype`, `assignedTo`, `priority`, `confirmationNumber`,
`comments[]`, `dataItems[]`.

`POST /tasks/item` body `{"taskId": "<id>"}` (**required**; query
`includeSystemComments=false`, `noValues`, `commentLimit=100`) → the task with
its `dataItems` (values resolved). This is the call the dashboard makes when a
task is clicked; it fails with "Failed to get task details" when the approval
activity has no approval form bound ([workflows.md](workflows.md)).

## 6. Act on tasks — `POST /tasks` **[live]**

```json
{"tasks": [{"taskId": "a563c8e938fa4b48b0cc754ed3f5eed2"}], "action": "approve", "comment": "REST approve"}
```

| Field | Required | Accepted values |
|---|---|---|
| `tasks[].taskId` | **yes** | from `/tasks/list` |
| `action` | **yes** | `approve`, `deny`, `comment`, `refuse`, `claim`, `release`, `reassign`, `return` (upper-cased before matching; anything else → 489 `InvalidEnumValue`) **[server]** |
| `comment` | no (the dashboard always sends one) | free text |
| `approver` | for `reassign` | DN |
| `proxyUser` | no | act as a proxied user |
| `dataItems` | no | `[{"name","values":[…]}]` approval-form values for a legacy form |

Response `{"success": true, "succeeded": [{"id": "<taskId>"}], "failed": []}`.
`approve` and `deny` both proven (W4 and this spike). The engine logs
`Workflow_Claimed` then `Workflow_Approved`/`Workflow_Denied` for the call.

**JSON approval form path** — `POST /tasks/v2` **[dashboard/server]**, what the
renderer's Approve/Deny/Claim buttons send:
`{"taskId": "<id>", "taskAction": "ApprovalAction"|"DenyAction"|"ClaimAction", "comments": "…", "data": [{"key","value":[…]}]}`
(`data` keys = the approval form's field keys). Not exercised live yet; `POST
/tasks` with `action` is enough when the approval form carries no fields that
must be filled.

## 7. Request history **[live]**

`GET /requests/historylist?nextIndex=1&size=20&q=*` (also `sortOrder`,
`sortBy=requestDate`, `item`, `type`, `status`, `cn` (confirmation number),
`startDate`/`endDate` (epoch ms), `action`) → `{"requests": [ … ]}` with per
request: `id` (= the process id), `name`, `entityType`, `requestState`,
`processState`, `requestStatus`, `requestOper`, `requester`, `recipient`,
`recipientName`, `recipientType`, `requestDate`, `effectiveDate`,
`expirationDate`, `confirmationNumber` (`YYYYMMDD-n`). Observed states on
idm254: a completed approved request reads `requestState 2, processState 3`; a
denied one `requestState 1, processState 3`; a request that failed in the
engine (the NoApproval placeholder) `requestState 4, processState 2`.

`POST /requests/history/item {"id": "<process id>"}` → the request with its
comments (used in W4). `GET /requests/history?nextIndex=1&size=5` (the short
form) worked on 2026-09-15 and answered 489 "Failed to get process request
history" on 2026-09-16 — prefer `historylist`.

## 8. The other request endpoints — when to use which **[server/live]**

| Endpoint | Body | Use it for | JSON-form PRD? |
|---|---|---|---|
| `POST /requests/permissions/v2` | §2 | a PRD with a JSON request form | **yes** |
| `POST /requests/permissions/item` | `SinglePermReqNode`: `id`*, `entityType`* (`prd`/`role`/`resource`), `reason`, `effDate`/`expDate` (epoch ms), `sodJustification`, `permReqParams[{key,value}]` (resource parameters), `recipients[{dn,type}]`, `dataItems[{name,dataType,valueType,readOnly,multiValued,values,target,source}]` (legacy-form PRD data items), `proxyUser` | one role, resource or **legacy-form** PRD, one or many recipients | **no** — answers 200 `{"success": false, … "reason": "Internal exception occurred processing REST service"}` for a JSON-form PRD (three attempts, with and without `dataItems`) |
| `POST /requests/permissions` | `RequestNode`: `reqPermissions[{id,entityType}]`, `recipients[]`, `reason`, `effDate`, `expDate`, `sodJustification`, `process{…}`, `proxyUser` | bulk: many permissions × many recipients (roles/resources) | not for JSON-form PRDs |

`*` required. `entityType` values are case-insensitive everywhere
(`PermissionType.valueOfJson`).

## 9. Where the JSON form renderer lives (for completeness) **[dashboard]**

The dashboard opens a JSON request form in a new window at
`https://<apps host>/forms/#/form/details?id=<request form DN>&pid=<PRD DN>&sid=IDM&uri=/rest/access/forms&formContainer=RequestForms&locale=en&recipient=<DN>`
(approval: `formContainer=ApprovalForms`, `id=<taskId>`, `pid=<processId>`);
the renderer proxies every REST call through the browser session as
`/IDMProv/WFHandler?sid=IDM&uri=<rest path>`. That is why a browser session
can do what a bearer token cannot see (the index search in §3) — but the
request itself is the same v2 call.

## 10. Resource map (root paths under `/IDMProv/rest/access`) **[server]**

`/requests` (permission requests, history), `/permissions` (index search,
item), `/index` (index maintenance), `/tasks`, `/workflow` (PRD creation from
templates — not used; the tool authors PRDs as-code), `/forms` (form details,
`listWorkflowForms?formContainer=RequestForms|ApprovalForms|TemplateForms`,
`dataItemsList?formId=`), `/assignments`, `/teams`, `/users`, `/user`,
`/groups`, `/entities`, `/containers`, `/query`, `/gcv`, `/codeMap`,
`/delegation`, `/proxy`, `/rob`, `/availability`, `/sods`, `/cprs`,
`/helpdesk`, `/notifyService`, `/featuredItems`, `/ulp`, `/ulp/l10n`,
`/supportedlocales`, `/preference`, `/info`, `/counts`, `/queue` (cart),
`/dashboard`, `/orgChart`, `/relationships`, `/administration`, `/config`,
`/monitoring`, `/data/migration`.
