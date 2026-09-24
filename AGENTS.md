# Agent guide — DirXML Dev

Agent-driven IDM (DirXML) development on top of the DirXML Policy Simulator.
Read `docs/plan.md` before working here; it holds the architecture, the phases,
the decisions already made, and the **non-negotiable safeguards**.

Operating a client driver set with `bin/idm` is [docs/agents.md](docs/agents.md)
and [docs/agent-guide.md](docs/agent-guide.md). Those pages are for any agent.
This file is for changing DirXMLDev itself.

## Ground rules

- **Source of truth is IDM-as-code** (files in this repo / a client repo), never the
  live vault. Designer is an import/export target.
- **Nothing writes to a vault** unless it goes through validate → simulate → diff →
  snapshot. No exceptions, including spikes: spikes write only to scratch objects
  in the **test** vault and clean up after themselves.
- **Package overrides are the supported customization method** — make them easy
  and always set the modified/customized flag; never edit packaged content silently.
- The proprietary IDM jars live in `lib/` (gitignored) — `lib/` may be a directory
  symlink to the simulator's `lib/`, or a real directory the jars are copied into.
  Client artifacts, LDIFs, traces, and credentials are never
  committed.
- **`bin/idm validate` after every edit to an as-code tree**, and read the errors
  as the engine's own verdict — they come from its compilers, in the driver's
  context (`docs/validation.md`). A validator check that fires on a *running*
  production vault is a bug in the check, not the vault: calibrate on real driver
  sets before an ERROR ships.

## Building blocks you can reuse (from the simulator, on the classpath)

`DesignerProject` / `DriverExport` / `LdifDriverSource` / `JndiLdapSearch`
(readers), `Case` + `ChannelSimulator` (run policies), `BatchRunner` / `Harvester`
/ `Comparer` (regression), `DxCacheReader` (DirXML extended-op pattern),
`XmlCompare.canonical` (canonical XML), `MappingTableSource`, `GcvReferences`.

## Test environment

A test vault exists for spikes and integration tests; its connection details live
in a local, gitignored properties file — ask the user if you don't have them.
