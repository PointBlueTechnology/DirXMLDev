# Event store: design note

Status: **decided 2026-10-07** (Jerry took the proposals in section 9). Nothing is built yet; it waits on the logger's 2.0.0 release. The
overall plan is DirXMLDevWeb's `docs/event-logger.md`; this note is the core's half (C1 there).

The store is the PostgreSQL table the
[DirXMLEventLogger](https://github.com/jcombs-pointblue/DIrXMLEventLogger) driver writes:
every subscriber-channel event as JSON plus the raw XDS, and, through `PolicyLogger`, any
document a policy chose to log at a named point of its channel. The contract is the logger's
`docs/store.md` (schema version 2, release 2.0.0). The core reads it, never writes it, and turns
rows into simulator cases in the tree.

## 1. What the core adds

| Piece | Kind | What |
|---|---|---|
| `deploy/EventStore` | class | JDBC reader over one environment's store: connect, the queries of section 3, row → `Event`, close |
| `model/Event` | record | `id, eventId, className, srcDn, srcEntryId, type, time, srcDriver, channel, policy, stage, schemaVersion, json, xml` |
| `query events` | read command | list rows: by object, subtree, name, driver, policy, event id, time window, text; JSON or table |
| `query event <id>` | read command | one row with its JSON and XML, and the other rows of the same engine event |
| `events.case` | edit operation | writes `cases/<name>/{case.properties,input.xds}` from a row; the tree's only write |
| `<env>.events*` | environment keys | where the store is and how to bind, read-only |
| `EventXml` | helper | the XDS from a row: `xmlevent` when present, else rebuilt from the JSON (marked lossy) |

No validation check, no deploy, no writes to the store. The web's Events view and "save as case"
sit on these; the agent gets the same through the MCP server's read commands and the operation.

## 2. Environment settings

Beside the vault bind in the environments file; every key optional, the environment has a
store only when `eventsUrl` is set.

```
edir3.eventsUrl=jdbc:postgresql://db.example:5432/idmEvent
edir3.eventsUser=eventlogger_reader
edir3.eventsPasswordKeychain=idm-edir3/events      # or eventsPassword=, eventsPasswordEnv=, eventsPasswordCommand=
edir3.eventsTable=public.dxmlevent                 # default
edir3.eventsPseudonymise=true                      # default false; see section 5
edir3.eventsTree=EDIR3                             # default: from the vault; the tree name DNs in the store start with
```

The password resolves through the same four sources the vault password has (`Environments`
already has the parser; the events password reuses it with the `events` prefix). The reader
account is the logger's `eventlogger_reader`; the core opens the connection with
`readOnly=true` as well, so a mistaken account still cannot write.

`doctor` probes the store when the environment has one: connect, `SELECT max(schemaversion)`,
row count, newest `cachedtime`; it warns on schema version 1 rows (section 6) and on a table
without the `policy` column (an unmigrated store).

## 3. Queries

Each is one of the contract's patterns, with bound parameters, always with a `LIMIT`
(default 100, `--limit`, capped at 1000) and `ORDER BY cachedtime DESC, id DESC` unless the
pattern orders otherwise. Selectors combine with AND:

| Selector | SQL | Note |
|---|---|---|
| `--dn <dn>` | `srcdn = ?` | the object's timeline, ordered ascending |
| `--under <container dn>` | `srcdn LIKE ? ESCAPE ''` with `\` appended | subtree, prefix index |
| `--name <rdn or tail>` | `reverse(srcdn) LIKE reverse('%' \|\| ?) ESCAPE ''` | an object by its name, reverse index |
| `--driver <name or dn>` | `srcdriver = ?` | a tree driver name resolves to its DN through `VaultMapping.driverDn` |
| `--policy <name>` | `policy = ?` (with `--driver`) | PolicyLogger rows at that policy; `--stage input\|output` |
| `--own` / `--logged` | `policy IS NULL` / `policy IS NOT NULL` | the driver's own rows, or what its policies logged |
| `--type add,modify,…` | `eventtype IN (…)` | |
| `--class User` | `classname = ?` | |
| `--since <iso>` `--until <iso>` | `cachedtime >= ? AND < ?` | `--since 24h` and `7d` as shortcuts |
| `--event-id <id>` | `eventid = ?` ordered `policy NULLS FIRST, id` | the driver row then each policy stage |
| `--text <s>` | `eventjson::text ILIKE ?` | refused without `--since` or another indexed selector on a store over 100k rows |
| `--attr <name>` | `eventtype='modify' AND eventjson->'attributes' ? ?` | modify events that touched an attribute |

DNs in the store are the engine's slash form (`\TREE\data\users\jdoe`). The commands accept
slash or LDAP form and convert with the tree name of section 2; output keeps the store's form
and adds the LDAP form, the way `VaultMapping` does elsewhere.

Output: a table (`id, time, type, class, src-dn, driver, policy/stage`) or `--json`, the row
with `eventjson` parsed, `xmlevent` omitted unless `--xml`. `query event <id>` prints the JSON,
the XML (or the rebuilt one with `reconstructed: true`), and for a modify the attribute changes
as `attr: removed → added`, which is what the web's diff view renders.

## 4. `events.case`

```
idm events.case --env edir3 --id 4711 --name "ad-user-add-sample" [--driver "Active Directory Driver"] [--channel subscriber] [--run]
```

- The row's XDS becomes `cases/<name>/input.xds`. From `xmlevent` when present; else rebuilt
  with the logger jar's `JsonToXmlConverter` (an optional dependency, section 7) and the case
  gets `reconstructed=true`.
- `case.properties` gets `driver=` (default: the row's `srcdriver` mapped back to a tree driver
  name; refused if the store's driver is not in the tree and no `--driver` is given),
  `channel=` (default: the row's `channel`, else `subscriber`), `source=events:<env>:<id>`,
  `sourceEventId=`, `sourcePolicy=`/`sourceStage=` for PolicyLogger rows, and
  `pseudonymised=true` when section 5 applied.
- The directory is the tree's `cases/` (decided in the web note); `--dir` writes elsewhere for a
  sample that must not be committed.
- A case that exists is refused unless `--replace`.
- `--run` runs it through the simulator at once and records the golden
  (`expected-output.xds`), the same as the web's "run and record".

It is an edit operation so it goes through the review like every tree write: the web shows the
case files as the diff before applying, and the MCP server exposes it with the others.

## 5. Pseudonymisation

With `eventsPseudonymise=true` (production stores, or any store the operator says holds real
people), the vault clone's `Pseudonymiser` is applied to what leaves the core: `src-dn`,
`old-src-dn`, `qualified-src-dn`, the association value, and the values of the person
attributes it already knows (given name, surname, full name, mail, telephone) in both the JSON
and the XML, consistently within one process so a timeline still lines up. Selectors still take
real names (the store is queried as is); only output is masked. A case saved from a masked row
is masked and says so. This is the clone's existing facility, so a name maps the same way in a
cloned tree and in its events.

## 6. Schema versions

The core reads schema version 2. Rows with `schemaversion = 1` are listed and opened, with the
contract's version-1 differences handled where they matter: modify values that are whole-element
text are shown as text, rename rows without `new-name` take it from `src-dn`, move rows take the
parent association from `parent.value`. `events.case` from a version-1 row needs `xmlevent`
(the version-1 JSON cannot be rebuilt faithfully) and is refused otherwise. A store whose
`max(schemaversion)` is above 2 is refused with the version named; the core's release notes say
which version it reads.

## 7. Dependencies and shipping

- `org.postgresql:postgresql` 42.7.x (BSD-2) becomes the core's first non-provided runtime
  dependency. The launcher gains it on the classpath the way it has the simulator jar (from
  `~/.m2` by the pom's version); `hosted/docker/build.sh` and `image.yml` in the web gather it
  with the other jars; the core's release attaches it beside `dirxml-dev-<v>.jar` so a
  no-build install has it.
- The logger jar (`com.pointblue.idm:dirxml-event-logger` 2.0.0) is **optional**: only
  `JsonToXmlConverter` is used, only for rows without XML. Loaded by name; when absent, those
  rows cannot become cases and the message says which jar to add. It is not bundled, so the
  core never depends on the logger's engine-jar `provided` scope.
- Nothing proprietary: the store holds XDS text, and the core already handles XDS without the
  engine on the classpath.

## 8. Tests

- Unit: the selector → SQL builder (every selector, combinations, escaping of `\`, `%`, `_`,
  the limit cap); row → `Event` mapping and the version-1 adjustments from fixture rows taken
  from the contract's examples; `EventXml` for rows with and without XML; `events.case`
  against a synthetic tree (files written, properties, refusals, the pseudonymised variant).
- Live: `bin/smoke-events.sh` starts the logger's `docker/compose.yml` database when Docker is
  present, loads the contract's example rows, and runs `doctor`, every `query events`
  selector and `events.case --run`; skipped with a notice otherwise. CI runs the unit tests;
  the live script is for the lab.

## 9. Decisions (taken 2026-10-07)

1. **The JDBC driver is a normal dependency** shipped with the core: on the launcher's classpath
   from `~/.m2` by the pom's version, gathered into the hosted image, attached to the release.
2. **The caller names the case**; the web suggests `<driver>-<type>-<yyyymmdd-hhmm>`.
3. **`--text` is refused without an indexed selector** on a store over 100k rows.
4. **Pseudonymise by the store's flag** (`eventsPseudonymise=true`), not by tier.
