<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge P2 gate audit

This is a historical phase checkpoint. Subsequent [P6 evidence](JAVA_BRIDGE_P6_EVIDENCE.md)
and [x86host hardware results](JAVA_BRIDGE_X86_EVIDENCE.md) record completed
P0-P4/P6a implementation and D213 hardware work. P5/P7 remain deferred; final
numerical acceptance remains open.

P2 passes for continued implementation: the public producer creates a paired
macOS ARM64 value-preview jar, with proved static primitive/String entries,
built-in exception snapshots, automatic loading and distribution inventories.
Continue to P3a. This is not completion of the object bridge, P6 qualification,
numerical performance acceptance or release readiness. P5/P7 remain deferred;
real x86-64 hardware checks remain pending under D213.

The authoritative gate is [the implementation plan](JAVA_BRIDGE_PLAN.md#12-implementation-phases-and-exit-criteria).
The [progress log](JAVA_BRIDGE_PROGRESS.md) records focused commits and fixes.
The public producer composition, version experiment, loader qualification,
notice propagation and runnable preview are committed in `8e9b0373`, `272e8582`,
`ea3181f4`, `da9e55a8` and `bf92e93c`, respectively. These reuse the P0/P1 analysis,
typed-entry, runtime and final-link foundations. They do not substitute JNI-side
ownership guesses for compiler proofs.

## Evidence map

Paths below are relative to `workspace/java-bridge/evidence/`. Every runtime
record refers to its own retained jars/images and identities. Earlier images
are not relabeled as a later production candidate. The final distribution and
rich-exception runs use the producer including notice propagation. P6 must
qualify its eventual combined candidate separately.

| P2 requirement | Focused evidence and outcome |
| --- | --- |
| Exact-package and complete signature discovery | `BridgePackageTests`, `BridgeApiTests` and `BridgeGenerationTests` pass source/class-directory/individual-class/archive reconstruction, inherited API inventory, visibility/signature closure, marker collisions, malformed/missing inputs and distinct complete-program identities. Unsupported public shapes remain rejected. |
| Mandatory proofs reused by production | `BridgeEntryModule.stringValues`, built-in exception closure and exact artifact-bound contracts feed Java/JNI generation. Producer tests reject retaining String inputs, general object surfaces and unsafe frees in every missing-free mode, preserving earlier output. Unmapped reachable custom exceptions remain rejected until P3. |
| Ordinary Java 21-23 consumers | `p2/producer/run-14323175281039715469`: 18 Java 21 launch/allocation children plus 36 Java 22/23 default and checked-JNI class-path/module-path/executable-jar launches. All expected values and continued calls pass without warnings. Source O0, class-directory O3 and archive O3 jars share logical generation/API/program/module identities. |
| All scalar carriers and String cleanup | `p2/value-adapters/run-9307225434529863560`: 30 generated-adapter O0/O3 children across all three JDKs, with separately identified acquisition/result-delivery fault images. Primitive edge values/bit patterns, UTF-16/NUL/surrogates/null, fresh/alias/immortal results and every acquired buffer's cleanup pass. JNI critical APIs are absent. Public producer jars additionally exercise copied values and allocation budgets 0/1/2. |
| Built-in exceptions and bounded graphs | `p2/producer-exceptions/run-17218665186162862845`: 42 recoverable O0/O3 Java 21/22/23 child cases and six separately asserted fatal-exhaustion controls. Public source/archive producer jars preserve checked/unchecked types, messages, getter fields, constructor-required causes, cycle identity, secondary order, graph/trace bounds and stored initializer failures. Native allocation and Java heap exhaustion return safely where the native contract permits, and subsequent scalar/exception calls pass. |
| Snapshot-owned temporary cleanup | Generated transport allocation counters and fault controls in `p2/exception-getters/run-12243100898431218434` validate fresh getter cleanup, pending Java failure preservation and bounded fallback. These are isolated transport checks; public-jar integration is the separate row above. No native throwable reclamation permission is invented. |
| Collision prevention and disjoint artifacts | `p2/producer-loaders-1`: 78 O0/O3 checked-JNI children across Java 21/22/23. Duplicate classes and package-only collisions in both resolution/first-use orders fail before extracting the losing image. Disjoint jars work, and an already usable artifact remains usable. Mixed identities and altered native signatures fail preflight. |
| Permanent binding and rollback | The same 78 cases cover GC anchoring, same-loader idempotence, wrong pairing/loader refusal and retained-image JNI_OnLoad rejection. A separately inventoried fault producer partially binds its second API class and proves cleanup of both its completed and partial registrations while preserving a disjoint artifact. No fault hook ships in production. |
| Actionable deployment failures | `p2/producer-loaders-deployment-2`: 42 additional O0/O3 Java 21/22/23 controls for unsupported host/floor, missing/corrupt resources, unsafe directories, corrupt existing images and native build mismatch. Temporary payloads are removed, corrupt existing bytes stay unchanged/unmapped and unrelated bindings survive. |
| Canonical extraction and D210 signatures | Producer, loader and exception evidence retains exact packaged/extracted hashes and strict macOS codesign results. Loader-source regressions additionally cover concurrent extraction, stale partials, symlinks and permissions. Final-image deployment metadata replaces the earlier fixture placeholder; no older macOS support is inferred. |
| Version predicate and Java 24 refusal | `BridgeLoaderSourceTests` covers the exact 21-23 predicate. `p2/version-policy-3` includes ordinary Java 24/25 controls at O0/O3 in all three launch forms; refusal precedes extraction/loading. |
| D209 product experiment and decision | The same version-policy evidence records 36 child outcomes, including separately paired exact-Java-25 admission, default warnings, checked JNI and deny controls. The [report](JAVA_BRIDGE_JAVA25.md) recommends later consideration of warning-based support. The maintainer retains Java 21-23 for this run, satisfying the pre-P6 decision without expanding support. |
| Atomic distribution and usage | `BridgeDistributionTests`, `BridgeJarArchiveTests` and the producer selector verify exact source/notices, reconstructed library inputs, archive notice propagation, staged content hashes, Java 21 classes, generated source/Javadoc/legal files, and failed-output preservation. The [value example](../examples/java-bridge/README.md) passes on Java 21/22/23. |

## Limits and next dependency

The native scalar adapter's O3 instructions match the handwritten JNI equivalent
after address normalization in its retained benchmark/disassembly record. Both
use a 320-byte adapter frame and the four-instruction typed add entry. Warmed
scalar calls allocate no native objects. These are structural and diagnostic
measurements; P6 final numerical acceptance remains the maintainer's review.

The documented D070/D081 second allocation failure while implicit OutOfMemoryError
is active terminates the child. Its six observed exit-1 controls are not counted
as recoverable exceptions. P2 does not broaden native catchability. Separately
injected faults, deliberately inconsistent jars and the Java 25 experiment are
never counted as production payloads or supported JVM cells.

Public object construction, permanent/reclaimable facades, enum object transport,
custom exception projections, explicit object free and retention reconciliation
remain producer rejections until the corresponding P3 checkpoints pass. P3a must
reuse and extend the existing immutable proof modules and preserve source/class/
archive parity before any dependent adapter admits those capabilities. P4,
multi-target assembly and ARM64 P6b are still required by the active task.

Verification used focused selectors and child processes, strict Java/C compilation,
license audits and diff checks. No unfiltered compiler suite, hosted development
job, paid infrastructure, push, merge or release was used.
