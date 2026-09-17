# How-to: a fresh Designer project from an Identity Vault, without Designer talking to the vault

**When to use this.** You need a Designer project of a customer's driver set,
and Designer's own *Import from the Identity Vault* is unusable: a slow link
to the vault, an anti-virus scanner watching Designer's workspace, or both.
DirXMLDev reads the vault once over LDAP into a set of files, writes one
configuration file in Designer's own export format, and Designer imports that
file from disk with no vault connection at all. The steps below assume a
Windows workstation; the Mac/Linux commands are the same with `bin/idm`
instead of `bin\idm`.

What you get: a project holding the whole driver set (every driver with its
policies, filters, schema maps, GCVs, mapping tables, ECMAScript, engine
control values, shim settings and package associations, plus the Library).
What the configuration-file route does **not** carry, and how to get it, is in
§5.

## 1. One-time setup on the Windows workstation

1. JDK 21 and Maven installed; `set JAVA_HOME=C:\Program Files\Zulu\zulu-21`
   (or `IDM_JAVA_HOME`). Git, and Python 3 if you will use `bin\apps`.
2. The simulator built once (`mvn install` in its repository, with the engine
   jars in its `lib\`), and DirXMLDev cloned with `lib` pointing at those jars
   (`mklink /J lib ..\DirXMLSimulator\lib` from an administrator prompt, or a
   copy of the jars). `mvn -q test` in DirXMLDev builds it.
3. `bin\idm.cmd` is the Windows launcher (`bin\idm` from a Git Bash / WSL
   shell works too). `bin\idm` with no arguments lists every command.
4. A working directory for the customer (not inside the DirXMLDev checkout),
   with an `environments.properties` for the vault — see
   [getting-started.md](getting-started.md) §3–4. On Windows the password
   should not be a literal in the file:

   ```properties
   cust.url=ldaps://idm.customer.example:636
   cust.bindDn=cn=admin,ou=sa,o=system
   cust.passwordEnv=IDM_CUST_PASSWORD
   cust.driverSet=cn=driverset1,o=system
   cust.tier=dev
   ```

   and in the shell that runs the export, `set IDM_CUST_PASSWORD=…` (or use
   `cust.passwordCommand=powershell -NoProfile -Command "(Get-StoredCredential -Target idm-cust).GetNetworkCredential().Password"`
   with the PowerShell `CredentialManager` module, which keeps it in the
   Windows Credential Manager). The tool warns when the file is readable by
   other users.
5. TLS: Java must trust the vault's certificate. Import the CA once
   (`keytool -importcert -cacerts -alias cust-ca -file cust-ca.cer` as
   administrator) or, for a lab whose certificate does not match the host
   name, `set IDM_JAVA_OPTS=-Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true`.

Anti-virus: the tool writes a few hundred small XML files under `tree\` and
one export file. If the scanner slows even that, exclude the working
directory; nothing here needs Designer's workspace to be scanned.

## 2. Read the vault once

```bat
cd C:\idm\customer
bin\idm import-live tree --env cust
bin\idm validate tree
bin\idm vault.diff tree --env cust
```

`import-live` reads the driver set named by the environment (a driver-set DN
before `tree` overrides it) over one LDAP session — every object, every
attribute, package stamps included — and writes `tree\`. Expect seconds to a
minute for a normal driver set even on a slow link; it is a read of a few
megabytes, not the object-by-object conversation Designer holds. `validate`
loads every policy through the engine's own compilers (0 errors is normal for
a production vault) and `vault.diff` must say `no differences`. Commit
`tree\` if the customer keeps a repository.

If Java cannot reach the vault at all but a jump host can, run the same
`import-live` on the jump host (any OS; the tool is the same jar) and copy
`tree\` over, or copy the export file from §3.

## 3. Write the configuration file

```bat
bin\idm export tree customer-driverset.xml
```

This is a Designer *Export to Configuration File* document in its
`<driver-set-configuration>` form: the driver set, its GCVs, the Library, and
every driver with its shim settings, filter, schema map, policies and
resources, policy-set linkage, engine control values and the package
attributes Designer records on packaged objects (`package-id`,
`pkg-assoc-id`, `checksum`, `modified` on each object; `package-id` and
`package-version` on each driver — derived from the vault's `DirXML-pkg*`
stamps). The writer is the exact inverse of the reader that imports such
files, so what Designer exports and what the tool writes are the same shape.
One file, a few megabytes; copy it to the workstation that runs Designer if
that is a different machine.

Measured on the idm254 lab (4 drivers, 2026-09-16): `import-live` over the
tunnel in under a minute, a 640 KB file, and `import` of that file back into a
second tree gives identical content — the only differences `tree.diff` reports
are the package stamps' representation (vault form vs export form) and the 11
forms and 39 PRDs the format cannot carry (§5).

## 4. Import it into a new project in Designer

1. **Designer → File → New → Project**, a name, an empty project (no vault
   connection asked for).
2. In the Modeler, add an **Identity Vault** from the palette. Leave its
   connection unconfigured, or fill the host and user without testing it —
   Designer never needs to connect for this route.
3. Right-click the Identity Vault (or the driver set it created) → **Import
   from Configuration File…** and choose `customer-driverset.xml`. Designer
   creates the driver set and every driver from the file. Answer the prompts
   about the driver set placement with the same container as the vault
   (`o=system` in the example); leave every driver's password prompts empty —
   they are not in the file (§5).
4. Save the project. Open a driver's policy set and a policy to see the
   content is there; *Project → Validate* if you like.

**Packages.** Each packaged object in the file names its package and
version. Designer shows the driver as packaged when those packages are in its
catalog, so before step 3 make sure they are: *Help → Check for Package
Updates* against the vendor site, and, for packages built with
`package.build`, add the site `bin\idm package.site` publishes (Designer:
*Window → Preferences → Package Manager → Sites*). A package that is absent
from the catalog leaves its objects in the project as plain, un-packaged
objects — they work, but Designer will not offer upgrades for them.

## 5. What the configuration-file route leaves out

| Not in the file | Why | How to get it |
|---|---|---|
| **Provisioning forms, request definitions (PRDs, workflows), entitlements** under the User Application driver's `AppConfig` | Designer's configuration-file format has no place for them | Designer → *Live → Import* on the **User Application driver only**, pick *Provisioning* objects. That is a small subtree and fast even on a slow link. Or leave them out: DirXMLDev authors and deploys forms, PRDs and entitlements as code without Designer ([forms.md](forms.md), [workflows.md](workflows.md), [entitlements.md](entitlements.md)). |
| **Jobs** (`DirXML-Job`) and role-based entitlement policies | not carried by the export format; the tree notes their count only | Designer *Live → Import* of those objects, if they matter |
| **Passwords**: shim, Remote Loader, named passwords, application passwords in GCVs | the vault never returns them | type them in Designer when needed, or keep them out of the project (recommended) and let `vault.deploy` set them from the secrets file at deploy time |
| Designer-only decoration: diagram layout, notes, colours | not vault objects | Designer lays out the imported project itself |

**The planned replacement for §4–5:** `export-project --new`, a project
written straight from the tree with the `AppConfig` objects and the package
catalog entries the configuration file cannot carry —
[designer-new-project.md](designer-new-project.md).

## 6. Keeping the project in step afterwards

Once the project exists, do not repeat the import for every change:

- Changes made **with DirXMLDev** flow into the project with
  `bin\idm export-project tree C:\designer_workspace\Customer` (it edits only
  the files the diff calls for, keeping Designer's layout; see
  [walkthrough.md](walkthrough.md) step 9). It refuses to create a *new*
  packaged driver in the project — for that, deploy to the vault and let
  Designer *Live → Import* just that driver.
- Changes made **in Designer** come back with `bin\idm import-project` into
  a fresh tree, or, after Designer deploys them, with another `import-live`.
- The vault stays the source of truth: `bin\idm vault.diff tree --env cust`
  tells you at any time whether the tree, and so the project made from it,
  still matches.

## 7. What is verified, and what is not yet

- Verified: the vault read, the file, and its round trip through the tool's
  own reader (§3). The tool round-trips Designer's own exports byte-for-byte
  in its tests, so the format is Designer's.
- **Not yet verified in Designer itself:** the import of a file the tool
  wrote — in particular that Designer associates the packages from the
  `package-id`/`pkg-assoc-id` attributes the way it does for its own exports.
  Do the first one on a scratch project, check that a packaged driver shows
  its packages installed and nothing marked modified, and record the result
  in `docs/spikes/` (the same check spike 7c made for a vault import). If
  Designer rejects the file, send the error text and, if the customer allows
  it, the file itself to the DirXMLDev maintainers.
