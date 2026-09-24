# Installing and building DirXMLDev

This page is for someone who still has to **build** `bin/idm`. If the tool is
already on your machine, skip to [getting-started.md](getting-started.md):
client directory, a redacted `environments.properties`, and the first
`import-live` / `validate` / `vault.diff`.

It covers what to install, what goes in the two private configuration files,
and how to check that each part works. Day-to-day use is in
[day-to-day.md](day-to-day.md); the long form of the same loop is
[walkthrough.md](walkthrough.md).

Everything is a command-line tool: `bin/idm` (the tool) and `bin/apps` (the
Identity Applications helper). There is no server, no database and no GUI.

## 1. What you need

| Requirement | Why | Check |
|---|---|---|
| **JDK 21** | the tool and the policy simulator are Java 21 | `/usr/libexec/java_home -v 21` (macOS) or `java -version` |
| **Maven 3.9+** | build | `mvn -v` |
| **git** | the tree is versioned; the deploy gate reads commits | `git --version` |
| **Python 3.8+** | only for `bin/apps` (standard library, nothing to install) | `python3 --version` |
| **The DirXML Policy Simulator** built and installed locally | the engine that validates and simulates policies | `ls ~/.m2/repository/com/pointblue/dirxml/dirxml-simulator/` |
| **The Identity Manager engine jars** | proprietary; the simulator and the tool load the real policy compilers and the LDAP/extended-operation client | see §2 |
| **Network access to the vault** | LDAPS to the eDirectory server that holds the driver set (port 636) | `nc -z host 636` |
| Optional: **SSH to the engine host** | `driver.trace tail` reads the trace file over SSH | `ssh user@host true` |
| Optional: **Designer 4.8+** on the workstation | only for `form.edit` (the vendor's JSON form builder is a Designer plugin) | `bin/idm form.edit --check` |
| Optional: **the Identity Applications** URL and an application user | `bin/apps` runs a workflow end to end without a browser | `bin/apps --env <env> token` |
| Optional: **kubectl/SSH to the applications host** | reading the workflow engine log | your own `kubectl` or SSH access |

Roles and resources are not in scope: they are managed in the Identity
Applications, not in Designer and not by this tool.

## 2. Install

### 2.1 The simulator and the engine jars

```bash
git clone https://github.com/PointBlueTechnology/DirXMLSimulator ~/IdeaProjects/DirXMLSimulator
```

Put the engine jars from your Identity Manager installation into the
simulator's `lib/` (they are never committed; the directory is gitignored):
`dirxml.jar`, `dirxml_misc.jar`, `nxsl.jar`, `xp.jar`, `js.jar`, `jclient.jar`,
`ldap.jar`, `XDS.jar`, `dhutil.jar`, `CommonDriverShim.jar`. They come from
the engine server (`/opt/novell/eDirectory/lib/dirxml/classes/`) or from the
Designer install. Then build and install the simulator so Maven can find it:

```bash
cd ~/IdeaProjects/DirXMLSimulator && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q install
```

### 2.2 DirXMLDev

```bash
git clone <this repository> ~/IdeaProjects/DirXMLDev
cd ~/IdeaProjects/DirXMLDev
ln -s ~/IdeaProjects/DirXMLSimulator/lib lib          # the same jars; lib/ is gitignored
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test  # builds and runs the test suite
bin/idm                                                # prints every command
```

`bin/idm` finds JDK 21 by itself (`IDM_JAVA_HOME` overrides), compiles on first
use if `target/classes` is missing, and puts `target/classes`, `lib/*.jar` and
the simulator jar on the class path. `IDM_SIM_VERSION` selects another
installed simulator version; `IDM_JAVA_OPTS` passes JVM options through (used
for `import-live` credentials and for TLS settings, below).

Windows: `bin\idm.cmd` is the equivalent launcher (`IDM_JAVA_HOME` or
`JAVA_HOME` must point at a JDK 21; the simulator jar is found under
`%USERPROFILE%\.m2`); `bin/idm` also works from Git Bash or WSL. For the common
Windows task — a fresh Designer project from a vault without Designer touching
the vault — see [howto-fresh-designer-project.md](howto-fresh-designer-project.md).
To carry a customer's vault home as a zip and stand it up as a test vault, see
[howto-clone-vault.md](howto-clone-vault.md).

### 2.3 Use it from an agent

Any agent that can run a shell uses `bin/idm`. There is no separate agent
protocol. Read [agents.md](agents.md): commit an `AGENTS.md` in the client
repository (template in [examples/client-AGENTS.md](examples/client-AGENTS.md)),
and point the agent at [agent-guide.md](agent-guide.md).

Claude Code, when that is the agent, also loads `.claude/skills/dirxml-dev/`
automatically in this checkout. Other agents do not need that directory.

An optional MCP server in `mcp/dirxmldev-mcp` wraps reads, dry-runs, and a
few gated writes. It is not required. [mcp.md](mcp.md) is the wiring.

### 2.4 Check the workstation (`doctor`) and what CI runs

`bin/idm doctor` reports whether this machine can run the tool. It checks JDK 21,
the simulator jar at the version this repo pins (`1.5.2`, or `IDM_SIM_VERSION`),
and the ten proprietary jars in `lib/`. It does not print passwords, bind DNs, or
URLs.

```bash
bin/idm doctor                 # one line per check; exit 1 when something is missing
bin/idm doctor --json          # the same report as JSON (ok, checks[])
bin/idm doctor --env stg       # also parse environments.properties and probe LDAPS
```

`--env` reads `environments.properties` (or `IDM_ENVIRONMENTS`) and lists each
environment's name, tier, and whether a URL, a bind DN, a password, and a driver
set are configured. The LDAPS probe runs only for the named environment. A refused
handshake or a bad password is reported as a failure; the password and the bind DN
are not printed. With no JDK and no built classes, the shell launcher still prints
the JDK, simulator, and `lib/` lines so a broken workstation is diagnosable before
`mvn` succeeds.

**Agent write gate.** `vault.deploy` with `--yes` or `--step`, `vault.rollback --yes`,
`vault.import-clone --yes`, and the operate commands that change a driver
(`driver.start|stop|restart|migrate|resync|submit`, `driver.cache clear`,
`driver.secrets set|remove`, `driver.trace set|reset`) refuse to run unless
`IDM_AGENT_ALLOW_WRITE=1` or the command includes `--confirm <env>` for that same
environment. `--dry-run` and read-only commands are not writes. The tier rules in
§4 still apply on top of this gate; the gate is what stops an agent from mutating
a vault when neither the environment variable nor an explicit confirm is present.
It is checked before any secret is resolved and before LDAP is opened.

**CI.** GitHub Actions (`.github/workflows/test.yml`) has two jobs:

| Job | When it runs | What it runs |
|---|---|---|
| `test` | every push and pull request | workflow YAML, shell syntax, `bin/require-engine.sh --inform` (missing jars are a message, not a failure), the `doctor --json` shell fallback, and `mvn -B -Pidm.portable test` |
| `engine` | only when the repository variable `RUN_ENGINE_TESTS` is `true` | `bin/require-engine.sh` (failure if jars are missing) and `mvn -B test` |

`idm.portable` compiles doctor, the environment parser, and the write gate against
a stub LDAP client and runs `DoctorTest` and `AgentWriteGateTest`. It does not
compile the rest of the tree. A normal `mvn test` is still the full suite: the
enforcer in `pom.xml` stops it with the jar list when `lib/` or the simulator is
absent, which is what you want on a workstation where you meant to compile
everything.

A clean GitHub-hosted runner has no proprietary jars, so `test` is the required
check and `engine` stays skipped. To run the full suite in Actions, on a
self-hosted runner that already has the simulator in `~/.m2` and the jars on disk:

1. Set the repository variable `RUN_ENGINE_TESTS` to `true`.
2. Set `ENGINE_RUNNER` to that runner's label (`ubuntu-latest` is the default).
3. Set `IDM_LIB` to the absolute path of the directory that holds the ten jars.
   The engine job symlinks it to `lib/`. Do not commit the jars.

Locally, with the jars installed as in §2.1, `mvn -B test` is the full suite.
Without them, `mvn -B -Pidm.portable test` is the subset CI runs.

## 3. A working directory per client

The tool never keeps client content in this repository. Make one directory
(ideally its own git repository) per client or per environment family:

```
client-acme/
  environments.properties     vault targets and tiers          — gitignored, never committed
  secrets-stg.properties      what the tree cannot carry       — gitignored, never committed
  secrets-prd.properties
  tree/                       the driver set as code (bin/idm import-* writes it) — committed
  cases/                      the simulator's regression corpus — committed
  deploy-log/<env>.jsonl      one line per deploy/operation (the tool appends) — committed
  deploy-snapshots/<env>/     LDIF taken before every write (the tool writes) — gitignored
  catalog/                    package jars fetched from the update site (optional) — committed
```

Add `environments.properties`, `secrets*.properties`, `deploy-snapshots/` and
`*.ldif` to that repository's `.gitignore` before the first commit. Every
command takes the tree directory as its first argument and `--env <name>` for
anything that touches a vault; run them from the client directory so
`environments.properties` is found (or point `IDM_ENVIRONMENTS` at it).

## 4. `environments.properties` — the vault targets

One block per environment; the prefix is the environment's name, which is what
you type after `--env` and, for production, after `--confirm`.

```properties
# --- staging -------------------------------------------------------------
stg.url=ldaps://idm-stg.example.com:636        # LDAPS to the vault (required)
stg.bindDn=cn=idm-deploy,ou=sa,o=system        # a deploy identity with rights on the driver set (required)
stg.passwordKeychain=idm-stg/cn=idm-deploy     # the bind password from the macOS Keychain (§4.2), or
                                               #   stg.passwordCommand=op read "op://idm/stg-deploy/password"
                                               #   stg.passwordEnv=IDM_STG_PASSWORD
                                               #   stg.password=…   (a literal — dev labs only)
stg.driverSet=cn=driverset1,o=system           # the driver set this environment means (required)
stg.tier=stg                                   # dev | stg | prd — decides the gate (required)
stg.secrets=secrets-stg.properties             # the secrets file for this environment (§4.1)
stg.sshHost=idm-stg.example.com                # engine host, for driver.trace tail (optional)
stg.sshUser=idm                                # ssh user; key-based login is assumed (optional)
stg.formsUrl=https://idm-stg-apps.example.com  # the Identity Applications base URL (optional; forms + bin/apps)
stg.appsUser=uaadmin                           # bin/apps: the user that requests and approves (optional)
stg.appsPasswordKeychain=idm-stg-apps/uaadmin  # bin/apps: that user's password — same four forms as above (optional)
stg.appsClient=rbpmrest                        # bin/apps: OSP client id (optional, default rbpmrest)
stg.appsSecretKeychain=idm-stg-apps/rbpmrest   # bin/apps: OSP client secret when it differs from the password (any form)
stg.k8sHost=k3s.example.com                    # containerised applications: host, user, namespace and the
stg.k8sUser=debian                             #   kubectl command to run there — only for reading the
stg.k8sNamespace=idm                           #   applications' log during a live proof (optional)
stg.kubectl=sudo -n k3s kubectl

# --- production ----------------------------------------------------------
prd.url=ldaps://idm.example.com:636
prd.bindDn=cn=idm-deploy,ou=sa,o=system
prd.passwordEnv=IDM_PRD_PASSWORD               # the password comes from that environment variable
prd.driverSet=cn=driverset1,o=system
prd.tier=prd
prd.secrets=secrets-prd.properties
prd.requires=stg                               # a green stg deploy of the same tree commit is required first
```

**The deploy identity.** Give each environment its own eDirectory user with
rights only on its driver set (and the User Application driver's `AppConfig`
subtree when forms/PRDs are deployed). The tool writes nowhere else; `admin`
works on a lab but should not be the identity in `prd`.

**Every credential takes four forms.** For any key `k` that holds a
password (`password`, `appsPassword`, `appsSecret`, and every key of the
secrets file below) the file may carry `k=` (a literal), `kEnv=VAR` (an
environment variable), `kCommand=…` (a shell command whose output is the
value, e.g. a password-manager CLI) or `kKeychain=service[/account]` (the
macOS Keychain, §4.2). Use an indirect form for anything but a throwaway lab:
the file then holds names, not secrets. The tool warns once per run when
either file is readable by other users; keep them at mode 600:

```bash
chmod 600 environments.properties secrets-*.properties
```

**Tiers and the gate.** Every deploy, including `--dry-run`, runs `validate`
first and refuses a tree that has errors. Writing needs `--yes` (the whole
plan, after a snapshot) or `--step` (confirm and verify each change). On a
`prd` tier the write also needs `--confirm` followed by that environment's
name, a committed tree with no uncommitted changes under it, a vault that
matches the last recorded deploy (or `--capture-drift` first), and
`<env>.requires` satisfied when it is set. `simulate` is how you prove a
policy change against a corpus; the production gate does not run the corpus
itself. The design note is [vault-deploy.md](vault-deploy.md), "Environments
and gating". The operator's version is [day-to-day.md](day-to-day.md). Any
command that would change the vault also needs `IDM_AGENT_ALLOW_WRITE=1` or
`--confirm <env>` (§2.4); `--confirm prd` satisfies both the production tier
and that gate.

**TLS trust.** When `<env>.trustAll` is omitted it defaults to true: LDAPS
accepts the server certificate without checking the JDK truststore. Set
`<env>.trustAll=false` once the vault CA is in the JDK truststore (see §4.3).

### 4.1 `secrets-<env>.properties` — what the tree cannot carry

Exports, projects and the tree hold no passwords. A driver needs them at
deploy time, so they live in a per-environment file the tool reads and never
prints, snapshots or logs:

```properties
AD Driver.shim-auth-password=…                 # DirXML-ShimAuthPassword
AD Driver.remote-loader-password=…
AD Driver.named.exchange-service=…             # a named password the policies read
driverset.named.smtp-relay=…                   # a named password on the driver set
# any key can be indirect instead of literal (preferred):
AD Driver.shim-auth-passwordEnv=AD_SHIM_PW                       # from an environment variable
AD Driver.named.exchange-serviceCommand=op read "op://idm/exchange/password"   # from a command's output
AD Driver.remote-loader-passwordKeychain=idm-stg/ad-remote-loader             # from the macOS Keychain (§4.2)
```

A new driver's deploy refuses until every secret the driver needs is present
(`MISSING SECRET: <driver>.<key>` in the plan). An existing driver's secrets
are not touched unless you pass `--secrets all|missing`.

### 4.2 macOS Keychain

On a Mac the Keychain is the simplest place for these passwords: nothing is
on disk in clear, and the login keychain unlocks with the session. Add one
item per credential (the `-w` without a value prompts, so the password never
enters shell history), then reference it by service and account:

```bash
security add-generic-password -s idm-stg -a cn=idm-deploy -w          # the stg bind password
security add-generic-password -s idm-stg-apps -a uaadmin -w           # the applications user
security add-generic-password -s idm-stg -a ad-remote-loader -w       # a driver secret
```

```properties
stg.passwordKeychain=idm-stg/cn=idm-deploy
stg.appsPasswordKeychain=idm-stg-apps/uaadmin
# secrets-stg.properties
AD Driver.remote-loader-passwordKeychain=idm-stg/ad-remote-loader
```

The tool reads it with `security find-generic-password -s <service> -a
<account> -w`; `service` alone (no `/account`) takes the first item with that
service name. An item added with `security` is readable by `security` without
a prompt; one created in Keychain Access asks once for permission — answer
*Always Allow*. When the item is missing, the error names the exact
`add-generic-password` command to run. Linux and Windows have no equivalent
built in; use `kCommand` with your password manager's CLI (`op read`,
`pass show`, `secret-tool lookup`, …).

### 4.3 TLS, and when Java cannot reach the vault

The JDK must trust the vault's LDAPS certificate: import the CA into the JDK's
`cacerts` (`keytool -importcert -cacerts -alias idm-ca -file ca.pem`) or into a
truststore named with `IDM_JAVA_OPTS=-Djavax.net.ssl.trustStore=…`. When the
certificate's subject does not match the host name you connect to (a lab, an
SSH tunnel), add `-Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true`
to `IDM_JAVA_OPTS` — never in production.

If `ldapsearch`/`curl` reach the vault but every Java command reports
"No route to host", forward the port over SSH through a host that can reach it
and define a second environment that points at the tunnel:

```bash
ssh -f -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L 6636:idm-vault:636 user@jump-host
```

```properties
stgtun.url=ldaps://127.0.0.1:6636    # everything else identical to stg
```

## 5. The vendor form builder (optional)

`bin/idm form.edit` opens a JSON provisioning form in OpenText's own form
builder, which ships only as a Designer plugin. Install Designer 4.8 or later
on the workstation (or copy the plugin's `lib` directory from a Designer
install and point `IDM_FORMBUILDER` at it), then:

```bash
bin/idm form.edit tree/ "Help-desk Request Form" --check
```

`--check` reports which executable it will use and prints the one-time fixes
each OS needs (Gatekeeper quarantine on macOS, SmartScreen on Windows, execute
bits and `--no-sandbox` on Linux). It never runs them. On Apple silicon the
vendor bundle is Intel-only and runs under Rosetta; the sibling
DesignerModernPlatform project rebuilds it on native Electron. Everything
else about forms (`form.field.add`, `form.preview`, `prd.map`, …) needs no
builder.

## 6. The Identity Applications (optional)

Nothing here is needed to author or deploy forms and workflows — only to
*prove* them at runtime. With `formsUrl`, `appsUser`, `appsPassword` set (§4):

```bash
bin/apps --env stg token                       # OSP password grant works
bin/apps --env stg permission "Widget Access"  # the PRD as the applications see it (is it indexed? which form?)
bin/apps --env stg request "Widget Access" --data reason="proof"   # start it
bin/apps --env stg tasks                       # the approval task
bin/apps --env stg approve <taskId> --comment "ok"
bin/apps --env stg history
```

The bodies these calls send, the required fields and the accepted values are
in [idapps-rest.md](idapps-rest.md). One timing fact matters: a PRD deployed
less than about ten minutes ago is not yet in the applications' permission
index and `request` reports it as not started.

## 7. Packages (optional)

To add packaged drivers or install packages the way Designer does, keep a
catalog of package jars in git:

```bash
bin/idm package.fetch --catalog catalog/ --short NOVLADBASE --short NOVLADDCFG   # newest of each; repeat --short (a comma is not a separator)
bin/idm package.list  --catalog catalog/ --base
```

Details and the install/upgrade/uninstall commands: [packages.md](packages.md).

## 8. Check the installation

Run these once; every one should succeed before the first real change.

```bash
bin/idm                                            # usage: the tool runs
bin/idm engine.version --env stg                   # LDAPS + bind + driver set found
bin/idm driverset.status --env stg                 # every driver: state, start option, cache
bin/idm import-live tree/ --env stg                 # the driver set as code (url, bind, password, driver set from the environment)
bin/idm validate tree/                             # the real compilers load every policy; expect 0 errors
bin/idm vault.diff tree/ --env stg                  # "no differences" right after an import
bin/idm form.edit tree/ "<any request form>" --check   # only if you will use the builder
bin/apps --env stg token                            # only if you will use the applications
```

`validate` on a production vault's tree normally reports zero errors and a
handful of warnings/infos (template placeholders, missing localisations); an
error means the engine would refuse the object too — read it before changing
anything.

## 9. Keep it current

- After upgrading the tool, **re-import each tree from its vault** before the
  next deploy: a tree written by an older version may lack a kind of object the
  new version models, and a diff would then propose deleting it. The deployer
  refuses to empty a kind, but a fresh import is the right fix.
- `deploy-log/<env>.jsonl` and `cases/` are part of the tree's history; commit
  them with it. `deploy-snapshots/` is the rollback material for the last
  deploys; keep it out of git and out of chat.
- Never paste a password, a bind DN's secret or a snapshot into a
  conversation; the tool prints names, never values.
- Prefer the Keychain or a password-manager command over literals in the two
  credential files, and keep both files at mode 600.

Using the built tool: [getting-started.md](getting-started.md).
