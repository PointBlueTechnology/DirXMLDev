# Phase 0 spikes

De-risking the two real unknowns before any foundation code (see
[../plan.md](../plan.md), Phase 0). Each spike is read-only or scratch-only; none
touches a real object in any vault.

| # | Spike | Question it settles | Status |
|---|---|---|---|
| 1 | **LDAP write path** — `LdapWriteSpike` | Can we create/modify/delete `DirXML-Rule` objects + `XmlData` over LDAP with the deploy identity? What does the server stamp on them? | **program ready** (`com.pointblue.dirxml.dev.spike.LdapWriteSpike`); blocked on the test vault being up |
| 1b | **Engine pickup** (opt-in follow-on) | After linking a policy and `RestartDriver`, does the engine run it? Which changes need a restart vs are re-read live? | pending #3's API reference + a human-chosen side-effect-free driver |
| 2 | **Package modified flag** | Which attribute marks a packaged object as modified/customized (project + vault), and how is the baseline stored? | analysis running → [`modified-flag.md`](modified-flag.md) |
| 3 | **Extended-op API** | Constructor/response conventions for Start/Stop/Restart, Set/GetDriverSet, InitDriverObject, Migrate/Resync, Submit*, named passwords, GCVs, cache, version | analysis running → [`extended-ops-api.md`](extended-ops-api.md) |

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
