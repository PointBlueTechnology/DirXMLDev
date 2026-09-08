# DirXML LDAP Extended Operations — Java API Reference

Static analysis of `com.novell.nds.dirxml.ldap.*` and `com.novell.nds.dirxml.util.DxCommand`
in `~/IdeaProjects/DirXMLSimulator/lib/dirxml_misc.jar`, via `javap -p -c -constants` (no
decompiler). Cross-checked against `DxCacheReader.java` and `docs/dxcmd-design.md`.

## 1. Common pattern

Every request extends `DirXMLRequest extends com.novell.ldap.LDAPExtendedOperation`. Its
constructor calls `DirXMLExtensions.getOID("<n>")` (OID = `"2.16.840.1.113719.1.14.100." +
n`) then `DirXMLExtensions.encodeData(Object[])` to BER-encode the args as an ASN1 SEQUENCE
positionally: `String`→OctetString, `byte[]`→OctetString, `int[]`→SetOf Integer,
`String[]`→SequenceOf OctetString, `char[]`→UTF‑8 password bytes as OctetString (zeroed
after use), `Integer`→Integer. A bare-DN ctor calls `DirXMLExtensions.encodeDN(String)` =
`encodeData(new Object[]{dn})` — **no DN-form validation or conversion happens**; the string
is passed through verbatim.

Responses extend `DirXMLResponse extends LDAPExtendedResponse`, lazily LBER-decoding
`getValue()` into `responseData: ASN1Sequence`; getters just index into it. Each response
class has `static void register()` registering itself for its OID with the SDK — **call
`XxxResponse.register()` before `extendedOperation()`**, or you get back a generic
`LDAPExtendedResponse` you can't cast. `DirXMLExtensions.initialize()` registers most of them
in one call.

**DN form:** `DxCommand` never converts the DN passed into any request — the `--dnform`
option only controls what form it *prints*, via `XdsDN.getDNFragment` (tree-name stripping
for display). Since `DxCacheReader` already validated LDAP-form DNs
(`cn=Active Directory Driver,cn=driverset1,o=system`) live against
`ViewCacheEntriesRequest`/`GetDriverStateRequest`, and every op here shares the identical
`encodeDN`/`DirXMLRequest` plumbing, **LDAP-form DN strings are the proven, safe choice for
all operations below.**

## 2. Chunked-result protocol

Several ops return a `(handle, size)` pair via `ChunkedResultResponseBase extends
DirXMLResponse` (`getDataHandle()`/`getDataSize()` = `responseData[0]`/`[1]`). Pull the
payload with `GetChunkedResultRequest(handle, chunkSize, 0)` (3rd arg always literally `0`
in every dxcmd call site) until drained, then `CloseChunkedResultRequest(handle)`.
`handle==0 || size==0` means no data — not an error. dxcmd's chunk size is `64512`
(`Math.min(remaining, 64512)`), matching `DxCacheReader.MAX_CHUNK`. OIDs:
`GetChunkedResult` 33/34, `CloseChunkedResult` 35.

```java
GetChunkedResultResponse.register();
byte[] data = new byte[0];
if (handle != 0 && size != 0) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(size);
    for (int remaining = size; remaining > 0; ) {
        byte[] chunk = ((GetChunkedResultResponse) conn.extendedOperation(
            new GetChunkedResultRequest(handle, Math.min(remaining, 64512), 0))).getData();
        if (chunk == null || chunk.length == 0) break;
        out.write(chunk); remaining -= chunk.length;
    }
    conn.extendedOperation(new CloseChunkedResultRequest(handle));
    data = out.toByteArray();
}
```

## 3. Driver lifecycle

**StartDriverRequest(dn)** 15/16, **StopDriverRequest(dn)** 17/18,
**RestartDriverRequest(dn)** 101/102 — plain `DirXMLRequest`; response is bare
`DirXMLResponse` (success = no exception). `RestartDriverRequest` is **never called by
`DxCommand`** — unverified against real usage, only against javap.

```java
conn.extendedOperation(new StartDriverRequest(driverDN));   // throws LDAPException on failure
conn.extendedOperation(new StopDriverRequest(driverDN));
conn.extendedOperation(new RestartDriverRequest(driverDN)); // unverified vs. dxcmd
```

**GetDriverStateRequest(dn)** 13/14 → `GetDriverStateResponse.getDriverState()` = int, not
chunked. States, confirmed via `DxConst.getDriverStateString`'s switch table: `0=Stopped,
1=Starting, 2=Running, 3=Shutdown Pending, 11=Get Schema (pseudostate after
DriverGetSchemaRequest), 4–10=Unknown`. Matches `DxCacheReader`'s existing constants.

```java
GetDriverStateResponse.register();
int state = ((GetDriverStateResponse) conn.extendedOperation(
    new GetDriverStateRequest(driverDN))).getDriverState();
```

**GetDriverStartOptionRequest(dn)** 7/8 → `getDriverStartOption()` int.
**SetDriverStartOptionRequest(dn, int startOption, boolean flag)** 9/10, fields
`[dn, Integer(startOption), Integer(flag?1:0)]`. `startOption`, confirmed via
`DxConst.getDriverStartOptionString`: `0=Disabled, 1=Manual, 2=Auto, 3=Overflow Manual,
4=Overflow Auto`. `flag` semantics (from `ArgSetDriverStartOptionHandler`, only exercised for
`startOption==0`): dxcmd's CLI words are `resync`/`noresync` → `flag=false`/`true` — read as
"skip resync on next enable." Not exercised for Manual/Auto in the decompiled path.

```java
conn.extendedOperation(new SetDriverStartOptionRequest(driverDN, /*Auto*/2, /*noResync*/false));
```

**GetDriverSetRequest()** (no-arg) 1/2 → `GetDriverSetResponse.getDriverSetDN()`.
**SetDriverSetRequest(dn)** 3/4. **ClearDriverSetRequest** 5/6 (not decompiled, presumably
no-arg). None of these are used by any `DxCommand` driver-lifecycle method inspected — they
read as session/connection-scoped context selectors, not general queries; the other ops all
take an explicit driver DN and don't need this.

**InitDriverObjectRequest(dn)** 39/40 — plain `encodeDN`, bare `DirXMLResponse`.

**MigrateAppRequest(dn, byte[] xds)** 25/26, extends `SubmitDocBase(oid, dn, data)`
(2-string-arg ctor, no version int) — encodes `[dn, data]` only; bare `DirXMLResponse`, no
result document (`--migrateapp` pushes a file in, no read-back).

**DriverResyncRequest(dn, java.util.Date since)** 23/24 — encodes
`[dn, Integer(since.getTime()/1000)]`, i.e. **epoch seconds, not millis**. dxcmd's no-arg
resync passes `new Date(0)` (full resync); its timed overload multiplies a caller-supplied
seconds count by 1000 before building the Date.

```java
conn.extendedOperation(new DriverResyncRequest(driverDN, new Date(0)));      // full resync
conn.extendedOperation(new MigrateAppRequest(driverDN, migrateXdsBytes));
conn.extendedOperation(new InitDriverObjectRequest(driverDN));
```

## 4. Submit / queue to the engine

**SubmitCommandRequest(dn, int version, byte[])** 29/30 and **SubmitEventRequest(dn, int
version, byte[])** 31/32, both extend `SubmitDocBase(oid, dn, version, data)` — fields
`[dn, Integer(version), data]`. **`version` is always the literal `1`** at every call site —
a fixed document-format version, *unrelated* to dxcmd's own `submitXDS(dn, bytes, version)`
argument, which instead **selects the request class**: `0`→`SubmitCommandRequest`
(subscriber-channel command), `1`→`SubmitEventRequest` (publisher-channel event), confirmed
by `ArgSubmitCommandHandler`/`ArgSubmitEventHandler` passing `0`/`1` into the shared
`ArgSubmitXdsHandler`→`DxCommand.submitXDS`. **This corrects `dxcmd-design.md`'s
"SubmitCommand=30"** — 30 is the response OID; the request is **29**. Response is
`ChunkedResultResponseBase` (§2); `handle==0||size==0` = no result document.

```java
ChunkedResultResponseBase resp = (ChunkedResultResponseBase) conn.extendedOperation(
    new SubmitCommandRequest(driverDN, 1, xdsBytes));   // subscriber-channel command
// or: new SubmitEventRequest(driverDN, 1, xdsBytes)    // publisher-channel event
byte[] resultXds = (resp.getDataHandle() == 0 || resp.getDataSize() == 0)
    ? new byte[0] : getChunkedResult(conn, resp.getDataHandle(), resp.getDataSize());
```

**QueueEventRequest(dn, byte[])** 27/28, `SubmitDocBase` 2-arg (no version) — bare
`DirXMLResponse`, not chunked: fire into the subscriber cache, no result document.
`DxCommand.queueEvent` passes the DN/bytes through unchanged.

```java
conn.extendedOperation(new QueueEventRequest(driverDN, xdsBytes));
```

## 5. Cache management

**ViewCacheEntriesRequest(dn, int timeout, int position, int count, int cacheType)** 41/42
(reference — already used by `DxCacheReader`). **Encoding oddity:** `cacheType` is
multiplied by 2 before encoding (`0`→wire `0`, `1`→wire `2`); `DxCommand.readCache` only
ever passes 0/1 (a `priority` boolean) and lets the constructor double it — don't double it
yourself. Response `ChunkedResultResponseBase` + `getPositionToken()` (`responseData[2]`)
for pagination. Already validated live (22 events read from a stopped AD driver).

**DeleteCacheEntriesRequest(dn, int p2, int p3, String p4, int priority)** 43/44 (4-arg ctor
defaults trailing int to 0). **Encoding oddity, asymmetric with ViewCache:** `priority` is
encoded as `priority*2 + 1` (always odd) vs. ViewCache's plain `*2` (always even) —
suggesting a shared low bit for read (even) vs. destructive (odd) ops, **inferred, not
confirmed**. `p2`/`p3`/`p4` (String) are **not resolved** to named semantics in this pass —
plausibly a start-token/count/filter mirroring `ViewCacheEntriesRequest` minus `timeout`,
but unproven. **Flagged: verify against a disposable driver before real use.**

```java
// Shape only — p2/p3/p4 semantics unconfirmed.
conn.extendedOperation(new DeleteCacheEntriesRequest(driverDN, p2, p3, p4, /*priority*/0));
```

## 6. Named passwords / GCVs

**GetNamedPasswordRequest(dn, String name)** 91/92 and **GetDriverGCVRequest(dn,
String[]|String names)** 89/90 are **never called by `DxCommand`** (zero hits across the
full class) — documented from javap only, unverified live. `GetNamedPasswordResponse
.getPasswordValue()` reads `responseData[0]` only when the sequence has exactly one element
(else null — "not set" is an empty sequence, not an error). `GetDriverGCVResponse` has
`getGCVDefinition()` (first entry's `[1]`) and `getGCVList()` (all entries as `[name,value]`
pairs).

```java
String pwd = ((GetNamedPasswordResponse) conn.extendedOperation(
    new GetNamedPasswordRequest(driverDN, "myPasswordName"))).getPasswordValue(); // untested vs. dxcmd
List<String[]> gcvs = ((GetDriverGCVResponse) conn.extendedOperation(
    new GetDriverGCVRequest(driverDN, new String[]{"gcv1","gcv2"}))).getGCVList();
```

**SetNamedPasswordRequest(dn, passwordName, moduleName, char[] value)** 75/76, extends
`SetPasswordRequest` — fields `[dn, passwordName, moduleName, value]`; `value` zeroed after
send via `.zero()`. Confirmed live shape from `DxCommand.setNamedPassword`.
**ListNamedPasswordsRequest(dn)** 81/82 → `getList()`: `List<String[2]>` of
`{name, module}`. **RemoveNamedPasswordRequest(dn, passwordName)** 77/78 (there's also
`RemoveAllNamedPasswordsRequest(dn)`, 79/80, same shape as `InitDriverObjectRequest`).

```java
SetNamedPasswordRequest req = new SetNamedPasswordRequest(driverDN, "myPasswordName",
    "moduleName", "s3cret".toCharArray());
try { conn.extendedOperation(req); } finally { req.zero(); }
List<String[]> names = ((ListNamedPasswordsResponse) conn.extendedOperation(
    new ListNamedPasswordsRequest(driverDN))).getList();         // {name, module}
conn.extendedOperation(new RemoveNamedPasswordRequest(driverDN, "myPasswordName"));
```

## 7. Version / stats

**GetVersionRequest()** (no-arg) 11/12 → `getVersion()` = **packed** int
(`DxCommand.dirXMLVersion`). Decode via `DxConst.parseDirXMLVersion(int)` (branches on bits
28-31, unpacks 4 fields) / `getDirXMLVersionString(int)` → `"major.minor.patch..."`. Treat
the raw int as opaque unless you need the string — dxcmd itself just compares it against
thresholds like `0x01000000`/`0x20000000`.

**GetDriverStatsRequest(dn, int priorityFlag)** delegates to `(dn, formatVersion=1,
priorityFlag)` 19/20 — `DxCommand.getDriverStats(dn, boolean)` converts to 0/1 and always
uses `1` for the middle arg (same format-version=1 convention as §4). Response
`ChunkedResultResponseBase`, no extra getters — pull via §2; payload presumed to be an
XML/XDS stats document (not decoded further here).

```java
int packedVersion = ((GetVersionResponse) conn.extendedOperation(
    new GetVersionRequest())).getVersion();
ChunkedResultResponseBase resp = (ChunkedResultResponseBase) conn.extendedOperation(
    new GetDriverStatsRequest(driverDN, /*formatVersion*/1, /*priority*/0));
byte[] statsXml = getChunkedResult(conn, resp.getDataHandle(), resp.getDataSize());
```

## 8. OID quick table (`2.16.840.1.113719.1.14.100.<n>`)

| Op | REQ | RSP | | Op | REQ | RSP |
|---|---|---|---|---|---|---|
| GetDriverSet | 1 | 2 | | GetChunkedResult | 33 | 34 |
| SetDriverSet | 3 | 4 | | CloseChunkedResult | 35 | 36 |
| ClearDriverSet | 5 | 6 | | InitDriverObject | 39 | 40 |
| GetDriverStartOption | 7 | 8 | | ViewCacheEntries | 41 | 42 |
| SetDriverStartOption | 9 | 10 | | DeleteCacheEntries | 43 | 44 |
| GetVersion | 11 | 12 | | GetNamedPassword | 91 | 92 |
| GetDriverState | 13 | 14 | | SetNamedPassword | 75 | 76 |
| StartDriver | 15 | 16 | | RemoveNamedPassword | 77 | 78 |
| StopDriver | 17 | 18 | | RemoveAllNamedPasswords | 79 | 80 |
| GetDriverStats | 19 | 20 | | ListNamedPasswords | 81 | 82 |
| DriverGetSchema | 21 | 22 | | GetDriverGCV | 89 | 90 |
| DriverResync | 23 | 24 | | RestartDriver | 101 | 102 |
| MigrateApp | 25 | 26 | | | | |
| QueueEvent | 27 | 28 | | | | |
| **SubmitCommand** | **29** | 30 | | | | |
| **SubmitEvent** | **31** | 32 | | | | |

`docs/dxcmd-design.md`'s "SubmitCommand=30" was the **response** OID; the request is **29**.

## 9. Flags / open questions

1. **`DeleteCacheEntriesRequest`'s `p2`, `p3`, `p4`** — semantics not resolved; the
   `*2+1` vs. ViewCache's `*2` encoding is inferred, not confirmed by any named constant.
   Verify on a disposable driver before destructive use.
2. **`GetNamedPasswordRequest`/`GetDriverGCVRequest`** — zero call sites in `DxCommand`;
   documented from javap only, unverified live.
3. **`RestartDriverRequest`** — present in the jar, unused by `DxCommand`; same caveat.
4. **`SetDriverStartOptionRequest`'s boolean** — inferred from one CLI branch
   (Disabled/resync); meaning for Manual/Auto (if any) not exercised in the decompiled path.
5. **`GetDriverSetRequest`/`SetDriverSetRequest`/`ClearDriverSetRequest`** — read as
   session-scoped context selectors, not exercised by any inspected lifecycle method.
6. **DN form** — no server/library-side form conversion happens anywhere in this layer;
   whatever string you pass is BER-encoded verbatim. LDAP form is the only form
   independently proven end-to-end (via `DxCacheReader`'s live test).
