# Spike 7b: our package install vs Designer's (2026-09-10)

**Question.** Does `package.install` (docs/packages.md §3.2) produce what
Designer produces — the same objects, stamps, installed checksums and set
order — for a real driver?

**Setup.** `test11`'s eDirectory driver (`eDir2eDirJFWOld`, also on the test
vault) was installed by Designer from `NOVLEDIRBASE 2.1.2.20190219130306` +
`NOVLEDIRDCFG 2.1.0.20120831225140` + `NOVLPWDSYNC 2.1.2.20190806140123` +
`NOVLEDIRPSYN 1.0.0`. We built a driver from the same four jars in one
transaction (`driver.add --name eDirTest --packages base,…`; prompt answers =
the values Designer's driver carries) and compared by association id.
`PackageInstallTest.installsTheEdirectoryDriverLikeDesigner` is the spike as a
test.

**Result.**

| check | result |
|---|---|
| packaged objects Designer installed (18: 13 policies, 3 GCV objects, 2 filter/prompt-derived) | all 18 present, same scope and name |
| installed checksum (`DirXML-pkgChecksum` / `Idm:ContentChecksum`) | 18 / 18 identical |
| policy-set order: input, output, publisher command/matching/placement, subscriber command | identical (Designer's two hand-made policies excluded) |
| GCV set order | ours = the **vault's** (`DirXML-Policies … #14`: DCFG 120, PSYN 140, PWDSYNC 500 by weight); Designer's project relation order differs because `Idm:GlobalConfigs` is creation order, not linkage order |
| driver attributes | shim class, auth server from the prompt; `shim-auth-id` empty (no prompt in the base package — Designer's driver had it typed in by hand) |
| customized marks | none (a fresh install is never "customized") |

**What it took, beyond the research note.**

1. **Prompts are XSLT run by the engine's own processor** (`com.novell.xsl.Stylesheet`
   from `nxsl.jar`, with `setParameter(name, Document)` for `$defsDoc` /
   `$curDoc` / `$npDoc` / `$directiveDoc`). The JDK's XSLTC refuses DOM
   documents as parameters ("Invalid conversion … to 'node-set'"). A prompt
   whose `<package-item pkg-assoc-id=""/>` is empty targets the **package
   directive itself** (that is how the base package's `shim-auth-*`,
   `global-config-values` and named passwords get filled in).
2. **A package set installs in one transaction.** Policies of the common
   password package reference GCVs that its driver-specific companion defines;
   installing them one transaction at a time trips the validator. Designer
   installs the resolved set and validates afterwards; `package.install
   --jar a,b,c` / `driver.add --packages` do the same.
3. **Package-level linkage.** `NOVLPWDSYNC`'s policies carry no linkage of
   their own; `NOVLEDIRPSYN`'s package directive links them by
   `pkg-assoc-id` (`<policy-linkage><policy-set name channel order
   package-id pkg-assoc-id value/>`). Applied after item linkage, then the
   linked objects' installed checksums are recomputed (they fold in the set
   names).
4. **Fresh installs must not be marked customized**: the transaction's
   `touch` keeps a baseline *and* sets the mark; installs use `installed()`
   (baseline = initial state, no mark).
5. **DOM Level 2**: the engine's DOM returns null from `getTextContent`;
   every text read goes through a child-walking helper.
6. Driver attributes the model does not carry are reported, not applied:
   `configuration-manifest`, `reciprocal-links`, `driver-image`,
   `log-events`, `driver-cache-limit`. Jobs, entitlements, ID policies and
   templates in a package are reported, not installed (not in the model).

**Consequence.** Step 3 of the build order is done for driver packages; the
vault side (step 4) can write the stamps we now compute, and Designer's
verdict (7c) is the remaining acceptance test.
