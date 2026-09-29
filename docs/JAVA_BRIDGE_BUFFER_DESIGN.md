<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7c0 bounded byte-view design

Status: P7c0 accepted; P7c1 implementation authorized on 2026-09-28. The
maintainer explicitly approved JVM-managed storage, then requested proceeding
to P7c1 after the remaining shared-type/dependency gate was reported. That
instruction accepts the documented shared `ByteView` dependency. The previous
stop before implementation is superseded for P7c1 only. P7d-P7f remain pending.
P7c1 is now implemented and qualified on all three targets; see the
[evidence and measurements](JAVA_BRIDGE_BUFFER_EVIDENCE.md) and
[implementation log](JAVA_BRIDGE_BUFFER_PROGRESS.md). This document remains the
accepted contract; numerical performance acceptance remains maintainer review.

The accepted design is a small `ironwood.bridge.ByteView` type: allocate reusable
storage in Java, pass it directly to proved native methods, and expose only
bounded byte operations. Java controls the backing memory's lifetime. There is
no public `close()` or `free()`. A small shared Java dependency gives independent
bridge artifacts the same public type.

## Public API

Use the same nominal name, **`ironwood.bridge.ByteView`**, in Ironwood source and
Java. It is final, with no public constructor, subclassing, public fields or
native address. It is a dedicated bridge value, not a subtype or replacement
of either `ironwood.nio.ByteBuffer` or `java.nio.ByteBuffer`.

The following table is the entire accepted and implemented declared surface.
These are API signatures, not complete runnable examples.

| Signature | Java application | Ironwood source | Meaning |
| --- | --- | --- | --- |
| `static ByteView allocate(int length)` | Yes | Absent | Allocate zero-filled writable storage and its initial view. |
| `ByteView slice(int offset, int length)` | Yes | Absent | New identity, sharing the selected byte range; offset is relative to this view. |
| `ByteView asReadOnly()` | Yes | Absent | New identity over the same range, with writes through this view prohibited. |
| `int length()` | Yes | Yes | Fixed number of accessible bytes. |
| `boolean isReadOnly()` | Yes | Yes | This view's immutable permission. |
| `byte get(int index)` | Yes | Yes | Absolute signed byte read. |
| `void put(int index, byte value)` | Yes | Yes | Absolute byte write; no position changes or chaining result. |

Construction and view creation belong to the Java host. Native source receives
views only as synchronous parameters and may forward them to proved helpers.
It has no allocator, wrapping factory or slicing operation for this type in
P7c1. Thus the initial library can consume a Java view but cannot construct one
in a standalone native program. A native-only benchmark uses the equivalent
byte-array kernel; it must perform the same byte operations and checksum.

No position, limit, mark, byte order, bulk operations, typed primitive access,
array wrapping, arbitrary direct-buffer wrapping, memory mapping, resize,
address accessor or raw backing-buffer accessor is present. There is no
`AutoCloseable`, serialization, cloning, `Comparable` or content equality.
Java's inherited `Object` methods keep ordinary Java identity behavior. Native
view use admits null checks and `==`/`!=`; other Object operations, upcasts,
generic/object-container uses and reflective/type operations are rejected at
compilation/producer admission rather than implemented through fake objects.

### Values, bounds and failure order

- Lengths and indices are `int`. Negative allocation length throws
  `IllegalArgumentException`; exhausted Java/direct storage throws
  `OutOfMemoryError`. Zero length is valid. No narrowing `long` overload exists.
- `slice(offset, length)` requires nonnegative operands and
  `offset <= this.length() - length`, after rejecting an oversized length.
  Failure throws `IndexOutOfBoundsException`; validate before creating a view.
  A zero-length slice at the end is valid. Flatten nested slices relative to
  their backing owner after an overflow-safe range proof.
- `get` requires `0 <= index < length`; otherwise
  `IndexOutOfBoundsException`. `put` first checks the view's read-only permission,
  then the index. A read-only write throws `UnsupportedOperationException`,
  even for an invalid index. This distinct API uses the already supported
  exception mapping and does not claim the full NIO exception contract.
- Byte reads preserve the eight bits and sign-extend according to ordinary
  `byte` semantics. Java's usual widening to an `int` index remains allowed;
  a nonconstant `int` value requires an explicit narrowing cast to `byte`.
- Null parameters are allowed. Native source can test null and choose its own
  branch. Invoking a view operation on null throws `NullPointerException` inside
  the protected entry. Do not reject null eagerly and change source behavior.
- `slice` preserves read-only permission; `asReadOnly` cannot make a view
  writable. Read-only is a capability of a view, not an immutable snapshot:
  writes through a writable sibling remain visible to it.
- Every write affects backing storage immediately. Overlapping slices observe
  actual source-order writes. A native exception leaves completed writes in
  place. There is no copy-back, rollback or P7b diagnostic aggregation for a
  byte view. Other admitted arguments/results retain their own existing rules.
- Do not reject a read-only argument merely because some branch might write.
  A branch that performs no write succeeds. Enforce permission at an actual
  `put`; optimizations must preserve preceding effects and exception order.

## Ownership and lifetime

| Entity | Owner and lifetime | Reclamation / invalidation |
| --- | --- | --- |
| Backing direct byte storage | Java, privately allocated by `ByteView.allocate` | Eligible for JVM-managed reclamation only after all views and active JNI references cease to keep it reachable. No deterministic reclamation deadline. |
| Java view and slices | Ordinary Java objects; each keeps the backing owner strongly reachable | Java collection of one view cannot invalidate a surviving sibling. Dropping the initial view is safe. |
| Native borrowed descriptor | Generated adapter's bounded invocation storage | Expires on return/unwind; never an Ironwood heap allocation or public object header. |
| Native access permission | P0 borrowing/non-retention/non-reclamation plus complete typed effects | Compiler rejects escape, publication, return, input `free`, unknown effects and callbacks/reentry. |
| Existing native facades | Existing Ironwood lifetime contracts | D189 explicit `free()` and root/view proofs remain unchanged. |

Only the host library can create a backing allocation. It uses the Java 21
direct-buffer allocation API internally, without exposing that buffer, its
cleaner or an address. Every slice retains the original owner, not just a raw
address. Arbitrary direct buffers, FFM segments and externally closable storage
cannot enter through any public factory. This is the crucial difference from
accepting an arbitrary `java.nio.ByteBuffer` argument.

Native JNI parameters remain rooted throughout the call; keep an explicit local
reference to each backing buffer until all native access has ended, including
failure translation. No `DeleteLocalRef` may precede last access. Use JNI's
direct-buffer address operation only on these owned buffers. Cache class/field
IDs during binding, while retaining their defining class as required by JNI.
This is a proposed construction proof; P7c1 must verify it under GC pressure.
[JNI local-reference lifetime](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html#referencing-java-objects)
and [direct-buffer access](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#nio-support)
provide the underlying host mechanisms.

There is no explicit close state, active-use counter, owner-thread cleanup
queue, lock, global-reference registry or native destructor. JVM cleanup frees
only JVM-owned backing memory; it never enters an Ironwood world or destroys an
Ironwood object on a cleaner thread. D188 confinement and D189 native reclamation
remain intact. The cost is nondeterministic release of off-heap Java storage,
so applications should reuse bounded allocations rather than churn them.
[Java's direct-buffer guidance](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html#direct-vs-non-direct-buffers)
also distinguishes their allocation cost from ordinary heap buffers.

Applications must prevent concurrent access while native code uses the storage,
including access through sibling views. Preserve the existing loaded-world
thread confinement rule; add no thread checks. A view may be reused sequentially
by independent bridge artifacts without acquiring ownership in either world.
Unsupported reflective/Unsafe/native tampering with private backing state is
not a new supported input mechanism.

## Compiler and transport contract

Use a compiler-owned bounded byte-view representation with address, length,
permission and, where observed, identity. Payload addressing never pretends to
be an Ironwood array/object header. Lower byte operations from typed IR, with
existing bounds-check reasoning and protected exception paths. Do not implement
semantics solely through emitted LLVM strings.

Repeated references to the same Java view must preserve native reference
equality, while separate overlapping slices remain distinct identities.
Reuse P7b-style per-invocation identity normalization only where needed for
observed identity; bounded stack descriptors suffice. Different view identities
do not imply disjoint memory: never give overlapping backing ranges `noalias`
or reuse stale loads across possibly aliasing stores. No identity hash table,
heap descriptors or per-element JNI calls are permitted.

One-time binding verifies the exact host-support ABI and resolved Java class,
then caches IDs. The call path acquires metadata, obtains the address and
enters protected native code without payload copying or allocation. Empty views
must work without dereferencing or doing pointer arithmetic on a null address.
If the JVM cannot provide direct access to a nonempty owned buffer, fail before
target execution with `UnsupportedOperationException`; do not silently copy.
JNI direct-buffer support is optional in the specification, so P7c1 must test
its availability on each pinned JVM/target cell.

Permitted costs for review are JNI entry/exit, metadata/address acquisition,
required null/bounds/read-only semantics, and identity normalization when source
code observes identity. Eliminate or specialize bounds/permission checks where
proved; do not promise their elimination for arbitrary code. No lifetime
bookkeeping is needed under the proposed no-close policy. Unrelated scalar and
array entries acquire no byte-view work. These limits preserve D132/D133;
an implementation needing additional safety overhead returns to review.

Admission requires final facts matching the typed program, complete helpers,
initializers, dispatch, cleanup and exceptional paths. Reuse P0 lifetime proofs,
P7b descriptor/proof separation, and source/class/archive revalidation. Add no
borrow exemption solely because a type is named `ByteView`: require the exact
compiler-owned library identity. Reject view results, fields/statics/containers,
constructor parameters, listener signatures, callbacks/reentry, retained views,
native allocation/free of views and unproved effects. Existing return/other
argument conversions still need their own proofs. Unimplemented compositions
remain producer errors, never inferred permissions.

## Packaging choice

Use a Java-only **`ironwood-bridge-values.jar`**, automatic module
**`ironwood.bridge.values`**, package **`ironwood.bridge`**, carrying the one
public type above and private support. Compile for Java 21, keep Java 21-23 and
the existing Java 24+ artifact refusal. Pin its version, ABI and content digest
in each paired artifact. No native loader, world state or per-artifact generation
identity lives in this common type.

Reserve the exact `ironwood.bridge` package for this shared API. The producer
must recognize the verified builtin as an explicit signature dependency, without
requiring `--export ironwood.bridge` or generating a native object facade for it.
Reject application declarations/exports colliding with that package. Existing
artifact-private generated subpackages remain separate. Record this exception
to exact user-export closure in paired metadata rather than silently extending
the selected user packages.

The selected coordinates are `org.ironwood:ironwood-bridge-values:<IDK version>`;
this reserves a local distribution convention, not an existing published artifact.
The IDK would include this jar and its source/licenses. Existing distribution
commands would copy it alongside buffer-using artifacts and declare this ordinary
Maven/Gradle dependency, without
downloading or publishing anything. Artifacts that do not use views retain their
current single-jar behavior. A manual classpath launch supplies both jars; a
build-tool consumer receives the shared dependency transitively. Missing or
incompatible support must fail before native initialization, with an actionable
diagnostic. Independent artifacts must resolve one compatible support class;
test both classpath and module-path composition and preserve existing loader rules.

Do not embed competing copies of `ironwood.bridge.ByteView` into every artifact:
that creates duplicate-class or split-package ambiguity. The current producer's
exact export ownership and generation checks cannot silently make that safe.
The maintainer accepted this narrowly scoped change to the single-jar
distribution contract when authorizing P7c1 after its review gate.

| Alternative | Why not recommended for this first boundary |
| --- | --- |
| Accept arbitrary `java.nio.ByteBuffer` | A direct address and a strong Java reference do not establish ownership of externally invalidatable storage. |
| Extend existing `ironwood.nio.ByteBuffer` | Its allocated/wrapped array ownership and source loans under D123 are different; changing them broadens the task. |
| Explicit close/free of host buffers | Deterministic release needs stale-view and active-use protection or a stronger lifetime mechanism. It requires a separate reviewed cost/ownership design. |
| Artifact-local generated view types | Can preserve one-jar delivery with an explicit public package convention, but independent artifacts have incompatible Java view types and cannot reuse a view directly. Prefer this only if single-jar packaging is mandatory. |
| Shared type copied into every artifact | Conflicts with module/package ownership; reject this shortcut. |
| FFM backing | Changes Java-version/native-access policy and belongs to P7e's separate review. |

## P7c1 verification plan, not execution

The affected consumers are semantic call/escape/retention facts, exact artifact
reconstruction, ABI selection, typed entry lowering, bounds/alias optimizations,
Java/JNI generation, dependency binding and packaging. Preserve P7b and native
ByteBuffer ownership behavior when extending shared analysis. Follow the
[pre-change review](POOL_RELEASE_HELPER_REGRESSION.md#before-a-substantial-change)
again if implementation changes this dependency map.

| Focused check | Required accepted and rejected cases |
| --- | --- |
| API and source/class/archive parity | Exact signatures; null/empty views; helper forwarding; byte widening/narrowing; reject missing factories, free/close, wrapping, escape/return and counterfeit library types. |
| Lifetime and failure | Keep only a slice, discard the initial view, induce GC pressure in child processes; read/write remains valid through normal and exceptional return. No deterministic-GC deadline assertion. |
| Bounds and read-only | Negative/extreme indices, nested/empty slices, overflow-safe ranges; read-only conditional no-write success, actual write refusal, defined permission/index ordering. |
| Aliasing | Same-view equality, distinct equal-range slice inequality, overlapping forward/backward writes and read-after-write at O0/O3; no false disjointness assumptions. |
| Exception containment | Write then throw retains completed writes; failed entry changes nothing; unknown effects/callbacks and input reclamation stay rejected in every unfreed mode. |
| Packaging | Two independent artifacts use one view; wrong/missing support rejects before entry; source/license/hash delivery and classpath/module-path assembly. |
| Allocation and code | Cold allocation counted separately; warmed reuse has zero Java/native allocation and no payload copies; no TLS/registry/counter/lock lifetime work; scalar/array paths unchanged. |
| Linux comparison | Identical byte-array and view kernels at matching sizes, including overlapping ranges; native, Java and bridge checksums, throughput and batch-average latency, exact payload hashes and optimized disassembly. |

Use pinned Java 21-23 and LLVM 23 on Mac ARM64, local Linux ARM64 and physical
Estonia x86-64; Estonia work remains in `~/temp/java-bridge`. Linux is the
performance judge. Use crash-risk lifetime/failure experiments only in child
processes, with no unsafe successful-path contract. No hosted/full suites or
installation are implied. A failed proof blocks admission; a missing useful
zero-copy advantage blocks P7c completion rather than being labeled success.

## Checkpoint and next action

P7c0 is accepted and P7c1 is implemented and qualified within this boundary.
Proofs and measurements are recorded in the [evidence](JAVA_BRIDGE_BUFFER_EVIDENCE.md)
and [implementation log](JAVA_BRIDGE_BUFFER_PROGRESS.md).
Do not advance to P7d or change an accepted contract without authorization.

Design baseline: clean canonical checkout at `a5ab027d`, local `java-bridge`,
required origin fetch/push URLs verified. Reviewed the existing native
`ByteBuffer`, D123 ownership, D188/D189 confinement/reclamation, P7b proofs,
Java signature/export generation, and private callback direct-buffer scratch.
Checked the Java 21 public JNI/NIO specifications, without importing JDK source.
The original P7c0 checkpoint required documentation/link/whitespace consistency
checks only. Subsequent P7c1 implementation and experiments are recorded in the
[evidence report](JAVA_BRIDGE_BUFFER_EVIDENCE.md).
