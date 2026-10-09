# Phase 5 design — operate

Historical design note: which extended operation backs each command, and the
tier gate. Day-to-day use is [day-to-day.md](day-to-day.md) §5. The gate table
below matches `Operate.gate`.

Status: **built and proven on the test vault** (2026-09-09; spike findings in [spikes/operate.md](spikes/operate.md)). Follows [plan.md](plan.md) Phase 5; builds on
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
idm driver.cache clear    --env stg --driver "AD Driver" --yes --confirm stg
idm driver.migrate        --env stg --driver "AD Driver" --xds migrate.xml --yes
idm driver.resync         --env stg --driver "AD Driver" [--since 2026-09-01T00:00:00Z] --yes
idm driver.secrets list|set|remove --env stg --driver "AD Driver" [--name X]
idm driver.trace show|set|reset|tail --env stg --driver "AD Driver" [--level N] [--file F] [--lines N] [--follow] [--ldap [--seconds N] [--engine]]
idm driver.trace view      --env stg --driver "AD Driver"   |   --file trace.log      (the desktop DirXML Trace Viewer)
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
| `driver.cache clear` | `DeleteCacheEntries(dn, 0, size, "", 0)` — spike 5a: `(position, count)`, `count=0` rejected, `size` from `GetDriverStats`; verify with `ViewCacheEntries` | destructive: every queued event is discarded |
| `driver.migrate` | `MigrateApp(dn, xds)` with an XDS file (`<nds><input><query class-name=…>` as iManager's Migrate from Application builds it) | the driver must be running |
| `driver.resync` | `DriverResync(dn, since)` — epoch **seconds**; no `--since` = `Date(0)` = full resync | the driver must be running; a full resync of a big tree is a real load |
| `driver.secrets list/set/remove` | `List/Set/RemoveNamedPassword`; `set` reads the value from the environment's secrets file (`<driver>.named.<name>`) or `--stdin`, never from an argument | `vault.secrets` from the Phase 4 note lands here |
| `driver.trace show/set/reset` | `DirXML-TraceLevel` (int) and `DirXML-TraceFile` on the driver (spike 1b: the only driver-level trace attributes); `set` records the previous values in the audit line so `reset` can put them back (`--for 15m` resets automatically) | a trace change is a live change: the engine picks up trace level without a restart? — **spike 5b** checks (1b changed it while stopped) |
| `driver.trace view` | Opens the [DirXML Trace Viewer](https://github.com/PointBlueTechnology/DirXMLTraceViewer) (install.md §5.2) already connected to the environment's vault with the driver selected, streaming live with colouring, filters and find, or already showing a trace file (`--file`). The viewer makes its own LDAP connection; the password goes to it on stdin. For a person; the agent keeps `tail` for what it reads itself | read-only |
| `driver.trace tail` | Two ways. **Over LDAP** (`--ldap`, and the default when the environment names no `sshHost`): the engine's DirXML debug events on the environment's own LDAPS connection, one driver's lines out of them, `--follow` until Ctrl-C or `--seconds N` (default 30) collected; `--grep` filters, `--engine` adds the engine channel; needs eDirectory's Monitor Entry right, no trace file, nothing written (the simulator's `EdirTraceStream`, proved 2026-09-25). **Over SSH**: `ssh <env.sshUser>@<env.sshHost> tail -n N [-f] <traceFile>` — the file on the engine host; `--since` = the last N minutes by the trace's own timestamps, which only the file has (they are the engine host's local time with no zone, so the cutoff comes from `date` on that host, over the same ssh; the whole file streams through the filter) | read-only; this is how spike 1b proved engine pickup, made routine |
| `engine.version` | `GetVersion` (packed int → `DxConst.parseDirXMLVersion`) | |
| `engine.stats` | `GetDriverStats` per driver, and the engine's JVM stats if the ext op exposes them (`GetJvmStats` — to confirm in the jar); the "which driver is leaking heap" question from the test vault's ndsd deaths | read-only |
| `driver.submit` | `SubmitCommand` (subscriber channel) — spike 5c: runs through the channel on a running driver, the shim's status comes back in the result document. `--mode event` is `SubmitEvent` (publisher; spike 5c saw it deliver nothing — offered for the record, check the trace), `--mode queue` is `QueueEvent`: the document goes into the driver's subscriber cache, running or stopped, and is processed when the driver runs | the DxCMD Phase 2 canary for the subscriber channel: the same command through the simulator and the live engine, compared |
| `driver.start-option` | `SetDriverStartOption` (auto, manual, disabled), live; read back and audited like start/stop (`LIGHT`) | what iManager's driver properties set; the tree's own value wins at the next deploy |
| `driver.associations` | an LDAP search `(DirXML-Associations=<driver dn>#<state>#*)` per state — eDirectory indexes the path syntax by driver and state (a bare `#*` is refused with -306); counts per state (disabled, processed, pending, manual, migrate) and the objects with their association values | read-only; the Identity Console's *driver inspector* |
| `driver.password-sync` | the driver set's `DirXML-PasswordSyncTimeout` and every password-related definition of the driver's live `DirXML-ConfigValues` | read-only; what the console's password sync pages read |
| `job.list` / `job.status` | an LDAP search for `DirXML-Job` under the set (or one driver), each with `GetJobState` (running state, configuration state, scheduled, next run) | read-only; the console's jobs page |
| `rbe.list` / `rbe.members` | the set's `DirXML-SharedProfileSet` and its `DirXML-SharedProfile` policies: priority, the member count the directory computes for the dynamic group, the entitlement refs, the membership query; one policy's computed members | read-only; the console's RBE pages. Policies are deployed from the tree (`rbe-policies/`), and the Entitlements Service driver restarts to re-read them |
| `job.start` / `job.abort` | `StartJob` / `AbortJob`, gated as a light write and audited | a deploy of a changed job sends `NotifyJobUpdate` itself (Plan step `NOTIFY_JOB`) |
| `object.inspect` | one object's `objectClass`, its `DirXML-Associations` (driver, state, value) and `DirXML-PasswordSyncStatus` (driver, time, status) | read-only; the console's *object inspector*: "what state is this user in on each driver" |

## Safeguards

The same tiers as deploy ([vault-deploy.md](vault-deploy.md), "Environments and
gating"), applied by what an operation can break:

| operation | dev | stg | prd |
|---|---|---|---|
| status, cache view, trace show/tail, secrets list, engine.* | free | free | free |
| start, restart, trace set/reset, secrets set/remove | free | `--yes` | `--yes --confirm <env>` |
| stop, resync, migrate, submit | `--yes` | `--yes` | `--yes --confirm <env>` |
| cache clear | `--yes` | `--yes --confirm <env>` | `--yes --confirm <env>`. The command prints the count and the first and last event before the gate; a missing flag is a refusal after that preview, not a second prompt |

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
stg.sshHost=idm-stg            # the engine host, for driver.trace tail over SSH (optional: without it tail streams over LDAP)
stg.sshUser=root
stg.traceDir=/var/opt/novell/eDirectory/log   # where trace files land when a driver sets a relative name
```

## Build order — all done (2026-09-09)

Everything below landed. Proven live on the test vault through the CLI:
`driverset.status` (19 drivers), `driver.status`, `engine.version` (4.8.7.0
build 3), `engine.stats` (JVM heap/threads + per-driver cache and operation
counters — the leak-hunting view), `driver.cache view` (33 queued events on the
stopped AD driver), `driver.secrets list`, `driver.trace show|tail|--since|--grep`,
and the write path on `Querytest`: `driver.start` → `driver.trace set` →
**`driver.submit --tree` (canary: MATCH — the live engine handed the shim what
the simulator predicted)** → `driver.trace reset` → `driver.stop`, every step
audited. `driver.cache clear` was proven by spike 5a (`(0, size)`) and is
tested against a fake engine. Not done: `driver.trace set --for N` auto-reset;
`SubmitEvent` (publisher) stays unavailable.

1. ✅ **`Vault` operate primitives** + **spikes 5a/5b/5c** on the test vault's
   `Querytest` driver: cache view (port `DxCacheReader`'s chunked read), cache
   clear (resolve `DeleteCacheEntries`' parameters with a scratch queued event),
   migrate / resync (shape only — Querytest has no application), trace attribute
   read/write and whether a level change is honoured live, `GetVersion` /
   `GetDriverStats` / JVM stats, `SubmitEvent` semantics (the open item from
   spike 1b). Findings → `spikes/operate.md`.
2. ✅ **`Operate`** (the command layer, Sonnet): gating by tier and operation class, audit
   lines, the wait/verify loops, cache-clear's snapshot; `driverset.status` /
   `driver.status` rendering (text + `--json`).
3. ✅ **`driver.trace tail`** over SSH (environment `sshHost`/`sshUser`), and the
   start/restart trace verification.
4. ✅ **`driver.cache view --out`** as a simulator case, `driver.secrets`, and the
   CLI (`OperateCli`) for all of the above.
5. ✅ **`driver.submit`** as the canary (subscriber channel; 5c/5d).
6. ✅ Docs: agent guide "operate" section; the skill's missing-inputs table points
   at `driver.cache view --out` and `driver.trace tail` as input sources.

Delegation: 2 and 4 (well-specified against the finished primitives) are
subagent work; 1, 3 and 5 (live vault, SSH, the open semantics) are not.

## Decisions (confirmed 2026-09-09 — "your defaults are good")

1. **Tiering by what an operation can break** (table above), with `cache clear`
   the only operation that insists on showing you what it's about to discard.
2. **A cleared cache is saved first** (`deploy-snapshots/`), as events — the
   rollback story for the one operation that has none otherwise.
3. **Trace tail over SSH** is in scope (the environment names the host; the
   tool never copies or stores trace files, only streams them).
4. **`driver.submit` waits for spike 5c**; it ships only if the live engine
   demonstrably runs the submitted event through the channels.

## Attaching a debugger

Three ways to step through execution, from safest to most invasive. The first needs no server.

### 1. The simulator, in process

`idm simulate` and the simulator CLI run the engine's own policy classes (DirXML Script, XSLT,
ECMAScript) from the proprietary jars inside a plain JVM. Run the case from IntelliJ, or start it
with the JDWP agent and attach:

```sh
JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005" bin/idm simulate <case>
```

Attach the decompiled engine sources (kept outside the repository) as the source root for the
jars. Breakpoints at method entry and step-over are reliable; line stepping inside a method is
approximate, because decompiled line numbers do not match the bytecode. With `shim=true` the
real shim runs too and can be stepped through the same way. What you step through is the policy
chain against the fake directory, not a live vault.

### 2. The engine itself (lab only)

The engine is a JVM embedded in `ndsd` by `libvrdim.so`, created once when the DirXML module
loads. That library reads `DHOST_JVM_OPTIONS` from `ndsd`'s environment (with
`DHOST_JVM_ADD_CLASSPATH`, `DHOST_JVM_INITIAL_HEAP`, `DHOST_JVM_MAX_HEAP`, `DHOST_JVM_VERBOSE_GC`).
On a lab engine set

```sh
DHOST_JVM_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
```

in `/opt/novell/eDirectory/sbin/pre_ndsd_start` on a host, or in the container's environment for
a containerised engine, restart `ndsd`, open an SSH tunnel to 5005 and attach with IntelliJ's
Remote JVM Debug.

Rules:

- Never on a production engine. JDWP has no authentication: bind to loopback and tunnel.
- Suspend the thread, not the JVM. A breakpoint on a driver thread leaves LDAP and NCP serving,
  but the engine's timeouts, heartbeats and a Remote Loader link can fire while you wait.
- The engine jars are stripped: `javap -l` on a class in `dirxml.jar` (IDM 4.10.2) shows neither
  a `LineNumberTable` nor a `LocalVariableTable`. Expect method-entry breakpoints and variables
  by slot only; line stepping inside the engine's own classes does not work. Your shim's classes
  carry whatever your build gives them.

### 3. The Java Remote Loader

A shim under `dirxml_jremote` is a plain `java` command line in its own process. Put the same
JDWP option on its launcher and debug the shim without touching `ndsd`. For shim development
this is the simplest live route.

Planned (DirXMLDevWeb `docs/design.md`): an `engine.debug` helper that sets the option on a
containerised lab engine, restarts it, opens the tunnel and prints the attach settings, refusing
on the `prd` tier.

