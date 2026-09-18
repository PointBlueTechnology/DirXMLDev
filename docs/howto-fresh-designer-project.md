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

**Two routes.** §4 writes the project *straight from the tree*
(`export-project --new`) — no Designer import step, and it carries the User
Application driver's forms, PRDs and entitlements and the package catalog that
the configuration file cannot. §5 is the older configuration-file route, kept
for a Designer that will not open a project it did not create itself and for
comparison. Both start from the same `tree\` of §2.

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

## 3. Write the configuration file (only for the §5 route)

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

## 4. Write the project itself (`export-project --new`)

```bat
bin\idm export-project tree C:\designer_workspace\Customer --new ^
    --vault-name IDM_CUSTOMER --vault-host idm.customer.example ^
    --vault-user cn=admin,ou=sa,o=system ^
    --server idm-engine --server-context ou=servers,o=system ^
    --catalog packages
```

The directory must not exist or be empty and **its basename is the project
name** (`Customer` above): it is written into `.project`, `Customer.proj` and
`Customer.cproj`, so never rename the folder afterwards. Add `--dry-run` first
to see every file it would write without creating anything. **No vault
password is ever written** — Designer asks on its first connect.

What it writes: the Eclipse and Designer descriptors, the three model roots,
the domain, the `IdentityVault_`, the driver set and its Library, the modeler
diagram, and then everything the tree holds — every driver with its channels,
filter, schema map, policies, resources, GCVs, engine control values and shim
settings, an `Application_` and a driver icon per driver, the User Application
driver's whole `AppConfig` (`.provisioning`, `.appconfig`, the container
digests, the forms and the PRDs) and its entitlements. Packaged items carry
their package attributes and an `<id>_initial_state.xml` baseline, so Designer
can tell a customized object from an untouched one.

**`--catalog` is what makes a packaged driver look packaged.** Point it at the
git package catalog (`docs/packages.md`; `bin\idm package.fetch` and
`package.build` fill it). For every package the tree's stamps name, the
command finds the jar and writes the package into the project's *own* catalog
— the category, the category folder, the `IdmPackage_` with its readme,
licence, change log and language bundles, its nine package folders and one
object per package item — and then the `Idm:InstalledPackages` relations from
the driver, the driver set and the vault. Without it the items are still
stamped but Designer shows them as plain objects and its driver-set *Packages*
page reports "invalid values".

If the catalog is missing a package the tree uses, the command **refuses and
lists every missing one**, writing nothing:

```
REFUSED — the package catalog packages does not hold 3 of the 9 package(s) this tree's
stamps name; fetch or build them (package.fetch / package.build) and run again — nothing
was written:
  NOVLADBASE 2.2.7.20220330111630 [57T9GSLL_201003011155340962]
  …
```

Fetch them (`bin\idm package.fetch --catalog packages --package NOVLADBASE`) or,
for a package the customer built, `package.build`, and run again.

**Driver icons** come from the vault: `import-live` reads each driver's
`DirXML-DriverImage` — the custom icon someone set in Designer, or Designer's stock
icon for the driver type, whichever was deployed — into `drivers\<name>\icon.gif`
(or `.png`), and the project gets exactly those bytes. Only a driver with no image
in the vault falls back to a Designer install on the same machine, where the tool
copies `plugins\com.novell.core_*\icons\iManager\<ApplicationType>.gif` the way
Designer's own importer does (`IDM_DESIGNER`, then the `designer` system property,
then the usual install locations; `--designer DIR` overrides all three). With
neither it writes no icon and says so once; Designer draws its own once the
project is open.

Then in Designer: **File → Open Projects from File System…** (or *Import →
Existing Projects into Workspace*) and pick the folder. Check a driver's
policy set, the Provisioning view for the forms and PRDs, and a packaged
driver's *Packages* page.

**What `--new` still leaves out:** jobs (`DirXML-Job`), notification
templates, the Identity Vault schema, the rest of `AppConfig` (DirectoryModel,
UIConfig, RoleConfig, TeamDefs, AppDefs, AuthTypes), the driver-set settings no
tree records (`DSetCreatePartition`, `DirXML-LogEvents`, the trace/log
settings, `JavaEnvParameters`, `NamedPasswords`) and every password. Get the
first three with a Designer *Live → Import* of just those objects if they
matter; set the driver-set settings in Designer; keep passwords out of the
project and let `vault.deploy` set them from the secrets file.

## 5. The older route: import a configuration file into an empty project

Use this when §4 is not an option. It needs §3's `customer-driverset.xml`.

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
   they are not in the file.
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

**What this route leaves out, on top of §4's list:**

| Not in the file | Why | How to get it |
|---|---|---|
| **Provisioning forms, request definitions (PRDs, workflows), entitlements** under the User Application driver's `AppConfig` | Designer's configuration-file format has no place for them | use §4 instead, which writes them; or Designer → *Live → Import* on the **User Application driver only**, picking *Provisioning* objects. Or leave them out: DirXMLDev authors and deploys forms, PRDs and entitlements as code without Designer ([forms.md](forms.md), [workflows.md](workflows.md), [entitlements.md](entitlements.md)). |
| **The project's package catalog** | the file names packages but cannot carry them | use §4 with `--catalog`; or import the packages into Designer's workstation catalog before step 3 |
| **Jobs** (`DirXML-Job`) and role-based entitlement policies | not carried by the export format; the tree notes their count only | Designer *Live → Import* of those objects, if they matter |
| **Passwords**: shim, Remote Loader, named passwords, application passwords in GCVs | the vault never returns them | type them in Designer when needed, or keep them out of the project (recommended) and let `vault.deploy` set them from the secrets file at deploy time |
| Designer-only decoration: diagram layout, notes, colours | not vault objects | Designer lays out the imported project itself |

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

- Verified in code: the vault read; the configuration file and its round trip
  through the tool's own reader (§3); and, for §4, that `import-project` of a
  written project reads back equal to the tree, that a package written from a
  jar is identical to Designer's own copy of the same package version (attribute
  for attribute, item for item, and byte for byte in the readme, licence,
  language bundles and item contents), and that a driver icon is the same file
  Designer's importer copies.
- **Verified in Designer (2026-09-17, first check):** a `--new` project opens,
  the System Model / developer / Provisioning views are populated, the forms and
  PRDs open, the diagram is right, *Validate* is clean and a policy deploys from
  it. Two problems, both fixed since: drivers had no icon, and the driver-set
  *Packages* page reported "invalid values" because the project had no package
  catalog. See `docs/designer-new-project.md` §7.2a.
- **Not yet verified in Designer:** a `--new --catalog` project's *Packages*
  pages (driver set and driver) — packages installed, nothing marked modified —
  and that *Check for Package Updates* offers nothing wrong. Do the first one on
  a scratch project and record the result; the checklist is
  `docs/designer-new-project.md` §7.5.
- **Not yet verified in Designer:** the import of a configuration file the tool
  wrote (§5) — in particular that Designer associates the packages from the
  `package-id`/`pkg-assoc-id` attributes the way it does for its own exports.
  If Designer rejects the file, send the error text and, if the customer allows
  it, the file itself to the DirXMLDev maintainers.
