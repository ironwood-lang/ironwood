<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6.2: Bridge consumer qualification (D294)

Status: in progress, blocked on one decision. The Bridge JAR writer profile,
its verified atomic publication, the dependency manifests and the JDK-tool
process path are qualified below; the native producer's JDK selection awaits
the maintainer ([JDK_SELECTION.md](JDK_SELECTION.md)). S7's producer identity
is tracked, not designed. Nothing here ports BridgeJarArchive,
BridgeValuesLibrary, BridgeDistributionCommand, BridgeAssembler or
BridgeBuildTools; it qualifies the port's pieces their S7 ports use.

| Port source | Replaces |
| --- | --- |
| [BridgeJar.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/BridgeJar.iron) | BridgeJarArchive.publish: name checks, the manifest check, the destination check, `ZipOutputStream` staging, the `ZipFile` read-back with digest comparison, `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` and `deleteIfExists` |
| [JarStreams.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/JarStreams.iron) | `new JarInputStream(...).getManifest()` in BridgeValuesLibrary.validate |

## Writer profile and publication

The profile is D275's STORED writer applied to Bridge JARs: the manifest
first, the other entries in String order, each entry spelled as Java's
`ZipOutputStream` spells a STORED entry with time 0. Before writing, names are
checked in String order (the callers' maps are sorted) and the manifest must
read as one with main `Manifest-Version: 1.0`; a destination that exists
without following a final link and is not a regular file is refused. The
stage is created exclusively in the destination's parent, written whole,
read back with `ZipArchive` and compared entry by entry (count, presence,
size, bytes), then published with `moveAtomicReplacing`, with no non-atomic
fallback; the stage is deleted on every exit. Comparing bytes is equivalent
to the baseline's comparison of their SHA-256 digests and needs no hashing.
No fsync or durability policy is added (HANDOFF_M4.1-M4.2).

| Check | Result |
| --- | --- |
| Entries, order and bytes | For a paired artifact with a loadable class and a 200,000-byte native image, a sources companion, a values companion and a 3,000-entry jar, the native jar lists Java's entries in Java's order, each with Java's bytes |
| Byte spelling | Each native jar equals Java's `ZipOutputStream` STORED spelling of the same entries byte for byte; Java's own jars are DEFLATED |
| Java readers and tools | `ZipFile`, `JarFile`, `JarInputStream` (same main attributes), a `URLClassLoader` that loads and calls the class, `jar tf` and `jar --describe-module`, which reports the automatic module name |
| Invalid contents | 13 invalid names (empty, `/a`, `a/`, `a//b`, `./a`, `a/./b`, `a/../b`, `..`, `.`, a backslash, NUL, `a/.`, `../a`), six manifest defects (no version, version 2.0, a header without a space, empty, no line end, a misplaced continuation) and a missing manifest give the publisher's messages, and a lowercase `manifest-version` publishes, as in Java |
| Destinations | A directory, a link to a file and a dangling link give `Java Bridge output is not a regular file: <path>`; a read-only parent fails to stage; each keeps the earlier jar and leaves no stage |
| Replacement | A valid publication replaces an earlier jar and creates missing parents |
| Allocation failure | Raising the limit from zero, every stopped run unwinds its allocations, keeps the earlier jar and leaves no stage; a two-entry publication passes 126 limits before it completes |

## Dependency manifests

| Check | Result |
| --- | --- |
| Values companion validation | 24 companions (built by BridgeValuesLibrary.packageClass, rebuilt by BridgeJar, and handcrafted STORED and DEFLATED variants: `META-INF/` first, lowercase names, `ſ` and `ı` in names, the manifest after another entry, wrong version, ABI or module name, a malformed or empty manifest, an empty jar and garbage) under six metadata variants (matching, wrong digest, wrong ABI, missing version, an invalid Maven version, a mismatching version), plus two companion-absent cases, give BridgeValuesLibrary.validate's verdicts and messages, the pairing metadata read through BridgeProperties |
| Distribution inventory | A stage with jars, a POM, a link and a subdirectory gives BridgeDistributionCommand's `bridge-distribution.properties` byte for byte, through FileCollector, BridgeIdentity, TextMap and BridgeProperties |
| Assembly targets | Ordered, reversed, duplicate, empty and case-differing host target sets give BridgeAssembler.assemble's duplicate error, first entry and order through TextMap's `putIfAbsent` |

`JarStreams` finds a manifest only as the first entry, or the second after a
`META-INF/` directory entry, as `JarInputStream` does, and does not verify jar
signatures, which the companion never carries.

## Identity effects of STORED Bridge JARs

| Identity | Effect |
| --- | --- |
| `content.sha256.<entry>`, `native.sha256`, `native.input.java.projection.sha256`, `native.input.distribution` | Unchanged: they hash entry contents, which are the same bytes |
| `java.values.sha256` in pairing metadata | Changes: it hashes the whole values companion jar |
| `sha256.<file>` in `bridge-distribution.properties` | Changes for every jar in the Maven stage |
| BridgeValuesLibrary.copy's existing-companion check and assembly's common-content comparison | A Java-built and a native-built companion differ, so one output directory or one assembly cannot mix them; assembly already refuses mixed producers through `compiler.sha256` |

Jar sizes grow by their contents' compression ratio: compressible contents
such as class files and HTML were 3.0 times larger as STORED class artifacts
in M5.2 (D275); native images that compress poorly change little.

## JDK-tool process path

`javac` and `javadoc` run by absolute path through D273's `Command` and
D272's `runToFile`, with BridgeBuildTools' exact flags, write the in-process
tools' class files and documentation pages byte for byte with the same JDK
21.0.1, and a `-Werror` failure exits 1 with the same diagnostics. The test
passes its own JVM's home: which JDK a native producer uses, how it finds it
and how it records its version and vendor is the open decision
([JDK_SELECTION.md](JDK_SELECTION.md)).

## Producer identity (S7, tracked)

BridgeProducerInputs identifies the producer by the Java compiler's class
directory or jar inventory, requiring `ironwood/compiler/Main.class` and a
VERSION resource equal to the compiler version, plus the runtime's `.c` and
`.h` inventory; `compiler.sha256` and `runtime.sha256` enter the generation
identity, and assembly requires them to match. A native producer has no such
inventory. S7 designs a versioned native producer manifest binding the actual
compiler, generation inputs, runtime and dependencies, with Java and native
producer pairing and assembly compatibility, failing closed. M6 adds no
synthetic `Main.class` entry and no fabricated Java compiler inventory: the
M6.1 classification's producer-identity patterns are B rows naming this S7
design (D293), and the hashing helpers (`BridgeIdentity`, `SourceFiles` for the
runtime inventory, D269's BuildIdentity) only preserve existing algorithms.
First affected stage: S7; blocking effect: native Bridge generation and
assembly identities cannot be produced until the design exists.

## Verification

| Test | Covers |
| --- | --- |
| `M6.2 native Bridge jars spell Java's STORED entries and open in Java's readers and JAR tools` | entries, order, bytes, spelling, readers, tools, identity effects |
| `M6.2 Bridge jar publication keeps Java's verdicts and earlier output on failure` | invalid contents, destinations, read-only parent, replacement |
| `M6.2 Bridge jar publication keeps the earlier jar under every allocation failure` | the allocation sweep with the earlier-jar and stage checks between runs |
| `M6.2 Bridge jar contents borrow inputs and own their copies` | ownership pairs in every unfreed mode, JarStreams included |
| `M6.2 Bridge dependency manifests and distribution inventories match the Java producer` | values validation, distribution inventory, assembly targets |
| `M6.2 JDK tools run by absolute path match the in-process javac and javadoc` | the JDK-tool process path |

The M6.1 allocation sweep now also covers JarStreams (509 limits), the six
M6.1 tests pass, and one compilation of all 199 port sources with the five
pilot adapters under `--unfreed=warn` reports no diagnostics. The M6.1
classification was regenerated so that eight rows cite D294's helpers
(BridgeJar, JarStreams and the `isSymbolicLink` destination check); one
pattern, `Files.newOutputStream`, moved from B to D.

Verification of the delivered part: from a fresh `git archive` of 9fa163d0
on macOS arm64, the strict `scripts/build.sh`, `javac --release 21 -Xlint:all
-Werror` over every compiler test source, 21 tests (the six M6.1 and six
M6.2 tests, the five M5.4 tests, the M5.2 STORED writer, the M4.2
publication moves, the port generics audit and SHA-256), the nine
classification tables M3.1 to M6.1 regenerated byte for byte, the port
compilation with no diagnostics and the license audit passed, 24 statuses in
all. The Linux hosts run at the M6 checkpoint, after the JDK selection.
