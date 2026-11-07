<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC10 final acceptance and self-hosting handoff

2026-11-07. Acceptance audit of `76335baa`, following `e5cabbed`, on local
`codex/bdwgc-integration-plan`. Canonical checkout and both origin URLs were
verified; the initial tree was clean. GC10 changes documentation, library
comments and generated development references only. **GC0-GC10 integration is
accepted within the evidence bounds below**, under
[decision D329](DECISIONS.md#d329---accept-gc-integration-and-a-separate-self-hosting-feasibility-handoff).
No self-hosting, compiler-capacity, release or publication claim follows from
completing GC integration.

## Acceptance matrix

Each row accepts its implemented closure, not every future gate mentioned by
its initial report. The linked compact manifests bind exact source, tools,
configuration, commands, results and artifact hashes. Historical production
controls against the former heap are not current-source GC results.

| Milestone / source commits | Implemented contract and completion record | Correctness, refusal, compatibility and cost evidence |
| --- | --- | --- |
| GC0 `82c52506`, baseline `bb0fbab6` | [Inventory and frozen contracts](GC0_BASELINE_AND_CONTRACTS.md), [manifest](gc-evaluation/gc0-2026-11-04-manifest.json): allocation domains, 77 library destructors, analysis consumers, accounting, bridge API and supersession map. | Focused old-model safety/defer/artifact and isolated engine controls established the migration baseline. Manual-heap call/pool and C/LLVM timing are historical; the 5 GiB sparse virtual-span experiment is not resident-capacity evidence. No production GC was accepted here. |
| GC1 `e113c12f`, Linux `82b8e4cd` | [Engine preparation](GC1_ENGINE_PREPARATION.md), [native Linux follow-up](GC1_LINUX_X86_64_QUALIFICATION.md), [manifest](gc-evaluation/gc1-2026-11-04-manifest.json): pinned unmodified source, private adapter, explicit eager scheduling, allocator domains and redistribution. | Exhaustion/retries perform zero collections; full collection permits immediate reuse. Exact-input, environment, relocation and public-symbol negatives pass. macOS arm64 and physical Linux x86-64 isolated engines only. Engine-1's configuration is historical and cannot replace later SDKs. |
| GC2 `f506c026`, Linux `64c11fa3` | [Layouts](GC2_LAYOUTS_AND_QUALIFICATION.md), [Linux](GC2_LINUX_X86_64_QUALIFICATION.md), [manifest](gc-evaluation/gc2-2026-11-04-manifest.json): exact physical maps, all arrays/String tails/sidecars, zeroing and engine-2 USE_MARK_BYTES=1 overflow recovery. | C/LLVM layout agreement, inherited/generated/large maps, 1,024 types, pointer-shaped primitives, 120 actual stack-overflow recoveries, metadata/allocation failure and old-SDK refusal. Fully touched 128 MiB control is bounded; no compiler-capacity claim. Production remained manual. |
| GC3 `6860bdfc`, Linux `403742ae` | [Effects and statepoints](GC3_ROOTS_AND_STATEPOINTS.md), [Linux](GC3_LINUX_X86_64_QUALIFICATION.md), [manifest](gc-evaluation/gc3-2026-11-04-manifest.json): transitive MayCollect, bases, exact native/static roots and retained maps. | Original 148 O0-O3 native checks plus Linux map/unwind/protection controls; source/class/archive parity and corrupt PC/slot/function/boundary refusals. Actual O3 noncollecting bodies have no maintenance; collection-capable boundaries require spills. Original root ABI 1 was developmental; GC4 and production supersede it. |
| GC4 `777a26ff`, Linux `1b0e7674` | [Runtime lifetimes](GC4_RUNTIME_LIFETIMES.md), [Linux](GC4_LINUX_X86_64_QUALIFICATION.md), [manifest](gc-evaluation/gc4-2026-11-04-manifest.json): root ABI 2 / owner ABI 1, exception/trace/emergency graphs, partial construction and native acquisition rollback. | Thirty macOS native scenarios across O0-O3 and the separate Linux record cover real unwind, pending/deferred values, preparation/OOM, exact owners and native-resource balance. Call/pool machine-code controls retain D132/D133. This experimental completion did not yet qualify JNI or activate ordinary source GC. |
| GC5 `c76a3a75` | [Integrated cutover](GC5_IMPLEMENTATION.md), [manifest](gc-evaluation/gc5-integrated-2026-11-05-manifest.json): normal pipeline and required library use one managed heap, canonical collection, preserved general defer and counters; removed reclamation forms. | O0-O3 normal native sharing/cycles, partial publication, collecting construction/init/cleanup, D051, resource and pool cleanup, OOM/no-auto-GC, intrinsic spoofing, migration errors, class/archive parity and legacy-deallocation refusal. Engine-3 SDKs bind actual runtime/pass/root inputs. `3ff18cdd` and Linux `23190dcd` are incomplete pre-cutover checkpoints, not GC5 acceptance. |
| GC6 `3afea8f8` | [Foreign integration](GC6_IMPLEMENTATION.md), [manifest](gc-evaluation/gc6-2026-11-05-manifest.json): schema 2 / objects-gc-v1, roots 3 / owners 2, segment 1, independent anchors/registrations, exact JNI completion and private images. | Real Java 21 checked JNI and Java 23 critical/JNI at O0-O3: A/Java/A and A/B/A, suspended outer locals/registers/bases, listener replacement/active retirement, views/arrays/copy-back/failures and balanced globals. Actual production Mach-O/ELF exports and independent heaps pass. `a91a13cd` is the incomplete checkpoint. GC9 later adds Linux arm64. |
| GC7 `9d2edc20`, Linux follow-up `e16152d4` | [Implementation](GC7_IMPLEMENTATION.md), [dispositions](GC7_DISPOSITION.md), [manifest](gc-evaluation/gc7-verification.json): migrated consumers and deleted free/destructor AST/IR, heap-only passes, whole-program free refinement and old bridge projections. | Forty-eight-class structural retirement, paired shared graphs/final initialization/pool/native/foreign cases, reconstruction parity, IDE/generators, representative examples/projects and bridge load/input refusals. Real reference effects remain conservative. `e8ea963d` is incomplete; its [frozen reports/hashes](gc-evaluation/gc7-checkpoint/manifest.json) remain unchanged. |
| GC8 `04f09a36`, physical Linux `e16152d4` | [macOS performance/resident report](GC8_IMPLEMENTATION.md), [manifest](gc-evaluation/gc8-verification.json), [Linux report](GC7_GC8_LINUX_X86_64_QUALIFICATION.md), [manifest](gc-evaluation/gc7-gc8-linux-x86_64-verification.json): current kernels, exact retained graphs, capacity reuse and resource plateaus. | Actual O3 reference/rare-allocation, call/defer, pool, OrderBook and separate critical/JNI/callback/reentry paths; A/A and longer Linux same-path controls explain variation. Fully touched repeated graphs and native block controls separate collector/image stability from JVM reporter warmup. Deadline-terminated larger candidate remains incomplete. No matched pre-GC migration percentage or full compiler workload. |
| GC9 `e5cabbed`, completed `76335baa` | [Initial checkpoint](GC9_IMPLEMENTATION.md), [completion report](GC9_LINUX_X86_64_DISTRIBUTION.md), [completion manifest](gc-evaluation/gc9-native-distribution.json): three direct official targets, native/managed images, local host packages, relocated restricted IDKs and one assembled jar. | O0-O3 roots/owners/reentry, exact C ABI/layout/PIE/ASLR/protected-map/unwind/export audits, stale/missing SDK/pass/scalar metadata controls, private GCC support, local TLS/wget and binary/source/license payload audits. Initial Rosetta x86 evidence did not close the hardware gate; physical execution later did. The initial broad ARM matrix and final-profile focused rechecks retain distinct identities. |

GC0-GC9 gates are met within these closures. No prerequisite is closed merely by
renaming a checkpoint. Later current-source checks carry earlier contracts
forward, without asserting that every historical test was rerun at HEAD. GC10
adds no timing, capacity, platform or JDK measurement.

## Dependency, ABI and configuration identities

[packaging/bdwgc.json](../packaging/bdwgc.json) is the exact recipe:
BDWGC **8.2.12**, archive SHA-256
`42e5194ad06ab6ffb806c83eb99c03462b495d979cda782f3c72c08af833cd4e`;
LLVM/Clang/opt/llc **23.1.0**, Linux lld **23.1.0**, Java **21** bootstrap.
The static engine disables threads, parallel/incremental collection, ambient
stack/static/library discovery, startup precollection and Java finalization.
EAGER_SWEEP, STACK_NOT_SCANNED, DONT_ADD_BYTE_AT_END and USE_MARK_BYTES=1 are
required, with the complete configure/CFLAGS tuple in that recipe. No collector
source patch or process allocator interposition is used.

Native memory ABI is `ironwood-native-gc-1`, adapter `ironwood-gc-engine-3`,
layout version 1, LLVM stack-map version 3. Ordinary outputs use root ABI 2 /
owner ABI 1. Managed bridges use schema 2 / objects-gc-v1, root ABI 3 / owner
ABI 2 and foreign segment version 1. These protocols are not interchangeable.

The current exact profiles in
[bdwgc-qualified.properties](../packaging/bdwgc-qualified.properties) agree with
GC9's completion evidence:

| Profile | Configuration SHA-256 | Exact SDK settings / sysroot receipt SHA-256 |
| --- | --- | --- |
| macOS arm64 | `f6cdb6e12017ba8d203b7ab46c4f870585e4860c7f5de6ea88bad1c0d60b78ab` | `82165b15ee21419d4cffaba8895797391b908ac9499ba70f561f3e1bd16758d6` (Apple SDK 27.2) |
| Linux arm64 | `d8434872c7bd54595bc73e03ec9b245dc4c58748f328d2854b7e6ee030120912` | `2cbb493daf6f0c064e728723fb6341a42f6d32e6c5a09d0fe0fdd7e1c2aad1a4` |
| Linux x86-64 native host | `5e536f53ea4a5a695f0176a8133b4168443d09225521e13f0881ba1d4a3ef347` | `f1dcba2c3ef87eec42905fdd16aab8e399919c7b565e615aa027bf3da1dddb68` |
| Linux x86-64 cached IDK | `b147ad665f5c5639127bf333342034717793c4e9f53d52680597a39069e0b827` | `82573c10a11db164255da8c2d7fe5a254e46db1e8771866b84c6a1f63ad4a070` |

Historical profiles remain in their manifests: GC1 engine-1; GC2-GC4 engine-2
(macOS `6fd39c7b...`); GC5 engine-3 macOS `fa43f3f0...` / Linux `6b23fb6b...`;
GC6 `0ac9acaa...` / `9abad060...`; GC7 `728c45bd...` / `27c2bc0d...`;
physical GC8 Linux `97dad8a3...`. These abbreviations identify linked historical
records, not accepted current inputs. In particular the GC8 Linux SDK predates
GC9 reader changes and correctly fails current freshness. Configuration, runtime,
root-reader, pass, collector and support payload hashes all remain mandatory.
A matching version string alone cannot admit an SDK.

Linux support pins [GCC 16.2.0 binaries, source and recipes](../packaging/java-bridge-support.properties)
and the exact glibc 2.17 sysroot receipts above. Optional TLS distribution keeps
[OpenSSL 3.5.8 and the 2026-08-13 CA export](../packaging/tls-dependencies.properties)
with their SHA-256 pins, source and notices. GC9 verifies those copied closures;
GC10 changes none of these dependencies or their licenses.

## Supported execution and distribution bounds

| Target | Direct GC9 environment and tool scope | Accepted outputs and limits |
| --- | --- | --- |
| macOS arm64 | Apple M5, 32 GiB; macOS 27.0.1 (26A434); Apple SDK 27.2; LLVM 23.1.0; Oracle 21.0.1 bootstrap, Conda OpenJDK 21.0.10 IDK; matching Temurin 23.0.2+7 producer/Javadoc | Ordinary native executables, managed private images, host archive/IDK and assembled jar. Managed deployment floor 14.0 is encoded, not direct execution evidence on macOS 14. |
| Linux arm64 | Matching ARM hardware via Apple VZ, 6 CPUs / 8 GiB; Ubuntu 24.04.5, Linux 6.8.0-117-generic aarch64; cached Conda Java 21.0.10-internal and LLVM/lld 23.1.0; matching Temurin 23.0.2+7 producer/Javadoc | Same output categories with exact arm64 SDK/support inputs. Execution through the pinned glibc 2.17 loader on the current kernel does not qualify older kernels. No GC8 arm64 performance/resident matrix. |
| Linux x86-64 | Physical Xeon E-2288G, about 16 GiB; Ubuntu 18.04.6, Linux 4.15.0-188, glibc 2.27; cached Conda Java 21.0.10-internal and LLVM/lld 23.1.0; verified Temurin 23.0.2+7 producer/Javadoc | Same output categories, including bare-host relocated IDK and minimal JVM-only scratch deployment. Both exact x86 profiles execute directly; no Rosetta substitution. Actual pinned glibc 2.17 execution is scoped to the current kernel. |

GC9 records complete per-tool hashes, collector/adapter/root-reader/source
payloads, local package versions (`0.6.2-beta` and explicit
`0.6.2-gc9-local`), and assembled jar SHA-256
`dd99206401639c9900e1a91b2319ef44a5d70cc9ca36ce3b6d057a1e3d361c2e`.
Linux images travel with private GCC runtime support and corresponding source,
recipes and notices. Collector source/notices/configuration travel with linked
outputs; IDKs retain the verified nonmoving pass. No new package was built here.
The Java 21-25 launcher policy is broader than the GC qualification's Java 21
JNI / recorded Java 23 critical/JNI controls. Historical P6 JDK measurements do
not establish a full post-GC JDK matrix. arm64e remains unqualified.

## Accepted behavior and compatibility

Canonical `ironwood.lang.Memory.collect()` runs a full synchronous collection
on the caller, completing marking and sweeping before return. Allocation never
collects, including exhaustion/failure. There is one nonmoving managed heap
model, freely shared references, exact roots/layouts and conservative unknown
MayCollect effects. Unsupported/corrupt metadata fails before unsafe reclamation;
preparation OOM reclaims nothing. No finalizers, automatic/background collection,
conservative fallback, reference-write barriers, continuous trace maintenance
or steady-state GC administration was introduced.

Source free, deferred-free statements, destructors, implicit temporary destruction
and managed construction rollback are removed. Removed syntax, --unfreed,
--explain-rejected-free and @SuppressUnfreed have migration diagnostics.
Ordinary names/methods free and destructor remain legal. General defer retains
immediate receiver/argument capture, block LIFO, every-exit behavior, pending
roots and D051 primary/ordered-secondary failures. Resource close, pool return,
foreign anchor release and registration closure remain explicit independent acts.
Statics, pools, caches and physical reference slots can intentionally retain
graphs; collection does not scrub them or close native resources.

System.allocationCount() counts successful non-immortal language allocations,
including failed construction, excluding internal sidecars/native scratch/fixed
image objects. System.liveAllocationCount() is the post-collection language
count plus subsequent successful allocations, using existing 64-bit accounting.
It is not bytes or an immediate reachability census. Reads never collect/scan.

RootHandle.close() idempotently releases only its anchor; another independent
handle or native reference may retain the graph. Source close must be called
separately. ListenerRegistration owns its exact final bindings; reconciliation
runs on success/exception and newer nested bindings take precedence. Active
retirement delays JNI release until invocation completion. Closing a root does
not unregister listeners. JVM reachability alone is not an Ironwood native root.
Exact foreign records and saved native segments root supported A/Java/A and
A/Java/B/Java/A execution without unwinding Java frames. Each private image owns
its collector, roots, anchors, counters and transition chain.

Format-1 `.ironclass`/`.ironjar` containers still reconstruct embedded sources
and undergo full current semantic/IR validation. Format 1 is not a native ABI
compatibility promise. Old source can therefore receive current migration errors;
old native/bridge generations and missing/mixed schema, protocol, SDK, pass,
configuration or payload identities refuse before admitted execution. Raw shared
IR is not a bypass for managed admission. See [IDK](IDK.md#gc-native-inputs),
[compiler](COMPILER.md) and [bridge usage](JAVA_BRIDGE_USAGE.md).

## Performance, resident memory and resources

GC8's six-process timings and actual O3 callers establish no unexplained GC
maintenance in the accepted reference, rare-allocation, ordinary call/defer,
pool and OrderBook windows. Collection-capable spills and JNI segment capture
are accepted boundary work, measured separately from noncollecting scalar paths.
D132/D133 remain requirements. Shared hosts, timer resolution, A/A variation and
absence of a matched pre-GC Ironwood baseline preclude universal zero-overhead,
tail-latency or migration-percentage claims. GC9's cold reader/distribution fixes
do not remeasure or expand GC8 performance conclusions.

GC8 uses three processes per size, 14 phases, 4,096/16,384 nodes and fully touched
5.5/88 MiB payloads per graph. Exact survivors are 8,199/32,775 after clearing,
16,392/65,544 during intentional obsolete retention and six after final drop.
Collector capacity plateaus near 34.8/415.0 MB on macOS and 33.5/413.2 MB on
Linux. Largest large-process RSS high waters are 486,424,576 / 486,842,368 B.
The macOS 1,700-phase native control stabilizes image/resource measurements;
the Linux 500-phase control shows fixed collector/native allocator storage with
4 KiB RSS/PSS observation drift per block. RSS need not fall, and free-block
bytes exclude reusable cells in partial blocks. Native malloc observations are
partial, not a complete per-image/JVM/cgroup memory census.

Observed resident boundaries retain seven descriptors, sixteen fixed JNI
globals, zero anchors/facades and no post-collection sidecars. GC6 separately
qualifies listener replacement/retirement and acquisition/completion failure
balance. Collection performs none of those resource releases. The 16 GiB sampled
group RSS guard and 60-second native children bound evidence; samples can miss
short peaks/double-count shared pages and are not exact charged high waters.
The larger deadline-terminated GC8 run remains incomplete. These are workload
plateaus, not unbounded stability or full compiler-capacity evidence.

## Documentation audit and verification

| Current authority / consumers | Audit disposition |
| --- | --- |
| [README](../README.md), [AGENTS](../AGENTS.md), [roadmap](ROADMAP.md) | Final acceptance and qualification limits linked; historical milestone/rollback language separated from current readiness. |
| [Memory](MEMORY.md), [usage](MEMORY_MANAGEMENT.md), [language](LANGUAGE.md), [specification](LANGUAGE_SPECS.md), [object model](OBJECT_MODEL.md), [generics](GENERICS.md) | Explicit-only collection, failure, exact roots, defer, counters and one model agree with source. Removed blanket bridge/Linux arm64 gates corrected. |
| [Java differences](DIFFERENCES_FROM_JAVA.md), [comparison](IRONWOOD_VS_JAVA.md), [exclusions](JAVA_EXCLUSIONS.md) | Features 10, 11, 39, 55, 57, 61, 72, 73 and 77 retain their matrix/prose/link/status alignment. Totals remain 67 supported, nine alternatives, 32 excluded, zero pending across 108 entries. Corrected the exclusions guide's no-GC row, allocated-child/fresh-result wording, trace lifetime and Feature 77's old interning rationale. No next feature selected. |
| [Compiler](COMPILER.md), [runtime](../runtime/README.md), [optimizations](IMPORTANT_OPTIMIZATIONS.md), [IDK](IDK.md), [bridge overview](JAVA_BRIDGE.md) and [producer](JAVA_BRIDGE_USAGE.md) | Ordinary/bridge ABIs, full source reconstruction, private images and input checks agree with implementation. Historical GC3/GC4 descriptions remain labeled. Current bridge/support packaging claims retain GC9 scope. |
| [Stdlib](STDLIB.md), [roadmap](STDLIB_ROADMAP.md), [porting](OPENJDK_PORTING.md), [examples](../examples/README.md), [projects](../projects/README.md), [API reference](api/README.md) | Removed old destruction/ownership guidance and stale captured-alias catalog wording; corrected managed/network lifetime comments. Development API regenerated through update-irondocs.sh, including prior stale ArrayList/DirectoryStream/SocketImplFactory text. Released references remain frozen. No executable source or snippet tokens changed. |
| Self-hosting and old design plans | Current prerequisites map retired proof machinery to GC and retain allocation, retention, value, resource and algorithmic concerns. Legacy plans explicitly historical; dead current-code/contract links corrected. Frozen decisions, qualification reports, manifests and rollback evidence preserved. |

Source inspection included parser/Main/BridgeProducerCommand migration errors,
GcEffects and GcManagedHeapVerifier, GcDependency/BridgeGcBuildInputs, runtime
allocation/counters, iw_gc_collect and generated anchor/registration/transition
implementations. No implementation correction was needed. The [compact GC10 verification manifest](gc-evaluation/gc10-verification.json)
records final checks: local links/anchors, status/identity assertions,
source-token equality, generated
API consistency, frozen evidence hashes, text/fence policy, licenses, tracked
files and git diff --check. The focused audit checks 80 documents, 2,958 local
links (1,227 Markdown fragments), all 37 comment-only source edits, unchanged
executable tokens in 704 Java-fenced snippets and 2,669 unchanged historical/
released files, including both GC7 checkpoint hashes.
A separate current-guidance pass checks 78 documents and 916 local links;
GC9 initial-manifest/current-implementation hashes match their completion record.
Compiler/native suites, benchmarks, SDK rebuilds,
GC9 packaging, remote/hosted work and self-hosting were not rerun for this audit.

## Remaining unsupported behavior

arm64e, Windows, musl, 32-bit targets and other unmatched configurations are not
qualified. Async callbacks, concurrent image calls, raw cross-image managed
references, unknown retaining foreign borrows, unloading and unsupported export
shapes remain refused or outside the explicit externally serialized contract.
No language threads, finalization, weak-reference API, automatic collection,
JVM class loading, new ownership syntax or owning-container alternative is added.
Older OS/JDK/toolchain and broader workload claims require their own evidence.

## Self-hosting handoff

The proposed next task is the separately authorized
[bounded Java-to-I1-to-I2 checkpoint](SELF_HOSTING_PLAN.md#proposed-separately-authorized-java-to-i1-to-i2-checkpoint),
with [preparation reconciliation](BEFORE_SELF_HOSTING_PLAN.md) and
[ownership-alternative disposition](OWNERSHIP_COMPOSITION_PLAN.md).
At `76335baa` the fixed Git tree identities are:

| Input family | Git tree identity |
| --- | --- |
| compiler/src/main/java (430 files) | `b8a68701c8c4c7c1392eeb20f807013d1afff4ca` |
| compiler/src/main/ironwood (185 files, incomplete port) | `6942947d55f10921cd3c0ea896552a711895c50f` |
| runtime | `c138ad542c4d16b3d600827904df404e8eec7f9e` |
| stdlib/src/main/ironwood (255 files, before GC10 comment edits) | `6e0e4a4e521dda21330073ec8a61de4aad54107a` |
| packaging | `518f406b93c5b2a8ed4d4e6eec8f20c2ea6ea7da` |

Freeze a new exact port closure before execution, including current generated
models/resources and this comment/documentation checkpoint, rather than reusing
the discarded 711-input port. Plan explicit phase collections after last consumers,
account separately for published and temporary graphs, and retain the 16 GiB
guard, bounded time and diagnostic/artifact evidence. Require genuine independent
Java/I1/I2 behavior and artifact comparisons before peripheral migration; I3 and
reproducibility follow only after I2 succeeds. Guard stops, unexplained retention,
nonconvergence or mismatches stop the task for reassessment. GC does not prove
that the historical I2 failure is solved. D008 keeps Java authoritative.
