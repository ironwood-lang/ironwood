# Additions Before Self-Hosting

Status: proposed implementation plan, not an accepted language decision or an
implementation claim. Audited on 2026-10-04 at repository commit
`10b44d88fb182f67961bad58d0226921effef8df`, following
[SELF_HOSTING_PLAN.md](SELF_HOSTING_PLAN.md). That document defines migration
stages S0 through S8; this document defines preparation work B0 through B7.

## 1. Recommendation and scope

Ironwood has enough language machinery to begin a self-hosting feasibility
pilot. The immediate additions should concentrate on library contracts that the
compiler depends on: independent collection snapshots, reliable traversal, and
efficient sorting of compiler objects. Validate their ownership behavior using
a real slice of analysis before translating the compiler broadly.

Complete the host and artifact facilities before the corresponding migration
milestones, rather than making all of them prerequisites for the first parser
port. A source-only compiler with a shell driver can demonstrate self-hosting
before native process launching and ZIP support are complete. MD5 is an earlier
dependency than the archive work because optimized trace metadata already uses
it during native code generation.

| Preparation | Needed before | Priority and recommended scope |
| --- | --- | --- |
| B0. Contract inventory and ownership pilot | Broad translation after S1 | First gate; establish practical memory use and semantic equivalence |
| B1. Copies, snapshots, and traversal | Ownership pilot and S3 semantic analysis | First library work; extend the required `ironwood.ds` types only |
| B2. Stable comparator sorting | Large analysis/emission workloads | Early; arbitrary reference objects and predictable scaling |
| B3. Filesystem completion | Native driver and artifact publication | Incremental; temporary paths, real paths, access checks, explicit publication operations |
| B4. Synchronous process execution | Replacing shell orchestration | Small native service; inherited environment and file-based output |
| B5. CRC32, MD5, and SHA-256 | MD5 by S4; the others by their artifact/TLS consumers | Named algorithms with exact byte contracts and reusable state |
| B6. Archive codec and publication integration | S6 artifact parity and S7 Bridge packaging | Preserve current formats, compressed input, validation, and publication guarantees |
| B7. Compiler-local portability helpers | Each translated compiler slice | Explicit walkers, value types, bounded arithmetic, and text/format helpers |

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
| Sorting | `IronDoc`, `SharedTraceOrder`, `OptimizedTraceMetadata`, archive indexes, and Bridge inventories | Reference sorts use insertion sort; even comparator overloads require `T extends Comparable<T>` |
| Native tools | `NativeBackend`, `LlvmToolchain`, `MacNativeTools`, `TlsDependency`, and `BridgeBuildTools` | No corresponding process facility; actual launch sites inherit the environment |
| Filesystem | Driver staging, discovery, archive replacement, Bridge distribution | Core IO, directory traversal, attributes, copy, and move exist; missing operations and publication guarantees remain |
| Archives | `IronClass`, `IronJar`, and Bridge JAR consumers/producers | No ZIP or CRC32 implementation; existing artifacts include DEFLATE data |
| Digests | LLVM trace GUIDs, TLS input identity, Bridge content/generation identity | No matching named digest implementations; serialization rules differ by consumer |

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

## 3. B0: establish contracts and run the ownership pilot

### Deliverables

Create a compiler-port compatibility inventory with one entry per dependency
pattern, recording its callers, equality/ordering requirements, mutation rules,
exception behavior, allocation owner, retained references, and cleanup boundary.
Record the replacement and its evidence. This is more useful than a count of
unsupported Java imports.

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

Set resource budgets from these measurements and the supported development
machines before expanding the migration. Do not infer that native compilation
will be faster or that process-lifetime allocation will be affordable.

### Exit gate

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
`HashMap<K,V>`, `IdentityHashMap<K,V>`, `LinkedHashMap<K,V>`, and the required
set families. Add primitive-container equivalents only when selected compiler
code needs them. These names are proposed Ironwood APIs, not implementations of
Java `clone()` or the Java collection interfaces.

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
name or mark all copies non-retaining. Derive any reusable proof from verified
structure and preserve conservative handling of unknown effects.

The existing [container loan rules](CONTAINER_REMOVAL_LOANS.md) generally
discharge known local loans on whole-container clear/destruction, not after
individual removal. Copying creates another borrower. Clearing the source must
not erase the destination's loan. Per-removal precision is a separate possible
optimization, not a prerequisite for safe snapshots.

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

## 5. B2: stable sorting for arbitrary compiler objects

### Contract and APIs

The existing reference sorts in `ironwood.util.Arrays` are stable insertion
sorts. They can be quadratic on compiler-sized symbol/index arrays. Their
comparator overloads also require `Comparable<T>`, preventing direct use with
ordinary compiler objects that only have an external comparator.

Add proposed helpers `Arrays.sortWithComparator(T[], Comparator<? super T>)`
and its range variant with `T extends Object`. Require an explicit non-null
comparator. This distinct name gives the helper a clear contract without
pretending to support every Java natural-order fallback for arbitrary objects.

Preserve existing `Arrays.sort` overloads and their null-comparator behavior.
Java's reference sorting contract includes stability and natural ordering when
the comparator is null. Removing the generic bound while silently rejecting
that case would violate the behavioral contract review. A future fully
compatible general overload needs a demonstrated type-safe implementation of
the fallback. See the [Java 21 Arrays contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Arrays.html).

For the proposed helper, specify stable order among equal comparator keys,
half-open ranges, unchanged elements outside the range, exact range exceptions,
and propagation of comparator exceptions. A throwing comparator need not leave
the range sorted or restore its original order; private workspace must still
be reclaimed. Whether null elements are sortable depends on the comparator.

### Implementation

Use a stable O(n log n) comparison algorithm, initially a conventional merge
sort with small-range insertion sorting and bounded workspace. Compare a simple
original implementation against a verified Classpath-covered upstream helper
before choosing provenance. Do not copy a large sorting subsystem merely to
support these two entry points.
Share the algorithm with existing reference-sort overloads where their natural
order and null-comparator contracts can be preserved. Avoid maintaining two
independent large-array sorting implementations.

Allocate scratch storage at most once per nontrivial call, never once per merge
or comparison. Preserve fast small-array paths. Only add reusable workspace as
a second API if the compiler profile demonstrates a benefit and its aliasing
contract can be proved. Do not expose raw scratch arrays that invite unproved
input/workspace aliasing or require runtime tracking.

Comparator calls remain ordinary closed-world calls with their actual effects.
They may throw, access captured state, or retain references. Do not grant a
purity or non-retention exemption to enable freeing elements or the input.
Scratch storage remains a separate implementation-owned object.

Replace `TreeMap`/`TreeSet` only where a hash/linked collection followed by a
sorted key snapshot matches the actual algorithm. Queries requiring ordered
navigation or ordered mutation need a separate implementation or algorithm
change. Preserve unsigned GUID ordering, UTF-16 string ordering, and explicit
tie-breakers used for deterministic compiler output.

### Verification and exit gate

Cover non-Comparable objects, comparator supertypes, duplicate-key stability,
empty/singleton arrays, subranges, reverse/random/already-sorted inputs, null
handling, and comparator exceptions. Keep existing natural-order overload tests.
Use differential tests against Java for admitted Java-compatible operations.

Measure comparison counts and time over geometrically increasing sizes, plus
allocation counts and actual compiler inventories. Demonstrate non-quadratic
growth on adversarial orderings. Compare generated O3 code for callback dispatch
and hot loops; use the relevant deterministic benchmark. Primitive sort
optimization is separate unless profiling identifies it as necessary. Any later
floating-point sort change must preserve NaN and signed-zero ordering.

## 6. B3: filesystem operations and publication guarantees

### Reuse the existing surface

`Files` already provides whole-file and stream IO, directory streams,
`walkFileTree`, `readAttributes`, `isSameFile`, copy/move, and basic kind queries.
Obtain modification time through `readAttributes(path).lastModifiedTime()` when
that suffices. Do not build a parallel filesystem abstraction or a generic file
provider framework for this port.

Add the following small surface in dependency order. Signatures are proposals;
Java-shaped names must pass the behavioral contract review before implementation.

| Proposed addition | Contract to establish | First consumers |
| --- | --- | --- |
| `Files.deleteIfExists(Path)` | False only for absence; preserve errors such as permission denial and nonempty directory | Driver and staging cleanup |
| `Files.createTempFile(Path, String, String)` and `createTempDirectory(Path, String)` | Exclusively create the object before returning its fresh path; deterministic cleanup on partial failure | Native output staging and logs |
| Corresponding default-directory overloads | Use a documented native temporary-directory convention; no Java property/resource dependency | Existing driver paths without an output parent |
| `Path.toRealPath()` | Resolve existing paths through the host filesystem, including symlinks; propagate lookup failures | Tool discovery, source deduplication, output identity |
| `Files.isReadable(Path)` and `isExecutable(Path)` | Advisory access checks; actual IO/launch still handles failure | LLVM, SDK, and Bridge tool selection |
| `Files.readAttributesNoFollow(Path)` | Explicit Ironwood helper for final-component link inspection; fresh attribute result | Bridge destination validation |
| `Files.moveAtomicReplacing(Path, Path)` | Explicit atomic replacement or a distinguishable failure; no copy/delete fallback | Verified Bridge JAR publication |
| `Files.moveReplacing(Path, Path)` | Separately specified replacement/failure behavior for callers permitting a non-atomic fallback | `IronJar` fallback policy |

The temporary-file contract must include nullable prefix/suffix behavior admitted
by the selected overloads, invalid path components, permissions, and failure if
the parent does not exist. In Java's contract, null suffix selects `.tmp`; do not
accidentally replace that behavior with a narrower runtime trap. Omit unsupported
attribute-option overloads from the API. The native default temporary directory
and permissions are proposed platform conventions to record before coding.
See the [Java 21 Files contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html).

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

Keep replacement helpers distinctly named. Java's `ATOMIC_MOVE` does not itself
promise portable replacement of an existing target on every provider; the
Ironwood helper must state its supported-host guarantee explicitly. Specify
same-file cases, target directories, final-component symlinks, cross-filesystem
failures, and whether any source removal has occurred when an error is returned.
Atomic visibility is not a claim of crash durability; adding fsync policy would
be a separate decision.

### Compiler/runtime integration and verification

Extend the existing typed file boundary:
[IrFileInstruction](../compiler/src/main/java/ironwood/compiler/ir/IrFileInstruction.java),
intrinsic binding in `FunctionAnalyzer`, dependency discovery in
`TypeDependencyScanner`, `LlvmEmitter`, and isolated runtime functions/declarations.
Audit `AllocationResultSemantics`, escape summaries, return origins, and owned
results for newly allocated paths/attributes. Existing internal no-follow
operations may be reused; a new public options framework is unnecessary.

Preserve error categories sufficiently to distinguish absence, already-exists,
permission failure, and unsupported atomic operation. Do not guess the reason
from an error string. Cleanup must retain the primary failure and report
secondary failures according to the existing exception policy.

Test temporary-name collisions, spaces/Unicode, symlink/dangling-link cases,
same-file and competing-destination cases, nonempty directories, inaccessible
parents, cross-filesystem behavior where available, and allocation failure after
native creation. Verify prior output survives failed Bridge publication. Check
runtime ABI availability against the glibc 2.17 and macOS 11.0 packaging targets
in [IDK.md](IDK.md), not only the developer machine's newer OS.

## 7. B4: a synchronous process facility

### Minimum useful service

The compiler currently needs argument-vector execution, inherited environment,
optional working directory, merged stdout/stderr, completion status, and useful
failure output. LLVM/tool discovery captures output; Bridge builds already
redirect it to log files. No audited launch site requires a mutable child
environment map. Generated Java code and test-only subprocess requirements
should not enlarge the first native API.

Prefer a distinctly named helper, provisionally
`ironwood.process.ProcessRunner.runToFile(String[] command, Path directory,
Path output)`, returning a small `ProcessResult`. This avoids importing the
larger mutable contract of Java `ProcessBuilder`, `Process`, stream piping,
threads, and asynchronous lifecycle control. The result should contain primitive
status fields and own no live process or stream handle.

Proposed conventions to approve and record before implementation:

- Execute the argument vector directly. Support PATH lookup for bare command
  names and direct execution for paths. Never construct a shell command.
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
nonzero child status is a completed process result. Define executable lookup and
ENOEXEC behavior without an implicit shell fallback. Tests must include an
argument containing spaces, quotes, `$`, and shell metacharacters as literal data.

File redirection avoids pipe-capacity deadlocks and unbounded managed buffering.
Discovery code can read a bounded log and strip text as its existing contract
requires; failure diagnostics can report the log location or an explicit bounded
excerpt. Do not silently claim complete captured output after truncating it.

### Native design

Keep the public class ordinary Ironwood. Introduce a small typed process
instruction/boundary with frontend checks, effect descriptions, lowering, and
isolated C support. This is not an excuse to add arbitrary native declarations
or expose pointers to source programs. Extend allocation-result facts only for
actual fresh managed results.

Compare a portable fork/exec/wait implementation with available spawn facilities
against the pinned target baselines. Do not assume newer spawn-with-chdir
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
| SHA-256 | `TlsDependency.sha256`, Bridge byte/content/generation identities | Preserve each consumer's byte serialization and lowercase hexadecimal output |

MD5 here is an established LLVM identity calculation, not a proposed security
primitive. Do not substitute SHA-256 or another hash for it. Conversely, do not
replace SHA-256 artifact integrity checks with the cheaper checksum.

Bridge generation identity is particularly easy to change accidentally.
`BridgeGeneration.digest` starts with its domain string, sorts map keys using
Java string order, and feeds each string as a big-endian 32-bit UTF-16 code-unit
count followed by big-endian 16-bit code units. This deliberately distinguishes
unpaired surrogates. Hashing UTF-8 text or length-prefixed UTF-8 bytes is not
equivalent. `bytesDigest` hashes raw bytes instead. Keep these serialization
helpers above the digest implementation and freeze independent golden vectors.

### Implementation boundary

Add a small Java-shaped CRC32 class only for the supported operations:
construction, reset, `update(int)`, byte-array/range updates, and `getValue()`.
The integer update consumes its low byte; range and unsigned-result behavior
need explicit tests. Do not advertise a larger checksum interface with missing
inherited defaults.

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

Add golden compiler-specific cases: Unicode linkage names, signed-bit GUIDs and
unsigned order, raw file hashes, sorted inventory ordering, empty strings,
supplementary characters, and distinct unpaired-surrogate values. Verify both
trace GUID implementations agree. Test source/class/archive paths, allocation
failure, private-buffer cleanup, and no managed allocation per processed block.
Preserve existing trace and Bridge identity fixtures as integration consumers.

## 9. B6: archive codec and artifact compatibility

### 9.1 Existing formats require more than a STORED ZIP writer

| Profile | Existing writer | Reader/publishing requirements |
| --- | --- | --- |
| `.ironclass` | `ZipOutputStream` default compression, zero entry times; source-bearing payload; direct destination write | Reconstruct source/types; preserve entry-name, duplicate, manifest, and decoding behavior |
| `.ironjar` | Sorted entries, STORED method, explicit size and CRC, zero times | Validate order/index/payloads; embedded `.ironclass` entries may contain DEFLATE; reader uses `ZipFile` |
| Bridge `.jar` | Java manifest first, then sorted remaining entries, default compression, zero times | Preserve Java JAR interoperability, verify staged bytes, atomically publish |

A STORED-only reader cannot read ordinary artifacts produced by the current
compiler. Even the outer `.ironjar` reader should not be narrowed merely because
its current writer chooses STORED. Record the accepted-input matrix separately
from the canonical writer profile.

There are also intentional profile differences. `IronClass.read` skips directory
entries and uses String decoding that replaces malformed UTF-8 in relevant
payloads; `IronJar` applies stricter text/index validation and rejects directory
entries. Bridge puts its manifest first, which differs from a globally sorted
entry sequence. Preserve these distinctions unless a separately reviewed format
change is chosen. Do not unify all readers by silently applying the strictest
existing policy everywhere.

### 9.2 Recommended implementation boundary

Begin with compiler-private archive reader/writer services. Keep container
format parsing separate from raw DEFLATE, entry validation, and the
IronClass/IronJar/Bridge profile logic. Promote reusable pieces into
`ironwood.util.zip` only after their complete admitted contracts are established.
Do not publish a partial `ZipFile` or `ZipInputStream` pretending to implement
all Java-valid calls.

Recommended first implementation to evaluate: original Ironwood ZIP structure
handling plus a pinned, reviewed zlib dependency for raw DEFLATE through a small
typed runtime boundary. Compare that with a pure Ironwood codec before accepting
the dependency. The latter avoids native packaging but brings a substantial
compression/decompression implementation and validation burden. A general
archive framework is unnecessary.

The [ZIP format reference](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT),
[DEFLATE specification](https://www.rfc-editor.org/rfc/rfc1951.html), and
[zlib manual](https://www.zlib.net/manual.html) are the contract references for
this decision. ZIP entry DEFLATE needs the raw stream mode, not a zlib/gzip wrapper.
Pin a concrete dependency release, source digest, build flags, license, and
notices before importing any code. No dependency version is selected by this plan.

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

For canonical `.ironjar` output, preserve the existing exact-byte reproducibility
checks. Compression output may differ between implementations while decoded
contents agree; such a difference needs an explicit recorded compatibility and
reproducibility decision, not an unnoticed relaxation of tests. Native writer
output must itself be deterministic under pinned inputs/tools. Never normalize
away changed source, manifests, identities, or validation outcomes.

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
| Records and sealed hierarchies | Ordinary final classes/interfaces; explicit value operations where needed | Equality/hash/identity distinctions and exhaustive dispatch coverage |
| `BigInteger` literal validation and folding | Bounded checked magnitude scanner plus width-aware primitive arithmetic | Huge invalid literals, minimum signed values, nondecimal bit patterns, wrapping, shifts, casts, and division diagnostics |
| `String.stripIndent` and lexer text helpers | Compiler-local exact text-block normalization | Raw/cooked blocks, closing delimiter, tabs/blank lines, CR/LF, escapes, UTF-16 spans |
| Streams, method-reference pipelines, collectors | Direct loops and small named helpers using B1/B2 | Encounter order, short-circuiting, duplicate behavior, exception timing, and allocation measurements |
| Regex and locale formatting | Purpose-specific scanners and deterministic numeric/string formatting | Existing grammar and malformed inputs; floating raw bits, signed zero, NaN, and rounding |
| Java resources/code-source discovery | Launcher-provided installation root and generated build identity | Checkout and installed layouts, overrides, missing inputs, reproducible identity |
| Weak-reference diagnostic caches | Explicit bounded invocation-owned evidence storage | Same mandatory safety verdicts with explanations on/off; truthful storage-limit fallback |
| Properties/JAR manifest parsing | Helpers for the actual admitted formats | Escaping, continuation, duplicate/ordering rules, and existing generated artifacts |
| `javax.tools` in Bridge compilation | Selected external JDK tools through B4, or shell during transition | Release flags, argument files, diagnostics, failure output, and version selection |

Reflection removal can start in the Java compiler: replace reflective traversal
with typed walkers while the current test harness can compare behavior. No
runtime introspection API is necessary for these fixed compiler-owned data types.
Any generator should produce ordinary source, run deterministically, and fail
when the model inventory and generated coverage disagree. It must not become
an undeclared Java dependency of each native self-build.

Treat producer identity migration as a separate S7 format/design task. Native
compiler identity cannot be fabricated by keeping an expected `Main.class`
inventory entry after that class no longer represents the compiler. The hashing
helpers here preserve existing algorithms; they do not settle the new producer
manifest design.

## 11. Sequence, review boundaries, and completion evidence

### Suggested implementation increments

| Increment | Concrete deliverable | Depends on | Completion evidence |
| --- | --- | --- | --- |
| 1 | B0 inventory and frozen Java/native comparison fixtures | Current compiler | Required contracts and resource measurements can be reproduced |
| 2 | ArrayList/BitSet copy slice and private compiler snapshots | 1 | Independent snapshots; safe/unsafe cleanup pairs; allocation-failure cleanup |
| 3 | Required map/set copies and nested traversal solution | 2 | Identity/value/order preserved; ownership pilot fits its budget |
| 4 | B2 arbitrary-object stable sorting | 1; B1 where snapshot keys are used | Stable deterministic output and measured scaling |
| 5 | B7 reflection/value/text/numeric helpers for selected slices | 1 | Java baseline remains equivalent; native lexer/parser and analysis pilots pass |
| 6 | MD5 and trace GUID integration | 1 | Exact GUIDs and trace-order behavior for S4 |
| 7 | B3 temporary paths, cleanup, discovery, and publication primitives | 1 | Native failure/resource/publication cases pass on qualified hosts |
| 8 | B4 process service and driver adapter | 7 | Controlled process cases plus real LLVM pipeline pass |
| 9 | CRC32/SHA-256 and identity serialization | 1; B2 for sorted inventories | Known vectors and exact existing artifact identities |
| 10 | B6 compressed archive readers, writers, and profile integration | 7, 9; codec dependency decision | Cross-reader compatibility, malformed-input rejection, deterministic output |

These increments can overlap when independent. Do not delay S1 for increments
7 through 10, and do not start broad translation merely because all library
methods now compile. The decisive early gate remains the measured B0/B1 pilot.
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
| Comparator dispatch/effects | Generic/interface calls, captured objects, temporary reclamation | Known safe scratch cleanup; retaining/throwing comparator remains conservative |
| File/process result allocation and errors | Existing IO intrinsics, partial construction, finally/defer | Successful cleanup and failure rollback; no surviving pointer to freed input |
| New typed IR operations | Dependency scanning, closed-world effects, optimization, emission, artifact reconstruction | Live effects survive transformations; unreachable facilities remain removable |
| Checksums/codecs | Trace metadata, TLS inventory, Bridge identity, class/archive loading | Exact known output; malformed or mismatched input fails without false success |

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
- `filesystem mutation and random access helpers run at O3`
- `Files.readAllLines rolls back partial results on OOM`
- `Clang version reporting preserves vendor identity and diagnoses query failures`
- `Ironwood archives create list and reproduce exact bytes`
- `Ironwood archives reject malformed paths indexes and payloads`
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
accepted decisions when behavior changes. Run license checks for source or
distribution changes and `git diff --check` for every increment. Do not mark these
features implemented merely because this plan exists.

Each increment should end with the chosen contract, measured results, focused
verification, remaining platform/format boundaries, and its effect on S0-S8.
The recommended first implementation is **the B0/B1 copy-and-ownership slice**,
followed by B2. That establishes whether the existing language and its explicit
reclamation model can support the compiler at useful scale before the larger
host-service and archive work is undertaken.
