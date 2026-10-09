<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6 pre-change review

Entry: M3 is complete at bdf34a55a1981aa36297a2b6fcff720bc9cf159c, the M4
process checkpoint passed and was qualified on three hosts, and the M5
checkpoint passed at 9b2bbc5f; its checks were repeated on macOS arm64 at
176c0545 after D278-D290 (recorded in eb3c1413). M6 completes preparation for
S7's Bridge consumers: B7's remaining compiler-local contracts (M6.1), the
qualification of the Bridge consumers of B3-B6 (M6.2) and the final
reconciliation of every B0-B7 inventory item (M6.3). It does not port Bridge
production, admission, generators, packaging, assembly or distribution, and
it claims neither S7 nor S8. Every S2+ stage stays out of scope. All work and
commits are local on `before-self-hosting`, without remote operations.

## Scope and first consumers

The M0 source-backed inventory assigns M6 one call gate, `M6.1 before M6.2`
(8,240 calls in 385 patterns), plus the two `HexFormat` calls of
`BridgeGeneration.digest` whose gate reuses the M3.2 digest-text helper at
M6.1. Every first migration consumer is S7. M6.1 classifies every one of
these 387 patterns, as M3 to M5 did, into an existing Ironwood API (A), a
recorded port convention (B) or a delivered helper (D), with phase-scoped
rules so the eight earlier tables regenerate byte for byte. The same gate
also holds 2,408 syntax rows, 368 captured-symbol rows, 164 hash origins and
488 traversals (1,881 contributions); M6.3 reconciles them with every other
gate's rows.

| B owner | Calls | Main consumers |
| --- | --- | --- |
| B7 | 4,827 | the Java and C source generators in `ironwood.compiler.bridge`, BridgeGeneration's identity, BridgePackageManifest |
| B1 | 2,559 | Bridge proofs, admission and generator collections and traversal |
| B2/B7 | 392 | sorted Bridge dictionaries and generator orders |
| B3 | 288 | BridgeJarArchive, BridgeAssembler, BridgeDistributionCommand, BridgeProducer, BridgeValuesLibrary staging |
| B3/B7 | 113 | the same consumers' path text and diagnostics |
| B6 | 57 | BridgeJarArchive, BridgePairedArchive, BridgeValuesLibrary, BridgeProducerInputs (ZIP and JAR manifests) |
| B5, B5/B7 | 6 | BridgeGeneration's SHA-256 and lowercase hex |

| Phase | Delivery | First consumers |
| --- | --- | --- |
| M6.1 | Java 21 qualified-name validation sharing `JavaIdentifiers`' table, with both export callers' diagnostics; exact Bridge identity, inventory and pairing-manifest serialization; JAR manifest writing and reading; the remaining Bridge text and pattern helpers; Bridge file-inventory traversal; the classification | BridgePackageInputs, BridgeExportSurface, BridgeGeneration, BridgePackageManifest, BridgePairedArchive, BridgeJarArchive, BridgeValuesLibrary, BridgeDistributionCommand, BridgeDistributionInputs and the generators (S7) |
| M6.2 | The Bridge JAR writer profile on `ZipWriter` with verified `moveAtomicReplacing` publication; the values companion and distribution dependency manifests; the selected JDK-tool process path; the tracked producer-identity boundary | BridgeJarArchive, BridgeValuesLibrary, BridgeDistributionCommand, BridgeBuildTools, BridgeAssembler (S7) |
| M6.3 | Reconciliation of every B0-B7 inventory item and the final handoff | S5-S8 |

## Contracts to preserve

- B7 and the Java 21 `SourceVersion.isName` contract: each dot-separated
  component (split with limit -1) is an identifier whose first code point is
  a Java identifier start and whose other code points are identifier parts,
  and is not a Java 21 keyword: the reserved keywords including `_`, and the
  literals `true`, `false` and `null`. Contextual keywords such as `record`,
  `var`, `yield`, `sealed`, `permits`, `when` and `module` are valid. Code
  points pair surrogates as `codePointAt` does; an isolated surrogate is
  neither start nor part. Both export callers keep their null check, their
  `invalid Java Bridge export package: '<name>'` text, their sorted
  deduplicated package set and, in BridgeExportSurface, the separate
  `ironwood.bridge` reservation. The narrower Ironwood lexer grammar is not
  reused, and the documentation tag scan keeps its UTF-16 `char` loop.
- B5 and the Bridge identity: `BridgeGeneration.digest` hashes the domain
  string `ironwood-java-bridge-identity-v1`, then every key and value of the
  map in String order, each as a big-endian 32-bit UTF-16 unit count followed
  by big-endian 16-bit units (unpaired surrogates stay distinct);
  `bytesDigest` hashes raw bytes; both print 64 lowercase hex digits.
  `contentIdentity` keeps its empty-inventory, blank-name and hash checks and
  their IllegalArgumentException texts.
- B6 and B7 manifests: the pairing manifest is
  `# Ironwood Java Bridge paired artifact; schema 1\n` followed by
  `key=value\n` lines in String order, with every unit at or below space,
  above `~`, or in `\:=#!` written as `\u` and four lowercase hex digits, in
  US-ASCII; readers parse it as Java's `Properties.load(InputStream)` does
  (ISO-8859-1, comments, continuations, escapes, later duplicates replacing
  earlier ones) and then require byte equality with the re-serialized form.
  JAR manifests are written as Java's `Manifest.write` writes a main section
  (the version first, then the other attributes in insertion order, each line
  wrapped at 72 UTF-8 bytes with a leading-space continuation, CRLF, a closing
  empty line, and nothing but the empty line when no version is set), and
  read with Java's verdicts and case-insensitive attribute names.
- B6 9.1 and D275: the Bridge JAR is STORED in Java's spelling with the
  manifest first and the remaining entries in String order; entry names are
  validated before writing; the complete staged jar is verified (entry count,
  each entry's presence, size and bytes) before publication.
- B3 and D271: Bridge JAR publication stages with
  `createTempFile(parent, ".ironwood-bridge-", ".jar")`, publishes only with
  `moveAtomicReplacing` (no non-atomic fallback, an earlier jar survives
  every failure) and deletes the stage on every exit. No fsync or durability
  policy is added (HANDOFF_M4.1-M4.2). Distribution staging keeps its
  no-replace policy. Traversal rewrites follow B3's table: `Files.walk` and
  `Files.list` inventories become `walkFileTree` selections, reverse-sorted
  cleanups become post-order deletion.
- B4, D272 and D273: JDK tools run by absolute path through
  `ProcessRunner.runToFile` with the existing release, encoding, lint and
  path flags; the generated Java and C and the external JDK boundary stay as
  they are; the D272/D273 `runToFile` exemptions stand.
- S7 producer identity: `BridgeProducerInputs` requires
  `ironwood/compiler/Main.class` and a VERSION resource in a Java compiler
  inventory. A native producer cannot satisfy that scheme, and no synthetic
  `Main.class` entry or fabricated Java compiler inventory stands in for the
  separate producer-manifest design; hashing helpers do not authorize one.
- D132/D133, mandatory memory safety in every unfreed mode, D278-D290's
  whole-program missing-free checks, field loans and per-object verdicts, and
  the D261/D262 port forms (creation-array owners, no service getters,
  invocation-lived shared graphs).

## Material choices

Each choice records its options and the default M6 adopts, subject to the
evidence named.

**Identifier-start data.** Options: (a) generate Java 21's
`Character.isJavaIdentifierStart` ranges from JDK 21's observed answer for
every code point, as M5.4 generated the part ranges, into the same
`JavaIdentifiers` file and generator; (b) derive starts from Ironwood's own
character categories, which do not cover every Unicode 15.0 category Java
uses; (c) translate OpenJDK's character tables. Choose (a): the data stay
Unicode 15.0 character data with the existing Unicode notice, no OpenJDK
source is consulted, and the regeneration test covers both tables. The
keyword set comes from JLS 21 sections 3.9 and 3.10 and is checked against
`SourceVersion.isKeyword` for every reserved and contextual keyword.

**Inventory and identity serialization.** Java keeps these inventories in
`TreeMap<String, String>`; a native map of Strings would expose every String
it holds, so none could be freed (the M5 TextList precedent). A
compiler-private sorted text map owns keys and values as primitive storage,
keeps String order, replaces on `put` and refuses on `putIfAbsent`, and feeds
the identity digest and the pairing manifest without materializing Strings.
The digest reuses M3.2's `Sha256` and `hexDigest`.

**Pairing-manifest reader.** Options: (a) Java's full `Properties.load`
semantics followed by the canonical re-serialization check; (b) a strict
reader of the canonical grammar only, reporting every other input as
noncanonical. (b) would turn Java's `IllegalArgumentException` for a
malformed `\u` escape into the noncanonical message. Choose (a), written
from the published `Properties.load` specification and checked against Java
on a generated corpus, so every input gets Java's verdict: a parse failure,
noncanonical, or the same map.

**JAR manifest reader.** Options: (a) Java's verdicts and messages for the
whole manifest, keeping the main attributes; (b) a reader of the writer's own
format only. Choose (a): untrusted paired archives carry the values
companion's manifest, and Java parses named sections after the main one.
Java logs a warning for a duplicate attribute and keeps the later value; the
native reader keeps the later value without logging, a recorded difference.

**Bridge text and pattern helpers.** The generators' `Float.toHexString`,
`Double.toHexString`, `String.format("\\%03o")`, `String.format("\\u%04x")`
and `stripTrailing`, and the eleven `String.matches` patterns (Maven group,
coordinate and version, the C root name, SHA-256 text, macOS minimum
version, dependency paths and the ensure method), become purpose-specific
helpers with Java's results; no format or regular-expression engine is
added. Java 21's API documentation and observed results are the references.

**Bridge JAR writer.** The port's writer validates names as Java does,
requires a manifest whose main `Manifest-Version` is `1.0`, refuses a
destination that exists and is not a regular file, writes STORED entries with
`ZipWriter`, re-reads the staged bytes with `ZipArchive` and compares each
entry's bytes with its content directly (equivalent to Java's digest
comparison, without hashing), then calls `moveAtomicReplacing`. STORED
output changes the bytes of every Bridge JAR and of every identity computed
over those bytes (the values companion's `java.values.sha256` and the
distribution's `sha256.<file>` inventory); entry-content identities such as
`content.sha256.<entry>` and `native.sha256` do not depend on the container.
M6.2 records these effects.

**JDK selection.** The Java bootstrap selects the producer JDK by D239: the
running JVM's home, chosen through `JAVA_HOME`, the IDK's bundled JDK or the
`PATH` runtime, with in-process `javax.tools` and the running JVM's
`java.runtime.version` and `java.vendor` recorded in the native build inputs.
A native producer has no running JVM. Which JDK it uses, how it locates it
and how it records its version and vendor are the human's decision; M6.2
records the options with evidence and stops at that point instead of
choosing.

## Shared machinery and safety

| Changed machinery | Consumers to protect | Paired evidence |
| --- | --- | --- |
| `JavaIdentifiers` gains the start table; its generator writes both | DocText's tag scan (M5.4), the regeneration test | M5.4's documentation transcript and table test still pass; start and part tables equal JDK 21 for every code point |
| New compiler-private Bridge helpers (names, identity, inventories, manifests, text, JAR writer) | Port reference-bound audit, D163 owners, the Files borrowing classification, `Sha256`, `ZipWriter`, `ZipArchive`, M4.2's moves | Inputs freed after each call; results freed by their owner; use after free, double free and retained loans rejected in every unfreed mode; every allocation failure unwinds without leftover entries |
| No compiler, IR, runtime, lowering or analysis change is planned | Every existing consumer | If one becomes necessary it is recorded here with paired safe and unsafe regressions before any port code relies on it |

All new sources are original under the default license except the generated
identifier ranges' Unicode notice. Java 21's public API documentation, the JAR
File Specification, JLS 21 and observed JDK behavior are the references. No
OpenJDK source is consulted or translated.

## Focused verification selection

New registrations, each with Java 21 references as single-file programs under
`docs/self-hosting/m6/*-evidence/`:

- name validation: `SourceVersion.isName`, `isIdentifier` and `isKeyword` over
  a generated corpus (keywords, contextual keywords, empty and dotted forms,
  non-ASCII letters and digits, identifier-ignorable characters, paired and
  isolated surrogates, supplementary letters) and the start and part
  predicates for every code point; both export callers' diagnostics and
  package sets;
- identity and inventories: `BridgeGeneration`'s digest framing,
  `bytesDigest` and `contentIdentity` on golden vectors (empty strings,
  supplementary characters, distinct unpaired surrogates, 00/7f/80/ff bytes)
  and the pairing-manifest writer and reader against `Properties.load` and
  `BridgePackageManifest.serialize` on a generated corpus;
- JAR manifests: `Manifest.write` bytes, including wraps that split UTF-8
  sequences, and `Manifest` reading verdicts and values on a corpus;
- Bridge text and patterns: the hex-float, escape and strip helpers and every
  pattern's verdicts against Java's expressions;
- Bridge file inventories: the runtime `.c`/`.h` inventory, the package
  directory listings and the distribution stage listing against Java's
  `Files.walk` and `Files.list` selections on a fixture tree with links and
  Unicode names;
- M6.2: native Bridge JARs read by Java's `ZipFile`, `JarInputStream`, the
  `jar` tool and a class loader; the staged verification and
  `moveAtomicReplacing` keeping an earlier jar on each injected failure; the
  values companion and distribution manifests against the Java producer's;
- ownership controls in every unfreed mode and allocation-failure sweeps for
  each new allocating path.

Existing consumers to rerun: the five M5.4 tests (the identifier file and
generator change), `M5.2 STORED writer equals Java's bytes and opens in Java
readers and JAR tools`, `M4.2 publication moves match Java 21 on one file
system across artifacts`, `compiler port generics spell reference bounds`,
`compiler SHA-256 matches Java digests across artifacts`, and one compilation
of every port source with the five pilot adapters under `--unfreed=warn`
with no diagnostics.

Hosts: this macOS arm64 machine qualifies every phase; the M6 tests also run
on Linux x86-64 (`estonia`) and Linux arm64 (`miami`) for the checkpoint. Run
`git diff --cached --check` for every commit and `./scripts/check-licenses.sh`
for source changes. Never run an unfiltered suite. Unsafe programs are
compile-only.

Status: initial review recorded before implementation. Each increment below
adds the review made as its phase advances.

## M6.1 increment (D291-D293)

No analysis rule changed. The material choices held: the start ranges were
generated into the shared table, whose five M5.4 consumers were rerun; the
pairing-manifest reader implements `Properties.load` in full; the manifest
reader gives Java's verdicts for whole manifests. Three scope findings
shaped the increment: BridgeLinuxPayload's four regular expressions over
`llvm-readelf` output were not in the initial review and became ReadelfScan;
a float constant's raw bits are kept by the native constant representation
rather than a public `Float.floatToRawIntBits`, which would be a public API
change; and an element load whose index is a call fails D281's field proof,
so such indexes are computed into locals first. The allocation sweep found
the first JarManifest writer allocating its result before its last line and
leaking it when that line failed; the result is now the last allocation. The
classification needed 88 phase-scoped rules; the eight earlier tables
regenerate unchanged.

## M6.2 increment (D294)

No analysis rule changed. The destination check uses `Files.isSymbolicLink`,
`exists` and `isRegularFile`, which need no exception for an absent
destination, instead of `readAttributesNoFollow`. The JarInputStream rule is
its own class so that JarManifest does not depend on the ZIP readers, and the
sweep found it leaking an entry's bytes when a name check failed; the bytes
are now released before the failure propagates. The native producer's JDK
selection is the maintainer's decision: its options and evidence are recorded
in [JDK_SELECTION.md](JDK_SELECTION.md) and M6.2 stops there. S7's producer
identity is tracked without a synthetic compiler inventory.

## JDK selection review (before D295)

The maintainer chose option B with R2 from
[JDK_SELECTION.md](JDK_SELECTION.md) on 2026-10-07. Contract to implement in
a port adapter beside D273's, without changing the Java producer or the
launcher scripts:

- Selection, as `scripts/jdk.sh` orders it: a nonempty `JAVA_HOME` selects
  `$JAVA_HOME/bin/java` and an invalid one fails without fallback (`selected
  Java is missing; set JAVA_HOME to a JDK: <path>`); otherwise the
  installation's `toolchain/lib/jvm/bin/java` when executable; otherwise
  `java` found on `PATH` through M4.3's ExecutableSearch, failing with
  `... JDK: java on PATH`. Relative spellings resolve against the working
  directory, because runToFile needs an absolute executable.
- Inspection and recording: the selected `java -XshowSettings:properties
  -version` runs through runToFile into a scratch log that is read within
  1 MiB and always deleted; a nonzero exit or a missing `java.home`,
  `java.specification.version`, `java.runtime.version` or `java.vendor` line
  gives `could not inspect selected Java: <path>`. `jdk.version` and
  `jdk.vendor` are the last two values, as the Java producer records them.
- Gates with BridgeBuildTools' messages: feature 21 to 25, the `javac` and
  `javadoc` launchers in place of the in-process tools, and the JNI headers.
- Tools: `javac` and `javadoc` with BridgeBuildTools' flags (and the
  assembler's class path) by absolute path, diagnostics read back from a log.

Consumers to protect: D273's Command and ExecutableSearch, ProcessRunner's
audited borrowing, the M6.2 JDK-tool test. Paired evidence: selection,
values and failures against `scripts/jdk.sh`'s own selection, each JDK's own
`System.getProperty` values and BridgeBuildTools' messages; ownership controls
and an allocation sweep with no log left behind. No compiler, runtime or
analysis change is planned. Focused tests: the new M6.2 selection test, the
reworked JDK-tool test, the M4.3 adapter tests that share Command and
ExecutableSearch (`M4.3 port executable search matches the Java seed`,
`M4.3 driver adapters own arguments and lend probe outputs`), and the port
compile.

## D295 increment

No analysis rule changed. The values moved into a TextList owner after the
field proof rejected `new String(field)` copies and calls through a local
alias of a String field. `scripts/jdk.sh` served as the selection reference
in the same environment and directory, with a tools directory supplying `sed`
and `cat` but no `java`. The M4.3 tests that share Command and
ExecutableSearch were not affected, as neither changed.

## M6.3 increment

M6.3 changes no compiler, runtime, library or analysis code. It adds the
reconciliation tool and records. Contracts to preserve: the pinned M0 ledgers,
which the tool only reads and checks against their manifest, and the model
schemas, whose hashes it records; each recorded phase table, which it
regenerates unchanged; and the
plan's rule that no prerequisite of a ready stage is deferred. Paired evidence:
the tool's pass on the recorded inputs and on an unchanged copy, and eight
negative controls that each introduce one defect (a changed ledger, a missing
M1 row, a changed phase rule, an unknown decision, a missing record, an IR
record missing from IrModel, an edited output document and an edited output
ledger). Focused checks: the tool, the controls, `git diff --check` and the
license audit for the new sources.
