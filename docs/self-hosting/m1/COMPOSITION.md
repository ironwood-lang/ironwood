<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 seven-field ownership snapshot composition

Base: 035f81e1 (D256). This closes the M1.1 obligation to "prepare all seven
ownership fields, structural state/slot keys, identity allocation/pool keys and
immutable shared child versions" (PILOT_HANDOFF), and the M1.2 traversal and
restore semantics those fields need, using the helpers from D249, D250, D251
and D254. D257 records the decision; the ledger is
[composition-evidence/manifest.json](composition-evidence/manifest.json).

## Shape

| Java field | Live container | Snapshot storage | Traversal |
| --- | --- | --- | --- |
| `states` | node fields; append-only allocation list | `SnapshotIdentityMap<Node, StateValue>` | allocation list plus keyed lookup |
| `knownArraySlots` | `LinkedHashMap<Slot, Node>` | `SnapshotLinkedMap` | slot-key list in D247 current-store order |
| `borrowedOwnedFields` | `LinkedHashMap<String, Node>` | `SnapshotLinkedMap` | name list |
| `retainedBorrows` | `IdentityHashMap<Node, SnapshotList<Node>>` | `SnapshotIdentityMap` of shared child versions | owner list; child versions are lists |
| `poolOwners` | `IdentityHashMap<Node, Node>` | `SnapshotIdentityMap` | value list |
| `exposedContainerContents` | `IdentityHashSet<Node>` | member list | the list itself |
| `unfreedLive` | `IdentityHashSet<Node>` | `SnapshotIdentitySet` | member list |

`Node`, `StateValue` and `Slot` in the fixtures stand in for the analyzer's
`AllocationInfo`, `AllocationStateSnapshot` and `ArraySlot`, whose native forms
M2.2 ports. `StateValue` compares state, nullable reason text and the detached
flag by value; `Slot` compares container identity and index; nodes keep
identity. Only the slot list's order is semantic (D247). The other lists follow
the live container's own order, which no selected consumer observes
(OWNERSHIP_PILOT). Restoring borrowed fields from a linked snapshot is
deterministic, where Java's `Map.copyOf` order is incidental and later-only.

## Lifetimes

Builders and traversal lists are independent of the snapshot and retire at
once. A snapshot retires exactly the storage it acquired: 6n + 75 allocations
at n = 8, 32 and 128 (the composite, five map copies, one identity-set copy and
six lists), on top of transient key builders during the save. A null at any of
the eight constructor positions rolls back the fields already copied, leaving
only the ordinary exception's allocation.

State values are immutable versions: snapshots share a node's current version
and a state change installs a new one, so versions grow with state changes, not
with saves. Retained-child versions are immutable lists shared by reference; a
join builds a new version. Nodes, state versions and child versions are borrowed
and must outlive every snapshot that observes them. The existing proofs do not
follow loans through a multi-field composite, so once one of these is read
through a snapshot its later free is rejected: they are invocation-lived, like
the analyzer's own state. Choosing how the native analyzer finally retires them
is the M2.2 native lifetime proof that the M0 handoff already names.

## Sufficiency

`compiler_ownership_operations` saves, mutates every field, saves again,
restores and joins the two versions with FunctionAnalyzer's rules: per-node
state agreement or UNCERTAIN/MAYBE_FREED with joined detached flags, slot and
field agreement removal and first-path re-insertion, first-owner pool
precedence with conflict blocking, retained-child and exposed unions, lost-slot
blocking and live intersection. It reads snapshots only through keyed lookup
and the traversal lists. Its sorted projection equals the Java 21
[reference](composition-evidence/OwnershipOperationsReference.java), which
applies the same rules with Java collections, `Map.copyOf` and the D247 slot
copy. Field projections are sorted because restored field order is later-only.

## Evidence

| Check | Result |
| --- | --- |
| Operations | Native projection for restored, changed and merged state is byte-identical to the Java reference |
| Order and collisions | The same scenario rerun with reversed insertion of every order-independent membership, with capacity-1 containers (one collision chain and repeated resizes) in both orders, and with capacity 64 prints the identical projection |
| Value contract | 43 native checks: 7 record value checks, 14 seven-field lookup/independence/sharing checks, 7 builder null-domain checks, 8 null-source rollback checks, 3 D247 order checks, 4 version checks |
| Java mapping | The M0 [47-check probe](../../../scripts/self-hosting/OwnershipContractProbe.java) passes on J0. Its 12 copy-time null key/value/member checks become 7 insertion-time builder rejections; its 7 `UnsupportedOperationException` checks become the absence of any mutator on the snapshot types; its distinct-but-equal unchanged state objects become one shared version, which compares equal as before |
| Allocation | Snapshot storage 123, 267 and 843 allocations at n = 8, 32, 128 (6n + 75); saves allocate 159, 351 and 1,041 including transient builders; freeing returns to the pre-save live count |
| Controls | Off/warn/error: snapshot retirement while live state changes accepted; freeing an observed node or child version, or using a retired snapshot, rejected; freeing a node or child version after snapshot use rejected as the documented conservative boundary |
| Artifacts | All three fixtures compile and link from classes and archive at `-O3`; each has one `--unfreed=warn` diagnostic, the deliberately retained live analyzer state |
| Allocation failure | `compiler_ownership_snapshot_failure` fails a save at every limit from 131 to 267 (0-130 stop in setup and are skipped); the partial snapshot rolls back and every temporary key builder is freed, leaving the live count unchanged; 268 onward completes. The save helper retires its builders with `defer`, the pattern the port must keep |
