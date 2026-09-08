# Spike: reverse-engineering `Idm:ContentChecksum`

**Result: negative.** No standard hash/checksum algorithm over any tested
serialization of `_contents.xml` reproduces `Idm:ContentChecksum`. Stronger
still, the value is **provably not a pure function of the content bytes at
all** — see §2. Recommendation (§5): don't compute it; copy/compare integers.

## 1. Samples and method

Collected all `(ID, Idm:ContentChecksum, _contents.xml bytes, baseline
idm-contentchecksum)` tuples across `~/designer_workspace/test11` (2795
total CObjects with a checksum found: ScriptPolicy 989, NotfTemplate 1433,
IDMResource 135, Entitlement 54, StylesheetPolicy 84, MappingPolicy 50,
MappingTableResource 42, ECMAScriptResource 8) and the unzipped
`AMICA-PRD-20260627`. 956 objects had a captured `_initial_state.xml`
baseline (755 checksum-matches-baseline "unmodified", 201 mismatches
"modified").

Tested, over 150–190 samples per run, ~227 (transform × hash) combinations:
- Content forms: raw bytes; UTF-8/UTF-16(LE/BE) text; XML-decl stripped;
  whitespace collapsed; inter-element whitespace stripped (`>\s+<` → `><`);
  `xml.dom.minidom` canonical serialization; ID-salted (`id+content`,
  `content+id`, with/without separator).
- Hash functions: `zlib.crc32`, `zlib.adler32`, MD5/SHA1/SHA256 truncated
  to 32 bits (big/little-endian), 7 other CRC-32 variants (BZIP2, C,
  MPEG-2, POSIX/cksum, JAMCRC, CRC-32Q, XFER) implemented from first
  principles, FNV-1/FNV-1a-32, djb2, sdbm, ELF hash, Jenkins
  one-at-a-time, Murmur3-32, Java `Arrays.hashCode(byte[])`, Java
  `String.hashCode()` (unsigned-masked, since checksum never negative).

**Every one of these combinations scored 0/N matches.** Not "low match
rate" — literally zero hits across hundreds of samples for each of ~227
candidates.

## 2. Proof the checksum is not content-derived

First confirmed the earlier spike's whitespace-invariance claim exactly:
for `4PRD2MRS` (test11), stripping the XML decl and inter-element
whitespace from `_contents.xml` produces a string byte-identical to the
same treatment of its `_initial_state.xml` `XmlData` `<ds-value>` — so
*some* normalized form is checksum-invariant, as expected.

Then found the falsifying case. `4PRD2MRS_contents.xml` and
`CTXWEP9Z_contents.xml` (different objects, one an installed
driver-instance, one the package-library master) are **byte-for-byte
identical** (MD5 `627876f4...`, 6861 bytes) yet:
- `4PRD2MRS` → `Idm:ContentChecksum = 2510045168`
- `CTXWEP9Z` → `Idm:ContentChecksum = 3859131201`

A second, cleaner pair confirms it within the *same* installed-object
population (both have real `_initial_state.xml` baselines, same package):
`4RL9A2XE_contents.xml` and `G56Q0ZAB_contents.xml` are byte-identical
(890 bytes) with checksums `81761591` vs `711100711` — and both share the
*same* baseline value (`81761591`), so one reads as "unmodified" and the
other as "modified" despite **identical live content**.

Salting the hash with the object's own ID (`id+content`, `content+id`,
various separators) still produced zero matches, so it isn't a simple
`hash(id, content)` either.

## 3. The modified/unmodified signal is itself unreliable

Directly measuring text agreement (normalized) between each object's
`_contents.xml` and its own baseline's `XmlData`:

| Checksum says | Text actually... | Count |
|---|---|---|
| "modified" (≠ baseline) | really differs | 27 (13%) |
| "modified" (≠ baseline) | **identical** to baseline | 174 (87%) |
| "unmodified" (= baseline) | identical (expected) | 576 (76%) |
| "unmodified" (= baseline) | **really differs** from baseline (some by 1000s of chars, different rule names/logic entirely) | 179 (24%) |

So `Idm:ContentChecksum` vs `idm-contentchecksum` agreement/disagreement
correlates only weakly with an actual textual diff — it is not usable as
a reliable content-diff detector even setting aside the algorithm question.

## 4. Value-shape and package-correlation evidence

- All 2795 checksums are non-negative, range `[5,473,888 .. 4,292,482,414]`
  — inside unsigned 32-bit space, never exceeding `2^32-1`. Zero negatives
  rules out raw signed Java `hashCode()`; shape fits a Java
  `CRC32`/`Adler32`-style unsigned `long`.
- Low-byte values use 247/256 possible bytes — well-distributed, so this
  looks like a genuine hash/CRC output, not a sequence counter.
- 2795 samples → only 925 distinct checksum values (776 distinct
  normalized-content groups); doesn't collapse onto `Idm:PackageGuid`
  either — of 57 multi-member package groups, only 3 share one checksum
  across all members, so it isn't "one checksum per package build."

## 5. Conclusion and recommendation

The evidence points to `Idm:ContentChecksum` / `idm-contentchecksum` being
a value **synced from the live eDirectory `DirXML-Rule` object**, computed
server-side (native NDS/DS-agent code, not Java/Designer), likely over
some internal binary attribute-value representation (sync metadata,
internal encoding, replica/revision state) that is invisible in the
exported flat-file `_contents.xml`/`_initial_state.xml`. Designer/the
project export appears to cache whatever value eDirectory last reported,
rather than compute it from the visible XML text — this is the only
model consistent with byte-identical content producing different
checksums, and with the poor content-diff correlation in §3.

**Recommendation for a writer tool:**
1. Do not attempt to compute a "correct" `Idm:ContentChecksum` — no
   reproducible algorithm exists at the text level, and per §3 even
   genuine Designer/eDirectory-issued values aren't a trustworthy
   content-diff oracle.
2. Default: leave `Idm:ContentChecksum` **unchanged** when writing new
   `_contents.xml`. This is no worse than any alternative and avoids
   fabricating a value that looks authoritative but isn't.
3. If forcing a "modified" indicator in Designer's UI is required, the
   comparison is a plain integer equality against `idm-contentchecksum` in
   `_initial_state.xml` — any different value trips it; copying the
   baseline value back exactly clears it. That mechanics is reliable (it's
   just int comparison) — the *meaning* of the number is what isn't
   recoverable.
4. Validate against a live Designer+eDirectory environment before relying
   on this in production: edit a real driver policy and observe whether
   `idm-contentchecksum` changes with content held constant — that can't
   be tested from static project files alone.
