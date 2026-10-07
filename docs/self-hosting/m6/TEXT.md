<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6.1: Bridge generator text, patterns and file inventories (D293)

Scope: B7's last Java text conversions and patterns used by the Bridge
source generators, packaging and loader validation, and B3's traversal
coverage for the Bridge consumers that read file inventories. They prepare
S7's ports of BridgeJavaSources, BridgeBootstrapSources,
BridgeRootStateSources, BridgePackageManifest, BridgeCallbackGuardSources,
BridgeLoaderSources, BridgeDistributionCommand, BridgeValuesLibrary,
BridgeDistributionInputs, BridgeProducerInputs, BridgePackageInputs and
BridgeAssembler; they are not those ports.

| Port source | Replaces |
| --- | --- |
| [BridgeText.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/BridgeText.iron) | `Float.toHexString`, `Double.toHexString`, `String.format("\\%03o", ...)` with and without `Locale.ROOT`, `String.format("\\u%04x", ...)`, `String.stripTrailing` |
| [BridgePatterns.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/BridgePatterns.iron) | `matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")`, `matches("[A-Za-z0-9_][A-Za-z0-9_.+-]*")` (two callers), `matches("[A-Za-z_$][A-Za-z0-9_$]*")`, `matches("[0-9]+(?:\\.[0-9]+){0,2}")`, `matches("[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)*")`, `matches("\\$ironwood\\$ensure\\$*")`; `[0-9a-f]{64}` is `BridgeIdentity.isHash` (D292) |
| [SourceFiles.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/SourceFiles.iron) | `Files.walk(runtime)` filtered to regular `.c` and `.h` files, sorted, with `relativize(...).toString().replace(separatorChar, '/')` |

## Traversal coverage for the Bridge consumers

| Java traversal | Consumer | Port treatment |
| --- | --- | --- |
| `Files.walk(runtime)`, `.c`/`.h`, sorted | BridgeDistributionInputs.read, BridgeProducerInputs.read | `SourceFiles` |
| `Files.list(directory)`, regular files ending with `.iron` or `.ironclass`, sorted | BridgePackageInputs.files | `FileCollector(directory, extension, 1)` (D276, D277) |
| `Files.list(stage)`, regular files | BridgeDistributionCommand.distribute | `FileCollector(stage, "", 1)`; the inventory is a TextMap, so listing order does not matter |
| `Files.walk(classes)`, regular files, relativized | BridgeAssembler.assemble | `FileCollector(classes, "")` with `relative` |
| `Files.walk(stage).sorted(reverseOrder())` deletes | BridgeAssembler, BridgeDistributionCommand, BridgeValuesLibrary, BridgeProducer | M4.1's `TreeDeletion` (D270) |
| `Files.walk(compiler/ironwood/compiler)` and the compiler jar | BridgeProducerInputs.read's compiler inventory | Not ported: it is the Java compiler's producer identity, S7's separate native producer-manifest design (tracked in M6.2) |

A no-follow walk keeps a link whose target is a regular file, under the
link's spelling, and lists but does not enter a link to a directory, as
Java's `Files.walk` with `Files.isRegularFile` does; directories named like
sources (`x.c`, `dir.iron`) are entered, not kept.

## Semantics checked against Java

[BridgeTextReference.java](text-evidence/BridgeTextReference.java) runs JDK
21's conversions and patterns and the baseline's own `BridgeJavaSources.quote`
and `BridgeBootstrapSources.cString` by reflection. The corpus has 4,523
float and 4,518 double bit patterns (zeros of both signs, the smallest and
largest subnormals, normal boundaries, extremes, infinities, quiet and
signaling NaN payloads, and seeded patterns with 500 subnormals each), 810
integers (0 to 599, the octal and hex width boundaries, negative and extreme
values, 200 seeded) and 3,353 strings (control characters, DEL, quotes,
backslashes, Java's white-space set and its exclusions U+00A0, U+2007 and
U+0085, non-ASCII and supplementary characters, unpaired surrogates, Maven
coordinates, versions, dependency paths and ensure-method forms, 3,300
seeded). Facts it pins include:

- `Float.MIN_VALUE` is `0x0.000002p-126`, not the double spelling
  `0x1.0p-149`; `-0.0f` is `-0x0.0p0`; every NaN is `NaN` and gets
  BridgeJavaSources' `(0.0f / 0.0f)` spelling.
- `%03o` and `%04x` of a negative int print its unsigned 32-bit value
  (`\37777777777`, `￿ffff`) and never truncate wider values.
- `stripTrailing` removes U+001C to U+001F and U+2028 but keeps U+00A0,
  U+2007 and U+0085.
- `[0-9]` is ASCII: a fullwidth digit fails the version pattern.

The walk test builds a tree with links to a file, a directory and nothing,
directories named `x.c` and `dir.iron`, a nested header, an uppercase `.C`, a
`.cc`, a file named `.c`, and Unicode names, and compares the runtime
inventory (names, digests and content identity), both package listings, the
stage listing and the class walk with Java's own expressions.

## Verification

| Test | Result |
| --- | --- |
| `M6.1 Bridge text conversions and patterns match JDK 21 and the generators` | the 13,204-line transcript of [compiler_bridge_text.iron](../../../integration-tests/cases/compiler_bridge_text.iron) equals the reference's from class and archive links, and every result is freed |
| `M6.1 Bridge inventories walk and list as Java does` | [compiler_bridge_walks.iron](../../../integration-tests/cases/compiler_bridge_walks.iron) prints Java's selections, spellings, digests and identity |
| `M6.1 Bridge helpers borrow inputs and own their results` | stripped text, hex strings, escapes and source inventories are freed by their owners after their inputs; use after free of a stripped result is rejected in every unfreed mode |
| `M6.1 Bridge helpers unwind every allocation failure` | [compiler_bridge_helpers_failure.iron](../../../integration-tests/cases/compiler_bridge_helpers_failure.iron) unwinds each of its 398 limits and [compiler_bridge_walks_failure.iron](../../../integration-tests/cases/compiler_bridge_walks_failure.iron) each of its 194 |

One compilation of all 196 port sources with the five pilot adapters under
`--unfreed=warn` reports no diagnostics.

## Conventions and boundaries

- `BridgeGeneration.constant` hashes `Float.floatToRawIntBits` of a float
  constant. The native constant representation keeps the raw bits as an `int`
  payload, as D268 kept double payloads, so no public
  `Float.floatToRawIntBits` is added; adding one would be a separate public
  API decision. `BridgeText.floatHex` needs no raw bits, because NaN prints
  as `NaN`.
- `Character.toUpperCase` in BridgeArrayInputSources only capitalizes a
  primitive type name for JNI's `Get<Kind>ArrayRegion`, which is ASCII
  casing; Ironwood's `Character.toUpperCase` is used.
- A trailing white-space unit is tested as a char; Java tests code points,
  and no supplementary character is white space, so the results agree.
