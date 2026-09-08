# Agent guide — DirXML Dev

Agent-driven IDM (DirXML) development on top of the DirXML Policy Simulator.
Read `docs/plan.md` before working here; it holds the architecture, the phases,
the decisions already made, and the **non-negotiable safeguards**.

## Ground rules

- **Source of truth is IDM-as-code** (files in this repo / a client repo), never the
  live vault. Designer is an import/export target.
- **Nothing writes to a vault** unless it goes through validate → simulate → diff →
  snapshot. No exceptions, including spikes: spikes write only to scratch objects
  in the **test** vault and clean up after themselves.
- **Package overrides are the supported customization method** — make them easy
  and always set the modified/customized flag; never edit packaged content silently.
- The proprietary IDM jars live in `lib/` (gitignored) — `lib/` may symlink to the
  simulator's `lib/`. Client artifacts, LDIFs, traces, and credentials are never
  committed.

## Building blocks you can reuse (from the simulator, on the classpath)

`DesignerProject` / `DriverExport` / `LdifDriverSource` / `JndiLdapSearch`
(readers), `Case` + `ChannelSimulator` (run policies), `BatchRunner` / `Harvester`
/ `Comparer` (regression), `DxCacheReader` (DirXML extended-op pattern),
`XmlCompare.canonical` (canonical XML), `MappingTableSource`, `GcvReferences`.

## Test environment

A test vault exists for spikes and integration tests; its connection details live
in a local, gitignored properties file — ask the user if you don't have them.
