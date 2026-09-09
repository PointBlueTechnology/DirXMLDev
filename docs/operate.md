# Phase 5 design — operate

Status: **design** (2026-09-09). Follows [plan.md](plan.md) Phase 5; builds on
[vault-deploy.md](vault-deploy.md) (`Vault`, environments, tiers, secrets, the
audit log) and [spikes/extended-ops-api.md](spikes/extended-ops-api.md).

## What Phase 5 delivers

Day-two operation of a driver set from the same CLI, with the same environments,
gating and audit as deploy: see what every driver is doing, start / stop /
restart, look at and clear a driver's cache, migrate and resync, manage named
passwords, turn trace on and read it — without iManager, DxCMD or a shell on
the engine host.

```
idm driverset.status      --env stg                        every driver: state, start option, cache
idm driver.status         --env stg --driver "AD Driver"
idm driver.start|stop|restart --env stg --driver "AD Driver" [--wait N]
idm driver.cache view     --env stg --driver "AD Driver" [--out cases/ad-cache]
idm driver.cache clear    --env stg --driver "AD Driver" --yes
idm driver.migrate        --env stg --driver "AD Driver" --xds migrate.xml
idm driver.resync         --env stg --driver "AD Driver" [--since 2026-09-01T00:00:00Z]
idm driver.secrets list|set|remove --env stg --driver "AD Driver" [--name X]
idm driver.trace show|set|reset|tail --env stg --driver "AD Driver" [--level N] [--file F] [--lines N] [--follow]
idm engine.version        --env stg
idm engine.stats          --env stg [--driver "AD Driver"]
```

Everything is an extended operation or an LDAP attribute we already use;
`DxCacheReader` in the simulator already reads a cache. What's new is putting
them behind one surface with the deploy's discipline.

Not in Phase 5: jobs (`DirXML-Job` scheduling — read-only listing at most),
Remote Loader lifecycle (the RL process is outside the vault), `--delete-driver`
(still explicit-only, still deferred), activation and key management.

## Operations and what backs them

| command | extended op / attribute | notes |
|---|---|---|
| `driverset.status` | `GetDriverState`, `GetDriverStartOption` per driver; cache count via `ViewCacheEntries` (first page only) | one table; the thing an operator looks at first |
| `driver.status` | the same, plus trace level/file, named-password names, last audit entries for the driver | |
| `driver.start` | `StartDriver` → `waitForState(RUNNING)`; a start that falls back to STOPPED is reported as the engine refusing (check the trace) | |
| `driver.stop` | `StopDriver` → `waitForState(STOPPED)`; some shims take >60 s to stop (spike 1b) — default wait 180 s | the cache is kept; events keep queueing |
| `driver.restart` | `RestartDriver` (proven in spike 1b) | what deploy does |
| `driver.cache view` | `ViewCacheEntries` paged through the chunked-result protocol (as `DxCacheReader`); `--out` writes the events as a simulator case dir (`cache.xds` + `input.xds`), the same shape `bin/sim dxcache` produces | read-only; the driver may be running |
| `driver.cache clear` | `DeleteCacheEntries` — **its three middle parameters are unresolved** (spike report §9.1); **spike 5a** settles them on `Querytest` with a scratch queued event before the command exists | destructive: every queued event is discarded |
| `driver.migrate` | `MigrateApp(dn, xds)` with an XDS file (`<nds><input><query class-name=…>` as iManager's Migrate from Application builds it) | the driver must be running |
| `driver.resync` | `DriverResync(dn, since)` — epoch **seconds**; no `--since` = `Date(0)` = full resync | the driver must be running; a full resync of a big tree is a real load |
| `driver.secrets list/set/remove` | `List/Set/RemoveNamedPassword`; `set` reads the value from the environment's secrets file (`<driver>.named.<name>`) or `--stdin`, never from an argument | `vault.secrets` from the Phase 4 note lands here |
| `driver.trace show/set/reset` | `DirXML-TraceLevel` (int) and `DirXML-TraceFile` on the driver (spike 1b: the only driver-level trace attributes); `set` records the previous values in the audit line so `reset` can put them back (`--for 15m` resets automatically) | a trace change is a live change: the engine picks up trace level without a restart? — **spike 5b** checks (1b changed it while stopped) |
| `driver.trace tail` | `ssh <env.sshUser>@<env.sshHost> tail -n N [-f] <traceFile>` — the trace file is on the engine host, readable over the key-based SSH the environment names; `--grep` filters; `--since` = the last N minutes by the trace's own timestamps | read-only; this is how spike 1b proved engine pickup, made routine |
| `engine.version` | `GetVersion` (packed int → `DxConst.parseDirXMLVersion`) | |
| `engine.stats` | `GetDriverStats` per driver, and the engine's JVM stats if the ext op exposes them (`GetJvmStats` — to confirm in the jar); the "which driver is leaking heap" question from the test vault's ndsd deaths | read-only |
| `driver.submit` | `SubmitEvent` / `SubmitCommand` — **only after spike 5c** resolves why spike 1b's submitted event never reached the channels (the response is an ack; the event may need a running publisher / a different document shape) | the DxCMD Phase 2 canary: run the same event through the simulator and the live engine, compare |

## Safeguards

The same tiers as deploy ([vault-deploy.md](vault-deploy.md), "Environments and
gating"), applied by what an operation can break:

| operation | dev | stg | prd |
|---|---|---|---|
| status, cache view, trace show/tail, secrets list, engine.* | free | free | free |
| start, restart, trace set/reset, secrets set/remove | free | `--yes` | `--yes --confirm <env>` |
| stop, resync, migrate, submit | `--yes` | `--yes` | `--yes --confirm <env>` |
| cache clear | `--yes` | `--yes --confirm <env>` | `--yes --confirm <env>` and the cache viewed first in this session (`--i-viewed-it` is not a flag: `cache clear` prints the count and first/last event, then asks) |

Every state-changing operation appends to `deploy-log/<env>.jsonl` with
`operation: "operate"`, the command, the driver, the before/after state, and
for trace changes the previous level/file. `driver.cache clear` writes the
cleared events to `deploy-snapshots/<env>/<ts>-cache-<driver>.xds` first — a
cleared cache is recoverable as input (`driver.submit`, or a simulator case),
which is the closest thing to a rollback it has.

A driver's own trace is the ground truth for whether an operation did what the
ext op's bare "success" claims: `driver.start` and `driver.restart` end by
tailing the trace for the driver's startup lines when `sshHost` is configured
(`Found DirXMLScript policy …` for each linked policy, then the shim's
initialization), and report what they saw.

## Environments, extended

```properties
stg.sshHost=idm-stg            # the engine host, for driver.trace tail (key-based ssh)
stg.sshUser=root
stg.traceDir=/var/opt/novell/eDirectory/log   # where trace files land when a driver sets a relative name
```

## Build order

1. **`Vault` operate primitives** + **spikes 5a/5b/5c** on the test vault's
   `Querytest` driver: cache view (port `DxCacheReader`'s chunked read), cache
   clear (resolve `DeleteCacheEntries`' parameters with a scratch queued event),
   migrate / resync (shape only — Querytest has no application), trace attribute
   read/write and whether a level change is honoured live, `GetVersion` /
   `GetDriverStats` / JVM stats, `SubmitEvent` semantics (the open item from
   spike 1b). Findings → `spikes/operate.md`.
2. **`Operate`** (the command layer): gating by tier and operation class, audit
   lines, the wait/verify loops, cache-clear's snapshot; `driverset.status` /
   `driver.status` rendering (text + `--json`).
3. **`driver.trace tail`** over SSH (environment `sshHost`/`sshUser`), and the
   start/restart trace verification.
4. **`driver.cache view --out`** as a simulator case, `driver.secrets`, and the
   CLI (`OperateCli`) for all of the above.
5. **`driver.submit`** as the canary, if 5c says how; otherwise documented as
   still open.
6. Docs: agent guide "operate" section; the skill's missing-inputs table points
   at `driver.cache view --out` and `driver.trace tail` as input sources.

Delegation: 2 and 4 (well-specified against the finished primitives) are
subagent work; 1, 3 and 5 (live vault, SSH, the open semantics) are not.

## Decisions to confirm

1. **Tiering by what an operation can break** (table above), with `cache clear`
   the only operation that insists on showing you what it's about to discard.
2. **A cleared cache is saved first** (`deploy-snapshots/`), as events — the
   rollback story for the one operation that has none otherwise.
3. **Trace tail over SSH** is in scope (the environment names the host; the
   tool never copies or stores trace files, only streams them).
4. **`driver.submit` waits for spike 5c**; it ships only if the live engine
   demonstrably runs the submitted event through the channels.
