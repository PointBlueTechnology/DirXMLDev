# Identity Console gaps: design note

Status: **decided 2026-10-09** (section 5); J1, J2, R1, M1 built and M2 in progress (G5, G12, G11 built; G8 next). Basis: *DirXMLDev CLI vs Identity
Console API: Capability Gaps* (2026-10-09, 208 `edirapi` routes against DirXMLDev 0.18.0). The
first set — **G3** association and object inspection, **G4** password-sync diagnostics, **G6**
queue/submit event, **G10** the live start option — is built (`docs/operate.md`, release 0.19.0)
and exposed in DirXMLDevWeb (the Drivers tab's start option, *objects* per driver, the Vault
view's Objects tab). This note is the rest.

## 1. Jobs (G1) — model work

**What the vault holds** (edir3, idm254, ig4 read 2026-10-09): `DirXML-Job` objects under a
driver *or* the driver set (`cn=StatisticsJob,cn=driverset1,o=system`; `cn=process ent
Refs,cn=CyberArk,…`), with `XmlData` (the job's configuration document: the job class, schedule,
parameters, scope), `DirXML-ServerList` (which servers run it), `DirXML-Scope`, and the trace
settings `DirXML-TraceLevel` / `DirXML-TraceFile` / `DirXML-TraceSizeLimit`; a `DirXML-uiXML…`
Designer artefact beside it. A `DirXML-TelemetryJob` object sits at the set level (the engine's
own, not ours to model). Designer keeps jobs under `Idm:Jobs`; an export carries them under
`<jobs>`. The readers today count them and drop them.

**Model.** `Job { name, dn, owner (driver | driver set), configXml (XmlData), scope,
servers[], traceLevel, traceFile, traceSizeLimit }` on `Driver.jobs` and `DriverSet.jobs`.

**Tree layout.** `drivers/<driver>/jobs/<name>.xml` and `jobs/<name>.xml` beside the set's
files: the `XmlData` document with the other attributes as a small header element, the way
entitlements are kept (`entitlements/<name>.xml`). Round-trips through the Designer project and
export (`Idm:Jobs`, `<jobs>`), the vault (`import-live`, LDIF) and the clone.

**Validation.** `job-unknown-server` (a server not in the set's list), `job-no-schedule`, a
schema check of the configuration document against the engine's job DTD when present in the jar.

**Deploy.** `JOB_ADDED / JOB_REMOVED / JOB_CHANGED` in `vault.diff`; add and modify as the
attributes above; removal only through `--delete-all jobs` (as entitlements). `vault.verify`
re-reads them.

**Operate.** `job.list --env E [--driver D]` (name, owner, schedule, servers, last run and
status from the job's own status attributes), `job.start --env E --job J --yes`,
`job.abort …`, `job.status …` — the engine's job extended operations (`StartJob`, `AbortJob`,
`GetJobStatus`, the console's `startJob`/`abortJob`/`getJobStatus`; to confirm in the jar as the
cache ops were). The web: a **Jobs** panel on the Drivers tab.

## 2. Role-based entitlement policies (G2) — model work, to ground first

The console's 15 RBE routes manage the policies of the **Entitlements Service driver**: a
policy's membership (static members, a dynamic filter), the entitlements it grants, priorities.
**No lab vault holds one** (edir3, idm254 and ig4 have no RBE policy objects, read 2026-10-09),
so the object class and attribute shape are not grounded yet; Designer's export carries them under
`<rbe-policies>` and the readers count them.

**Plan.** Ground first: a lab with the Entitlements Service driver and one policy (edir3 can take
it), read its objects, then model as jobs. *Grounded 2026-10-09 (section 9): the policies do not
hang off the driver — they live in one container at the driver set — so the layout is
`rbe-policies/<name>.xml` beside `driverset.xml`, not under the driver.* Operate: `rbe.list`,
`rbe.members`; the console's policy-driver stop/restart is `driver.stop/restart` already.

## 3. Medium gaps

| Gap | What | How |
|---|---|---|
| **G5** migrate into the application | the console's `migrateFromNDS` with `migrateStatus`: the engine reads the vault objects of a query and sends them through the subscriber channel | `driver.migrate --direction app\|vault` (today's `MigrateApp` stays the default; `MigrateFromNDS` is the other verb — to confirm in the jar), the same gate; `--status` polls |
| **G7** driver health | `DirXML-DriverHealth…` state and the health-configuration job; clearing the status, triggering the actions | `driver.health --env E --driver D` (read), `driver.health clear --yes`; the health configuration is a job (section 1) |
| **G8** e-mail server, notification templates | SMTP settings on the set (`DirXML-…`), templates as `notfTemplate` objects (clone carries them) | `notify.templates` as tree objects `templates/<name>.xml` with diff/deploy; `vault.email-server show\|test` for the SMTP options and the console's test send |
| **G9** query the connected system | `queryValues`: a query through the driver to the application | `driver.query --env E --driver D --class C [--dn …] [--attr …]` through the engine's query verb; the web's Objects tab gets "ask the application" beside the vault's answer |
| **G11** key and keystore passwords | `keyPassword`, `keystorePassword` beside `appPassword`, `rlPassword` | two more secret kinds in the inventory and `driver.secrets`: `key`, `keystore` |
| **G12** log level, log events | `DirXML-LogLevel` and the audit event selection on drivers and sets | read into the tree (`driver.xml` header), diffed and deployed like the trace settings; `driver.log-level` live |

## 4. Low gaps

Driver-set activation, server association and server-specific attributes live
(`driverset.activation`, `driverset.servers add|remove`), excluded objects and work orders as
tree objects, the metrics routes as `engine.metrics`, and effective rights as a `doctor` check of
the driver's security-equivalent identity. Each is one read or one write on an attribute the
clone already carries; they follow the medium set.

## 5. Decisions (taken 2026-10-09, "proceed as you suggested")

1. **Jobs first** (G1), then RBE policies once grounded (G2).
2. **Tree layout** for jobs as proposed: `drivers/<driver>/jobs/<name>.xml`, `jobs/<name>.xml`
   at the set, mirroring entitlements.
3. **RBE grounding on edir3**: the Entitlements Service driver with one policy, read from a real
   object before modelling.
4. **Medium set in the order** G9, G7, then G5, G12, G11, G8.

## 6. Phases

| Phase | What |
|---|---|
| J1 | `Job` model, readers (project, export, live, LDIF, clone), tree layout, validation |
| J2 | diff and deploy of jobs; `job.*` operate commands; the web's Jobs panel |
| R1 | RBE grounding on edir3; model, readers, layout, diff, deploy |
| M1 | G9, G7 |
| M2 | G5, G12, G11, G8 |
| L1 | the low set as needed |

## 7. As built (J1)

- `model/Job`: name, the configuration document (`<job-aggregation>` or Designer's
  `<job-definition>`), `servers`, `scopes`, meta (`trace-level`, `trace-file`, `trace-size-limit`,
  `dn`, Designer id and parameters, package stamps); `javaClass()`, `disabled()`, `displayName()`.
  `Driver.jobs` and `DriverSet.jobs`.
- Readers: the vault and an LDIF (`DirXML-Job` under a driver or directly under the set; under an
  unknown driver, dropped), a Designer project (`Idm:Jobs` on the set and each driver; a `.Job_`
  CObject's `contents`, its `IdmParameter:*`, its `Idm:JobServers` as server names — a project
  records no server DN), an export (`<jobs><job name=… trace-level=…><server dn/><scope value/>
  the document</job></jobs>` at the set and under a driver's children; Designer's own shape for
  jobs in an export is still to be seen — the reader also takes any `<job>` child).
- The tree: `drivers/<driver>/jobs/<name>.xml`, `jobs/<name>.xml`, the manifest `<job>` entries;
  written back to an export the same way. `validate`: `jobs` check (section 1's codes).
- Verified: `import-live` of edir3 puts `drivers/CyberArk/jobs/process ent Refs.xml` and
  `jobs/StatisticsJob.xml` in the tree; `validate` says the first is disabled.
- Not yet: diff, deploy, the `job.*` operations, the web's Jobs panel (J2).

## 8. As built (J2)

- `vault.diff`: `JOB_ADDED` / `JOB_REMOVED` / `JOB_CHANGED` (paths `drivers/<d>/jobs/<name>`,
  `jobs/<name>`), comparing the document, the servers, the scopes and the trace settings; no
  driver restart (the scheduler is told instead). A removed job needs `--delete-all jobs`.
- `vault.deploy`: `ADD` with `DirXML-Job` and every attribute, or one `MODIFY` per attribute, then
  a `NOTIFY_JOB` step (`NotifyJobUpdate`) so the engine's scheduler re-reads the object; the
  snapshot covers the job DN; `vault.verify` re-reads it like everything else.
- `job.list`, `job.status`, `job.start`, `job.abort` (docs/operate.md); the web's Vault view gets
  a **Jobs** tab, the outline and the Developer tree list jobs.
- Not yet: writing jobs into a Designer project (`ProjectWriter`) — read works; `CheckJobConfig`
  and `DiscoverJobs` (the console's job wizard) are not offered.

## 9. As built (R1): role-based entitlement policies

**Grounding (2026-10-09).** No lab held a policy, so the shape was taken from three sources and then
proven live on edir3: Designer's RBE editor and deploy code (`com.novell.idm.rbe`,
`DeployRBEContainer`/`DeployRBEPolicy`, `RBEPolicyImpl`), the Entitlements Service shim itself
(`EntitlementServiceShim.jar`, `Directory.cacheEntitlementPolicies` / `checkPriorities`), and the
vault schema. Then the RBE base package (`NOVLRBEBASE` 2.0.0, fetched into the catalog) built an
Entitlements Service driver on edir3 with `driver.add`, one policy was written with `vault.deploy`,
the driver started and cached it (trace: *number of policies cached: 1*), the policy was changed,
removed with `--delete-all rbe-policies`, and the driver deleted again. What the vault holds:

- **One container per driver set**, `cn=Entitlement Policies,<driver set>` (`DirXML-SharedProfileSet`;
  iManager's and Designer's name). Its `DirXML-SPPriority` typed names (`<policy dn>#<level>#0`) order
  the policies. The shim finds it by class under the driver set and reads only this attribute.
- **Each policy** is a `DirXML-SharedProfile`, a dynamic group: `Description`; `memberQueryURL`
  (NDS name `memberQuery`: `ldap:///<base>??<one|sub>?<rfc2254 filter>?x-sparse` — eDirectory
  re-cases the DN components, so the diff compares it case-folded); `dgIdentity`; `Member` and
  `excludedMember` (static); `DirXML-SPFilterXML` (the editor's `<selection-criterion>`, groups of rows
  with `AS.Op.*` operations); `DirXML-EntitlementRef` path values `<entitlement dn>#0#<ref>…</ref>`
  (what it grants — the shim reads these, not the display document); `DirXML-SPDisplayEntitlements`
  (Designer's `<Drivers><Driver><Entitlement>…` display copy; Designer derives the refs from it at
  deploy time, so an export without refs gets them derived the same way). A non-empty legacy
  `DirXML-SPEntitlementsXML` makes the shim refuse the policy ("unconverted policy").
- **The shim's rules**, now validation errors: every policy needs exactly one priority entry and the
  levels must run 0, 1, 2 … with no gap, or the driver refuses to start ("Entitlement Policy
  priorities are non-sequential"). The shim caches policies at start, so a policy change restarts the
  Entitlements Service driver (the one whose shim is
  `com.novell.nds.dirxml.driver.entitlement.EntitlementServiceDriver`); the console's
  `/rbe/restartRBEDriver` exists for the same reason.

**Built.**

- Model: `EntitlementPolicy` (name, description, memberQuery, identity, criteria, members,
  excludedMembers, entitlementRefs, displayEntitlements, priority, meta); `DriverSet.rbePolicies`,
  `rbeContainerName()` (meta `rbe.container` when not the default), `entitlementServiceDrivers()`.
- Tree: `rbe-policies/<name>.xml`, one `<rbe-policy name priority>` document holding everything;
  the manifest's `<rbe-policy name file>` entries (docs/tree-layout.md).
- Readers: the vault and an LDIF (`DirXML-SharedProfileSet` under the set, its `DirXML-SharedProfile`
  children), Designer's export (`<rbe-policies>` holding the container `ds-object` with nested policy
  `ds-object`s, the two XML attributes base64 as Designer writes them; both the deploy shape's
  `<typed-name-level>` and the import shape's `<rbe-priority>` are read), a Designer project
  (`Idm:RbePolicies` on the driver set — from Designer's model code; no project with a policy was at
  hand, so this one is unverified), and back to an export.
- `validate`: `rbe-name-blank`, `rbe-legacy-entitlements-xml`, `rbe-no-priority`,
  `rbe-duplicate-priority`, `rbe-priorities-not-sequential` (errors); `rbe-no-membership`,
  `rbe-no-entitlement`, `rbe-unknown-entitlement`, `rbe-no-service-driver` (warnings).
- `vault.diff`: `RBE_ADDED` / `RBE_REMOVED` / `RBE_CHANGED` (`rbe-policies/<name>`); a change of the
  priority or the display document alone is `settings`. `vault.deploy`: the container is created when
  absent, an added policy is one add (`Top`, `DirXML-SharedProfile` — the directory supplies the
  dynamic-group superclasses), a changed one a modify per attribute (a dropped attribute is cleared),
  the container's `DirXML-SPPriority` is rewritten once after the policy steps, a removal is held by
  the empty-kind guard until `--delete-all rbe-policies`; the Entitlements Service driver restarts
  when running. Scoped to `--driver <the Entitlements Service driver>`, the policies travel with it.
- Operate: `rbe.list --env E` (priority, member count the directory computes, grants, the query),
  `rbe.members --env E --policy P` (the computed members). Read-only.

**Left open.** `driver.add` from the RBE base package creates the driver but no server association
is needed for it to run (the engine runs every driver of the set); what it did need was a priority
list — the first start failed on `priority=1` for the only policy, hence the sequential-from-0 rule
above. The web has no policies panel yet (an R2, with the outline and the Developer tree). The
Designer project reader for policies is from the model code only. `dgIdentity` is left to the author:
the lab policy ran without one.

## 10. As built (M1): the application query and driver health

**G9 `driver.query --env E --driver D [--class C] [--scope subtree|subordinates|entry] [--dn DN]
[--association A] [--search name=value…] [--read-attr A…|none]`.** The engine's query verb, sent as a
command into the running driver's subscriber channel (`SubmitCommand`, the same door `driver.submit`
uses): `<query class-name scope dest-dn>` with `<search-class>`, `<search-attr>` and `<read-attr>`
children; the shim answers `<instance class-name src-dn><association/><attr attr-name><value/>`,
printed one instance per block and as JSON. The verb takes DNs in slash form, so an LDAP `--dn` is
converted (`cn=x,ou=users,o=data` → `data\users\x`). Proven on edir3 through the Loopback driver,
whose shim answers from the vault: a search by CN under `ou=users,o=data` returned the user with its
Surname and Given Name, an entry-scope query with `--read-attr none` returned the bare instance. A
stopped driver is refused up front. Gated light and audited: it reads the application, but it takes
the driver's channel to do so.

**G7 `driver.health [clear] --env E --driver D`.** Grounded on the Driver Health job itself
(`ckdrvhealthjob.jar`, `CheckDriverHealthJob`, read 2026-10-09): the job runs at the driver set,
scoped to drivers; it reads each driver's health configuration from the driver's
`DirXML-ConfigManifest` (`<health-config>` with `<green>`, `<yellow>`, `<red>` and `<custom-state
unique-id>` elements, each conditions plus `<actions>`), evaluates it per server, and writes the result
to the driver object's `DirXML-uiXMLSmall` (adding the aux class `DirXML-uiExtensions`):
`<dirxml-ui><health-config-status><last-state><driver dn><server dn last-state="green|yellow|red"/>` and
one `<custom-state unique-id>` block per custom state with true/false. `driver.health` prints the three:
the last state per server, the configured states with their condition-group and action counts, and the
set's health jobs with whether this driver is in their scope (or that nothing evaluates it). `clear`
removes `DirXML-uiXMLSmall`, which is what the console's `clearDriverHealthStatus` amounts to. No lab
driver carries a health configuration yet, so the read was checked against the job's own document
shapes in tests and live only for the empty case.

**Left for later.** The health configuration is not in the tree: the model does not carry
`DirXML-ConfigManifest`, so `<health-config>` is read live only (modelling the manifest is its own
piece). The health job's actions (start, stop, restart, clear the cache, send e-mail, a workflow) are
listed by count, not by kind. The web's Objects tab is to get "ask the application" beside the vault's
answer (an R2/M1b web change). Custom shims that are not installed on a lab engine refuse to start,
which is the engine's doing, not the query's.

## 11. As built (M2, first part): migrate into the application, the log level, the extra secrets

**G5 `driver.migrate --direction vault --base DN --filter F --class C [--max N] [--dry-run]`.** The engine
has no verb for it: the console's `migrateFromNDS` searches the vault itself and then migrates object by
object (`migrateObject`, with `migrateStatus` for the progress). The command does the same: an LDAP search,
then one `<sync class-name src-dn>` command per object through the running driver's subscriber channel
(`SubmitCommand`), which makes the engine read the object and send its add or modify to the shim; each
object's status is reported and the run is audited as one line. `--dry-run` lists the objects. The old
`driver.migrate --xds` (the engine's `MigrateApp`, from the application) stays as `--direction app`, the
default when `--xds` is given. Not proven live: the only lab driver that would run safely is a Loopback
whose publisher policy writes an association back onto the user, so the live check stopped at the dry run.

**G12 `driver.log-level [set] --env E [--driver D]`.** Grounded on Designer's log level page
(`com.novell.idm.config`, `LogLevelComposite`, and `DSUtil.LOG_LEVEL_n_EVENTS`, read 2026-10-09): the
page's radio lands in `DirXML-DriverTraceLevel` — 0 log errors, 1 errors and warnings, 2 only update the
last log time, 3 logging off, 5 log specific events (6 was XDAS events before 4.8) — and the event ids it
implies in `DirXML-LogEvents` (0 → 4 5 38; 1 → 3 4 5 35 38 39; 2 → -1; 3 → none). That attribute is
engine-written, so it is set through `SetLogEvents` / `ClearLogEvents`. `DirXML-LogLimit` is the most
log entries kept (0 turns the set's logging off), `DirXML-LogEventsType` the page's format choice. A driver
without its own values uses the driver set's; `--inherit` clears a driver's four. The command shows all of
this for a driver or the set and sets it live; `--level specific-events` needs `--events`. The trace
settings were never in the tree (they are operational, `driver.trace`), and the log level follows them
rather than the note's earlier idea of a tree attribute.

**G11 `driver.secrets set|remove --kind named|shim-auth|remote-loader|key|keystore`.** The engine's
`SetRemoteLoaderPassword`, `SetMutualAuthKeyPassword` and `SetMutualAuthKSPassword` (and their Clear
operations) are what the console's `rlPassword`, `keyPassword` and `keystorePassword` call. Two new
secret kinds, `<driver>.mutual-auth-key-password` and `<driver>.mutual-auth-keystore-password`, join the
inventory as optional needs of a Remote Loader driver (no missing-secret note when absent); the deployer
sets all three where before it skipped Remote Loader passwords as unsupported.

**Left for later.** G8 (the e-mail server and the notification templates). The event ids' names (the
engine's audit event table) are shown as numbers. The migrate-into-application path is tested against the
fake engine only.

