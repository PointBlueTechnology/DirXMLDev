# How to clone an Identity Vault to disk, and deploy it to a test vault

A manual runbook. Every step is a command you type; no agent is involved. The
outcome of the first half is one zip file that you carry home; the second half
turns that zip into a working test vault with an engine serving the cloned
driver set. The design and the lessons behind it are in
[vault-clone.md](vault-clone.md); this page only says what to do.

What travels in the zip: the schema, the tree's containers, `cn=Security`
(password policies, notification templates), the driver set with every driver,
policy, GCV, job, entitlement and the User Application driver's `AppConfig`, and
optionally a slice of identity data. What never travels: any password
(`userPassword`, shim passwords, Remote Loader passwords, named passwords),
driver run-time state and cache, PKI and login-method internals, iManager RBS
objects unless asked for. Passwords are listed by name in `secrets-needed.txt`
so you can set them on the lab afterwards.

## Part 1 — at the customer: export to a zip

### 1.1 What you need on the workstation

- **JDK 21**, **Maven 3.9+**, **git**.
- The **DirXMLSimulator** repository, built and installed (`mvn install`), with
  the engine jars from the customer's own IDM installation in its `lib/`
  (`/opt/novell/eDirectory/lib/dirxml/classes/` on an engine server, or the
  Designer install). The jars are proprietary and are never committed.
- The **DirXMLDev** repository with `lib` symlinked to the simulator's `lib`, built
  once (`mvn -q test`). `bin/idm` then runs everything; on Windows use
  `bin\idm.cmd` (a JDK 21 in `IDM_JAVA_HOME` or `JAVA_HOME`).
- **LDAPS** to the vault server that holds the driver set. If the driver set is
  served by more than one server, LDAPS to each of them, or an SSH tunnel per
  server (§1.3).
- An eDirectory identity that can **read the whole tree including `cn=Security`
  and every ACL**. `admin` does; a plain read-only account usually does not see
  ACLs or password policies, and the bundle is then incomplete without an error.

[install.md](install.md) §1–§2 has the install in full. Using the built tool
is [getting-started.md](getting-started.md).

### 1.2 A working directory

```bash
mkdir -p ~/idm-work/<customer> && cd ~/idm-work/<customer>
chmod 700 .
```

Everything below happens in that directory. The bundle and the zip land there;
nothing is written to the vault by the export.

### 1.3 Describe the source vault

Create `environments.properties` (mode 600) with one block for the source. Any
credential may be a literal, an environment variable, a command, or a macOS
Keychain item; prefer the indirect forms.

```properties
src.url=ldaps://idm-vault.customer.example:636
src.bindDn=cn=admin,ou=sa,o=system
src.passwordEnv=IDM_SRC_PASSWORD           # or src.passwordKeychain=… / src.passwordCommand=… / src.password=…
src.driverSet=cn=driverset1,o=system
src.tier=dev
src.secrets=secrets-src.properties         # may stay empty; the export needs no secrets
```

Then tell the tool where the file is:

```bash
export IDM_ENVIRONMENTS=$PWD/environments.properties
chmod 600 environments.properties
```

Two situations need one more line each:

- **A driver set on several servers.** The export reads each server's own
  values (start options, trace settings, GCV overrides) over its own LDAPS. It
  derives each server's URL from the server object; when that address is not
  reachable from your workstation, name the URLs yourself:
  `src.servers=cn=idm2,ou=servers,o=system=ldaps://10.0.0.2:636;cn=idm3,ou=servers,o=system=ldaps://10.0.0.3:636`
- **Only reachable through a jump host.** Forward the port and point the
  environment at the tunnel; the tool accepts the certificate's name not
  matching `127.0.0.1` (a build before 2026-09-23 needs
  `IDM_JAVA_OPTS=-Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true`).
  ```bash
  ssh -f -N -o ExitOnForwardFailure=yes -L 6636:idm-vault.customer.example:636 you@jump-host
  ```
  ```properties
  src.url=ldaps://127.0.0.1:6636
  ```

### 1.4 Check the connection

```bash
bin/idm engine.version --env src
bin/idm driverset.status --env src
```

The first prints the engine version; the second lists every driver with its
state. Both are read-only. If the first fails with "simple bind failed", the
credentials or the TLS name check are wrong (§1.3); if it says "No route to
host" while `ldapsearch` works, use the tunnel.

### 1.5 Export

Configuration only:

```bash
bin/idm vault.export-clone --env src --out clone-<customer>-$(date +%Y-%m-%d)
```

Configuration plus identity data, with names replaced by fakes on the way out:

```bash
bin/idm vault.export-clone --env src --out clone-<customer>-$(date +%Y-%m-%d) \
    --data o=data --pseudonymise
```

- `--data <container>[,…]` adds the people, groups, aliases and roles under the
  named containers, with their group memberships, managers, role assignments
  and driver associations. Never their passwords or login history.
- `--pseudonymise` replaces given name, surname, full and display name and the
  local part of every mail address with consistent fakes, on the export, so the
  bundle on disk never holds a real name. It does **not** touch `cn`, `uid`,
  `workforceID` or `description`; where logins are built from surnames those
  attributes still carry them. Decide with the customer whether that is
  acceptable before you take data at all.
- `--rbs` also takes the iManager RBS objects (normally left out).
- `--keep-driver-state` keeps each driver's start option as it is; without it
  every driver is exported as manual start, so nothing wakes up in the lab with
  the customer's endpoints.

The export takes seconds for the configuration and a few minutes for tens of
thousands of people. It prints what it read, what it skipped and why. Read that
output.

### 1.6 Inspect the bundle before you zip it

```text
clone-<customer>-<date>/
  manifest.json          what was read, counts, what was skipped, what was excluded
  secrets-needed.txt     the passwords the lab will need, by object and attribute
  10-schema.ldif         attribute and class definitions
  20-containers.ldif     containers, the driver set, the drivers
  30-objects.ldif        everything else, DN-valued attributes removed
  35-server-values/      per-server values of the driver set (one file per server)
  40-references.ldif     the DN-valued attributes, added once every target exists
  50-acls.ldif           the ACLs, last
```

Check three things:

```bash
python3 -c "import json;m=json.load(open('clone-<customer>-<date>/manifest.json'));print(m['counts']);print(m['skippedSubtrees'])"
cat clone-<customer>-<date>/secrets-needed.txt
grep -c "userPassword\|DirXML-ShimAuthPassword\|nspmDistributionPassword" clone-<customer>-<date>/*.ldif   # expect 0 on every file
```

The counts should match what you expect of the tree (drivers, policies, people
if `--data`). The skipped list names PKI, login-method and RBS subtrees, never a
driver. The grep proves no password is in the bundle.

### 1.7 Zip and carry

```bash
zip -r clone-<customer>-<date>.zip clone-<customer>-<date>
shasum -a 256 clone-<customer>-<date>.zip > clone-<customer>-<date>.zip.sha256
```

Expect tens of MB for a configuration, about 150 MB with 15,000 people. The
zip is the customer's configuration: hostnames, GCVs, policies, and with
`--data` their pseudonymised people. Move it as you would move any customer
data (an encrypted disk or an SFTP drop, not mail), and keep the checksum file
with it. Delete the bundle directory from the customer workstation afterwards
if the workstation stays there.

## Part 2 — at home: deploy the zip to a test vault

### 2.1 Build the test vault engine-first, and only then touch it

The lab must be an eDirectory **with the IDM engine configured to completion
before the clone arrives**. The engine's own configuration extends the IDM
schema and creates the driver set as a partition; a tree where the clone did
that first will accept the objects but its engine will never serve the driver
set (every start-option call answers "No Such Attribute", no driver ever gets a
state). This cannot be repaired afterwards; the tree has to be rebuilt.

For the lab containers on DockerHost1 the recipe is: an empty `config/`
directory holding only `silent.properties`, `docker compose up -d`, then wait
without restarting anything until the engine section is done:

```bash
tail -3 config/idm/log/idmconfigure.log      # ends with: Completed configuration of : Identity Manager Engine
```

Then, from your workstation, with a `lab` block in `environments.properties`
(the same keys as §1.3; `lab.driverSet` is the driver set the engine created,
normally `cn=driverset1,o=system`):

```bash
bin/idm engine.version --env lab     # a configured engine reports "build 3"; "build 0" means it is not
bin/idm driverset.status --env lab   # an empty list, no error
```

If `driverset.status` answers with an error naming `GetDriverStartOption`, the
engine is not configured: rebuild before going on.

### 2.2 Unpack and check

```bash
shasum -a 256 -c clone-<customer>-<date>.zip.sha256
unzip clone-<customer>-<date>.zip
cat clone-<customer>-<date>/secrets-needed.txt
```

### 2.3 A lab password for the cloned people

If the bundle carries people, the import gives every one of them a single lab
password. Put it in the lab's secrets file (mode 600) under any key:

```properties
# secrets-lab.properties
lab.user.password=…        # or lab.user.passwordEnv=… / lab.user.passwordKeychain=…
```

The customer's password policies travel with the clone and apply to that
password; a policy with `nspmMaximumLength` 12 refuses a 16-character one. The
import counts refusals and goes on; a re-run with a compliant password sets it
on everyone who has none.

### 2.4 Plan, then import

Without `--yes` the command plans and writes nothing. Read the plan.

```bash
bin/idm vault.import-clone --env lab --from clone-<customer>-<date> \
    --server cn=<labserver>,ou=servers,o=system --user-password lab.user.password
```

`--server` is the lab server's DN (the driver set is associated with it; every
source server maps onto it). Then the same command with `--yes`. It runs in
phases (schema, entries parents-first, the server-specific values, references,
ACLs, passwords, start options) and ends with a verify that reads back what it
wrote. A few minutes for the configuration, a few more for 15,000 people.

Read the notes at the end. Normal ones: schema attributes the LDAP server
cannot define (tagged syntaxes, transfer-option aliases), a dynamic group's
`memberQueryURL` not read back, references dropped because their target was
not in the clone. Not normal: any `failed add`.

### 2.5 Restart the engine, re-run the import

The engine picks the cloned drivers up at once, but restart it anyway so it
starts from the finished tree, then run the identical import command once more.
The re-run adds nothing, sets every driver to manual start through the engine
in case the first pass could not, and re-verifies:

```bash
docker compose restart                      # on the lab host, in the compose directory
bin/idm vault.import-clone --env lab --from clone-<customer>-<date> \
    --server cn=<labserver>,ou=servers,o=system --user-password lab.user.password --yes
bin/idm driverset.status --env lab          # every driver: stopped, manual
```

### 2.6 Secrets, then drivers

Work down `secrets-needed.txt`. A shim or Remote Loader password goes into the
lab's secrets file under `<driver>.shim-auth-password` /
`<driver>.remote-loader-password` and is written by a deploy; named passwords
go in directly:

```bash
bin/idm import-live tree-lab --env lab      # a tree of the lab as it is now
bin/idm vault.deploy tree-lab --env lab --driver "<driver>" --secrets all --allow-missing-secrets --dry-run
bin/idm vault.deploy tree-lab --env lab --driver "<driver>" --secrets all --allow-missing-secrets --yes
bin/idm driver.secrets set --env lab --driver "<driver>" --name <name> --stdin --yes   # the value on stdin
bin/idm driver.secrets list --env lab --driver "<driver>"
```

`--allow-missing-secrets` is needed because the inventory spans every cloned
driver; it sets what you provided and lists the rest. Start drivers one at a
time, beginning with those that need no external system (Loopback, Null):

```bash
bin/idm driver.start --env lab --driver "<driver>" --yes
bin/idm driver.status --env lab --driver "<driver>"
bin/idm driver.trace tail --env lab --driver "<driver>" --lines 200
```

A driver whose endpoint is the customer's system stays stopped unless you
point it at a lab endpoint first (GCVs and shim settings, through a tree and
`vault.deploy`).

## If something goes wrong

| Symptom | Cause | What to do |
|---|---|---|
| `simple bind failed` from every command | wrong credentials, or the certificate's name does not match a tunnel address on an older build | check the bind DN and password with `ldapsearch`; set the JVM option in §1.3 |
| the export prints a server "could not be reached" | a second server of the driver set is not reachable at its own address | `src.servers=…` in §1.3, or accept the primary's values for the drivers that ran there (the note says which) |
| `failed add cn=<driver>…: no access (-672)` on the import | the lab's engine was already running when the clone created the schema, or an older tool build | rebuild the lab engine-first (§2.1); a build from 2026-09-23 on never writes the start option when an engine answers |
| `failed reference … syntax violation (-613)` | the referenced object is missing, usually because a parent or driver add failed above it | fix the add, re-run the import; it converges |
| `driverset.status`: `GetDriverStartOption … No Such Attribute` | the engine is not configured on that tree | rebuild engine-first (§2.1) |
| people created without a password, `passwordsRefused` counted | a cloned password policy refuses the lab password | choose a compliant one, re-run with `--user-password` |
| `driver set … already exists with drivers the clone does not carry` | the lab already holds another driver set's drivers | a fresh lab, or `--replace-driverset` after a snapshot |
| the lab's drivers start with the customer's endpoints | `--keep-driver-state` was used, or a driver was started before its settings were changed | stop it; drivers are exported as manual start by default for this reason |

Every import is logged with a copy of what the lab held before, under
`<bundle>/imports/`; nothing the import does is hidden.
