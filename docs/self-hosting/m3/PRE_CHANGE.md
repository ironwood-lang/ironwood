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
