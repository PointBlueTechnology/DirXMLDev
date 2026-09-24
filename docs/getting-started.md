# Getting started — using DirXMLDev

This is for someone who already has `bin/idm`. It gets a client directory,
a vault target, and a first import onto disk. Building from source, the
engine jars, the macOS Keychain, and TLS are in [install.md](install.md).

You run commands from the **client directory**. The DirXMLDev checkout is only
where `bin/idm` lives. Call it by path (`~/IdeaProjects/DirXMLDev/bin/idm`) or
put it on `PATH`. Client content never goes in the DirXMLDev repository.

`bin/idm` with no arguments lists every command. `bin/apps --help` lists the
Identity Applications helper.

## 1. One directory per client

```
client-acme/
  environments.properties     vault targets — gitignored, mode 600
  secrets-stg.properties      driver secrets — gitignored, mode 600
  secrets-prd.properties
  tree/                       the driver set as code — committed
  cases/                      regression corpus for simulate — committed
  deploy-log/<env>.jsonl      one line per deploy or operation — committed
  deploy-snapshots/<env>/     LDIF taken before every write — gitignored
  catalog/                    package jars (optional) — committed
```

Ignore `environments.properties`, `secrets*.properties`, `deploy-snapshots/`,
and `*.ldif` before the first commit. A redacted copy of the two property
files is in [examples/](examples/).

`bin/idm` looks for `environments.properties` in `$IDM_ENVIRONMENTS`, then the
working directory, then `~/.idm/environments.properties`.

## 2. What the tree is

`tree/` is the source of truth: one file per policy, filter, form, and so on,
plus three manifests (`driverset.xml`, `library/library.xml`,
`drivers/<driver>/driver.xml`). You commit it. You do not hand-edit the
manifests; operations update them.

A plain-language map is [tree-layout.md](tree-layout.md). The short version:

```
tree/
  driverset.xml                 which drivers exist
  config-values.xml             driver-set GCVs
  library/                      shared policies, ECMAScript, mapping tables
  drivers/<driver>/             that driver's policies, filter, channels
  drivers/<driver>/provisioning/   JSON forms and PRDs (User Application driver)
  .package-baseline/            pre-edit copy of a customized packaged object
  .gitattributes                written by import: * -text, so line endings stay exact
```

`cases/`, `environments.properties`, and `secrets-*.properties` sit **beside**
`tree/`, not inside it.

## 3. Configure the vault target

One block per environment. The prefix is the name you pass to `--env` and, on
production, to `--confirm`. The hosts below are fictional.

```properties
# --- staging ---
stg.url=ldaps://idm-stg.example.com:636
stg.bindDn=cn=idm-deploy,ou=sa,o=system
stg.passwordEnv=IDM_STG_PASSWORD
stg.driverSet=cn=driverset1,o=system
stg.tier=stg
stg.secrets=secrets-stg.properties
stg.trustAll=false
stg.sshHost=idm-stg.example.com
stg.sshUser=idm

# --- production ---
prd.url=ldaps://idm.example.com:636
prd.bindDn=cn=idm-deploy,ou=sa,o=system
prd.passwordEnv=IDM_PRD_PASSWORD
prd.driverSet=cn=driverset1,o=system
prd.tier=prd
prd.secrets=secrets-prd.properties
prd.trustAll=false
prd.requires=stg
```

Required for every environment: `url`, `bindDn`, a password, `driverSet`.
`tier` is `dev`, `stg`, or `prd` (default `dev` when omitted).

The password is one of four keys, for `password` and for every other secret
(`appsPassword`, `appsSecret`, and each key in the secrets file):

| Key | Value |
|---|---|
| `<k>=` | A literal. Throwaway labs only |
| `<k>Env=VAR` | An environment variable |
| `<k>Command=…` | A command whose stdout is the secret (`op read …`, `pass show …`) |
| `<k>Keychain=service[/account]` | macOS Keychain. [install.md](install.md) has the `security` commands |

```bash
chmod 600 environments.properties secrets-*.properties
```

The tool warns when either file is readable by other users. It never prints a
secret.

**What the other keys do**

- `secrets` — file of shim passwords, Remote Loader passwords, and named
  passwords. A new driver's deploy refuses until each required secret is
  present (`MISSING SECRET: <driver>.<key>` in the plan). Existing drivers
  are left alone unless you pass `--secrets missing` or `--secrets all`.
- `requires` — on a production deploy, a green deploy of the same tree commit
  to that other environment must already be in `deploy-log/`.
- `sshHost` / `sshUser` — key-based SSH to the engine host, for
  `driver.trace tail`. Without them, tail is unavailable; everything else works.
- `trustAll` — when omitted, LDAPS accepts any server certificate. Set
  `trustAll=false` once the vault CA is in the JDK truststore
  ([install.md](install.md) §4.3).
- `formsUrl`, `appsUser`, `appsPassword` (any of the four forms), optional
  `appsClient` (default `rbpmrest`) and `appsSecret` — only for `bin/apps`
  and for `form.edit --env`.

Give each environment its own eDirectory user with rights on its driver set
(and on the User Application driver's `AppConfig` when you deploy forms or
PRDs). `admin` is fine on a lab.

## 4. Import the driver set

The live vault is the ground truth when you can reach it.

```bash
bin/idm import-live tree/ --env stg
bin/idm validate tree/
bin/idm vault.diff tree/ --env stg
```

`import-live` reads `url`, `bindDn`, the password, and `driverSet` from the
environment. A driver-set DN before the output directory overrides `driverSet`:

```bash
bin/idm import-live "cn=driverset1,o=system" tree/ --env stg
```

The older form, with credentials in `IDM_JAVA_OPTS`, still works when you have
no environments file yet:

```bash
IDM_JAVA_OPTS="-Dldap.url=ldaps://idm-stg.example.com:636 -Dldap.bindDn=cn=idm-deploy,ou=sa,o=system -Dldap.password=…" \
  bin/idm import-live "cn=driverset1,o=system" tree/
```

Do not put a real password in shell history. Prefer `--env`.

Other sources, when the vault is not the one you trust yet:

```bash
bin/idm import-project ~/designer_workspace/Client tree/
bin/idm import DriverSet-export.xml tree/
bin/idm import-ldif driverset.ldif tree/
```

What to expect the first time:

- `validate` on a tree imported from a running vault reports 0 errors. Warnings
  and infos (template placeholders, missing localisations) are normal. An error
  on a production tree is a finding: report it, and do not edit the tree to
  silence it.
- `vault.diff` right after `import-live` reports no differences. Exit status 1
  means the tree and the vault differ.
- Import writes `tree/.gitattributes` (`* -text`) so git does not rewrite line
  endings. Keep that file. A CRLF inside an ECMAScript resource must survive
  checkout or the next diff will show it.

```bash
git add tree/ && git commit -m "Import stg driver set"
```

## 5. Look around before you change anything

These commands only read the tree.

```bash
bin/idm query tree/ drivers
bin/idm query tree/ artifacts "AD Driver"
bin/idm query tree/ chain "AD Driver" sub
bin/idm query tree/ gcvs "AD Driver"
bin/idm query tree/ tables "AD Driver"
bin/idm query tree/ fishbone "AD Driver"
bin/idm show tree/ "drivers/AD Driver/subscriber/sub-ctp-Transform"
bin/idm refs tree/ "library/lib-Shared"
```

`show`, `refs`, and `package.diff` take an **artifact path**: the object's
name, with no filename extension (`drivers/AD Driver/subscriber/sub-ctp-Transform`,
not `….policy.xml`). `chain` takes `sub` or `pub`.

`bin/idm check tree/` loads the tree and exits 1 when a link points at nothing.

## 6. Next

- Hand the same directory to an agent: [agents.md](agents.md).
- Change, prove, and deploy: [day-to-day.md](day-to-day.md).
- The same loop as one narrative: [walkthrough.md](walkthrough.md).
- File-by-file layout: [tree-layout.md](tree-layout.md).
- Fictional samples: [examples/](examples/).
