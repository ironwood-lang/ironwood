<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6.3: preparation closure

Status: complete. Every item of the M0 source-backed inventory has one
preparation treatment and one owner in
[the reconciliation](RECONCILIATION.md), and the remaining S7 producer and
IDE integration and S8 qualification work is recorded below without being
treated as done. M6.3 adds no public API, runtime, analysis, packaging or
provenance change.

## Reconciliation

[reconcile.py](reconcile.py) reads the pinned M0 ledgers (checked against
their M0 manifest hashes and counts) and the two model schemas, and writes
[RECONCILIATION.md](RECONCILIATION.md) with the per-item ledger
[reconciliation.json.gz](reconciliation.json.gz). Calls take their class from
their phase's table: the M1 table, whose C rows the M1 checkpoint closed, and
classify.py's tables from M3.1 to M6.1, each of which the tool regenerates and
compares with the recorded file. Syntax sites, captured symbols, hash
origins, traversals and contributions, consumer gates, excluded edges and
model declarations take the recorded conventions of their kind (D168,
D256, D258, D260-D265, D269 and B0's ordering rules), each citing its decision.

| Owner | Items | Meaning |
| --- | --- | --- |
| qualified | 1,877 | implemented in the S1 pilot and qualified at G1 |
| bounded S2, S3, S4 | 79, 1,196, 2 | qualified at G1 only for the pilot's finite input; the general consumer is that stage's |
| driver | 478 | delivered by M4 for the optional native-driver route; the shell route does not need it |
| prepared S2, S3, S4, S6, S7 | 2,860, 55,052, 7,984, 1,803, 14,080 | preparation delivered; the stage ports the exact consumer and runs the item's M0 fixture |
| identity S7 | 7 | the producer-identity calls; S7's native producer manifest design |
| excluded S6, S7 | 164, 32 | edges the source-only S4 route never executes |

The 85,614 items are 30,979 calls (covering all 712 API declarations), 12,413
syntax sites, 3,054 captured symbols, 849 hash origins, 2,957 traversals,
31,325 contributions, 3,489 consumer gates, 196 excluded edges and 352 model
declarations. Each owner names its reason, first affected stage and blocking
effect in the reconciliation. The tool fails on a changed input, an item
without exactly one treatment, an M1 row that does not account for its calls,
a phase table that no longer regenerates, a missing record or decision, an IR
declaration outside IrModel, or recorded outputs that differ from the
regenerated ones; [reconcile-controls.sh](reconcile-controls.sh) applies one
such defect per control to a scratch copy and requires each rejection.

### Readiness

No prerequisite of a stage marked ready is deferred. Every item's preparation
phase, M1.1 to M6.1, is complete, and its class is A, B or D: no pending row
and no open C item remains. What each owner leaves is that stage's own work,
named in its exit in [SELF_HOSTING_PLAN.md](../../SELF_HOSTING_PLAN.md):
porting the exact consumer and running its fixture, generalizing a bounded
pilot proof, or porting a route the source-only compiler excludes. The
producer-identity design is part of S7's own definition, not a preparation
prerequisite.

## Remaining S7 integration

- **Producer identity.** Design the versioned native producer manifest that
  binds the actual compiler, generation inputs, runtime and dependencies,
  with Java and native producer pairing and assembly compatibility, failing
  closed ([JAR.md](JAR.md#producer-identity-s7-tracked)). No synthetic
  `Main.class` or fabricated compiler inventory stands in for it. `SourceFiles`
  keeps the runtime `.c`/`.h` inventory and `BridgeIdentity` the existing
  hashes; neither is the design.
- **Bridge production port.** Port BridgeProducer, the generators, admission,
  native-entry transformations, BridgeJarArchive, BridgeValuesLibrary,
  BridgeAssembler, BridgeDistributionCommand and BridgeBuildTools onto the
  M6.1 and M6.2 helpers (JavaNames, BridgeExports, TextMap, BridgeIdentity,
  BridgeProperties, JarManifest, BridgeText, BridgePatterns, ReadelfScan,
  SourceFiles, BridgeJar, JarStreams, JdkSelection) and the M3-M5 services,
  writing each consumer's fixture. Bridge-specific semantic analysis is S3
  work after its ordinary proof dependencies.
- **JDK tools.** The producer passes its installation root to JdkSelection
  (D295) so that `toolchain/lib/jvm` is a candidate. JdkSelection passes
  sources as arguments; S7 adds argument files where a source list could
  approach the platform's argument limit, keeping the release and diagnostic
  flags.
- **STORED identities.** Whole-jar identities (`java.values.sha256`,
  `sha256.<file>` in `bridge-distribution.properties`) differ from the Java
  producer's DEFLATED jars, so one output directory or assembly cannot mix a
  Java-built and a native-built companion; assembly already refuses mixed
  producers through `compiler.sha256`.
- **Report order.** Where BridgeAssembler.verifyLinux names the first missing
  or changed file in `stringPropertyNames()` hash order, the port either
  reproduces Java's order or sorts and records the difference.
- **IDE consumers.** The language server calls `CompilerPipeline.analyze` in
  process and uses the Java lexer, parser and AST for source resolution and
  symbol queries. Before the Java frontend retires, move it to a native
  analysis interface with unsaved text, source identities, diagnostics and
  symbol queries; a resident service needs its own repeated-request memory
  gate. This consumer is outside the M0 inventory, which covers the compiler
  sources.
- The generated Java and C API and lifetime protocols stay stable, and the
  external JDK requirement stays explicit.

## Remaining S8 qualification

- Repeat the focused bootstrap, artifact, native-execution, resource and
  installation checks on macOS arm64, Linux arm64 and Linux x86-64 with the
  native compiler; the preparation runs qualify helpers, not the compiler.
- Update launchers, `scripts/build.sh`, IDK assembly, smoke paths and tool
  version reporting. The IDK keeps its bundled JDK for Bridge work, which
  JdkSelection's installation candidate relies on.
- A clean-room rebuild from the recorded seed, a tested fallback to the last
  accepted release, archived bootstrap inputs, and an explicit decision
  superseding the relevant part of D008 before the default changes.

## Documents

M6 changed no public API, standard library, runtime, script or package, so
STDLIB.md, STDLIB_ROADMAP.md, DIFFERENCES_FROM_JAVA.md, runtime/README.md, the
packaging documents and the Java Bridge user documents describe unchanged
behavior. The delivered scope is recorded in D291-D295, COMPILER.md (the
Bridge JAR writer), SOURCE_PROVENANCE.md and THIRD_PARTY_NOTICES.md (the
identifier-start data), the plan, and S7 of SELF_HOSTING_PLAN.md, which now
names the qualified JDK-tool path and selection.
