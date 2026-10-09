<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# G1 checkpoint (S1 gate)

Status: G1 passes on the qualified macOS arm64 M5/32-GiB profile at the commit
named in [checkpoint-evidence/manifest.json](checkpoint-evidence/manifest.json).
M2.1, M2.2 and M2.3 are complete; every piece of
[S1 evidence](../../BEFORE_SELF_HOSTING_PLAN.md#evidence-for-the-s1-exit-gate)
has an evidence record below. M3 has not started. Passing G1 permits broad
translation only within the representations, proofs and budgets recorded
here; it does not claim that unported consumers fit them.

## Delivered slices and decisions

| Phase | Deliverable | Decision | Record |
| --- | --- | --- | --- |
| M2.1 | spans, diagnostics, lexer, parser, AST subset | D261 | [FRONTEND.md](FRONTEND.md) |
| M2.2 | seven-field ownership snapshots and joins, evidence store, unfreed tracker, effect analyzer, input factory | D262 | [OWNERSHIP.md](OWNERSHIP.md) |
| M2.3 | missing-free classification, mandatory-safety modes, geometric scales | none (measurement) | [QUALIFICATION.md](QUALIFICATION.md) |

## S1 evidence

| S1 evidence | Result | Evidence record |
| --- | --- | --- |
| Every inventoried hash-container traversal is classified; unresolved dependencies block their slice | Pass for both slices. M0 inventoried and classified all 2,957 traversals and gave every later Unresolved contract a blocking fixture; M1 classified the 446 hash contributions in the pilot scope and implemented D247 order. The frontend slice has no hash container (keyword `switch`, D258); the ownership slice traverses linked maps and key lists where order is observable, and its two value-keyed evidence maps only for order-independent reference counting, retention and intersection. Ordering variations pass: colliding keys (evidence kernel), resize boundaries (8/32/128), reversed predecessor and current-store order (shapes 2 and 4), and two fresh processes per configuration. Unresolved later contracts block only their own later slices. | [M0 checkpoint](../m0/M0_CHECKPOINT.md), [M1 classification](../m1/CLASSIFICATION.md), [OWNERSHIP.md](OWNERSHIP.md#equivalence) |
| Snapshots stay independent under mutation; value, identity and order match the baseline | Pass. All 47 kernel configurations equal J0 byte for byte from classes and archive, including the projections of the first `before` and `changed` versions after 63 further mutate/restore/join rounds; the kernel checks each saved version against later mutation; explanations on and off give identical primary facts for all 12 size and shape pairs; versions survive their analyzer's retirement (accepted control). Frontend trees and diagnostics equal J0 on 36 workloads, the 125-unit bundle, 759 sources and 3,000 mutations. | [OWNERSHIP.md](OWNERSHIP.md#equivalence), [FRONTEND.md](FRONTEND.md#equivalence) |
| Immediate and field-retained callback counts are recorded separately, including captured-state helpers; paired capture-cleanup cases | Pass. Immediate named observer 2 allocations, anonymous capturing observer 1 plus 1 captured holder, field-retained named observer 2; observer calls add 0 allocations. Freeing the observer after its holder is accepted; freeing it while the holder lives, or freeing captured state after the callback, is rejected in every mode. | [OWNERSHIP.md](OWNERSHIP.md#callbacks) |
| Pilot compile and link use `--unfreed=warn`; logs, per-command counts, unique sites, classifications and suppressions recorded | Pass. Ten commands with zero missing-free findings and zero mandatory errors; the test adapters' intentional process-lifetime retention is declared by five reviewed local suppressions. | [QUALIFICATION.md](QUALIFICATION.md#missing-free-findings) |
| Nearby unsafe frees stay rejected in every mode; `warn` permits only missing-free findings | Pass. Every pilot's paired controls hold in `off`, `warn` and `error`; the two unsafe corpus sources are rejected in all three modes; the ten pilot commands report zero mandatory errors. | [QUALIFICATION.md](QUALIFICATION.md#mandatory-safety) |
| Measured scale fits the recorded budgets | Pass, measured in one isolated run (earlier runs overlapped a stray helper process and stay provisional). Frontend: 74 records (the whole bundle in one invocation 0.270 s, 138.7 MB), every case within a 128-KiB stack. Kernels: 94 records at the selected maximum scale, zero outstanding temporaries, stack at most 32 KiB. Geometric scales beyond the references are recorded with their growth. | [QUALIFICATION.md](QUALIFICATION.md#measurement-isolation), [FRONTEND.md](FRONTEND.md#resources), [OWNERSHIP.md](OWNERSHIP.md#resources) |
| A safe reclamation form exists without runtime lifetime tracking, unsafe exemptions or non-retention assumptions | Pass. Builders and holders retire by `defer`; join predecessors by the existing D163 creation-array proof; snapshot builders in the caller's frame; observers after their holders in one frame. No analysis, runtime or lowering change was made, and no unknown call is treated as non-retaining. | [OWNERSHIP.md](OWNERSHIP.md#lifetimes), D262 |

S1's exit adds that the snapshot model must not need new ownership semantics:
it does not. Every representation above uses proofs that existed before M2.

## Consumers blocked by retained conservative limits

These are rejections, not safety gaps. The pilot avoids each with an existing
proof; the listed consumers must use the same forms or wait for a focused
analysis change with paired safe/unsafe regressions.

| Limit | Pilot form | Blocked consumers |
| --- | --- | --- |
| A list lent to an analyzed method exposes its contents, so the listed objects cannot be freed (the M1 copy-lent limit) | join predecessors in an `OwnershipPaths` creation array | `FunctionAnalyzer` flow merges that collect `BranchFlow` or `JoinPath` lists (if, try/catch, switch, loop exits and back edges); `RejectedFreeEvidence.merge` callers with key lists |
| A getter that returns a constructor-held service, or a helper frame that loops over a holder, leaves every service the holder retains escaping (D253 escape summaries) | no service getters; observer, holder and loop in one frame | `SemanticAnalyzer`/`CompilerPipeline` observer and evidence-store plumbing that passes services through getters or loops in helpers; their services stay invocation-lived |
| Builders freed in the frame that copies them stay lent (copy-lent in the same frame) | `SnapshotBuilders` built by the caller | every record-like snapshot built from freshly collected lists |
| Objects stored in an analyzer's live containers cannot be freed individually | nodes, state and child versions, evidence payloads and IR are invocation-lived (D262) | per-function retirement of analyzer graphs before the whole invocation ends |
| A frozen child list stays with the node that stored it (D253) | AST nodes and their lists are invocation-lived (D261) | AST retirement before the invocation ends |

## What M3 needs

- Keep the D261/D262 representations for the remaining frontend and analyzer
  ports: `OwnershipPaths`-style owners wherever Java hands a list of versions
  to an analyzed method, services passed by field and never exposed by a
  getter, and caller-frame builders for record snapshots.
- Port the later-only roles behind explicit gates: nonempty join paths and
  alternatives, non-`LOCAL_NEW` origins, the remaining IR instruction,
  terminator and operand variants, `TypeName` display/splitting, `DeclaredTypes`,
  `PatternFlow`, `SourceFile.read` and `DiagnosticFormatter` (M3.1/M3.3).
- Finish B1/B2/B7 in consumer order and the early B5 SHA-256 digest (M3.2).
- Budget the effect analyzer before larger inputs: its fixed point needs one
  round per function in a chain, as Java's does, so 512 functions exceed the
  500-ms phase cap.
- If whole-invocation retention of analyzer graphs proves too costly at S3/S4
  scale, revisit it with a focused precision change, not a weaker proof.

## Checkpoint run

One run on a fresh `git archive` tree of 48a1516b (M2.3 with the declared
adapter retention and the isolated re-measurement): the strict
`scripts/build.sh` (library under `--unfreed=error`), `javac --release 21
-Xlint:all -Werror` over every compiler test source, and the nine M2 focused
tests (five frontend, four ownership), all passing, with a clean
`git diff --check` over every M2 change since the M1 checkpoint and a passing
license audit. The [manifest](checkpoint-evidence/manifest.json) names the
commit and each test, retains compressed logs and hashes the three M2 records
and their ledgers. [run-evidence.sh](checkpoint-evidence/run-evidence.sh)
reproduces it. An earlier checkpoint run of 65fdb750 also passed. Unsafe
programs were compile-only; no full suite or hosted build ran.
