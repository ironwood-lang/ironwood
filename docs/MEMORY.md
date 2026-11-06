<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Managed memory

GC5 replaces the manual reclamation model on the migration branch. This is the
single normal native heap model, including programs that never collect. The
[GC0 baseline](GC0_BASELINE_AND_CONTRACTS.md) and earlier decisions describe the
historical model; they do not authorize legacy lowering. See the
[implementation report](GC5_IMPLEMENTATION.md) for qualification and limits.

Objects, arrays, Strings, compiler-created heap values and runtime sidecars use
pinned BDWGC 8.2.12. Storage is completely zero initialized before the dynamic
type header is installed. Array bounds, size overflow and native failure checks
remain. Source references have stable identity: the collector never moves them.

## Explicit collection

`ironwood.lang.Memory.collect()` is public static void, takes no arguments and
is a validated compiler intrinsic with dedicated typed IR. Altered or malformed
reserved declarations fail, including declarations reconstructed from format-1
`.ironclass` and `.ironjar` embedded sources. User classes named Memory remain
ordinary classes. The compiler never executes an unrecognized intrinsic stub.

Collection is synchronous and finishes marking and sweeping before returning.
Only this explicit operation collects. Allocation failure throws without an
implicit collection. There are no background workers, incremental steps,
finalizers, reference counting, write barriers, ambient stack scanning, continuous
shadow stacks or callbacks that close resources or scrub library containers.
Unknown effects remain MayCollect. Derived addresses are paired with their
compiler-proven bases. Exact managed roots are reconstructed at collecting PCs;
ordinary noncollecting calls do not poll, register roots or update TLS.

Unreachable storage is eligible at the next collection. Reachable objects,
including published partially constructed objects, remain valid graphs after a
constructor throws. Failed construction does not run managed rollback. A caller
must still close native acquisitions on exceptional paths and obey initialization
and native lifetime rules. Collection does not promise any source destruction.

Ordinary executables use root ABI 2 / runtime-owner ABI 1. GC6 managed libraries
use root ABI 3 / runtime-owner ABI 2 and saved foreign segments version 1. Exact
source/runtime layouts, qualified target
configuration and the required LLVM 23 nonmoving statepoint pass are bound into
the verified inputs and image. Missing, corrupt or incompatible metadata refuses
before reclamation. Root scratch exhaustion validates the remaining metadata,
clears preparation state and throws OutOfMemoryError without partial collection.
Corruption and exhausted emergency delivery capacity remain fatal. Emergency
exception delivery and secondary occurrences use bounded fixed storage.

## Removed reclamation forms

`free value;`, `defer free value;`, source `destructor { ... }`, `--unfreed`,
`--explain-rejected-free` and `@SuppressUnfreed` diagnose their removal.
There is no missing-free warning or alternate manual memory mode. Identifiers,
classes and methods named `free` or `destructor` are ordinary names.
`defer free(value);` defers an ordinary void method call.

Unnamed temporaries, owned rendering results and constructor failure graphs
use the same managed lifetime as all other objects. Final linking rejects
reachable obsolete managed-deallocation IR, even in programs without collection.
Dead AST/IR/proof scaffolding remains for GC7; it grants no reclamation behavior.
Optimization, initialization, native acquisition and pool safety analysis remain.

## Defer, exceptions and resources

General defer captures receiver and arguments once immediately. Reassignment
changes neither captured identity nor the captured values. Actions execute in
block-scoped LIFO order on normal completion, return, yield, break, continue and
exceptional exit. Pending references and captured values survive collecting
cleanup. Lowering uses ordinary typed locals and control flow, without heap
action lists, closure allocations, registration or per-defer TLS.

D051 is unchanged: an existing primary survives; cleanup exceptions append in
execution order. Catch/rethrow preserves identity. Throwable's source message,
cause, stack trace and secondary APIs use exact managed runtime metadata and
retain optimized trace attribution. Explicit `close()` and pool `release()`
remain ordinary calls and may be deferred. Native acquisition rollback remains
necessary on constructor/factory failure. Collection never substitutes for close.

## Containers and reuse

Reference collections clear retired physical slots and retired map-entry links.
ArrayList.clear() clears all physical reference slots; remove clears retired
slots. ArrayList.resetForReuse() only resets logical size and intentionally
retains slots. clearUnusedReferences() clears the inactive suffix and is an
explicit cold operation. ArrayLinkedList provides the same named operations;
its old clear(boolean) delegates to their documented contracts. Primitive list
clear paths remain constant time. Pooled reusable objects intentionally remain
reachable. Known pool-origin conflicts remain compile-time errors. Unknown pool provenance
remains subject to the documented same-pool, at-most-once return obligation;
there is no runtime membership tracking.

## System counters

System.allocationCount() counts successful language allocations, including an
allocation whose constructor subsequently fails. Internal sidecars are excluded.
System.liveAllocationCount() is cached at collection completion, plus successful
language allocations since that collection. Reads do not collect or scan. Native
scratch and fixed image objects are outside these language counts.

## Temporary boundaries

Normal source, class and archive compilation and the entire main/testing library
are supported. [GC6 managed bridges](GC6_IMPLEMENTATION.md) are qualified on macOS
arm64 and Linux x86-64. Generated RootHandle anchors and ListenerRegistration
ownership are explicit and independent of source closure and collection. Exact
current/suspended segments cover collecting JVM reentry without unwinding Java.
Every independent native image owns its collector and roots. Schema 2 binds
required protocol versions and actual SDK/payload hashes before user execution.
Raw shared IR, unknown foreign lifetimes, async callbacks and raw cross-image
managed references remain unsupported. Linux arm64 and arm64e remain gated.
GC7 owns remaining repository consumer migration and broad dead-code removal;
GC8 owns whole-project performance and resident-memory acceptance. GC5 does not
claim completion of the complete GC project or self-hosting readiness.
