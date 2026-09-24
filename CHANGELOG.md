# Changelog

All notable changes to DirXMLDev are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Removed

- The lab `deploy-log/` audit trail is no longer in the tree. `deploy-log/` is
  gitignored in this repository so a local log is not committed. A client
  repository still commits its own `deploy-log/`.

### Fixed

- `mvn test` needs the engine jars as regular files in a real `lib/` directory
  ([docs/install.md](docs/install.md)). Maven's file check reports a directory
  symlink of `lib/`, and per-jar symlinks, as missing. The install docs say to
  copy the jars.

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
