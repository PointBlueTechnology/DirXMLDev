# Validation — the offline gate

`bin/idm validate <asCodeDir> [--json]` runs every check over an IDM-as-code tree
and exits 1 on any error. It is the first of the safeguards in
[plan.md](plan.md): nothing is deployed to a vault that does not validate clean,
and an agent editing policies runs it after every change (Phase 3 wires it into
each edit operation).

The point of the design: **the checks are the engine's own checks wherever the
engine has one.** A policy is compiled by the same `DirXMLScriptProcessor` /
XSLT parser / schema-mapping processor the engine runs at driver start, in a
context built the way the engine builds it — the driver's GCVs substituted into
the policy text, its mapping tables and includable policies resolvable. Phase 0
spike 1b showed that a policy which fails to compile aborts the whole driver, so
"compiles here" is the property that matters, and the diagnostic quoted is the
engine's.

Where the engine has no check until run time (an undefined GCV read through a
`token-global-variable`, a Map token naming a column the table lacks), the
validator reports what would fail on the first event that hits the rule.

## Calibration

Run on real driver sets before any check counts as done:

| Source | Result |
|---|---|
| RFI driver-set export (19 drivers) | 0 errors |
| IG4 live vault (19 drivers) | 0 errors |
| Amica PRD Designer project (50 drivers) | errors only where the project itself is inconsistent: dangling relations Designer left as `type="Ref"` placeholders, drivers whose GCV objects are absent from the project |
| Partial LDIF dump (test11) | errors only for the objects the dump lacks |

A running production vault must validate with zero errors. If it doesn't, the
check is wrong, not the vault — fix the check. (That rule caught three false
positives on the first pass: DirXML Script policies in the schema-mapping set
are legal; `GCDefinitions.merge` keeps the *first* definition, not the last;
the engine tolerates ragged mapping-table rows; and stock schema maps map one
NDS name to several application names.)

## Findings

Each finding has a severity, a stable code, the artifact path it concerns, a
message, and optional detail. **ERROR** = the engine will refuse to load it, or
the rule will fail when it runs. **WARNING** = loads and runs, but almost
certainly a mistake or something deploy must handle. **INFO** = worth knowing
(a named password the deploy must set, a dynamic reference that can't be
checked statically).

Paths are artifact paths (`library/X`, `drivers/D/X`, `drivers/D/subscriber/X`),
`drivers/D` for a driver-level finding, `driverset` for the set, or a file path
for a well-formedness error.

`--json`:

```json
{"ok":false,"counts":{"error":1,"warning":0,"info":2},
 "findings":[{"severity":"error","code":"compile-error",
              "path":"drivers/AD/subscriber/sub-ctp-Transform",
              "message":"Code(-9124) Error in  : Element 'do-frobnicate' not allowed in 'actions'."}, …]}
```

Errors are listed first, then warnings, then infos; within a severity, in check
order. Text output is one line per finding in the same order, detail lines
indented beneath, then a summary line (`OK: 0 error(s), …` / `FAIL: …`).

## Checks and codes

### Well-formedness (`Validator.validate(Path)`)

Every `.xml` under the tree is parsed before anything else. A malformed file is
`xml-not-well-formed` (E) with the parser's position, and the model checks are
skipped — they would only report consequences. `ascode-unreadable` (E) if the
manifests don't load.

### `links` — LinkCheck

The `DirXML-Policies` chains.

| code | sev | meaning |
|---|---|---|
| `link-unresolved` | E | a policy-set link names an artifact that isn't in the tree — a Library policy left out of a single-driver export, or a dangling reference in the source |
| `link-kind` | E | wrong kind of artifact for the set: set 3 must link ECMAScript resources, set 14 GCV-definition resources, every other set a policy. Set 0 (schema mapping) takes the `<attr-name-map>` and any DirXML Script / XSLT policies that run around it |
| `link-duplicate-order` | W | two links in one set at the same order — execution order is undefined |
| `link-cross-driver` | W | a driver links another driver's artifact |
| `link-channel-mismatch` | W | a Subscriber-container policy linked into a Publisher set or vice versa |
| `policy-unlinked` | I | a driver's own policy no set links (Library policies are shared by design and not reported) |
| `driver-no-shim` | E | no shim class |
| `driver-no-config` / `driver-no-filter` | W | no shim-config-info / no filter |

### `compile` — CompileCheck

Every policy (Library and driver) through the engine's compilers.

The context per driver: GCVs in the engine's precedence (the driver's
config-values, then GCV-definition resources linked in its set 14, then the
driver set's config-values, then the driver set's own GCV objects — plus the
engine's `dirxml.auto.driverdn/driverguid/treename`); the driver's mapping
tables (its own and the Library's) and includable policies registered with the
simulator's `vnd.nds.stream` store. The engine substitutes `~name~` **into the
policy text — any text or attribute, XPath expressions included — before
compiling**, via `GCDefinitions.apply`; an undefined `~name~` is fatal at driver
start ("Referenced value not found"), so it's fatal here.

A Library policy is loaded by each driver that links it, in that driver's
context, so it's compiled once per linking driver; a finding is attributed to
the Library policy with "(as loaded by driver 'X')" and repeated verbatim
findings are reported once. Unlinked Library policies compile against the
union of every driver's GCVs.

| code | sev | meaning |
|---|---|---|
| `compile-error` | E | the engine's compiler rejected it; message = the engine's diagnostic (`Code(-9124) Element 'x' not allowed in 'actions'`, `-9130 invalid XPATH`, `-9129 invalid regular expression`, `-9128 invalid value for attribute`, `-9025 missing <app-name>`, `-9014 XSLT parse error` with the parser's detail, `-9192 couldn't access map definition`, `-9177 unable to load included policy`, `GCV 'x' is referenced as ~x~ but is not defined`) |
| `policy-empty` / `policy-unknown-root` / `policy-unparseable` | E | no content / root isn't `<policy>`, XSLT or `<attr-name-map>` / the engine's parser rejects it |
| `java-class-missing` | W | references a Java extension class (`java:` / shim util) not on this classpath — the vault's may differ, so not an error |
| `named-password` | I | reads named password(s) — deploy must set them |

### `gcv` — GcvCheck

`token-global-variable` / `token-global-config-value` references — resolved at
run time, so an undefined one fails the rule on its first event.

| code | sev | meaning |
|---|---|---|
| `gcv-undefined` | E | a driver policy reads a GCV nothing in the driver's scope defines (names built from a local variable, `drv.x.$name$`, are skipped) |
| `gcv-undefined-library` | W | a Library policy reads a GCV no driver in the set defines (it may be linked only by drivers that do) |

### `mapping-tables` — MappingTableCheck

| code | sev | meaning |
|---|---|---|
| `mapping-table-missing` | E | a Map token names a table not in the driver's reach (its own resources, the Library's) |
| `mapping-table-column` | E | the token's `src`/`dest` isn't a `<col-def>` of the table |
| `mapping-table-malformed` | E | no `<col-def>`, or no content |
| `mapping-table-ragged-row` | W | rows whose `<col>` count differs from the column count — the engine loads them (missing cells read empty) but it's almost always a mistake |
| `resource-content-mismatch` | W | content type says mapping table but the root element is something else |
| `mapping-table-dynamic` | I | `table="$var$"` — resolved at run time, not checked |

### `ecmascript` — EcmaScriptCheck

ECMAScript resources and the `es:` calls that use them.

| code | sev | meaning |
|---|---|---|
| `ecmascript-syntax` | E | the resource does not parse (Rhino, the engine's own ECMAScript runtime — shaded as `com.novell.soa.script.mozilla.javascript` in `lib/js.jar`); message = Rhino's error with line/column |
| `ecmascript-function-undefined` | E | a policy calls `es:name(…)` (in a `token-xpath`, `if-xpath`, or an XSLT expression) and no ECMAScript resource linked in the driver's set 3 defines `name` |
| `ecmascript-unlinked` | W | an ECMAScript resource no driver's set 3 links (driver-scope resources only; Library ones are shared) |
| `ecmascript-empty` | W | an ECMAScript resource with no content |

Function definitions: top-level `function name(…)` and `var name = function`,
found from the Rhino AST (fall back to a regex if the parse fails, so a syntax
error doesn't also produce spurious undefined-function errors).

### `filter` — FilterCheck

The driver filter and the schema map.

| code | sev | meaning |
|---|---|---|
| `filter-malformed` | E | root isn't `<filter>`, or a `<filter-class>` without `class-name`, or a `<filter-attr>` without `attr-name` |
| `filter-invalid-value` | E | `publisher`/`subscriber` not one of `sync ignore notify reset`; `merge-authority` not one of `default edir app none`; `publisher-create-homedir`, `publisher-track-template-member`, `publisher-optimize-modify`, `subscriber-optimize-modify` not `true`/`false` |
| `filter-duplicate-class` / `filter-duplicate-attr` | E | the same class twice, or the same attribute twice in a class |
| `filter-dead-attr` | I | an attribute set to sync/notify in a class that is `ignore` on both channels — never reaches a policy |
| `schema-map-malformed` | E | a `<class-name>` / `<attr-name>` without both `<nds-name>` and `<app-name>` |
| `schema-map-duplicate` | I | the same `nds-name` mapped twice at the same level, or the same `app-name` mapped to two nds names at the same level. Legal and common in stock packages (the AD map sends `CN` to both `cn` and `sAMAccountName`) — the engine uses the first mapping in each direction — so it's informational, there to make an *unintended* duplicate visible |
| `schema-map-unfiltered-class` | I | a class in the schema map that isn't in the filter (harmless; usually a leftover) |

### `package-linkage` — PackageLinkageCheck

A packaged artifact that a policy set links should carry the package's own record of that
link, `dirxml-pkglinkages` (the vault's `DirXML-pkgLinkages`). Nothing at run time reads
it, but every later `package.install` / `package.upgrade` into that set does: Designer's
weight rule places a new package policy before the first existing link whose recorded
weight is greater, and a link with no record counts as hand-made (weight −1) and is never
displaced — so new package policies land after it regardless of weight
([packages.md](packages.md), "Link by weight"). A tree from the vault, one the package
installer built, and one read from a Designer project (`Idm:InstalledLinkages`, since
2026-09-18) carry the records; a tree from an export never does, nor does a project tree
imported before that date — re-import it. Designer records the link for policies and
ECMAScript resources but, on the vaults and projects sampled, for only some GCV objects,
so those are information.

| code | sev | meaning |
|---|---|---|
| `package-linkage-missing` | W | a packaged artifact is linked (any set but 14) and carries no `dirxml-pkglinkages` record |
| `package-linkage-missing-gcv` | I | a packaged GCV object is linked in set 14 (`gcv`) and carries no record |

### `check-failed` (E)

A check threw — a bug in the validator, never in the driver set. The message
names the check; the detail is the top of the stack.

## Extending

A check is a class implementing `Check` (`name()`, `run(DriverSet, Report)`);
`Model` holds the shared queries (a driver's defined GCVs, its reachable mapping
tables, its DN in slash form, …). Add it to `Validator.standard()`. Rules for a
check:

- **A problem in the driver set is a finding, never an exception.** Exceptions
  are validator bugs and surface as `check-failed`.
- **Codes are stable.** Agents and CI gate on them; renaming one is a breaking
  change to this document.
- **Calibrate on a real, running driver set before adding an ERROR.** If it
  fires on a vault that starts and runs, the check is wrong.
- Tests: a synthetic driver set built in code (see `ValidatorTest`) — one good
  case that must produce no finding, one bad case per code.

## AppConfig objects (`AppConfigCheck`, docs/appconfig.md)

| Code | Severity | Meaning |
|---|---|---|
| `appconfig-xml-invalid` | E | an XML-valued attribute (`XmlData`, `nrfResourceParms`, `nrfEntitlementConfigDefault`) does not parse |
| `appconfig-entity-key-duplicate` | E | two `<attribute key>` of one entity definition share a key |
| `appconfig-role-level-mismatch` | E | a role's `nrfRoleLevel` disagrees with the `Level<n>` container it sits in |
| `appconfig-ref-missing` | E | a DN-valued attribute (`nrfRequestDef`, the role configuration's `nrf*RequestDef`/`nrf*Container`) names an object the tree does not have |
| `appconfig-ref-outside` | I | such a DN points outside this driver's AppConfig (the driver itself, for `nrfUADContainer`); not checked |
| `appconfig-localized-unparsable` | W | a `*LocalizedNames`/`*LocalizedDescrs` value is not `lang~text|…` |
