# A project per environment: compare and promote

Some teams keep one project per environment: a QA tree and a production tree,
each in its own repository and deployed only to its own vault. `tree.compare`
shows what differs between two such trees. `promote.plan` and `promote.apply`
copy chosen differences from one tree into the other. The design is
DirXMLDevWeb's [project-per-environment.md](https://github.com/PointBlueTechnology/DirXMLDevWeb/blob/main/docs/project-per-environment.md).

The other model, one tree deployed to every environment with
`overrides/<env>.properties`, works as before
([existing-environments.md](existing-environments.md)).

The examples use two fictional projects: `acme-qa/tree` (the source) and
`acme-prod/tree` (the target).

## The pair's files

Everything about a pair lives in the target's tree, under `promotions/`, and is
committed with it:

| File | Written by | What it holds |
|---|---|---|
| `promotions/<source>.properties` | you | The map: which source driver is which target driver, and which source server plays which target server |
| `promotions/<source>.known.json` | `promote.accept`, `promote.forget` | Differences accepted as they are |
| `promotions/<source>.json` | `promote.pair`, `promote.apply` | The promotion record: each object's fingerprints, as the source had it and as the target had it afterwards |

`<source>` is the pair's name. By default it is the source tree's directory
name, or its project's name when the directory is `tree` (`acme-qa/tree` pairs
as `acme-qa`). `--name` gives another one: letters, digits, `.`, `_` and `-`.

### The map

The map is in the form of an overrides file: one `key = value` per line, split
at the first `=`, with `#` starting a comment. The source plays the tree and the
target plays the environment:

```properties
# acme-prod/tree/promotions/acme-qa.properties
drivers/AD QA.name = AD
servers.vault01qa = cn=vault01,ou=servers,o=acme
```

- `drivers/<the source's name>.name = <the target's name>`: only for drivers
  the two name differently.
- `servers.<a source server's name> = <the target's server's DN>`: one line per
  source server, several onto one where the target has fewer
  (`servers.<target server's name>.prefer = <source server's name>` picks whose
  per-server settings win, as in an overrides file). A server's name is its
  `servers/<name>/` folder's: the server's `cn`, in lower case.

The driver set's name and DN, and each driver's DN, are always the target's.
They never move. Nothing else is rewritten. A DN inside a policy that differs
per environment belongs in a GCV or a known difference.

After a change to the map, start the record over (`promote.pair --force`):
the record names objects by the target's paths.

## What is compared

Each tree is read, the source is translated through the map, and both are
written as files again. Then:

- **Files** are compared with their values set aside: every value an override
  could carry (driver and driver-set GCVs, shim parameters, engine control
  values, the authentication server and ID, a job's schedule, state and trace) is
  emptied on both sides first. A file differs only for its logic: a policy, a
  filter, linkage, a definition one side lacks.
- **Values** are compared on their own, key by key, as an overrides file keys
  them (`drivers/AD.gcv.drv.users`). Only values both sides hold are listed. A
  value only one side has comes with its file.
- Not compared: `overrides/`, `promotions/` and anything starting with `.`
  (`.package-baseline/`, `.gitattributes`). A promoted file's package
  baseline goes with it.

## The buckets

The record holds, per file and per value, a fingerprint of each side when it
was last promoted (or paired). Comparing those with the fingerprints now sorts
each difference:

| Bucket (JSON key) | The source since the record | The target since the record |
|---|---|---|
| **pending** (`pending`) | changed | unchanged |
| **target's own** (`own`) | unchanged | changed |
| **both changed** (`both`) | changed | changed |
| **unchanged since the record** (`unchanged`) | unchanged | unchanged |
| **known** (`known`) | accepted with `promote.accept`, and neither side changed it since | |
| **not paired yet** (`unpaired`) | the pair has no record yet | |

Once a pair has a record, a file with no entry was absent from both sides when
the record was taken: a file new in the source is pending, a file new in the
target is the target's own. A removal is a difference too: a file the source
removed since the record is pending, as *only in the target*.

It goes object by object, not commit by commit. Promoting two of five pending
files leaves the other three pending.

## Commands

`<target>` and `<source>` are tree directories. Every command takes `--json`.

### `tree.compare`

```
idm tree.compare <target> --with <source> [--name <pair>] [--json]
```

| Option | Required | Accepted values |
|---|---|---|
| `--with` | yes | the source tree |
| `--name` | no | the pair's name (default: from the source's directory) |

Prints the differences by bucket, any notes (a source server not mapped, a
driver the map names that the source lacks), and the drivers that look like
one another under other names, which are candidates for the map. Known and
unchanged differences are counted, not listed. Exit 0 when nothing is open
(everything is known or unchanged), 1 when something is.

```bash
bin/idm tree.compare acme-prod/tree --with acme-qa/tree
```

```
acme-prod/tree compared with acme-qa/tree (pair 'acme-qa')
pending (2):
  drivers/AD/itp.policy.xml  differs
  drivers/AD.gcv.drv.users  value: source=qa-users-2  target=users
target's own (1):
  drivers/AD/smp.policy.xml  differs
0 known, 1 unchanged since the record (not listed)
```

In JSON, each item has `path`, `kind` (`file` or `value`), `driver` (the
target's name, or null), `bucket`, `state` (`differs`, `only in the source`,
`only in the target`), `fingerprint` (the difference's own), `changedSinceKnown`,
and either `detail` (the changed lines, target `-` to source `+`, with two lines
of context) or `source` and `target` (the two values).

### `promote.pair`

```
idm promote.pair <target> --from <source> [--name <pair>] [--dry-run] [--force] [--by <who>] [--json]
```

Takes the first record: every file and value of both sides as they are now,
including the ones that differ. From then on only new changes show. Write the
map first. Refused if the pair already has a record, unless `--force`, which
starts the record over.

```bash
bin/idm promote.pair acme-prod/tree --from acme-qa/tree --by jdoe
```

### `promote.plan` and `promote.apply`

```
idm promote.plan  <target> --from <source> [--name <pair>] [--object <path>]… [--value <key>]… [--pending] [--contents <file.json>] [--json]
idm promote.apply <target> --from <source> [--name <pair>] [--object <path>]… [--value <key>]… [--pending] [--contents <file.json>] [--force] [--by <who>] [--json]
```

| Option | Required | Accepted values |
|---|---|---|
| `--from` | yes | the source tree |
| `--object` | at least one of these four | a file that differs (`drivers/AD/itp.policy.xml`), or a directory, meaning every differing file under it (`drivers/AD`). Repeatable |
| `--value` | | an overrides key of a value both sides hold (`drivers/AD.gcv.drv.users`). Repeatable |
| `--pending` | | every pending file (never values) |
| `--contents` | | a JSON file `{"<path>": "<content>"}`: a file as you merged it, line by line, in place of the source's. Names that file too |
| `--force` | no | writes despite new validation errors |
| `--by` | no | who, for the record |

`promote.plan` does everything but write. `promote.apply`:

1. Copies each chosen file of the translated source into a copy of the target,
   or removes it where the source removed it, along with its package baseline.
2. **Keeps the target's values**, except the ones named with `--value`. A
   promoted policy brings its logic, not the source's URL. A value only the
   source has comes with its file.
3. **Reads the result back and validates it.** It is refused when:
   - a chosen file wouldn't be part of the target's model: a new policy whose
     manifest (`driver.xml`, `library/library.xml`, `driverset.xml`) wasn't
     promoted with it, or a removal whose manifest still lists the file.
     Promote the manifest, or its lines, too;
   - the result has validation errors the target didn't have (unless `--force`).

   Nothing is written when it's refused.
4. Writes only the target files whose bytes change, and updates
   `promotions/<source>.json` for what moved.

It writes the tree in place, on whatever branch is checked out. Committing,
branching and the pull request are yours (DirXMLDevWeb puts them on a new
branch). The result gives the commit trailer to use,
`Promoted-from: acme-qa@<the source's commit>`.

```bash
bin/idm promote.plan acme-prod/tree --from acme-qa/tree --object "drivers/AD/subscriber/sub-new.policy.xml" --object "drivers/AD/driver.xml"
bin/idm promote.apply acme-prod/tree --from acme-qa/tree --pending --by jdoe
bin/idm promote.apply acme-prod/tree --from acme-qa/tree --value "drivers/AD.gcv.drv.users" --by jdoe
```

A merged file is recorded as the target has it. Until either side changes it
again, it shows as unchanged since the record, not pending.

### `promote.accept` and `promote.forget`

```
idm promote.accept <target> --from <source> [--name <pair>] --object <path or key> [--decision intended|later] [--note <text>] [--by <who>] [--dry-run] [--json]
idm promote.forget <target> (--from <source> | --name <pair>) --object <path or key> [--dry-run] [--json]
```

| Option | Required | Accepted values |
|---|---|---|
| `--object` | yes | one file path or value key that differs now |
| `--decision` | no | `intended` (different on purpose; the default) or `later` (to decide) |
| `--note` | no | free text |

An accepted difference is counted as known while neither side changes it. Once
one does, it shows again, flagged `changedSinceKnown`. `promote.forget` drops
the entry.

```bash
bin/idm promote.accept acme-prod/tree --from acme-qa/tree --object "drivers/AD/smp.policy.xml" --note "production maps Surname itself"
```

## Limits

- **Cases:** `cases/` are not promoted yet.
- **Environment lists:** a driver's or object's `environments` and `planned`
  lists move with its manifest as they are.
- **Renaming a driver in the map:** the record names objects by the target's
  paths, so after a rename, start the record over.
