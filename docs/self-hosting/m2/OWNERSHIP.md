<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M2.2 native ownership slice

Base: cb64dda1 (M2.1). This settles M2.2: the selected ownership, explanation
and effect operations (OWNERSHIP_PILOT, OPERATION_MODEL) run natively and match
J0. D262 records the decision; the ledger is
[ownership-evidence/manifest.json](ownership-evidence/manifest.json). This does
not establish S1/G1, which also needs M2.3.

## Shape

The port adds `ir` (the admitted IR records), `semantic` (the analyzers and
their state) and `port/BitRows` to `compiler/src/main/ironwood/ironwood/compiler/`.

| Java | Native |
| --- | --- |
| `FunctionAnalyzer`'s seven ownership fields, `snapshotOwnership`, `restoreOwnership`, `mergeOwnership`, `selectBlocked`, `uniqueIncomingArrayStore` | `FunctionOwnership`, with the same agreement, uncertain and may-be-freed joins, first-owner pool precedence and conflict blocking, retained-child and exposed unions, lost-slot blocking and live intersection |
| `OwnershipSnapshot` record | `OwnershipSnapshot`, the D257 composite; its constructor is its only factory and copies borrowed fields plus `SnapshotBuilders` made in the caller's frame |
| `mergeOwnership(before, List<OwnershipSnapshot>, reason)` | `mergeOwnership(before, OwnershipPaths, reason)`: a D163 creation-array owner of the predecessors, filled by `captureOwnership` |
| `AllocationInfo`, its state record, `ArraySlot` | `AllocationInfo` holding a shared immutable `AllocationStateSnapshot` version (D257), `ArraySlot` with value equality |
| `RejectedFreeEvidence` with weak version keys | the six maps, budgets and limits on D259 snapshot-owned `Saved` versions; `merge` reads predecessors through `RejectedFreeEvidence.Paths`; `FunctionOwnership.releaseOwnership` releases a version before it is freed |
| `UnfreedAllocationTracker.merge(List<Set>)` | the analyzer intersects the predecessors' live members and installs them with `clearLive` and `addLive` |
| `ClosedWorldEffectAnalyzer` summaries of `BitSet`s, pattern `switch`, `observerProjection` | one analyzer-owned `BitRows` matrix of three rows per function updated in place, scratch rows, an ordered `instanceof` chain, `appendSummary` in Java's `Summary[...]` text |
| IR records with `Optional` results | final classes with nullable results; only the admitted variants exist |
| streams, `forEach`, `removeIf`, lambdas | ordered loops with the same short-circuiting (D258) |

`PilotInputs` admits only the D260 roles: `LOCAL_NEW` allocations in an input
state, and effect functions with unique names, the `METHOD` kind, reference
types, one entry block of direct or fixed-target foreign calls on
nonnegatively numbered value references, and a value return. Everything else
throws before an analyzer exists.

## Lifetimes

| Object | Lifetime | Proof |
| --- | --- | --- |
| allocation nodes, state versions, child versions | invocation | they enter the analyzer's live list, node fields and maps, where a `free` is rejected |
| evidence sites, bindings, events, joins, retention keys | invocation | owned by the store's maps; versions share them |
| admitted IR | invocation | the analyzers borrow it |
| snapshots: copies, key lists, saved evidence | retired | `free`, or the join owner's destructor loop |
| join owners (`OwnershipPaths`) | retired | local `defer free` after `releaseOwnership` |
| builders, the evidence merge's common maps, the analyzers' scratch | retired | `defer` on every exit |
| effect analyzer with its summary matrix | retired | the caller frees it after projection |
| observer | the caller's | freed after its holder |

Where Java hands a list to an analyzed method, Ironwood's escape analysis
exposes the list's contents, so a version passed to `mergeOwnership` in a list
could never be freed. The join owner avoids that without any exemption: each
version is a fresh allocation recorded once in a private creation array,
lent through direct indexed getters, and destroyed by the canonical loop.

## Equivalence

Every result is compared byte for byte with the retained J0 `result.txt` of
the same configuration (ordered seed; cycles from the loop/cycle reference).

| Kernel | Configurations | Mismatches |
| --- | --- | --- |
| ownership: 8/32/128 nodes, shapes 1-4, explanations off/on, 64 iterations, 128 held versions and the merged version | 24 | 0 |
| evidence: 8/32/128 colliding nodes, 64 iterations, 128 held keys | 3 | 0 |
| effect chains: 8/32/128 functions x 65 parameters, 16 x 8 and 16 x 257, observer off/on | 10 | 0 |
| effect cycles: the same shapes | 10 | 0 |

The kernel adapter (`scripts/self-hosting/native/KernelCapture.iron`) holds
each iteration's versions in a recursion frame, so all 128 stay live until
verification as Java's list keeps them, and each frame retires its owner by a
local proof. Its `inputs` mode shows the boundary: one admitted chain, then
eight deviations (duplicate name, constructor kind, second block, devirtualized
call, other foreign target, missing foreign result, missing return value,
negative value number) and a freed allocation, each rejected before analysis.

## Retirement

The adapter reads the live-allocation count after setup and again after every
version, owner and analyzer has retired. The difference is exactly the
invocation-lived payload plus the result text, so outstanding temporaries are
zero in every run:

| Kernel | Live after retirement | Derivation |
| --- | --- | --- |
| ownership, explanations off or tight budget | 961 | 64 x (7 state versions + 2 child versions x 4 allocations) + 1 |
| ownership, explanations on | 1,281 | the same plus 64 x 5 selected-reason events |
| evidence | 128 | 64 x (1 join + 1 event) |
| effect chains and cycles | 1 | the projection text |

Per ownership iteration the state versions are the two mutations (`ESCAPED`,
detached), the two joined uncertain states and the three blocks (lost slot,
two pool owners); the child versions are the replacement set and the joined
union; the adapter calibrates a two-member child version at 4 allocations
before the phase. The counts do not depend on size, so no snapshot storage
survives, although the phases allocate 50,469 to 212,390 objects per ownership
run and 60,480 to 163,776 per evidence run.

## Callbacks

`SemanticAnalysisObserver` is the slice's callback service: immediate when a
helper builds an analyzer around it and retires the analyzer before returning,
field-retained when a long-lived `FunctionOwnership` keeps it across
snapshots and restores. The [callback fixture](../../../integration-tests/cases/compiler_ownership_callbacks.iron)
measures each case against a run without the observer:

| Case | Callback allocations | Captured-state allocations | Allocations added by its calls |
| --- | --- | --- | --- |
| immediate, named observer owning its counter | 2 (object and counter) | 0 | 0 |
| immediate, anonymous observer capturing a local holder | 1 | 1 (the holder) | 0 |
| field-retained, named observer over 16 snapshots and restores | 2 | 0 | 0 |

The slice's Java stream and `forEach` callbacks are loops natively, so they
allocate nothing. The effect kernels confirm that observer rounds allocate
nothing: observer on and off have the same phase allocations.

Paired controls, compile-only in `off`, `warn` and `error`:

| Program | Outcome |
| --- | --- |
| two captures (growing the owner), a join reading a lent version, release, retire | accepted, no diagnostics |
| retire the analyzer before its versions (copies are independent) | accepted, no diagnostics |
| observer, holder and loop in one frame; free the holder, then the observer | accepted, no diagnostics |
| free a version the owner lends | rejected: borrowed helper owned by another object |
| use a lent version after its owner is freed | rejected: use after free |
| free a snapshot's saved evidence | rejected: borrowed helper owned by another object |
| free a child version a snapshot copied | rejected: it escaped into the live map |
| free a node a snapshot observes | rejected: it escaped into the live list |
| free the observer while its holder is live | rejected: still borrowed by a live wrapper |
| free state an anonymous observer captured, after freeing the observer | rejected: retained as a captured variable |

Two further rejections are retained conservative limits, not safety gaps, and
the representation above avoids both: versions lent through a list to an
analyzed method cannot be freed afterwards, and a helper frame that loops over
a holder leaves the service it was given escaping (D253).

## Resources

All runs use the PILOT_BUDGETS procedure: 8,176-KiB soft and hard stack limits
set before exec, `/usr/bin/time -l`, a 1-second runner timeout, two fresh
repeats of all 47 configurations.

| Group | Runs | Maximum wall | Maximum RSS | Maximum phase | Budget (wall, RSS, phase) | J0 maxima |
| --- | --- | --- | --- | --- | --- | --- |
| ownership | 48 | 0.243 s | 12.6 MB | 7.5 ms | 1 s, 512 MiB, 250 ms | 0.164 s, 97.8 MB, 61.7 ms |
| evidence | 6 | 0.020 s | 10.6 MB | 10.0 ms | 1 s, 512 MiB, 250 ms | 0.132 s, 84.3 MB, 56.8 ms |
| effect | 40 | 0.077 s | 3.2 MB | 33.3 ms | 1 s, 512 MiB, 500 ms | 0.224 s, 261.6 MB, 130.5 ms |

All 94 records pass `check-pilot-budget.py` with zero outstanding temporaries
and zero mandatory build safety errors. The ownership wall maximum is the first,
cold run; every other run takes 0.020 s or less. Stack: bisecting the external
limit, every configuration completes at 32 KiB and fails at 16 KiB.

## Failure safety

Under every allocation limit from 0 to 3,561, a run of snapshots, two join
captures (growing the owner), a join, restore, release and an observed effect
analysis fails with a caught `OutOfMemoryError` while every `defer` retires its
object; at 3,562 it completes with the expected states and summary.

## Tests

Four focused tests: `ownership pilot retires versions and rejects frees of
observed state`, `ownership pilot counts callbacks and rejects frees of
captured state`, `ownership pilot matches the Java kernels across artifacts`
(classes and archive, all 47 configurations, the retirement counts and the
input boundary) and `ownership pilot unwinds every allocation failure
cleanly`. The five M2.1 frontend tests rerun because they compile the grown
port tree. The pilot and both fixtures compile and link under
`--unfreed=warn`; M2.3 classifies their findings.

## Reproduce

[run-evidence.sh](ownership-evidence/run-evidence.sh) rebuilds a fresh tree and
runs every check above, the license audit, and the native frontend over the 27
new source units, whose tokens, spans, trees and diagnostics equal J0's (135
files). The tools are `scripts/self-hosting/measure-m2-kernels.py`
and `check-m2-kernel-budgets.py`.
