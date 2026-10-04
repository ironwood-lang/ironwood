<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Reviewed snapshot and effect contracts

Scope: the original FunctionAnalyzer ownership-state representation,
ClosedWorldEffectAnalyzer effect vectors, and RejectedFreeEvidence optional
explanation storage. This selects real M2 structures, rather than a generic map
benchmark. This record specifies required migration contracts and fixtures;
the port and its M1 helpers have not been implemented.

## Equality, storage and retirement

| Structure | Original equality and mutation contract | Required M1/M2 treatment |
| --- | --- | --- |
| AllocationInfo / TypeSymbol | Mutable allocation/type nodes have object identity; equal names/state do not make two allocations equal. AllocationInfo state, blockingReason, present and detached change after a snapshot. | Borrow identity nodes in independently owned snapshots; copy primitive state/reason references into immutable AllocationStateSnapshot values. Never key state by a mutable name/type/state hash. |
| AllocationStateSnapshot | Record equality compares state enum, reason text by value and detached bit. `mergeOwnership` uses that equality to decide agreement. | Explicit value equality/hash; distinct equal state objects must join as equal. An identity map of state values would change the proof. |
| ArraySlot | Record key consists of AllocationInfo identity plus int index. | Preserve composite identity-and-value hashing/equality; identical indices in different arrays remain distinct. |
| LocalSymbol / FieldSymbol | Records have value equality including symbol id/name/type/final flag or declaration/type/owner/layout/constant. Their AST/SourceFile members retain their original identity semantics. | Preserve field-specific value operations. Keep the identity-keyed explanation binding table distinct from the value-keyed environment. Do not equate shadowed locals by name. |
| OwnershipSnapshot | Independent maps for states, array slots, borrowed fields, retained-borrow sets and pool owners; independent exposed/unfreed sets. Java Map.copyOf/Set.copyOf reject null keys/values/elements and discard encounter order. Keys/values/elements are shallow borrowed references. | Fixed private snapshot helpers, with identity/value variants. A builder mutation must not affect a saved version. Retire snapshot storage only when no branch/loop/restoration/explanation still observes it. Retained-borrow child sets are immutable versions, not shared mutable builders. |
| ClosedWorldEffectAnalyzer Summary | Three BitSet vectors are independently cloned in the compact constructor. Record equality uses BitSet content. `origin` clones before `clear`/`or`; `mergeOrigin` monotonically ORs bits and compares cardinality for change. | Copy via fresh BitSet plus existing `or`; explicit content equality. Borrowed parameter/SSA identities become primitive indices. Reject mutations that reach a saved summary; no clone-to-shared-storage substitution. |
| Effect | allocates/throws flags and published/reclaimed vectors. Foreign-call inputs are cloned separately for publication/reclamation. | Preserve separate vectors even if initially equal, and OR/boolean joins. Copy/mutate/compare/retire all three summary vectors in M2. |
| RejectedFreeEvidence Site / Binding | Site compares SourceFile by identity and SourceSpan by value. Binding intersection explicitly compares allocation/source by identity and span by value. | Preserve distinct equal-looking source objects and allocation nodes. Do not serialize source addresses as the comparison identity. |
| RejectedFreeEvidence Event / Join | Merge retains only the exact same Event or Join object across paths (`!=` checks), even if distinct objects have equal-looking record fields. Join owns an immutable ordered alternatives list. | Preserve evidence identity and incoming path order. Keep alternate-path labels/anchors and truncation flags. This differs from AllocationStateSnapshot value equality. |
| RejectedFreeEvidence Retention / arrayStores | Retention is a value pair of owner/child; actual owners/children are allocation identity nodes. Array-store keys are ArraySlot values. | Composite keys with those member semantics; shallow owned map storage, borrowed node/source/span references. |
| RejectedFreeEvidence snapshots | Weak SnapshotKey uses identity hash and referent identity, plus ReferenceQueue retirement. Saved contains six independent shallow Map.copyOf results. Reference-count tables use identity of Site/Binding/Event/Join. | Replace the GC dependency with explicit proof-snapshot ownership/retirement and fixed association counts, scoped to this private diagnostic store. Do not introduce general GC, a public reference-counting subsystem, or exemptions to mandatory free proofs. Preserve existing local/snapshot/invocation budgets and truthful fallback. |

The native slice must separate mandatory ownership proof state from optional
explanation state. Truncation or missing/retired optional state may omit a note;
it cannot accept an unsafe free. `close` releases all remaining optional units
once. Java's collector timing is part of the original resource observation, not
an instruction to mimic weak-reference polling on native valid paths.

## RejectedFreeEvidence traversal proofs

All 62 discovered traversal occurrences in this class map to the proofs below.
Repeated occurrences at one expression can represent different copied operands;
the machine ledger preserves each attributed source node and hash origin.
The proofs cover the entire class and all consumers of its private saved maps.
Keys are never used to assign IR ids/layout, select diagnostic text, schedule
work, or choose a first matching witness. Diagnostic selection is by an explicit
allocation/local/slot key supplied by FunctionAnalyzer, whose upstream ordering
is reviewed separately.

| Proof | Original source lines / operation | Classification and proof, including downstream behavior |
| --- | --- | --- |
| E1 | 292, 328: retainArrayStores / clearRetainingOwner iterators | Order-independent traversal. Delete every entry satisfying a key membership/owner-identity predicate. No early selection. Release one association and the referenced Site count for each removed entry; the sum and final identity refcounts do not depend on visit order. No reserve or new event occurs inside either scan. |
| E2 | 371-379: save, six independent copies and six retain scans | Order-independent traversal. Reserve the total associations plus two snapshot units atomically before copying/retaining. Keys are unique in the admitted analysis domain, values are non-null, and each saved map is independently stable. Retain scans only increment identity reference counts; they neither reserve more units nor replace the keyed Site/Binding/Event/Join. Saved maps later feed E3/E4/E6 or explicit keyed lookups. |
| E3 | 411-437: merge copy and six removeIf intersections | Order-independent traversal. Keep only key/value facts common to every incoming snapshot: Site equality, explicit Binding identity/value fields, exact Event/Join identity. Predicates read an unchanged other snapshot and do not mutate another candidate. An absent saved snapshot clears current state and returns false irrespective of its container iteration; incoming path order remains an ordered list. The resulting keyed intersection feeds E4, with no first witness selected. |
| E4 | 458-469: copyIntoCurrent putAll and retain scans | Order-independent traversal. Current maps have been cleared and one total-association reservation succeeds or fails before transfers. Each key receives exactly its selected fact. Identity refcount increments commute; no per-entry budget reservation can select a different prefix. Result maps are consumed only by keyed lookups or E1-E6. |
| E5 | 476-481: clearCurrent release scans | Order-independent traversal. Per-object counters decrement once per association; the last decrement releases that object's fixed payload units. Regardless of order, every referenced object and association contributes the same total release, then every current map is cleared. There is no intervening reservation/diagnostic selection or high-water increase. |
| E6 | 581-586: retireCollected saved-value release scans | Order-independent traversal. The selected dead snapshot is removed by identity before scans; release its precomputed units/associations, then decrement all saved object counters as in E5. ReferenceQueue order/GC timing can affect when memory retires, but hash iteration inside one retirement does not affect the result. The explicit native retirement policy requires separate lifetime evidence. |
| E7 | Private siteReferences, bindingReferences, eventReferences, joinReferences, fieldLoads and snapshots hash tables | Lookup/membership only apart from the saved payload traversals E2-E6. Their maps never export a view, stream, iterator, copy or Map object. Counters use identity get/put/merge/remove; snapshots get/remove a SnapshotKey and pass its Saved value, not bucket order, to E2-E6. fieldLoads has no snapshot traversal and is cleared at close. The queue supplies a specific key; close calls clear and one aggregate budget release. |

Use plain hashing for these stores; linked replacements would add no semantic
ordering benefit. The native snapshot helper must still distinguish their
key/value equality and null rules. These proofs do not classify
FunctionAnalyzer's traversal before it chooses a key/event, or prove that weak
retirement has an acceptable native replacement cost.

## FunctionAnalyzer ordering boundaries and M2 cases

`probeFree` selects retaining owners by minimum allocation-list position before
emitting a witness. `elementAliasOf` chooses an array slot by index then its
container's allocation-list position, and a retaining owner by allocation-list
position. These keys are assigned from source analysis before hash traversal;
no object-address/identity-hash tie-breaker is admitted. Local aliases use the
linked environment's declaration order; deferred actions use the cleanup stack
order. Singleton `iterator().next()` results require their exact singleton
guard, rather than a presumed arbitrary first element.

`restoreOwnership` restores each allocation's independent fields, so that state
scan is order-independent. It also restores map encounter order through putAll;
that order is not independently proved safe for every later consumer.
`mergeOwnership` has explicitly order-sensitive paths: joined allocation events
can consume a bounded explanation prefix, pool-owner conflicts can select
blocked nodes/events, and incoming array slots can establish the first selected
store for one allocation. Map.copyOf erases the linked slot producer's order.
Incoming paths are semantically ordered; iterating an unordered map inside a
path does not establish source order. These paths still require an explicit
Java ordering contract before updated references are frozen.

Retained child unions, recursive cancellation/exposure, one-of identities and
generic substitution maps require their own downstream classifications. It is
insufficient to label every union commutative: callbacks can change the first
blocking reason, optional event or eventual cleanup order. The full class
ledger remains open while those paths are reviewed.

M2's snapshot workload must exercise all of these real operations: save before
a branch; mutate an allocation and slot on only one incoming path; restore;
join equal states and unequal states; keep/remove keyed common evidence; copy
and mutate each BitSet; compare equal independent states versus equal-looking
distinct identity nodes/events; explicitly retire sibling and nested versions;
repeat under bounded explanation budgets with explanations on/off. Include
branch/loop/deep-shape scales, equal-looking source objects, shadowed locals,
two slots retaining the same node, colliding keys and capacity changes.
`BranchJoin` and `SlotOrder` retain the original safety pair; they are not the
entire lifetime/equality/resource workload. The required native compile/link
profile is `--unfreed=warn`; strict library construction remains unchanged.
