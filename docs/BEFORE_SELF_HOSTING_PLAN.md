<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Additions Before Self-Hosting

Status: proposed implementation plan, not an accepted language decision or an
implementation claim. Audited on 2026-10-04 at repository commit
`10b44d88fb182f67961bad58d0226921effef8df`. The source baseline is
`37e2dda14a1de7088bd0a079492a6fbec6c156a7` (`0.6.1-beta`), shared with
[SELF_HOSTING_PLAN.md](SELF_HOSTING_PLAN.md): the intervening commit added only
that document. It defines migration stages S0 through S8; this document defines
preparation work B0 through B7.

## 1. Recommendation and scope

Ironwood has enough language machinery to begin a self-hosting feasibility
pilot. The immediate additions should concentrate on library contracts that the
compiler depends on: independent collection snapshots, reliable traversal,
efficient worklists, and sorting of compiler objects. Validate their ownership
behavior using a real slice of analysis before translating the compiler broadly.

Complete the host and artifact facilities before the corresponding migration
milestones, rather than making all of them prerequisites for the first parser
port. A source-only compiler with a shell driver can demonstrate self-hosting
before native process launching and ZIP support are complete. SHA-256 and MD5
are earlier dependencies than the archive work: SHA-256 authenticates the bundled
ByteView declaration during S3 semantic analysis and hashes runtime headers for
the current native-link cache at S4; MD5 supplies optimized trace GUIDs at S4.
Section 8 records the proposed omission of that cache from the one-shot driver.

| Preparation | Needed before | Priority and recommended scope |
| --- | --- | --- |
| B0a. Contract inventory and Java baseline | B1/B7 preparation and S1 pilot | First work; audit hash iteration order, freeze comparison fixtures, and record resource budgets |
| B0b. S1 portability and ownership pilot | Broad translation after S1 | Run S1's gate after the required B1 copies and B7 rewrites; establish practical memory use and semantic equivalence |
| B1. Copies, snapshots, traversal, and worklists | Ownership pilot and S3 semantic analysis | First collection work; independent copies and explicit stack/FIFO replacements for `ArrayDeque` |
| B2. Stable list-level comparator sorting | Large analysis/emission workloads | Early; direct list sorting and `TreeMap`/`TreeSet` consumer rewrites with explicit ordering contracts |
| B3. Filesystem completion | Native driver and artifact publication | Incremental; temporary paths, real paths, access checks, explicit publication operations |
| B4. Synchronous process execution | Replacing shell orchestration | Small native service; resolved executable paths, inherited environment, and file-based output |
| B5. CRC32, MD5, and SHA-256 | SHA-256 by S3 for ByteView analysis; SHA-256/MD5 by S4 native linking; CRC32 by archive consumers | Public `ironwood.util.zip.CRC32`; compiler-private `Md5`/`Sha256`; exact byte contracts and explicit runtime-cache port decision |
| B6. Archive codec and publication integration | S6 artifact parity and S7 Bridge packaging | Preserve compressed input, validation, and publication; evaluate Ironwood inflate plus STORED native output before adding zlib |
| B7. Compiler-local portability helpers | Each translated compiler slice | Explicit walkers, value types, bounded arithmetic, text/format helpers, and Bridge export-name validation |

No evidence from this audit makes reflection, records, sealed classes, streams,
GC, a general FFI, a regex engine, or the Java Collections Framework prerequisites.
Most Java-to-Ironwood syntax translation is straightforward. Java's implicit
allocation lifetime, collection contracts, and host APIs still require explicit
design. A syntactically line-by-line port can retain all allocations until
process termination, but that does not establish acceptable compiler memory use
or preserve snapshot semantics when the collection implementation changes.

The scope here is preparation for a one-shot compiler. A resident language server
needs the separate repeated-request memory gate in S7. Per-element removal loan
discharge, general arenas, and arbitrary cyclic graph reclamation are not part
of this preparation unless the pilot demonstrates a concrete need.

This plan stages work ahead of the public API targets in
[STDLIB_ROADMAP.md, items 4 and 5](STDLIB_ROADMAP.md#later-useful-library-tranches).
It does not propose replacing the reduced `ProcessBuilder`/`Process` target or
the CRC and ZIP/GZIP target. B4 adds a narrower public utility; B6 builds private
archive mechanisms that later public APIs may reuse. B5's public CRC32 is one
slice of item 5. Track self-hosting readiness and roadmap completion separately.

## 2. Source findings that drive the plan

The following are implementation observations, not an inventory of every Java
method name found in source text. In particular, Java emitted by
`bridge/BridgeLoaderSources.java` continues to run in a Java consumer; its use of
Java file-owner and permission APIs does not itself require native Ironwood
equivalents.

| Area | Current compiler demand | Current Ironwood support and remaining gap |
| --- | --- | --- |
| Immutable semantic inputs | AST/IR constructors, `Diagnostic`, `LexResult`, `ParseResult`, and many result objects use `List.copyOf`, `Map.copyOf`, or `Set.copyOf` | `Collections.unmodifiableList` wraps a live `ArrayList`; it is not an independent snapshot |
| Mutable branch state | `FunctionAnalyzer.snapshotOwnership`, restoration/joins, and `RejectedFreeEvidence` preserve multiple versions of maps and sets | Containers exist, but equivalent copy boundaries and reclamation facts must be demonstrated |
| Effect bit vectors | `ClosedWorldEffectAnalyzer` clones `BitSet` values, including summary values | `BitSet.or`, range `get`, and array conversion exist; a direct copy convenience is missing, not the ability to copy bits |
| Identity and ordering | Allocation/IR identities coexist with structural type keys and deterministic insertion/sorted ordering | Identity, value, and linked containers exist; replacing all maps with one family is incorrect |
| Traversal | Nested scans, key/value iteration, and collection callbacks | Many `ironwood.ds` iterators are reused; map iteration yields values with a container-level current-key accessor |
| Sorting | Comparator-based list/stream sorting in documentation, trace ordering, and semantic analysis; `IronJar` indexes use natural String order through `TreeMap` and `sorted()`; no production `Arrays.sort` calls | `ArrayList` has neither sorting nor `toArray`; preserve natural ordering for archive indexes, while non-Comparable compiler objects need B2's explicit-comparator list helper |
| Sorted maps/sets | 68 unqualified `TreeMap` and 11 `TreeSet` constructions, plus fully qualified uses; the only ordered-navigation call found is `BridgeAssembler.firstEntry()` | No equivalent sorted containers; B2 owns sorted snapshots, comparator-equivalent deduplication, and least-key selection |
| Stacks and queues | 42 unqualified `ArrayDeque` constructions, plus fully qualified uses; scope stacks and analysis/specialization worklists | B1 must distinguish LIFO from FIFO: `ArrayList.removeFirst` shifts remaining items, while `ArrayLinkedList` exposes only stack-style end operations |
| Native tools | `NativeBackend`, `LlvmToolchain`, `MacNativeTools`, `TlsDependency`, and `BridgeBuildTools` | No corresponding process facility; actual launch sites inherit the environment |
| Filesystem | Driver staging, discovery, archive replacement, Bridge distribution; 16 `Files.walk` and two `Files.list` call sites | Rewrite stream-based traversal with existing visitors/directory streams; remaining operation gaps and publication guarantees need separate work |
| Archives | `IronClass`, `IronJar`, and Bridge JAR consumers/producers | No ZIP or CRC32 implementation; existing artifacts include DEFLATE data |
| Digests | ByteView declaration authority, native runtime-header cache keys, LLVM trace GUIDs, TLS input identity, Bridge content/generation identity | No matching named digest implementations; serialization rules and required milestones differ by consumer |

Primary source anchors for this audit:

- [FunctionAnalyzer](../compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java),
  [ClosedWorldEffectAnalyzer](../compiler/src/main/java/ironwood/compiler/semantic/ClosedWorldEffectAnalyzer.java),
  [RejectedFreeEvidence](../compiler/src/main/java/ironwood/compiler/semantic/RejectedFreeEvidence.java).
- [ArrayList](../stdlib/src/main/ironwood/ironwood/ds/ArrayList.iron),
  [HashMap](../stdlib/src/main/ironwood/ironwood/ds/HashMap.iron),
  [IdentityHashMap](../stdlib/src/main/ironwood/ironwood/ds/IdentityHashMap.iron),
  [Collections](../stdlib/src/main/ironwood/ironwood/ds/Collections.iron),
  [BitSet](../stdlib/src/main/ironwood/ironwood/util/BitSet.iron),
  [Arrays](../stdlib/src/main/ironwood/ironwood/util/Arrays.iron).
- [NativeBackend](../compiler/src/main/java/ironwood/compiler/backend/NativeBackend.java),
  [BridgeBuildTools](../compiler/src/main/java/ironwood/compiler/BridgeBuildTools.java),
  [Files](../stdlib/src/main/ironwood/ironwood/nio/file/Files.iron),
  [native runtime](../runtime/src/ironwood_runtime.c).
- [IronClass](../compiler/src/main/java/ironwood/compiler/IronClass.java),
  [IronJar](../compiler/src/main/java/ironwood/compiler/IronJar.java),
  [BridgeJarArchive](../compiler/src/main/java/ironwood/compiler/BridgeJarArchive.java),
  [BridgeGeneration](../compiler/src/main/java/ironwood/compiler/bridge/BridgeGeneration.java).

## 3. B0: inventory and the S1 pilot

B0a delivers the inventory and Java-baseline preparation required by
[S0](SELF_HOSTING_PLAN.md#s0-establish-the-baseline-and-comparison-harness):
the compatibility inventory, frozen fixtures, resource measurements/budgets,
and hash-iteration ordering audit. B0b executes the pilot defined in
[S1](SELF_HOSTING_PLAN.md#s1-prove-portability-and-memory-feasibility) once its
preparation dependencies are ready. S0 and S1 define the respective migration
gates; this section details their preparation dependencies and evidence.

### B0a: inventory and Java baseline

Create a compiler-port compatibility inventory with one entry per dependency
pattern, recording its callers, equality/ordering requirements, mutation rules,
exception behavior, allocation owner, retained references, and cleanup boundary.
Record the replacement and its evidence. This is more useful than a count of
unsupported Java imports.

Capture the Java comparison fixtures and resource measurements, and agree S1's
budgets for the supported development machines before evaluating the native
slices. B0a can finish using the current Java compiler and preparatory Java
refactorings; it does not require the native pilot to pass.

### Hash iteration and comparison baselines

Inventory every plain `HashMap`/`HashSet` allocation and classify each traversal,
including views, streams, `forEach`, and indirect traversal through copies or
helper calls. Include factory/collector results with unspecified encounter order;
counting constructor sites alone misses these paths. For each container, record
its source location, key/element equality and hashing, insertion source, traversal
consumers, first order-sensitive operation, classification below, proposed
replacement, and verification fixture. Explicitly mark containers used only for
lookup or membership, with evidence that no traversal escapes that role.

Java-compatible String hashing does not imply Java-compatible iteration order.
Ironwood's [HashMap](../stdlib/src/main/ironwood/ironwood/ds/HashMap.iron) defaults
to capacity 128 and load factor 0.80, uses masked/modulo bucket indexing, and its
[iterator](../stdlib/src/main/ironwood/ironwood/ds/HashMapIterator.iron) walks
buckets and their chains; [HashSet](../stdlib/src/main/ironwood/ironwood/ds/HashSet.iron)
uses that map. Java's [HashMap contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html)
specifies defaults of 16 and 0.75 and gives no iteration-order guarantee.
The compiler's dozens of plain hash containers therefore need a use-site audit
before their order becomes an accidental Java-versus-native test requirement.

| Classification | Required evidence or migration action |
| --- | --- |
| Lookup/membership only | No iteration reaches observable behavior; retain a plain hash container. |
| Order-independent traversal | Prove the result is independent of visit order, including worklist convergence, first-match choices, and diagnostic/explanation selection; retain hashing only when that proof holds. |
| Explicitly ordered before use | Identify the sorting boundary and deterministic tie-breakers; verify that no IDs, IR order, diagnostics, or other observable decisions are assigned before it. |
| Observable or otherwise order-sensitive traversal | Specify the required order and implement it in both compilers: preserve deterministic source/insertion order with a linked container, or sort by stable semantic keys using B2 before the first order-sensitive operation. Preserve language-defined precedence. |
| Unresolved | Trace downstream consumers until classified; block the affected slice's equivalence gate. |

Follow order transitively into lists, immutable snapshots, work queues, emitted
text/artifacts, IR numbering/layout, and diagnostic order. Copying an unordered
source into a linked container only preserves that source's incidental order.
A stable sort also preserves incidental order among equal keys; add deterministic
tie-breakers wherever those ties can affect observations. Do not derive them from
object addresses, identity hashes, or IDs already assigned by unordered traversal.
Keep lookup-only containers hashed rather than replacing every map/set globally;
measure the allocation and runtime cost of the ordered paths selected by the audit.

Make required ordering explicit in preparatory Java refactorings before freezing
the affected comparison fixtures, then port that contract. If this changes
previous output, review and record the change and retain the original baseline
evidence before updating goldens. Do not emulate a particular JDK bucket layout
or hide unexplained differences by sorting diagnostics or IR in the comparison
harness. Verify the chosen contract with colliding keys, resize boundaries,
different capacities/load factors, and equal sort keys; vary insertion order
where the result is required to be independent of it. Repeat comparisons across
fresh processes to catch unstable ordering that one Java/native run can miss.

### B0b: execute the S1 pilot

The lexer/parser slice needs B1's independent child-list copies (increment 2)
and B7's text-block normalization and excluded-syntax rewrites (the S1 subset
of increment 5), including fixed-arity or explicit-array replacements for
`Parser.contiguousKinds(TokenKind...)`. The ownership slice needs B1's
array/bit-set and map/set copies and nested traversal (increments 2 and 3), plus
the B7 value/syntax helpers used by that slice. Include B2 from increment 4
where B0a identifies an ordering dependency. Implement and verify these required
slices before evaluating the S1 exit gate; the remaining B1/B2/B7 work can follow
the needs of later migration stages.

Build two representative native slices alongside the frozen Java baseline:

1. Lexer/parser construction, including independent child-list snapshots and
   diagnostics. Compare tokens, source spans, AST structure, and errors.
2. Ownership/effect-state construction, copy, mutation, join, comparison, and
   retirement. Include branch-heavy and loop-heavy inputs, aliased allocation
   nodes, equal structural keys, and explanation evidence enabled/disabled.

The second slice must model real snapshot structure from `FunctionAnalyzer` and
`ClosedWorldEffectAnalyzer`, not just demonstrate that a small map can be copied.
Preserve which nested values are immutable shared nodes and which are mutable
state requiring another copy. Translating a Java record to a class also requires
the corresponding value equality where convergence or map lookup depends on it.

Start with invocation lifetime for shared graphs. Reclaim only demonstrably
independent temporary arrays, buffers, and containers. Measure phase allocations,
peak RSS, wall time, maximum practical input size, and outstanding temporary
storage after cleanup. Use geometrically increasing generated inputs plus a
representative compiler source slice. Record source hashes, host, flags, tools,
and the baseline for reproducibility.

Compare the native measurements with B0a's agreed budgets before expanding the
migration. Do not infer that native compilation will be faster or that
process-lifetime allocation will be affordable.

### Evidence for the S1 exit gate

- Every inventoried hash-container traversal has an ordering classification;
  unresolved dependencies in a slice block its differential comparison gate.
  Observable order follows the recorded contract in both compilers and passes
  the ordering variations above before broad translation.
- Snapshots remain independent under subsequent mutation; value/identity and
  deterministic ordering match the baseline's actual requirements.
- Nearby unsafe frees remain rejected in every `--unfreed` mode. Using
  `--unfreed=off` may suppress missing-free diagnostics, never safety failures.
- Measured scale fits the recorded resource budgets. If it does not, identify
  the dominant lifetime or allocation pattern and make a focused improvement.
- A safe reclamation form can be expressed without runtime lifetime tracking,
  unsafe exemptions, or assumptions that unknown calls do not retain inputs.

Failure of the memory budget is a reason to revisit a particular representation
or proof, not automatic justification for a new memory-management subsystem.

## 4. B1: independent copies, snapshots, and traversal

### 4.1 Proposed API boundary

Extend the concrete `ironwood.ds` containers used by the port. Prefer explicit
`copy()` methods returning the same concrete type, initially `ArrayList<E>`,
`HashMap<K,E>`, `IdentityHashMap<K,E>`, `LinkedHashMap<K,E>`, and the required
set families. Add primitive-container equivalents only when selected compiler
code needs them. These names are proposed Ironwood APIs, not implementations of
Java `clone()` or the Java collection interfaces.

[D129](DECISIONS.md#d129---complete-basic-indexed-array-list-operations) deferred
collection copy constructors, `toArray`, and list sorting as demand-driven
conveniences. Compiler snapshots and sorting supply that concrete demand. B1
proposes same-type `copy()` methods; it does not require collection interfaces or
a general collection constructor merely to match Java call-site spelling.

For each copy, specify:

- The destination owns independent backing storage, entries, pools, and iterator
  state. No source-owned helper or array becomes destination-owned.
- Keys and elements remain borrowed references. Copying a container neither
  copies user objects recursively nor transfers ownership of them.
- Value-based, identity-based, and insertion-ordered families preserve their
  respective lookup and order contracts. Capacity/load-factor policy must be
  explicit; copying current logical contents need not copy unused pool entries.
- A failure cleans up partially created private storage and leaves the source
  valid. Destruction of either container does not destroy caller-owned items.
- The copy operation does not consume/reset the source's reusable iterator.
  Implement it using internal traversal with local state.

Do not inherit Java `IdentityHashMap` behavior by name alone. The current
Ironwood map uses identity for keys, while parts of its equality behavior use
value equality. Port consumers according to the equality they actually need;
do not change the whole container contract incidentally to support copying.

Keep immutable compiler snapshots as a separate layer. Initially, use
compiler-owned classes with private copied containers and a narrow read API.
Never expose the mutable backing container. A read-only view of a private copy
can work if its owner and destruction order are proved, but a read-only view of
the original mutable builder cannot replace `List.copyOf`.

Do not introduce a universal immutable collection hierarchy in this step.
Compiler AST/IR snapshots can share a small helper once multiple actual consumers
establish its required operations. Preserve null rejection or nullable values
according to each original call site and the selected container's capabilities.
Current reference containers reject null keys/elements/values. Inventory Java
uses of null separately from absence; use an explicit presence/result type or a
reviewed internal sentinel where necessary. Do not silently translate a stored
null into a missing mapping. Broader nullable-container support requires a
demonstrated need and a separate contract decision.

### 4.2 BitSet copying

First implement the pilot using an independently allocated `BitSet` followed by
`or(source)`. This already expresses a logical copy with current APIs. Avoid
`valueOf(source.toLongArray())` in a hot snapshot path because it creates an
intermediate array.

Add `BitSet.copy()` if the pilot benefits from direct storage copying. Define
whether it preserves observable capacity (`size()`) or only logical contents
(`length()` and set bits); do not claim exact `java.util.BitSet.clone()` semantics
without checking both. Prefer capacity preservation for a same-type copy unless
measurement justifies a distinctly named compact copy. Cover empty instances,
trailing zero words, word boundaries, very high bits, and independence in both
directions. The result owns its word array and retains no reference to the source.

### 4.3 Nested traversal

Retain the current allocation-free reusable `iterator()` convention. Port simple
list traversals to indexed loops. Map algorithms that need a stable key set can
copy keys to private storage once and then traverse that snapshot. A key snapshot
still borrows its keys and needs its own lifetime proof.

Where repeated nested scans make key copying expensive, add an explicit
caller-owned cursor API. A proposed map cursor stores its own current key/value
and traversal position; it must not use the container's shared
`getCurrIteratorKey()` state. Merely returning a second value iterator would not
solve that problem. Allocate a cursor once per traversal or reuse it explicitly;
do not allocate an entry wrapper per item.

The cursor borrows the container and any exposed internal state. Freeing the
container while the cursor can still be used must be rejected. Document the
structural-mutation restrictions and test permitted removal paths separately.
Do not add mod-count checks, runtime registries, or per-step allocations just to
detect contract misuse. First check whether compiler loops can avoid mutation
during traversal entirely. New cursors are conditional on demonstrated need;
copies and local traversal are the initial scope.

### 4.4 Ownership analysis work

This addition may need compiler work. The audited rules in
[DataStructureSemantics](../compiler/src/main/java/ironwood/compiler/semantic/DataStructureSemantics.java)
recognize particular containers, methods, and even method-body shapes.
[TemporaryListBorrowAnalysis](../compiler/src/main/java/ironwood/compiler/semantic/TemporaryListBorrowAnalysis.java)
recognizes a limited local-list pattern. A new factory or copy constructor does
not automatically acquire the right ownership proof.

Trace copy facts through escape summaries, symbolic return origins, fresh
borrowing factories, owned-array/field analysis, temporary-borrow analysis, and
call-site reclamation. The required distinction is:

1. The returned container is fresh and owns new internal storage.
2. Its contents borrow the original keys/elements.
3. It need not retain the source container itself if no copied element or
   published alias requires that relationship.

The third statement is conditional. A source container stored in itself, a key
that refers back to it, an escaped cursor, or a callback that publishes state
prevents an unconditional source-independence claim. Do not whitelist a method
name alone across arbitrary types or mark all copies non-retaining. Follow
[D107](DECISIONS.md#d107---track-releasable-caller-item-loans-from-local-data-structures):
recognize audited container implementations on exact constructed types, with
the required callback and lifetime proofs; arbitrary subclasses or same-named
methods receive no exemption. Derive any reusable proof from verified structure
and preserve conservative handling of unknown effects.

D107 also supplies the nearest existing proof shape for a container copy:
"Copied-key map inputs are observed only during copying when their key-reading
callbacks prove non-retaining." Use that precedent to distinguish temporary
observation of the source during copying from the destination's retained element
loans. It does not by itself prove arbitrary container copies source-independent
or discharge the destination's loans.

This does not prohibit explicit classifications for audited library methods.
For example, `EscapeSummaryAnalyzer.isBorrowingFilesFacade` checks the exact
static owner `ironwood.nio.file.Files` and then switches on method names. Such
entries encode reviewed implementation contracts; they are not inferred from
the spelling of an unrelated method. B3 must maintain this existing Files rule
when adding facades, while keeping argument borrowing, return aliases, and
fresh-result ownership distinct.

The existing [container loan rules](CONTAINER_REMOVAL_LOANS.md) generally
discharge known local loans on whole-container clear/destruction, not after
individual removal. Copying creates another borrower. Clearing the source must
not erase the destination's loan. Per-removal precision is a separate possible
optimization, not a prerequisite for safe snapshots.

### 4.5 Stack and FIFO worklists

B1 owns the `ArrayDeque` migration warned about in
[SELF_HOSTING_PLAN.md](SELF_HOSTING_PLAN.md). Extend B0's container inventory with
each deque's operations, initialization order, iteration direction, empty-result
behavior, duplicate/re-enqueue policy, and lifetime. Classify stacks, FIFO queues,
and any mixed-end use before selecting a replacement.

- For LIFO scope stacks such as `FunctionAnalyzer.scopes`, use `ArrayList`
  append/remove-last and indexed peek. Preserve top-to-bottom traversal with
  reverse indexing where needed; replacing push/pop alone can reverse scope
  lookup and cleanup order. Preserve each caller's empty-stack behavior.
- For FIFO worklists such as `PrimitiveGenericSpecializer`'s class/function
  queues and `SelectiveInlining`'s reachability queue, start with a compiler-local
  two-index worklist: append at the write end and advance a read index to dequeue.
  Preserve seed order and items appended while draining, including ordering
  between the specializer's two queues. Dequeue must not move the remaining items.
- If sustained reuse or mixed-end operations require it, B1 includes a focused
  ring-buffer deque in `ironwood.ds`, with amortized O(1) end operations and no
  per-item node/wrapper allocation. Select only the operations demonstrated by
  the inventory and apply the standard API contract review before implementation.

[ArrayList.removeFirst](../stdlib/src/main/ironwood/ironwood/ds/ArrayList.iron)
calls `removeAt(0)`, shifting the live suffix on every dequeue; draining n items
that way costs O(n squared).
[ArrayLinkedList](../stdlib/src/main/ironwood/ironwood/ds/ArrayLinkedList.iron)
provides `addLast`/`removeLast`, not a FIFO dequeue operation. Neither is a
drop-in queue replacement.

Budget worklist storage explicitly. An append-only list retains space for all
items ever enqueued until reset. Reset after draining; for long-running nonempty
queues, use batched compaction with an amortized bound or the ring buffer so
storage tracks peak live occupancy. Measure retained references as well as bytes.
The worklist owns its backing storage and borrows its items; dequeue does not
automatically discharge a loan or authorize freeing an item. Preserve section
4.4's reclamation rules when growing, compacting, clearing, and destroying it.

### Verification and exit gate

Test empty/nonempty copies, equal-but-distinct identity keys, insertion order,
duplicate element references, mutation after copying, nested snapshots, and
allocation failure during construction. Compare semantic fixed-point results
and rejected-free evidence with the Java baseline.

Pair accepted cleanup of independent private storage with rejection of freeing
an element still borrowed by either copy. Add escaped-result, self-containing,
unknown-call, helper-extraction, interface-dispatch, and exceptional-exit cases
when their analysis paths change. Repeat affected cases through source, class,
and archive reconstruction. Completion requires measured pilot-scale copying
without source-iterator corruption and without weakened reclamation proofs.

For worklists, compare processing order and final compiler results against Java,
including nested scope lookup, duplicate/re-enqueued items, enqueue-during-drain,
empty/reuse cycles, and growth/compaction or wraparound. Pair safe backing-storage
cleanup with rejected frees of still-borrowed items. Measure geometrically
increasing workloads, including a wide queue: require amortized O(1) enqueue and
dequeue, no repeated front-shifting, and storage within the recorded B0 budget.

## 5. B2: stable list-level sorting for compiler objects

### Contract and APIs

The compiler sorts lists and materialized streams, not arrays. Direct examples
include `IronDoc`'s types, `DocModel`'s members, `SharedTraceOrder`'s groups, and
`OptimizedTraceMetadata`'s functions. Many semantic and packaging paths use
`stream().sorted()`. There are no production `Arrays.sort` calls in the audited
compiler source. An array-only addition would force manual list-to-array and
array-to-list loops because `ironwood.ds.ArrayList` has neither `sort` nor
`toArray`, adding storage and element transfers to the port.

Make the primary proposal a method on `ArrayList<E>`:
`void sortWithComparator(Comparator<? super E> comparator)`. Retain the existing
`E extends Object` boundary so ordinary compiler objects need not implement
`Comparable`. Require an explicit non-null comparator, with its failure behavior
specified even for an empty list. Natural-order stream operations become calls
with an explicit comparator for the selected element type. Add range sorting or
a public array helper only when a separate consumer needs that surface.

[D122](DECISIONS.md#d122---practical-java-shaped-standard-library-compatibility-expansion)
deliberately bounds existing reference comparator sorting to Comparable elements
so a null comparator can retain natural ordering. Preserve that contract and
its compile-time boundary. The distinctly named list helper avoids claiming
Java `sort(null)` behavior for arbitrary `E`. A Java-shaped `sort` with broader
element support would need a demonstrated type-safe natural-order fallback and
an explicit decision about D122. See the
[Java 21 Arrays contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Arrays.html).
[D129](DECISIONS.md#d129---complete-basic-indexed-array-list-operations) explicitly
deferred list sorting pending demand; this proposal selects that convenience for
the compiler without making `toArray` a prerequisite.

The list operation sorts only `[0, currentSize)`, stably preserving encounter
order among equal comparator keys. Successful sorting preserves size, capacity,
growth settings, and the same borrowed elements; it neither frees elements nor
exposes the backing array. Inactive slots must remain unobserved, including old
references left after clear/removal. Specify iterator behavior without silently
resetting the reusable iterator. Comparator exceptions propagate; partial
reordering is allowed, but private workspace must still be reclaimed.

### Implementation

Sort the list's private live backing range directly using a stable O(n log n)
comparison algorithm, initially conventional merge sort with small-range
insertion sorting and O(n) bounded workspace for n live elements. Keep the kernel
inside the list or an encapsulated implementation helper; do not add a public
backing-array accessor. Existing array overloads may share the algorithm when
their natural-order, range, and null-comparator contracts remain intact, but
array API expansion is not the compiler-facing deliverable.

Allocate scratch storage at most once per nontrivial call, sized from live
elements rather than capacity, never once per merge or comparison. Preserve
small-list paths without scratch allocation. The scratch buffer is algorithmic
workspace, not an additional full copy-out/copy-back adapter. Compare a simple
original implementation against a verified Classpath-covered upstream helper
before choosing provenance. Only add reusable workspace if profiling establishes
a benefit and its lifetime/aliasing contract can be proved.

If direct backing-range access cannot yet be proved safe, record the temporary
fallback explicitly: allocate one n-element array, copy out with indexed `get`,
sort it, write back with indexed `set`, and reclaim the array on every exit.
Account for its n references, two n-element transfer passes, and any separate
sort workspace. That fallback is an interim port path; it does not satisfy the
direct-list allocation gate below.

Comparator calls remain ordinary closed-world calls with their actual effects.
They may throw, access captured state, or retain references. Do not grant a
purity or non-retention exemption to enable freeing elements, the list, or its
backing storage. Audit callbacks that capture the list and may grow or mutate it;
document mutation restrictions without adding runtime misuse tracking, and
preserve mandatory rejection of unsafe reclamation. Scratch storage remains a
separate implementation-owned object.

Port in-place list sorts to the list method. For `stream().sorted().toList()`,
materialize the selected elements once into a destination list, sort that list,
and apply B1's snapshot/read-only boundary where required. Preserve the original
source collection and encounter order before sorting. The destination allocation
required by a non-mutating operation is distinct from an avoidable array adapter.

### Sorted-map and sorted-set consumer rewrites

B2 owns the `TreeMap`/`TreeSet` replacements as well as the sorting primitive.
The navigation audit finds only `firstEntry()` in
[BridgeAssembler](../compiler/src/main/java/ironwood/compiler/BridgeAssembler.java):
after rejecting empty input and duplicate targets, it selects the least target
key's host. Select the first sorted key and look up its host, or perform an
equivalent minimum-key scan; do not select the first insertion or hash entry.
The remaining ordering demand is sorted traversal, so a general navigable-tree
API is not a prerequisite on current evidence.

Use hash/linked containers plus sorted key/element snapshots where the original
lookup and duplicate rules agree with those containers. Sort at every required
observation boundary identified by B0, including keys, values, entries, callbacks,
and copies whose consumers retain encounter order. Rebuild a snapshot after
relevant mutation; avoid sorting again for every lookup. B1 owns snapshot storage
and borrowing contracts, while B2 owns ordering and consumer integration.

Preserve comparator-defined key equivalence as well as iteration order: Java's
[TreeMap](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/TreeMap.html)
and [TreeSet](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/TreeSet.html)
treat keys/elements comparing as zero as equivalent. A hash collection followed
by sorting is valid only when its equality/deduplication gives the same result.
Audit the custom comparator in
[BridgeCustomSnapshotLayout](../compiler/src/main/java/ironwood/compiler/bridge/BridgeCustomSnapshotLayout.java)
and preserve duplicate insertion results, retained representatives, and map-value
replacement behavior. Use a matching canonical key or an explicit sort/deduplicate
rewrite where needed. Preserve UTF-16 String order, existing Path/numeric order,
unsigned GUID ordering, and B0's rules for deterministic tie-breakers.

If a consumer needs frequent ordered observations during mutation, measure the
snapshot strategy before accepting it; a focused ordered structure or algorithm
rewrite remains B2 work if repeated sorting violates the budget.

### Verification and exit gate

Cover non-Comparable objects, comparator supertypes, duplicate-key stability,
empty/singleton lists, reverse/random/already-sorted inputs, explicit
null-comparator failure, and comparator exceptions. Verify size/capacity,
iterator position, and no-null element contracts. Include lists with excess
capacity and clear/remove/reuse histories to prove inactive slots are never
compared or made live. Keep existing array-sort contract tests if its shared
kernel changes. Use Java differential checks for the non-null comparator cases
that match Java list sorting; test the distinctly named helper's extra contract
directly.

Compare rewritten sorted-container consumers with Java for duplicate keys,
comparator-equal objects, shuffled insertion, mutation between observations,
empty/singleton inputs, and BridgeAssembler's least-target host selection.
Include archive/manifest bytes, documentation ordering, and snapshot slot IDs;
completion requires an assigned replacement for every inventoried tree-container
use, with equivalent lookup, duplicate, and traversal behavior.

Measure comparison counts, time, scratch allocation, and peak temporary storage
over geometrically increasing list sizes and actual compiler inventories. The
completion gate requires non-quadratic growth, at most one bounded scratch
allocation per nontrivial direct sort, and no list-to-array round trip or
per-element helper allocation. Exercise representative documentation, trace, and
semantic ordering consumers, including non-mutating stream replacements.
Compare generated O3 code for callback dispatch and hot loops; use the relevant
deterministic benchmark. Primitive list/array sort optimization is separate
unless profiling identifies a need. Any later floating-point sort change must
preserve NaN and signed-zero ordering.

## 6. B3: filesystem operations and publication guarantees

### Reuse the existing surface

`Files` already provides whole-file and stream IO, directory streams,
`walkFileTree`, `readAttributes`, `isSameFile`, copy/move, and basic kind queries.
Obtain modification time through `readAttributes(path).lastModifiedTime()` when
that suffices. Do not build a parallel filesystem abstraction or a generic file
provider framework for this port.

### Rewrite missing stream-based traversal

The audited compiler source has 16 `Files.walk` calls and two `Files.list` calls.
These require compiler-port rewrites, not new APIs returning streams or eager
collections under those names. Preserve
[D122](DECISIONS.md#d122---practical-java-shaped-standard-library-compatibility-expansion),
which keeps `Files.list` absent, and the
[traversal roadmap](STDLIB_ROADMAP.md#next-tranche-directories-and-file-metadata),
which defers both stream-returning operations.

| Current use | Replacement using existing Ironwood APIs |
| --- | --- |
| `Files.walk(root)` for source, class, runtime, and archive inventories | `Files.walkFileTree` with a visitor applying the existing filters/transforms; collect independent results only when later sorting or reuse needs them |
| `Files.walk(directory, maxDepth)` in `IronDoc.selectPackage` | The fixed-arity `walkFileTree(directory, maxDepth, false, visitor)` overload, preserving the recursive versus depth-one selection |
| Reverse-sorted walks for recursive staging cleanup | Delete files during visitation and directories after their children in `postVisitDirectory`, where only child-before-parent order matters; preserve explicit sorting when exact order is observable |
| `Files.list(directory)` in `BridgePackageInputs` and `BridgeDistributionCommand` | `Files.newDirectoryStream(directory)` with explicit iteration, filtering, and B2 sorting where currently required; close the stream on every exit |

Preserve root inclusion, maximum depth, default no-follow traversal, and each
caller's error/diagnostic policy. A no-follow walk followed by
`Files.isRegularFile(path)` can still select a symbolic link to a regular file;
substituting only the visitor's no-follow attributes would change that filter.
Keep deterministic ordering and deduplication explicit. Post-order cleanup must
preserve best-effort versus propagating failures and leave the root until last.

Respect the existing [traversal ownership rules](STDLIB.md): callback Paths and
attributes are borrowed and cannot be retained after the callback. Collect
independently owned copies or derived results with proved independence, not the
borrowed callback objects. Directory-stream entries, in contrast, are fresh
caller-owned Paths; reclaim discarded entries and assign a cleanup owner to
retained ones. Close the stream before freeing its wrapper. Neither collecting
into a list nor sorting transfers ownership of those Path objects.

`Path.endsWith(String)` is absent, but it is not required by
[StandardLibrary.discoverTypes](../compiler/src/main/java/ironwood/compiler/StandardLibrary.java):
that pipeline applies `root.relativize`, then `Path.toString`, then
`String.endsWith(extension)`. Preserve the existing
[String.endsWith](../stdlib/src/main/ironwood/ironwood/lang/String.iron) extension
check; do not replace it with a path-component comparison or add a Path API for
this call site. These traversal rewrites can start with the current library.

### Add the remaining filesystem operations

Add the following small surface in dependency order. Signatures are proposals;
Java-shaped names must pass the behavioral contract review before implementation.

| Proposed addition | Contract to establish | First consumers |
| --- | --- | --- |
| `Files.deleteIfExists(Path)` | False only for absence; preserve errors such as permission denial and nonempty directory | Driver and staging cleanup |
| `Files.createTempFile(Path, String, String)` and `createTempDirectory(Path, String)` | Exclusively create the object before returning its fresh path; deterministic cleanup on partial failure | Native output staging and logs |
| Corresponding default-directory overloads | Reuse Ironwood's `System.getProperty("java.io.tmpdir")`: nonempty `TMPDIR`, otherwise `/tmp` | Existing driver paths without an output parent |
| `Path.toRealPath()` | Resolve existing paths through the host filesystem, including symlinks; propagate lookup failures | Tool discovery, source deduplication, output identity |
| `Files.isReadable(Path)` and `isExecutable(Path)` | Advisory access checks; actual IO/launch still handles failure | LLVM, SDK, and Bridge tool selection |
| `Files.readAttributesNoFollow(Path)` | Explicit Ironwood helper for final-component link inspection; fresh attribute result | Bridge destination validation |
| `Files.moveAtomicReplacing(Path, Path)` | Explicit atomic replacement or a distinguishable failure; no copy/delete fallback | Verified Bridge JAR publication |
| `Files.moveReplacing(Path, Path)` | Separately specified replacement/failure behavior for callers permitting a non-atomic fallback | `IronJar` fallback policy |

The temporary-file contract must include nullable prefix/suffix behavior admitted
by the selected overloads, invalid path components, permissions, and failure if
the parent does not exist. In Java's contract, null suffix selects `.tmp`; do not
accidentally replace that behavior with a narrower runtime trap. Omit unsupported
attribute-option overloads from the API. Temporary-file/directory permissions
remain a platform convention to record before coding.
See the [Java 21 Files contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html).

The default directory already has a native convention:
[System.getProperty](../stdlib/src/main/ironwood/ironwood/lang/System.iron) exposes
`java.io.tmpdir`, and the [runtime](../runtime/src/ironwood_runtime.c) returns
`TMPDIR` when nonempty, falling back to `/tmp` only when it is unset or empty.
Default-directory overloads should obtain that property and delegate to the
explicit-directory implementation, preserving cleanup of temporary String/Path
objects. This property is implemented by Ironwood's runtime and requires no JVM.
Do not add a second environment lookup policy or silently fall back to `/tmp`
when a nonempty configured directory is unusable; propagate the creation error.

Use exclusive host creation, not an `exists`/create sequence. A failure to build
the returned managed Path after native creation must remove the new file or
directory. `free path` only releases the Path object; deleting its filesystem
entry remains a separate responsibility. Real-path resolution must not first
lexically normalize away components whose meaning changes across symlinks.

### Preserve three distinct publication policies

1. `IronJar.write` stages in the destination parent, tries atomic replacement,
   and falls back to replacement only when atomic movement is unsupported.
2. `BridgeJarArchive.publish` verifies staged content and requires atomic
   replacement. It has no non-atomic fallback; earlier output must survive a
   failed build/publication.
3. Bridge distribution/support staging uses moves into destinations that should
   not already exist. This is a no-replace policy, not replacement.

Current Ironwood `Files.move` is intended to refuse an existing destination. Its
C implementation checks with `lstat` and then calls `rename`; it must not be
treated as an atomic no-replace primitive under a competing creator. Audit and
test that boundary before using it for native publication. A stronger
no-replace helper must use a supported host operation or fail explicitly when
the required guarantee cannot be supplied. Treat any existing race fix as its
own behavior change with focused regression coverage.

Resolve the host primitive before implementing that helper. The glibc 2.17
baseline excludes a direct call to the `renameat2` libc wrapper, introduced in
glibc 2.28; the kernel operation dates to Linux 3.15 and its `RENAME_NOREPLACE`
flag also requires filesystem support. A compatible libc ABI alone does not
establish kernel/filesystem capability. See the
[Linux rename contract](https://man7.org/linux/man-pages/man2/rename.2.html).

| Host/path | Candidate and required boundary |
| --- | --- |
| Linux atomic no-replace move | Use the raw `renameat2` syscall with `RENAME_NOREPLACE` behind the isolated C runtime boundary, with guarded syscall-number/flag availability for each supported architecture. Distinguish an existing target from unsupported kernel/filesystem behavior and other errors; never fall back to check-then-rename. |
| Linux file-publication alternative | For a completed regular file on the same filesystem, `link` followed by `unlink` can publish without replacing the target. This is a narrower publication contract, not an atomic move of both names; see the [link contract](https://man7.org/linux/man-pages/man2/link.2.html). |
| macOS no-replace move | Use `renamex_np` with `RENAME_EXCL`, qualifying the SDK/deployment target and filesystem behavior on the macOS 11.0 baseline. Apple documents [exclusive-renaming support](https://developer.apple.com/documentation/foundation/urlresourcevalues/volumesupportsexclusiverenaming) as a volume capability. |

The link/unlink alternative cannot move directories, so it cannot implement
Bridge distribution/support directory publication. Those callers need the
exclusive rename operation or an explicit unsupported-operation failure. For
file publication, linking commits the destination before source cleanup; if
unlink then fails, report successful publication with failed source cleanup.
Do not claim that the move failed without effects or delete the published target
as an automatic rollback. Review this partial-success contract before exposing
the fallback through an existing move API. Unsupported hosts must not silently
weaken no-replace semantics or raise the declared platform baseline.

Keep replacement helpers distinctly named. Java's `ATOMIC_MOVE` does not itself
promise portable replacement of an existing target on every provider; the
Ironwood helper must state its supported-host guarantee explicitly. Specify
same-file cases, target directories, final-component symlinks, cross-filesystem
failures, and whether any source removal has occurred when an error is returned.
Atomic visibility is not a claim of crash durability; adding fsync policy would
be a separate decision.

### Compiler/runtime integration and verification

Trace each new facade and intrinsic through the following consumers. Several
already handle `IrFileInstruction` generically, so adding an operation may need
verification rather than a code change in every file; adding a new instruction
shape or result contract requires revisiting those assumptions.

| Boundary/consumer | Required integration or audit |
| --- | --- |
| `FunctionAnalyzer.fileIntrinsicOperation` and [IrFileInstruction](../compiler/src/main/java/ironwood/compiler/ir/IrFileInstruction.java) | Bind the exact intrinsic owner/signature, validate operation result/operand types, and preserve throwing versus nonthrowing lowering |
| [EscapeSummaryAnalyzer](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java) | Maintain `isBorrowingFilesFacade` and its uses in inferred summaries and `applyAuditedBorrowingContract`; preserve call-scoped borrowing for audited Files parameters |
| `AllocationResultSemantics`, symbolic return origins, and owned-result analysis | Classify genuinely fresh paths/attributes/arrays separately from returned input aliases and dependent borrows |
| [ClosedWorldPruner](../compiler/src/main/java/ironwood/compiler/ClosedWorldPruner.java) and [ClosedWorldEffectAnalyzer](../compiler/src/main/java/ironwood/compiler/semantic/ClosedWorldEffectAnalyzer.java) | Preserve allocation-failure reachability and effects of file operations, including the current non-allocating `LAST_ERROR` distinction in effect analysis |
| [BorrowDispatchAnalysis](../compiler/src/main/java/ironwood/compiler/semantic/BorrowDispatchAnalysis.java) | File reference results currently use conservative compatible-type propagation; new results must not become an empty dispatch set or gain invented non-retention facts |
| [PrimitiveGenericSpecializer](../compiler/src/main/java/ironwood/compiler/semantic/PrimitiveGenericSpecializer.java) and [IrCfgRenamer](../compiler/src/main/java/ironwood/compiler/ir/IrCfgRenamer.java) | Reconstruct file operations with specialized/renamed results and operands, preserving the operation and source span |
| [IrInvokeTerminator](../compiler/src/main/java/ironwood/compiler/ir/IrInvokeTerminator.java), `LlvmEmitter`, and isolated runtime functions/declarations | Preserve eligibility for potentially throwing file operations, normal/unwind paths, runtime ABI calls, and result/error conventions |
| [TypeDependencyScanner](../compiler/src/main/java/ironwood/compiler/TypeDependencyScanner.java) | At the AST level, `isFileIntrinsic` recognizes helper names and adds `OutOfMemoryError` dependencies for declarations/calls; review new helper names here, not as an `IrFileInstruction` visitor |

The Files borrowing classification is an explicit integration requirement.
`isBorrowingFilesFacade` first requires the exact static Files owner, then uses
a name switch. Its audited override prevents abstract `Path.toString` dispatch
from making otherwise borrowed input paths permanently escape. Review every
new Files method and helper, including each admitted overload, and add its name
when the implementation satisfies that borrowing contract. Without the entry,
the existing override does not apply and callers can lose the borrowing fact.
Verify the resulting summary and safe caller cleanup rather than assuming that
a delegating facade automatically inherits the classification.

Do not add every name unconditionally: helpers such as `visitFailed` and
`postDirectory` deliberately retain inferred effects because visitor callbacks
may retain or rethrow an exception. Audit new callbacks, native retention, and
returned aliases before granting a contract. The same-name static-owner switch
does not distinguish overload signatures, so an overload with different effects
requires a more precise predicate, not a blanket borrowing entry. Preserve the
existing foreign-body guard and conservative unknown effects.

Existing internal no-follow operations may be reused; a new public options
framework is unnecessary.

Preserve error categories sufficiently to distinguish absence, already-exists,
permission failure, and unsupported atomic operation. Do not guess the reason
from an error string. Cleanup must retain the primary failure and report
secondary failures according to the existing exception policy.

For each audited borrowing facade, pair a call followed by safe reclamation of
its temporary input Path/String with an unsafe retained-alias or publishing
callback case. Distinguish a returned input Path from a fresh result. Cover
source/class/archive reconstruction, generic specialization, CFG cloning, and
normal/exceptional cleanup so summaries and typed-IR rewrites agree. Include a
missing-classification regression for newly added facades and verify allocation
failure remains reachable after pruning.

Compare traversal rewrites on nested and empty trees, depth-one package scans,
symbolic links to files/directories, missing roots, and iteration failures.
Check selected source/type inventories and sorted outputs, deletion of children
before their parent, and each cleanup failure policy. Pair accepted retention of
independent copies with rejected retention of borrowed visitor Paths/attributes;
check directory-stream closure and entry cleanup on normal and exceptional exits.

Test default-directory selection with `TMPDIR` unset, empty, and nonempty;
confirm explicit-directory overloads ignore it and an unusable nonempty value
fails without another fallback. Compare selection with `java.io.tmpdir`.
Test temporary-name collisions, spaces/Unicode, symlink/dangling-link cases,
same-file and competing-destination cases, nonempty directories, inaccessible
parents, cross-filesystem behavior where available, and allocation failure after
native creation. Verify prior output survives failed Bridge publication. Check
runtime ABI availability against the glibc 2.17 and macOS 11.0 packaging targets
in [IDK.md](IDK.md), not only the developer machine's newer OS.

For no-replace publication, test competing file and empty-directory creators,
existing symlink targets, and unsupported syscall/filesystem paths. If the
file-only link/unlink path is selected, inject source-unlink failure and verify
the reported publication state. Inspect Linux symbol versions to ensure no
`renameat2@GLIBC_2.28` dependency or other baseline violation was introduced;
qualify runtime capability failures separately from successful baseline linking.

## 7. B4: a synchronous process facility

### Minimum useful service

The compiler currently needs argument-vector execution, inherited environment,
optional working directory, merged stdout/stderr, completion status, and useful
failure output. LLVM/tool discovery captures output; Bridge builds already
redirect it to log files. No audited launch site requires a mutable child
environment map. Generated Java code and test-only subprocess requirements
should not enlarge the first native API.

Keep executable discovery in compiler callers so the process facility can omit
PATH search. The launch audit requires two preparatory changes:

- [TlsDependency](../compiler/src/main/java/ironwood/compiler/backend/TlsDependency.java)
  launches bare `xcrun` when `SDKROOT` is unset or blank. Use `/usr/bin/xcrun`,
  matching [MacNativeTools](../compiler/src/main/java/ironwood/compiler/backend/MacNativeTools.java),
  while preserving TLS discovery's existing arguments and `SDKROOT` handling.
  Record that PATH-selected `xcrun` substitutes are no longer selected.
- [LlvmToolchain](../compiler/src/main/java/ironwood/compiler/backend/LlvmToolchain.java)
  already searches PATH for LLVM installations and constructs absolute tool
  paths, but `discoverHomebrewPrefix()` also launches bare `brew`. Resolve that
  executable to an absolute path in compiler-owned discovery before invoking
  the process facility. Preserve formula/candidate precedence and optional
  discovery failure when Homebrew is absent. The current `locateOnPath` returns
  an LLVM installation home, not an executable path, so it cannot be reused
  unchanged for this purpose.

Define and test PATH candidate selection, empty/relative entries, and resolution
failures in that discovery helper. Resolve relative discovery results against
the parent's working directory before launch; child chdir must not change the
selected executable. LLVM/JDK selection and fixed Apple tool paths remain caller
policy. Inheriting PATH for the child does not require searching it in the runner.

Prefer a distinctly named helper, provisionally
`ironwood.process.ProcessRunner.runToFile(String[] command, Path directory,
Path output)`, returning a small `ProcessResult`. This avoids importing the
larger mutable contract of Java `ProcessBuilder`, `Process`, stream piping,
threads, and asynchronous lifecycle control. The result should contain primitive
status fields and own no live process or stream handle.

`ProcessRunner` is a proposed additional public API ahead of roadmap item 4,
with its own supported contract once published. It is not the roadmap's reduced
`ProcessBuilder`/`Process` implementation or a proposal to cancel that work.
A later design may reuse B4's native launch, redirection, wait, and cleanup
mechanisms, but still needs its own pipe/environment/handle-ownership review;
it cannot simply rename this synchronous, file-output helper. Completing B4
records a narrower delivered capability while item 4's public design remains
pending. Those broader APIs are not prerequisites for self-hosting.

Proposed conventions to approve and record before implementation:

- Execute the argument vector directly, requiring an absolute executable path
  in `command[0]`. Reject bare names and relative executable paths; do not search
  PATH or construct a shell command.
- Inherit the environment. A null directory means inherit the current working
  directory. Do not change the parent process's working directory.
- Use noninteractive stdin and merge stdout/stderr into the supplied output
  file. Create/truncate it as specified; compiler callers use fresh staging
  paths. Resolve its path against the parent's directory before child chdir.
- Reject embedded NUL and malformed command structure before launch. Define
  UTF-16-to-host encoding deliberately; test Unicode paths and arguments.
- Wait synchronously, always reap the child, and return normal exit status or
  explicit signal termination. Distinguish launch failure from a child that
  legitimately exits with status 127.
- Borrow arguments/paths only for the duration of the call. Return no child
  handle and retain no managed input after completion.

Missing executables, output-open failures, and failed chdir are launch/IO errors;
nonzero child status is a completed process result. Report invalid executable
format (`ENOEXEC`) as a launch failure without an implicit shell fallback.
Tests must include an argument containing spaces, quotes, `$`, and shell
metacharacters as literal data.

File redirection avoids pipe-capacity deadlocks and unbounded managed buffering.
It adds filesystem work to discovery, which B4 must measure explicitly.

### Discovery probe lifecycle and invocation reuse

`LlvmToolchain.run`, `MacNativeTools.run`, and `TlsDependency.command` currently
read merged stdout/stderr into memory, decode UTF-8, and strip the result. With
`runToFile`, each uncached probe instead needs exclusive temporary-file creation,
child output writes, a read-back after completion, and deletion. This includes
Homebrew/LLVM version probes, Apple SDK/linker queries, and TLS's SDK query;
file-based output does not make their capture allocation-free or IO-free.

Use a compiler-owned probe adapter with B3 temporary paths, preferably under one
lazily created invocation staging directory. Create a fresh log per uncached
probe, wait for completion, close the read handle after bounded read-back, and
preserve the caller's decoding, stripping, empty-output, and failure behavior.
An output limit must produce an explicit probe failure, never a truncated SDK
path/version treated as a successful result. Capture a diagnostic excerpt before
deleting the log; report a log location only if that file is deliberately retained
under a documented diagnostic policy. Clean logs, paths, and private buffers on
success, launch/read/decode failure, and catchable interruption. Retain the primary
failure if cleanup also fails, and remove the staging directory at invocation end.

Prefer lazy reuse of successful discovery facts in a small invocation-owned
context: selected toolchain/version data, Clang version, Apple SDK/linker facts,
and TLS SDK selection when needed. Preserve existing reuse first:
`LlvmToolchain` already stores its LLVM version, and `BridgeProducer` passes its
selected `MacNativeTools` into `NativeBackend.linkShared`. Measure remaining
duplicate probes before adding another cache. Store results rather than log
files or process handles; a reused result needs no new process or temporary log.

Scope reuse to the selected executable/configuration, full probe arguments,
working directory, and relevant inherited environment such as `PATH`, `SDKROOT`,
and `DEVELOPER_DIR`. Different `xcrun` arguments are different queries. Changed
configuration or a new compiler invocation requires fresh discovery. Do not
memoize transient failures or arbitrary build commands, or suppress explicit
input/identity revalidation before publication. Invocation cleanup releases the
retained facts; no static cache or cross-invocation invalidation system is needed.

### Native design

Keep the public class ordinary Ironwood. Introduce a small typed process
instruction/boundary with frontend checks, effect descriptions, lowering, and
isolated C support. This is not an excuse to add arbitrary native declarations
or expose pointers to source programs. Extend allocation-result facts only for
actual fresh managed results.

Compare a portable fork/exec/wait implementation with available spawn facilities
against the pinned target baselines, using direct-path execution rather than
PATH-searching variants. Do not assume newer spawn-with-chdir
extensions are available. A fork implementation must prepare native argument
storage before forking, keep the child path restricted to suitable host calls,
redirect descriptors, and communicate pre-exec errors through a close-on-exec
channel. Clean every descriptor and temporary native buffer on every failure
path. Newly opened internal descriptors must not leak into the executed tool.

Signal handling is a completion gate, not an incidental detail. Select and test
foreground process-group behavior, terminal interruption, EINTR during waiting,
and cancellation cleanup so stopping the compiler does not ordinarily leave an
LLVM tool running. Do not add global managed thread machinery for this. Document
the boundary for uncatchable termination; no library can promise cleanup after
the parent is forcibly killed in every circumstance.

### Verification and exit gate

Use small controlled helper executables for exit codes, signals, cwd,
environment inheritance, literal argv, large output, and descriptor inspection.
Test missing/not-executable tools, output errors, launch failure after setup,
interrupted waits, and repeated calls with no accumulating descriptors or
unreaped children. Include output exceeding pipe capacity even though the new
implementation uses files.

Verify the runner rejects bare/relative executable names and launches the chosen
absolute executable even with an empty or conflicting PATH and a different
child directory. Separately test LLVM/Homebrew discovery and TLS SDK discovery,
including missing `brew`, PATH candidate order, and the fixed `/usr/bin/xcrun`
choice. These caller adaptations must pass before removing launch-time PATH
lookup from the port.

Measure probe launches, temporary-file creates/deletes, bytes written/read,
retained result memory, and elapsed discovery time for cold and repeated requests
within one invocation. Verify repeated identical discovery reuses successful
results, while changed configuration and a fresh invocation probe again. Inject
launch, read-back, oversized-output, and cleanup failures; check diagnostics and
absence of unintended leftover logs/directories. Distinct probes still require
separate capture work even when repeated requests are cached.

Then run selected real LLVM discovery and compile/link workflows, preserving
the `llvm-as` -> `opt` -> `llc` plus Clang/runtime pipeline. Qualify each supported
host locally. Keep the shell driver until this service passes its gate.
Environment customization, async execution, pipelines, timeouts, and public
kill APIs are deferred until a concrete consumer requires them.

## 8. B5: exact checksums and digests

### Three algorithms, distinct uses

| Algorithm | Consumer | Compatibility requirement |
| --- | --- | --- |
| CRC32 | ZIP entries, especially `IronJar` STORED output | ZIP checksum of the uncompressed bytes; unsigned 32-bit value exposed in a `long` |
| MD5 | `OptimizedTraceMetadata` and `LlvmEmitter` trace GUIDs | Hash UTF-8 linkage-name bytes; interpret the first eight digest bytes as a little-endian 64-bit value; preserve unsigned sort order |
| SHA-256 | `ByteViewIntrinsic.trusted` during semantic analysis | Hash the UTF-8 encoding of `SourceFile.content()` and compare lowercase hex with `SOURCE_SHA256`; preserve exact declaration authority by S3 |
| SHA-256 | `NativeBackend.prepareRuntimeObject` via `TlsDependency.sha256` | Hash each runtime header's bytes for the in-process cache key during native linking at S4; explicitly omit this work only when omitting the cache |
| SHA-256 | TLS dependency identity and Bridge byte/content/generation identities | Preserve each consumer's byte serialization and lowercase hexadecimal output |

MD5 here is an established LLVM identity calculation, not a proposed security
primitive. Do not substitute SHA-256 or another hash for it. Conversely, do not
replace SHA-256 artifact integrity checks with the cheaper checksum.

[ByteViewIntrinsic.trusted](../compiler/src/main/java/ironwood/compiler/semantic/ByteViewIntrinsic.java)
is called during function analysis and by Bridge admission checks. Its digest
comparison authorizes the exact bundled declaration, so SHA-256 must be bit-exact
before the S3 port handles ByteView programs, even though full Bridge production
waits until S7. Hash the source content as the current compiler does, without
new whitespace or line-ending normalization. Do not replace the digest check
with a package/type-name check or bypass it to defer hashing until packaging.

[NativeBackend.prepareRuntimeObject](../compiler/src/main/java/ironwood/compiler/backend/NativeBackend.java)
walks the runtime tree and hashes each `.h` file whenever it prepares a runtime
object, including ordinary native links without TLS. These hashes are part of
`RuntimeObjectKey`; the static `RUNTIME_OBJECTS` map stores compiled object bytes
for reuse across links in the same process. This is not a persistent disk cache.

Recommended port decision: omit `RUNTIME_OBJECTS` and its cache-key construction
from the initial one-native-link-per-process driver, and compile the required
runtime objects directly. Different runtime source files have different keys,
so that driver gains no reuse from retaining their object bytes. This explicitly
removes the cache-only header hashing, not SHA-256's ByteView, TLS, or artifact
consumers. Keep SHA-256 available by S4; retain the current hash behavior if the
cache is ported instead. Revisit caching for batch/multiple-link or resident
drivers using measured reuse, bounded storage, and complete input invalidation.
The proposed omission applies to the native port, not to the Java bootstrap.

Bridge generation identity is particularly easy to change accidentally.
`BridgeGeneration.digest` starts with its domain string, sorts map keys using
Java string order, and feeds each string as a big-endian 32-bit UTF-16 code-unit
count followed by big-endian 16-bit code units. This deliberately distinguishes
unpaired surrogates. Hashing UTF-8 text or length-prefixed UTF-8 bytes is not
equivalent. `bytesDigest` hashes raw bytes instead. Keep these serialization
helpers above the digest implementation and freeze independent golden vectors.

### Implementation boundary

Choose a public standard-library class, `ironwood.util.zip.CRC32`, at
`stdlib/src/main/ironwood/ironwood/util/zip/CRC32.iron`. The compiler and B6 archive
services will consume that class. CRC32 has a reusable byte-stream contract and
belongs to item 5, **Compression and checksums**, in
[STDLIB_ROADMAP.md](STDLIB_ROADMAP.md#later-useful-library-tranches). This is the
CRC32 slice of that item, not completion of its ZIP/GZIP APIs. Update `STDLIB.md`
and the roadmap's partial status when implemented; this plan adds no implemented
API claim.

The initial public surface is `CRC32()`, `void reset()`, `void update(int)`,
`void update(byte[])`, `void update(byte[], int, int)`, and `long getValue()`.
Apply the [behavioral contract review](OPENJDK_PORTING.md#behavioral-contract-review)
to every admitted call against the
[Java 21 CRC32 contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/CRC32.html).
The integer update consumes its low eight bits, including widened primitive
arguments; array null/range errors, reset, and nondestructive unsigned-result
reads must have specified compatible behavior beyond the compiler's own uses.

Omit the `Checksum` interface and `update(ByteBuffer)` from this first increment,
with negative compilation coverage for those omissions. Implement the whole-array
overload explicitly: Java obtains it from a
[Checksum default](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Checksum.html#update(byte%5B%5D))
that delegates to the range update, so review subclass dispatch as well as direct
calls. Do not expose an incomplete interface or accept buffer calls that fail at
runtime. The base implementation borrows input arrays only during updates;
verify its actual effects without extending that guarantee to arbitrary overrides.

Initially keep `Md5` and `Sha256` as named compiler helpers unless a separate
library consumer justifies public placement. No provider registry,
algorithm-by-string lookup, reflection, or `java.security` compatibility layer
is needed. A reusable state object should process blocks without per-block
allocation and support a caller-supplied result buffer or a fresh fixed-size
digest result. Specify reset/finalization behavior and internal-buffer ownership.

Use the [MD5 specification](https://www.rfc-editor.org/rfc/rfc1321.html) and
[SHA-256 specification](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) as algorithm
references. Select implementation provenance under `LICENSE_MECHANICS`: an
independent implementation or a verified, exactly pinned, Classpath-covered
translation. Reference source included in a specification is not automatically
default-licensed Ironwood code. Do not require the TLS dependency merely to hash
compiler files.

### Verification and exit gate

Compare against Java digests/CRC for empty data, known vectors, every byte value,
randomized deterministic inputs, and incremental versus one-shot updates. Cover
block/padding boundaries, nonzero offsets, repeated reset, finalization, and
length accounting across large streamed inputs without allocating them whole.

For public CRC32, also cover widened/negative integer arguments, null arrays,
invalid and overflowing ranges, empty ranges, repeated `getValue()` followed by
further updates, whole-array delegation through subclass overrides, and omitted
API compilation failures. Verify no per-update allocation and pair safe input
array reclamation after an ordinary update with rejection when an override retains
that array. Public API completion requires this contract and ownership evidence,
not only ZIP acceptance tests.

Add golden compiler-specific cases: Unicode linkage names, signed-bit GUIDs and
unsigned order, raw file hashes, sorted inventory ordering, empty strings,
supplementary characters, and distinct unpaired-surrogate values. Verify both
trace GUID implementations agree. Test source/class/archive paths, allocation
failure, private-buffer cleanup, and no managed allocation per processed block.
Add ByteView authority cases for the exact bundled source and a changed
declaration, through source/class/archive reconstruction, retaining the same
intrinsic eligibility and admission outcome. For S4, verify direct runtime
compilation after the proposed cache omission; if caching is retained, verify
unchanged inputs reuse objects and header changes invalidate them. Preserve
existing trace, ByteView, and Bridge identity fixtures as integration consumers.

## 9. B6: archive codec and artifact compatibility

### 9.1 Separate legacy input support from native writer policy

| Profile | Existing writer | Reader/publishing requirements |
| --- | --- | --- |
| `.ironclass` | `ZipOutputStream` default compression, zero entry times; source-bearing payload; direct destination write | Reconstruct source/types; preserve entry-name, duplicate, manifest, and decoding behavior |
| `.ironjar` | Sorted entries, STORED method, explicit size and CRC, zero times | Validate order/index/payloads; embedded `.ironclass` entries may contain DEFLATE; reader uses `ZipFile` |
| Bridge `.jar` | Java manifest first, then sorted remaining entries, default compression, zero times | Preserve Java JAR interoperability, verify staged bytes, atomically publish |

A STORED-only reader cannot read ordinary artifacts produced by the current
compiler. Even the outer `.ironjar` reader should not be narrowed merely because
its current writer chooses STORED. Record the accepted-input matrix separately
from the canonical writer profile.

Reading legacy DEFLATED entries requires only inflate. A deflate encoder is
needed only if native writers continue producing DEFLATED entries. Choosing
STORED for native-written `.ironclass` and Bridge JARs, alongside the already
STORED `.ironjar` writer, could eliminate the need for a new archive-codec zlib
dependency. Changing `.ironclass` alone is insufficient if Bridge output still
requires a native deflater. All readers must continue accepting legacy compressed
inputs, including compressed `.ironclass` payloads inside STORED `.ironjar` entries.

There are also intentional profile differences. `IronClass.read` skips directory
entries and uses String decoding that replaces malformed UTF-8 in relevant
payloads; `IronJar` applies stricter text/index validation and rejects directory
entries. Bridge puts its manifest first, which differs from a globally sorted
entry sequence. Preserve these distinctions unless a separately reviewed format
change is chosen. Do not unify all readers by silently applying the strictest
existing policy everywhere.

### 9.2 Recommended implementation boundary

Begin with compiler-private archive reader/writer services using B5's public
`ironwood.util.zip.CRC32`. Keep container
format parsing separate from raw DEFLATE, entry validation, and the
IronClass/IronJar/Bridge profile logic. Promote reusable pieces into
`ironwood.util.zip` only after their complete admitted contracts are established.
Do not publish a partial `ZipFile` or `ZipInputStream` pretending to implement
all Java-valid calls.

B6 is an interim implementation layer below roadmap item 5's later public
ZIP/GZIP APIs. Its helper signatures remain private while existing artifact
compatibility obligations still apply. Extract reusable mechanisms only when a
separately selected public surface passes the behavioral contract review.
B6 completion establishes compiler artifact support, not public ZIP/GZIP support.
In particular, the inflate-only reader and STORED writer option does not complete
public compression or GZIP APIs. B5's public CRC32 advances only the checksum
slice; record the remaining item 5 work explicitly rather than marking the whole
item complete. Replacing either roadmap target would require a separate accepted
decision and roadmap edit, neither of which this plan proposes.

Evaluate the reader and writer choices separately:

| Option | Native implementation and output | Tradeoff and decision gate |
| --- | --- | --- |
| Ironwood inflate plus STORED writers | Original ZIP handling and raw DEFLATE decoder; STORED `.ironclass`, `.ironjar`, and Bridge JAR output | Avoids an archive-codec C dependency and any compressor implementation; review writer-profile changes and measure IO, memory, and artifact size |
| Pinned zlib codec | Original ZIP handling plus a small typed runtime boundary for inflate/deflate; retain DEFLATED `.ironclass` and Bridge output | Reuses a mature codec but adds dependency preparation, native build/link, provenance, and distribution work |
| Ironwood inflate and deflate | Original ZIP handling and both codec directions; retain compressed output | Avoids a codec C dependency but adds the compressor's match search, block selection, and encoding work; do not make this the initial prerequisite |

Recommended first evaluation: prototype the inflate-only option against the
legacy artifact corpus, then review STORED output for both `.ironclass` and
Bridge JARs. Decoding is a substantially smaller implementation scope than a
complete compressor/decompressor, but it must handle all admitted raw DEFLATE
blocks, not merely streams observed in a few fixtures. Keep bounded, reusable
decoder state and validate malformed streams before claiming compatibility.

The STORED choice is a proposed writer-profile change, not an accepted format
decision. Record it in `DECISIONS.md` and the artifact specifications before
changing canonical output fixtures. Specify method, sizes, CRC32, headers/extra
fields, entry order, and zero-time encoding; preserve source payloads, manifests,
indexes, and publication behavior. Verify existing Java readers and JAR consumers
accept the result. Account explicitly for changed archive bytes and identities;
larger artifacts are a measurement input, not an automatic reason to reject this
option. A general archive framework is unnecessary.

The [ZIP format reference](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT),
[DEFLATE specification](https://www.rfc-editor.org/rfc/rfc1951.html), and
[zlib manual](https://www.zlib.net/manual.html) are the contract references for
this decision. ZIP entry DEFLATE needs the raw stream mode, not a zlib/gzip wrapper.
If zlib is selected, build on existing dependency precedents:

- [BridgeNativeSupport](../compiler/src/main/java/ironwood/compiler/backend/BridgeNativeSupport.java)
  already requires `sources/zlib-1.3.1.tar.gz` and verifies it against
  `zlib.source.sha256` in
  [the Bridge support pins](../packaging/java-bridge-support.properties).
  [Its preparation script](../scripts/prepare-java-bridge-support.py) delivers
  that source archive with Linux support binaries and recipes. This is a pinned
  source/distribution precedent, not an existing general-purpose codec library
  for the native compiler. The inflate-only choice does not remove those separate
  Bridge support source-delivery requirements.
- [TlsDependency](../compiler/src/main/java/ironwood/compiler/backend/TlsDependency.java)
  already implements a distribution-relative dependency home, `IRONWOOD_TLS_HOME`
  override, matching `dependencies.properties`/`build.properties`, platform/LLVM
  and configuration checks, SHA-256 file manifests, and failure without a system
  fallback. Reuse that pattern for an independently named codec home and override,
  with B5 hashing; archive use must not require installing the TLS SDK.

Review the existing zlib source pin as a candidate rather than inventing a second
unrelated dependency mechanism. Pin the selected codec release, source digest,
build flags, license, and notices before importing or linking it. No B6 codec
release is selected by this plan; the existing 1.3.1 source pin is a precedent,
not an automatic approval of a new codec build.

Keep dependency reachability explicit: programs that do not use compression
must not acquire its runtime requirements. Validate packaging on the pinned
macOS/Linux baselines rather than silently linking a developer-installed library.

### 9.3 Required behavior and boundary decisions

Build a codec support table from current Java-produced and Java-accepted fixtures:

- STORED and DEFLATED entries, empty entries, streaming data descriptors,
  central-directory offsets, local-header agreement, and checksum verification.
- UTF-8 names, flags, comments/extra fields that must be accepted or skipped,
  zero-time encoding, and deterministic entry ordering. Inspect actual bytes;
  setting a Java timestamp to zero is not a complete ZIP metadata specification.
- ZIP64 counts/sizes/offsets. A large entry count can require ZIP64 even when
  individual payloads fit in Ironwood arrays. Use checked 64-bit parsing before
  narrowing to allocation/index sizes.
- Truncation, overflow, impossible lengths, inconsistent metadata, duplicate
  entries, unsupported methods/flags, and decompression failures.

If the first increment implements a limited profile, label it explicitly and
keep the affected parity gate open. Full format parity cannot be claimed while
silently rejecting previously admitted large or compressed artifacts. General
encryption, split archives, and unrelated compression methods are outside this
plan unless the current compiler actually admits and needs them.

Enforce bounds while parsing and streaming, before allocating from untrusted
lengths. Check CRC and actual decoded length. Any new resource limit must be
documented as an explicit accepted-input policy, not disguised as a decode
failure. Keep decoder working storage reusable and promptly reclaimable.

At the profile layer, retain `IronJar`'s unsafe-path rejection, strict ordering,
canonical type names, duplicate/missing index checks, entry-to-type mapping,
allowed notices, nested-archive rules, and reconstructed source validation.
Archive metadata must never substitute for semantic or ownership proof.
Extraction must validate names before resolving/writing paths. Bridge manifest
parsing and content identity remain separate from generic ZIP validation.

### 9.4 Verification and exit gate

Check Java writer -> native reader and native writer -> Java reader for every
profile, then native round trips and existing malformed-artifact fixtures.
Include payloads that use compressed blocks and descriptors, ZIP64 count cases,
boundary lengths, duplicate names, invalid indexes, malformed text, checksum
failure, and failures during finalization or publication.

For an Ironwood inflater, cover uncompressed, fixed-Huffman, and dynamic-Huffman
DEFLATE blocks, overlapping back-references, window/block boundaries, incremental
input, invalid code trees/distances, truncation, and decoded-length overflow.
Exercise compressed legacy entries through all three artifact profiles. Under
the STORED writer option, verify each new entry's method/size/CRC, Java reader
and Bridge JAR interoperability, and absence of an archive-codec zlib build/link
requirement. Under the zlib option, test missing/mismatched dependency homes,
manifest checksums, explicit overrides, and absence of a system-library fallback.

The existing `Ironwood archives create list and reproduce exact bytes` test in
[CompilerTests](../compiler/src/test/java/ironwood/compiler/CompilerTests.java)
compiles its `.ironclass` inputs once, creates two `.ironjar` files with the same
Java writer using directory input versus reordered explicit file inputs, and
compares those archives with `Files.mismatch`. This establishes same-writer
determinism for identical payload bytes, not agreement with a checked-in Java
golden or Java-versus-native output equality. Preserve that test and apply the
same reordered-input/repeat-run requirement independently to the native writer.

Java-versus-native byte equality of `.ironclass` DEFLATE output is a separate
writer-contract decision, not a property established by the existing archive
test or by reader interoperability. If required, add dedicated cross-writer
fixtures and pin compression settings, codec versions, and ZIP metadata; matching
decoded entries alone does not satisfy that gate. If different compressed bytes
are permitted, record that decision explicitly and compare decoded payloads,
required metadata, and validation behavior while requiring each writer's output
to remain deterministic under pinned inputs/tools.

The proposed STORED `.ironclass` profile deliberately changes container bytes;
embedding those changed bytes also changes the enclosing `.ironjar`. Record this
in the reviewed writer-profile fixtures without weakening same-writer determinism
for identical inputs. Never normalize away changed source, manifests, identities,
or validation outcomes.

Verify staged publication leaves no published partial artifact on failure and
preserves earlier outputs according to each caller's policy. `IronClass.write`
currently writes directly to its destination; adding atomic staging there would
be a separate improvement, not a guarantee to attribute to the baseline.
Measure peak memory for large archives and many small entries. B6 is complete
only when the artifact corpus, validation contract, and publication behavior
all pass, not merely when a sample ZIP opens successfully.

## 10. B7: compiler-local portability helpers

These belong in compiler preparation, not in a general language expansion.

| Java dependency or idiom | Proposed replacement | Required evidence |
| --- | --- | --- |
| Reflection over records/IR components | Explicit typed walkers or checked-in generated visitor code with a reproducible generator | Every variant/operand visited; nested arrays/constants/fields retained; unrelated unreachable code still pruned |
| Records and sealed hierarchies | Ordinary final classes/interfaces; explicit value operations where needed | Equality/hash/identity distinctions; rewritten dispatch satisfies the variant coverage check below |
| Type-pattern `switch` in IR/semantic/Bridge dispatch | Ordered `instanceof`-pattern `if`/`else` chains, following the [self-hosting language audit](SELF_HOSTING_PLAN.md#language-and-data-representation) | Evaluate the selector once; preserve arm order, null behavior, binding scopes, result values, and abrupt exits. Replace lost sealed-switch exhaustiveness with explicit per-consumer variant coverage. |
| `BigInteger` literal validation and folding | Bounded checked magnitude scanner plus width-aware primitive arithmetic | Huge invalid literals, minimum signed values, nondecimal bit patterns, wrapping, shifts, casts, and division diagnostics |
| `String.stripIndent` and lexer text helpers | Compiler-local exact text-block normalization | Raw/cooked blocks, closing delimiter, tabs/blank lines, CR/LF, escapes, UTF-16 spans |
| Small binary/text APIs: unsigned byte comparison, range copies, unsigned widening, UTF-16 conversion, `String.split`, and `String.lines` | Compiler-local helpers and direct scans inventoried in [10.1](#101-small-binary-and-text-helpers) | Caller-specific ordering, bounds, separators, result ownership, and Java differential fixtures before each native slice |
| Varargs and convenience factories, including `Parser.contiguousKinds(TokenKind...)` | Fixed-arity helpers or explicit arrays/builders with clear ownership | Same argument order and empty/nonempty behavior; token adjacency and parser diagnostics preserved |
| `Character.isJavaIdentifierPart(char)` in `DocComment`; `SourceVersion.isName(name, RELEASE_21)` in `BridgePackageInputs` and `BridgeExportSurface` | Shared compiler-local Java 21 identifier predicates: tag-name scanning by S6, qualified-name validation by S7 | Tag boundaries and existing IronDocs diagnostics; empty/dotted components, keywords/literals/contextual keywords, Unicode identifiers, and unchanged export diagnostics |
| Streams, method-reference pipelines, collectors | Direct loops and small named helpers using B1/B2 | Encounter order, short-circuiting, duplicate behavior, exception timing, and allocation measurements |
| Regex and locale formatting | Purpose-specific scanners and deterministic numeric/string formatting | Existing grammar and malformed inputs; floating raw bits, signed zero, NaN, and rounding |
| Java resources/code-source discovery | Launcher-provided installation root and generated build identity | Checkout and installed layouts, overrides, missing inputs, reproducible identity |
| Weak-reference diagnostic caches | Explicit bounded invocation-owned evidence storage | Same mandatory safety verdicts with explanations on/off; truthful storage-limit fallback |
| Properties/JAR manifest parsing | Helpers for the actual admitted formats | Escaping, continuation, duplicate/ordering rules, and existing generated artifacts |
| `javax.tools` in Bridge compilation | Selected external JDK tools through B4, or shell during transition | Release flags, argument files, diagnostics, failure output, and version selection |

Supply the shared Java-identifier-part predicate before S6's `irondoc` port.
[`DocComment.parse`](../compiler/src/main/java/ironwood/compiler/doc/DocComment.java)
uses `Character.isJavaIdentifierPart(trimmed.charAt(end))` to find the tag name
after `@`, then checks `BLOCK_TAGS`. Preserve this UTF-16 `char` scan, including
its use of the part predicate at the first position; do not add identifier-start
or keyword checks, or combine surrogate pairs during this scan. Share the Java
21 character properties with the later Bridge validator while keeping each
caller's traversal and validation rules. Before S6 exits, compare tag boundaries,
parsed tag/value pairs, and unsupported-tag diagnostics against Java for ordinary
tags, BMP letters/digits/combining marks, identifier-ignorable characters,
supplementary pairs, and isolated surrogates.

Complete the qualified-name validator and replace the exported-package checks in
[`BridgePackageInputs`](../compiler/src/main/java/ironwood/compiler/BridgePackageInputs.java)
and [`BridgeExportSurface`](../compiler/src/main/java/ironwood/compiler/bridge/BridgeExportSurface.java)
before S7 export selection. Match the
[Java 21 `SourceVersion.isName` contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.compiler/javax/lang/model/SourceVersion.html#isName(java.lang.CharSequence,javax.lang.model.SourceVersion)):
each dot-separated component must be a Java identifier, excluding reserved
keywords (including `_`), `true`, `false`, and `null`, but allowing contextual
keywords such as `record`. Preserve both callers' null checks and diagnostics,
and the separate `ironwood.bridge` restriction in `BridgeExportSurface`.
Do not reuse the narrower Ironwood lexer grammar for this check. The current
`Character` API lacks `isJavaIdentifierStart`/`isJavaIdentifierPart`; supply the
required code-point predicates/tables privately in the compiler. Freeze
differential fixtures against JDK 21, including non-ASCII letters, supplementary
characters, identifier-ignorable characters, malformed surrogates, and empty,
leading/trailing-dot, or repeated-dot components. This needs no `javax.lang.model`
API or runtime JDK in the native compiler.

The lexer's existing character classification is already available:
[`Lexer`](../compiler/src/main/java/ironwood/compiler/lexer/Lexer.java) allows ASCII
letters, `_`, and `$` at identifier start, then also `Character.isDigit(char)`
for continuation, including non-ASCII digits. Ironwood's
[`Character`](../stdlib/src/main/ironwood/ironwood/lang/Character.iron) already
provides the Java 21 whitespace set and Unicode 15.0 digit predicates. Reuse
these with UTF-16 `char` indexing; no new lexer Unicode-predicate feature is
needed. Keep differential cases for whitespace, non-ASCII digit continuations,
rejected non-ASCII identifier starts, and source spans so translation neither
narrows continuations to ASCII nor broadens identifier starts.

For S1, include `a` followed by U+0661 (ARABIC-INDIC DIGIT ONE) as a declared
identifier in the Java-bootstrap/native comparison corpus. Require one identifier
token with the same spelling and a two-code-unit UTF-16 span; compare rejection
diagnostics when that digit instead starts the declared name.

Reflection removal can start in the Java compiler: replace reflective traversal
with typed walkers while the current test harness can compare behavior. No
runtime introspection API is necessary for these fixed compiler-owned data types.
Any generator should produce ordinary source, run deterministically, and fail
when the model inventory and generated coverage disagree. It must not become
an undeclared Java dependency of each native self-build.

The pattern-switch rewrite is B7 work under excluded Features 106-108, not a
new language prerequisite; supported non-pattern switches can remain. Apply the
same [variant coverage check](SELF_HOSTING_PLAN.md#reflection-really-occurs-in-the-compiler)
to rewritten dispatch and typed walkers. Derive the relevant variant inventory
from model declarations or a checked finite schema independently of the dispatch
arms. Record each consumer's treatment, including intentional no-ops and
rejections; a generic fallback does not establish coverage. Demonstrate that
adding an untreated variant or removing a treatment fails the build-time check.

Treat producer identity migration as a separate S7 format/design task. Native
compiler identity cannot be fabricated by keeping an expected `Main.class`
inventory entry after that class no longer represents the compiler. The hashing
helpers here preserve existing algorithms; they do not settle the new producer
manifest design.

### 10.1. Small binary and text helpers

The following methods are absent from the current Ironwood library. Assign their
replacements to B7 in increment 5, using private, purpose-specific names for
reduced contracts. This inventory does not propose new public `Arrays`, wrapper,
`Character`, regex, or stream APIs. B0a must record the exact overload, admitted
inputs, result ownership, and first consuming slice for each call.

| Missing Java helper | Compiler consumers | Replacement and required evidence |
| --- | --- | --- |
| `Arrays.compareUnsigned(byte[], byte[])` | [`SharedTraceOrder`](../compiler/src/main/java/ironwood/compiler/backend/SharedTraceOrder.java), when root GUIDs tie | Allocation-free unsigned lexicographic comparison of group payload bytes; retain the existing `Long.compareUnsigned` primary GUID ordering. Test equal GUIDs with equal/prefix payloads, differing lengths, and bytes crossing `0x7f`/`0x80`. |
| `Arrays.copyOfRange(byte[], int, int)` | Two probe-section/group copies in `SharedTraceOrder`; UTF-8 round-trip validation in [`BridgeMacPayload`](../compiler/src/main/java/ironwood/compiler/bridge/BridgeMacPayload.java) | Private byte-slice copy using a fresh array and `System.arraycopy`. All three callers validate in-bounds extents; preserve those checks and malformed-object diagnostics. Test empty/full/interior copies and independent result storage. Do not present this bounded helper as the full Java API, which also permits zero-padding beyond the source end. |
| `Integer.toUnsignedLong`, `Byte.toUnsignedInt`, `Short.toUnsignedInt` | `SharedTraceOrder`, `BridgeMacPayload`, [`LlvmEmitter`](../compiler/src/main/java/ironwood/compiler/backend/LlvmEmitter.java), and [`OptimizedTraceMetadata`](../compiler/src/main/java/ironwood/compiler/backend/OptimizedTraceMetadata.java) | Primitive widening and masking with `0xffffffffL`, `0xff`, or `0xffff`, respectively. Widen before the 32-bit mask; prevent sign extension in offsets, UTF-8 bytes, and GUID assembly. Test zero, signed extrema, and all-one bit patterns; no wrapper allocation. |
| `Character.toCodePoint(char, char)` | [`StringPool.utf8Length`](../compiler/src/main/java/ironwood/compiler/semantic/StringPool.java) | Combine the already-validated surrogate pair with primitive arithmetic. Preserve the caller's U+FFFD treatment of unpaired surrogates. Test BMP and supplementary UTF-8 lengths, pair boundaries, and isolated high/low surrogates. |
| `Character.toChars(int)` | [`DocComment.entities`](../compiler/src/main/java/ironwood/compiler/doc/DocComment.java) | Emit the same one or two UTF-16 units into the entity result, preferably without a temporary `char[]`. Test BMP values including surrogate code points, supplementary boundaries, and invalid code points with the existing entity diagnostic. |
| `String.split` | Names in `TypeName`/`TypeResolver`, CLI path lists, artifact indexes and paths, documentation parsing, and Bridge validation | Fixed-delimiter scans plus separate documentation whitespace/line-break scans, preserving each call's limit and empty-field policy; inventory below. |
| `String.lines` | `IronClass` index iteration; two `LlvmToolchain.clangVersion` branches; `MacNativeTools.validate` | Direct line iteration for the index and a first-line scan for probes. Preserve empty-output fallbacks or failure at each caller; avoid allocating every line for a first-line query. |

Match the Java 21 [range-copy](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Arrays.html#copyOfRange(byte%5B%5D,int,int))
and [code-point conversion](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Character.html#toChars(int))
behavior used by these callers; narrowing a private helper requires proving all
its call sites satisfy the narrower contract.

There are 20 compiler-executed `split` calls: 17 with regex literals and three
using the host path separator. The three additional calls inside generated Java
in `BridgeLoaderSources` stay Java. The compiler-side inventory is:

| Separator or pattern | Call sites | Required behavior |
| --- | --- | --- |
| Literal `.`, `/`, `:`, LF, or tab | `TypeName` (1), `TypeResolver` (3), `IronJar` (4), `IronClass` (1), `IronDocOptions` (2), `BridgeLinuxPayload` (1), `BridgeLoaderSources.Payload` (1), `BridgeJarArchive` (1) | Decode the fixed regex literals into delimiter scans. Preserve leading/interior empty fields; negative limits retain trailing empties, while the one-argument calls discard them. Keep empty/no-match behavior and existing validation. |
| Host path separator | `Main.parsePathList`, `BridgeProducerCommand.paths`, `LlvmToolchain.locateOnPath` | The first two retain empty fields and map them to `.`; tool discovery discards trailing empties and skips blank entries. Preserve these different caller policies. |
| `\R` with limit -1; `\s+` with limit 2 | `DocComment.parse`, `head`, and `tail` | Keep Unicode line-break splitting separate from ASCII whitespace-run splitting; limit 2 leaves the remaining tail unsplit, and leading whitespace can produce an empty head. |

Freeze [Java 21 split semantics](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#split(java.lang.String,int))
in fixtures for empty input, no delimiter, repeated/leading/trailing delimiters,
and limits -1, 0, and 2. For the documentation
[regex patterns](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html),
`\R` recognizes CRLF as one separator and also LF, VT, FF, CR, U+0085, U+2028,
and U+2029; unflagged `\s` recognizes only space, HT, LF, VT, FF, and CR.
`Character.isWhitespace` is not a substitute for that set. The four
[`String.lines` calls](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#lines())
recognize only LF, CR, and CRLF, give no lines for empty input, and omit a final
empty line after a terminator. Include those distinctions and consecutive
terminators in the differential corpus.

Complete any `TypeName` splitting reached by the S1 pilot before G1;
`TypeResolver` splitting and `StringPool` conversion are needed by S3, and the
trace/binary helpers by S4. Discovery scans accompany the native driver; archive
and documentation consumers follow at S6 and Bridge consumers at S7. Bring a
helper forward whenever an earlier slice includes its caller. Check allocations
and lifetime proofs for copied arrays, split-result containers/strings, and any
temporary character storage; borrow inputs only for the duration proved by the
implementation, and record any result that aliases an input instead of assuming
every split element is independently owned.

## 11. Sequence, review boundaries, and completion evidence

### Suggested implementation increments

| Increment or gate | Concrete deliverable | Depends on | Completion evidence |
| --- | --- | --- | --- |
| 1 | B0a inventory, per-traversal ordering audit, frozen Java comparison fixtures, and S1 resource budgets | Current compiler | Required contracts, Java reference outcomes, and resource measurements can be reproduced; pilot dependencies identified |
| 2 | ArrayList/BitSet copy slice and private compiler snapshots | 1 | Independent snapshots; safe/unsafe cleanup pairs; allocation-failure cleanup |
| 3 | B1 map/set copies, nested traversal, and stack/FIFO worklist rewrites | 2 | Identity/value/order preserved; amortized O(1) worklist end operations; copy/worklist allocation and storage costs measured |
| 4 | B2 stable comparator sorting directly on `ArrayList` and sorted-map/set consumer rewrites | 1; B1 for snapshots | Non-Comparable lists sort without an array round trip; tree consumers preserve ordering/deduplication with measured scaling |
| 5 | B7 helpers for S1 (text-block, varargs, value, and numeric rewrites), followed by remaining B7 helpers; B5 SHA-256 by S3 | 1; required B1/B2 slices for helpers that use them | Focused helper equivalence and ownership checks pass; Java baseline remains equivalent; exact ByteView declaration authority by S3 |
| G1 (S1 gate) | B0b lexer/parser and ownership-snapshot pilots; the S1 gate from SELF_HOSTING_PLAN.md | 1; required copy/traversal slices of 2 and 3; S1 helpers from 5; 4 where the inventory requires ordering work | S1 exit criteria pass: Java/native equivalence, accepted/rejected reclamation cases, and memory/stack/time within agreed budgets |
| 6 | MD5/trace GUID integration and S4 runtime-cache port decision | 1, 5 | Exact GUIDs, native-link hash consumers, and explicit cache omission or verified invalidation |
| 7 | B3 traversal rewrites, temporary paths, cleanup, discovery, and publication primitives | 1; B2 for sorted inventories | Equivalent inventories and traversal behavior; native failure/resource/publication cases pass on qualified hosts |
| 8 | B4 process service, executable-discovery adaptations, and driver adapter | 7 | Absolute-path launches; measured probe IO, cleanup, and invocation reuse; LLVM/Homebrew/TLS discovery, controlled process cases, and real LLVM pipeline pass |
| 9 | Public `ironwood.util.zip.CRC32` and artifact identity serialization using B5 SHA-256 from increment 5 | 1, 5; B2 for sorted inventories | CRC32 public-contract/ownership review, known vectors, and exact existing artifact identities |
| 10 | B6 legacy inflate reader, selected native writer profiles, and artifact integration | 7, 9; STORED-versus-DEFLATED writer decision and codec selection | Cross-reader compatibility, malformed-input rejection, deterministic output; verified dependency-free codec path or pinned codec home |

G1 is the stable reference for S1's pilot gate in this sequence. These increments
can overlap when independent. Increment 1 comes first; G1 follows its required
slices of increments 2, 3, and 5, plus 4 where
needed. Do not delay S1 for later-stage work in increment 5 or increments 6
through 10. Do not start broad translation merely because all library methods
now compile: the decisive early gate is S1, with B0b recording its evidence.
New cursor APIs, reusable sort workspace, and per-removal loan precision require
evidence from that pilot rather than automatic implementation.

Keep source refactoring, public API changes, shared-analysis changes, runtime
mechanisms, and dependency/package changes reviewable in separate increments.
If a contract change is necessary, record the accepted decision and update its
authoritative documentation when implemented. This plan does not supersede any
existing decision.

### Pre-change review of shared machinery

Following the [regression lessons](POOL_RELEASE_HELPER_REGRESSION.md#lessons-for-future-changes),
record the following map before each implementation increment and revise it if
the scope changes:

| Changed machinery | Existing consumers to protect | Paired evidence |
| --- | --- | --- |
| Borrowing/copy/return-origin proofs | Lists/maps, pool release helpers, array detachment, returned library objects | Fresh private storage can be freed; borrowed/published inputs cannot |
| List sorting and comparator dispatch/effects | ArrayList live-slot boundary and loans, reusable iteration, generic/interface calls, captured objects, backing-array reclamation | Inactive slots stay unobserved; safe scratch cleanup; retaining/throwing or list-mutating callbacks do not weaken reclamation proofs |
| File/process result allocation and errors | Audited Files borrowing classification, return aliases/fresh results, existing IO intrinsics, partial construction, finally/defer | Safe caller-input cleanup; publishing/retaining cases remain conservative; failure rollback leaves no surviving pointer to freed input |
| New typed IR operations | AST intrinsic dependency discovery, closed-world pruning/effects, borrow dispatch, specialization, CFG renaming, invoke handling, emission, artifact reconstruction | Allocation failures and live effects survive transformations; results retain conservative dispatch facts; unreachable facilities remain removable |
| Checksums/codecs | ByteView declaration authority, native runtime-cache keys, trace metadata, TLS inventory, Bridge identity, class/archive loading | Exact known output; changed declarations lose authority; retained caches invalidate on header changes; malformed or mismatched input fails without false success |

Preserve D132/D133 and mandatory memory safety in every mode. New host services
may perform required IO and maintain actual resource state, but must not add
per-call bookkeeping to unrelated steady-state code. Any unavoidable hot-path
safety overhead or unresolved proof requirement needs a concrete design review
before implementation, not a quiet runtime workaround.

### Focused verification selections

Use `./scripts/test.sh --test 'EXACT NAME'` and select from registrations current
at implementation time. Relevant existing names include:

- `generic list families run at O3`
- `object and identity maps run at O3`
- `linked and character-sequence maps run at O3`
- `linked set equality uses hash lookups without resetting the other iterator`
- `pool and data structures allocate nothing after warmup`
- `pool release helper proofs preserve mandatory safety`
- `pool release helper proofs survive artifact reconstruction`
- `rejected-free evidence snapshots retain identity and enforce storage limits`
- `caller-owned library results survive source class archive and tree-shaking round trips`
- `util compatibility helpers run at O3`
- `U2 path and whole-file operations use typed IR and audited ownership`
- `U5 file tree traversal enforces borrowed visitor callbacks`
- `U5 directory foundation enumerates entries and reads attributes`
- `U5 file tree traversal controls depth links and cleanup`
- `native filesystem scratch and resource cleanup survive injected failures`
- `filesystem mutation and random access helpers run at O3`
- `Files.readAllLines rolls back partial results on OOM`
- `Clang version reporting preserves vendor identity and diagnoses query failures`
- `Ironwood archives create list and reproduce exact bytes`
- `Ironwood archives reject malformed paths indexes and payloads`
- `Java Bridge byte-view proofs preserve typed bounds confinement and artifact parity`
- `Java Bridge jar publication verifies bytes and preserves earlier output on failure`
- `Java Bridge shared traces preserve records under deterministic root ordering`

These are existing anchors, not coverage for APIs that do not yet exist. Add
focused regression registrations for each new contract and use Java differential
tests only for Java-compatible behavior. Include parser/semantic/typed-IR/native
and negative tests where a new compiler intrinsic or behavior needs them.
Preserve exact source spans and useful failures for invalid inputs.

Run only the selections relevant to the changed machinery. Shared phases,
merges, and pushes do not authorize an unfiltered suite. Inspect O3 machine code
and run the corresponding deterministic benchmark when hot lowering changes.
Packaging/dependency changes need affected local package/smoke checks and ABI
inspection; hosted three-platform builds remain release-only.

### Documentation, licensing, and final handoff

Before creating or translating source, follow [LICENSE_MECHANICS](LICENSE_MECHANICS)
and the [behavioral contract review](OPENJDK_PORTING.md#behavioral-contract-review).
Choose provenance per implementation. Any OpenJDK-derived file must have verified
Classpath coverage, exact immutable provenance, its full required header, and
updated notices/source distribution. Independently written helpers use the
project's default source license. A zlib choice requires its own pinned source,
build, license, and distribution review.

Update `STDLIB.md`, relevant compatibility/ownership/compiler docs, `IDK.md`, and
accepted decisions when behavior changes. Record B4/B5/B6 delivery separately
from the remaining public API work in `STDLIB_ROADMAP.md`. Run license checks for
source or distribution changes and `git diff --check` for every increment. Do not
mark these features implemented merely because this plan exists.

Each increment should end with the chosen contract, measured results, focused
verification, remaining platform/format boundaries, and its effect on S0-S8.
Begin with **B0a's inventory**, then the required B1 copies and B7 pilot rewrites,
using B2 where the ordering audit requires it. Complete B0b's S1 evidence before
broad translation. That establishes whether the existing language and its
explicit reclamation model can support the compiler at useful scale before the
larger host-service and archive work is undertaken.
