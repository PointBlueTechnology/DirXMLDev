<!-- Copy to .github/PULL_REQUEST_TEMPLATE.md in the client repository. -->

## What changes

<!-- One line per driver or object; link the ticket. -->

## Packages

- [ ] `bin/idm package.status tree/ --catalog catalog/ --strict` passes (CI runs it; paste the last line here)
- [ ] Every new or upgraded package version is in `catalog/` and named below

| Driver | Package | From | To |
|---|---|---|---|
| | | | |

No released package is customised in this change. A behaviour change to a
packaged policy is delivered as a new package version (`package.build`, then
`package.upgrade`), never as an edit of the installed object.

## Evidence

- [ ] `bin/idm validate tree/` reports 0 errors
- [ ] `bin/idm vault.diff tree/ --env stg` output attached, read by a person
- [ ] `bin/idm vault.deploy tree/ --env stg --dry-run` output attached
- [ ] Simulation corpus green, if the tree has one (`bin/idm simulate`)

## Deploy

Target environment: `stg` first; `prd` only after a green `stg` deploy of this commit (`prd.requires=stg`).
