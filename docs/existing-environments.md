# Taking on existing environments

*Status: decided 2026-10-20; being built on the `driver-environments` branches (core and web).*

The usual case is not a clean start. A customer has a production tree and one or more others (QA,
test, dev), each changed by hand over the years: drivers added in one and not the other, the same
driver under two names, hot fixes made in production and never carried back, work in QA never
promoted, host names typed into policies. The tool has to take that on without asking anyone to
clean it up first, and it has to help decide what genuinely needs resolving.

## What a real pair of environments looks like

A customer's QA and production driver sets, read from two LDIF exports (2026-10-20):

| | Production | QA |
|---|---|---|
| objects in the driver set | 1,143 | 1,542 |
| drivers | 41 | 43 |
| servers | 4 | 2 |
| driver set DN | `…,ou=SERVICES,o=…` | `…,ou=services,o=…` |

Compared driver by driver (QA against production, production as the tree):

- **Drivers in one only:** 7 in production only, 9 in QA only. Among them one is plainly the same
  driver renamed (20 of 20 policies in common, names one character apart); one production driver has
  four QA counterparts (one QA certificate-revocation driver per CA where production has one); and two
  drivers look alike because each pushes to the environment below (production→QA, QA→dev), which only
  a person can call the same driver or not.
- **Values:** 81 scalar values differ: 69 shim parameters, 5 engine control values, 4 driver GCVs, 3
  driver-set GCVs. These are what overrides are for.
- **Everything else:** 879 differences. 52 differ only in letter case (`OU=services` against
  `OU=SERVICES`; eDirectory reads them as the same) and are set aside. The other 827:

| | count | what it suggests |
|---|---:|---|
| only in QA | 402 | QA-only objects; 350 are role-based entitlement policies |
| only in production | 57 | production has them, QA never got them |
| lines only production has | 37 | a change made in production, not carried back |
| lines only QA has | 26 | work in QA not yet promoted |
| package versions differ | 13 | the newer side is known |
| cosmetic (descriptions, comments, display names) | 6 | align freely |
| environment text inside content | 7 | a host or server name typed into a policy: belongs in a GCV |
| both sides changed | 268 | needs a person; mostly User Application objects and PRDs |

Two more things the data shows. Jobs differ by schedule (production runs them on a schedule, QA
doesn't), which an override can't carry yet. And jobs, the driver set's server list and per-server
settings name servers by DN. Tree names and server names are globally unique, so two environments
never share a server: every environment but the base needs its servers mapped, here four production
servers onto two in QA.

## The process

One environment at a time, the production tree first.

1. **Base.** Import production into `main`. `main` is what production runs; everything any other
   environment has beyond it shows up as a change to look at, not as something already on its way to
   production. *(built)*
2. **Connect the next environment** under Connections, and add it to the project.
3. **Set up that environment**: a guided read (*Vault → Environments → Set up QA…*, proposed) that
   proposes, for review, everything it needs. Nothing is written until you apply; you commit the
   result.
   1. **Match drivers.** Same name: matched. Otherwise a likely match by shim class, name and policies
      in common, which you confirm (a `drivers/<d>.name` override) or reject. Proposals are one-to-one;
      one production driver is never matched to two. *(matching proposed; the name override is built)*
   2. **Membership.** A driver only that environment has is marked as its own
      (`environments="QA"` in `driver.xml`); a tree driver that environment lacks is marked as not
      deployed there. *(built: the marks, compare, deploy and update follow them)*
   3. **Values.** Each differing value becomes that environment's override, ticked by default (the
      tree holds production's value). *(built: recorded one at a time from the Environments tab; bulk
      proposed)*
   4. **Servers.** Map the tree's servers to that environment's (several to one allowed), so jobs and
      per-server settings line up. Required for every environment but the base, since no two
      environments share a server. *(proposed: see "Servers")*
   5. **What's left: analyse, then decide** (next section).
4. **Compare again.** What remains is what you decided to leave, and from then on only new
   differences show.

## Analysing what's left

Each remaining difference gets a likely cause from the comparison itself, as in the table above, and
the page groups them by driver with counts, cause first, so that 827 differences read as a short list
of drivers with a few kinds of problem each. For each you choose:

- **Production is right:** leave the tree as it is; a deploy to QA (when you choose to) brings QA in
  line. Suggested for *only in production*, *lines only production has* and an older package in QA.
- **QA is ahead:** take QA's version into a branch (*Update this branch from QA…*, ticking those
  files), to promote through the normal flow. Suggested for *lines only QA has* and a newer package in
  QA.
- **Needs fixing:** both sides wrong or a design problem, such as a host name typed into a policy.
  Opens the file; the suggestion names the fix (move the literal into a GCV, then override it).
- **Different on purpose:** this environment's content genuinely differs and no value can carry it.
  Recorded as known (**D1**).
- **Decide later:** recorded as known, with who and when, so it stops hiding new differences but
  stays listed.

What's known about a driver helps decide. When the repository holds notes on a driver (a summary,
known defects; for example `docs/drivers/<driver>.md` from an earlier analysis), the setup shows them
beside that driver's differences. In the customer data above, production carries an extra rule that
vetoes every user event on one driver's subscriber channel, and QA doesn't. Whether that is a stopgap
for a defect the earlier analysis found is the kind of question only such context can help answer.

Resolving is then ordinary work on branches: each decision either leaves the tree, changes it on a
branch, or records the difference as known.

## Drivers only some environments get: for now, or for good

An item that only some environments get may be waiting (built in QA, not yet promoted to production) or
permanent (a test loopback, a feed that only exists in QA). The tree says which, beside the list:

```xml
<driver name="Slack Driver"  environments="QA" planned="PROD">   <!-- waiting for promotion to PROD -->
<driver name="Test Loopback" environments="QA">                  <!-- QA's own, for good -->
```

The same two attributes go on jobs, role-based entitlement policies and notification templates. A
compare against production lists the planned ones as waiting for promotion and leaves the permanent ones
quiet; promoting moves the environment from `planned` to `environments`, one reviewed change; a plan for
production names what is planned for it and not promoted yet.

## Feeds between environments

Keeping a lower environment's data current from production is common: an eDirectory-to-eDirectory link
whose sending end is a driver in production (for example "VaultProdToVaultQA") and whose receiving end is
a driver in QA ("VaultQAToVaultProd"); QA may feed dev the same way ("VaultQAToVaultDev"). Each end is its
own driver. Two ways to keep them:

- **Each end its environment's, for good** (`environments="PROD"`, `environments="QA"`): the simple
  default, and right when the ends differ in more than where they point.
- **One tree driver per role, named per environment**: "send to the environment below" is the same
  configuration in production (VaultProdToVaultQA) and QA (VaultQAToVaultDev), aimed at a different tree.
  One tree driver with `drivers/<d>.name` per environment and the remote tree's address as each
  environment's override keeps a fix to one end in step with the other.

Names that carry environment names, and a remote address that is another environment of the project, are
how the setup recognises a feed; it proposes, and a person decides. Keep a feed's remote address an
override in every environment that gets the driver, so a deploy never points one environment's feed at
another's target. (A validation check for this is a possible follow-up; the setup flags feeds today.)

## Known differences (D1)

A file per environment beside the overrides, `overrides/<env>.known.json`, listing differences
accepted as they are: the object's path, a fingerprint of both sides, the decision, who and when. A
compare shows them collapsed under "known" and shows anything whose fingerprint changed as new. A
deploy to that environment still writes what the tree holds; the plan says which of its changes
overwrite a known difference, so nobody flattens one by accident.

## Servers

Server names are globally unique, so the server DNs in the tree (the driver set's server list, each
driver's `servers/<server>/` settings, jobs' servers) are the base environment's. Every other
environment maps them in its overrides file, one line per tree server. The key names the tree's server by
its name (the `servers/<name>/` folder's), since the file splits a line at its first `=`; the value is the
environment's server's DN:

```
# overrides/QA.properties
servers.vault01 = cn=vault01qa,ou=servers,o=acme
servers.vault02 = cn=vault01qa,ou=servers,o=acme
servers.vault03 = cn=vault02qa,ou=servers,o=acme
servers.vault04 = cn=vault02qa,ou=servers,o=acme
```

The tree's servers act as roles. Compare, plan and deploy against QA read and write each role on the
server it maps to; an import from QA maps the other way. Two roles mapped to one server must agree
on that server's settings, or the setup flags the conflict. A server of QA that no role maps to is
reported, as is a role left unmapped (validation: `server-unmapped`). The guided setup proposes the
map by name and by which drivers run where, for you to confirm.

## Decisions (taken 2026-10-20)

- **D1 — Known differences: recorded** per environment in `overrides/<env>.known.json`, as above.
- **D2 — Driver-set objects get an environments list like drivers:** role-based entitlement
  policies, notification templates and driver-set jobs. Compare and deploy leave them out of other
  environments; an update from an environment marks the ones new to the tree as its own.
- **D3 — Servers: required, mapped in each environment's overrides file** (`servers.<tree server's name> =
  <env server's DN>`). Fewer servers outside production is the normal case, so several tree servers mapping
  to one is the rule. A driver whose settings differ between two tree servers that map to the same
  server is flagged, and the person picks which that server takes
  (`servers.<env server's name>.prefer = <tree server's name>`).
- **D4 — Job schedules, enabled state and trace are override keys:** `drivers/<d>.job.<job>.schedule`
  (empty = no schedule), `.disabled`, `.trace-level`, `.trace-file`, `.trace-size-limit`, and
  `driverset.job.<job>.…` for the set's jobs. The comparison lists them as values. In the customer data,
  two production jobs run on a schedule QA's don't, and job trace levels and files differ.
- **D6 — A new driver's server, chosen at deploy:** promoting a driver to an environment with several
  servers asks which one runs it (default: the one the deploy connects through). That server gets the
  driver's start option, manual, and every other server of the set gets disabled
  (`vault.deploy --driver-server "D=<server>"`; *Runs on* in the plan). A deploy choice, not a tree value:
  it matters only when the driver is created there. The step waits for the driver to replicate to that
  server first.
- **D5 — A guided *Set up <environment>…* flow** proposing 3.1–3.5 together, reviewed before anything
  is written, then committed.
