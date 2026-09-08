# Phase 0 spikes

De-risking the two real unknowns before any foundation code (see
[../plan.md](../plan.md), Phase 0). Each spike is read-only or scratch-only; none
touches a real object in any vault.

| # | Spike | Question it settles | Status |
|---|---|---|---|
| 1 | **LDAP write path** — `LdapWriteSpike` | Can we create/modify/delete `DirXML-Rule` objects + `XmlData` over LDAP with the deploy identity? What does the server stamp on them? | ✅ **PASS** → [`ldap-write.md`](ldap-write.md) |
| 1b | **Engine pickup** — `EnginePickupSpike` | After linking a policy and `RestartDriver`, does the engine load and run it? Are live edits re-read? | ✅ **PASS** (proven from the driver trace; `SubmitEvent` semantics logged as a Phase-2/DxCMD open item) → [`engine-pickup.md`](engine-pickup.md) |
| 2 | **Package modified flag** | Which attribute marks a packaged object as modified/customized, and how is the baseline stored? | ✅ done — a checksum *pair*, not a boolean → [`modified-flag.md`](modified-flag.md) |
| 2b | **Checksum algorithm** | Can we compute `Idm:ContentChecksum`? | ✅ done — **negative**: not a content function (identical content, different checksums) → [`checksum-algorithm.md`](checksum-algorithm.md) |
| 3 | **Extended-op API** | Constructor/response conventions for Start/Stop/Restart, Set/GetDriverSet, InitDriverObject, Migrate/Resync, Submit*, named passwords, GCVs, cache, version | ✅ done → [`extended-ops-api.md`](extended-ops-api.md) |

## Running spike 1 (test vault only — it writes)

```bash
export JAVA_HOME=…/zulu-21
mvn -q compile
java -cp "target/classes:$(ls lib/*.jar | tr '\n' ':')$HOME/.m2/repository/com/pointblue/dirxml/dirxml-simulator/1.5.0/dirxml-simulator-1.5.0.jar" \
  -Dspike.url=ldaps://HOST:636 -Dspike.bindDn='cn=admin,ou=sa,o=system' -Dspike.password=… \
  -Dspike.driverSetDn='cn=driverset1,o=system' \
  com.pointblue.dirxml.dev.spike.LdapWriteSpike
```

It creates `cn=dirxmldev-spike-<ts>,cn=Library,<driverSetDn>`, reads/modifies it,
prints the server-side attributes, and deletes it in a `finally`. Record the
output in [`ldap-write.md`](ldap-write.md).
