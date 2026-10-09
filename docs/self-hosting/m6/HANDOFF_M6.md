<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6 handoff: Bridge preparation and final handoff

Status: the M6 checkpoint passes, and with it before-self-hosting preparation
(M0-M6) is closed. Every in-scope preparation obligation has an evidence
record, and every M0 inventory item that remains for a later stage states its
reason, first affected stage and blocking effect. The handoff run is recorded
in [handoff-evidence/manifest.json](handoff-evidence/manifest.json) and
reproduced by [run-evidence.sh](handoff-evidence/run-evidence.sh), whose Linux
part is [linux-evidence.sh](handoff-evidence/linux-evidence.sh). Passing M6
claims neither S7 nor S8: the Bridge production port, the IDE transition and
release qualification are those stages' work, and no stage from S2 on has
started. The native bootstrap and production cutover still require the
separate S2-S8 exits in [SELF_HOSTING_PLAN.md](../../SELF_HOSTING_PLAN.md).

## Delivered

| Plan item | Delivery | Decision | Record |
| --- | --- | --- | --- |
| M6.1 export names | `JavaNames` (Java 21's `SourceVersion.isName`, `isIdentifier` and `isKeyword`), `BridgeExports` (both export loops' messages), Java 21's identifier-start ranges in the shared `JavaIdentifiers` table | D291 | [NAMES.md](NAMES.md) |
| M6.1 identities, inventories and manifests | `TextMap`, `BridgeIdentity` (UTF-16 framed digests), `BridgeProperties` (the pairing-manifest writer and `Properties.load`), `JarManifest` (`Manifest.write` and the reading verdicts) | D292 | [INVENTORIES.md](INVENTORIES.md) |
| M6.1 generator helpers | `BridgeText`, `BridgePatterns`, `ReadelfScan`, `SourceFiles` | D293 | [TEXT.md](TEXT.md) |
| M6.1 classification | 8,242 calls in 387 patterns: A 5,109 (128), B 2,183 (127), D 950 (132) | (record) | [CLASSIFICATION_M6.1.md](CLASSIFICATION_M6.1.md), [CONTRACTS.md](CONTRACTS.md) |
| M6.2 Bridge JARs | `BridgeJar` (STORED in Java's spelling, staged, verified and published with `moveAtomicReplacing`), `JarStreams` (`JarInputStream.getManifest`) | D294 | [JAR.md](JAR.md) |
| M6.2 JDK selection and tools | `JdkSelection`: `scripts/jdk.sh`'s order, the selected JDK's own version and vendor through `java -XshowSettings:properties`, BridgeBuildTools' gates, `javac` and `javadoc` by absolute path | D295 | [JDK_SELECTION.md](JDK_SELECTION.md), [JAR.md](JAR.md) |
| M6.3 closure | [reconcile.py](reconcile.py) with its controls, [the reconciliation](RECONCILIATION.md) of all 85,614 M0 inventory items, the remaining S7 and S8 work | (record) | [CLOSURE.md](CLOSURE.md) |

M6 adds compiler-private port sources, tests and records only: no public
API, standard library, runtime, analysis, script or package change.

## Checkpoint

| Criterion | Result |
| --- | --- |
| Every in-scope preparation obligation has an evidence record | Each phase from M0.1 to M6.3 is checked in the plan with its record, and each milestone has its checkpoint or handoff: [M0](../m0/M0_CHECKPOINT.md), [M1](../m1/CHECKPOINT.md), [G1](../m2/CHECKPOINT.md), [M3.1](../m3/HANDOFF_M3.1.md), [M3.2](../m3/HANDOFF_M3.2.md), [M3.3](../m3/HANDOFF_M3.3.md), [M4.1-M4.2](../m4/HANDOFF_M4.1-M4.2.md), [M4.3](../m4/HANDOFF_M4.3.md), [M5](../m5/HANDOFF_M5.md) and this record. Every call's phase table gives it class A, B or D, with no pending row and no open C item. |
| A deferred item states its reason, first affected stage and blocking effect | Every M0 item has one owner in [RECONCILIATION.md](RECONCILIATION.md), and each owner states the three. |
| No prerequisite of a claimed stage is deferred while that stage is ready | What each owner leaves is that stage's own work (the consumer port and its fixture, a general form of a bounded pilot proof, or a route the source-only compiler excludes); [CLOSURE.md](CLOSURE.md#readiness). |

| Owner | Items | First affected stage |
| --- | --- | --- |
| qualified at G1 | 1,877 | none |
| bounded: the pilot's input only | 79 S2, 1,196 S3, 2 S4 | the general consumer's stage |
| delivered for the optional native-driver route | 478 | S4 if it elects the native driver, otherwise S6 |
| prepared for the consumer port | 2,860 S2, 55,052 S3, 7,984 S4, 1,803 S6, 14,080 S7 | the stage named |
| S7's producer-identity design | 7 | S7 |
| excluded from the source-only route | 164 S6, 32 S7 | the stage named |

## Stage readiness

| Stage | Preparation evidence | What the stage itself must still do |
| --- | --- | --- |
| S2 | [M3.1 handoff](../m3/HANDOFF_M3.1.md) | Port the AST/IR representations onto the D261-D266 forms, replace the reflective walkers with traversal that follows `IrModel.walked` before porting it, and run the 2,939 S2 items' fixtures |
| S3 | [M3.1](../m3/HANDOFF_M3.1.md) and [M3.2](../m3/HANDOFF_M3.2.md) handoffs | Port semantic analysis in dependency order over 56,248 items, generalizing the pilot's bounded proofs; record a newer J0 seed, since J0 cannot compile the accumulated port, and qualify capacity on it |
| S4 | [M3.3 handoff](../m3/HANDOFF_M3.3.md); optionally [M4.3](../m4/HANDOFF_M4.3.md) | Port native code generation and the source-only compiler over 7,986 items with the shell driver (D269), or elect the native driver on M4's adapters; the S4 J0 build-capacity qualification |
| S5 | none of its own | The fixed point after S4's exit, on a frozen seed |
| S6 | [M5 handoff](../m5/HANDOFF_M5.md), with M4 | Port Main's dispatch, the class and archive tools and IronDoc over 1,967 items and edges, and compare them with the Java tools |
| S7 | This handoff | Port Bridge production over 14,119 items and edges, design the native producer identity, and move the IDE consumers ([CLOSURE.md](CLOSURE.md#remaining-s7-integration)) |
| S8 | none of its own | Qualify the native compiler on the three platforms and make it the default ([CLOSURE.md](CLOSURE.md#remaining-s8-qualification)) |

## Qualified hosts and boundaries

| Host | Result |
| --- | --- |
| macOS 27.0.1 arm64 | the 15 M6 tests and 11 consumer tests, with 13 further installed JDKs in the selection test; the reconciliation with all nine phase tables regenerated and its controls; the port compilation and the audits |
| Linux x86-64 (`estonia`, kernel 4.15, glibc 2.27, JDK 21.0.1, conda LLVM 23.1.0) | the 15 M6 tests and the five M5.4 consumers, with 10 further installed JDKs in the selection test (Oracle 17, 20, 23 and 25; GraalVM 21 and 25; Temurin 21 and 23; Semeru 21 and 23) |
| Linux arm64 (`miami`: Ubuntu 22.04 guest, kernel 5.15, glibc 2.35, Temurin 21, conda LLVM 23.1.0) | the 15 M6 tests and the five M5.4 consumers, with every JVM printing its SVE startup warning |

Boundaries carried forward, none needing runtime overhead:

- J0 cannot compile the accumulated port (M3.2); S3 and S4 qualify capacity
  on a newer recorded seed.
- Publication has no fsync or durability policy (HANDOFF_M4.1-M4.2); the
  replacing fallback copies regular files only.
- Tools inherit descriptors the compiler received without close-on-exec, and
  a signal to the compiler alone leaves a running tool (HANDOFF_M4.3).
- Readers and writers hold whole archives in memory, inflate is one-shot and
  there is no compressor (HANDOFF_M5).
- Native `.ironclass` and Bridge JAR bytes, and identities over whole jars,
  differ from the Java bootstrap's DEFLATED output; content identities agree
  ([JAR.md](JAR.md#identity-effects-of-stored-bridge-jars)).
- Where the Java baseline names the first failing entry in
  `stringPropertyNames()` hash order (TlsDependency, BridgeNativeSupport,
  BridgeAssembler.verifyLinux), the port reproduces that order or sorts and
  records the difference.
- A repeated manifest attribute keeps the later value without Java's logged
  warning; `JarStreams` does not verify jar signatures, which the companion
  never carries.
- JDK selection skips empty `PATH` entries, and a tool process's JVM startup
  warnings reach its merged diagnostics, which the in-process tools never
  print ([JAR.md](JAR.md#jdk-selection-d295)).
- Representation limits from M1-M5 still apply: caught exceptions and their
  messages stay allocated, a `StringBuilder` made from a String cannot be
  freed, and objects stored in live analyzer containers are invocation-lived
  (the G1 conservative limits).

## Decisions

Recorded during M6: D291-D295. The maintainer chose the JDK selection (D295,
option B with R2) on 2026-10-07. Left for the maintainer at their stage: the
native producer-identity design (S7) and the decision superseding the relevant
part of D008 before a production cutover (S8). No other decision is pending.

## Handoff run

One run on a fresh `git archive` of 8dcf9441: the strict `scripts/build.sh`,
`javac --release 21 -Xlint:all -Werror` over every compiler test source, 26
macOS tests (the 15 M6 tests in [m6-tests.txt](handoff-evidence/m6-tests.txt)
and 11 consumers: the five M5.4 tests of the shared identifier table, the
M5.2 STORED writer, M4.2's publication moves, the two M4.3 tests of Command
and ExecutableSearch, SHA-256 and the port generics audit), the M6.3
reconciliation, which regenerated all nine phase tables byte for byte, with
its unchanged and eight negative controls, one compilation of all 200 port
sources with the five pilot adapters under `--unfreed=warn` (no
diagnostics), `git diff --check` over eb3c1413..8dcf9441, the license audit,
and on estonia and miami the 15 M6 tests with the five M5.4 tests. All 17
statuses passed. The macOS part started once the one-minute load fell below
3, with the estonia run beside it; the miami run had finished. Unsafe programs
were compile-only; no full suite or hosted build ran.

Before this run, de054745 kept JVM startup warnings out of two comparisons
after checking the miami guest's warning: the `jar` tool's listing now reads
standard output only, and the JDK-tool test leaves warning lines out of the
tool's merged diagnostics. Both tests pass there. The commit that records
this handoff changes records only, and a fresh archive of it reruns the
reconciliation, `git diff --check` and the license audit.
