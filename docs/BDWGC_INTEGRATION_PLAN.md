<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Explicit GC integration and memory-model migration

Date: 2026-11-04. Status: GC0 through GC3 complete for their documented experimental
closures; production GC remains disabled.
Current compiler behavior and the existing memory specification remain
authoritative until the cutover.

The selected direction is BDWGC 8.2.12 with compiler-provided roots and exact
layout metadata, synchronous `Memory.collect()`, removal of source `free` and
destructors, and preservation of general `defer`. This follows the
[local evaluation](GC_EVALUATION.md) and its
[evidence manifest](gc-evaluation/2026-11-04-manifest.json).

## 1. Intended result and boundaries

Ordinary objects and arrays have freely shared, Java-shaped references. Their
storage remains valid while reachable from the compiler/runtime's strong roots.
Unreachable managed storage becomes reusable during an explicit collection.
Source code no longer needs an ownership proof to reclaim ordinary heap graphs.

The completed implementation must provide all of the following:

- `ironwood.lang.Memory.collect()` is a public static `void` operation. It runs
  a full collection on the calling application thread and completes marking and
  sweeping before returning. It is an operation, not a suggestion to the engine.
- Ordinary allocation, allocation failure, loop back edges, resource closure,
  class initialization and process exit do not introduce implicit collections.
  User code may explicitly call `Memory.collect()` from an initializer or cleanup.
- No collector worker threads, incremental work, reference counters, remembered
  sets, reference-write barriers, GC polling or continuous shadow-stack updates.
- The compiler identifies references precisely in objects, arrays, statics,
  native callers, deferred captures, exceptions and supported foreign roots.
  No ambient stack/register or dynamic-library word scanning is a fallback.
- Source `free`, `defer free`, source destructors and implicit temporary
  destruction disappear. Collection never invokes user cleanup or finalizers.
- General `defer` retains its existing capture, exit and exception behavior.
  External resources still use explicit `close()`, `finally` or `defer`.
- The Java 21 bootstrap, LLVM 23 pipeline, closed-world linking, existing source
  language shape and native executable/library outputs remain intact.

This is one managed-heap model. Do not ship a permanent manual/GC mode pair or a
flag that turns old `free` statements into no-ops. Internal migration fixtures
may use an isolated development configuration before the integrated cutover.

The work does not add automatic finalization, weak references, automatic Java
Cleaner callbacks, a public raw allocator, regions, ownership syntax, a JVM or
language threads. Try-with-resources is useful future work but is not a
prerequisite: existing general `defer` and `finally` already provide cleanup.
Do not restart compiler self-hosting as a side effect of this implementation.

## 2. What the experiments established

The selected compiler-directed BDWGC profile passed native caller-root tests,
explicit scheduling and exhaustion controls, dead-cycle collection, mixed-lifetime
hole reuse and type-specific reference maps. A manually authored LLVM fixture
preserved 17 caller roots through 30 collections, and an omitted-root control
confirmed that metadata was responsible. One collector kind supported 1,024
type descriptors. The final synthetic throughput comparison was close to its
allocation-only control and used identical pooled-mutation machine code.

Those results justify selecting the engine. They do not qualify the Ironwood
frontend, all heap layouts, exceptions, JNI boundaries or Linux targets. The
5 GiB experiment tested a virtual span with two explicitly touched payload pages;
it did not test that much resident memory. Individual allocation timing was
partly below the host timer's resolution. Do not promote these results into a
zero-overhead, exact tail-latency or self-hosting-capacity promise.

## 3. Existing implementation and affected contracts

The current allocation boundary in `runtime/src/ironwood_runtime.c` uses zeroing
`calloc`, installs the first-word type pointer and counts successful language
allocations. Arrays, runtime-created Strings, exception wrappers and exception
metadata use different representations and lifetimes. There is no existing
`ironwood.lang.Memory` class.

`Throwable.traceState` currently stores a native metadata pointer in a `long`.
That metadata contains primary/secondary language references and native trace
storage. A plain source-field bitmap would miss these references. Some cleanup
currently depends on a source destructor or generated rollback. The GC migration
must handle this before reclamation is enabled.

`ArrayList.clear()` currently only resets `currentSize`, and pooled map entries
can retain old keys and values. Those are real references to a tracing collector.
The Java Bridge additionally retains native objects through generated facades,
native records, callback frames and JNI references. It already has an explicit
single-threaded or externally serialized caller contract without runtime thread
checks. GC does not remove that contract or make a JVM heap visible to BDWGC.

| Area | Existing implementation anchors | Required change |
| --- | --- | --- |
| Syntax and semantics | `parser/Parser.java`, `ast/FreeStatement.java`, `ast/DeferredFreeStatement.java`, `ast/DestructorDeclaration.java`, `semantic/SemanticAnalyzer.java` | Remove reclamation syntax and heap-only restrictions; preserve typed language and cleanup semantics. |
| Shared analysis | `semantic/EscapeSummaryAnalyzer.java`, `semantic/UnfreedAllocationTracker.java`, rejected-free explanation classes | Separate remaining optimization/resource consumers from obsolete heap-reclamation proof work. |
| Typed IR and linking | `ir/IrClass.java`, `ir/IrFreeInstruction.java`, `NativeLinkPipeline.java`, `ClosedWorldPruner` and specialization passes | Add explicit collection/effects and layout descriptions; remove destruction entries after all consumers migrate. |
| LLVM and native backend | `backend/LlvmEmitter.java`, `backend/NativeBackend.java`, `backend/RuntimeLibrary.java`, `backend/LlvmToolchain.java` | Managed pointer identity, statepoints, retained root sections, target readers and collector linkage. |
| Runtime | `runtime/src/ironwood_runtime.c` | Collector adapter, allocator domains, exact native roots, exception metadata and accounting. |
| Library | `stdlib/src/main/ironwood/ironwood/lang`, `ironwood/ds`, `ironwood/pool`, I/O, networking and bridge packages | GC-managed storage, explicit resource cleanup, obsolete-reference clearing and API migration. |
| Bridge | `bridge/Bridge*`, `semantic/Bridge*`, `backend/LlvmByteViewEmitter.java` | Foreign root lifetimes, GC-capable shims, callbacks, byte views and generated API changes. |
| Artifacts and distribution | `IronClass.java`, `SourceSetLoader.java`, bridge archive producers, packaging scripts | Revalidate embedded sources; version native GC ABI/generation and deliver collector source/licenses. |

Compiler paths in this table are relative to
`compiler/src/main/java/ironwood/compiler/`. The implementation inventory must
also find compiler-generated and standard-library helper frees; searching only
source `free` statements is insufficient.

Before changing shared machinery, apply the
[pre-change review](POOL_RELEASE_HELPER_REGRESSION.md#lessons-for-future-changes).
Map each analysis result to its consumers and record a disposition: remove,
retain for optimization, retain for a resource/protocol contract, or replace
with a GC effect/root fact. Unknown effects must remain conservative.

## 4. Required memory and collection contract

### 4.1 Managed storage and allocation failure

All ordinary objects, arrays, compiler-created heap temporaries and managed
memory-only sidecars use the collector. Preserve zero/default initialization,
array bounds and length checks, allocation identity and reference equality.
Immortal literals, descriptors and emergency objects keep their existing
separate representation and accounting policy.

Allocation never enables collection to recover from exhaustion. Preserve the
existing catchable, immortal `OutOfMemoryError` path, including bounded emergency
state and deterministic failure on recursive exhaustion. Collection scratch and
collector-native metadata need separate budget/headroom accounting.

`Memory.collect()` prepares roots and required scratch while collection remains
disabled, enables the engine only for the full collection, then restores the
disabled state before returning. Do not run managed allocation or user callbacks
inside that enabled window. Collection setup failure must not reclaim managed
storage and must report through the established failure path. Unsupported/corrupt
root metadata must fail before reclaiming objects, never continue with partial
root coverage. Engine-internal unrecoverable failure is not successful collection.

Reclaimed means available for reuse. It does not promise lower RSS, immediate
OS unmapping or reclamation of objects still held by statics, pools, native
anchors or compiler-live variables. No library should insert collections merely
to recover an old live-count assertion or control allocation growth.

### 4.2 Collector configuration and interface

Pin the release archive and its evaluated SHA-256
`42e5194ad06ab6ffb806c83eb99c03462b495d979cda782f3c72c08af833cd4e`.
Keep configuration and an adapter ABI identifier in dependency/build manifests.

Use the evaluated profile: static library; threads, parallel marking, incremental
collection and ambient root discovery disabled; eager sweeping; large-heap
configuration; startup precollection disabled; global collection disabled
between explicit operations. Preserve the pinned configuration manifest instead
of retyping an approximate set of options. GC2 adds USE_MARK_BYTES to preserve exact descriptors during
mark-stack overflow recovery; the GC1 configuration alone is not sufficient
for that path. Rebuild and requalify SDKs after this identity change.

`STACK_NOT_SCANNED` is a source configuration hook, not a public stack-map API.
Audit its pinned implementation and public descriptor interfaces. Upgrades must
repeat the relevant qualification. `DONT_ADD_BYTE_AT_END` is valid only with the
compiler's allocation-base preservation contract; arbitrary one-past-end foreign
pointers must not become the only evidence of an object's lifetime.

Keep collector internals behind a narrow original Ironwood C adapter. Do not
replace process `malloc`, let application code call `GC_enable`, or route a
collector allocation to libc `free`/`realloc`. Runtime-owned transient native
storage may still use matched native allocation/deallocation internally.

Complete the exact license/notice and redistribution audit before linking the
engine into distributed outputs. Preserve unmodified dependency source and
required notices through prepared SDKs, IDKs, native libraries and bridge jars.
No HotSpot implementation is imported.

### 4.3 Heap layouts and initialization

Extend compiler-owned layout/type metadata and its C/LLVM representations
together. Reuse the existing first-word type pointer. Ordinary object allocation
must not need a descriptor footer, a registry entry per field write, or one
collector kind per source class.

Generate exact maps from resolved physical layouts, including inherited fields,
generic specializations, enum bodies, nested enclosing references, captured
locals, compiler-generated holders and runtime-reserved reference fields. Numeric
values must not be traced because they resemble addresses. Preserve complete
allocation zeroing and install the correct dynamic descriptor before source code
or callbacks can collect, including during superclass construction.

Qualify each layout family:

- Fixed-size ordinary classes, including classes with no reference fields and
  maps larger than a machine-word bitmap.
- Reference arrays and multidimensional arrays: scan all physically stored
  reference elements. Array length and element-kind integers are not roots.
- Primitive arrays and inline String payloads: no scanning of numeric contents;
  retain required zero initialization even when a pointer-free allocator does not
  provide it automatically.
- Memory-only runtime blocks containing managed references versus pointer-free
  trace/text/buffer data. Their allocation kinds and accounting must be distinct.
- Free-list cells, incomplete initialization and mark-stack overflow/recovery.
  Exactness cannot silently degrade to conservative descriptors on metadata OOM.

Do not skip inactive collection slots based on a logical size unless every alias
and observation is covered by an accepted compiler proof. The initial design
traces physical references and fixes retention in collection code instead.

### 4.4 Collection effects, native roots and optimized code

Add a compiler-owned `MayCollect` effect. `Memory.collect()` has that effect.
Compute its transitive closure over resolved direct/virtual/interface calls,
constructors, class-initialization prerequisites, closures, deferred cleanup,
exception paths and supported callbacks. Unknown native callbacks are not
automatically noncollecting. Do not add a user annotation that bypasses proof.

Recompute or preserve sound effects after cloning, specialization, pruning and
devirtualization. An initialized-type specialization may remove an initialization
route that can collect; that must be reflected in the final effect graph.

Functions and calls proved noncollecting retain ordinary code generation. Add no
GC back-edge polls or entry/exit registration. Collection-capable calls may need
root spills even when a particular invocation does not collect. This is a real
potential cost: keep collection at phase boundaries and measure affected paths.

Represent managed references distinctly in LLVM so optimization and the late
statepoint rewrite retain bases for derived pointers. Describe stack regions
holding references as well as SSA values; an unpromoted alloca is not covered
merely by adding an attribute to the function. Apply root generation in every
optimization mode. Review pass ordering with the existing post-optimization
trace-metadata injection so neither call/root changes nor source attribution
become stale.

At collection, use immutable stack maps and unwind information to discover
active managed callers. Preserve needed unwind information without forcing frame
pointer maintenance throughout unrelated code. Support the actual target's
return PCs, ASLR/PIE, register conventions and Mach-O/ELF section retention.
Do not ship the fixture-specific reader as production code. Validate the map
format, counts, relocations and descriptors at build/link or image startup.

Native/static roots include managed statics, class-initialization state,
in-flight returns/throws, active cleanup captures, runtime exception state and
supported foreign anchors. Immutable tables identify static slots. Reusable
unmanaged scratch captures dynamic stack roots only during collection; root
preparation must not depend on allocating another managed object to collect.
Unregister or clear temporary roots after collection so scratch does not retain
an old graph. Collector administration must not appear on ordinary method calls.

### 4.5 Runtime sidecars, exceptions and external resources

Replace pointer-bearing integer tricks such as `Throwable.traceState` with
explicitly described internal references and separate primitive state. Managed
exception metadata, secondary-exception links and memory-only trace storage
must follow the Throwable graph without requiring a source destructor or a
per-object finalizer. Avoid a permanently strong global side table that roots
every exception it has ever observed.

Native unwind wrappers must retain their language exception while an exception
is in flight, including collection in `finally`, a deferred call or nested
failure. Add/remove roots at the existing throw/wrapper lifecycle, not at every
ordinary call. Preserve primary/secondary ordering, rethrow identity and existing
bounded emergency behavior. Clear emergency references when their active use ends.

Class destructors currently mix memory reclamation with other cleanup. Classify
each body and generated rollback:

| Existing action | GC-era disposition |
| --- | --- |
| Free a managed object, array, backing buffer or child graph | Remove; retain the correct references until their last use. |
| Release a file/socket/TLS/JNI/native-resource handle | Move to explicit resource closure or failure cleanup. |
| Return an item to a pool | Keep an explicit pool operation, often scheduled with general `defer`. |
| Release memory-only native metadata | Move it to a described GC-owned block when its lifetime follows managed objects. |
| Release transient native call storage | Keep matched internal native cleanup on all exits. |

Failed constructors no longer recursively destroy their managed fields. A
published partially constructed object must remain a valid, zero-initialized
managed object after failure. Explicitly close native resources acquired before
failure and preserve the original exception. Audit old construction-publication
restrictions: remove heap-reclamation-only restrictions, while retaining actual
initialization, native lifetime and protocol requirements. Do not simply delete
all construction analysis.

GC protects managed memory, not a native buffer already released by `close()`.
Borrowed byte views, native slices and resource-backed facades still need safe
closure/invalidation contracts. No collection may run user `close()` methods,
source destructors, Java callbacks or object resurrection hooks.

### 4.6 General defer remains part of the language

Preserve immediate, once-only operand evaluation; captured receiver/argument
identity after local reassignment; block-scoped LIFO execution; independent
cleanup copies for every exit; and D051 primary/secondary exception ordering.
Captured references are roots until their action executes or is no longer pending.
Collection inside an earlier cleanup must not collect a later action's captures.

Continue lowering statically known actions into typed control flow. Do not add a
heap-allocated action list, closure allocation, registration helper or TLS access
to each `defer`. Pending-free binding restrictions disappear with `defer free`;
they must not accidentally become new restrictions on ordinary captured calls.

### 4.7 Pools and obsolete references

Ordinary reference collection removal/clear paths should null obsolete slots and
retired map-entry keys, values and links. Primitive-only containers retain their
cheap clear behavior. Pool-owned reusable objects stay reachable intentionally;
returning them to a pool must not free them or automatically close their resources.

Changing an O(1) clear into reference scrubbing can affect a measured hot path.
Where needed, propose a distinctly named `resetForReuse()` with explicit reference
retention, paired with cold `clearUnusedReferences()` before collection. A cold
helper may scan inactive capacity, avoiding new per-insertion high-water or
registry bookkeeping. Scope such helpers to actual consumers and review their
behavioral contract before implementation. Ordinary users should get the familiar
reference-releasing operation, and fast-reset users must have a tested way to
discard retained graphs. Never hide library callbacks inside `Memory.collect()`.

### 4.8 Foreign lifetimes and Java Bridge

BDWGC cannot discover reachability in the JVM heap. Native objects exported to
Java need an explicit native root until the foreign lifetime ends. Existing
single-thread/external-serialization obligations remain; add no runtime thread
checks, synchronization or TLS operations to ordinary scalar calls.

GC0 fixes the replacement for generated owning-facade `free()` as an explicit
`AutoCloseable` bridge lifetime handle, separate from source object methods.
Use the generated `RootHandle<T>`, factories and registration API in the
[frozen GC0 contract](GC0_BASELINE_AND_CONTRACTS.md#generated-java-lifetime-api).
Its `close()` releases the foreign anchor; a source-defined `close()` keeps its
source resource semantics. Releasing
an anchor is not immediate object reclamation and must not destroy a graph still
reachable from Ironwood or another valid anchor. Dropping a foreign handle without
closing it can retain an external resource; no asynchronous Cleaner is added.

Native/JNI references, listener registrations and byte-view leases need explicit
resource lifetimes too. Migrate destructor-dependent `DeleteGlobalRef` and
unregistration to explicit closure/replacement and failure paths. Audit cycles
that cross the JVM/native heaps; neither collector alone resolves them.

Root managed values retained by C/JNI frames across a collection-capable call or
same-thread reentry. Prefer the existing compiler-owned IR shims for GC-sensitive
work; describe remaining native-held roots at their boundary lifetime. Reuse
existing callback context storage where suitable. Calls proved noncollecting
must not acquire new root or retention bookkeeping. Preserve existing supported
callback identity, copy-back and failure order, and reject unsupported crossings
before execution rather than assuming a borrowing exemption.

Do not assume the system unwinder can traverse HotSpot JIT frames. At a
collection-capable transition into Java, describe the suspended managed stack
segment in the existing callback/transition context. Collection must visit all
suspended managed segments as well as the current segment and native anchors,
without scanning JVM memory. Qualify Java-to-Ironwood-to-Java-to-Ironwood reentry
with objects held only in an outer managed local. An unwalkable foreign frame
must not cause outer roots to be silently skipped. Measure any added boundary
capture cost on callback-capable paths; do not add it to noncollecting scalar calls.

Independent loaded images must not share/interpose collector state accidentally.
Use hidden/namespaced collector symbols and an explicit per-image ownership/root
boundary; do not accept raw cross-image managed references. Preserve the existing
unsupported unloading/hot-reload boundary. Qualify multiple independent images
and sequential serialized foreign use before native library acceptance.

### 4.9 Diagnostics, artifacts and accounting

Remove heap-reclamation diagnostics and settings with the language forms:
`--unfreed`, `--explain-rejected-free`, `@SuppressUnfreed`, destructor restrictions
and pending-free rules. Recognize obsolete options and source forms with useful
migration errors; never silently accept them. Permit Java-shaped identifiers
named `free` or `destructor` once the keywords are removed, including an ordinary
void method named `free` used by general `defer`.

The current `.ironclass` format embeds validated source in a format-1 ZIP, not
stable typed IR. Do not invent a format migration unnecessarily: reparse and
fully revalidate embedded source under the new semantics and recompute final
effects. Reject old reclamation syntax with an artifact/source diagnostic and
rebuild affected standard-library artifacts. Record any required language epoch
explicitly. Native ABI and bridge-generation identities must change so old
native images, descriptors and generated facades cannot be mixed with the GC runtime.

Preserve `System.allocationCount()` as successful ordinary object/array/String
allocation count. Engine metadata and runtime-only memory must not inflate it.
For `liveAllocationCount()`, aim to retain the meaning of allocated, not-yet-
reclaimed non-immortal language objects: count them during collection using
qualified engine heap enumeration and allocation-kind metadata, then account
for subsequent allocations using the existing total allocation counter. It is
not a continuously updated reachability census. GC0/GC2 must verify enumeration
does not count free-list cells or sidecars. If that cannot be correct without
new hot-path ledgers/finalizers, resolve the diagnostic API change before
cutover; do not add such machinery solely to preserve the old test helper.

GC0's [accounting experiment](GC0_BASELINE_AND_CONTRACTS.md#collection-and-accounting)
establishes this route for the selected engine profile, so both APIs are retained.
[GC2 qualification](GC2_LAYOUTS_AND_QUALIFICATION.md) covers the audited
allocation families and failure paths with exact scanners or explicit legacy
layout refusals. Production root/exception/foreign integration remains gated.

## 5. Milestones and dependencies

Each milestone has a reviewable local checkpoint, focused evidence and a recorded
exit decision. These are GC milestones, not the old self-hosting S1-S5 stages.
Do not claim the new memory model complete before GC10.

Update authoritative specifications and decision supersessions with the
milestone that changes behavior. GC10 is the final consistency audit, not a
reason to leave the current memory or bridge specification stale during work.

| Milestone | Deliverable | Prerequisites |
| --- | --- | --- |
| GC0 | Contract, consumer inventory and migration baseline | This plan |
| GC1 | Pinned collector build, adapter and packaging inputs | GC0 |
| GC2 | Exact layouts and standalone allocator/collector qualification | GC1 |
| GC3 | Collection effects, native root metadata and backend integration | GC2 |
| GC4 | Runtime exceptions, native roots and resource lifecycle migration | GC3 |
| GC5 | Integrated language/stdlib/defer cutover on the migration branch | GC4 |
| GC6 | Java Bridge and native-library GC qualification | GC5 |
| GC7 | Repository consumer/test migration and obsolete analysis removal | GC6 |
| GC8 | Critical-path performance and resident-memory qualification | GC7 |
| GC9 | Supported-target SDK/IDK and native distribution validation | GC8 |
| GC10 | Documentation acceptance and self-hosting readiness handoff | GC9 |

### GC0. Freeze the contract and inventory consumers

1. Record the selected GC semantics and the new native ABI/generation identifier.
   Fix the bridge lifetime API, native-image isolation policy and accounting
   disposition before their implementation. Use the recommendations above.
2. Inventory source and generated frees, destructor effects, rollback hooks,
   allocator families, runtime-owned references, remaining analysis consumers,
   resource cleanup and every supported foreign entry/exit.
3. Catalogue tests as preserved behavior, changed heap semantics, removed syntax,
   or replacement GC/native-resource coverage. Capture current focused baselines,
   allocation diagnostics and optimized-code/benchmark fingerprints.
4. Prepare a supersession map for D005, D027, D083, D140, D145, D185 and affected
   temporary/bridge decisions such as D089 and D189. Preserve D051, D132/D133 and
   D188's threading obligation. D168 keeps general defer and loses deferred free.
   Do not append a misleading decision that revives automatic allocation-time GC.

**Exit:** the inventory has no unclassified resource cleanup or reference owner;
breaking API/CLI changes and performance baselines are concrete. Existing manual
behavior remains unchanged. Future decision-log edits explicitly distinguish the
approved direction from the implementation's current status.

**Completion, 2026-11-04:** [GC0 baseline and contracts](GC0_BASELINE_AND_CONTRACTS.md)
fulfills these criteria against baseline `bb0fbab6`. It records the source-backed
consumer/resource ledger, all 77 main-library destructor sites, separate generated
RootHandle and listener-registration lifetimes, suspended native segments across
JVM reentry, private per-image collectors, `ironwood-native-gc-1` and schema-2
bridge compatibility. Collection-time kind-filtered enumeration supports keeping
both allocation counters without a hot-path ledger or finalizers. The
[evidence manifest](gc-evaluation/gc0-2026-11-04-manifest.json) records 24 exact
tests passing on Java 21 and 25, isolated accounting/layout/policy controls and
optimized-code/performance fingerprints. D315 accepts the future contracts;
existing decisions are not prematurely superseded. GC0 handed off the bounded
GC1 build/adapter/packaging task, with the current allocator unchanged.

### GC1. Prepare the engine without enabling it for programs

Add immutable pins, hashes, target-specific collector configuration and a narrow
adapter build through the existing runtime/link infrastructure. Match allocator
domains, validate private configuration hooks and isolate exported symbols.
Include collector source/notices and a configuration identity in SDK manifests.
The production allocation path remains unchanged at this milestone.

**Validation:** reproduce the existing qualification; build the adapter with
strict warnings; prove startup/ordinary allocation/exhaustion cannot collect;
verify explicit eager collection and allocator reuse in isolated fixtures.
Inspect static/dynamic symbols and dependency manifests. Run license/provenance
checks for the actual files being incorporated.

**Exit:** pinned builds are reproducible and an incompatible configuration fails
closed. No hidden thread, incremental mode, automatic collection or allocator
interposition is accepted.

**Completion, 2026-11-04:** [GC1 engine preparation](GC1_ENGINE_PREPARATION.md)
records pinned, byte-reproducible machost SDK builds, the narrow isolated C
adapter, effective configuration-hook audit, canonical configuration identity,
offline validation, original-source/license review and host/IDK packaging inputs.
Policy/eager reuse and frozen accounting controls passed, together with 25
nearby negative checks, private archive/final Mach-O symbol inspection and six
exact unchanged production tests. The relocated host package compiled a current
O3 program after its GC SDK was removed. The subsequent
[Linux x86-64 qualification](GC1_LINUX_X86_64_QUALIFICATION.md) passed native
engine/ELF, reproducibility, relocation and six exact production controls.
Linux arm64, complete IDK assembly and two-image runtime qualification remain
explicit gates. This historical GC1 checkpoint precedes the GC2 layout work;
the production calloc/free path and current generation constants are unchanged.

### GC2. Describe and qualify every managed layout

Add layout descriptions to compiler-owned metadata and emit matching C/LLVM
descriptors. Implement fixed/per-object maps, large maps, reference/primitive
arrays, inline Strings and internal block kinds. Establish zeroing/header order,
collector-safe incomplete objects and exact accounting. Use isolated runtime
fixtures until root coverage is ready; do not switch the normal allocator yet.

**Validation:** inherited/generic/hidden fields; pointer-shaped primitives;
zero/null fields; multi-array graphs; large payloads; partial construction;
metadata exhaustion; mark-stack overflow/recovery; large virtual spans and
separate bounded resident workloads. Pair a rooted graph that survives with
its unrooted counterpart that is reclaimed. Check free-list cells and counters.

**Exit:** every reachable allocation shape has an exact scanner or a compile/link
refusal, with no conservative fallback. Ordinary object layout has no added
descriptor footer. Resolve the live-count diagnostic before proceeding.

**Completion, 2026-11-04:** [GC2 layouts and qualification](GC2_LAYOUTS_AND_QUALIFICATION.md)
records the authoritative allocation-family inventory, compiler-owned physical
maps, matching C/LLVM descriptors, bounded language/internal kinds and exact
small/large/array scanning. Zeroing, dynamic header order, incomplete objects,
Strings, all array kinds, metadata-budget exhaustion, actual mark-stack overflow
and recovery, free cells, native construction scratch and accounting passed.
The collector remains unmodified; USE_MARK_BYTES removes conservative recovery
accelerators and deliberately changes the configuration identity. Stale GC1 SDKs
are rejected. Legacy Throwable/carrier integer links and borrowed ByteView
layouts explicitly refuse experimental output. Ordinary object storage has no
descriptor footer; production allocation and mandatory proofs are unchanged.
GC2 is qualified on macOS arm64 and, through the
[native Linux follow-up](GC2_LINUX_X86_64_QUALIFICATION.md), Linux x86-64.
The new profile passed exact overflow recovery, reproducibility, ELF isolation
and focused production controls on Linux. Linux arm64 remains unqualified.
Roots/effects are GC3.

### GC3. Integrate effects, statepoints and root enumeration

Add dedicated typed IR for collection and a sound transitive effect analysis.
Preserve managed reference identity through lowering and LLVM optimization;
generate late statepoints and target root sections. Add precise static roots,
startup registration, runtime scratch and production Mach-O/ELF root readers.
Validate compatibility with trace metadata, unwind tables and linker pruning.

Start on macOS arm64, then implement the Linux arm64/x86-64 readers. An unsupported
target must refuse experimental GC output until its reader is qualified.
Root collection can be rehearsed on controlled fixtures before production
reclamation. Do not trace a legacy malloc/free heap as though it were GC-ready.

**Validation:** O0/O1/O2/O3, calls and recursive callers, derived/base pairs,
SSA merges, loops, stack spills, inlining, specialization, interfaces, class
initialization, exceptions/landing pads, pending return/yield values, and static
roots. Test missing/stale metadata in isolated negative controls that stop
before dereferencing reclaimed objects. Verify source/class/archive parity.

**Exit:** coverage is complete for the selected experimental closure, unqualified
calls fail closed, and noncollecting hot code has no GC maintenance. Do not enable
general source collection while exceptional/native roots remain incomplete.

**Completion, 2026-11-04:** [GC3 roots and statepoints](GC3_ROOTS_AND_STATEPOINTS.md)
records the explicit macOS arm64 and Linux x86-64 standalone closure, compiler-owned effects and
managed pointers, late statepoints in every mode, exact stack/static/native slots,
optimized trace identity/map integrity, target readers and fail-closed boundaries.
148 native checks, two exact compiler selections, structural ELF arm64/x86-64
checks, seven production controls and unchanged GC0 hot-code fingerprints passed.
The separate [Linux x86-64 qualification](GC3_LINUX_X86_64_QUALIFICATION.md)
adds 156 native checks, actual ELF/PIE/RELRO validation, stripped-unwind and
writable-map refusals, offline SDK use and seven production controls. Linux
arm64's GC3 runtime gate remains closed pending native qualification. GC2's
engine-2/USE_MARK_BYTES profile and layout refusals remain authoritative. Actual
Throwable/foreign roots, ordinary GC activation and the language/bridge cutover
remain later milestones. The next bounded task is GC4, not production activation.

### GC4. Migrate runtime-only lifetimes and failure cleanup

Implement exact exception/secondary/trace graphs, native unwind-wrapper roots,
emergency-root lifetimes and memory-only sidecar ownership. Inventory native
resources released by destructors and convert those releases to explicit
resource/registration cleanup. Implement the common native-root/foreign-handle
mechanisms required by the bridge, keeping administration off ordinary calls.

**Validation:** collect while an exception crosses nested finally/defer handlers;
catch/rethrow identity; primary/secondary cycles; trace capture/refill; OOM before
collection; resource acquisition failure at each step; completed and failed
construction with published references; repeated root creation/release. Measure
native handle/JNI-reference balances as well as managed heap reuse.

**Exit:** no runtime memory-only block relies on a source destructor or finalizer,
and resource failure cleanup does not depend on successful collection.

### GC5. Make the coordinated language and standard-library cutover

Implement canonical `Memory.collect()` through validated intrinsic resolution,
dedicated typed IR and the adapter. Reject an unrecognized intrinsic rather than
executing an empty standard-library stub. Its effects must survive optimization.

In the same integrated cutover, change the managed allocator, remove reclamation
syntax and destructor lowering, migrate the entire required standard library,
replace resource cleanup, and remove temporary/owned-rendering frees. Retain
general defer with GC-rooted captures. Update reference-container cleanup and
measured pool reset paths. Regenerate standard-library artifacts and diagnostics.

This is a coupled change: rejecting `free` while the loaded standard library still
uses it is not a finished milestone. Do not delete destructor bodies blindly or
silently preserve legacy explicit-free runtime entries. Large cutover work may
use reviewable private commits on the migration branch, but remains nonshipping
until the integrated checkpoint passes. Do not expose a second supported memory
model to disguise an incomplete migration.

Until GC6 qualifies the boundary, refuse GC bridge/library outputs whose native
roots or generated cleanup remain unported. The final-link verifier must reject
any reachable legacy instruction that deallocates managed storage. Incomplete
private checkpoints must not emit an image that mixes GC allocation with old
generated frees. Update the memory/language specification and decision log with
the implemented cutover, explicitly recording the temporary development limits.

**Validation:** parser/semantic/typed-IR/native coverage for the new intrinsic and
removed forms; ordinary identifiers named `free`/`destructor`; all defer exits,
capture reassignment, nested collecting cleanup and failure ordering; aliases,
cycles and container sharing; failed construction; resource close independent of
collection; pool retention versus explicit stale-reference removal; counters and
source/artifact diagnostics.

**Exit:** the normal compiler and required library build and run with the GC model
on the migration branch. No shipped configuration is declared complete before
bridge, consumer, performance and platform acceptance below.

### GC6. Complete foreign and library integration

Migrate generated facade `free()` APIs to explicit bridge lifetime handles and
update generation checks. Integrate foreign anchors, managed values in native
frames, callback/reentry paths, Strings, copied arrays and borrowed byte views.
Migrate JNI global-reference/registration cleanup explicitly. Keep source
`close()` semantics distinct from release of a foreign anchor.

**Validation:** a facade survives collection through its anchor; released anchors
stop retaining otherwise unreachable graphs; independent surviving Ironwood
aliases remain valid; callback reentry preserves outer suspended locals across
JVM frames; cleanup/Java allocation failures preserve copy-back and exception
order; view/lease closure is safe;
multiple images are isolated; supported serialized caller use remains valid.
Retain refusal tests for unsupported async/cross-image/unknown lifetime cases.
Inspect the scalar noncollecting entry path for new TLS, helper calls or lookups.

**Exit:** all currently supported bridge categories have a sound GC path or an
explicitly reviewed compatibility change. Do not quietly ship regressions by
omitting formerly exported members or changing resource methods into traps.

### GC7. Migrate consumers and retire obsolete proof machinery

Update examples, projects, integration fixtures, test generators, benchmarks,
launch scripts, packaging smoke inputs and documentation snippets. Remove
obsolete flags and missing-free annotations. Complete runnable resource examples
must still demonstrate cleanup. Preserve pure pooling/reuse workloads.

Delete free/destructor AST and IR nodes, dispatch/rollback metadata, explanations,
CLI options and heap-only proof passes after their consumer inventory is empty.
Keep escape/retention facts needed for optimization and sound foreign/resource
contracts; factor them away from obsolete free proofs. Remove dead allocation
ownership fields from caches and artifacts rather than maintaining two analyses.

**Validation:** replacement tests are linked to retired cases in the disposition
ledger; representative old unsafe-free cases now fail as removed syntax;
equivalent shared/aliased GC graphs work; remaining native/protocol misuse still
fails appropriately; source/class/archive outputs agree. Search for generated
destruction/deallocation paths and unexpected libc frees of managed storage.

**Exit:** there is one implemented memory model with no reachable legacy heap
reclamation path and no retained whole-program free-proof fixed point.

### GC8. Establish critical-path and memory evidence

Run the deterministic pooled-rewrite and rare-allocation comparisons plus
representative existing application kernels. Separate allocator layout/locality
effects from collector maintenance, and compare cold/steady allocation, heap
growth and post-collection reuse. Measure supported callback-capable paths
separately from proven noncollecting paths.

Inspect O3 machine code for writes/barriers/calls/TLS on reference operations,
ordinary calls and general defer registration. Collection time is reported but
is not a pause-time optimization target. Collecting calls may impose spills;
an unexplained hot regression is a review gate, not something amortized away by
an unrelated end-to-end average. Do not select arbitrary universal percentage
thresholds from the earlier microbenchmark.

Use repeated compiler-shaped graph phases: build AST-like trees, shared symbols,
cyclic type graphs, worklists, snapshots and exception paths; retain published
results, clear obsolete scratch roots, then explicitly collect. Record allocation
volume, reachable/retained graph explanations, reusable storage, process/native
memory and peak resident/charged memory separately. Use the protective 16 GiB
guard with a documented measurement method and bounded time per experiment.
Qualify real resident workloads; do not substitute another sparse virtual-span
test. Collection cannot fix caches that intentionally keep obsolete facts alive.

**Exit:** accepted critical kernels have no unexplained GC maintenance and memory
stabilizes across phases. Native resource counts also stabilize. The report
states workload/host limits and does not claim self-hosting success.

### GC9. Qualify supported targets and distribution

Complete focused native tests on macOS arm64, Linux arm64 and Linux x86-64 with
pinned LLVM 23. Verify target C ABI/layouts, unwind/root sections, linker retention,
PIE/ASLR, hidden symbols, allocator alignment and current Linux compatibility
constraints. Check native executables and libraries as distinct output modes.

Exercise affected local SDK/IDK preparation, archive manifests, installation,
version/configuration output and Java Bridge packaging/smoke paths. Verify that
collector binaries, corresponding source and required licenses travel together
and that missing/mismatched inputs fail before link or publication.

**Exit:** every currently supported target has direct focused evidence. An
unavailable target keeps GC9 open and is reported as a blocker; narrowing target
support requires an explicitly accepted decision. Host results are not
substituted for another target. Hosted release jobs and an unfiltered full
compiler suite require a separate explicit request. No such job or suite is
authorized by this milestone plan.

### GC10. Accept the completed model and hand off

Synchronize `MEMORY.md`, language/spec/compatibility docs, `COMPILER.md`, stdlib
docs, CLI/IDK/bridge guides, examples and `AGENTS.md` with implemented behavior.
Record explicit decision supersessions and align all affected numbered-feature
entries in `IRONWOOD_VS_JAVA.md`. Preserve historical evaluation/rollback records.

Update `SELF_HOSTING_PLAN.md` and `BEFORE_SELF_HOSTING_PLAN.md` prerequisites to
reflect actual GC capability. Treat `OWNERSHIP_COMPOSITION_PLAN.md` as the prior
alternative for managed-heap reclamation; do not implement owning collections
or new ownership syntax in parallel without another request. Preserve applicable
resource-contract lessons.

Finish with a report covering targeted tests, machine-code/performance evidence,
memory and native-resource plateaus, exact dependency/configuration versions,
source/native compatibility changes, supported targets and remaining limitations.
Keep commits local unless the maintainer changes that instruction.

**Exit:** all earlier gates are met and no current authoritative document still
describes the old model as implemented. A new self-hosting attempt requires a
separate scoped task with an end-to-end checkpoint and explicit phase collection
sites; this plan does not revive the discarded migration branch.

## 6. Focused validation matrix

Each row needs a registered focused selection or dedicated native harness before
its implementing milestone is accepted. Names below describe planned coverage;
they are not claims that these tests already exist.

| Contract | Positive/reclamation coverage | Failure or nearby counterexample |
| --- | --- | --- |
| Explicit scheduling | Allocation and ordinary calls leave collection count unchanged; explicit collect finishes sweep and enables reuse. | Exhaustion with retry settings still throws/fails without collecting; no lazy sweep after return. |
| Exact heap maps | Real fields, inherited/hidden references, all arrays and runtime sidecars survive. | Pointer-shaped primitives do not retain targets; large-map/metadata failure cannot select conservative scanning. |
| Native roots | Caller chains, registers/spills, derived bases, phis, loops, returns and class-init paths survive in all modes. | Omitted or stale maps are rejected or isolated by controls before invalid dereference. |
| Exceptions | In-flight/caught/rethrown objects and secondary graphs survive collecting cleanup. | Dropped graphs stop retaining; emergency slots clear; metadata failure preserves primary identity. |
| Defer | Captures survive reassignment and collection; all normal/exceptional exits preserve LIFO and D051. | Removed deferred-free syntax fails; ordinary `defer free(...)` method calls remain legal. |
| Construction | Fully zeroed dynamic layout is safe during a collecting constructor; published partial objects remain valid after failure. | Failed acquisition closes native resources exactly once; no generated managed-child destruction. |
| Pools/collections | Live items survive; obsolete slots clear; explicit retaining reset can reuse items without allocation. | Uncleaned slots intentionally retain graphs; cold cleanup releases them without clearing active values or closing pooled resources. |
| Foreign roots/resources | Anchors, native frames, listeners and view owners cover supported synchronous reentry. | Missing/closed/unsupported lifetimes fail safely; no cross-runtime resource leak on supported cleanup paths. |
| Surface/artifacts | Canonical intrinsic emits collection; source/class/archive behavior agrees; old source diagnoses meaningfully. | Spoofed/malformed intrinsic, unsupported legacy syntax, old native ABI or missing collector input fails closed. |
| Counters/memory | Total counts preserve their boundary; post-collect accounting excludes sidecars/free cells; graph phases stabilize. | RSS is not confused with live object count; sparse virtual allocation does not qualify resident capacity. |
| Performance | Noncollecting hot paths and defer contain no new GC maintenance; rare allocation has matched controls. | Class-init/callback collecting routes are measured separately; unexpected spills/TLS/helpers block acceptance. |
| Targets/distribution | Executables/libraries and collector SDKs work on each supported target. | Incorrect metadata version, stripped root section, interposed collector or target mismatch refuses build/load. |

Reuse existing defer behavior selections listed in
[LOCAL_TESTING.md](LOCAL_TESTING.md#deferred-cleanup-milestone-1), adapting only
tests whose ownership/free expectations become obsolete. Preserve their current
exit/failure-order and allocation-free registration evidence. Use
`./scripts/test.sh --test 'EXACT NAME'` and exact focused groups; unknown names
must fail. Never replace the matrix with an unfiltered compiler/native suite.

For each milestone, record the actual selections and results, relevant baseline
revision, compiler/toolchain and collector configuration, machine-code checks,
input/workload hashes and neutral host role. Keep raw logs, archives and large
generated evidence ignored; commit compact manifests and conclusions. Once the
chosen checks pass, broaden only for a new change, failure or unresolved risk.

## 7. Stop criteria and local workflow

- Stop reclamation activation if any root/layout class is unaccounted for, a
  scanner silently becomes conservative, or construction/native metadata can
  contain an undescribed managed reference.
- Stop the cutover if removing a destructor loses a resource cleanup path, or if
  native view safety is incorrectly treated as solved by managed reachability.
- Revisit the engine choice if the pinned root/descriptor interfaces cannot meet
  exactness, overflow recovery or target requirements. MMTk MarkSweep is the
  already tested fallback; do not accumulate undocumented collector patches.
- Investigate retained roots and pool/cache contents before treating heap growth
  as a collector failure. Stop capacity runs at the protective limit and report
  the measured category; never silently raise the guard.
- Review unavoidable hot-path regressions before implementation acceptance.
  Do not hide them behind a diagnostic option, unsafe exemption or automatic GC.

Use one local migration branch and coherent milestone commits. Keep the initial
infrastructure independently reviewable, and mark incomplete coupled-cutover
checkpoints clearly. Record a concrete remaining gate at each stopping point.
No branch creation beyond the maintainer's request, pushing, hosted jobs or
automatic self-hosting restart is part of this document's implementation plan.

GC0 through GC3 are complete for their documented experimental closures. The
next bounded task is GC4's runtime-only lifetimes and failure cleanup. Preserve
production allocation, syntax, mandatory proofs and generation contracts until
the coordinated cutover. Carry forward GC2's layout refusals, Linux arm64's GC3
native root-reader gate and its unexecuted engine/layout gate.
Subsequent tasks can own one milestone or a named substage, carrying forward
prerequisite commit IDs, evidence and remaining gates.
