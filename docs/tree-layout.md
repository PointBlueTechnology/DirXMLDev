# Tree layout

An IDM-as-code tree is the driver set as files. `bin/idm import`,
`import-project`, `import-ldif`, and `import-live` write it. Git is the
history. The vault is where you deploy it.

This page is the plain-language map. The file-format contract (canonical XML,
package-stamp attributes, round-trip) is [model.md](model.md).

Names below are fictional. A real driver is whatever `driverset.xml` lists.

## Beside the tree

These live in the client directory, next to `tree/`, not inside it.

| Path | Committed? | What it is |
|---|---|---|
| `environments.properties` | No | Vault URL, bind DN, tier, driver-set DN, where the password comes from. One prefix per environment (`stg.`, `prd.`). |
| `secrets-<env>.properties` | No | Shim passwords, Remote Loader passwords, named passwords. The tool reads them at deploy and never prints them. |
| `cases/` | Yes | Simulator cases. `simulate --cases cases/` runs them. |
| `deploy-log/<env>.jsonl` | Yes | One JSON line per deploy or driver operation. The production gate reads it. |
| `deploy-snapshots/<env>/` | No | LDIF (and cache XDS) taken before a write. `vault.rollback --snapshot` restores an LDIF. Keep it out of git and out of chat. |
| `catalog/` | Yes | Package jars you fetched or built. Optional. |

A redacted environments file and secrets file are in [examples/](examples/).

## Inside `tree/`

```
tree/
  driverset.xml
  config-values.xml
  .gitattributes
  .package-baseline/
  library/
    library.xml
    <name>.policy.xml
    <name>.js
    <name>.mapping-table.xml
    <name>.gcv.xml
  drivers/
    AD Driver/
      driver.xml
      driver-filter.xml
      config-values.xml
      shim-config-info.xml
      engine-control-values.xml
      icon.gif
      <name>.policy.xml
      subscriber/
      publisher/
      entitlements/
      provisioning/
```

### `driverset.xml`

The driver-set manifest: the set's name and DN, and one `<driver>` per driver
pointing at `drivers/<name>`. This is the table of contents. Operations rewrite
it when you add or remove a driver. Do not edit it by hand.

### `config-values.xml` (next to `driverset.xml`)

Global configuration values for the whole driver set. A driver can override
the same name in its own `config-values.xml`. `query tree/ gcvs "<driver>"`
shows the value that wins and where it is defined.

### `library/`

Policies, ECMAScript, mapping tables, and GCV objects shared by more than one
driver. `library.xml` lists them. A driver uses a library object by linking
it (`policy.link`); the link is recorded in that driver's `driver.xml`, and
the file stays in `library/`.

### `drivers/<driver>/driver.xml`

That driver's manifest: shim class, authentication server and id (not the
password), which config files exist, every policy and resource, the
entitlements, and the **linkage** — which object runs in which policy set, in
which order. The linkage is what Designer's policy flow shows.
`query tree/ chain "<driver>" sub` prints the subscriber order.
`query tree/ fishbone "<driver>"` prints the same flow as the VS Code viewer.

### Channel directories

`subscriber/` is Identity Vault → application. `publisher/` is application →
Identity Vault. Policies that belong to the driver as a whole (schema map,
input and output transforms) sit next to `driver.xml`, not in those folders.

On disk the file is `<name>.policy.xml`, `<name>.js`, or
`<name>.mapping-table.xml`. Commands address the object by **artifact path**,
which is the name without the extension:

```
drivers/AD Driver/subscriber/ACME-sub-ctp-NormalizeTitle
library/lib-Shared
```

`show`, `refs`, `package.diff`, `rule.add`, and `policy.link` all use that
path. Quote it when the driver name contains spaces.

### Config files next to `driver.xml`

| File | What it is |
|---|---|
| `driver-filter.xml` | Which classes and attributes synchronize, per channel |
| `shim-config-info.xml` | Shim parameters (the driver's options page) |
| `engine-control-values.xml` | Engine control values for this driver |
| `config-values.xml` | This driver's GCVs |
| `icon.gif` (or `.png`) | The icon Designer and iManager show. Opaque bytes. A tree without one leaves the vault's icon alone |

Passwords are not in these files. They live in `secrets-<env>.properties`.

### `entitlements/`

`<name>.xml` for each `DirXML-Entitlement` on the driver. Workflows grant
them; the Identity Applications read the definition from the vault. They
deploy with `vault.deploy` and do not restart the driver.

### `provisioning/`

Present on a User Application driver that has an `AppConfig` container.

```
provisioning/
  provisioning.xml
  forms/request/<name>.form.json
  forms/approval/<name>.form.json
  forms/template/<name>.form.json
  prds/<name>/definition.xml
  prds/<name>/request.xml
  objects/...                 roles, resources, entities, navigation, …
```

`form.list` and `prd.list` read this tree. `prd.flow` walks the workflow
inside a PRD. JSON forms are pretty-printed here so git diffs are readable;
the deployer writes the compact document the vault expects.

### `.package-baseline/`

The content of a packaged policy, form, PRD, or other object **before** the
first customization. The object in `drivers/` or `library/` is your current
copy and is marked customized. `package.diff tree/ <artifact-path>` shows the
difference. Do not delete the baseline if you want a later package upgrade to
know what you overrode.

### `.gitattributes`

Import writes this once:

```
# IDM-as-code: content is byte-exact vault data; never normalize line endings
* -text
```

Keep it. Vault content is bytes. If git converts a CRLF inside an ECMAScript
resource, `vault.diff` will report a change you did not make.

## What you edit, and what you do not

| You want to | Do this |
|---|---|
| Change a rule's XML | Edit the `.policy.xml` file, or `rule.add` / `artifact.set-content`, then `validate` |
| Add, link, rename, or reorder a policy | `policy.add`, `policy.link`, `policy.reorder`, `artifact.rename` |
| Change a GCV, the filter, the schema map, a mapping-table row | `gcv.set`, `filter.set-attr`, `schema-map.set`, `mapping-table.set-row` |
| Add a driver | `driver.add` |
| Customize a packaged object | Edit it through an operation. The tool marks it customized |
| Change which drivers exist in the set | An operation. Not a hand-edit of `driverset.xml` |
