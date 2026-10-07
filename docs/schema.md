# Schema: the vault's in the tree, the application's from the shim

Designer keeps a copy of the Identity Vault's schema in the project, lets you define new
classes and attributes there and push them to the vault, and asks a driver's shim for the
application's schema. The tree does the same, and nothing about schema ever happens on its own:
every read is a command you run.

## The vault's schema in the tree — `schema/vault.xml`

```bash
idm vault.schema tree/ --env lab                 # cn=schema → schema/vault.xml (read-only on the vault)
idm query tree/ schema                           # classes, with the custom ones marked *
```

Every attribute (NDS and LDAP names, OID, syntax, single-valued, no-user-modification) and class
(kind, superclasses, mandatory and optional attributes, containment, naming attributes).
Importing a Designer project reads its `…_schema.xml` into the same file — that is the project's
own copy, not a refresh. `import-live` leaves the schema alone.

With a schema in the tree, validation warns (`schema-unknown-class`, `schema-unknown-attr`,
`schema-attr-not-of-class`) when a filter or a schema map names something the vault does not
have — a refresh settles whether the vault moved on, or the tree is wrong.

## Schema you define — and push

```bash
idm schema.add-attribute tree/ --name "PB Cost Center" --ldap pbCostCenter --oid 1.3.6.1.4.1.NNNN.1.1 --single
idm schema.add-class tree/ --name pbContractor --kind auxiliary --oid 1.3.6.1.4.1.NNNN.2.1 --optional "PB Cost Center, Given Name"
idm schema.set tree/ --name pbContractor --optional "PB Cost Center"
idm schema.remove tree/ --name pbContractor        # from the tree only
idm vault.deploy-schema tree/ --env lab            # the plan: what the vault lacks
idm vault.deploy-schema tree/ --env lab --yes      # written to cn=schema (--confirm <env> on production)
```

Definitions you make are marked *custom*; a refresh keeps them (and drops the mark on any the
vault now has). Only custom definitions change or go; the vault's own are read-only here, and
**nothing is ever removed from a vault's schema** — eDirectory does not take schema back safely.
A push needs an OID on each definition (eDirectory requires one over LDAP; use your
organisation's arc), writes attributes first and classes in superclass order, and skips what the
vault already has.

## The application's schema — `drivers/<driver>/app-schema.xml`

```bash
idm vault.app-schema tree/ --env lab --driver "Active Directory Driver"
idm query tree/ schema "Active Directory Driver"
```

`DriverGetSchema` — the engine's own extension, what iManager's and Designer's *Refresh
Application Schema* use: the engine asks the shim for its schema and stores the answer on the
driver object (`DirXML-ApplicationSchema`); the tree then reads that attribute. The driver must
be **stopped** — the engine starts the shim for the question and refuses (`Other`) while the
driver runs. The shim must be able to connect (a Remote Loader must be up). When the engine
refuses, the last schema it stored is taken, and the result says so. A shim with nothing to say
(the Loopback driver) gives an empty `<schema-def/>`.

The file is never deployed; it is a picker's and a reviewer's reference: the application side of
a schema map, a filter's names, what the shim actually offers.
