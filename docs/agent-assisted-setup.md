# Agent-assisted setup

You already know Identity Manager. JDK 21, Maven, the policy simulator, ten
proprietary jars, an environments file, Keychain items, and TLS are the same
work as [install.md](install.md). This page hands that work to a coding agent
— Claude Code, Cursor, Codex, or any agent that can read files and run a
shell. You paste a prompt. The agent does the workstation steps and stops
after each phase so you can see it worked.

The agent runs the same `bin/idm` you would. After setup, day-to-day use is
[getting-started.md](getting-started.md) and [agents.md](agents.md).

## Which prompts do I need?

Paste the [kickoff prompt](#all-in-one-kickoff) once. That is the whole
setup. The agent works through every phase and stops at each checkpoint so
you can look. You say continue, and you leave the other prompts unused.

| You want to | What to paste |
|---|---|
| The whole install, in one conversation | The kickoff prompt. It is enough on its own. |
| One phase at a time, a resume after a break or a failure partway through, or one phase again later (a second environment, MCP after the CLI already works) | That phase's prompt, by itself |
| A step that failed | The matching troubleshooting prompt |

The phase prompts are other ways in. They are not further steps after the kickoff.

## What you do

These stay with you. The rest of this page is the agent's job.

### Where the engine jars are

The policy compilers and the LDAP client are proprietary NetIQ / OpenText
jars. They are not in this git repository and not on Maven Central. You
already have them in one of these places:

- The engine server, in `/opt/novell/eDirectory/lib/dirxml/classes/`
- A Remote Loader install (the same jars, on that host's engine classpath)
- A Designer install on the workstation

DirXMLDev needs these ten files, with these names (`XDS.jar` is capitalized):

`dirxml.jar`, `dirxml_misc.jar`, `nxsl.jar`, `xp.jar`, `js.jar`,
`jclient.jar`, `ldap.jar`, `XDS.jar`, `dhutil.jar`, `CommonDriverShim.jar`

The [DirXML Policy Simulator](https://github.com/PointBlueTechnology/DirXMLSimulator)
describes `ldap.jar` as optional (only its cache-reading command needs it).
DirXMLDev needs it for every vault command. Copy all ten. They are the
4.10.1 engine set this project expects.

Tell the agent the directory that already contains them
(`<path-to-idm-jars>`). That directory has to be readable on the
workstation. When the jars are only on the engine or the Remote Loader,
copy those ten files down first; the agent can `scp` them once you give it
a host and say it may. The agent then copies them as ordinary files into
each project's `lib/`. You keep the license you already have.

### The vault, in names

The agent drafts `environments.properties` from facts that are not secrets:

- The LDAPS URL, usually `ldaps://<vault-host>:636`
- The driver-set DN
- An environment name (`<env-name>`, for example `stg`) and a tier: `dev`,
  `stg`, or `prd`
- The bind DN of the deploy identity

### A deploy identity

Each environment gets its own eDirectory user with rights on that driver set,
and on the User Application driver's `AppConfig` when you later deploy forms
or PRDs. Creating that user is ordinary eDirectory administration. `admin` is
fine on a lab. Use a dedicated account for production.

### Passwords, typed by you

The password goes into the macOS Keychain, into your password manager, or
into an environment variable you set in your own terminal. The properties
file stores a pointer: `passwordKeychain=`, `passwordCommand=`, or
`passwordEnv=`. A literal `password=` belongs on a throwaway lab only.

The agent may print the Keychain command. You run it. `-w` with nothing
after it prompts, so the password stays out of shell history:

```bash
security add-generic-password -s <env-name> -a '<bind-dn>' -w
```

Linux and Windows have no Keychain built in. Use your password manager's
CLI (`op read`, `pass show`, `secret-tool lookup`, …) as `passwordCommand=`,
or `passwordEnv=`.

Put the jar path, the host, the DNs, and the environment name in the prompt.
Keep the password out of the chat, out of the prompt, and out of any file
the agent writes.

### Saying yes to a vault write

Setup ends on read-only checks. A command that would change the vault
refuses to run unless `IDM_AGENT_ALLOW_WRITE=1` is set or the command
includes `--confirm <env-name>` for that same environment. The gated
commands are `vault.deploy` with `--yes` or `--step`, `vault.rollback --yes`,
`vault.import-clone --yes`, and the operate commands that change a driver
(`driver.start`, `stop`, `restart`, `migrate`, `resync`, `submit`,
`driver.cache clear`, `driver.secrets set` or `remove`, `driver.trace set`
or `reset`). `--dry-run` and read-only commands are not writes. The check
happens before any secret is resolved and before LDAP opens.

Leave the write flag unset during setup. When you want a write, say so after
you have read `vault.diff` and `vault.deploy --dry-run`. On a `prd` tier,
`--confirm` plus the environment name is also the production gate.
`--confirm prd` satisfies both.

## What the agent does

It asks before installing software. Then it can:

1. Check for JDK 21, Maven 3.9 or newer, and git, and install what is missing.
2. Clone the simulator, copy the ten jars into its `lib/` as regular files,
   and run `mvn install` so `dirxml-simulator` **1.5.2** (the version this
   repo pins) is in the local Maven repository. `IDM_SIM_VERSION` selects
   another installed build only when you mean it to. A release zip of the
   simulator runs `bin/sim` on its own; `bin/idm doctor` looks for the jar
   `mvn install` writes under `~/.m2`.
3. Clone this repository
   (`https://github.com/PointBlueTechnology/DirXMLDev`), copy the same jars
   into a real `lib/` (regular files again), and build. `lib/*.jar` is
   gitignored.
4. Run `bin/idm doctor` and fix what that report names.
5. Create a client directory — its own git repo, outside this checkout —
   with a `.gitignore` for `environments.properties`, `secrets*.properties`,
   `deploy-snapshots/`, and `*.ldif`.
6. Draft `environments.properties` with an indirect password, and on macOS
   or Linux set the file to mode 600.
7. Sort out TLS, or an SSH tunnel when the workstation cannot open port 636.
8. Run the read-only checks: `engine.version`, `driverset.status`,
   `import-live`, `validate`, `vault.diff`.
9. Optionally wire the [MCP server](mcp.md) and the Cursor / VS Code
   [fishbone extension](../extensions/dirxmldev-visual/README.md).

Maven may print that the installed simulator POM is invalid because
`systemPath` is still the literal `${project.basedir}/lib/...`. That text
lives in the simulator artifact under `~/.m2`. It is not this repository's
POM, and copying jars into DirXMLDev's `lib/` does not rewrite it. The build
continues.

Python 3.8 or newer is only for `bin/apps`, later. Node is only for the
optional MCP server (Node 20 or newer) and the fishbone (Node 18 or newer).

## Checkpoints

Stop after each phase. The next one starts when you say it looks right.

| Phase | Done when |
|---|---|
| 1. Prerequisites | `java -version` is 21, `mvn -v` is 3.9 or newer, `git --version` works |
| 2. Build | `bin/idm doctor` prints `DOCTOR: OK` |
| 3. Client directory | `bin/idm doctor --env <env-name>` reports `ldaps: OK` |
| 4. First import | `validate` reports 0 errors, and `vault.diff` reports no differences |
| 5. Optional extras | An MCP read works, or the fishbone opens. Skip this if you only want the CLI |

`doctor` with no `--env` does not open LDAP. A missing environments file is
expected at phase 2 (`environments: OK  none configured`). `--env` is the
connection test. A refused handshake or a bad password fails that check. The
report leaves out the password, the bind DN, and the URL. It prints host and
port.

`import-live` writes the local `tree/` only. It does not change the vault.

## Prompts

Replace the angle-bracket placeholders, then paste. Leave every password out.
One prompt is the whole job: the kickoff, below, or a single phase prompt
when you are doing just that phase.

These rules are already written into the kickoff prompt and into each phase
prompt:

- Vault access stays read-only until you say otherwise.
- Secrets stay out of the chat, the shell history, command output, and git.
- The proprietary jars stay out of git.
- The agent stops and asks before any vault write.

### All-in-one kickoff

This is the prompt to paste. It is enough on its own: the agent works
through every phase and stops at each checkpoint. Skip the phase prompts
below; they are alternatives, not the next thing to paste.

```
You are setting up DirXMLDev (https://github.com/PointBlueTechnology/DirXMLDev)
and the DirXML Policy Simulator (https://github.com/PointBlueTechnology/DirXMLSimulator)
on this machine. Once the DirXMLDev checkout exists, follow
docs/agent-assisted-setup.md. If that page and this prompt disagree, follow
the page and `bin/idm` (run it with no arguments for the command list).

Facts I am providing. None of these are secrets:
- Directory that already contains the ten IDM engine jars: <path-to-idm-jars>
  If that path is on the engine or a Remote Loader, ask me before scp, and
  copy only those ten filenames.
- Environment name: <env-name>
- Tier (dev, stg, or prd): <tier>
- LDAPS URL: ldaps://<vault-host>:636
- Bind DN of the deploy identity: <bind-dn>
- Driver-set DN: <driver-set-dn>
- Client working directory to create (its own git repo, outside both checkouts): <client-dir>
- Clone locations, unless I name others: ~/IdeaProjects/DirXMLSimulator and ~/IdeaProjects/DirXMLDev

Rules for the whole job:
- Ask me before you install JDK, Maven, git, Node, or anything else.
- Stay read-only toward the Identity Vault until I say otherwise. import-live
  may write the local tree/ only.
- Keep passwords, snapshots, and secrets-file values out of the chat, the
  shell history, command output, and git. I will enter passwords myself into
  the macOS Keychain or my password manager. Ask me to run a command; do not
  ask me to paste a secret.
- Copy jars as regular files. Do not commit them. Do not symlink lib/ or the
  individual jars.
- Do not set IDM_AGENT_ALLOW_WRITE. Do not run vault.deploy, vault.rollback,
  vault.import-clone, or driver start, stop, restart, migrate, resync,
  submit, cache clear, secrets set/remove, or trace set/reset. Stop and ask
  before any vault write.
- Stop after each phase, show the checkpoint, and wait for me to say continue.

Phase 1 — prerequisites. JDK 21, Maven 3.9 or newer, and git. Run Maven and
the builds with JDK 21 (on macOS, JAVA_HOME from /usr/libexec/java_home -v 21;
otherwise IDM_JAVA_HOME or JAVA_HOME). Checkpoint: java -version shows 21,
mvn -v shows 3.9 or newer, git --version works.

Phase 2 — build. Clone the simulator. Copy these ten jars from
<path-to-idm-jars> into its lib/ as regular files: dirxml.jar,
dirxml_misc.jar, nxsl.jar, xp.jar, js.jar, jclient.jar, ldap.jar, XDS.jar,
dhutil.jar, CommonDriverShim.jar. Run mvn install there with JDK 21 so
dirxml-simulator 1.5.2 lands in ~/.m2 (on Windows, %USERPROFILE%\.m2).
DirXMLDev pins 1.5.2; set IDM_SIM_VERSION only if I asked for another build.
A simulator release zip does not satisfy doctor. Clone DirXMLDev, copy the
same ten jars as regular files into its lib/, and run mvn test with JDK 21.
A warning that the simulator POM's systemPath is invalid is expected;
continue. Run bin/idm doctor. Checkpoint: it prints DOCTOR: OK.

Phase 3 — client directory. Create <client-dir>, git init, and a .gitignore
containing environments.properties, secrets*.properties, deploy-snapshots/,
and *.ldif. Draft environments.properties with url, bindDn, driverSet, tier,
and secrets=secrets-<env-name>.properties. Point the password at an indirect
form: on macOS, passwordKeychain=<env-name>/<bind-dn>; on Linux or Windows,
passwordCommand= or passwordEnv= for the manager I use. Leave trustAll unset
(the default accepts the server certificate). Create an empty
secrets-<env-name>.properties. On macOS and Linux, chmod 600 both files.
Put no password in either file. Print the exact command I should run to
store the bind password — on macOS, security add-generic-password -s
<env-name> -a '<bind-dn>' -w with nothing after -w — and wait until I
confirm I have done it. From <client-dir>, run
<path-to-DirXMLDev>/bin/idm doctor --env <env-name>. Copy
docs/examples/client-AGENTS.md to <client-dir>/AGENTS.md and fill the two
paths. Checkpoint: doctor reports ldaps: OK and does not print the password
or the bind DN.

Phase 4 — read-only checks. From <client-dir>, using that same bin/idm:
engine.version --env <env-name>, driverset.status --env <env-name>,
import-live tree/ --env <env-name>, validate tree/, vault.diff tree/ --env
<env-name>. Checkpoint: validate reports 0 errors, and vault.diff reports
no differences. If validate reports errors on this imported tree, show them
and stop; do not edit the tree to silence them. Commit tree/, .gitignore,
.gitattributes if import wrote one, and AGENTS.md. Leave environments,
secrets, deploy-snapshots, LDIFs, and jars uncommitted.

Phase 5 — only if I ask. MCP server per docs/mcp.md, with
IDM_AGENT_ALLOW_WRITE=0, and the fishbone extension under
extensions/dirxmldev-visual. Both are optional.

Start at phase 1.
```

### Phase prompts

Skip this section if you used the kickoff prompt. Each prompt below is an
alternative, for when you want to go one phase at a time, resume in a new
session or after a break or a failure partway through, or redo a single
phase later.

### Phase 1 — prerequisites

```
Check this machine for JDK 21, Maven 3.9 or newer, and git. DirXMLDev and
the DirXML Policy Simulator need JDK 21 because the Identity Manager 4.10.1
engine jars are Java 21 bytecode. Ask me before you install anything.

On macOS the launcher finds JDK 21 with /usr/libexec/java_home -v 21. On
Linux it also looks in /usr/lib/jvm/*21* and on PATH. The bin/idm launcher
accepts IDM_JAVA_HOME, then SIM_JAVA_HOME, then those searches. On Windows,
bin\idm.cmd uses IDM_JAVA_HOME or JAVA_HOME only; Git Bash or WSL can run
bin/idm, which has the fuller search.

Use JDK 21 for Maven too (set JAVA_HOME, or on macOS
JAVA_HOME=$(/usr/libexec/java_home -v 21)).

Stop when java -version shows 21, mvn -v shows 3.9 or newer, and
git --version works. Show me those three lines. Do not clone repositories
yet.
```

### Phase 2 — simulator, jars, and DirXMLDev

```
JDK 21, Maven, and git are in place. Install DirXMLDev and the DirXML
Policy Simulator. Follow docs/install.md section 2 and
docs/agent-assisted-setup.md in the DirXMLDev repo once it is cloned.

Jars are already on disk at <path-to-idm-jars>. Copy these ten as regular
files (cp, not ln -s), including ldap.jar, and keep the name XDS.jar:
dirxml.jar, dirxml_misc.jar, nxsl.jar, xp.jar, js.jar, jclient.jar,
ldap.jar, XDS.jar, dhutil.jar, CommonDriverShim.jar.

Clone https://github.com/PointBlueTechnology/DirXMLSimulator into
~/IdeaProjects/DirXMLSimulator (or another directory I name). Copy the ten
jars into that repo's lib/. With JAVA_HOME pointed at JDK 21, run
mvn install. That must produce
~/.m2/repository/com/pointblue/dirxml/dirxml-simulator/1.5.2/dirxml-simulator-1.5.2.jar
(on Windows, under %USERPROFILE%\.m2). DirXMLDev pins 1.5.2. A release zip
of the simulator is not a substitute. Set IDM_SIM_VERSION only if I asked.

Clone https://github.com/PointBlueTechnology/DirXMLDev into
~/IdeaProjects/DirXMLDev. mkdir -p lib and copy the same ten jars in as
regular files. lib/ must be a real directory. Run mvn test with JDK 21.
If Maven says the simulator POM is invalid because systemPath is still
${project.basedir}/lib/..., continue: that message is in the installed
simulator artifact, not in DirXMLDev's POM. If mvn test fails for some
other reason, show me the failure; still run doctor afterward.

Run bin/idm doctor (Windows: bin\idm.cmd doctor). Show me the report.
Stop when it prints DOCTOR: OK. Do not commit the jars. Do not start a
client directory yet. Do not contact a vault.
```

### Phase 3 — client directory and credentials

```
bin/idm doctor already prints DOCTOR: OK. Create the client working
directory and a vault target. Stay read-only. Do not print or embed a
password.

Create <client-dir> as its own git repository, outside the DirXMLDev
checkout. Add a .gitignore with these lines:
environments.properties
secrets*.properties
deploy-snapshots/
*.ldif

Draft <client-dir>/environments.properties for environment <env-name>:
- <env-name>.url=ldaps://<vault-host>:636
- <env-name>.bindDn=<bind-dn>
- <env-name>.driverSet=<driver-set-dn>
- <env-name>.tier=<tier>
- <env-name>.secrets=secrets-<env-name>.properties
- Password as an indirect form. On macOS:
  <env-name>.passwordKeychain=<env-name>/<bind-dn>
  On Linux or Windows, <env-name>.passwordCommand= or passwordEnv= for the
  password manager I name. Do not write <env-name>.password= with a value.
- Leave trustAll unset so the first connection accepts the server certificate.

Create an empty secrets-<env-name>.properties. On macOS and Linux, chmod 600
both files. bin/idm looks for environments.properties in $IDM_ENVIRONMENTS, then
the working directory, then ~/.idm/environments.properties.

Print the command I should run to store the bind password, and wait.
On macOS that command is:
security add-generic-password -s <env-name> -a '<bind-dn>' -w
with nothing after -w, so the password is prompted and stays out of shell
history. On Linux or Windows, tell me the passwordCommand or the environment
variable name, and stop until I confirm the secret is stored.

Then, from <client-dir>, run <path-to-DirXMLDev>/bin/idm doctor --env <env-name>.
The report must show url, bind, password, and driverSet configured, and
ldaps: OK. Host and port on that ldaps line are expected. It must not print
the password or the bind DN.

Copy DirXMLDev's docs/examples/client-AGENTS.md to <client-dir>/AGENTS.md
and set the bin/idm path and the path to docs/agent-guide.md.

Stop and show me the doctor --env summary (names and yes/no only). Do not
import the driver set until I say so.
```

### Phase 4 — read-only checks and the first import

```
The client directory <client-dir> is ready and
bin/idm doctor --env <env-name> reports ldaps: OK. From <client-dir>, using
<path-to-DirXMLDev>/bin/idm, run these and show me each result:

bin/idm engine.version --env <env-name>
bin/idm driverset.status --env <env-name>
bin/idm import-live tree/ --env <env-name>
bin/idm validate tree/
bin/idm vault.diff tree/ --env <env-name>

import-live writes the local tree only. Do not deploy, do not start or stop
a driver, and do not set IDM_AGENT_ALLOW_WRITE.

Done when validate reports 0 errors and vault.diff reports no differences.
Warnings and infos (template placeholders, missing localisations) are
normal; paste those counts. If validate reports errors on this tree, show
the findings and stop. Do not edit the tree to silence them.

Commit tree/ (including tree/.gitattributes if import wrote it), .gitignore,
and AGENTS.md. The commit message can be "Import <env-name> driver set".
Leave environments.properties, secrets files, deploy-snapshots/, *.ldif, and
every engine jar uncommitted. Do not print file contents that hold a secret.
```

### Phase 5 — MCP and the fishbone (optional)

```
The CLI already works against <env-name>. Wire the optional extras only.
Reads and dry-runs are allowed. Leave IDM_AGENT_ALLOW_WRITE unset or set it
to 0. Do not turn on mutators.

MCP server, from the DirXMLDev checkout (Node 20 or newer):
cd mcp/dirxmldev-mcp && npm install
Write the Cursor (or other client) mcp config the way docs/mcp.md shows,
with the absolute path to mcp/dirxmldev-mcp/src/index.js. In env, set
IDM_AGENT_ALLOW_WRITE to 0 and IDM_ENVIRONMENTS to the absolute path of
<client-dir>/environments.properties. Ask me to reload the MCP client.
Confirm with a read-only tool (idm.context or idm.engine.version). Tool
output must not contain secret values.

Fishbone extension (Node 18 or newer; Node 20 covers both):
cd extensions/dirxmldev-visual && npm install && npm run compile
Install that folder as a local extension. The directory name is
pointblue.dirxmldev-visual-<version> using the version field in package.json
(0.1.2 today): ~/.cursor/extensions/ on Cursor, ~/.vscode/extensions/ on
VS Code. On Windows, %USERPROFILE%\.cursor\extensions\ and the VS Code
equivalent. Restart the editor. When the open folder is <client-dir>, set
dirxmldev.idmPath to the DirXMLDev bin/idm (or IDM_HOME to the checkout) so
the view can run query fishbone. The extension is read-only: it does not
deploy and it does not talk to the vault.

Stop when a read-only MCP call works, or the fishbone opens, and tell me
which one you verified. Skip either piece if I say so.
```

### Troubleshooting

Use these only when something goes wrong. A setup that is moving through
the checkpoints does not need them. Paste one of these when a phase stops.
Fill the same placeholders.

#### `doctor` is not OK

```
bin/idm doctor is failing in the DirXMLDev checkout. Read its full output.
Use bin/idm doctor --json if the text is ambiguous. Fix only what the
report names, then run doctor again and show me the new report.

JDK: DirXMLDev needs JDK 21. Set IDM_JAVA_HOME to a JDK 21 home. The shell
launcher also checks SIM_JAVA_HOME, /usr/libexec/java_home -v 21, JAVA_HOME
when that Java is 21, /usr/lib/jvm/*21*, and java on PATH when that java is
21. On Windows, bin\idm.cmd uses only IDM_JAVA_HOME or JAVA_HOME.

Simulator: this repo pins dirxml-simulator 1.5.2. doctor looks for
~/.m2/repository/com/pointblue/dirxml/dirxml-simulator/1.5.2/dirxml-simulator-1.5.2.jar
(bin\idm.cmd uses %USERPROFILE%\.m2\...). Produce it with mvn install in the
DirXMLSimulator repo, run with JDK 21. If mvn install succeeded and doctor
still says the jar is missing, Maven and the launcher are looking at
different home directories; point them at the same .m2. Set IDM_SIM_VERSION
only when I asked for a different installed build. A release zip of the
simulator does not put this jar in .m2.

lib/: a real directory containing regular files dirxml.jar,
dirxml_misc.jar, nxsl.jar, xp.jar, js.jar, jclient.jar, ldap.jar, XDS.jar,
dhutil.jar, and CommonDriverShim.jar. ldap.jar is required here. Copy them
from <path-to-idm-jars>. If the report says classes did not load, the jars
are present but are not the 4.10.1 engine set.

A missing environments file is fine when --env was not passed. With --env,
a failed LDAPS line is a connection problem: use the TLS / route prompt
below. Do not print secrets. Do not write to the vault.
```

#### TLS, or "No route to host"

```
engine.version or bin/idm doctor --env <env-name> cannot reach the vault.
Diagnose it. Keep every password, bind DN, and URL value out of the chat
and out of git. You may say the host and port.

If the error is "No route to host" or a timeout, including the case where
ldapsearch or curl reaches the vault and Java does not, the workstation has
no route to port 636. Ask me for a jump host and an SSH user, then forward
the port and add a second environment that matches <env-name> except for
the URL:

ssh -f -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L 6636:<vault-host>:636 <ssh-user>@<jump-host>

<env-name>tun.url=ldaps://127.0.0.1:6636
(same bindDn, the same indirect password, the same driverSet and tier)

Leave trustAll unset on that block. The default accepts the certificate and
turns off the hostname check, which a tunnel to 127.0.0.1 needs. Re-run
doctor --env <env-name>tun and engine.version --env <env-name>tun.

If the handshake fails and <env-name>.trustAll=false, the JDK does not trust
the vault CA yet. Ask me for the CA file, then either import it
(keytool -importcert -cacerts -alias idm-ca -file ca.pem) or point
IDM_JAVA_OPTS at a truststore
(-Djavax.net.ssl.trustStore=...). For this first connection you may remove
trustAll=false so the default applies, and set trustAll=false again after
the CA is in the truststore. With trustAll=false, a lab tunnel whose
certificate name is not 127.0.0.1 also needs
IDM_JAVA_OPTS=-Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true.
Keep that flag off production.

If the failure is a bad password or a missing Keychain item, the error
names the fix. Print the security add-generic-password command with nothing
after -w, or the passwordCommand / passwordEnv name, and stop. If doctor
redacted the account, use the bind DN I already gave you. I will store the
secret. Do not ask me to paste it.

Stay read-only. When doctor --env reports ldaps: OK, stop and show me that
line.
```

#### Symlink / `requireFilesExist`

```
mvn test, bin/require-engine.sh, or bin/idm doctor says a jar is missing,
or Maven stopped in requireFilesExist, but ls shows the files under lib/.

Check two things: whether lib itself is a directory symlink, and whether
any of the ten jars inside lib/ is a symlink. Maven's requireFilesExist
check compares a path with its canonical path and reports both kinds of
symlink as missing. doctor reports the same case as
"symlink is not a Maven file".

Fix it by copying regular files. In the DirXMLDev checkout:

mkdir -p lib
cp <path-to-idm-jars>/dirxml.jar lib/ && cp the other nine the same way:
dirxml_misc.jar, nxsl.jar, xp.jar, js.jar, jclient.jar, ldap.jar, XDS.jar,
dhutil.jar, CommonDriverShim.jar.

Do the same in the DirXMLSimulator checkout if that build failed this way.
Confirm with `find lib -type l` printing nothing and `find lib -type f`
listing the ten jars. Do not commit the jars. Re-run bin/idm doctor and
show me DOCTOR: OK, or the next failure that is not a symlink.
```

## Platform notes

### macOS

The launcher finds JDK 21 with `/usr/libexec/java_home -v 21`. Homebrew (or
a Temurin JDK 21 you already have) is a normal way to install it; the agent
asks first. Run Maven with that same `JAVA_HOME`.

The Keychain is the password store to use. An item added with `security` is
readable by `security` without a prompt. An item created in Keychain Access
asks once; choose Always Allow. When the item is missing, the error names
the `add-generic-password` command to run. `doctor` still leaves the bind DN
out of its report, so the prompts have the agent print that command from the
DN you already provided.

`chmod 600 environments.properties secrets-*.properties` after they exist.
The tool warns once per run when either file is readable by other users.

### Windows

`bin\idm.cmd` is the launcher. `IDM_JAVA_HOME` or `JAVA_HOME` must be a JDK
21; this launcher does not search `java_home` or `/usr/lib/jvm`. The
simulator jar is
`%USERPROFILE%\.m2\repository\com\pointblue\dirxml\dirxml-simulator\1.5.2\dirxml-simulator-1.5.2.jar`.

Git Bash or WSL can run `bin/idm`, which has the fuller JDK search and reads
`$HOME/.m2`. Use one home directory for Maven and the launcher so both see
the installed simulator jar.

There is no Keychain. Use `passwordCommand=` (your password manager's CLI)
or `passwordEnv=`. The mode-600 warning is a POSIX check; on Windows keep
the two properties files out of git and out of a shared folder.

A fresh Designer project from a vault, without Designer connecting, is a
later task: [howto-fresh-designer-project.md](howto-fresh-designer-project.md).

### Linux

The launcher accepts `IDM_JAVA_HOME`, then a `JAVA_HOME` that is JDK 21,
then `/usr/lib/jvm/*21*`, then `java` on `PATH` when that binary is 21.
OpenJDK 21 from the distro (`openjdk-21-jdk` on Debian and Ubuntu,
`java-21-openjdk-devel` on Fedora and RHEL) and Maven 3.9 or newer are
enough; the agent asks before it installs packages.

Passwords use `passwordCommand=` (`op read`, `pass show`,
`secret-tool lookup`, …) or `passwordEnv=`. `chmod 600` the properties
files. `XDS.jar` must keep that capitalization on a case-sensitive
filesystem.

## When this is done

The client directory is the place you work. Call `bin/idm` by its full path,
or put it on `PATH`. The next pages:

- [getting-started.md](getting-started.md) — the same first import, and how
  to look around the tree.
- [agents.md](agents.md) — how any agent runs the loop after setup.
- [agent-guide.md](agent-guide.md) — the rules that agent follows.
- [day-to-day.md](day-to-day.md) — a policy change through deploy.
- [install.md](install.md) — the same setup, typed by hand, plus CI.
