<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6.1: Bridge identity, inventory and manifest serialization (D292)

Scope: the exact Bridge and TLS inventory serialization and the remaining
properties and JAR manifest helpers that S7's Bridge consumers need:
BridgeGeneration's identities, BridgePackageManifest's pairing and
distribution manifests, BridgePairedArchive's and BridgeAssembler's readers,
BridgeJarArchive's manifest check, BridgeDistributionCommand's and
BridgeValuesLibrary's companion manifests. They prepare S7's ports; they are
not those ports.

| Port source | Replaces |
| --- | --- |
| [TextMap.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/TextMap.iron) | `TreeMap<String, String>` inventories: put, putIfAbsent, get, containsKey and key-order traversal |
| [BridgeIdentity.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/BridgeIdentity.iron) | `BridgeGeneration.digest`, `update`, `bytesDigest`, `contentIdentity` and `requireHash`'s `matches("[0-9a-f]{64}")`; `HexFormat.of().formatHex` stays D267's `hexDigest` |
| [BridgeProperties.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/BridgeProperties.iron) | `BridgePackageManifest.serialize` and `escape` (with `String.format("\\u%04x")`), `Properties.load(InputStream)`, and the pairing readers' `Arrays.equals(bytes, serialize(metadata))` |
| [JarManifest.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/JarManifest.iron) | `new Manifest()`, `getMainAttributes().putValue`, `Manifest.write`, `new Manifest(InputStream)` and `getMainAttributes().getValue` |

## Contracts

- Identity: the domain string, then each key and value in String order, each
  framed as a big-endian 32-bit UTF-16 unit count and big-endian units, so
  `\ud800` and `\udc00` values give different identities where UTF-8 would
  replace both with `?`. `bytesDigest` hashes raw bytes; both are 64 lowercase
  hex digits. `contentIdentity` reports an empty inventory, then the first
  blank name or non-hash value in key order, with Java's texts.
- Pairing manifests: the header line, then `key=value` lines in String order
  with `\u` escapes for units up to space, above `~` and in `\:=#!`; the
  readers accept only bytes equal to the re-serialization of what
  `Properties.load` read, so every input gets Java's verdict: a malformed
  `\u` escape (`IllegalArgumentException`), noncanonical, or the same map.
- JAR manifests: `Manifest.write` breaks a line after 72 bytes and then every
  71 bytes even inside a UTF-8 sequence, and writes no attributes at all
  without a `Manifest-Version` or `Signature-Version`; the reader repeats
  Java's line limit, terminators, continuation joining before decoding, name
  rules, section headers and messages such as
  `invalid header field (line 2)`, `misplaced continuation line (line 1)`,
  `invalid header field name: Bad Name (line 1)`, `line too long (line 1)`,
  `manifest line too long (line 3)` and `invalid manifest format (line 3)`.

## Semantics checked against Java

[InventoryReference.java](inventory-evidence/InventoryReference.java) calls the
baseline's own `BridgeGeneration.digest`, `bytesDigest` and
`contentIdentity` and `BridgePackageManifest.serialize` by reflection, and
JDK 21's `Properties` and `Manifest`. Its corpus:

| Kind | Items | Notes |
| --- | --- | --- |
| Maps | 414 | empty, empty key, UTF-16 versus code point order, both unpaired surrogates, long and multibyte values, blank and whitespace-only names (U+2028 is blank, U+00A0 is not), short, long and uppercase hashes, escapable characters, 400 seeded maps |
| Byte vectors | 66 | empty, 00, 7f, 80, ff, all 256 values, 60 seeded vectors up to 1,000 bytes |
| Properties texts | 2,559 | 41 edge cases (continuations, comments after continuations, trailing backslashes, separators, malformed escapes), non-ASCII bytes, the pinned TLS and Bridge support inventories, a generated support build.properties, every map's canonical form, 1,500 seeded texts and 600 mutations of canonical ones |
| Attribute lists | 211 | version first, later, absent or replaced by Signature-Version; repeated names in other case; invalid names; values that break inside two-, three- and four-byte characters; 200 seeded lists |
| Manifest texts | 1,483 | 66 edge cases from the probes, four invalid or split UTF-8 inputs, the 211 written manifests and 1,200 seeded line mixes |

539 properties texts are canonical, 169 fail with Java's malformed-escape
error, 385 maps fail `contentIdentity`, and 814 manifest texts fail with one
of Java's messages.

## Verification

| Test | Result |
| --- | --- |
| `M6.1 Bridge identities properties and manifests match the Java baseline and JDK 21` | the 13,591-line transcript of [compiler_bridge_inventories.iron](../../../integration-tests/cases/compiler_bridge_inventories.iron) equals the reference's from class and archive links; every result is freed, and each caught failure keeps only its exception, plus its message when the message was built |
| `M6.1 Bridge helpers borrow inputs and own their results` | maps, loaded properties, digests and manifests are freed by their owners after their inputs; use after free of a looked-up value and double free of a manifest are rejected in every unfreed mode |
| `M6.1 Bridge helpers unwind every allocation failure` | [compiler_bridge_helpers_failure.iron](../../../integration-tests/cases/compiler_bridge_helpers_failure.iron) unwinds each of its 388 allocation limits |

One compilation of all 193 port sources with the five pilot adapters under
`--unfreed=warn` reports no diagnostics.

## Differences and boundaries

- Java logs a warning for a repeated manifest attribute; the native reader
  keeps the later value silently.
- BridgeAssembler.verifyLinux visits the Linux support manifest's
  `stringPropertyNames()` in hash order and names the first missing or changed
  file; a native consumer reading through `TextMap` visits String order and
  names the first file in that order.
- TlsDependency keeps M3.3's `PropertiesText` (D268) for the TLS SDK inventory;
  `BridgeProperties.load` reads the same format with Java's verdicts and
  is available to Bridge consumers.
- A replaced map or manifest value keeps its storage until the owner is freed.
