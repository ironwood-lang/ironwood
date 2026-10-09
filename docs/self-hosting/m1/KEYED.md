<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 private keyed snapshot increment

Base: 8f013511 (D253). This increment adds compiler-private read-only keyed
snapshots over the qualified D250/D251 copies and the structural read proof in
D254. M1.1/M1.2/M1.3 remain incomplete and no S1/G1 budget is evaluated. The
source/hash and complete-log ledger is [keyed-evidence/manifest.json](keyed-evidence/manifest.json).

## Storage and behavior

| Helper | Backing | Key semantics | Consumer field |
| --- | --- | --- | --- |
| `SnapshotMap<K, V>` | `HashMap.copy()` | value `hashCode`/`equals` | String-keyed `borrowedOwnedFields` |
| `SnapshotIdentityMap<K, V>` | `IdentityHashMap.copy()` | identity, no callbacks | allocation-keyed `states`, `retainedBorrows`, `poolOwners` |
| `SnapshotLinkedMap<K, V>` | `LinkedHashMap.copy()` | value, insertion order kept | D247 `knownArraySlots` |
| `SnapshotIdentitySet<E>` | `IdentityHashSet.copy()` | identity, no callbacks | `exposedContainerContents`, `unfreedLive` |

Each wrapper owns its private copy, borrows payloads and exposes only `size`,
`isEmpty` and lookup. Source mutation after construction is invisible. Lookup
performs no allocation. Destruction reclaims private storage and never frees
payloads. A null source throws `NullPointerException` with the same live delta
as an ordinary thrown exception, so failed construction retains no storage.
Allocation counts at sizes 0/8/32/128/512 are 9 and n+8 for the three maps and
11 and n+10 for the identity set. These are measured workloads, not a bound for
every capacity; the inherited float threshold can add growth at very large sizes.

The Java 21 [reference](keyed-evidence/KeyedSnapshotReference.java) checks the
same contracts with the consumer's actual Java calls: `Map.copyOf` value lookup,
identity lookup ignoring `equals`, the D247 linked copy and its encounter order,
identity set membership, `Map.copyOf`/`Set.copyOf` on identity-equal allocation
keys, and null source/value rejection. It passed in four fresh JVMs. It is a
logical reference only, never an oracle for native ownership or `free`.

## Proof boundary

Accepted programs retire the source before the snapshot and free payloads after
the last lookup alias. Rejected programs free a key or value still in the copy,
keep a lookup alias past the snapshot, publish a returned value, retain a
self-containing source, or free a live sibling's shared child version. Changed
lookup bodies that save a key, clear the map or call an observer lose the proof.
Value-family wrappers reject publishing key callbacks; the identity wrapper
accepts them because it runs none. Stored-key callbacks reached by a changed
count or lookup body expose the stored keys, including nested map contents. A
distinct scalar query does not inherit a reference result alias.

Ordered traversal and restore are deliberately absent. The seven-field
composition adds them with its own contract and evidence.

## Evidence

| Check | Result |
| --- | --- |
| Focused tests | 16 passed: four keyed tests plus list/map/set copies, private snapshots, D252/D253 and pool safety consumers |
| Native fixtures | `compiler_keyed_snapshots`, `_membership`, `_failure`, `_callback_failure` exit 42 (failure success path 43) from classes and archive at `-O3` |
| OOM | Limits 0-71 fail cleanly with exit 42; 72-80 complete with 43; private storage asserted reclaimed |
| `--unfreed=warn` | Every compile and link reports zero warnings, with no suppressions |
| IronDocs | Four types generated without diagnostics |
| Java reference | Four fresh JVMs pass |
| Hygiene | `git diff --check` and `./scripts/check-licenses.sh` pass |
