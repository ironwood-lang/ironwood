<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Compiler worklist and scope-stack contracts

Scope: every ArrayDeque construction in original J0 (44), its attributed
references, and FunctionAnalyzer.restoreDeque's one borrowed deque parameter.
All storage is private compiler machinery. No general public Deque API is
selected. Source references and source hashes are joined to each reviewed row by
`review-worklists.py`. These are storage/traversal contracts, not a proof that
hash-backed inputs to a queue are ordered or order-independent. That transitive
ordering review remains separate.

## Exact deque declarations

| Exact discovery IDs / declarations | Reviewed admitted behavior and selected treatment | Owner, phase, first consumer / required fixture |
| --- | --- | --- |
| API0319 `ArrayDeque()`; API0320 `ArrayDeque(Collection<? extends E>)` | Empty constructor creates independent mutable membership. Collection constructor makes a shallow encounter-order copy, rejecting null source/elements and borrowing non-null elements. Preserve all source order, including unsorted Set input until its upstream contract is separately resolved. Compiler-private FIFO/stack storage replaces these constructors; storage owner must release membership without freeing borrowed elements. | B1, M1.2 for selected pilot; M3.1 before other S3/S4 consumers, M5/M6 before S6/S7 / independent copy, null source/element and input-order tests |
| API0321 `ArrayDeque.add(E)`; API0322 same member reference; API0384 `Deque.add(E)`; API0324 `ArrayDeque.addLast(E)`; API0386 `Deque.addLast(E)` reference | Append at tail, rejecting null. add returns true in Java, discarded at every selected site; addLast returns void. Bound references execute immediately (successor enqueue or restore), never escape. Native private append may return void. Allocation/growth failure needs rollback and cleanup; do not infer ownership transfer of the enqueued object. | B1, M1.2/M3.1, FIFO and restore / nested enqueue, null rejection, growth failure |
| API0323 `ArrayDeque.addAll(Collection<? extends E>)`; API0385 `Deque.addAll(Collection<? extends E>)` | Append each input element in encounter order. Return boolean is discarded. No selected source is the destination itself. General Java addAll can leave a prefix when a later null fails; do not claim transactional general API semantics for an explicit private non-null loop. Retain complete input for the traversal; copied membership does not own input elements. | B1, M1.2/M3.1, closure/CFG queues / ordered multi-successor append and independent source |
| API0328 `ArrayDeque.push(E)`; API0389 `Deque.push(E)` | Insert at head, rejecting null. Scope/Tarjan/documentation stacks require newest-first reads and traversal. A native tail-backed ArrayList stack may append, but must reverse its traversal/snapshot order and restore it correctly. Preserve explicit nested context lifetime; pop does not prove the removed context or its children reclaimable. | B1, M1.2 if reached; M3.1/M5 for other consumers / nested scopes, shadowing, inner-to-outer snapshot |
| API0327 `ArrayDeque.pop()`; API0388 `Deque.pop()`; API0329 `ArrayDeque.removeFirst()`; API0390 `Deque.removeFirst()` | Remove and return head; empty throws NoSuchElementException. FIFO reads follow while-nonempty checks. Stack pops rely on matched pushes/control-flow invariants. Preserve returned borrowed object identity and remove only membership. Indexed get alone would fail to remove; indexed empty access would change the exception. | B1, M1.2/M3.1, FIFO/stack consumers / empty failure and matched nested push/pop |
| API0326 `ArrayDeque.peek()`; API0387 `Deque.peek()` | Read head without removal; empty returns null. Documentation checks nonempty; semantic stacks have active-context invariants or explicit absence paths. A tail-backed stack must select its last element and retain the empty-null contract. Pure reads allocate and retain nothing beyond the existing stack. | B1, M1.2/M3.1/M5, scope/current-context lookup / empty peek and innermost identity |
| API0325 `ArrayDeque.isEmpty()`; API0330 `size()` | Pure membership count, with no allocation or mutation. Interface-typed stacks also call inherited Collection.isEmpty/clear/stream and Iterable.forEach; their source references are retained in the per-storage ledger. Clear removes all membership, without freeing shared context/payload objects. Stream/forEach/copy reads must traverse head-to-tail. | B1/B7, M1.2/M3.1/M5, worklist guards and context snapshots / cleared storage and ordered traversal |

## Reviewed storage consumers

The phase column names readiness before the first native consumer. Only the
exact M0.3-selected pilot slices enter M1/M2. Other entries remain later B1
preparation; this table does not authorize their implementation during M0.
FIFO means append-tail/take-head. Stack means push-head/pop-head/peek-head and
head-to-tail (innermost-first) traversal. Preserve queue priority when two queues
are drained together. Required fixtures below have not all been run.

| ID | Exact original declaration | Reviewed storage/consumer contract | Owner, readiness, first consumer / required fixture |
| --- | --- | --- | --- |
| Q01 | ClosedWorldPruner.java:69 classWork | FIFO; class reachability; drain after the function queue, then repeat if either gained work. | B1, M3.1 before S4 / functions enqueue classes which enqueue functions |
| Q02 | ClosedWorldPruner.java:70 functionWork | FIFO; function reachability; drain completely before the class queue in each round. Output filtering separately follows original program lists. | B1, M3.1 before S4 / export/entry roots and mutual class/function discovery |
| Q03 | EnumArgumentSpecializer.java:110 work | FIFO seeded from callee collection; recursive closure membership, seen guard and unknown-target guard. Do not substitute DFS without downstream review. | B1, M3.1 before S4 / recursive call graph and repeated callees |
| Q04 | FieldValueForwarder.java:45 pending | FIFO Pending(label,state); first rewritten visit wins. Propagate copied state only on eligible single-predecessor normal edges, otherwise empty state. Scheduling is part of the transformation contract. | B1, M3.1 before S4 / branch/join/backedge/unwind and first visit |
| Q05 | FieldValueForwarder.java:243 queue | FIFO return reachability; visited membership and any reachable return decides false. | B1, M3.1 before S4 / cycle with and without a return |
| Q06 | FieldValueForwarder.java:278 queue | FIFO reachable block membership, then original block-list filtering. | B1, M3.1 before S4 / branch ordering and unreachable block removal |
| Q07 | InitializedTypeSpecializer.java:145 work | FIFO entry/callee traversal with seen/known-function/body-cost/demand guards. Appended result stops when MAX_GROUP_FUNCTIONS is exceeded, so the accepted prefix and budget selection require queue order. | B1, M3.1 before S4 / initialized target across recursive callees and competing bounded-group candidates |
| Q08 | InitializedTypeSpecializer.java:241 work | FIFO CFG closure, successors in producer order, output from original block lists. | B1, M3.1 before S4 / branch/switch/invoke/throw reachability |
| Q09 | InitializedTypeSpecializer.java:275 work | FIFO Kahn elimination, initial zero-degree entries in declaration order; decrement every successor multiplicity. Result compares removed count to block count. | B1, M3.1 before S4 / reducible/irreducible cycles and duplicate edges |
| Q10 | SelectiveInlining.java:79 work | FIFO call-graph closure from source Set encounter order, membership result. Upstream edges and downstream candidate consumers remain separate order obligations. | B1, M3.1 before S4 / recursion and candidate set parity |
| Q11 | SelectiveInlining.java:92 work | FIFO Kahn CFG cycle check, same degree/multiplicity contract as Q09. | B1, M3.1 before S4 / cycle and acyclic inlining eligibility |
| Q12 | BridgeCustomExceptionTypes.java:34 pending | FIFO requested exception names, append cause/secondary result types then superclass; first rejected/unknown type controls the returned reason. Preserve requested and dependency order. | B1, M6 before S7 / two invalid requested types and chained causes/superclasses |
| Q13 | DocComment.java:93 lists | Stack of ul/ol tag text; pop must match closing tag, peek chooses numbered/bullet output, size controls indentation. Empty/mismatched/unclosed tags preserve diagnostics. | B1, M5 before S6 / nested mixed list kinds and malformed close |
| Q14 | BridgeArrayInputs.java:90 pending | FIFO initializer targets then callable target, then resolved calls. Closure order and first unknown/rejected operation control proof output. | B1, M6 before S7 / initializer error before callable error |
| Q15 | BridgeByteViews.java:60 pending | FIFO initializers then callable and call targets; first unsupported/unknown view operation is source-sensitive proof output. | B1, M6 before S7 / ordered multi-target rejection |
| Q16 | BridgeCleanupAnalyzer.java:44 pending | FIFO cleanup targets then resolved destruction dependencies; first effect/unknown operation chooses the proof reason. | B1, M6 before S7 / multiple cleanup failures and recursive dependencies |
| Q17 | BridgeControlFlow.java:67 pending | FIFO CFG view; successor order jump, branch true/false, switch default/cases, normal/unwind as admitted. Preserve incoming predecessor insertion and unwind edge facts. | B1, M6 before S7 / switch/invoke with multiple predecessors |
| Q18 | BridgeNonReclamationAnalyzer.java:29 pending | FIFO reachable functions; enqueue once by linkage text, scan in queue order. Final rejected/unknown reasons and closure are sorted explicitly; exclusions retain their collected list order and need that separate downstream review. | B1/B2, M6 before S7 / two reachable unsafe effects and unpublished-cleanup exclusions |
| Q19 | BridgeRootRetentionAnalyzer.java:236 pending | FIFO zero-incoming type elimination; decrements each retained edge and counts removed vertices. This checks cycles, not first-witness selection. | B1, M6 before S7 / cyclic and acyclic retained roots |
| Q20 | ClosedWorldEffectAnalyzer.java:203 pending | FIFO CFG reachability; branch true before false, switch default before cases, normal before admitted unwind. Effects/fixed-point contract is reviewed separately in SNAPSHOT_CONTRACTS. | B1, M1.2 before selected M2 effect workload / branch/loop/invoke reachability and summary parity |
| Q21 | EffectivelyFinalCaptureAnalyzer.java:94 variableScopes | Stack of name maps; declaration/peek uses current scope, resolve traverses innermost first with field-shadow stopping rule. | B1, M3.1 before S3 / equal names in nested local classes/scopes |
| Q22 | EffectivelyFinalCaptureAnalyzer.java:96 classFrames | Stack of class frames; current class uses peek; enclosing-instance/field-shadow scans stop at first matching frame. | B1, M3.1 before S3 / nested same simple names and enclosing field shadow |
| Q23 | EscapeSummaryAnalyzer.java:92 switchYields | Stack of switch-expression yield-origin sets; yields update nearest active set and completion pops it. | B1, M3.1 before S3 / nested switch expressions with different origins |
| Q24 | FinalFieldAssignmentAnalyzer.java:72 scopes | Stack of local-name sets; current declaration via peek and locality via any inner-to-outer scope. Reset clears membership at analysis boundaries. | B1, M3.1 before S3 / local/field name shadow and separate constructors |
| Q25 | FinalFieldAssignmentAnalyzer.java:73 breakFlows | Stack of transfer-flow lists; nearest unlabeled break via peek, snapshots traverse all active inner-to-outer lists before finally processing. | B1, M3.1 before S3 / nested loop/switch breaks through finally |
| Q26 | FinalFieldAssignmentAnalyzer.java:74 yieldFlows | Stack of switch-expression transfer lists, nearest yield target, inner-to-outer transfer snapshots. | B1, M3.1 before S3 / nested yield and finally assignment |
| Q27 | FinalFieldAssignmentAnalyzer.java:75 labeledBreakFlows | Stack of label/flow contexts; first matching label receives flow; transfer snapshots retain inner-to-outer order. | B1, M3.1 before S3 / nested labeled breaks through finally |
| Q28 | FreshArrayElementAnalysis.java:253 pending | FIFO CFG successors, ignore required-creation block, visited guard; reaching original store block proves repeatability. | B1, M3.1 before S3 / store loop with/without intervening creation |
| Q29 | FunctionAnalyzer.java:214 scopes | Stack of local symbol maps; current declaration via peek, resolution innermost first, constructor parameter exclusion preserved. Captured variable identity lookup scans active scopes. | B1, M3.1 before S3 / nested locals and initializer parameter hiding |
| Q30 | FunctionAnalyzer.java:215 exceptionRegions | Stack of exception regions, head-to-tail snapshots; restore preserves that same sequence. Current region/temporary unwind selection must remain innermost first. | B1, M3.1 before S3 / nested try/cleanup and restored outer exception target |
| Q31 | FunctionAnalyzer.java:216 checkedCatchScopes | Stack of catch-type lists; push/pop around try body, copy/restore in stored contexts, ordered outer-catch search. | B1, M3.1 before S3 / nested checked catches and cleanup restoration |
| Q32 | FunctionAnalyzer.java:217 observedTryBodyExceptions | Stack of observed exception sets; peek receives current body exceptions; snapshots copy stack membership but share the intended set values. | B1, M3.1 before S3 / nested try observations and restored context |
| Q33 | FunctionAnalyzer.java:218 finallyContexts | Stack of cleanup contexts; copies/restores and stream first-match selection preserve newest-first deferred cleanup choice and cleanup execution ordering. | B1, M3.1 before S3 / nested defer/free/call matching and abrupt exit |
| Q34 | FunctionAnalyzer.java:219 loopContexts | Stack of loop contexts; peek selects nearest continue/loop flow, matched push/pop. | B1, M3.1 before S3 / nested continue and loop exits |
| Q35 | FunctionAnalyzer.java:220 breakContexts | Stack of break contexts for loops/switches; peek selects nearest unlabeled target. | B1, M3.1 before S3 / switch inside loop and loop inside switch |
| Q36 | FunctionAnalyzer.java:221 labeledContexts | Stack of named targets; first matching label and duplicate-active-label detection; matched push/pop. | B1, M3.1 before S3 / labeled break/continue and duplicate label |
| Q37 | FunctionAnalyzer.java:222 switchExpressionContexts | Stack of yield contexts; peek selects nearest expression target. | B1, M3.1 before S3 / nested switch-expression yields |
| Q38 | FunctionAnalyzer.java:266 temporaryScopes | Stack of temporary-allocation contexts; peek handles current scope; name/cancel visits every active scope. Scope values may remain referenced by snapshots and proof evidence. | B1, M3.1 before S3 / nested temporary naming/cancellation and rejected free |
| Q39 | GenericInferenceSolver.java:1010 stack | Tarjan stack: push on discovery, pop a complete component until its root; onStack membership is separate. Component members are sorted after pop; component traversal order has its own generic solver review. | B1/B2, M3.1 before S3 / mutually recursive inference constraints |
| Q40 | OwnedArrayElementAnalyzer.java:383 pending | FIFO CFG successors; skip creation block/visited, return true on original store block. Preserve initial successor encounter order until upstream set review. | B1, M3.1 before S3 / repeated store with/without recreation |
| Q41 | OwnedArrayFieldAnalyzer.java:284 switchYieldOrigins | Stack of boolean-vector yield origins, nearest active vector via peek; matched push/pop. Vector content and joins are separate value contracts. | B1, M3.1 before S3 / nested switch yields of owned fields |
| Q42 | PrimitiveGenericSpecializer.java:32 functionQueue | FIFO specialization requests; drain functions after class requests in each round. Materialized function insertion affects final function ordering. | B1, M3.1 before S4 / function request enqueues another class/function |
| Q43 | PrimitiveGenericSpecializer.java:35 classQueue | FIFO class requests, drain first each round; preserve allocated type IDs and putIfAbsent first class context. | B1, M3.1 before S4 / repeated class requests and stable type IDs |
| Q44 | TemporaryBorrowAnalysis.java:174 pending | FIFO Position(block,index) paths; normal before possible unwind, complete successor traversal. Any unclean exit/repeated allocation/unhandled unwind rejects, successful paths stop at free. | B1, M3.1 before S3 / normal/unwind cleanup and repeated allocation |

## Native representation obligations

A tail-backed stack must expose newest-first indexed traversal. Its ordered
copy is newest-first too. FunctionAnalyzer.restoreDeque clears the old
membership and appends the saved list in its existing head-to-tail order; a
tail-backed native stack must insert that list in reverse storage order. Copy
and restore must not reverse lexical lookup, exception dispatch or cleanup.
The generic helper's deque parameter is borrowed, never a new owner.

FIFO storage needs a cursor or ring, avoiding an ArrayList shift on every
dequeue. Clear consumed membership promptly and retire queue backing storage
at the invocation/analysis boundary, including failure. A cursor must not retain
every historical request as live state; growth/reuse and clearing require
allocation and resource evidence in M1/M2. This is required queue state, not
runtime safety bookkeeping. Borrowed labels/types/functions and potentially
shared scope maps remain alive under their real owners. Newly constructed
Pending/Position/request holders require separate ownership review before a
native free; removing a slot is not a reclamation proof.

No worklist refactor occurs in M0. Required native equivalence, safe/unsafe
cleanup, failure and allocation fixtures remain later gates. The ledger does
not classify global hash flows through queued source collections or the maps
stored in lexical scopes.
