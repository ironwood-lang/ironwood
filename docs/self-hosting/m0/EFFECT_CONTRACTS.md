<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Original effect analyzer contracts

Scope: the complete original ClosedWorldEffectAnalyzer, its 63 exact external
member patterns and 244 uses, two hash origins and nine propagated traversal
sites. This source review does not cover escape, symbolic-return or owned-field
summary analyzers. `review-effects.py` pins the original analyzer and production
consumer sources and rejects incomplete/stale review rows.

## Exact dependencies

| Exact discovery IDs / declarations | Reviewed admitted behavior and selected treatment | Owner, phase, first consumer / required fixture |
| --- | --- | --- |
| API0044 `Class.cast(Object)` reference; API0050 `Class.isInstance(Object)` reference | Only the constant IrInstanceOfInstruction type token, filter then cast. Use typed variant discrimination, preserving the same node identity; no reflection or runtime class loading. The filter excludes null and every other instruction. | B7, M1.3, catch-all fallback recognition / matching and nonmatching tests |
| API0092 `Iterable.forEach(Consumer)`; API0365 `Collection.stream()` | Actual receivers are ordered immutable IR lists, functions-map values, or target lists. forEach is synchronous. Stream pipelines retain their callbacks until the terminal operation in the same expression; they do not escape the helper. Captures borrow analyzer/maps/bit vectors. Use loops in the existing order; retire temporary storage after consumption without freeing IR nodes. | B1/B7, M1.1/M1.3, effect analyzer / source-order traversal and nested destructor/dispatch lists |
| API0136 `String.equals(Object)`; API0163 `String.startsWith(String)` | Class/linkage/label matching is content equality, false for null/other type. Catch-all recognition requires literal catch.next prefix. Use existing text operations; no regex or temporary key object. | B7, M1.3, subtype and catch fallback / equal distinct text, prefix and absent name |
| API0319 `ArrayDeque()`; API0321 `add(E)`; API0322 `add(E)` reference; API0325 `isEmpty()`; API0329 `removeFirst()` | Q20 in WORKLIST_CONTRACTS.md: FIFO labels, enqueue true then false, default then cases, normal then admitted unwind. removeFirst is guarded, labels non-null. Native cursor/ring storage must clear consumed membership. No dequeue shifting or ownership transfer of block labels. | B1/B7, M1.1/M1.3, reachableBlocks / branch, switch, recursive throwing callee and unreachable cleanup |
| API0351 `BitSet()`; API0359 `set(int)`; API0355 `get(int)`; API0353 `clear(int)` reference | Parameter-index bit vectors, initially empty; indices are nonnegative parameter positions bounded by Java list size. set grows storage, get outside storage is false, clear outside storage does nothing. ifPresent clear is immediate on a private clone. Select private primitive-word storage, not boxed sets. Preserve all parameter positions, including beyond one machine word. | B2, M1.2, origins/effects / 0, 63, 64, 127, 128, 256; rendered release exclusion |
| API0352 `BitSet.cardinality()`; API0356 `isEmpty()`; API0357 `nextSetBit(int)`; API0358 `or(BitSet)` | OR is monotone, commutative and idempotent. cardinality change detects strictly added bits, not replacement; no bits are cleared in the accumulated origin map. nextSetBit is ascending, -1 when exhausted; parameter bound excludes overflow of parameter+1. may-reclaim is diagnostic suppression only, never proof authorizing free. Private word loops must preserve union and exact logical equality. | B2, M1.2, fixed-point origins / cross-word bits, repeated/empty union, transitive return and reclaim |
| API0354 `BitSet.clone()` | Independent storage/content equality, including capacity-independent logical equality/hash. origin always clones before rendered-string clear; Summary clones all three inputs so later mutations do not change a previous fixed-point fact. Foreign Effect published/reclaimed vectors are separate copies. Retire owned vector storage after analyzer/results stop referencing it; borrowed summaries are not independently freed by each caller. | B1/B2, M1.1/M1.2, Summary comparison / clone mutation, stable fixed point and distinct foreign result vectors |
| API0406 `HashSet.add(E)` reference; API0438 `LinkedHashSet()`; API0567 `Set.add(E)`; API0568 `Set.add(E)` reference; API0572 `Set.contains(Object)` | HashSet.add is the inherited declaration on an actual LinkedHashSet, not a plain hash origin. Reachable/visited names use content equality; target deduplication uses IrFunction record value equality while preserving first insertion. No null names/targets enter valid typed IR. Native private ordered set must not change duplicate/first-target order or use identity instead of function value equality. Retire set storage after freezing/consuming membership. | B1/B7, M1.1/M1.3, target closure / repeated dispatch entries, interface cycle, equal target records |
| API0414 `IdentityHashMap()`; API0490 `Map.computeIfAbsent(K,Function)` | targetCache requires instruction identity, even for distinct structurally equal records. Mapper executes on a cache miss, produces a non-null immutable list, and is not retained. Cache stores targets only, never changing summaries. Origins computeIfAbsent uses primitive value-id equality and allocates a vector once per absent id. No callback may free captured analyzer or operands. Select explicit private cache lookup/insert and primitive-id map. | B1/B7, M1.1/M1.3, targets/mergeOrigin / distinct equal instructions, empty cached target list, evolving callee effect |
| API0428 `LinkedHashMap()`; API0503 `Map.put(K,V)`; API0511 `Map.values()` | functions and summaries preserve first linkage insertion order; duplicate linkage replaces value without moving position. Compiler-generated unique linkage/SSA/block IDs are the typed-IR input contract, not a new runtime scan. Fixed-point round traversal and diagnostic order remain source function order. origins/conversions/blocks use primitive or text keys and private lookup. Native ordered storage must preserve replacement and iteration semantics. | B1/B7, M1.1/M1.3, analyze/validate / colliding Aa/BB linkages, recursive calls, diagnostic order |
| API0497 `Map.get(Object)`; API0498 same member reference; API0499 `getOrDefault(Object,V)` | Absence means null only for maps with non-null values; filters explicitly discard missing function resolutions. getOrDefault evaluates the empty Summary/BitSet default eagerly in original Java. Private native lookups may avoid constructing an unused empty value without changing facts, exceptions or observable callbacks. Values are borrowed; origin then clones its selected vector. | B1/B2/B7, M1.1/M1.2/M1.3, call effects / missing target, missing origin and no mutation of stored vectors |
| API0496 `Map.forEach(BiConsumer)`; API0585 `TreeMap()`; API0480 `Map.copyOf(Map)` | observerProjection reads each complete summary and inserts non-null text keys/values into a natural-order TreeMap, then freezes extensional map membership. copyOf does not preserve that encounter order. Actual test observers copy/read/compare facts by key; listed facts are explicitly sorted where output matters. Select a private independent fact snapshot; never serialize its unspecified iteration as semantic order. Retire builder and snapshot storage according to observer ownership; summaries' text is retained result data. | B1/B7, M1.1/M1.3, selected EFFECT projection / extensional equality under target/function permutation and unchanged diagnostic order |
| API0441 `List.copyOf(Collection)`; API0442 `List.of()`; API0443 `List.of(E)` | Independent immutable ordered membership; copy/of reject null elements. Empty/singleton direct targets and class snapshot borrow IR objects. Target lists preserve first encountered function order. No deep IR copy; shared empty storage needs a defined lifetime. | B1, M1.1, targets/classes / later input mutation, duplicate first encounter and empty initialization target |
| API0454 `List.add(E)`; API0459 `contains(Object)`; API0462 `get(int)`; API0463 `getFirst()`; API0467 `isEmpty()`; API0475 `size()` | Diagnostic append order follows function source order then allocation, outward-throw, publication checks. contains uses String content; get returns borrowed argument/parameter. Bounds are established by current guards and typed-IR invariants. reachableBlocks requires at least one block; no newly invented fallback for invalid internal IR. isRenderedStringRelease checks both counts before first/index access. | B1/B7, M1.1/M1.3, summarize/validate / empty target, two-parameter release shape, unchanged diagnostic sequence |
| API0521 `Objects.nonNull(Object)` reference | Immediate stream filter after resolving a linkage; missing functions are excluded. No callback retention beyond the enclosing terminal expression. Use direct presence test; no facade or allocation. | B7, M1.3, dispatch/destructor targets / missing linkage |
| API0526 `Optional.map(Function)`; API0529 `filter(Predicate)`; API0531 `ifPresent(Consumer)`; API0532 `isEmpty()`; API0535 `orElse(T)`; API0538 `stream()` | Existing present operands/superclass/destructor name become presence branches. Filter/map in foreign result propagation runs predicate then merge only for a reference result. map's boxed Boolean becomes primitive; null result would mean empty, but this callback always returns Boolean. Optional.stream is ordered 0/1 destructor name. Callbacks borrow state and are consumed within the expression. orElse arguments here are eager null/false constants. | B7, M1.3, call result/targets/unwind / absent result, primitive foreign result, no superclass/destructor |
| API0539 `OptionalInt.empty()`; API0541 `of(int)`; API0540 `ifPresent(IntConsumer)` | exactParameter returns primitive presence/index, preserving parameter-order lookup and conversion-chain identity. Absent or call/phi result is not exact. Callback clears only a copied origin's bit. No boxed Integer or general supplier. | B2/B7, M1.2/M1.3, rendered release / exact object versus may-alias result, converted parameter |
| API0659 `Stream.flatMap(Function)`; API0660 `map(Function)`; API0668 `distinct()`; API0669 `filter(Predicate)`; API0671 `forEach(Consumer)`; API0681 `toList()` | Ordered classes to ordered 0/1 destructor names or dispatch entries, resolve functions, discard missing targets, deduplicate by record value equality in first-encounter order. toList freezes independent unmodifiable membership; null is permitted generally but excluded by the prior filter here. Select nested loops/ordered dedup and explicit owned result list, with no escaping stream or callback. | B1/B7, M1.1/M1.3, resolveTargets / overlapping destructor chain, duplicate entries, unknown linkage |
| API0666 `Stream.anyMatch(Predicate)`; API0670 `findFirst()` | anyMatch returns only an existential Boolean, no witness or diagnostic. Subtype recursion shares a visited-name membership set and short-circuits true; no first-failure state escapes. findFirst selects the first class with a matching name in the immutable class list; retain this precedence even when valid compiler IR has unique names. Use ordered loops and preserve short circuit/evaluation. | B7, M1.3, mayUnwind/subtype/fallback / interface cycle, allocation-only and throwing target, missing class |

## Hash and propagated traversal proofs

**C1, H0441:** targetCache is an identity-keyed memo of immutable target lists.
It is never iterated or exposed. Discovery's four propagated sites at lines
236, 278, 306 and 370 consume those lists, not cache entries. resolveTargets
uses class/function/dispatch list order and LinkedHashSet first insertion.
No upstream unordered cache order is transferred into a list. Reclamation and
publication summaries are looked up afresh on each call, including after a
fixed-point iteration, so caching a target list cannot freeze an earlier effect.

**C2, H0442:** Map.copyOf at line 109 freezes non-null observer facts. The only
production transfer is SemanticAnalyzer.selectedProjection. The observer seam
is package-private and the actual SemanticObserverBridge consumers store and
compare extensional maps. TemporaryReclamationTests explicitly sorts its
reclaiming key list. No diagnostic/IR/LLVM consumer observes this copy's iteration
order. The comparison adapter must represent these private observer facts by
key; this proof does not authorize sorting any semantic list or map with an
order-sensitive consumer. No production ordering refactor is needed here.

**T1, line 236:** any target with missing/throwing/allocating summary makes the
Boolean true. Predicates only read current facts. Empty ordinary call remains
unknown; empty type-initialization barrier cannot introduce an exception.

**T2, line 278:** each returned-parameter vector contributes selected argument
origin bits by OR. No first target/witness/budget is chosen. Stored vectors are
read-only and argument origins are cloned.

**T3, lines 306 and 370:** allocation/throw flags and publication/reclamation
bits are unions across targets. The rendered-string exclusion clears a bit on
that target's argument-origin clone before OR, never on the accumulated union
or another target's vector. A different target that actually reclaims that
parameter still contributes its bit. possiblyReclaimedArguments never authorizes
free, and foreign arguments conservatively mark every reference position.

**T4, both line-337 sites:** call.arguments is a List.copyOf snapshot in
IrForeignCallInstruction, unrelated to unordered specialization maps. The flow
graph merges record component/factory paths and overapproximates those hash
origins. Reference argument origins combine by OR, without ordering-dependent
mutation of input vectors.

**T5, line 445:** IrClass.interfaces is an independent ordered list, not a hash
view. Initialization closure inserts this type's functions, superclass closure,
then interface closures in list order, suppressing repeated names. This is a
list of possible callable effects, not an execution schedule for initialization.
The effects consumer unions facts; retain original target order nevertheless.

**T6, lines 461 and 463:** the same ordered interfaces list drives existential
subtype reachability. Each visited class name is traversed at most once; a cycle
cannot create a false path, and success returns immediately without recording
a witness. The equals argument at line 463 is a String element, not a container
traversal; that discovery candidate is a propagated scalar comparison. Preserve
content matching and first matching class-list precedence.

## Fixed-point, ownership and fixture boundary

Outer rounds preserve function insertion order. Within a function, original
block/instruction order is preserved, but origin facts grow only through OR.
The fixed-point equality compares Summary Boolean fields and logical BitSet
contents, not vector identity/capacity. Reachability is recomputed each round
so a newly throwing/allocating callee admits its exceptional edge. Summary
vectors are copied at construction and never mutated through their accessors.
No optional explanation storage budget or first-witness choice exists here.

The native slice needs immutable list membership, text/primitive lookup,
instruction-identity target cache, ordered target dedup, a FIFO, primitive-word
vectors with copies/value equality, typed variant tests and immediate callback
loops. These are B1/B2/B7 dependencies for M1 and the actual M2 effect workload.
Cache/summary/origin storage belongs to an analyzer invocation; shared IR and
class/function nodes remain borrowed until all dependent holders are retired.
No GC assumption, unknown-effect exemption or per-call runtime safety registry
is permitted. Resource budgets and exact pilot selection remain the M0.3 gate.

The nullable SemanticAnalysisObserver at original lines 25 and 37 is a retained
compiler-defined service, separately from the functional-member/capture ledger.
Construction notifies analyzerCreated, then later analyze calls analyzerRound
once per fixed-point round. Select this seam for the M0.3 observed effect pilot:
keep the observer and its mutable counters alive until the analyzer's last use,
including repeated analyze calls, with constructor/round argument and exception
timing intact. The default null path stays the unobserved performance baseline.
Observer allocation/retention and map copies must be measured separately;
observation cannot authorize reclamation or change the facts. The named test
observer in EffectContractProbe outlives its factory call and checks later rounds
against an unobserved analyzer. This is a retained-service qualification, not
proof of native capture reclamation or allocation behavior.

EffectContractProbe invokes the original J0 analyzer. It permutes colliding
function names and class target order, exercises multiple bit-vector words,
direct/transitive return and reclaim, foreign conservative effects, exact
rendered-result exclusion, and unwind reachability. It compares observer facts
extensionally and checks selected facts explicitly; it does not sort diagnostics
or IR. Its retained fresh-process qualification is linked from VERIFICATION.md.
