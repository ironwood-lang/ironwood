<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M3 pre-change review

Entry: G1 (the S1 gate) passed at adfa0515194209c1cd4fb2766f3097ea79f51573;
original J0 6bde84df320e9dbc32977a2b665d214ac09bc2d4. M3.1, M3.2 and M3.3
deliver the remaining B1/B2/B7 helpers and the early B5 digests in consumer
order. They prepare S2-S4 and do not port those stages: no AST/IR model
completion, semantic analyzer, backend or Java baseline walker change is made
here. M4-M6 are not started. All work and commits are local on
`before-self-hosting`, without remote operations.

## Scope and first consumers

The M0 source-backed inventory (`m0/deferred/calls.json.gz`) assigns 13,622
calls in 319 API patterns to M3.1, 981 calls in 72 patterns to M3.2 and 5,059
calls in 314 patterns to M3.3, each with its exact source consumer. Each phase
classifies every one of its patterns, as M1 did, into an existing Ironwood API
(A), a recorded port rewrite convention (B) or a delivered helper (D). A
pattern that needs a helper blocks its handoff until the helper passes.

| Phase | Helpers that the inventory requires | First consumers |
| --- | --- | --- |
| M3.1 | B2 stable list sort; sorted key/element snapshots for TreeMap/TreeSet and `sorted()`; min/max first-tie selection; LIFO scope stacks (Q21-Q41) and FIFO reuse (Q01-Q11, Q28, Q40, Q42-Q44); value snapshot sets, fixed-arity lists and list value equality; compiler-local callback interfaces; the shared variant inventory with a missing-treatment check | S2 representations, S3 analyzers (`FunctionAnalyzer`, `SemanticAnalyzer`, `LexicalTypeScopes`, `EscapeSummaryAnalyzer`, generic solver) |
| M3.2 | bounded literal scanning and width-aware folding (BigInteger), UTF-16/UTF-8 length and surrogate helpers (`StringPool.utf8Length`), fixed-delimiter name splitting (`TypeResolver`, `TypeName`), SHA-256 with lowercase hex (`ByteViewIntrinsic.trusted`, `BridgeGeneration.bytesDigest`) | S3 `IntegerLiteralDecoder`, `StaticConstantEvaluator`, `FunctionAnalyzer` constants, `StringPool`, `TypeResolver`, ByteView admission |
| M3.3 | MD5 and the trace GUID, unsigned widening and comparison, bounded byte slices, little-endian reads, the LLVM floating and byte-escape text, purpose-specific scanners replacing the backend regexes, a path-list split, the admitted properties format, streaming file SHA-256, explicit installation and build identity, the runtime-object cache decision | S4 `LlvmEmitter`, `OptimizedTraceMetadata`, `SharedTraceOrder`, `NativeTarget`, `TlsDependency`, `CommandLine.parsePathList`, `RuntimeLibrary`/`CompilerVersion` discovery |

## Contracts to preserve

- B1: copies and snapshots own independent backing and borrow elements; the
  reusable iterator is never consumed or reset; value, identity and linked
  families keep their own equality and order; the null domain stays rejected.
- B2 (SORTING_CONTRACTS): stable order over `[0, size)` only; inactive slots
  never compared or made live; an explicit non-null comparator, even for an
  empty list; comparator failures propagate; at most one bounded scratch
  allocation per nontrivial sort, sized from live elements; no list-to-array
  round trip; comparator-zero equivalence and first representatives for the
  tree rewrites; UTF-16 String order and unsigned GUID/byte order.
- Worklists (WORKLIST_CONTRACTS): newest-first stack reads and traversal,
  head-to-tail copies and restores, FIFO without front shifting, prompt
  clearing of consumed slots, `NoSuchElementException` and null-peek empties.
- Callbacks (CALLBACK_CONTRACTS): primitive boolean signatures, invocation
  order and failure timing; retained callback fields are recorded, never
  assumed non-retaining.
- B5: bit-exact digests of the bytes the Java consumers hash; lowercase
  two-digit hex; MD5 kept for LLVM GUIDs and never replaced.
- B7: Java 21 split, line, surrogate, unsigned and range semantics at the
  admitted call sites; exact `String.format` output for the backend's three
  patterns; diagnostics and failure order of each consumer.
- D132/D133, mandatory memory safety in every unfreed mode, D261/D262 port
  forms (creation-array owners, no service getters, caller-frame builders,
  invocation-lived shared graphs, exhaustive variant switches).

## Shared machinery and safety

The only public library change expected is B2's `ArrayList.sortWithComparator`.
Its consumers are DataStructureSemantics and the D107/D248 item-loan proofs,
the owned backing-array field and its `grow` free, the reusable iterator,
escape summaries of comparator dispatch and generic/interface calls. First
reproduce the current analysis outcome for a sort call; change analysis only
for a demonstrated precision defect, with paired safe/unsafe regressions and
source/class/archive reconstruction. The sort must never cache the backing
array across a comparator call: a callback that grows the list frees the old
array, so every access re-reads the field. No method-name exemption, unknown
non-retaining effect or runtime bookkeeping is permitted.

All other helpers are compiler-private Ironwood sources under
`compiler/src/main/ironwood/ironwood/compiler/port/`, built with
`--unfreed=warn`. They add no compiler analysis, runtime or lowering change.
Provenance: original or independent implementations under the default
license. SHA-256 and MD5 are written from the FIPS 180-4 and RFC 1321
algorithm descriptions, not from any reference or OpenJDK source.

## Focused verification selection

Paired cases for every helper: accepted retirement after the last borrower
against rejected frees of borrowed elements, retained callbacks, captured
state and helper results still observed; normal and exceptional cleanup;
allocation-failure rollback; Java 21 differentials only for Java-compatible
behavior; source, class and archive links at `-O3` with `--unfreed=warn`.

Existing consumers to rerun for the ArrayList change: `generic list families
run at O3`, `pool and data structures allocate nothing after warmup`,
`data structures retain inserted references for safe-free analysis`,
`data structure generic bounds reject primitives at the use site`,
`independent list copies preserve destination and nested payload loans`,
`independent list copy proofs survive source class and archive
reconstruction`, `caller-owned library results survive source class archive
and tree-shaking round trips` and `util compatibility helpers run at O3`.
Hot-path evidence: O3 machine code of the merge loop with devirtualized and
interface comparators, and a deterministic comparison-count and time series.
A program that does not call the new method must produce byte-identical LLVM.

Run `git diff --check` for every change, `./scripts/check-licenses.sh` for
every source change and focused IronDocs generation for the public API. Never
run an unfiltered suite. Unsafe programs are compile-only.

Status: initial review recorded before implementation. The increments below
add the review made as each phase advances.


## List sort increment (D263)

Current behavior, reproduced before deciding on any analysis change: the
strict library build rejected the first draft's `defer free` of the
workspace, because an `arraycopy` whose destination is a parameter array
escapes that array; an element loop does not. A call to the new method is
not an audited container operation, so it exposes the list's stored
elements, exactly as a multi-root `get` already does. An audited sort proof
would only help single-root lists and no consumer needs it, so no shared
analysis changed; the exposure is recorded as a retained limit.

The review's expectation of byte-identical LLVM for non-calling programs was
wrong: adding a method that references `Comparator` renumbers closed-world
type IDs, dispatch slots and string constants. The corrected check compares
function sets and bodies with exactly those numberings normalized, and they
are identical. Focused results are in [SORT.md](SORT.md).

## M3.3 increment (D268, D269)

**Second public library addition.** The emitter's static initializers spell
`Double.doubleToRawLongBits`, whose NaN sign and payload reach the LLVM text
(a negated NaN constant has the sign bit set on every host). The raw-bit
intrinsic is private to `ironwood.lang.Double`, so the port cannot reach it,
and `doubleToLongBits` collapses NaNs. `Double.doubleToRawLongBits(double)`
is added as a wrapper over that intrinsic. Behavioral contract review:

1. Java has the one `double` overload; every Java-valid call, including
   widened `float`, integral and `char` arguments, widens identically in
   Ironwood. A widened float NaN keeps the host conversion's payload in both
   languages. The related `doubleToLongBits` and `longBitsToDouble` are
   unchanged.
2. No native-model constraint applies; the whole Java contract is provided.
3. Nothing is reduced. `Float.floatToRawIntBits` is not added and stays a
   visible compile-time absence.
4. `numeric_helpers` checks signed zero, one, a signaling NaN payload and a
   negative quiet NaN payload at `-O3`; the LLVM text fixture compares the
   raw bits of 100,030 values, NaN payloads from seeded bits and widened
   floats included, with Java.
5. The implementation is original: one call of the existing intrinsic.

No analysis, runtime or lowering changes. The `-O3` inspection is recorded in
[BACKEND.md](BACKEND.md); the IronDocs test covers the new comment.

**Findings that shaped the helpers.** A wrapping `ByteBuffer` makes its
array escape, so SharedTraceOrder's object bytes could never be freed:
`Bytes` reads little-endian values directly. A Properties parse that borrowed
its text would retain it: `PropertiesText` owns a copy. A list of discovered
Paths handed to an analyzed method would expose them, and a failed append
would leak the candidate: `LibraryRoots` is a D163 creation-array owner that
reserves a slot before creating a Path and records a fresh copy no call has
seen, as that proof requires. Java's class path entry is canonical, so the
launcher passes a canonical location.

**Verification selection.** The eleven M3.3 tests (MD5, binary helpers, LLVM
text, LLVM scans, Properties, header scan, installation, build identity,
ownership, failure and the runtime-object evidence); `numeric standard-library
helpers run at O3` and the IronDocs test for the public addition; the
reference-bound audit over every port source; the four SelectiveInlining
tests and the native target, shared trace order and version tests as
unchanged Java consumers; the M3.3 classification; `git diff --check` and
`./scripts/check-licenses.sh`.
