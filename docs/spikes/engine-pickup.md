# Spike 1b — Engine pickup of an LDAP-deployed policy: **PASS** (2026-09-08)

**Question:** after we deploy a policy over LDAP (create the `DirXML-Rule`, add a
`DirXML-Policies` linkage) and `RestartDriver`, does the engine actually load and
run it — and do live edits need a restart? **Yes / yes / yes — proven from the
driver's own trace**, not inferred from a response.

Subject: `cn=Querytest,cn=driverset1,o=system` (your own `pubquerytest` shim, stopped,
empty cache, no policies, filter `<filter/>`), test vault only. Program:
`com.pointblue.dirxml.dev.spike.EnginePickupSpike` (`-Dspike.mode=status
-Dspike.traceFile=/opt/novell/dirxmldev-querytest.trace …`). Every write was undone
in `finally`; the driver's original trace settings (level 10 →
`/opt/novell/querytest.txt`) were restored. The level-3 trace of the run is left
on the server at `/opt/novell/dirxmldev-querytest.trace` as evidence.

## What the trace shows

**1. The engine loads the LDAP-deployed Library policy at driver start** — for each
set we linked it into (`<policyDN>#0#<set>` values on `DirXML-Policies`, Typed-Name
textual form accepted by LDAP):

```
QT ST:Loading Subscriber input transformation policies.
QT ST:Reading XML attribute vnd.nds.stream://IDM_IG4_TREE/system/driverset1/Library/dirxmldev-pickup-1788883261784#XmlData.
QT ST:Found DirXMLScript policy.
QT ST:Loading Subscriber output transformation policies.   … Found DirXMLScript policy.
QT ST:Loading Subscriber event transformation policies.    … Found DirXMLScript policy.
```

**2. It executes it.** On the shim's startup documents the rule fired on the
subscriber thread, the publisher thread, and the engine thread:

```
QT ST:Applying policy: %+C%14Cdirxmldev-pickup-1788883261784%-C.
QT ST:    Applying rule 'dirxmldev spike'.
QT ST:      Action: do-status(level="warning","dirxmldev-spike-v1").
     Message:  dirxmldev-spike-v1
```

**3. A live `XmlData` edit is *not* re-read.** The policy was rewritten to marker
v2 while the driver ran; every firing until shutdown (11:01:07) is still **v1** —
policies are read once at start (the `vnd.nds.stream` read above), not watched.

**4. `RestartDriver` reloads it.** After the restart (11:02:07 `Reading driver
information …`) every firing is **v2**:

```
QT ST:      Action: do-status(level="warning","dirxmldev-spike-v2").
```

So the deployer's contract is: **write objects + linkage over LDAP, then
`RestartDriver`** (or start a stopped driver). No proprietary API involved.

## Also learned

- **`SubmitEventRequest` did not deliver our event into the driver's channels.** The
  submitted `<modify>` (src-dn `dirxmldev-spike-src`) never appears in the trace,
  and the op returns a bare `<nds><source/><output/></nds>` every time. It is not a
  synchronous "run this through the channel and give me the output" call as the
  DxCMD design note assumed — at least not for this driver/doc. Treat it as an open
  item for **DxCMD Phase 2** (semantics, required doc shape, and which channel it
  targets), **not** a deploy concern. Execution must be observed via the trace (which
  the harness can now read over SSH) or an eDir side effect.
- **The empty filter was not the blocker** — the input transformation runs before
  the publisher filter and fired regardless.
- **A misbehaving policy aborts driver start.** With a `do-set-dest-attr-value
  direct="true"` (+ veto) policy linked, the driver went `starting → stopped`: the
  shim's startup `GetSchema` document ran through the policy and failed
  (`GetSchema Failed … Code(-8001) Unable to retrieve application schema`). The
  simulator compiled and ran that policy fine — a **validation gap** to close in
  Phase 2 (policies fire on the shim's init/schema docs, not only on events;
  "would this break driver start" needs a check).
- **Trace attributes on a driver** are `DirXML-TraceLevel` (int) and
  `DirXML-TraceFile`; `DirXML-XSLTraceLevel`/`DirXML-JavaTraceFile` are driver-set
  only (-608 illegal on a driver), and `DirXML-DriverTraceLevel` is listed in the
  class but has no attribute-type definition (LDAP: undefined). The deployer's
  "operate" layer should use the first pair.
- **This shim's publisher thread doesn't terminate on stop** ("Publisher thread did
  NOT terminate (timed out)" after 60 s) — the source of the slow stops; a shim
  quirk, not an engine or tooling issue.
- The driver traces at level 10 to `/opt/novell/querytest.txt` normally; all earlier
  runs of this spike are recorded there.

## Implications for the plan

- **Phase 4 deployer** = LDAP add/modify/delete of `DirXML-*` objects + `DirXML-Policies`
  linkage (+ config attrs) → `RestartDriver`. Post-deploy verification = re-read
  objects (byte-equal, spike 1) **and** confirm the restarted driver's trace shows the
  new policy loaded (`Reading XML attribute …<policyDN>#XmlData` / `Found DirXMLScript
  policy`) — a cheap, reliable "did it take" check now that trace files are readable
  over SSH.
- **Phase 2 validation** must include "safe to load at driver start" (policies run on
  the shim's init/`GetSchema`/identification docs), which the simulator alone doesn't
  catch today.
- **Phase 5 operate**: driver trace level/file management + remote trace tailing is a
  first-class capability (proved useful immediately).
