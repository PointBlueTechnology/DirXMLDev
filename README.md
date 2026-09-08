# DirXML Dev

**Agent-driven IDM (DirXML) development — Designer-optional.** A typed model of an
Identity Manager driver set, an **IDM-as-code** representation on disk, validation,
and vault **deploy / operate** with safeguards — with the
[DirXML Policy Simulator](https://github.com/PointBlueTechnology/DirXMLSimulator)
as its test engine.

Status: **Phase 3 complete — edit operations, simulate gate, CLI.** Phases 0–2:
typed model, IDM-as-code with readers for export / Designer project / LDIF /
live vault (byte-idempotent), and `validate` — every policy through the engine's
own compilers in the driver's context, plus linkage, GCV, mapping-table,
ECMAScript and filter/schema-map checks (running production vaults validate
with zero errors). Phase 3: the reference-aware edit operations as validated
transactions, package-aware overrides, `export` (Designer imports it), and
`simulate` (the regression corpus against the tree, diffed against the tree
before the edit). CLI only by decision — no MCP server. Next: Phase 4, vault
deploy with safeguards. See [docs/plan.md](docs/plan.md),
[docs/edit-operations.md](docs/edit-operations.md) and
[docs/agent-guide.md](docs/agent-guide.md).

```bash
bin/idm import <export.xml> <outDir>          # driver / driver-set export → IDM-as-code
bin/idm import-project <projectDir> <outDir>  # Designer project → IDM-as-code
bin/idm import-ldif <dump.ldif> <outDir>      # LDIF of the driver-set subtree → IDM-as-code
IDM_JAVA_OPTS="-Dldap.url=ldaps://host:636 -Dldap.bindDn=… -Dldap.password=…" \
  bin/idm import-live <driverSetDN> <outDir>  # live vault → IDM-as-code
bin/idm check <asCodeDir>                     # load a tree, report it, exit 1 on broken links
bin/idm validate <asCodeDir> [--json]         # every validation check; exit 1 on any error
bin/idm export <asCodeDir> <out.xml>          # a Designer driver-set export of the tree
bin/idm simulate <asCodeDir> --cases <dir> [--against <asCodeDir>]   # the regression corpus, diffed
bin/idm query <asCodeDir> chain <driver> sub  # orient: artifacts | chain | gcvs | tables; show; refs
bin/idm policy.add <asCodeDir> --driver D --scope subscriber --name X --link subscriber-command
bin/idm <operation> <asCodeDir> --… [--dry-run] [--force] [--json]   # bin/idm with no args lists them all
```

The edit operations (policies, rules, links, GCVs, filter, schema map, driver
settings, mapping tables) are transactions: load → apply → validate → write only
if no new error. See [docs/agent-guide.md](docs/agent-guide.md).

## What this is

The simulator already gives an agent the *read* and *test* halves of IDM work
(load a driver set from a Designer project / export / LDIF / live LDAP; run real
policies headlessly; regression corpus; compare; coverage). This repo adds the
*write, deploy, and operate* halves:

- a **typed, reference-aware model** of the driver set (policies, filters, GCVs,
  resources, mapping tables, schema map, packages) with canonical serialization;
- **IDM-as-code** — one readable, git-versioned file per object as the source of truth;
- **validation** (DTD, XSLT/ECMAScript compile, linkage, GCV/mapping-table refs, schema);
- **vault deploy** over LDAP + the DirXML extended operations, with diff/dry-run,
  snapshot/rollback, environment gating, and package-aware overrides;
- **driver operations** (start/stop/restart, state, cache, migrate, passwords);
- **provisioning forms** (Track P — the form builder for PRDs);
- surfaced as a **CLI** and an **MCP server** with destructive tools annotated.

## Requirements

JDK 21, Maven, and the NetIQ/OpenText IDM jars in `lib/` (proprietary; never
committed — `lib/` may be a symlink to the simulator's `lib/`). Build the simulator
first (`mvn install` in its repo) so `dirxml-simulator` resolves from `~/.m2`.

```bash
export JAVA_HOME=.../zulu-21
mvn test
```

## Layout

- `docs/plan.md` — the plan (architecture, phases, decisions, safeguards).
- `docs/spikes/` — Phase 0 spike findings.
- `src/` — (Phase 1+) the model, as-code, validation, deployer.
