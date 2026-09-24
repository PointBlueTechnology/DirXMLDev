# Examples

Fictional samples for the guides. Nothing here is a customer driver, a real
host, or a real password. Copy the shape, then replace the names.

Command lines match `bin/idm` usage (`Cli` and the edit-operation registry) and
`bin/apps --help`. If a flag here ever disagrees with `bin/idm` on your
machine, follow `bin/idm`.

| File | What it is |
|---|---|
| [environments.properties.example](environments.properties.example) | Staging and production targets, passwords via environment variables |
| [secrets.properties.example](secrets.properties.example) | Driver secrets, also via environment variables |
| [normalize-title.policy.xml](normalize-title.policy.xml) | A one-rule DirXML Script policy for `policy.add --content-file` |
| [first-session.md](first-session.md) | The first import, a policy change, and a dry-run, as a script |
| [client-AGENTS.md](client-AGENTS.md) | `AGENTS.md` to commit in a client repo so any agent sees the rules |

Do not commit a filled-in `environments.properties` or `secrets*.properties`.
The `.example` suffix is what keeps these templates out of that ignore rule.
