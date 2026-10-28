# Changelog

All notable changes to DirXMLDev are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.57.0] - 2026-10-28

### Fixed

- A Designer project with two objects of one name in the tree's Library (or a driver or channel)
  no longer gives a tree that won't load (DirXMLDevWeb #174: `duplicate artifact path`, every check
  failing). This happens when a driver set uses Libraries in two containers that both hold the
  object, or when a package upgrade leaves an old copy behind. `import-project` keeps one and says
  which in a `NOTE:` line (`DuplicateArtifacts`):
  - from different Libraries, the one in the driver set's own `cn=Library`, where a deploy writes;
  - the same copies, the first;
  - otherwise the newest package version, or the first.

  With more than one Library, each Library artifact records its Library's DN (`library.dn`). An
  object a Designer relation names twice is read once. A tree already written with two loads with
  one of them, and `validate` reports a `duplicate-artifact` error that says which, instead of
  every check failing.

### Added

- `docs/commands.md`: every `bin/idm` command with each argument (required or not, the values
  it takes, the default) and an example. The edit operations' part is written from the registry
  (`OperationsDoc`, examples in `edit/examples.properties`); `CommandsReferenceTest` fails when a
  command the CLI runs has no entry, when the usage text leaves one out, when the generated part
  is stale, or when an example names an argument the operation lacks or leaves out a required one.
- `Registry.Arg.flag` marks an argument that takes no value; `bin/idm`'s usage shows it as
  `[--name]`. `Registry.example(name)` returns an operation's example.

### Changed

- `bin/idm`'s usage text lists the commands it left out (`driver.log-level`, `driver.start-option`,
  `driver.associations`, `driver.password-sync`, `object.inspect`, `promote.map`, `query … schema`)
  and flags it left out (`vault.deploy --driver-server`, `vault.rollback [<tree>] --server`,
  `vault.import-clone --replace --json`, `import-live --mark-new-drivers`, `form.edit --force`,
  `package.build --description --readme --supported-driver`, `package.site --json`,
  `driver.migrate --direction vault`, `driver.secrets --kind`, `driver.submit --mode`,
  `trace.view --trace-driver`, `vault.email-server --auth`, `--env` on the console-gap commands),
  and no longer shows optional flags as required (`entitlement.show --driver`; `package.build
  --driver --vendor --version`; one of `vault.deploy --dry-run|--yes|--step`).
- `docs/event-store.md` and `docs/vault-deploy.md` no longer describe `events.case --run` and
  `vault.deploy --step --restart-each`, which were never built. `docs/getting-started.md` says
  what `doctor --env` adds.

## [0.56.0] - 2026-10-27

### Added

- **`gcv.set --server S`** sets a driver's GCV on one server of a set served by several (#134). The
  value goes into that server's own copy of the driver's config-values, made from the primary's when it
  has none, and no other server changes. A copy that comes to equal the primary's is dropped. Without
  `--server`, the primary's value is set, as before; every server without its own copy follows it.
- **`query gcvs <driver>`** prints, under a GCV, each server whose value differs from the primary's, a
  GCV only another server defines, and which server is the primary.
- **`GcvOps.onServers`, `GcvOps.onlyOnServers`, `GcvOps.valueIn`:** where a driver's GCV differs per
  server, for the web workbench.

## [0.55.0] - 2026-10-26

### Added

- **Cases move with their driver** (docs/promotion.md): when both projects have simulator cases,
  each case's files compare as `cases/<path>`, its `driver=` read as the target names the driver.
  Choosing a driver's directory brings its cases. `--cases` and `--source-cases`; by default
  `<tree>/cases`, or `cases/` beside a `tree/` directory.
- **`promote.map`:** a proposed map, printed. It covers the look-alike drivers, and each source
  server onto the target server with the nearest name when the map names none.
- **`promote.pair` choices:** `--propose-map` writes the proposal and pairs with it, `--pending`
  leaves a difference pending, `--accept` accepts one as known.
- **API:** `Projects` (both trees, the pair, each side's cases, and a map not written yet) for
  `ProjectCompare.of`, `Promote.run`, `preview`, `pair` (with `Pairing`) and `accept` (several
  at once).

## [0.54.0] - 2026-10-26

### Added

- **`Promote.preview`:** every file that differs between two projects, as the target has it and as
  a promotion writes it (the source's logic, the target's values), for a review that shows both
  sides and lets a person merge line by line. DirXMLDevWeb's *Compare projects* uses it.

### Fixed

- **A new driver promoted alone:** `promote.apply` named the driver's own `driver.xml` as the
  manifest to promote with it. It names `driverset.xml`, which lists the drivers (and
  `library/library.xml`).

## [0.53.0] - 2026-10-26

### Added

- **A project per environment: compare and promote** ([docs/promotion.md](docs/promotion.md)).
  For teams that keep a tree per environment, each in its own repository:
  - **`tree.compare <target> --with <source>`:** what differs, file by file and value by value,
    in the target's terms. The source's driver names and servers go through the pair's map
    (`promotions/<source>.properties`, in the overrides file's form). The driver set's name and
    DN and the drivers' DNs stay the target's. Files are compared with their values set aside,
    so a value that differs never makes its file look changed.
  - **The promotion record** (`promotions/<source>.json`): per file and per value, a fingerprint
    of each side at its last promotion. With it each difference is *pending* (the source changed
    it), *the target's own*, *both changed*, *known* or *unchanged since the record*. It goes
    object by object, so a partial promotion leaves the rest pending.
  - **`promote.plan` / `promote.apply`:** copy chosen files (`--object`, a file or a directory;
    `--pending`; `--contents` for a file merged line by line) into the target. The target's
    values stay unless named with `--value`. A promoted file's package baseline goes with it.
    The result is read back and validated: a file whose manifest wasn't promoted with it, or a
    new validation error, is refused and nothing is written. Gives the commit trailer
    `Promoted-from: <source>@<commit>`.
  - **`promote.pair`** takes the first record. **`promote.accept`** and **`promote.forget`** handle
    known differences (`promotions/<source>.known.json`).

## [0.52.0] - 2026-10-26

### Added

- **`Vault.open(DirContext, LDAPConnection)`:** a vault over an extended-operation connection the
  caller has already connected and bound. Whatever the caller set on it applies to every engine
  request, such as RFC 4370 proxied authorization, so a service account's connection acts for one
  person; Directory Console uses it for Identity Manager under single sign-on. The engine keeps the
  last identity it was given on a connection, so such a connection belongs to that one person.

## [0.51.0] - 2026-10-26

### Changed

- **The trace parser is DirXML Trace Viewer's `dirxml-trace-core` 1.10.0** (was 1.8.0), as
  DirXMLDevWeb 0.76.0 uses. For `trace.view` over LDAP:
  - **The vault's time:** each live line carries the time eDirectory reports for the event, not the
    time it arrived, so it lines up with the engine's own trace files. A server that sends no time
    falls back to the local clock.
  - **Names with escapes:** server and driver names with an escaped comma, equals sign, plus sign
    or UTF-8 escape (`cn=Smith\, John`) are read correctly, and so is the check for which server a
    connection reaches.

## [0.50.0] - 2026-10-22

### Changed

- **`bin/apps` reads the environments the way `bin/idm` does.** Without `--props` it reads
  `IDM_ENVIRONMENTS` (or `./environments.properties`), then the person's own `IDM_CREDENTIALS` when
  set, whose lines win: the split a team server and the DirXMLDevWeb container keep.

## [0.49.0] - 2026-10-20

### Added

- **Environments that differ in more than values** (docs/existing-environments.md, decisions D1–D6).
  Taking on an existing system with production and QA trees that have grown apart:
  - **Which environments get what:** `environments` and `planned` on drivers (`driver.xml`), jobs,
    role-based entitlement policies and templates. Compare, plan and deploy leave out what an
    environment doesn't get; `planned` tells "not yet" from "never". `driver.set environments|planned`,
    `environments.set` for several objects at once.
  - **Names per environment:** `drivers/<d>.name` in an environment's overrides file. Compare, plan
    and deploy use that name; imports map it back; secrets answer to either name.
  - **Servers per environment:** `servers.<tree server's name> = <env server's DN>`, several onto one,
    `.prefer` to choose whose settings a shared server takes; validation `server-unmapped`.
  - **Jobs per environment:** schedule, disabled and trace settings as override keys.
  - **A new driver's server** chosen at deploy: `--driver-server "D=<server>"`.
  - **Two environments compared:** `vault.compare-envs`: one-sided objects, values (expected or
    not, with the tree's value), likely renames (feeds flagged), drift with likely causes,
    case-only DN differences apart.
  - **Known differences:** `overrides/<env>.known.json`, `known.accept` / `known.forget`, matched by
    fingerprint so a changed object shows again.
  - **Import:** keeps the tree's drivers an environment doesn't get; `--mark-new-drivers`.

### Changed

- `override-missing-env` no longer warns about server maps and driver names, which belong to the
  environments that need them.

## [0.48.0] - 2026-10-19

### Fixed

- **A value eDirectory can't return no longer stops a compare.** One damaged value, or a value that
  doesn't fit its attribute's syntax, makes eDirectory fail a whole subtree search with "invalid
  attribute syntax" (LDAP 21, NDS -325), and the error names only the driver set. The read now
  falls back to reading object by object when that happens. An object that still fails is read
  attribute by attribute, and the one eDirectory can't return is left out. A note names the object
  and the attribute: the compare goes on, and you know what to fix. This applies to compare, the
  live read, and a deploy's plan and verify. New `VaultAccess.names` (a search with no attributes)
  and `attributeNames` (attribute types only).

## [0.47.0] - 2026-10-19

### Changed

- **dirxml-trace-core 1.8.0** (DirXMLTraceViewer). Its new `POLICY_RESULT` marker matches the engine's
  "Query from policy result" line. `trace.view` keeps such a line inside the policy running, as it
  does a log event, instead of ending the policy there. Before, the rules and actions after a
  policy's query were lost from its step. A release now attaches `dirxml-trace-core-1.8.0.jar`.

### Added

- **Not affiliated with OpenText:** `THIRD-PARTY.md` and the README now carry the trademark notice.

## [0.46.0] - 2026-10-17

### Fixed

- **A capture (`--capture-drift`) of a tree in a subfolder of its repository wrote the vault's state
  at the repository's top.** A page project's tree is in `tree/`. The as-found commit added the
  whole driver set beside it, so every file looked new, and merging it duplicated the driver set.
  The capture now writes into the tree's own folder.

### Added

- **A plan against production runs the production checks too.** A dry run reports what a deploy
  would be refused for, as `gate` and `gateKind` (`not-in-git`, `uncommitted`, `branch`, `requires`,
  `unknown-state`, `drift`), and captures nothing.
- **`vault.deploy --capture-only`** records the vault's state when it isn't the known one, writing
  nothing to the vault and needing no confirmation:
  - an as-found branch (`gateKind` `captured`, `capturedBranch`);
  - the matching commit (`verified`);
  - or nothing to do (`known`).
- **A refusal carries `gateKind`, and a capture `capturedBranch`,** in the result and its JSON.

## [0.45.0] - 2026-10-17

### Fixed

- **A GCV defined only on another server of the driver set was `gcv-undefined` (#134).** Each server
  holds its own `DirXML-ConfigValues` for a driver, so a driver that runs only on another server has
  its GCVs there. Validation never looked at them, and refused a deploy of the whole driver set.
  Validation and the compile check now count every server's values.
- **The note for a server an import couldn't reach** names Connections → Edit → Servers, where its
  address is given.

### Added

- **`driver.server.copy`** copies a driver's server-specific settings from one server of the set to
  another: GCV values, shim parameters, engine control values, the shim's server and account.
  - `--from` and `--to` take a server's DN, its name, or `primary`.
  - `--driver D`, or every driver without it. `--only` picks some of `gcv,shim,ecv,auth-server,auth-id`.
  - No other server changes, and the tree keeps only what differs from the primary.
  - A deploy writes the copy to the target server.

## [0.44.0] - 2026-10-16

### Fixed

- **`prd.requires=stg` accepts the commit of a deploy record.** "Commit the deploy record" after the
  STG deploy made a new commit, so production saw a different commit and refused, though nothing had
  changed. A green STG deploy now counts for any commit with the same content, differing only in
  `deploy-log/` and `deploy-snapshots/`. A content change since the STG deploy is still refused.
- **"The last deploy" is the latest by its timestamp.** It used to be the last line of the log, and
  the lines of two merged logs interleave.
- **`docs/vault-deploy.md` said the production gate runs `simulate`.** It doesn't, and the doc now
  says so. It also says that every tier validates.

### Added

- **`vault.deploy --base-branch <b>`.** A production deploy is refused unless the tree is on that
  branch. The web page passes the project's base branch, so production takes only what was merged.
- **Deploy logs merge without conflicts.** The tree's `.gitattributes` now carries
  `deploy-log/*.jsonl merge=union`, so two branches that both deployed merge cleanly.
  `export-project` writes the line, or adds it to an existing `.gitattributes`.
  `DeployLog.writeAttributes` is the shared writer.

## [0.43.0] - 2026-10-16

### Fixed

- **`<env>.servers=<serverDn>=<url>` matches the whole server DN.** The value was split at the
  first `=`, so `cn=idm2,ou=servers,o=system=ldaps://…` became the key `cn`, and no server's address
  was ever used. That affected the trace, import, deploy and the clone. The address now starts at
  `=ldap://` or `=ldaps://`.

### Added

- **Certificate pinning: `<env>.certSha256`.** A vault whose certificate the JDK doesn't trust (a
  private CA, a host name that doesn't match, a tunnel) is reached by pinning that one certificate's
  SHA-256, instead of `trustAll`.
  - The pinned certificate is trusted whatever its issuer or name. Anything else is refused, and a
    changed certificate is refused naming both fingerprints.
  - `PinnedTrust` is used on the JNDI connection (`PinnedSockets`), the extended-operation
    connection and the LDAP trace.
  - `trustAll` still works.
- **`SignIn`: a sign-in's outcome in words**, for the web's Connections page.
  - `SignIn.probe(url)` shows the certificate a vault presents (subject, issuer, validity, SHA-256)
    and whether the JDK trusts it, without signing in.
  - `SignIn.check(config)` says **rejected** (with eDirectory's reason: wrong password, expired,
    locked out, account expired, and so on), **certificate**, **unreachable** or **ok**. When it's
    ok, it also gives the account's `passwordExpirationTime` (days left) and `loginGraceRemaining`,
    where the account may read them.

## [0.42.0] - 2026-10-15

### Added

- **A deploy and a rollback report each step as it happens.** `Deployer.Options.progress`, and
  `Deployer.rollback(…, progress)`, hear one line per step:
  - validating, reading the vault, the plan;
  - the snapshot, each write (`write 3/9: modify cn=…`);
  - each restart and its wait for Running;
  - verify, and the outcome.

  The web page shows them while a deploy runs.

### Fixed

- **`--capture-drift` works where git has no identity**, which includes the web image's container. The
  as-found commit failed with "unable to auto-detect email address", so the capture stopped before it
  recorded the vault's state, and the next production deploy still found no deploy on record. When
  git has no `user.email`, the commit now uses `DirXMLDev <dirxmldev@localhost>`. A configured
  identity is left alone.

## [0.41.0] - 2026-10-15

### Changed

- **A deploy scoped to drivers is no longer refused for validation errors in drivers it leaves
  alone.**
  - It's held to errors in its own drivers, in the Library artifacts they link, in the RBE policies
    when the Entitlements Service driver is in scope, and at the driver-set level.
  - Errors elsewhere are listed on the plan's first line and don't block.
  - A whole-driver-set deploy still needs the whole tree clean.
  - Before this, one broken policy in any driver blocked every deploy of a production driver set
    (`deploy/ValidationScope`, docs/validation.md).

### Fixed

- **A role-based entitlement policy no longer compares as changed right after an import.**
  - The tree's writer left carriage returns as they were. A parser turns CR LF into LF, so a value
    typed on Windows came back from `rbe-policies/<name>.xml` different from the vault's. Typical
    cases are a Description from Designer or iManager, or criteria.
  - Carriage returns are now written as `&#13;`, and newlines and tabs in attribute values as
    references, everywhere in the tree's XML.
  - The compare also ignores whitespace around a policy's `DirXML-EntitlementRef` values, which the
    tree's reader trims.
  - A tree imported before this fix still holds the altered text. Import again, or take the vault's
    state into the branch, to clear the difference.

## [0.40.0] - 2026-10-15

### Fixed

- **Importing needs no engine jar.** `import-live`, `vault.diff`, deploy's read of the vault, and
  importing a Designer project or export used to parse with the engine's `XmlDocument` (through the
  simulator's `Xds`). On an install with no `lib/`, they stopped with `NoClassDefFoundError`: the
  web's "Export from an Identity Vault", "Import a Designer project" and "Import a Designer export"
  failed on a fresh container. They now parse with the JDK (`CanonicalXml.parse` and the new
  `parseFile`). Importing idm254 both ways gives byte-identical trees (726 files).
- `VendorFreeTest` also walks from the readers (`VaultDiff.readLive`, `LdifReader`,
  `ProjectReader`, `ExportReader`, `AsCodeReader`/`Writer`), so this stays fixed.

## [0.39.0] - 2026-10-15

### Fixed

- **`validate` without the engine jars no longer crashes.** A check that runs the Identity Manager
  engine (compile, ECMAScript, forms, flows) is skipped with an info finding,
  `check-skipped-no-engine`, that names the missing class. The other checks run and the result can
  be OK. It used to stop with `NoClassDefFoundError`, for example in the web's container with no
  `lib/` mounted.

## [0.38.0] - 2026-10-15

**`driver.trace tail --ldap` prints lines as a trace file has them**
(`[10/15/26 15:30:01.123]:AD ST:Start transaction.`, an XML document's lines after its message). It
used to print `[15:30:01.123] ST: Start transaction.`. `trace.view --ldap` can now read the stream
into transactions, which it could not before, and a saved tail opens in the trace viewer.

### Changed

- **The trace over LDAP uses `dirxml-trace-core`'s stream** (the trace viewer's and the web's), over
  JLDAP, in place of the simulator's `EdirTraceStream`. It needs neither the simulator nor an engine
  jar. A certificate the JVM does not trust is accepted for the run only when the environment says
  `trustAll`, as the vault connections do.

## [0.37.0] - 2026-10-15

**Talking to a vault no longer needs any NetIQ/OpenText jar** (docs/vendor-free-vault.md). Default
behaviour is unchanged. `ldap.jar` is no longer one of the jars `lib/` needs: the LDAP client is
open-source JLDAP 4.3 from Maven Central, which a release attaches (THIRD-PARTY.md).

### Added

- **The IDM extended operations are this project's own encoding** (`deploy/IdmOps`), byte for byte
  the vendor's (a guarded test compares them), so `dirxml_misc.jar` is not needed for deploy, diff,
  import-live or the `driver.*`, `driverset.*`, `engine.*` and `job.*` commands. Validate's compile
  check, simulate and package checksums still use the engine jars.
- **`doctor` reports `vault`**, the vault path's readiness, separately from `lib` (the engine jars).
- **`VendorFreeTest`** walks from the methods the directory-console calls and fails if any reachable
  code names a vendor class (or loads one by name).

- **`trace.view` and the MCP tool `idm.trace.view`** (docs/trace-view.md). These show a driver
  trace as transactions: per driver and channel, the event's operation, class and DN, the policy
  sets and policies applied with their rules and actions, vetoes, `<status>` documents and the worst
  status, plus each transaction's line ranges.
  - The input is a file (`--file`), or a bounded read from an environment: SSH `--lines` or
    `--since`, or LDAP `--seconds`.
  - Filters: `--channel`, `--status`, `--vetoed`, `--text`, `--last`. `--transaction N` returns that
    transaction's raw text.
  - It is best effort. Only the engine's own wording is read; anything a shim prints is kept and
    counted, never interpreted.
- **A new dependency: `dirxml-trace-core`.** It is the parser the trace viewer and the web share.
  `bin/idm` takes it from `~/.m2` at `tracecore.version`; CI installs it from the DirXMLTraceViewer
  release, and each core release attaches it (docs/install.md).

### Changed

- **The duplicate guarded tests are folded together.** The zero form/PRD-error checks now run
  inside `ProvisioningGuardedTest`, and `PlanTest` keeps only the whole-kind versus
  partial-removal case.

## [0.36.0] - 2026-10-15

**Behaviour change:** `role.add`, `resource.add`, `role.set` and `resource.set` now check `--category`
against the tree's category list (`DirectoryModel/ChoiceDefs/roles-category` or
`resources-category`). A key the list does not have is refused, and the refusal names the keys
the list does have. Before this release `--category Sales` wrote the key `sales` unchecked, and
the applications then showed no category (#70).

### Added

- **Container and category are now separate (#70).** `role.add` and `resource.add` take
  `--container NAME` for the container the object goes in, and `--category` for one or more category
  keys. Keys are matched without regard to case and written as the list spells them. Without
  `--container` the object goes in the container named like its first category, as before.
- **`category.list`, `category.add`, `category.set` and `category.remove`** (`--for role|resource`)
  read and edit the category lists: a key, plus a label per language. `category.remove` is refused
  while a role or resource still uses the key. The lists are packaged objects, so the first edit
  keeps a baseline, as for any packaged object.
- **`validate` warns `appconfig-category-unknown`** when a role or resource names a key its list does
  not have.

- **`bin/apps flush [--holder NAME]` and `bin/apps holders`.** These call the Identity Applications'
  cache flush, `DELETE /IDMProv/rest/admin/cache/holder/items` (default holder `All Cache`). A PRD
  deployed a moment ago is then requestable at once, with no 10-minute wait for the permission
  index (live on idm254). The request, its required fields and the effect are in
  docs/idapps-rest.md §3.1.
- **`bin/apps permission <PRD> --wait SECONDS`** polls until the PRD is in the index, with one
  sign-in.

### Fixed

- **`bin/apps` signs in once per process** and renews only near expiry. Every OSP password sign-in
  grows the user's `oidpInstanceData`. On idm254 a loop of separate calls filled it, and every
  sign-in for that user then failed (eDirectory -649). docs/idapps-rest.md §1 says how to recover
  and avoid it.
- **`bin/apps` prints OSP's own error** when a sign-in fails.

### Documentation

- **W3 live proof, part 2.** `start-flow` runs. `role-request` and `resource-request` are accepted as
  written (their `GRANT`/`USER` children are enum literals) but on idm254 their grant fails fetching
  the workflow administrator's OAuth token, which is lab configuration
  (docs/spikes/w3-rest-live.md, docs/workflows.md §4 W3).

## [0.35.0] - 2026-10-14

### Fixed

- **The diff's JSON no longer fails on a driver-set-level guard.** When the vault has jobs, templates
  or RBE policies at the driver-set level and the tree has none, the held-back deletes are reported as
  an "empty kind" with no driver. `ModelDiff.json()` threw a `NullPointerException` on that, and
  DirXMLDevWeb's Compare showed only "Cannot invoke String.length() because s is null"
  (DirXMLDevWeb#116). The driver is now written `null`, and the JSON writer writes any null string as
  `null`. The CLI's text report was never affected.

## [0.34.0] - 2026-10-14

### Added

- **Mapping tables as CSV.**
  - `mapping-table.import-csv --path P --csv-file F` replaces a table from RFC 4180 CSV. The header
    names the columns and the first column is the key. A column already in the table keeps its type
    and a new one is `nocase`. A duplicate or empty key, a ragged row or bad quoting is refused, and
    the table is left untouched.
  - With `--merge true` it adds and updates rows by key instead and keeps the others.
  - `mapping-table.export-csv <tree> <path>` prints a table as CSV.
- **The drivers that use a mapping table.** A table is not linked into a driver the way an
  ECMAScript resource is: a driver reaches it through a policy that reads it with `token-map`.
  `Refs.withDrivers` (and `idm refs`) now also lists each such driver, as a `driver` reference: one
  that runs its own such policy, links a Library one, or gets it from the driver set. Rename and
  delete still act on `Refs.to`, as before.

### Fixed

- **A `rest` workflow activity authored by the tool now runs.** A live run on idm254 found two
  shapes wrong in the W3 grammar, which was calibrated from the binding classes alone:
  - **The attributes are ECMAScript expressions.** This covers `protocol`, `host`, `port`, `path`,
    `method` and the three header attributes. `protocol="https"` failed with
    `ReferenceError: "https" is not defined`.
  - **The output maps are flowdata paths.** A bare `restStatus` kept nothing; the map has to read
    `flowdata.restStatus`.

  `flow.activity.add`/`.set` now quote a plain value and keep an expression, and they add the
  `flowdata.` prefix to a bare output name. `validate` reports both mistakes in hand-edited XML
  (`flow-rest-unquoted`, `flow-rest-output-map`), and `prd.flow` shows the literal values. The
  operations' help, docs/workflows.md, the agent guide and the skill's facts say so too.
  docs/spikes/w3-rest-live.md has the run.
- **`bin/apps` no longer suggests `index` to make a new PRD requestable.** Measured live, neither
  `ADD_OR_MODIFY` nor `REFRESH` shortens the permission index's wait; `permission` says when the
  PRD is there.

## [0.33.0] - 2026-10-14

### Added

- **`IDM_CREDENTIALS`** names a person's own credentials file, which the CLI merges over the
  environments file it found (`Environments.load()`, `loadFound(file)`, and `idm doctor`), as
  `Environments.load(definitions, credentials)` does for the server. A hosted DirXMLDevWeb keeps
  only the definitions in its environments file and each person's bind DN and password in their own
  file, so the `idm` inside its container could see the environments but never bind. Without
  `IDM_CREDENTIALS` nothing changes. docs/vault-deploy.md, "Two files".

## [0.32.0] - 2026-10-14

### Changed

- **`Environments.load(definitions, credentials, false)` now leaves out every reference in a person's
  file, not only commands**: `…Env`, `…Command` and `…Keychain`. A hosted server resolves each of them as
  itself (its own environment variables, shell and Keychain), so a person who is not a deployer could
  have read one of the server's own variables. Their secrets are now only the values kept in their own
  file. `refusedReferences()` lists each key left out with its form, and asking for one of those secrets
  says which form a deployer's is. `refusedCommands()` stays, deprecated, as its key list. The
  two-argument form and the one-file form are unchanged.

## [0.31.0] - 2026-10-14

### Added

- **`Environments.load(definitions, credentials, commands)`**: with `commands` false, a person's
  `…Command` references are left out of the merge and listed (`refusedCommands()`); asking for one of
  those secrets says a command is a deployer's rather than "not set", and the command never runs. A
  hosted server (DirXMLDevWeb) uses it for everyone who is not a deployer, since it runs a command as
  itself. The two-argument form and the one-file form are unchanged.

### Fixed

- **Docs**: docs/plan.md and docs/vault-deploy.md no longer call the Remote Loader password deferred; it
  is set since 0.24.0 (`SetRemoteLoaderPassword`, with the mutual-authentication key and keystore
  passwords), tested against the fake engine only.

## [0.30.0] - 2026-10-13

### Added

- **`export-project` writes jobs, notification templates and entitlement policies** into a Designer
  project, laid out as Designer keeps them (grounded on real projects and Designer's own `DeployedPath`):
  - A job is a `Job_` under its driver set or driver, with its document, its own trace settings, a link
    to each server it runs on that the project holds, and each scope as a `JobScope_`. A server the
    project does not hold is noted and left out of the comparison.
  - The vault's template collection is a `NotfTemplateCollection_` tied to the Identity Vault, each
    template a child with its subject and body.
  - The set's `RBEContainer_` holds each policy as an `RBEPolicy_` and their priorities as
    `DirXML-SPPriority`.
  - An update adds, changes and removes them like any other object. A new project made from the edir3
    vault tree (2 jobs, 351 templates) and from two client projects with policies reads back equal and
    updates to nothing.

### Fixed

- **Reading a Designer project's jobs**: a job's scopes (`JobScope_`) are now read, as the vault's
  `DirXML-Scope` value (`<dn>#0#<scope-def/>`, a missing scope attribute filled in as Designer does when
  it deploys), and its own trace settings (`DirXML-TraceLevel`, `-TraceFile`, `-TraceSizeLimit`). Trees
  imported from a project before this lack them: import again to pick them up.

## [0.29.0] - 2026-10-13

### Fixed

- **`export-project` round trip**: updating a project to the tree it was just written from now changes
  nothing. Three causes, all in `ProjectWriter`:
  - A packaged item's package linkage record (`dirxml-pkglinkages`, Designer's `Idm:InstalledLinkages`)
    was never written into a project, so every update saw it missing on each packaged item. It is now
    written with the item's other package attributes, verbatim as the vault holds it, and an update
    keeps it in step.
  - A PRD whose `<process>` the tree keeps apart from its definition (a vault whose `XmlData` lacks it)
    compared as changed against its `.prd`, which always embeds it. An update now compares it the way
    the `.prd` holds it; what is written is unchanged.
  - A file rewritten with the bytes it already had was listed as changed. The result now lists, and the
    writer now writes, only files whose bytes change.
- A project's result now says when jobs, notification templates or entitlement policies of the tree
  were not written: the project writer does not carry them yet (deploy them from the tree).

## [0.28.0] - 2026-10-12

### Fixed

- **`vault.deploy --capture-drift`** clears every path a tree read writes before writing the vault's state
  (jobs, entitlement policies and notification templates too, through the new
  `AsCodeWriter.MANAGED_PATHS`), so an object the vault no longer holds leaves no stale file on the
  captured `as-found/…` branch.

## [0.27.0] - 2026-10-10

### Added

- **`Vault.open`** for services that sign a person in and must not keep the password (Directory
  Console): it borrows the caller's bound JNDI connection (left open by `close`) and binds the
  extended-operation connection once, through the caller's TLS socket factory. The password is used
  for that bind only. A missing `dirxml_misc.jar` is now reported as such.
- **`Operate.Engine` and `Operate.CachePage` are public**, and `Environments.Environment.forService`
  builds an environment from a name, a driver set and a tier: a service can call `Operate` with its
  own connections (`Operate.vaultEngine(Vault.open(…))`) and test against a fake engine.

## [0.26.0] - 2026-10-09

### Fixed

- **Designer projects: entitlement policies** are read from where real projects keep them (the driver
  set's `Idm:RbeContainer`, priorities in `DirXML-SPPriority`, multi-valued attributes as nested
  structures, the display document as hex); verified against three real projects.
- **Entitlement refs derived from a display document** (projects and exports) carry LDAP DNs: Designer
  writes the entitlement DNs in NDS dot form, which a deploy cannot write.

### Added

- **Designer projects: notification templates** are read (the driver set's Identity Vault →
  its template collection → the templates, subject, body and package stamps).

## [0.25.0] - 2026-10-09

### Added

- **The low console gaps** (docs/console-gaps.md §13): `driverset.activation [apply]`,
  `driverset.servers [add|remove]`, `engine.metrics`, `driver.rights`, `workorder.list`.

## [0.24.0] - 2026-10-09

### Added

- **`driver.migrate --direction vault`** (docs/console-gaps.md §11, G5): migrate vault objects into the
  application — an LDAP search, then one `<sync>` per object through the running driver's subscriber
  channel; `--dry-run` lists them; `--direction app` is the old `MigrateApp` path.
- **`driver.log-level [set]`** (§11, G12): the log level of a driver or the driver set as Designer's page
  holds it (`DirXML-DriverTraceLevel`, `DirXML-LogEvents` through `SetLogEvents`, `DirXML-LogLimit`,
  `DirXML-LogEventsType`); `--inherit` puts a driver back on the set's settings.
- **`vault.email-server [set]`** (§12, G8): the notification collection's SMTP settings, shown and
  written live (the password from the secrets file or stdin, never shown).
- **Notification templates in the tree** (§12, G8): the vault's `notfMergeTemplate` objects as
  `templates/<name>.xml`, read live and from an LDIF, validated, diffed (`TEMPLATE_*`) and deployed
  (a removal needs `--delete-all templates`).
- **Secret kinds** (§11, G11): `driver.secrets set|remove --kind named|shim-auth|remote-loader|key|keystore`;
  `vault.deploy --secrets` now sets the Remote Loader password (before: skipped as unsupported) and the
  Remote Loader's mutual-authentication key and keystore passwords when the secrets file has
  `<driver>.mutual-auth-key-password` / `<driver>.mutual-auth-keystore-password`.

## [0.23.0] - 2026-10-09

### Added

- **`driver.query`** (docs/console-gaps.md §10, G9): ask the connected system through the running
  driver with the engine's query verb — class, scope, a DN (LDAP form converted to slash form) or an
  association, search attributes, the attributes to read (`none` for a match test); the shim's
  `<instance>`s as text or JSON. Light write gate, audited.
- **`driver.health [clear]`** (§10, G7): the Driver Health job's last state per server from the
  driver's `DirXML-uiXMLSmall`, the health configuration on its `DirXML-ConfigManifest` (states,
  condition groups, actions), the set's health jobs and whether the driver is in scope; `clear`
  removes the recorded status.
- The main usage now lists `job.*` and `rbe.*` beside the other operate commands.

## [0.22.0] - 2026-10-09

### Added

- **Role-based entitlement policies** (docs/console-gaps.md §9, phase R1, grounded on edir3 with the
  Entitlements Service driver): `DirXML-SharedProfile` policies in the driver set's
  `DirXML-SharedProfileSet` container, read from the vault and an LDIF, a Designer export
  (`<rbe-policies>`) and project (`Idm:RbePolicies`, unverified), kept as `rbe-policies/<name>.xml`,
  written back to an export; `validate` checks them (`rbe-no-priority`, `rbe-duplicate-priority`,
  `rbe-priorities-not-sequential`, `rbe-legacy-entitlements-xml`, `rbe-no-membership`,
  `rbe-no-entitlement`, `rbe-unknown-entitlement`, `rbe-no-service-driver`); `vault.diff` reports
  `RBE_ADDED` / `RBE_REMOVED` / `RBE_CHANGED`; `vault.deploy` creates the container, writes the policy,
  rewrites the priority list and restarts the Entitlements Service driver (a removal needs
  `--delete-all rbe-policies`); `rbe.list`, `rbe.members`.

### Fixed

- A deploy no longer prints the Identity Applications cache note for a job change; the driver set's own
  removals (jobs, policies) no longer trip the empty-kind guard with a null driver.

## [0.21.0] - 2026-10-09

### Added

- **Jobs deploy and operate** (docs/console-gaps.md §8, phase J2): `vault.diff` reports
  `JOB_ADDED` / `JOB_REMOVED` / `JOB_CHANGED`; `vault.deploy` writes the `DirXML-Job` object and
  sends `NotifyJobUpdate` so the scheduler re-reads it (no driver restart; a removal needs
  `--delete-all jobs`); `job.list`, `job.status` (`GetJobState`: running, configuration,
  scheduled, next run), `job.start`, `job.abort`.

## [0.20.0] - 2026-10-09

### Added

- **Jobs in the model** (docs/console-gaps.md §1, phase J1): `DirXML-Job` objects under a driver
  or the driver set — the configuration document, the servers, the scopes, the trace settings —
  read from the vault and an LDIF, a Designer project (`Idm:Jobs`, `.Job_`) and an export
  (`<jobs>`), kept as `drivers/<driver>/jobs/<name>.xml` and `jobs/<name>.xml`, written back to an
  export; `validate` checks them (`job-no-document`, `job-wrong-root`, `job-no-class`,
  `job-no-server`, `job-unknown-server`, `job-disabled`). Diff and deploy follow in J2.

## [0.19.0] - 2026-10-09

### Added

- **Identity Console gaps, first set** (docs/operate.md): `driver.start-option --option
  auto|manual|disabled` (`SetDriverStartOption`, live, gated and audited); `driver.submit --mode
  command|event|queue` (`SubmitCommand`, `SubmitEvent`, `QueueEvent` into the cache of a running
  or stopped driver); `driver.associations --driver D [--state …]` (the objects associated with a
  driver, by state, through `(DirXML-Associations=<driver>#<state>#*)`); `driver.password-sync`
  (the driver set's sync timeout, the driver's password-related settings); `object.inspect --dn`
  (an object's classes, associations across drivers, password-sync status). Read-only ones need
  no `--yes`.
- The usage text now lists `vault.schema`, `vault.app-schema`, `vault.deploy-schema`,
  `schema.diff`, `events.describe`, `query … events|event` and
  `events.case`, which existed without a line.

## [0.18.0] - 2026-10-09

### Added

- `ApplicationType.of(driver)` is public, with `displayName(type)` (what a diagram labels an
  application: "Active Directory", "JDBC", "User Application"…) and `vaultOnly(type)` (a driver
  with no external system), for DirXMLDevWeb's Architecture view (#32 there).

## [0.17.0] - 2026-10-08

### Added

- **`schema.diff`** (docs/schema.md): the tree's `schema/vault.xml` against an environment's live
  `cn=schema` (`schema.diff <tree> --env E`), or two environments' live schemas (`--env A --other
  B`); nothing written. Definitions match by NDS or LDAP name; only-here, only-there, and
  differing syntax, OID, flags, superclasses or attribute lists (as sets) are reported, custom
  definitions not yet pushed marked. `SchemaDiff.of` for the web's Compare.

## [0.16.0] - 2026-10-08

### Added

- **Two environment files** for a hosted server (DirXMLDevWeb's `docs/multi-user.md`):
  `Environments.load(definitions, credentials)` merges the project's definitions (no secret keys;
  the load refuses a file that holds one, `Environments.secretKeys` lists them) with one person's
  credentials (their `bindDn`, `password…`, `trustAll`, `eventsPassword…`, or whole environments
  of their own — `Described.own`). A relative `<env>.secrets` resolves beside the definitions. The
  CLI's one file reads as before.

## [0.15.0] - 2026-10-08

### Changed

- A blank driver (`driver.add --shim-class`) now starts with the documents the engine and
  Designer expect, as templates: a filter with User and Group and the usual attributes
  synchronised both ways, shim settings with one example parameter per section (driver,
  Subscriber, Publisher heartbeat), and an empty configuration-values document — instead of an
  empty `<filter/>` and bare option groups.

## [0.14.0] - 2026-10-07

### Added

- The Event Logger's store (docs/event-store.md): `events.describe`, `query <tree> events --env E`
  with the contract's query patterns as selectors (object timeline, subtree, name, driver, policy
  and stage, own or logged rows, types, class, time window, engine event id, free text, modified
  attribute), `query <tree> event <id>` with the modify diff and the other rows of the same engine
  event, and `events.case` turning a row into `cases/<name>/{case.properties,input.xds}` (the raw
  XDS when stored, rebuilt from the JSON with the logger jar otherwise and marked). Per-environment
  keys `eventsUrl`, `eventsUser`, `eventsPassword…`, `eventsTable`, `eventsPseudonymise`,
  `eventsTree`; the read-only reader account on a read-only connection. `eventsPseudonymise=true`
  masks people in everything that leaves the core, with the vault clone's pseudonymiser.
  `bin/smoke-events.sh` checks it all against a throwaway PostgreSQL in Docker.

### Changed

- `org.postgresql:postgresql` is the core's first runtime dependency that is not an engine jar
  (BSD-2); `bin/idm` takes it from `~/.m2` by the pom's version, and a release attaches it.

- Static-RSA key exchange (`TLS_RSA_*`) stays enabled by default. eDirectory's LDAPS listener
  often offers only those suites, and recent JDK builds (24+, and the 21 updates from mid-2026)
  disable them, which made a bind to such a vault fail with "simple bind failed" although the
  credentials were right. `LegacyTls.enable()` (base package, so the portable build has it) runs at start-up and before every vault
  connection; opt out with `-Didm.tls.legacy=false` or `IDM_TLS_LEGACY=false`. `doctor` reports
  when the suites are still disabled.

## [0.13.0] - 2026-10-07

### Added

- Schema (docs/schema.md): the vault's schema in the tree (`schema/vault.xml`, read by
  `vault.schema --env E`, by a Designer project import from its `_schema.xml`; `query schema`);
  schema you define (`schema.add-attribute`, `schema.add-class`, `schema.set`, `schema.remove`)
  pushed with `vault.deploy-schema` (plan, `--yes`, `--confirm` on production; attributes first,
  classes in superclass order, never a removal); the application schema from the shim
  (`vault.app-schema --driver D`, the engine's `DriverGetSchema`, into `drivers/<d>/app-schema.xml`);
  validation warns about filter and schema-map names the vault schema lacks (`schema-unknown-*`,
  `schema-attr-not-of-class`). Nothing reads schema on its own: every refresh is explicit.

## [0.12.0] - 2026-10-07

### Added

- `driver.set --clear`: make a setting empty. `--value` is optional now; an empty value cannot be
  given on the command line (it reads as missing), so the flag says it.
- `driver.setting.add` / `driver.setting.remove`: a shim parameter (`param:<name>`, into
  `driver-options`, `subscriber-options` or `publisher-options` of the shim config) or an engine
  control value (`engine:<name>`) with its display name, type, value and description — what a
  shim under development needs without editing the XML.

## [0.11.0] - 2026-10-06

### Added

- `GcvOps.usage(ds, driver, name)`: every place a GCV is read — policies (tokens and `~name~`) and
  the driver's configuration documents (shim parameters, engine controls, filter). `gcv.delete`
  refuses on any of them now, not only policies.
- `UpdateSite.list(url)`: what an update site offers, for a picker; `package.fetch` is unchanged.

- `query fishbone --json` policies carry `description` (a DirXML Script policy's `<description>`,
  else the artifact's description meta) so a picture can show it on hover.

### Fixed

- `sites.properties` names with a space ("Novell Public") were cut at the space; the file is read
  as `name=url` lines now.
- GCV readers: an `<if-global-variable>` condition counts as reading the GCV (it has no token
  form, so `GcvReferences` missed it); `gcv.delete` refuses for it and the validator's
  undefined-GCV check sees it.
- `resource.add` was registered twice — for mapping-table, ECMAScript and GCV-definition
  resources, and later for the role catalog's resources — and the later one hid the first, so
  the artifact kinds could not be created through the catalog (CLI, MCP, web). The artifact
  creator is `artifact.add` now (`--kind mapping-table|ecmascript|gcv`); registering a name
  twice refuses at start-up.

## [0.10.0] - 2026-10-06

### Changed

- `docs/secrets.md`: the four credential forms and the exact line for each secret manager
  (AWS, Azure, GCP, CyberArk CCP, BeyondTrust, Bitwarden, Bitwarden Secrets Manager, Vault,
  1Password, a Terraform output), what each needs, and the helper scripts; linked from the
  README, the docs index and vault-deploy.md.

## [0.9.0] - 2026-10-05

### Added

- Terraform: module `terraform/modules/idm-environment` writes an environment's
  `environments-<name>.properties` and `secrets-<name>.properties` (0600) and, with
  `deploy_mode = "deploy"`, runs `vault.deploy` for a tree, again whenever the tree or a file
  changes; literal or command-referenced credentials; examples for a lab and AWS Secrets
  Manager; `docs/terraform.md`. Helper commands `bin/secrets/cyberark-ccp.sh` and
  `bin/secrets/beyondtrust.sh` for a `<key>Command=` reference to those managers.

## [0.8.0] - 2026-10-05

### Fixed

- Every `flow.*` operation failed with `WRONG_DOCUMENT_ERR` on a PRD read from a tree (its
  `process.xml` is a document of its own): the process copy placed in the definition is now
  imported into the definition's document first.

### Added

- `override.set` / `override.remove`: a stage override as an edit operation (the key must
  name something the tree defines; removing an environment's last key removes its file).
- `config.set-content`: replace a whole configuration document (a driver's config-values,
  shim-config-info, driver-filter or engine-control-values, or the driver set's
  config-values), for an editor that works on the XML itself.
- `Registry.Spec.create(args)` is public: a caller outside the `edit` package can build an
  operation from the catalog and run it through `Transaction` (DirXMLDevWeb does).

## [0.7.0] - 2026-09-29

### Added

- `idm version [--check]`: the running version (from `idm-version.properties`,
  filled in at build time), and with `--check` the latest GitHub release.
- Release notices: `doctor` reports the latest release on a `release` line,
  and every other command prints at most one line a day on stderr when a
  newer release is known (cached in `~/.idm/release-check.json`, refreshed in
  the background with a two-second timeout, silent on any failure). Off with
  `IDM_NO_UPDATE_CHECK`, in CI, without a console, and with `--json`
  (docs/install.md §2.5).

## [0.6.0] - 2026-09-28

### Fixed

- A deploy that adds a new driver now creates its forms, PRDs and AppConfig
  objects with it, the `cn=AppConfig` container included — created with its
  mandatory `Version` (and the recorded `srvprvPlugins`), since eDirectory
  refuses the container without it (-609). Before, the diff only saw the
  driver, the deploy wrote everything but the provisioning subtree, and the
  verify after it failed on the forms it then found missing.
- `prd.add` fills the properties `srvprvRequest` makes mandatory when the
  template did not carry them, so a PRD the tool creates is one the vault
  accepts.
- A single-driver export (what `simulate` hands the simulator) now carries a
  mapping table that only a linked Library policy reads; the simulator could
  not resolve that `Map` token before.

### Added

- `validate`: `prd-property-missing` (E) — a PRD lacking a property
  `srvprvRequest` makes mandatory (status, flow-strategy, grant, revoke,
  category-key, localized-names, localized-descrs); the vault refuses such an
  object with -609.

### Changed

- The tests that read files the way a user does run on a committed synthetic
  driver set (`src/test/resources/fixtures/synthetic/`: a driver-set export, a
  single-driver export, the LDIF a vault holds after deploying it) instead of
  skipping without a client export, a Designer project or Designer's package
  catalog; the real-file tests stay as opt-in extras. The package build and
  install round trip runs without Designer.

### Added

- `validate`: `script-required-attribute` (W) — a DirXML-Script element
  missing an attribute Designer's grammar (dirxmlscript 4.7.5 DTD) requires.
  The engine loads such a policy; Designer's importer drops it (ig4's `Send
  expiration email`, a `do-send-email-from-template` with no `template-dn`,
  found 2026-09-21). A warning rather than an error so a deploy of a tree
  that carries one is not refused; the finding names the rule.
- `simulate --env E`: the corpus runs with `overrides/E.properties` applied
  to the tree (and to the `--against` tree), so a case sees a stage's values.

## [0.5.0] - 2026-09-27

### Fixed

- A multi-server deploy could not write: reading the other servers' settings
  closed the connections the deploy had cached for its snapshot, writes and
  verify. The reader no longer closes what the caller handed it; the fake
  vault in tests now refuses use after close, as the real one does.
- The production known-state gate ran `git archive | tar` through a shell
  with the deploy log's commit and the tree path interpolated. It now runs
  git and tar as argument lists, accepts only a 40-hex SHA, removes its temp
  directory on failure, and works on Windows.
- The extended-op LDAPS channel (start/stop/restart, named passwords, cache,
  submit) never checked that the certificate matched the host; it does now
  unless `trustAll` is set.
- A `…Command` secret helper that wrote enough to stderr blocked for ever;
  stderr is drained, its first line joins the error on a non-zero exit, and
  the process is destroyed on failure.
- A secrets-file shim auth id no longer appears on `MISSING SECRET` lines.
- `--driver` naming a driver neither the tree nor the vault has is refused
  instead of reported as "no differences".
- `import-live --env X` with no output directory prints the usage line.
- `driver.trace tail --since` compared engine-local stamps against this
  machine's zone; the cutoff now comes from `date` on the engine host.
- JNDI search enumerations are closed; the server DN is escaped in the LDAP
  filter that derives another server's URL; `doctor` says when a bind was
  cleartext `ldap://`.
- `bin/idm.cmd` checks that the JDK is 21, as `bin/idm` does.

### Changed

- The MCP `apps.*` tools no longer take `insecure`; TLS trust is the
  operator's setting in the environments file, not a tool argument.
- CI runs the JSON and environments/secrets parser tests and the MCP
  server's `npm test`; the spike mains moved out of the product jar into the
  test tree; `OperateCliTest` (a duplicate of the write-gate test) is gone.
- Every fixture-bound test resolves its machine-local file through
  `LocalFixture` (defaults under the user's home; `-Ddirxml.fixture.*` /
  `DIRXML_FIXTURE_*` overrides, now including the e2e directory, the JFW and
  AD exports and Designer's package catalog).

## [0.4.0] - 2026-09-27

### Added

- Overrides reach the driver object's own connection settings and the engine
  control values: `drivers/<driver>.shim-auth-server`,
  `drivers/<driver>.shim-auth-id`, `drivers/<driver>.ecv.<name>`. A Remote
  Loader host, an AD account or a REST base URL now differs per stage the same
  way a GCV does.
- `<driver>.shim-auth-id` in the secrets file, for an auth id that is an OAuth
  client id or API key: applied on diff and deploy, never shown by either,
  never written into the tree by `import-live --env`.
- The per-server model carries `DirXML-ShimAuthServer` and `DirXML-ShimAuthID`
  too (both never-sync, like the config blobs): read per server, kept as
  attributes of `<server dn>` in `driver.xml`, diffed, written through that
  server's connection, and a change of the primary's value fans out to the
  servers without their own.

### Changed

- Maven 3.9 is the minimum. The enforcer range is `[3.9,)`, the same floor its
  message and the install docs already stated. The 0.2.0 note that the build
  accepted Maven 3.6.3 described the range that release enforced; it is no
  longer the requirement.

### Fixed

- A change of a driver's `shim-auth-server` or `shim-auth-id` reached only the
  primary server of a multi-server set.
- `ProjectWriterTest` skips when its Designer project is not on the machine,
  instead of failing with `NoSuchFileException`. The project is not committed.
  `-Ddirxml.fixture.test11` or `DIRXML_FIXTURE_TEST11` points that test at a
  local copy. The same override covers the other tests that hard-code a
  machine-local path (the Amica project, the e2e trees, and the RFI export).
  With nothing set, the historical path is used
  ([docs/install.md](docs/install.md) §2.4).

## [0.3.0] - 2026-09-27

### Added

- Values that differ per stage: `overrides/<env>.properties` beside the tree,
  one file per environment (`drivers/<driver>.gcv.<name>`,
  `drivers/<driver>.shim.<name>`, `driverset.gcv.<name>`). `vault.diff` and
  `vault.deploy --env X` apply X's file to the base; `import-live --env X`
  folds X's values back into that file and keeps the base in the tree;
  `validate` checks the files (`override-malformed`, `override-unknown`,
  `override-missing-env`). Asked for by Norbert Klasen: one tree used to
  deploy the same GCV value to every stage, and an import from one stage
  overwrote it ([docs/getting-started.md](docs/getting-started.md) §3.1).

## [0.2.0] - 2026-09-25

### Changed

- **TLS is verified by default.** An environment's LDAPS connection checks
  the server certificate against the JDK truststore; `<env>.trustAll=true`
  opts in to accepting any certificate (and, as before, skips the host-name
  check an SSH tunnel cannot pass). Before 0.2.0 `trustAll` defaulted to true.
  An environment on a private CA that never set it now needs `trustAll=true`
  or the CA in the truststore ([docs/install.md](docs/install.md) §4.3).
  `import-live` without `--env` likewise needs `-Dldap.trustAll=true`. The
  simulator made the same change (its 1.6.1 and 1.7.0); DirXMLDev pins
  `dirxml-simulator` 1.7.0.
- `driver.trace tail` streams the engine's DirXML trace over LDAP
  (`--ldap`, and the default whenever an environment names no `sshHost`):
  `--follow` or `--seconds N`, `--grep`, `--engine`. The SSH host and the
  trace file are optional; `--since` still needs the file
  ([docs/operate.md](docs/operate.md)).
- Multi-server driver sets: `import-live` reads every server's own driver
  settings (`DirXML-ConfigValues`, `DirXML-ShimConfigInfo`,
  `DirXML-EngineControlValues`) through that server's connection and keeps
  what differs under `drivers/<d>/servers/<server>/`; `vault.diff` reports per
  server; `vault.deploy` writes an override on its server, fans a change of the
  primary's value out to servers without an override, restarts the driver
  there, and snapshots each server (`vault.rollback --server`). Single-server
  sets are unchanged ([docs/vault-deploy.md](docs/vault-deploy.md), "Several
  servers"). Proven against fake servers only so far.
- The build states its minimums up front: Maven 3.6.3 or newer and JDK 21
  (enforcer). `doctor` and `bin/require-engine.sh` name a 4.10.2 engine's
  `xp-1.0.0.jar` when `xp.jar` is missing and say to copy it as `xp.jar`.
- `import-ldif` explains a file that holds no driver set: how many entries,
  whether `objectClass` is present, which classes or `DirXML-*` attributes
  it saw, and what to export instead
  ([docs/getting-started.md](docs/getting-started.md)).
- [docs/agent-assisted-setup.md](docs/agent-assisted-setup.md) is restructured
  around the one kickoff prompt: placeholders first, the prompt, the
  checkpoints, then the alternatives.

### Added

- [DirXML Trace Viewer](https://github.com/PointBlueTechnology/DirXMLTraceViewer)
  integration: `viewer.install` (a release from GitHub, SHA-256 checked, or
  `--source` to clone and build), `viewer.check`, a `doctor` line, and
  `driver.trace view --env E [--driver D]` / `--file F` to open the desktop
  viewer connected to a vault with a driver selected, or on a trace file, the
  password on its stdin. Needs viewer 1.2.0 ([docs/install.md](docs/install.md) §5.2).
- `package.status --strict`: exit 1 and every violation named — a customised
  packaged object, a package stamped on objects with no installed record, and
  with `--catalog` a version the catalog lacks — for a client whose auditors
  forbid customised released packages; a pull-request template in
  [docs/examples](docs/examples/PULL_REQUEST_TEMPLATE.md)
  ([docs/packages.md](docs/packages.md) §5.1).
- [docs/agent-assisted-setup.md](docs/agent-assisted-setup.md): paste-ready
  prompts for a coding agent to install and configure DirXMLDev. Linked from
  the README, the docs index, and the manual install page.
- The Active Directory policy-flow fishbone on the README.

### Removed

- The lab `deploy-log/` audit trail is no longer in the tree. `deploy-log/` is
  gitignored in this repository so a local log is not committed. A client
  repository still commits its own `deploy-log/`.

### Fixed

- `mvn test` needs the engine jars as regular files in a real `lib/` directory
  ([docs/install.md](docs/install.md)). Maven's file check reports a directory
  symlink of `lib/`, and per-jar symlinks, as missing. The install docs say to
  copy the jars.
- The portable CI build compiles everything `doctor` depends on.

## [0.1.0] - 2026-09-24

First public version line of the Designer-optional Identity Manager toolchain
already described in the [README](README.md). Phases 0–7 (typed model and
IDM-as-code, validation, edit operations, vault deploy with safeguards,
driver operations, Designer round-trip, and package management), JSON
provisioning forms, workflow authoring (`flow.*`), AppConfig, entitlements,
vault clone, and the read-only policy-flow viewer were already built and
exercised on lab vaults before this cut. 0.1.0 records that toolchain as a
release and includes the release-facing pieces below. It does not introduce
a new engine or a new command set; `bin/idm` with no arguments remains the
contract.

### Added

- MIT license for the DirXMLDev source ([LICENSE](LICENSE)). Proprietary
  NetIQ/OpenText engine jars, and any third-party simulator packaging, stay
  under their own terms and are not redistributed by this license.
- User-facing documentation: [getting started](docs/getting-started.md),
  [install](docs/install.md), [day to day](docs/day-to-day.md),
  [agent guide](docs/agent-guide.md), and sanitized
  [examples](docs/examples/). The index is [docs/README.md](docs/README.md).
- Agent-agnostic setup. [docs/agents.md](docs/agents.md) is the instructions
  for any agent that can read files and run a shell. A client repository
  commits the [AGENTS.md template](docs/examples/client-AGENTS.md). The Claude
  Code skill at `.claude/skills/dirxml-dev/` remains one loader for the same
  loop, not a second product.
- Optional gated MCP server in [`mcp/dirxmldev-mcp`](mcp/dirxmldev-mcp),
  documented in [docs/mcp.md](docs/mcp.md). It shells out to `bin/idm`. Reads
  and dry-runs are on by default. Vault deploy, rollback, and driver
  start/stop/cache clear stay off unless `IDM_AGENT_ALLOW_WRITE=1` and the
  call sets `confirm`.
- `bin/idm doctor` checks JDK 21, the simulator jar, and `lib/*.jar`.
  `--json` prints the same report as JSON. Optional `--env` also parses
  `environments.properties` and probes LDAPS for that environment, without
  printing passwords or bind DNs.
- Agent write gate. Commands that change a vault (`vault.deploy --yes` or
  `--step`, `vault.rollback --yes`, `vault.import-clone --yes`, and the
  operate commands that change a driver) refuse unless
  `IDM_AGENT_ALLOW_WRITE=1` or the command includes `--confirm <env>` for
  that environment. The check runs before secrets are resolved and before
  LDAP is opened. Production tier rules still apply on top of this gate.
- GitHub Actions workflow (`.github/workflows/test.yml`) whose default `test`
  job stays green without the proprietary jars. It runs
  `mvn -B -Pidm.portable test` (doctor and the write gate against a stub LDAP
  client). The full engine suite (`mvn -B test`) is a separate `engine` job,
  run only when the repository variable `RUN_ENGINE_TESTS` is `true` on a
  runner that already has the jars and the simulator.
