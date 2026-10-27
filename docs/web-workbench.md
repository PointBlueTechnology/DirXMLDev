# DirXMLDevWeb: the browser workbench

[DirXMLDevWeb](https://github.com/PointBlueTechnology/DirXMLDevWeb) is a browser workbench over
this core. It does Designer's job on the same IDM-as-code tree: the outline and the policy flow,
structured editors, compare and deploy against a vault, live trace, the simulator, and, on a
server, projects, branches, pull requests, sign-in and audit. It is MIT licensed, a separate
repository, and in beta.

It is a third client of the core, next to `bin/idm` and the [MCP server](mcp.md). The page calls
the same typed operations the CLI runs, so it has the same validation, the same refusals and the
same production gate. It is not a port of Designer and contains no OpenText code. The tree on disk,
in git, is the only state: change it in the page, with `bin/idm` or with an agent, and each one
sees the others' changes.

## When to use it

DirXMLDev on its own is enough when the people doing the work are comfortable at a shell, or the
work is done by an agent or a pipeline. Add DirXMLDevWeb when one of these is true:

| You want | DirXMLDev alone | With DirXMLDevWeb |
|---|---|---|
| To see a driver set | `query`, `show`, `docs`, the fishbone in VS Code or Cursor | The Architecture view, the outline, the policy flow, GCVs with every policy that reads them, the schema map, a PRD's workflow drawn as a picture |
| To edit a policy | The XML in your editor, or an operation such as `policy.add` | A structured *Rules* editor for DirXML Script, or the source; the draft goes through the engine's compiler as you type |
| To edit a filter, GCVs, settings, entitlements, forms, a mapping table | `gcv.set`, `form.field.add` and the other operations, or the files | A form for each, with a column per environment for the values that differ per stage; mapping tables to and from CSV |
| To review a change before it is written | `--dry-run` on each operation | One review for every write: each file before and after, side by side, then *Apply* as one transaction |
| People who know Designer, not a shell | Training on `bin/idm` | Pages laid out the way Designer lays them out |
| A team on one driver set | Each person's checkout, and git by hand | One server: a branch per person, commits, pull requests through GitHub or GitLab, merging conflicts in the page, sign-in through your identity provider, groups as roles, an audit log |
| Vault passwords kept off laptops | Each person's `secrets-<env>.properties` or Keychain | On a server, each person's own sign-in in an owner-only file, or a secret manager for deployers |
| To compare and deploy | `vault.diff`, `vault.deploy --dry-run`, then `--yes` | *Compare*, *Plan* and *Deploy* as three steps with their state; the environment's name typed for production; rollback to any snapshot |
| Live trace | `driver.trace tail --ldap`, or the desktop Trace Viewer | The trace streamed into the page from one server or all of them, with filters, find, a pop-out window and recording to a file |
| A project per environment | `tree.compare`, `promote.plan`, `promote.apply` ([promotion.md](promotion.md)) | *Compare projects*: the differences grouped by driver, a tick and a line merge per item, a new branch and a pull request |
| Agents | The MCP server here, over a checkout | `/mcp` on the server: personal tokens, and the agent acts as you, under your roles, with the same plan and confirmation as the page |

## What stays on the command line

The page covers the everyday loop. These are still `bin/idm` only; the image carries the CLI, so
they are available beside the page (`docker exec idmweb idm …`):

- `vault.deploy --step`, a question per change. In the page, deploy in parts by deploying one
  driver at a time.
- `package.import` of a jar from disk. The page installs packages from update sites.
- `simulate --against` another tree. The page runs cases and creates plain ones; a case's other
  keys are edited in its files.
- Scripts and pipelines. The page is for people; CI and Terraform ([terraform.md](terraform.md))
  call `bin/idm`.

The full list is DirXMLDevWeb's
[docs/limits.md](https://github.com/PointBlueTechnology/DirXMLDevWeb/blob/main/docs/limits.md).

## Three ways to run it

| | Who | What you get |
|---|---|---|
| Local | One person, Docker on their own machine | `http://localhost:8080`, one folder on disk (`~/idmweb/`) with a git repository, projects and branches |
| Server | A team, behind a proxy with TLS | Sign-in through OpenID Connect (Keycloak, Entra, Okta and others), the team's repository on GitHub or GitLab, a branch per person, audit |
| Workstation | A developer, from a checkout | `bin/idmweb serve <tree>` on loopback, one tree edited in place, no sign-in and no audit |

Local and server are the same image. The engine jars are optional for both: without them the page
does everything except the engine's compile check and the simulator. The jars are mounted from a
folder on the host and never go into the image.

Start with DirXMLDevWeb's
[quickstart](https://github.com/PointBlueTechnology/DirXMLDevWeb/blob/main/docs/quickstart.md)
(two commands), then its
[tutorial](https://github.com/PointBlueTechnology/DirXMLDevWeb/blob/main/docs/tutorial.md), from
reading a vault to a first deploy. Every view is listed in its
[features](https://github.com/PointBlueTechnology/DirXMLDevWeb/blob/main/docs/features.md).

## Versions

Each DirXMLDevWeb release is built on one release of this core, and the image carries that core's
`idm`, `sim` and `apps` CLIs. The page's top bar shows both versions, and `/api/session` returns
them. When you use the page and your own `bin/idm` on one tree, keep them on the same core
release so both validate it the same way and write the same files.

The design that led to it, and why it is a page over this core rather than Designer ported to a
browser or compiled to WebAssembly, is [web-designer.md](web-designer.md).
