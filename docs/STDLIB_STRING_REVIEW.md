# Everyday String APIs and fixed text conventions

D117 completes the selected everyday String operations. Ironwood targets
high-performance Java-shaped applications compiled ahead of time, without
promising to replace every Java library subsystem.

## Public contract and scope

| Surface | Contract |
| --- | --- |
| `trim()` | Remove leading/trailing UTF-16 units at or below U+0020. |
| `strip()`, `isBlank()` | Java 21 `Character.isWhitespace` semantics, including exclusions for nonbreaking spaces. |
| `toUpperCase()`, `toLowerCase()` | Full Unicode 15.0 conversion with fixed `en_US` behavior. Expansions, supplementary characters and contextual Greek sigma are supported. |
| `equalsIgnoreCase(String)` | Java's locale-independent simple case comparison, rather than full expanding case folding. Null is false. |
| Character queries and mapping | Unicode 15.0 digit, letter, case, whitespace, numeric/radix, simple mapping and surrogate behavior for char/int overloads. |
| `replace(char, char)`, `replace(CharSequence, CharSequence)` | Literal UTF-16 replacement. Empty targets insert at every code-unit boundary; text matches do not overlap. |
| `getBytes()`, `String(byte[])` | Fixed UTF-8 snapshots. Encoding unmatched surrogates uses `?`; decoding malformed bytes uses Java's UTF-8 replacement behavior. |
| `valueOf(char[])`, `valueOf(char[], int, int)` | Fresh snapshots, using existing checked char-array construction. Floating overloads remain D116. |
| Offset searches | `lastIndexOf(int, int)`, `lastIndexOf(String, int)`, and `startsWith(String, int)` follow Java's offset and empty-search rules. |
| `repeat(int)` | Nonnegative repetition; negative counts throw `IllegalArgumentException`; unrepresentable result sizes throw `OutOfMemoryError`. |
| `join` | Explicit String/CharSequence arrays and zero through three individual elements. Null elements render as `null`. No implicit varargs array is introduced. |

There is **no `ironwood.util.Locale`**. Case conversion does not read `LANG`,
`LC_ALL`, or other host settings. Locale arguments and mutable locale defaults
are excluded from the product scope, with no implementation commitment.
Fixed `en_US` is an explicit accepted convention for locale-sensitive library
behavior, not a claim that Java's configurable default locale is supported.
Future APIs must still specify and test their concrete behavior before shipping.
Regex `split`, charset overloads, and Iterable-based `join` remain absent.
The latter requires a separate iterator ownership contract; callers can use
explicit arrays. D047 continues to exclude varargs.

Like the established substring and copy APIs, transformations return fresh
caller-owned results even for unchanged or empty text. Java may reuse an input
String in such cases. Ironwood chooses predictable result ownership under D087
and D088. `equalsIgnoreCase`, whitespace checks, and searches allocate nothing.

## Implementation and provenance

The small String/Character facades, typed IR integration, UTF-8 encoding, and
native result allocation use original or independently implemented Ironwood
code under `MIT OR Apache-2.0`. The existing D115 UTF-8 decoder is reused by
`String(byte[])`. No OpenJDK String implementation body is translated into
these facades.

The Unicode helper takes the derived-code route. The complete headers of
these exact OpenJDK files were inspected and expressly carry the Classpath
Exception:

- [ConditionalSpecialCasing.java](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/java/lang/ConditionalSpecialCasing.java)
- [RuleBasedBreakIterator.java](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/text/RuleBasedBreakIterator.java)
- [BreakIteratorRules.java](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/text/resources/BreakIteratorRules.java), the build-time source of English word tables

`runtime/src/ironwood_case.c` adapts the final-sigma condition and required
forward/backward word-boundary queries to native UTF-16 and bounded stack
state. `runtime/src/ironwood_case_data.h` holds generated full/simple case
mappings, Character classification/digit/numeric tables, English word tables,
and compressed word/cased properties. Both
files retain those headers and use `GPL-2.0-only WITH Classpath-exception-2.0`.
The RuleBasedBreakIterator Taligent/IBM attribution and the additional IBM
notice on BreakIteratorRules are retained in full alongside their Oracle
GPL/Classpath headers. This review treats the generated word tables as derived
data, not default-licensed observations. The prototype's other locale rules,
normalization dependencies, locale objects,
and host-locale parsing are not retained.

The Java 21 API specification is the behavioral reference for the independent
methods: [String](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html)
and [Character](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Character.html).
Tests are original contract tests and Java observations, not copied OpenJDK tests.
Generated Unicode data also carries the upstream Unicode 15.0 notice in
`LICENSES/Unicode-15.0.txt`. The provenance ledger and third-party notices
identify the helper, data and immutable upstream revision.

To reproduce the tables, use JDK 21.0.1+12, matching that revision, from the
repository root. The checked generation used Oracle JDK 21.0.1+12-LTS-29:

```sh
curl -fL https://raw.githubusercontent.com/openjdk/jdk21u/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/text/resources/BreakIteratorRules.java \
  -o workspace/BreakIteratorRules.java
/path/to/jdk-21.0.1/bin/java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/sun.text=ALL-UNNAMED \
  scripts/GenerateCaseData.java runtime/src/ironwood_case.c \
  workspace/BreakIteratorRules.java workspace/ironwood_case_data.h
cmp runtime/src/ironwood_case_data.h workspace/ironwood_case_data.h
```

The generator queries the pinned bootstrap JDK for the documented case and
Character data. Generated applications neither
load a JVM nor require Java reflection. Packaged sources include the generator,
helper, data and all applicable notices. Native linking discards unreachable
helper functions and data. The compiler also prunes dispatch entries whose
slots are never called; otherwise String metadata alone would retain casing.
Slot indices and all reachable virtual/interface overrides are preserved.

## Allocation and failure review

Immutable operations allocate their required result directly, without a
builder or temporary character array. Replacement and String-array joining
measure lengths before allocating and copy complete immutable pieces directly.
The measurement accounts for surrogate pairs formed across piece boundaries.
Case conversion measures and fills through the helper without heap scratch.

CharSequence replacement snapshots each argument once and reclaims proven-fresh
renderings in nested `finally` blocks. String-array join allocates its result
directly. Generic CharSequence-array join uses a builder and releases its
backing storage and fresh element renderings. Fixed-arity joins use existing
concatenation cleanup. A custom `toString()` may return borrowed or retained
text; existing descriptor-based ownership governs whether it is reclaimed.

Call-site borrowing proofs consider every reachable `toString()` override.
A publishing override is not incorrectly treated as borrowing. These compiler
proofs add no runtime misuse tracking. Allocation failures leave inputs intact
and release completed internal temporaries. Ordinary String result limits and
catchable allocation failure are retained.

## Verification and lessons

Focused regressions compare the everyday calls with Java, check 5,832 mixed
word/surrogate contexts, and pin full-code-point casing, comparison, and
whitespace fingerprints to Java 21. Allocation counters check fresh results and
temporary cleanup. Injected failures cover each allocation in conversion,
replacement, joins, repeat, byte export and byte construction. Negative tests
keep Locale, locale overloads, charset overloads and regex split unavailable,
and preserve rejection of reclamation after a publishing callback.

The scope mistake was treating a common method's dependency as a mandate to
build a general subsystem. The first recommendation should have offered fixed
text conventions alongside configurable Locale support and omission. The human
selected fixed `en_US`, preserving useful calls with a deliberate, visible
policy difference. This is distinct from silently substituting a limited
algorithm for an ordinary Java contract.

The implementation review also caught why a partial word-boundary adaptation
was insufficient: forward boundaries alone differed from Java around a
supplementary character before sigma. Retaining the relevant mature algorithm
and comparing boundary cases exposed and corrected that discrepancy. Neither
API-only copying nor line-by-line porting is a universal rule; use the smallest
implementation that fulfills the selected behavioral contract.
