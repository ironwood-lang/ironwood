<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Diagnostic allocation-obligation tracker

Scope: UnfreedAllocationTracker and the unfreedLive field of OwnershipSnapshot.
This tracker produces optional missing-free diagnostics; mandatory reclamation
proofs are separate. FunctionAnalyzer constructs it only when mode is not OFF.
Direct helper calls in OFF still produce warnings, which is not production OFF
behavior. No native implementation or public API is added here.

| Exact discovery declarations | Source-reviewed behavior and replacement | Owner, phase, first consumer and fixture |
| --- | --- | --- |
| API0428 LinkedHashMap(); API0438 LinkedHashSet(); API0491 Map.containsKey(); API0497 Map.get(); API0503 Map.put(); API0505 Map.putIfAbsent() | Mutable invocation-owned origins/findings and live/suppressed storage. Generic A uses ordinary equals/hashCode; production AllocationInfo has identity equality. First registration preserves span/description; completed may update live membership on repeat registration. Replacing a name preserves registration order and the first explicit name. Null register is ignored; completed/suppress act only on registered keys. | B1 M1.1, tracker registration; colliding identity nodes and equal String keys |
| API0163 String.startsWith() | Exact case-sensitive prefix prevents overwriting an explicit allocation name. Concatenation retains text values in Origin/Diagnostic. Private prefix comparison suffices. | B7 M1.3, tracker naming; first-name and original-span checks |
| API0553 Set.copyOf(); API0567 Set.add(); API0569 Set.addAll(); API0571 Set.clear(); API0572 Set.contains(); API0579 Set.remove(); API0583 Set.retainAll() | Independent immutable shallow snapshot membership, non-null elements; live restore and join mutate owned backing storage. Universal predecessor intersection, including first predecessor, empty list clears. Hash traversal order is irrelevant to membership. Elements remain borrowed allocation identities. Mutation and restore do not reclaim elements; snapshot storage retires after its last consumer. Null queries on immutable Java sets can throw; pilot-produced keys are non-null. | B1 M1.1, ownership snapshot diagnostic field; 8/32/128 collisions, reversed paths, independent copies and empty merge |
| API0463 List.getFirst(); API0467 List.isEmpty(); API0092 Iterable.forEach() | Read ordered predecessor list immediately; retainAll bound receiver is live evaluated before the call. Replace by a direct loop preserving calls, no retained callback. Empty predecessor list bypasses getFirst; otherwise inputs are borrowed during the call. | B1/B7 M1.1/M1.3, tracker merge; both predecessor orders and empty list |
| API0494 Map.entrySet(); API0512 Entry.getKey(); API0514 Entry.getValue(); API0613 Predicate.test() | observe visits origins in registration order. Suppressed/not-live keys bypass predicate. Predicate runs immediately once per remaining entry; exceptions propagate without deferred calls. Findings preserve first creation order and first finding per allocation. Predicate receiver/captured retained-membership set are borrowed during this call. Production predicate reads allocation state/origin and retained membership; it neither escapes nor mutates tracker state. | B1/B7 M1.1/M1.3, tracker observation; visit-order and skipped-key checks, false predicates |
| API0496 Map.forEach(); API0511 Map.values(); API0441 List.copyOf() | diagnostics traverses findings in creation order, filters suppression before span-value deduplication, and keeps first visible finding for each equal span. visible is call-owned linked storage; result owns independent immutable ordered backing storage and borrows Diagnostic objects. Immediate callback reads suppressed and writes visible; both remain live until return. | B1/B7 M1.1/M1.3, diagnostic output; equal-span winner after suppression and old-result independence |
| API0443 List.of(E) | Singleton related note is immutable/non-null and in order; abandoned removes live membership before creating its first finding. Already-suppressed/absent/not-live allocations do not emit. Diagnostic severity is error only in ERROR mode. | B1/B7 M1.1/M1.3, abandonment output; first reason and all three helper modes |

H0866 is Set.copyOf(live). Its six local propagated traversal records at lines
74/79/87/88 copy, restore or intersect membership. The three downstream records
in original FunctionAnalyzer at 12518/12661/14266 copy this snapshot field, map
the ordered incoming list to its unfreedLive sets, or copy membership again.
They do not consume set order. observe deliberately iterates origins, never live,
so restore's incidental membership order cannot change predicate or diagnostic
order. diagnostics deliberately iterates findings. This proves only H0866's
contribution to shared rows; H0600/H0618 and other snapshot fields need their
own proofs. The ordered D247 seed changes array-slot copying, not this field.

Diagnostic finding creation order is still supplied by callers of abandoned and
observe. Full FunctionAnalyzer event/branch ordering is outside this helper
proof, with B1/B7 M3.1 before S3 equivalence. The selected pilot must specify
its event sequence explicitly. Mandatory free proofs and native safe/unsafe
cleanup pairs remain required in M2.

The [qualified Java probe](unfreed-probe/qualification.json) runs 2,148 checks in
each of four fresh original J0 JVMs. All outputs match. Three sizes straddle
resize boundaries with deliberate collisions; registration and predecessor order
vary independently where membership must be invariant. Generic value equality,
first registration/name, ordered predicates, immutable copies, severity and
suppression-before-deduplication are exercised. This is not resource or native
lifetime evidence. An initial probe mistakenly queried a non-null immutable set
with null and exposed JDK's NullPointerException; its raw failure is retained in
target/self-hosting-m0/unfreed-probe-initial-null-query. The final assertion checks
the live membership preserved by ignored null registration without assuming
nullable immutable-set queries.
