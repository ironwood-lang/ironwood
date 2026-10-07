<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6.2: Bridge consumer qualification (D294, D295)

Status: complete. The Bridge JAR writer profile, its verified atomic
publication, the dependency manifests, the JDK selection the maintainer chose
([D295](../../DECISIONS.md#d295---select-the-native-bridge-producers-jdk-as-the-java-producers-launcher-does))
and the JDK-tool process path through it are qualified below; S7's producer
identity is tracked, not designed. Nothing here ports BridgeJarArchive,
BridgeValuesLibrary, BridgeDistributionCommand, BridgeAssembler or
BridgeBuildTools; it qualifies the port's pieces their S7 ports use.

| Port source | Replaces |
| --- | --- |
| [BridgeJar.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/BridgeJar.iron) | BridgeJarArchive.publish: name checks, the manifest check, the destination check, `ZipOutputStream` staging, the `ZipFile` read-back with digest comparison, `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` and `deleteIfExists` |
| [JarStreams.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/JarStreams.iron) | `new JarInputStream(...).getManifest()` in BridgeValuesLibrary.validate |
| [JdkSelection.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/JdkSelection.iron) | BridgeBuildTools' running-JVM `java.home`, `Runtime.version()`, `ToolProvider` tools and gates, BridgeProducer's `java.runtime.version` and `java.vendor` inputs, BridgeAssembler's in-process javac |

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

## JDK selection (D295)

The native producer selects its JDK as `scripts/jdk.sh` selects the Java
producer's: a nonempty `JAVA_HOME`, failing without fallback when invalid;
otherwise the installation's `toolchain/lib/jvm`; otherwise `java` on `PATH`.
It runs the selected `java -XshowSettings:properties -version` through
`runToFile` and records `java.runtime.version` and `java.vendor` as
`jdk.version` and `jdk.vendor`, the values the Java producer records, with
`java.home` and `java.specification.version` for the home and BridgeBuildTools'
gates.

| Check | Result |
| --- | --- |
| Selection order | `JAVA_HOME`, the installation, `PATH` and their precedence, an empty and a relative `JAVA_HOME`: each selects the JDK `scripts/jdk.sh` selects in the same environment and directory |
| Failures | A missing `JAVA_HOME` (with an installation present) and nothing on `PATH` give `scripts/jdk.sh`'s messages; a JDK double that exits 1 or omits `java.vendor` gives `could not inspect selected Java: <path>` |
| Values | The test JVM and 13 installed JDKs (Oracle 17, 20, 23 and 25; GraalVM 21 and 25; Eclipse Temurin 21, 22, 23, 24 and 25; IBM Semeru 23; Azul Zulu 23) record the home, version and vendor each JDK reports through its own `System.getProperty`; doubles behind a JVM warning line and multi-line properties parse too |
| Gates | JDKs 17 and 20 and doubles reporting 26 and `1.8` give BridgeBuildTools' range message with the runtime version; doubles without `javac`, `jni.h`, the platform's `jni_md.h` or `javadoc` give its component and header messages in its order |
| Tools | `javac` and `javadoc` through the selection write the in-process tools' class files and pages byte for byte with the same JDK 21.0.1; a `-Werror` failure exits 1 with the same diagnostics; no tool log is left |
| Allocation failure | With a JDK double, a selection, its gates and one `javac` and `javadoc` run pass 125 limits; every stopped run unwinds and leaves no inspection or tool log |

Empty `PATH` entries are skipped, as the native driver's ExecutableSearch does,
where `command -v` would search the working directory; a selected program that
cannot start reports ProcessRunner's failure.

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
| `M6.2 JDK selection follows JAVA_HOME the installation and PATH with Java's values and gates` | selection order, failures, values and gates (with `IRONWOOD_TEST_JDKS` naming more JDKs) |
| `M6.2 selected JDK tools match the in-process javac and javadoc` | the JDK-tool process path through the selection |
| `M6.2 JDK selection owns its values` | ownership pairs in every unfreed mode |
| `M6.2 JDK selection unwinds every allocation failure without leftover logs` | the selection sweep with log checks between runs |

The M6.1 allocation sweep now also covers JarStreams (509 limits), the six
M6.1 tests pass, and one compilation of all 199 port sources with the five
pilot adapters under `--unfreed=warn` reports no diagnostics. The M6.1
classification was regenerated so that eight rows cite D294's helpers
(BridgeJar, JarStreams and the `isSymbolicLink` destination check); one
pattern, `Files.newOutputStream`, moved from B to D.

The JAR, dependency-manifest and JDK-tool part was first verified from a
fresh `git archive` of 9fa163d0 (21 tests, 24 statuses); the JDK selection
was added after the maintainer's decision. The M6 handoff run verifies the
complete phase on all three hosts.
