<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 checkpoint

Status: M1.1, M1.2 and M1.3 are complete on the qualified macOS arm64 M5/32-GiB
profile at the commit named in [checkpoint-evidence/manifest.json](checkpoint-evidence/manifest.json).
M2 has not started; S1/G1 is not evaluated. Passing this checkpoint makes M2
runnable and claims nothing about native pilot results or budgets.

## Delivered helpers and decisions

| Phase | Deliverable | Decision | Record |
| --- | --- | --- | --- |
| M1.1 | `ArrayList.copy()` with structural item loans | D248 | [ARRAY_LIST.md](ARRAY_LIST.md) |
| M1.1 | `SnapshotList`, `SnapshotBits` | D249 | [SNAPSHOTS.md](SNAPSHOTS.md) |
| M1.2 | Value, identity and linked map copies | D250 | [MAPS.md](MAPS.md) |
| M1.2 | Value, identity and linked set copies | D251 | [SETS.md](SETS.md) |
| M1.2 | Constructor helper confinement (safety correction) | D252 | [SETS.md](SETS.md) |
| M1.2 | Receiver identity through constructor-held fields (safety correction) | D253 | [FIELD_ALIAS.md](FIELD_ALIAS.md) |
| M1.1/M1.2 | Read-only keyed snapshots | D254 | [KEYED.md](KEYED.md) |
| M1.2 | `WorkQueue` FIFO; evidence-store iterator removal | D255 | [WORKLIST.md](WORKLIST.md) |
| M1.3 | `TextBlocks.stripIndent` | D256 | [TEXT.md](TEXT.md) |
| M1.1/M1.2 | Seven-field ownership snapshot composition with traversal lists | D257 | [COMPOSITION.md](COMPOSITION.md) |
| M1.3 | `CharEscapes`, `SnapshotInts`, `Lists.single`, record value rules, port conventions | D258 | [VALUES.md](VALUES.md) |
| M1.1/M1.3 | Snapshot-owned saved explanation evidence | D259 | [EVIDENCE_STORE.md](EVIDENCE_STORE.md) |
| M1.3 | Finite input model with exhaustive variant switches | D260 | [VARIANTS.md](VARIANTS.md) |

The source bundle for M2.1's combined manifest is hashed in
[HELPER_BUNDLE.json](HELPER_BUNDLE.json): twelve compiler-private helpers under
`compiler/src/main/ironwood/ironwood/compiler/port/`, the seven library sources
that gained M1 copy APIs, and the strict-built library archive they compile
against.

## Checkpoint criteria

The plan requires Java-equivalence evidence, allocation measurements, and
applicable safe/unsafe and failure-cleanup pairs for each selected helper.

| Helper | Java equivalence | Allocation | Safe/unsafe pairs | Failure cleanup |
| --- | --- | --- | --- | --- |
| `ArrayList.copy` | Java 21 logical contract; 400-operation copy-constructor differential | three allocations at measured sizes | all modes | every OOM boundary |
| `SnapshotList`, `SnapshotBits` | `SnapshotReference` | four and two allocations | all modes | null rollback, every OOM boundary |
| map copies | Java 21 logical contract | 8 empty, n+7 | all modes | every OOM boundary, throwing callbacks |
| set copies | Java 21 oracle | 10 empty, n+9 | all modes, D252 | OOM limits 0-80, throwing callbacks |
| keyed snapshots | `KeyedSnapshotReference`, four JVMs | 9 and n+8; 11 and n+10 | all modes | null rollback, OOM 0-80, callback failure |
| `WorkQueue` | `ArrayDeque` transcript, four JVMs | 2 construction, 0 steady state | all modes | OOM 0-8 |
| `TextBlocks` | 20,012 inputs equal to Java 21 | one live result per call | all modes | value-helper OOM sweep |
| composition | seven-field join projection equal to Java; 43-check M0 value replay | 6n+75 retained | all modes | null rollback at 8 positions; save OOM 131-267 |
| value helpers | 131,072 escapes, 64 int sequences, records equal to Java 21 | results only | all modes | OOM 0-15 |
| saved evidence | Java store E2-E6 accounting (mechanism) | every unit and reference returns to zero | all modes | builder retired by `defer` |
| `OperationVariants` | lists equal Java sealed permits and enums by reflection | none | fails closed in all modes | not applicable |

Mandatory safety: D252 and D253 close the constructor-rollback publication
holes found during M1, with source, class and archive rejection in every unfreed
mode and unchanged valid LLVM. Unsafe programs were compile-only.

## Inventory reconciliation

[CLASSIFICATION.md](CLASSIFICATION.md) classifies every handoff item. With its
(C) items closed, the 1,330 selected calls are:

| Phase | Calls | Existing API (A) | Port rewrite (B) | Delivered (D) |
| --- | --- | --- | --- | --- |
| M1.1 | 916 | 518 | 166 | 232 |
| M1.2 | 15 | 0 | 0 | 15 |
| M1.3 | 399 | 304 | 89 | 6 |

All 723 syntax rows, 65 capture rows and 446 hash contributions are classified
there. Existing-API (A) members were confirmed to exist; their behavior rests on
the M0 contract reviews and is exercised by the M2 consumer comparisons. Eight
RECORD rows whose simple names coincide with selected records are excluded
(D258). The port conventions the M2 sources must follow are recorded in D258,
D257, D259 and D260.

## Remaining work

M2 obligations created or confirmed by M1:

- M2.2 ports save, restore and join and the evidence store's six maps onto the
  D257 and D259 mechanisms, keeps their `defer` cleanup, and chooses the native
  lifetime proof for allocation nodes, state versions, child versions and
  evidence sites, which M1 leaves invocation-lived.
- M2.2's input factory admits only the D260 variants and rejects everything
  else before analyzer entry; the effect pattern switch becomes an ordered
  `instanceof` chain.
- M2.1 applies the D258 conventions (presence branches, fixed-arity overloads,
  keyword `switch`, per-type empty lists, `SnapshotInts`, `CharEscapes`,
  `TextBlocks`) and the D260 exhaustive-switch pattern to AST variants, then
  completes the combined source manifest and measures it against the M0 budgets.

Later B1/B2/B7 work, unchanged by M1: the other WORKLIST_CONTRACTS queues and
any stack consumer (M3.1/M6), B2 sorting and sorted containers (no pilot
dependency), the later-only operation-model roles (M3.1), and the B3-B7 host,
digest, archive and Bridge work in M3-M6.

Conditional, not implemented because no consumer has demonstrated the need:
loan precision through multi-field composites (payloads read through a snapshot
stay live), loan discharge for queued items, a general deque, cursors or sort
workspace, and payload retirement beyond the M2.2 lifetime proof. Known
conservative limits retained as probes: escape summaries keep a parent
unfreeable after a local holder or an instance-method helper holds it (D253),
and a list passed to a helper, copied, and then lent through that copy to a
helper in the same frame stays unfreeable there, because exposing the copy's
contents marks its structural lender escaped. The list differential keeps each
copy generation in its own frame ([ARRAY_LIST.md](ARRAY_LIST.md#java-differential)).
These are rejections, not safety gaps; M2 records any consumer they block.

## Checkpoint run

One run on a fresh `git archive` tree of the checkpoint commit: the strict
`scripts/build.sh` (library under `--unfreed=error`), `javac --release 21
-Xlint:all -Werror` over every compiler test source, and the 55 focused tests
that M1 added or that consume its shared analysis (43 M1 tests and 12 earlier
consumers: pool release helpers, constructor and destructor effects, temporary
constructor helpers, Throwable printing, Java Bridge foreign-call effects,
array snapshot join witness order and rejected-free evidence snapshots). The
manifest names the commit and each test, and retains compressed logs, the
`git diff --check` and license audit results, and the hashes that
[HELPER_BUNDLE.json](HELPER_BUNDLE.json) binds. Unsafe programs were
compile-only; no full suite or hosted build ran.
