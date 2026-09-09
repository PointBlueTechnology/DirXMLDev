# Spike 5 — operate: cache, stats, trace, submit: **PASS** (2026-09-09)

`com.pointblue.dirxml.dev.spike.OperateSpike` against the test vault's
`Querytest` driver (phase a with the driver stopped; phase b starts it, works,
and stops it, restoring its trace settings). Trace observed over SSH
(`root@172.17.2.81`).

## Engine and driver statistics — the leak-diagnosis tools exist

- **`GetVersion`** → packed `1208418307` → `DxConst.parseDirXMLVersion` →
  `[4, 8, 7, 0, 3]` = IDM **4.8.7.0 build 3**.
- **`GetJvmStats(0, 0)`** → an XML document with the engine JVM's memory and
  thread statistics: heap initial/committed/used/total (3072 MB, 390 MB used),
  non-heap, daemon/current/peak thread counts, per-thread info. **This is how to
  watch for the heap growth behind the test vault's ndsd deaths** — sample it
  over time per driver activity.
- **`GetDriverStats(dn, 0)`** → `<driver-info driver-dn server-dn timestamp>`
  with `<subscriber><cache><size>` / `<unprocessed-size>` / transaction counts,
  and per-channel operation counters since `last-reset-time` (reported events,
  post-event/post-input transformation, commands, command results). The cache
  size is the count `driverset.status` wants, without paging the cache.

## 5a — the cache: queue, view, clear

- **`QueueEvent(dn, xds)` lands in a stopped driver's subscriber cache**; two
  scratch modifies queued → `ViewCacheEntries` shows 2 (position token 628).
- **`ViewCacheEntries(dn, 1, position, count, 0)`** pages the cache (as
  `DxCacheReader`). Right after `StopDriver` completes it can return
  `invalid request (-641)` for a moment while the shutdown finishes — retry.
- **`DeleteCacheEntries(dn, position, count, "", 0)` — semantics settled:**
  `count = 0` is rejected (`Other`); `(0, 1)` deletes **one** entry from the
  front; repeating empties the cache. So **clear = `(0, size)`** with `size`
  from `GetDriverStats`, or loop `(0, 1)` until `ViewCacheEntries` is empty. The
  fourth (String) parameter is accepted empty; `priority` stays 0.

## 5b — trace level on a running driver

`DirXML-TraceLevel` / `DirXML-TraceFile` set while stopped were honoured at
start (1,495 lines at level 3 within seconds of `running`). The level was
raised to 5 while running and the file kept growing (40 lines for the
submits), but the spike's traffic doesn't distinguish level 3 from 5 — **live
level changes are believed honoured (iManager's daily practice) but not
measured here**; `driver.trace set` will re-read the attribute back and say
"takes effect at the next restart" only if a future spike shows otherwise.

## 5c — SubmitCommand runs; SubmitEvent does not deliver

- **`SubmitCommand(dn, 1, xds)` runs the document through the Subscriber
  channel** on a running driver: the trace shows *Injecting User Agent XDS
  event document into Subscriber channel*, the shim's `execute()` called with
  the modify, and the shim's status returned in the result document —
  `<status event-id="…" level="error">NOT IMPLEMENTED</status>` (Querytest's
  shim doesn't implement modify, which is the point: the round trip is real).
  With Querytest's empty filter the event was also *Filtered out* on the way in
  — the filter applies to submitted commands like any other.
- **`SubmitEvent(dn, 1, xds)` returns a bare `<output/>` and never appears in
  the trace** — the same as spike 1b, now on a running driver at level 5. The
  publisher-side injection either needs something this shim/engine doesn't
  provide or isn't what the op does; **`driver.submit` ships for the subscriber
  channel only** and the publisher canary stays open.

## Implications

- `driverset.status`: `GetDriverState` + `GetDriverStartOption` +
  `GetDriverStats` `<cache><size>` per driver.
- `driver.cache clear`: view first (count, first/last event), save the page(s)
  to `deploy-snapshots/`, then `DeleteCacheEntries(0, size)`, verify empty.
- `engine.stats`: `GetJvmStats` + per-driver `GetDriverStats`; a `--watch N`
  that samples every N seconds is the leak-hunting mode.
- `driver.submit --channel subscriber`: `SubmitCommand`, result document
  printed; the canary compares it with the simulator's subscriber run.
- Trace over SSH: read stdout only — ssh's warnings go to stderr.

## 5d — the canary works (2026-09-09, after the spikes)

`operate.Submit` on a running `Querytest`: `SubmitCommand` with a scratch
modify → the live engine's result document was the shim's status
(`NOT IMPLEMENTED`); the driver's trace (over SSH, `Submitting document to
subscriber shim:`) gave the exact document the shim received; the same input
run through the simulator from the vault's tree (single-driver export from
`ExportWriter`) gave the same document — **MATCH** by canonical comparison.
That is DxCMD Phase 2's ground truth, on one event, for the Subscriber channel:
the harness predicts what the engine does. (The simulator printed
`warning: skipping a <configuration-values> block: … GCV reference is not
allowed here` while loading the export — a GCV-ref definition in a block the
simulator merges wholesale; harmless for the run, worth a look in the
simulator's `DriverExport.gcvDefinitions`.)
