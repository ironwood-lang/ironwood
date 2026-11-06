<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC6 foreign and native-library integration

Date: 2026-11-05. GC6 completes the managed bridge on macOS arm64 and Linux
x86-64. Work continues locally from `a91a13cd`, preserving GC5 `c76a3a75` and
its prerequisites. The earlier carrier checkpoint is superseded by this report.
See the [pre-change review](GC6_PRECHANGE_REVIEW.md), [frozen GC0 contracts](GC0_BASELINE_AND_CONTRACTS.md)
and [compact verification manifest](gc-evaluation/gc6-2026-11-05-manifest.json).

## Lifetime API and migration

All production bridges, including scalar-only APIs, use schema 2 and
`objects-gc-v1`. Generated support lives in the reserved
`ironwood.bridge.generated.g<generation>` package. Support operations cannot
collide with ordinary source methods named free, close, value, retain or construct.

* `RootHandle<T>` is final and implements AutoCloseable. Its construction is
  private. `value()` requires an open handle; `close()` is idempotent and releases
  only that handle's native anchor. It never collects, destroys children, closes
  source resources or invokes callbacks.
* Facades have no owning public constructors or generated reclamation methods.
  `Roots.construct_<tag>(arguments)` returns a RootHandle. The tag is lowercase
  hexadecimal UTF-8 of the binary source type name. `Roots.retain(Facade)`
  overloads create independent anchors.
* Every non-immortal managed reference result, including an alias or dependent
  view, gets an independent anchor before Java delivery. Inputs remain facades.
  Strings, copied primitive arrays, enums/permanent values and exception
  snapshots retain their documented value projections. Generic projections
  preserve their finite admitted types and final-bound input checks.
* An image-local facade cache preserves identity while an anchor remains open.
  Closing the last anchor closes its Java liveness state before releasing native
  records. Stale access fails before dereference. Monotonic anchor identities
  and detached liveness states prevent native address reuse from reviving a
  stale facade. A later re-export after all anchors close may have a new identity.

Move old Java facade construction to Roots factories and put handles in
try-with-resources. Obtain facade inputs with handle.value(). Close source files,
streams and sockets through their source methods before releasing handles.
Source resource closure and anchor release are independent operations. There is
no Cleaner, finalizer, weak-reference cleanup or collection-time JNI cleanup.

## Listener ownership and failure ordering

`Listeners.open()` acquires a `ListenerRegistration` before a retaining operation.
The registration is AutoCloseable with idempotent close. Retaining calls use
`Listeners.install_<tag>(registration, source arguments)`; constructors use
`Listeners.construct_<tag>(registration, source arguments)`. Tags encode the
GC0 compact sorted-key JSON callable identity. Return projections are preserved,
including void, primitives and constructor/alias RootHandles.

Native records reserve candidate listeners, bindings and result anchors before
source execution. The compiler generates noncollecting final-slot snapshots and
conditional detach operations. Reconciliation runs on success and exception,
including a constructor or method that stores a listener and then throws.
An operation adopts final slots matching its supplied listener identities. A
skipped store with a different input preserves its old binding. A newer nested
retaining operation owns its matching final binding ahead of an older operation
still in flight. This final-identity convention avoids source-store bookkeeping;
it does not assign ownership by counting individual physical stores. Tests cover
these cases, old-token closure and registration closure during an active call.

Replacement, unregistration and owner closure release each JNI ownership once.
An old token cannot detach a replacement. Invocation depth retains retired
listener proxies and JNI globals until outermost completion, including a listener
held only in an outer managed local. RootHandle release does not unregister.
Invocation carrier memory has an exact typed nextCarrier link. Translation
consumes its JNI cookie and completion clears/releases the global independently
of managed carrier reachability. Unknown carrier escape, retained/returned
carriers and callback-capable cached initialization failures refuse admission.

JNI null acquisitions without a pending exception raise the reserved Java OOM.
Unpublished delivery records and JNI acquisitions roll back; committed source
stores and graphs remain. Original throwable identity, secondary associations,
array copy-back attempts and diagnostics retain their established ordering.
Collection never performs this cleanup or runs source/Java callbacks.

## Exact roots and JVM execution

Ordinary GC5 executables retain root ABI 2 / owner ABI 1. Managed images use
root ABI 3 / owner ABI 2, foreign segment version 1 and LLVM stack-map version 3.
The rebuilt engine-3 SDK advertises both supported protocols. Exact layouts and
nonmoving statepoints come from the normal typed-IR/native pipeline.

Collecting native dispatchers delimit a managed segment with a validated native
function/frame boundary. Before Java or a JNI helper that may execute reentrant
Java, the image saves managed frames, machine-register state and exact maps.
Nested entry creates another segment. Collection reconstructs the current
segment and every suspended segment belonging to that image. It never unwinds
HotSpot JIT frames. The arm64 SP boundary and x86-64 frame-pointer boundary use
separate validated recovered frame SP bounds. Saved metadata/state are hashed
and checked before reclamation. Missing, corrupt or incomplete coverage refuses.
Capture reservation OOM prevents Java execution and leaves collection unstarted.

Exact foreign root records cover managed inputs, conversions, pending results,
exceptions, listener proxies, carriers and native anchor/binding fields.
Compiler-owned context specialization and shims cover constructors, scalar and
object entries, synchronous/retained callbacks, batches, Strings, copied arrays,
exception extraction, finally and defer. Unknown generated JNI execution helpers
fail generation. Class initialization is conservatively executable Java.

Borrowed ByteView descriptors and native buffer addresses are not managed
allocation bases. Java storage owners/JNI locals survive the complete borrow and
nested reentry. Bounds, read-only, nonretention and source resource validity
checks remain. JVM-owned ByteView gains no close method. Copied array conversions
have exact managed roots and preserve alias/copy-back rules.

## Images, inputs and packaging

Each linked image owns its collector, roots, anchors, counters and transition
chain. Mach-O uses explicit exports and two-level lookup. ELF uses an explicit
version script, local binding, hidden start/stop trace symbols and dependency
exclusion. The backend audits actual exports and refuses collector/adapter
imports. Only JNI_OnLoad and the image bootstrap escape production images.
No correctness depends on RTLD_LOCAL, load order or another collector's absence.
Raw cross-image facades are rejected; copied values and shared JVM-owned ByteView
storage are the supported host transport. Unloading/hot reload remain unsupported.

Common generation identity binds the projection, memory ABI, layout, root,
owner and segment versions. Each host manifest additionally requires the actual
`gc.config.sha256`, SDK manifest and collector/adapter/root-reader payload hashes.
They bind the native build identity. Assembly preserves configuration per target
instead of inventing a target-independent SDK digest. Restore, assembly, loader
preflight and native bootstrap reject missing/stale identities before user code,
including scalar-only images. Source, class and archive reconstruction produce
identical common facade/lifetime projections. Complete BDWGC source and notices
travel with paired native images; Linux retains the pinned private GCC support
source/notices and relative dependency paths.

Actual two-target assembly uses matching Temurin 23.0.2 producer/Javadoc tools,
then loads on Linux. The common-content check correctly rejected mixing Oracle
21.0.1 and OpenJDK 21.0.10 Javadoc bundles. Preserve that check and use matching
producer tools across hosts. Per-configuration SDK documents are retained under
their configuration digest; the full unmodified source/archive remains common.

Current SDK configurations, built from the changed runtime/root reader/pass:

| Target | Configuration SHA-256 |
| --- | --- |
| macos-arm64 | `0ac9acaa6ec276015c281b881473334ee3d457c736964070154994e18f836d5d` |
| linux-x86_64 | `9abad06077e76cab4b4bedac641caa3705a53e82e5e5759b58106c7d65ecc263` |

BDWGC stays pinned to 8.2.12, USE_MARK_BYTES=1, with allocation collection
disabled and explicit synchronous eager collection. Old SDK identities refuse.

## Verification and performance

Actual Java 21 consumers load generated JNI libraries with -Xcheck:jni and
HotSpot compilation enabled on both targets. Java 23 additionally exercises
actual critical/FFM calls and forced JNI fallback. Qualification is bounded to
these JVMs/platforms; historical Java 21-25 bridge records do not extend it.
Every child has the 16 GiB process-group RSS guard and a 60-second bound. Linux
runs use an isolated ignored source staging directory with a 30 GiB free-space
floor, preserving the canonical checkout and unrelated work.

* O0/O1/O2/O3: independent anchors, alias/view identity, stale/reused addresses,
  constructor publication failure, generic/enum/snapshot results and native
  resource closure versus anchor release/collection.
* O0/O1/O2/O3: A/Java/A at depths 1, 3, 8 and 16, A/Java/B/Java/A, outer-only
  locals, saved registers/derived bases, release during reentry, later reclamation,
  boundary corruption and sequential use by four Java threads.
* O0/O1/O2/O3: listener replacement, exceptional committed stores, active
  retirement, nested ownership, pending returns, exception translation, finally,
  deferred callbacks, batching and forced JNI batch fallback; JNI globals balance.
* Strings, arrays and borrowed views collect and reenter; qualification-only
  fault shims cover native records, JNI globals/String input, Java object/String
  delivery, capture reservation, facade publication reentry and failed copy-back
  preserving the original source failure. Production has no fault counters.
* Production packager images on Mach-O and ELF have distinct heaps/counters and
  actual audited export policies, A/B/A exceptions and serialized callers.
  Scalar-only CLI packages and load-time metadata corruption controls refuse
  missing/stale schema, versions, configuration and all required payload hashes.
* Focused GC2-GC6 layout/effect/deallocation/carrier/retention proofs and GC4/GC5
  O0-O3 owner, preparation failure, corruption, OOM and missing/writable-map
  controls remain. No unfiltered compiler suite or hosted workflow was run.
  Current-SDK layout/array/sidecar/metadata/failure/overflow controls recover from
  120 actual mark-stack overflows and check 65,536 numeric pointer-shaped values
  on each target.

O3 scalar native bodies retain no new root registration, TLS, registry lookup,
poll or valid-path administration helper. The batch append loop remains stores
and arithmetic; exact administration occurs at flush. Deterministic controls
check checksums, events and zero source allocations. Transition capture is
measured separately from scalar/critical calls. Raw measurements and disassembly
stay ignored; compact results/hashes are in the verification manifest. These are
bounded controls, not whole-application performance acceptance, which is GC8.

## Remaining refusals and bounded handoff

Linux arm64 and arm64e remain unqualified. Async callbacks, concurrent calls,
unknown/retaining native borrows, raw cross-image managed references, unloading,
unsupported signatures and incomplete metadata refuse or remain outside the
explicit externally serialized caller contract. Sequential calls may use
different Java threads. No language threads, TLS guards, synchronization or
per-source-operation bookkeeping were introduced.

The qualified public producer, assembler and distribution routes are enabled.
Arbitrary shared IR and legacy unmanaged bridge emission remain refused; a
managed admission and explicit isolated export policy are mandatory.
GC7 may separately migrate remaining repository consumers/fixtures and delete
obsolete managed ownership proof scaffolding only after inventorying consumers.
Preserve native/resource, initialization, pool, foreign confinement and optimization
facts. No GC7 implementation, GC8-GC10 work or self-hosting restart is included.
