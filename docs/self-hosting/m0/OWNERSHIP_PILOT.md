<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Selected ownership snapshot pilot

The proof-state workload invokes actual snapshotOwnership, restoreOwnership and
mergeOwnership on a constructed FunctionAnalyzer. All seven fields are populated:
state values, array slots, borrowed-field text keys, immutable retained-child
sets, identity pool owners, exposed membership and diagnostic live membership.
Setup constructs a minimal parsed host/type/callable only to satisfy the Java
analyzer constructor. That setup is outside the measured operation and is not
part of the native pilot's semantic-analysis or type-resolution scope. Native
M2 uses the same explicit state roles and operation sequence, with typed fields
in place of the test-only reflective access.

## Closed input and operation boundary

Sizes are 8/32/128 actual private AllocationInfo identity nodes, with repeated
aliases and newly constructed equal ArraySlot keys (same container/index).
Slots use index strides of 65,537. Actual identity hashes cannot be overridden
on these private final nodes; the separate explanation/tracker probes supply
forced collision keys. Every nested retained child set is an immutable version;
later mutations replace the parent entry rather than mutating that version.

Each of 64 iterations saves before a branch, changes node 0's state and node 1's
detached flag, removes slot/field 0, changes one pool owner, replaces retained
children, changes exposed membership and consumes one diagnostic obligation.
It saves a second version, verifies all seven earlier fields still contain their
original facts, restores, merges both predecessors and restores the earlier
version again. Registration/current-slot insertion and predecessor order reverse
together in the reverse shape; the separate tracker/evidence/D247 probes vary
those axes independently. The pool's selected first incoming owner intentionally
follows predecessor order. Node states/reasons and membership obey the source
join contracts, with five selected nodes becoming UNCERTAIN. 128 branch snapshot
keys plus a final merged comparison version remain strongly held until
verification; collector timing cannot retire them. All seven fields of the
before/changed/merged versions are projected by explicit input identity keys,
including every state/reason/detached value, slot, text field, pool owner, child,
exposed and live membership. This extensional projection is separately serialized
after the phase; it never sorts diagnostics or IR or chooses a witness by hash order.

Only LOCAL_NEW allocation origins are admitted, one pool-conflicting key is
present, and explicit JoinPath alternatives are empty. M2 must enforce the same
private fixture boundary. ONE_OF candidates, competing conflicts sharing owners,
nonempty nested JoinPath alternatives, general retained-child consumers,
arbitrary optional-budget prefixes and full source lowering remain B1/B7 M3.1
before S3. This representative subset does not declare FunctionAnalyzer portable.
The selected corpus retains complete accepted/unsafe branch and array-store
outcomes independently; M2 still needs native safe/unsafe reclamation pairs.

Explanations are disabled or enabled with invocation budget 1,048,576 or 20;
local and snapshot limits stay 1,048,576. The ample case can retain every fact
and every selected event. The tight invocation case stops during explicitly
ordered setup before branch save/join, so arbitrary joined-set order cannot
choose a different surviving event prefix. Ordinary proof state agrees with
explanations off. This is an enforced selected input/budget boundary, not a proof
that every bounded prefix of the general analyzer is order-independent.
Evidence close retires every optional unit, while proof snapshots remain live
for output verification. Native M2 must then retire proof backing storage and
verify outstanding temporary storage separately.

The effect companion uses the already qualified actual ClosedWorldEffectAnalyzer
chains (8/32/128 functions, 65 parameters), widths 8/257, independent Summary
vectors and extensional fixed-point results. M2 includes copy/clear/or/content
comparison of all three vectors, cyclic propagation and an explicit finite IR
input boundary. Other instruction/terminator kinds require explicit reject or
later treatment, never a permissive fallback. The retained observer is a separate
callback fixture; ownership-kernel metadata named observed/observer means only
explanation storage enabled and has zero observer round calls.

## Exact dependency contracts

The conservative original source closure includes 97 named methods and 528 calls,
with 67 external declaration patterns/310 calls across FunctionAnalyzer,
RejectedFreeEvidence and UnfreedAllocationTracker. It includes nonempty path
branches so their dependencies remain visible even though the selected fixture
does not reach them. Captured-state, null and hashing distinctions below apply
only in that source/fixture boundary. The ledger retains the original source
ranges; D247's ordered current-slot helper is an explicit separate source delta.

| Exact discovery declarations | Source-reviewed behavior and native treatment | Owner, phase, fixture |
| --- | --- | --- |
| API0061 Enum.name(); API0136 String.equals(); API0163 String.startsWith(); API0518 Objects.equals(); API0521 Objects.nonNull() | Enum state names and exact case-sensitive reason/prefix text; nullable reason equality is null-safe, record SourceSpan is value and SourceFile is identity. nonNull filters absent state lookup only. Explicit names/null branches preserve each distinction. Nonempty path evidence rendering is later-only. | B7 M1.3, named states/nullable reasons and absent incoming states |
| API0086 Integer.sum(int,int); API0101 Math.max(int,int) | Identity reference counters use primitive integer addition/subtraction and high-water max, not boxed identity. Selected positive budgets and association totals remain far below overflow. Maintain exact balanced counts for each Site/Binding/Event/Join; no prefix release may reclaim a borrowed proof node. | B1/B7 M1.1/M1.3, budget accounting and close zero |
| API0197 System.identityHashCode(); API0205 ReferenceQueue.poll(); API0206 WeakReference(T,ReferenceQueue) | SnapshotKey hashes and compares snapshot referent identity, never structural record equality. Queue only chooses dead version keys. Native private store uses explicit version identity and retirement, retaining borrowed keys/facts until the last consumer; no JVM/GC or runtime lifetime exemptions. | B1/B7 M1.1/M1.3, sibling/nested version retirement; M2 native lifetime proof |
| API0331 ArrayList(); API0454 List.add(); API0463 List.getFirst(); API0467 List.isEmpty(); API0475 List.size(); API0441 List.copyOf(); API0442 List.of() | Owned ordered state/path/alternative lists, shallow borrowed elements, immutable independent copies and non-null elements for copyOf. getFirst follows a nonempty check in reached paths. Empty paths select the guarded no-alternative branch. Alternative cap six and source path precedence remain visible for later nonempty-path consumers. | B1/B7 M1.1/M1.3, state agreement/disagreement and empty paths |
| API0364 Collection.removeIf(); API0365 Collection.stream(); API0092 Iterable.forEach(); API0660 Stream.map(); API0665 Stream.allMatch(); API0666 Stream.anyMatch(); API0669 Stream.filter(); API0676 Stream.noneMatch(); API0681 Stream.toList() | Immediate sequential traversal and lazy per-element calls, ordered incoming paths. Preserve short-circuiting, predicate effects and exceptions with explicit loops. State projection filters null then freezes ordered values. allMatch(empty) is true, anyMatch/noneMatch retain empty identities. No callback survives these calls; captured maps/nodes/reasons/path lists are borrowed until return. Full later paths must retain these rules, not just selected results. | B1/B7 M1.1/M1.3, two predecessor orders, copy independence and state equality |
| API0367 Collections.newSetFromMap(); API0414 IdentityHashMap(); API0415 IdentityHashMap(Map); API0416 IdentityHashMap.clear(); API0418 IdentityHashMap.entrySet(); API0419 IdentityHashMap.get(); API0420 IdentityHashMap.put(); API0421 IdentityHashMap.putAll(); API0422 IdentityHashMap.remove(); API0423 IdentityHashMap.size(); API0424 IdentityHashMap.values() | Explicit reference identity for allocation nodes and payload counters. Joined is an identity set; mutable node state must never enter its key hash/equality. Copies own backing storage but borrow keys/values. newSetFromMap requires an empty owned map and stores Boolean membership; private identity-set helpers replace boxing. Counter scans commute under evidence E1-E7, never select a witness. | B1 M1.1, equal-looking distinct nodes, restore fields and exact reference counts |
| API0425 Iterator.hasNext(); API0426 Iterator.next(); API0427 Iterator.remove() | Evidence scans remove every matching entry through its iterator and balance association/payload counters. Preserve iterator removal legality and current-entry identity, with no early witness selection. Private compiler traversal may replace the iterator with a direct remove-safe scan; generic API behavior is not promised. | B1 M1.1/M1.2, retained array-store intersection and deletion scans |
| API0392 HashMap(Map); API0430 LinkedHashMap(Map); API0440 LinkedHashSet(Collection) | Independent shallow mutable map/set copies. HashMap uses ordinary key equality; linked copies preserve supplied encounter order. Native allocation/value/slot/text variants preserve their reviewed equality and null domain. Copies retire backing storage only, not allocation/fact objects or immutable child sets. | B1 M1.1, builder mutation and seven-field snapshots |
| API0480 Map.copyOf(); API0553 Set.copyOf(); API0554 Set.of() | Independent immutable non-null membership; key/value/element null rejection. Ownership state keys remain allocation identity, slot keys remain container identity/index, borrowed fields use String value equality. State values compare state/reason/detached by value; evidence Events/Joins intersect by identity. Retained child sets are shared immutable versions. D247 overrides unspecified ordering for knownArraySlots only with ordered independent storage. | B1 M1.1, 47-check value/null probe and D247 ordering fixtures |
| API0488 Map.clear(); API0491 Map.containsKey(); API0494 Map.entrySet(); API0496 Map.forEach(); API0497 Map.get(); API0499 Map.getOrDefault(); API0501 Map.keySet(); API0502 Map.merge(); API0503 Map.put(); API0504 Map.putAll(); API0505 Map.putIfAbsent(); API0507 Map.remove(); API0510 Map.size(); API0511 Map.values(); API0512 Entry.getKey(); API0514 Entry.getValue() | Owned mutable current maps, borrowed immutable incoming maps. get-null means absence only because values are non-null. First pool owner is the first incoming path value; a conflict blocks both old/new owners. Counters merge with immediate primitive sum. Restore clears then replaces membership. Evidence reserves totals before unordered transfers (E2/E4), and result diagnostics use keyed lookup, not map encounter order. Full conflicting event prefixes and restored borrowed-field order remain later-only. | B1/B7 M1.1/M1.3, one pool conflict, lost slot, keyed evidence and atomic reservation |
| API0569 Set.addAll(); API0571 Set.clear(); API0572 Set.contains(); API0578 Set.iterator(); API0583 Set.retainAll() | Membership union/intersection and independent current storage; retained children/exposed sets union, unfreed live membership intersects. Evidence incoming iteration is ordered by predecessor list, not by snapshot key buckets. No selected pilot operation chooses a retained child by iteration. Full child cancellation/exposure remains a blocked later consumer. | B1 M1.1, shared immutable child versions and union/intersection results |

For the seven ownership fields, restore of state values mutates each distinct
node independently. Restoring other fields may change incidental hash encounter
order; the selected consumers are keyed lookup, set membership, or D247's ordered
slot path. General downstream traversals still need their own classifications.
Joined states are independent per node except optional-budget event creation:
the ample/tight-stopped preconditions above eliminate that prefix ambiguity in
this pilot. Pool order is explicit incoming-path precedence for the one conflict.
Retained child union is extensional here; no recursive cancellation/free/output
consumer is reached. Exposed and unfreed membership order does not select output.
The full RejectedFreeEvidence E1-E7 proofs and tracker origin/finding order remain
the authoritative downstream contracts. No generic container/import label replaces
these source proofs, and no global ownership-order gate is claimed here.

## Java evidence and remaining native gate

ownership-probe/qualification.json preserves 47 value/null/copy checks matching
in four fresh original J0 JVMs. It tests every map source/key/value and set
source/member null case, immutable independent backing, shared immutable child
identity, equal independent state values/reason Strings and composite slot keys.
It also invokes the real branch workload and checks distinct unchanged state
values from the before/changed snapshots for equality and matching hashes,
while the changed node's states differ. Array-slot
encounter order remains the separately qualified D247 contract.

The ownership resource archive retains 96 fresh ordered-seed JVMs with two repeats,
sampling on/off, explanation on/off, ample/tight invocation budget and two shapes
at each size. Exact results match within each declared shape/configuration.
Maximum wall is 0.164455 s, process RSS 93.313 MiB, sampled used heap 23.013 MiB,
and sampled frames 48. Sampling ratios span 0.901..1.076, maximum gap 5.326 ms.
Construction, actual-operation phase and verification/serialization are separate;
phase includes reflective calls, adapter assertions and snapshot inspection, so
it is not an isolated native algorithm timing. Used-heap/frame samples are lower
bounds; frames are not stack bytes. No native pilot has been evaluated.
Construction spans 41.709..64.735 ms, operation phase 21.227..61.747 ms, and
verification/projection 4.904..9.060 ms. Ample optional-unit high water is
2,367/8,655/33,807; tight invocation high water is 20. These are compiler-private
logical accounting units. Sampling and reflective/assertion overheads are not
separately isolated by these runs.

The 128 branch versions contain 4,288/16,576/65,728 outer associations at sizes
8/32/128. Those are retained version entries, not physical allocation counts or
bytes; the merged comparison version adds 4n+1 outer entries, and shallow shared
child sets are not counted again. Optional budget units
also are not bytes. Every explanation close ends at zero optional units.
Both native snapshots and invocation-owned graph nodes need explicit retirement
boundaries and separate safe/unsafe cleanup qualification before G1.

Reproduce with `python3 scripts/self-hosting/measure-kernels.py --kernel-set
ownership --output NEW_PATH` using its default distinct ordered identity, and
`python3 scripts/self-hosting/review-pilot-ownership.py`. The
[raw archive](ownership-resources.tar.gz) and its manifest preserve 2,964 files:
final full-field captures, the local-limit setup failure, earlier aggregate-only
captures, changed-reason/missing-repeat controls and legacy qualifier controls.
Earlier measurements retain their own source hashes and are not substituted for
the final structural reference. Both previous 52-run kernel archives continue
to qualify through the expanded verifier. Native lifetime and whole-analyzer
ordering claims remain outside these records.

M1 requires B1 identity/value map/set copies, immutable nested versions, explicit
version lifetime, existing BitSet copy via fresh storage plus or, and FIFO effect
worklists; B7 explicit record value operations, presence and callback/stream loops.
D247 current-slot order needs no B2 sort. No B2 dependency is introduced by the
selected ownership/effect subset. Complete later analyzer consumers remain gated.
