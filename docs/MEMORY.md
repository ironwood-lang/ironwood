<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Managed memory

GC5 replaces the manual reclamation model on the migration branch. This is the
single normal native heap model, including programs that never collect. The
[GC0 baseline](GC0_BASELINE_AND_CONTRACTS.md) and earlier decisions describe the
historical model; they do not authorize legacy lowering. See the
[GC5 implementation report](GC5_IMPLEMENTATION.md) for the cutover and
[GC10 acceptance](GC10_ACCEPTANCE.md) for reconciled evidence and current limits.
The [independent post-integration audit](GC_POST_INTEGRATION_AUDIT.md) records
fresh behavior on the three requested targets. Its
[native fault harness continuation](GC_NATIVE_HARNESS_MIGRATION.md) closes the
registered harness migration findings, and the
[physical x86-64 verification](GC_X86_POST_AUDIT.md) completes matching-hardware
coverage without changing the collector contract.

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

Collection is full and synchronous on the calling application thread and
finishes marking and sweeping before returning.
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

## Native heap sizes

Pass `-Xms` and `-Xmx` directly to a generated executable, before application
arguments. For example, `./app -Xms16m -Xmx256m input.txt` reserves an initial
16 MiB managed heap and caps its capacity at 256 MiB. These are runtime options,
not compiler/linker options. They never enable automatic collection.

Sizes are decimal byte counts, optionally suffixed with `k`/`K`, `m`/`M` or
`g`/`G` for binary KiB, MiB or GiB. Values must be whole KiB; `-Xms` requires
at least 1 MiB and `-Xmx` at least 2 MiB. Capacities round up to 64 KiB allocator
granularity. The last occurrence of each option wins. The effective initial
size must not exceed the effective maximum. Malformed, overflowing or conflicting
options fail before source execution with a startup diagnostic and exit status 1.
Failure to reserve the initial heap also prevents startup.

`-Xms` sets initial and minimum collector-owned capacity. Collection can return
unused physical pages to the OS without losing that reusable capacity; it is
not a resident-memory guarantee. Without `-Xms`, the collector uses its small
default initial capacity. Without `-Xmx`, no practical application heap cap is
configured, preserving existing behavior. Unlike JVM heap ergonomics, Ironwood
does not choose a maximum from available machine memory.

Ordinary managed allocation at the cap throws catchable `OutOfMemoryError`,
without first collecting garbage. After leaving the catch, discard unwanted
references and call `Memory.collect()` explicitly to recover storage. The cap
includes collector heap storage for objects, sidecars and heap administration;
usable object bytes are smaller because of headers, fragmentation and rounding.
It does not cap native scratch, stacks, libraries, collector metadata outside
the heap or total process RSS. An oversized limit or external memory pressure
can still cause an OS-level failure. Startup argument construction and exhausted
emergency exception delivery are outside an ordinary source catch boundary.

Leading runtime options are removed from `main(String[] args)`. Parsing ends at
the first application argument or at `--`; the delimiter itself is removed.
For example, `./app -Xmx8m -- -Xmxliteral` passes only `-Xmxliteral` to `main`.
Options following an application argument are ordinary application arguments.
Managed bridge images retain their private defaults and do not interpret the
hosting JVM's heap flags. See D332 and the focused heap-option tests in
[local testing](LOCAL_TESTING.md).

## Removed reclamation forms

`free value;`, `defer free value;`, source `destructor { ... }`, `--unfreed`,
`--explain-rejected-free` and `@SuppressUnfreed` diagnose their removal.
There is no missing-free warning or alternate manual memory mode. Identifiers,
classes and methods named `free` or `destructor` are ordinary names.
`defer free(value);` defers an ordinary void method call.

Unnamed temporaries, renderings and partially constructed graphs use the same
managed lifetime as other objects. GC7 removes the former free-proof fixed point,
managed destruction/rollback AST and IR, ownership descriptors and old admission
paths. Final linking rejects calls to legacy managed-deallocation symbols.
Conservative value flow, initialization, pool origins, native acquisition,
foreign confinement, collection effects, exact roots and layouts remain. See the
[GC7 implementation and verification](GC7_IMPLEMENTATION.md) and
[disposition ledger](GC7_DISPOSITION.md).

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

## Qualification and remaining boundaries

Normal source, class and archive compilation and the entire main/testing library
are supported. [GC6 managed bridges](GC6_IMPLEMENTATION.md), extended by
[GC9 target/distribution qualification](GC9_LINUX_X86_64_DISTRIBUTION.md), cover
macOS arm64, Linux arm64 and Linux x86-64 within the recorded configurations.
Generated RootHandle anchors and ListenerRegistration ownership are explicit and independent of source closure and collection. Exact
current/suspended segments cover collecting JVM reentry without unwinding Java.
Every independent native image owns its collector and roots. Schema 2 binds
required protocol versions and actual SDK/payload hashes before user execution.
Raw shared IR, unknown foreign lifetimes, async callbacks and raw cross-image
managed references remain unsupported. arm64e remains unqualified. Java
reachability alone is not a native root: an open generated handle or registration
and the exact foreign-root protocol must authorize the lifetime.
GC7 completes repository consumer migration and obsolete managed-free proof removal.
[GC8](GC8_IMPLEMENTATION.md) qualifies bounded critical kernels and repeated
resident graph/reuse phases on native macOS arm64 and, through a separate
[Linux x86-64 follow-up](GC7_GC8_LINUX_X86_64_QUALIFICATION.md), physical Linux.
They include explicit-only collection and stable native resource balances.
Their process memory, collector capacity and cached live counts have distinct
scopes; RSS need not fall after
collection. GC9 completes the focused native/distribution matrix, without
expanding those performance workloads or the complete JDK matrix.
[GC10 acceptance](GC10_ACCEPTANCE.md) closes integration, not compiler capacity,
self-hosting success, release readiness or publication.
