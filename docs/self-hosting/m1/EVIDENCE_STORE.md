<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 saved explanation evidence without weak references

Base: b8cf06cb. This settles the M1 handoff item "replace optional weak
diagnostic keys by explicit invocation-owned strong evidence with measured
retirement" (PILOT_HANDOFF; OWNERSHIP_PILOT API0197/0205/0206; SNAPSHOT_CONTRACTS
E2-E6). D259 records the decision; the ledger is
[evidence-store-evidence/manifest.json](evidence-store-evidence/manifest.json).

## Design

Java's `RejectedFreeEvidence` maps a `WeakReference` to each proof snapshot to
its saved maps and polls a `ReferenceQueue` to retire versions whose snapshot
the collector reclaimed. Natively there is no collector, and a store keyed by
snapshot identity could not free a saved version it removed: a map value is not
a proven fresh allocation.

Instead, each proof snapshot owns its optional saved evidence. The snapshot
constructor installs the store's `save()` result, which is fresh or null, so
the owned-field proof holds and the snapshot destructor frees it. The owner
calls the store's explicit `release(saved)` before freeing the snapshot, which
returns the version's units and decrements its sites' reference counts. Version
identity is simply the snapshot object.

| Java | Native |
| --- | --- |
| `SnapshotKey extends WeakReference`, identity hash | the proof snapshot itself |
| `ReferenceQueue.poll` in `retireCollected` | explicit `release` before `free snapshot` |
| `Map<Site, Integer>` reference counts with `merge` | primitive `references` field on the store-owned site |
| absent saved version: clear, mark truncated, return false | the same, for a snapshot saved without evidence |

## Evidence

| Check | Result |
| --- | --- |
| Accounting | Budgets 1,048,576, 8 and 5 admit two, one and zero saved versions; after restore, re-apply, fallback, release and close every unit and site reference is zero and the live allocation count is back at its baseline |
| Controls | Off/warn/error: release then free accepted; a saved-version alias used after its snapshot is freed, a separate free of the owned version, and use of a freed store are rejected |
| Artifacts | Classes and archive at `-O3`, zero `--unfreed=warn` diagnostics, exit 42 |

The real store's six maps, budget formula, merge intersections and limits are
M2.2 port work on this mechanism. Sites remain invocation-lived.
