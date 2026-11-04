<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC implementation evaluation

Date: 2026-11-04. Status: local experimental selection, not an implemented or
accepted change to Ironwood's current memory model.

## Conclusion

Select **BDWGC 8.2.12 as the engine for the first Ironwood GC implementation,
using compiler-provided native roots and exact heap layout metadata**. Use full,
nonmoving, nongenerational collection, eager sweeping, and collection on the
calling thread only when explicitly requested.

This selection depends on the compiler-directed profile below. Substituting
ordinary `GC_malloc` calls and ambient conservative root scanning does not meet
the intended contract. The final experiments show a viable path beyond that
default configuration, including optimized native caller roots, type-specific
field maps and critical-path timings close to the allocation-only control.

MMTk MarkSweep is the tested fallback if complete Ironwood layout integration
or the pinned BDWGC configuration cannot be qualified. Nonmoving Immix provided
good throughput but needed additional storage in the mixed-lifetime reuse case.
Reference counting is not selected: reference changes can be frequent even when
allocations are rare, and deferred destruction does not remove retain/release
bookkeeping that the compiler cannot eliminate.

These experiments select an implementation direction. They do not qualify every
Ironwood feature, prove self-hosting within 16 GiB, or enable GC in the compiler.
Current `free`, destructor, and safety contracts remain authoritative until an
explicitly specified migration is implemented.

## Why not simply reuse Java's collector?

Java does not mandate a particular collection algorithm. The JVM specification
leaves storage-management techniques to the implementation. HotSpot offers
several collectors with different goals; the language and collector are separate
choices. [JVM heap specification](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-2.html#jvms-2.5.3).

HotSpot uses precise information about objects and references. Its collector code
depends on HotSpot object representations, runtime services, compiler metadata
and execution protocols. Reading its Serial and G1 headers confirms those
dependencies; it is not a standalone C library for an unrelated LLVM compiler.
The inspected HotSpot implementation files are GPLv2 without an express
Classpath Exception. No implementation was copied or translated in this work.
[HotSpot architecture](https://www.oracle.com/java/technologies/whitepaper.html),
[Serial GC header](https://github.com/openjdk/jdk21u/blob/master/src/hotspot/share/gc/serial/markSweep.hpp).

BDWGC's absence from HotSpot is not evidence that Java-shaped native programs
cannot use it. The historical GNU Java compiler used BDWGC. GraalVM Native Image
also demonstrates that native Java can use collectors adapted to its own runtime,
including Serial and Epsilon. Those are useful architectural precedents, not
drop-in Ironwood libraries.
[BDWGC users](https://www.hboehm.info/gc/),
[GraalVM memory management](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/).

Ironwood should adopt precise compiler knowledge and familiar resource handling
where useful. Its single application thread and explicitly scheduled collection
do not require HotSpot's generational or concurrent collection machinery.

## Scope and method

All work ran locally on `machost`, macOS arm64, with LLVM 23.1.0, HotSpot
25.0.4.1 and task-local Rust 1.99.1. Inputs were BDWGC 8.2.12 and MMTk revision
`d154f4f0dcca27439b920165ce5c948ca18aef24`. Download hashes, evidence hashes,
source hashes and compact results are in
[`gc-evaluation/2026-11-04-manifest.json`](gc-evaluation/2026-11-04-manifest.json).

The [checked-in reproducer](../scripts/gc-evaluation/README.md) builds independent
C/LLVM/Java fixtures. It changes no compiler, runtime, standard-library or
ownership-analysis behavior. Dependency source, archives and binaries remain in
ignored workspace directories with their original licenses. The MMTk binding
adapts its published DummyVM skeletons; all other fixtures are original.

Qualification pairs live references with dead references, real reference fields
with numeric fields, and supplied native roots with deliberately omitted roots.
Additional controls cover cycles, allocation exhaustion, eager/lazy sweeping,
mixed lifetimes and a virtual allocation larger than 4 GiB. The pre-change review
preserved existing safety and D132/D133 contracts because production code is not
modified.

Timings use warmup, checksums, five separate processes per case and 31 batches
of five million operations per process. Final candidate/control cases were
interleaved in deterministic random order. The pool contains 4096 64-byte
objects. Rare allocation means one allocation per 4096 operations. No collection
occurred inside any timed throughput window.

Jobs used bounded counts or fixed heaps, a sampled 16 GiB RSS guard and
60-second timeouts. This is not the earlier self-hosting charged-memory monitor.
Short jobs can finish between RSS samples. The 5 GiB allocation test touches
only the first and last payload pages and tests metadata/addressing, not 5 GiB
of resident memory. No large self-compilation or resident-capacity claim follows.

## Findings

### 1. Explicit scheduling requires a stronger policy than a heuristic switch

In the default BDWGC experiment, disabling the ordinary collection heuristic
still allowed 209 collections outside the explicit wrapper when exhaustion
retries were enabled. Global disabling prevented collection and returned null
at the configured heap limit.

The selected profile also disables startup precollection. With a 32 MiB heap and
two exhaustion retries, it returned null after 521,408 fixed-size allocations
with zero automatic collections. One explicit collection enabled another
200,000 allocations, with no additional deferred reclaimed bytes. Production
allocation failure must preserve Ironwood's allocation-failure handling instead
of silently initiating collection.
[BDWGC controls](https://github.com/bdwgc/bdwgc/blob/v8.2.12/include/gc.h).

### 2. Eager sweeping keeps reclamation inside collection

The lazy-sweep control reclaimed approximately 40,000,000 allocator bytes during
later allocations. The eager build reclaimed 75,000,000 allocator bytes before
returning from collection and zero during the following allocation phase.
Those counters describe allocator size classes, not exact source-object payload.
The additional policy test confirmed no deferred reclamation in the selected
compiler-directed profile. Pauses may become longer; that matches the requested
tradeoff. [Eager-sweep implementation](https://github.com/bdwgc/bdwgc/blob/v8.2.12/reclaim.c#L678).

### 3. Conservative root discovery can retain numeric values

A deliberately registered conservative root region containing pointer-shaped
integers retained about 40 MB of allocated blocks. Clearing it reduced the
occupied-block measurement to 8 KiB. This is a controlled counterexample, not an
estimate of accidental retention in real Ironwood programs.

Typed heap allocation reclaimed 24,000,000 allocator bytes that an untyped
numeric-field control retained. Heap block occupancy alone did not expose the
difference because live neighbors kept those blocks allocated.

The selected profile uses compiler roots instead of native word scanning and
field maps instead of treating every heap word as a possible reference. It
preserved all 300,000 actual holders and reclaimed their numeric-only targets.
It also preserved a child held by a real field, then reclaimed it after that
field was cleared.

### 4. Optimized native roots work with both engines

The LLVM fixture uses `llvm-as -> opt -O3 -> rewrite-statepoints-for-gc -> llc`,
the system unwinder, and LLVM's emitted static stack maps. At collection it
walked 17 native caller frames and supplied 17 base roots. The selected BDWGC
profile and both MMTk plans preserved them through 30 explicit collections.
The fixture also accesses object fields through derived addresses after calls.

The negative control omitted those roots, verified all 17 objects were dead,
and exited before resuming code that would dereference reclaimed objects.
This demonstrates that the positive result depends on root metadata.

There is no shadow-stack registration on function entry or exit. Statepoints do
require spills at collection-capable calls. A production implementation needs a
sound transitive `mayCollect` analysis so ordinary noncollecting calls retain
their current code generation. The stack walker executes only during collection.

This is manually authored LLVM input and a fixture-specific Mach-O reader, not
Ironwood frontend integration. It proves the mechanism on one target. Exception
paths, all reference forms, globals, foreign roots and the other supported
targets remain integration gates.
[LLVM statepoints](https://llvm.org/docs/Statepoints.html).

### 5. Type metadata can avoid a footer and a kind per class

The default typed BDWGC interface adds descriptor storage to each allocation.
The final fixed-layout binding used a bitmap allocation kind without that
footer. A separate test used one per-object collector kind, the existing
first-word type-pointer convention and 1,024 immutable type descriptors with
different field maps. All 1,024 reference children survived, while all 1,024
numeric-only targets were reclaimed.

This establishes an efficient route for ordinary classes. Variable-length arrays,
large pointer maps, collector mark-stack recovery, partially initialized objects
and failed constructors still require focused tests before adoption. Metadata
construction must not silently fall back to conservative layouts when an exact
contract cannot be established.
[BDWGC advanced descriptor API](https://github.com/bdwgc/bdwgc/blob/v8.2.12/include/gc_mark.h).

### 6. Native throughput is promising, with layout-sensitive results

Final paired results, median of five process medians:

| Native configuration | Pooled rewrites, ns/operation | Allocation once per 4096 operations, ns/operation |
| --- | ---: | ---: |
| Allocation-only `calloc` control | 1.125 | 1.023 |
| Compiler-directed BDWGC, large configuration | 1.107 | 1.014 |

The small differences overlap process variability; they do not establish that GC
is faster. They support negligible overhead for this synthetic workload.

Earlier exploratory cases measured about 3.27 ns/operation for the default typed
BDWGC layout, 1.36 for MMTk MarkSweep and 1.07 for nonmoving Immix. The pooled
mutation function has identical 108-byte machine code across the native
executables: SHA-256
`846e8b129ff65ebe78a9c0c0c6c73885de9b83b3fc9bcff507b88c6144b0f5ef`.
It contains no collector calls. Allocation layout and locality therefore matter
even when no garbage is being produced. The footer-free compiler-directed
profile addressed that concern in the final paired comparison.

The standalone allocation timer has approximately 42 ns granularity. Many
individual medians were below resolution. These are not zero-cost allocations
and no production tail-latency guarantee is made.

### 7. Reclamation granularity matters for mixed lifetimes

After retaining alternate objects and collecting their dead neighbors, allocating
300,000 replacement objects required no additional heap blocks in BDWGC or MMTk
MarkSweep. Nonmoving Immix required 20,152,320 additional bytes in its allocator
accounting. A live neighbor can prevent reuse of a partially occupied Immix line.
This is fragmentation, even when an object is correctly recognized as dead.

BDWGC's compiler-directed profile also reused those holes without growth.
Repeated dead-cycle phases reached a stable baseline in all tested collectors.
Given the previous self-hosting memory pressure and the accepted long collection
pauses, object-level eager reclamation is a useful property.

### 8. Java measurements do not imply one universally best collector

Within HotSpot, the synthetic pooled-rewrite cases measured approximately 1.31
ns/operation for Epsilon, 1.44 for Serial and 1.56 for G1. Rare-allocation cases
were approximately 1.11, 1.16 and 1.31 respectively. There were no measured-window
collections. This deliberately reference-heavy microbenchmark can expose
differences that are negligible in a larger application. It does not contradict
the maintainer's application measurements, and Java/native timing is not used
as a language-performance comparison.

## Selected profile and implementation gates

Pin the collector source and use the evaluated single-thread configuration:

- Eager sweeping; incremental collection, parallel marking and collector threads
  disabled. Disable startup precollection and keep collection disabled outside
  the explicit, balanced collection wrapper.
- Compiler-provided native and static roots; disable ambient stack/register,
  main-static-data and dynamic-library root scanning. Every managed reference
  retained by native code must have a supported root path.
- Exact field metadata, using existing type headers where appropriate. Avoid a
  descriptor footer on every ordinary object.
- Large-heap configuration. Keep reclaimed storage available for reuse; returning
  pages to the OS is not the meaning of successful collection.
- Omit one-past-end padding only because compiler roots preserve allocation
  bases. That is not a safe general configuration for arbitrary foreign C code.

`STACK_NOT_SCANNED` is an upstream source configuration hook rather than a
general public root-map API. The source version must stay pinned, and upgrades
must re-audit its behavior. Compiler-directed configurations cannot allow an
unregistered managed pointer to survive solely in arbitrary foreign storage.
This is an explicit integration requirement, not a promise that a library call
automatically makes any LLVM program GC-correct.

The next implementation milestone should establish complete root and layout
coverage in the existing typed-IR/backend pipeline, including arrays, exceptions,
construction, statics and foreign handles. Pair accepted live-root cases with
reclamation controls, inspect optimized noncollecting paths and preserve the
allocation-failure policy. Then migrate managed-object `free`, deferred free
and destructors while preserving deterministic resource cleanup and general
`defer`. Test realistic compiler graphs before another self-hosting attempt.

The detailed implementation sequence and validation gates are in
[`BDWGC_INTEGRATION_PLAN.md`](BDWGC_INTEGRATION_PLAN.md).

No automatic finalizers or user destructors should run during collection.
Native resources still require explicit `close()` and failure cleanup. Pools
must clear references they no longer intend to retain; tracing correctly keeps
objects that remain reachable through a pool or an inactive backing slot.

All source, results and commits remain local. No GitHub jobs or pushes were used.
