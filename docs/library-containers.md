# Every Library in its own place in the tree (design note)

**Status: decided 2026-11-23 (D1–D6 below). L1 built (core 0.77.0); L2 and L3 to build.** Follows #111 (other Libraries
read-only) and #133 (an LDIF of a whole vault; a second Library inside the driver set).

## Why

A driver set links policies and resources from as many Libraries as it likes. The engine finds
each one by its DN. The tree has one `library/` folder, keyed by name, so it can hold only one
object of each name. Two real vaults in #133 show where that breaks:

- **One project** links from `cn=Library,cn=DriverSet,…`, `cn=Library,ou=idm,o=services`,
  `cn=XXX-Library,…` and `cn=YYY-Library,…`. Three objects (`ECMA-JSON`,
  `IAECHLP-StringRandomizer`, `NOVLLIBAJC-JS`) exist in two of them.
- **The other** has nine Libraries: two inside the driver set (`cn=Library` and `cn=SKyPRO`), and
  seven outside it, under `o=services` and `o=global`.

Today (0.75.0) the tree keeps one copy of each name: the driver set's own, if it has one. A `NOTE:`
names the copy left out and says when the two differ. A link to the copy left out then reads the
kept copy in the tree. A deploy that rewrites that driver's links points them at the kept copy:
the vault's behaviour changes although nobody changed the policy. The note asks people to check
those links. The tree should not lose the object in the first place.

## What the tree does today

- **`library/<name>`** holds the driver set's own Library (`cn=Library,<driver set>`) and, since
  0.58.0, objects from any other Library or container. An object from elsewhere carries
  `library.dn` meta (its container's DN) and is read-only. Links to it keep its DN. Compare and
  deploy skip it, and an edit that would change it is refused (#111).
- **Paths are the identity.** `library/X`, `drivers/D/X`, `drivers/D/subscriber/X`. Links (`ref`),
  the diff, promotion, validation scopes, the simulator's export and the web all key on them.
  `VaultMapping.pathDn` turns a path back into a DN.
- **Two of one name** go through `DuplicateArtifacts.drop`, which keeps one.

## Proposal

**A folder per Library.** The driver set's own Library stays `library/`, unchanged. Every other
Library, or other container the driver set links from, gets a folder under a new top-level
`libraries/`:

```
tree/
  library/                       cn=Library,<driver set>: as today
    library.xml
    NQCE-lib-AJC.resource.xml
  libraries/
    SKyPRO/                      cn=SKyPRO,<driver set>
      library.xml                dn="cn=SKyPRO,cn=DriverSet-Identity,ou=services,o=identity"
      SPACDMCS-EcmaScript.resource.xml
    Library@services/            cn=Library,o=services
      library.xml                dn="cn=Library,o=services"
      NQCE-lib-AJC.resource.xml  its own copy, no longer left out
    customizing@services/
    customizing@services.global/ cn=customizing,ou=services,o=global
```

- **Paths:** `libraries/<alias>/<name>`. A link to `cn=NQCE-lib-AJC,cn=Library,o=services` is
  `libraries/Library@services/NQCE-lib-AJC`. It never collides with the driver set's own copy.
- **The DN** lives once, in the folder's `library.xml` (`dn=`), instead of in every object's
  `library.dn` meta. `pathDn` reads it to give the link's DN back to a deploy.
- **The alias** is the Library's `cn` when no other Library in the tree has that name. Otherwise
  it is the `cn` plus the fewest parent RDN values that make it unique (`Library@services`,
  `customizing@services.global`), with `~2` as a last resort. It's a label only: the DN in `library.xml`
  is what counts.
- **Two of one name across Libraries stop being duplicates.** Each has its own path.
  `DuplicateArtifacts` remains only for two objects of one name in one container, as a Designer
  project can hold (#174).
- **Read-only stays (D4).** Objects in `libraries/` are references. Compare and deploy leave them
  alone, and edits are refused, as today.

### What changes, area by area

| Area | Change |
|---|---|
| Model | `Artifact` gains a `library` alias (null = the driver set's own). `Artifact.path` gives `libraries/<alias>/<name>`. `DriverSet` keeps the alias → DN map. |
| Readers | `LdifReader` and `ProjectReader` already know each object's container. They set the alias instead of `library.dn`. `ExportReader` (a Designer export) has one `<policy-library>`; links into other Libraries stay as today. |
| Tree on disk | `AsCodeWriter`/`AsCodeReader` write and read `libraries/<alias>/library.xml` and its objects. The canonical format of each object is unchanged. |
| Links and deploy | `VaultMapping.pathDn` and `policiesValues` take the DN from the folder's `library.xml`. `ModelDiff`'s skip and `Transaction`'s refusal key on `libraries/` instead of meta. |
| Simulator export | `ExportWriter` embeds each linked object from its own path. Two objects of one name linked by one driver need distinct names in the export the simulator reads. To check in L2. |
| Validation | `ValidationScope`, the compile and ECMAScript checks, and `library-other` (one line per folder) learn `libraries/`. A `token-map` that names a table in another Library by DN resolves to that folder. |
| Promotion | Paths must match across projects. Matching uses the alias, so the alias must be the same in both projects (D2). |
| Query and docs | `query` chain and fishbone print the alias. `tree-layout.md` and `model.md` describe `libraries/`. |
| Web | The Library view groups objects by Library, own first. Links, `FlowView` and `PromoteView` show `libraries/…` paths. The read-only badge exists already. |
| Designer project out | `ProjectWriter` should write each object into its own Library in the project. To check whether it writes other Libraries' objects today. |

## Decided (Jerry, 2026-11-23)

1. **D1:** a top-level `libraries/<alias>/` beside `library/`; existing paths don't change.
2. **D2:** the alias is derived from the DN (the `cn`, plus parent RDN values only when needed),
   with the DN recorded in the folder's `library.xml`. A person-chosen alias per environment can
   come later if projects per environment need it.
3. **D3 and D5:** every container other than `cn=Library,<driver set>` gets its own folder: a
   second Library inside the driver set, Libraries outside it, and any other non-driver container
   a driver links from.
4. **D4:** objects in `libraries/` stay read-only: never compared, never deployed, edits refused.
5. **D6:** existing trees move by re-import (`import-ldif`, `import-project`, **Update this
   branch**); no `library.split` operation.

## The options as they were put

- **D1. Where they go.**
  - **(a) Recommended:** a top-level `libraries/<alias>/`, beside `library/`. Existing paths and
    trees don't change.
  - (b) Nested `library/<alias>/`. This puts the driver set's own objects and folders in one
    place, but `library/X` then means two things.
- **D2. How a folder is named.**
  - **(a) Recommended:** the alias comes from the DN (the `cn`, plus parent RDN values only when
    needed), and the DN is recorded per tree. Projects per environment then match when their
    vaults use the same Library names.
  - (b) A person names each alias, with the DN per environment, as known differences do (D1 of
    existing-environments.md). This handles vaults whose Libraries sit at different DNs, at the
    cost of a mapping to keep.
  - (a) can grow into (b) later.
- **D3. A second Library inside the driver set** (`cn=SKyPRO,<driver set>`).
  - **Recommended:** its own folder, like any other Library. Only `cn=Library,<driver set>` is
    `library/`.
- **D4. Read-only.** Keep objects in `libraries/` read-only: never compared, never deployed.
  - **Recommended: keep.** A Library outside the driver set is often shared with other driver
    sets, so deploying it would change them too.
  - Making them comparable needs vault reads of those Libraries (#115) and a separate decision.
- **D5. Containers that are neither a Library nor a driver or channel** (0.74.0 reads their
  objects as references).
  - **Recommended:** the same folder treatment, named after the container. Since 0.75.0, channels
    are recognised by object class, so this should be rare.
- **D6. Existing trees.** Trees from 0.58.0 on hold other-Library objects in `library/` with
  `library.dn` meta. Two ways to move them:
  - **Recommended:** a re-import (`import-ldif`, `import-project`, or **Update this branch** in the
    web) writes the new layout.
  - An `idm library.split <tree>` operation moves the objects and rewrites the links without a
    source. Nothing in them is hand-edited, because they're read-only.

## Phases

- **L1 (core):** model alias, readers, tree writer and reader, links, deploy DNs, diff skip and
  edit refusal; `DuplicateArtifacts` limited to one container; tests from #133's two shapes; docs.
  Release core.
- **L2 (core):** simulator export, validation scopes and checks, promotion, query; the D6 move if
  chosen. Release core.
- **L3 (web):** Library view grouped by Library, paths in links, flow and promote views; pin core.
  Release web and update the lab.

Not in scope: vault reads of other Libraries (#115) and deploying them (D4).
