# Changelog

All notable changes to DirXMLDev are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Static-RSA key exchange (`TLS_RSA_*`) stays enabled by default. eDirectory's LDAPS listener
  often offers only those suites, and recent JDK builds (24+, and the 21 updates from mid-2026)
  disable them, which made a bind to such a vault fail with "simple bind failed" although the
  credentials were right. `LegacyTls.enable()` runs at start-up and before every vault
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
