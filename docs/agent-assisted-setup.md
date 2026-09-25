# Agent-assisted setup

The same install as [install.md](install.md) — JDK 21, Maven, the policy
simulator, ten proprietary jars, an environments file, a stored password, TLS
— done by a coding agent (Claude Code, Cursor, Codex, anything that reads
files and runs a shell) while you watch. You paste one prompt; the agent
works phase by phase and stops at each checkpoint for you to look. Setup
ends on read-only checks against the vault, and takes under an hour on a
machine that has the jars.

## Before you paste

Have these eight values ready. None of them is a secret. The password is
never in the prompt; you store it yourself in phase 3 (see
[What stays with you](#what-stays-with-you)).

| Placeholder | What it is | Example |
|---|---|---|
| `<path-to-idm-jars>` | A directory that already holds the ten engine jars, readable on this workstation | `~/Downloads/idm-4.10.1-jars` |
| `<env-name>` | The environment's name, used after `--env` and as the Keychain item | `stg` |
| `<tier>` | `dev`, `stg` or `prd`; decides the deploy gate | `stg` |
| `<vault-host>` | The eDirectory server that holds the driver set, LDAPS on 636 | `idm-stg.example.com` |
| `<bind-dn>` | The deploy identity's DN | `cn=idm-deploy,ou=sa,o=system` |
| `<driver-set-dn>` | The driver set this environment means | `cn=driverset1,o=system` |
| `<client-dir>` | The working directory to create: its own git repository, outside both checkouts | `~/idm-work/acme` |
| `<path-to-DirXMLDev>` | Where DirXMLDev is cloned; the kickoff defaults it to `~/IdeaProjects/DirXMLDev` | `~/IdeaProjects/DirXMLDev` |

**The ten jars.** The policy compilers and the LDAP client are proprietary
NetIQ / OpenText jars, not in git and not on Maven Central. You already have
them on the engine server (`/opt/novell/eDirectory/lib/dirxml/classes/`), on
a Remote Loader host, or in a Designer install. DirXMLDev needs all ten, with
these names (`XDS.jar` keeps its capitals): `dirxml.jar`, `dirxml_misc.jar`,
`nxsl.jar`, `xp.jar`, `js.jar`, `jclient.jar`, `ldap.jar`, `XDS.jar`,
`dhutil.jar`, `CommonDriverShim.jar`. Engines from 4.10.2 on ship `xp.jar`
as `xp-1.0.0.jar`; copy it as `xp.jar`. The simulator calls `ldap.jar`
optional; here every vault command needs it. If they are only on a server,
the agent can `scp` them once you name the host and say it may. They are
copied as regular files into each project's `lib/` and never committed or
symlinked. You keep the license you already have.

## The kickoff prompt

Replace the placeholders and paste. This one prompt is the whole setup; the
[phase prompts](#alternatives-to-the-kickoff) further down are ways to resume
or redo a single phase, not the next thing to paste.

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
dhutil.jar, CommonDriverShim.jar (a 4.10.2 engine names xp.jar
xp-1.0.0.jar; copy it as xp.jar). Run mvn install there with JDK 21 so
dirxml-simulator 1.6.1 lands in ~/.m2 (on Windows, %USERPROFILE%\.m2).
DirXMLDev pins 1.6.1; set IDM_SIM_VERSION only if I asked for another build.
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

## Checkpoints

The agent stops after each phase. The next one starts when you say it looks
right.

| Phase | Done when |
|---|---|
| 1. Prerequisites | `java -version` is 21, `mvn -v` is 3.9 or newer, `git --version` works |
| 2. Build | `bin/idm doctor` prints `DOCTOR: OK` |
| 3. Client directory | `bin/idm doctor --env <env-name>` reports `ldaps: OK` |
| 4. First import | `validate` reports 0 errors, and `vault.diff` reports no differences |
| 5. Optional extras | An MCP read works, or the fishbone opens. Skip this if you only want the CLI |

Expect these along the way:

- `doctor` without `--env` does not open LDAP, and at phase 2 a missing
  environments file is expected (`environments: OK  none configured`). With
  `--env` it is the connection test: a refused handshake or a bad password
  fails it. The report prints host and port and leaves out the URL, the bind
  DN and the password.
- Maven may say the installed simulator POM is invalid because `systemPath`
  is still `${project.basedir}/lib/...`. That text lives in the simulator
  artifact under `~/.m2`, not in this repository's POM; the build continues.
- `import-live` writes the local `tree/` only. It never changes the vault.
- Python 3.8 or newer is only for `bin/apps`, later. Node is only for the
  optional MCP server (20 or newer) and the fishbone (18 or newer).

## What stays with you

The agent asks before installing software and does the workstation work. Four
things are yours.

**A deploy identity.** Each environment gets its own eDirectory user with
rights on that driver set, and on the User Application driver's `AppConfig`
when you later deploy forms or PRDs. Creating it is ordinary eDirectory
administration. `admin` is fine on a lab; production gets a dedicated account.

**The password, typed by you.** It goes into the macOS Keychain, your
password manager, or an environment variable you set in your own terminal.
The environments file stores a pointer (`passwordKeychain=`,
`passwordCommand=` or `passwordEnv=`); a literal `password=` belongs on a
throwaway lab only. The agent prints the Keychain command and you run it.
`-w` with nothing after it prompts, so the password stays out of shell
history:

```bash
security add-generic-password -s <env-name> -a '<bind-dn>' -w
```

Linux and Windows have no Keychain: use your password manager's CLI
(`op read`, `pass show`, `secret-tool lookup`, …) as `passwordCommand=`, or
`passwordEnv=`. Keep the password out of the chat, the prompt, and any file
the agent writes.

**Saying yes to a vault write.** Setup ends on read-only checks. Any command
that would change the vault or a driver refuses to run unless
`IDM_AGENT_ALLOW_WRITE=1` is set or the command carries `--confirm <env-name>`;
`--dry-run` and reads are never writes, and the check runs before any secret
is resolved. The gated commands are listed in [install.md](install.md) under
"Agent write gate". Leave the variable unset during setup. When you want a
write, say so after you have read `vault.diff` and `vault.deploy --dry-run`;
on a `prd` tier `--confirm prd` is the production gate as well.

**The facts in the table above.** The agent drafts `environments.properties`
from them; nothing in it is a secret.

## Alternatives to the kickoff

Skip this section if you used the kickoff. Each prompt below does one phase
on its own: for going one phase at a time, resuming in a new session after a
break or a failure, or redoing a phase later (a second environment, MCP after
the CLI already works). The same rules are written into every one of them:
vault access stays read-only until you say otherwise, secrets stay out of the
chat, the shell history, command output and git, the jars stay out of git,
and the agent stops before any vault write.

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
ldap.jar, XDS.jar, dhutil.jar, CommonDriverShim.jar. A 4.10.2 engine names
xp.jar xp-1.0.0.jar: copy it as xp.jar.

Clone https://github.com/PointBlueTechnology/DirXMLSimulator into
~/IdeaProjects/DirXMLSimulator (or another directory I name). Copy the ten
jars into that repo's lib/. With JAVA_HOME pointed at JDK 21, run
mvn install. That must produce
~/.m2/repository/com/pointblue/dirxml/dirxml-simulator/1.6.1/dirxml-simulator-1.6.1.jar
(on Windows, under %USERPROFILE%\.m2). DirXMLDev pins 1.6.1. A release zip
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
pointblue.dirxmldev-visual-<version>, using the version field in package.json:
~/.cursor/extensions/ on Cursor, ~/.vscode/extensions/ on
VS Code. On Windows, %USERPROFILE%\.cursor\extensions\ and the VS Code
equivalent. Restart the editor. When the open folder is <client-dir>, set
dirxmldev.idmPath to the DirXMLDev bin/idm (or IDM_HOME to the checkout) so
the view can run query fishbone. The extension is read-only: it does not
deploy and it does not talk to the vault.

Stop when a read-only MCP call works, or the fishbone opens, and tell me
which one you verified. Skip either piece if I say so.
```

## Troubleshooting

Paste one of these when a phase stops, with the same placeholders filled in.
A setup that is moving through the checkpoints does not need them.

### `doctor` is not OK

```
bin/idm doctor is failing in the DirXMLDev checkout. Read its full output.
Use bin/idm doctor --json if the text is ambiguous. Fix only what the
report names, then run doctor again and show me the new report.

JDK: DirXMLDev needs JDK 21. Set IDM_JAVA_HOME to a JDK 21 home. The shell
launcher also checks SIM_JAVA_HOME, /usr/libexec/java_home -v 21, JAVA_HOME
when that Java is 21, /usr/lib/jvm/*21*, and java on PATH when that java is
21. On Windows, bin\idm.cmd uses only IDM_JAVA_HOME or JAVA_HOME.

Simulator: this repo pins dirxml-simulator 1.6.1. doctor looks for
~/.m2/repository/com/pointblue/dirxml/dirxml-simulator/1.6.1/dirxml-simulator-1.6.1.jar
(bin\idm.cmd uses %USERPROFILE%\.m2\...). Produce it with mvn install in the
DirXMLSimulator repo, run with JDK 21. If mvn install succeeded and doctor
still says the jar is missing, Maven and the launcher are looking at
different home directories; point them at the same .m2. Set IDM_SIM_VERSION
only when I asked for a different installed build. A release zip of the
simulator does not put this jar in .m2.

lib/: a real directory containing regular files dirxml.jar,
dirxml_misc.jar, nxsl.jar, xp.jar, js.jar, jclient.jar, ldap.jar, XDS.jar,
dhutil.jar, and CommonDriverShim.jar. ldap.jar is required here. Copy them
from <path-to-idm-jars>; if that directory has xp-1.0.0.jar instead of
xp.jar, copy it as xp.jar. If the report says classes did not load, the jars
are present but are not the 4.10.1 engine set.

A missing environments file is fine when --env was not passed. With --env,
a failed LDAPS line is a connection problem: use the TLS / route prompt
below. Do not print secrets. Do not write to the vault.
```

### TLS, or "No route to host"

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

### Symlink / `requireFilesExist`

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

| | JDK 21 | Launcher | Password store | Files |
|---|---|---|---|---|
| **macOS** | `/usr/libexec/java_home -v 21` finds it; Homebrew or a Temurin JDK installs it (the agent asks first). Run Maven with the same `JAVA_HOME` | `bin/idm` | The Keychain. An item added with `security` is read without a prompt; one made in Keychain Access asks once (choose Always Allow). A missing item's error names the command to run | `chmod 600` both properties files; the tool warns once per run when either is readable by others |
| **Linux** | `IDM_JAVA_HOME`, else a `JAVA_HOME` that is 21, else `/usr/lib/jvm/*21*`, else `java` on `PATH` when it is 21. The distro's OpenJDK 21 (`openjdk-21-jdk` on Debian and Ubuntu, `java-21-openjdk-devel` on Fedora and RHEL) and Maven 3.9 or newer are enough | `bin/idm` | `passwordCommand=` or `passwordEnv=` | `chmod 600` as on macOS; `XDS.jar` must keep its capitals on a case-sensitive filesystem |
| **Windows** | `IDM_JAVA_HOME` or `JAVA_HOME` only; no search. The simulator jar is under `%USERPROFILE%\.m2\repository\com\pointblue\dirxml\dirxml-simulator\1.6.1\` | `bin\idm.cmd`; Git Bash or WSL can run `bin/idm`, which has the fuller search and reads `$HOME/.m2` — use one home directory for Maven and the launcher | `passwordCommand=` or `passwordEnv=`; there is no Keychain | The mode-600 warning is a POSIX check; keep the two properties files out of git and out of shared folders |

A fresh Designer project from a vault, without Designer connecting, is a
later task: [howto-fresh-designer-project.md](howto-fresh-designer-project.md).

## When this is done

The client directory is where you work. Call `bin/idm` by its full path, or
put it on `PATH`. Next:

- [getting-started.md](getting-started.md) — the same first import, and how
  to look around the tree.
- [agents.md](agents.md) — how any agent runs the loop after setup.
- [agent-guide.md](agent-guide.md) — the rules that agent follows.
- [day-to-day.md](day-to-day.md) — a policy change through deploy.
- [install.md](install.md) — the same setup, typed by hand, plus CI.
