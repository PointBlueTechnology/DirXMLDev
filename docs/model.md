# The IDM model and the IDM-as-code layout

Phase 1 of [plan.md](plan.md). The **typed model** is the in-memory, reference-aware
representation of a driver set; **IDM-as-code** is its on-disk form — one readable,
git-versioned file per object plus small manifests. Everything else (validation,
edit operations, deploy, the Designer writer) works on the model.

## Model (`com.pointblue.dirxml.dev.model`)

```
DriverSet  name, dn, configValues (GCVs, XML), library, drivers[], meta
 ├─ Library      policies[], resources[]                      scope: library
 └─ Driver       name, dn, shimClass, config{…}, links[], meta
     ├─ policies[]   (driver scope: schema map, input/output transforms)
     ├─ resources[]  (driver-scope mapping tables, ECMAScript, …)
     ├─ subscriber   Channel → policies[]                    scope: subscriber
     └─ publisher    Channel → policies[]                    scope: publisher
```

- **Artifact** = `Policy` | `Resource`. Every artifact has a `name`, a `Scope`
  (`library`, `driver`, `subscriber`, `publisher`), the owning driver (unless library)
  and a **path** that is its identity: `library/<name>`, `drivers/<driver>/<name>`,
  `drivers/<driver>/subscriber/<name>`, `drivers/<driver>/publisher/<name>`. Names are
  unique within a scope (eDir enforces cn uniqueness per container), so paths are unique.
- **Policy** holds the raw content element (`<policy>`, `<xsl:stylesheet>` /
  `<xsl:transform>`, `<attr-name-map>`) and its derived `kind`. **Resource** holds a
  `contentType` and either XML content (mapping tables) or text (ECMAScript).
- **Links** — a driver's `links[]` is the ordered policy-set linkage: `(PolicySet, ref,
  order)` where `ref` is an artifact path. This is the only cross-object reference in
  the model; it is what makes edits reference-aware (rename → fix refs; delete →
  refuse while referenced). `PolicySet` uses the engine's set ids (0 schema-mapping,
  1 input, 2 output, 3 ecmascript, 4/5 event, 6/7 matching, 8/9 create, 10/11 command,
  12/13 placement, 14 gcv; sub=even, pub=odd from 4).
- **Driver config** is kept as raw XML blobs keyed by kind — `shim-config-info`,
  `config-values`, `driver-filter`, `engine-control-values` — plus `shimClass`,
  `shimAuthServer`, `shimAuthId`. The model doesn't interpret them in Phase 1 (the
  simulator already knows how); it preserves them losslessly.
- **meta** (`Map<String,String>`) on the driver set, drivers and artifacts carries
  source-specific extras we don't model yet (package GUIDs/versions, Designer ids,
  DN) so no import is lossy.

Sources feed the same model: driver / driver-set **export** (`ExportReader`), **LDIF
/ live LDAP** (`LdifReader`, over the simulator's entry reader), Designer **project**
(`ProjectReader`). Reading is total; nothing is dropped (unknown things land in `meta`).

## IDM-as-code (`com.pointblue.dirxml.dev.ascode`)

```
<root>/
  driverset.xml                     manifest: name, dn, meta, driver list
  config-values.xml                 driver-set GCVs (raw <configuration-values>)
  library/
    library.xml                     manifest: artifacts (name, kind, file, content-type, meta)
    <name>.policy.xml               one policy per file (content only)
    <name>.mapping-table.xml
    <name>.js                       ECMAScript resource (text)
    <name>.resource.xml             other XML resources
  drivers/<driver>/
    driver.xml                      manifest: dn, shim, config files, artifacts, LINKAGE
    shim-config-info.xml  config-values.xml  driver-filter.xml  engine-control-values.xml
    <name>.policy.xml               driver-scope policies / resources
    subscriber/<name>.policy.xml
    publisher/<name>.policy.xml
```

- **Content files are pure** — exactly the policy/resource content, serialized by
  `CanonicalXml` (stable attribute order, indentation only in element-only content,
  text verbatim). Everything else (names, kinds, links, meta) lives in the manifests,
  so a policy file diffs like a policy, not like a container record.
- **Manifests are the registry.** `driver.xml` lists each artifact (`path`, `kind`,
  `file`, `content-type`, meta) and the full `<linkage>` in set order:
  ```xml
  <driver name="CyberArk" dn="cn=CyberArk,cn=driverset1,o=system" shim-class="…">
    <config kind="driver-filter" file="driver-filter.xml"/> …
    <artifact kind="policy" scope="subscriber" name="sub-etp_Scoping Policy" file="subscriber/sub-etp_Scoping Policy.policy.xml"/> …
    <linkage>
      <set key="subscriber-event">
        <link ref="drivers/CyberArk/subscriber/sub-etp_Scoping Policy" order="1"/>
        <link ref="library/lib-common-event" order="2"/>
      </set> …
    </linkage>
  </driver>
  ```
- **Filenames** are the artifact name with filesystem-unsafe characters replaced; the
  manifest's `name` is authoritative, so odd names never corrupt identity.
- **Round-trip contract:** `read(write(model))` ≡ `model` (structural equality), and
  `write` is idempotent byte-for-byte — the basis of clean git history. A source →
  model → as-code import is checked the same way (model equality; source bytes are
  not preserved, their meaning is).

## Non-goals in Phase 1

Interpreting config blobs, validation, edits, deploy, package semantics beyond
preserving their identifiers, and the Designer-format writer.
