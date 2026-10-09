# Identity Console gaps: design note

Status: **decided 2026-10-09** (section 5); J1 in progress. Basis: *DirXMLDev CLI vs Identity
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
it), read its objects, then model exactly as jobs: `EntitlementPolicy { name, dn, members[],
dynamicFilter, entitlements[], priority }` under the Entitlements Service driver,
`drivers/<driver>/rbe-policies/<name>.xml`, diff and deploy, Designer and export round-trip.
Operate: `rbe.list`, and the console's policy-driver stop/restart is `driver.stop/restart`
already.

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
