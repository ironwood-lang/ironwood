<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge P3a compiler admission gate

The P3a compiler-admission checkpoint passes for continuation to P3b on local
`java-bridge`. P3b/P3c/P3d and the P3 combined host safety gate remain unfinished.
The public producer still admits its P2 value preview only. This audit does not
claim generated object-facade support, OrderBook execution or release readiness.

The authoritative scope is [the implementation plan](JAVA_BRIDGE_PLAN.md),
especially D192/D196/D200/D202/D204/D211 and its P3 submilestones. P0 analysis
modules remain the production proof foundation. No source free proof, unknown
effect or unsafe case has been relaxed to enable a bridge export.

## Contract and evidence map

| Contract | Production compiler mechanism | Focused evidence |
| --- | --- | --- |
| Complete signatures | `BridgeExportSurface.objectValues` inventories concrete/static-nested/enum APIs and copied custom snapshots. Package closure includes catch parents and getter/throws types. Constructors for exception snapshots are not native object roots. | Custom snapshot, snapshot-surface, enum invocation and API-boundary tests; source/class-directory/individual-class/archive checks. |
| Automatic lifetime selection | `BridgeObjectAdmission` requests D192 proof for unknown object results and explicit receiver publication, follows proved dependent views and retains ordinary roots. Failed root cycles, slot transfers or cleanup are not retried as permanent APIs. | `p3a-object-admission-final2.log`; root, mixed, permanent, dependent-view and actual OrderBook cases. Source deallocation and unknown effects refuse admission. |
| Root retention and cleanup | `BridgeRootRetentionAnalyzer`, `BridgeDestructionAnalyzer` and `BridgeCleanupAnalyzer` preserve exact fixed slots, acyclic root dependencies, uniform result origins, separate destruction and unpublished rollback. | Root/enum and final-root selectors; retained views, helpers/specialized helpers, exceptional effects and rejected slot transfers/unknown owners/cycles. |
| Mixed permanent references | `BridgePermanentValues` binds exact program/roots and independent non-reclamation proofs. Permanent holders cannot capture reclaimable inputs. Generated root destruction remains inside final permanent proofs. | `p3a-mixed-lifetime-final.log`, six selectors and 66 O0/O3 native children. Permanent storage survives root destruction; a destructor reaching a permanent candidate is rejected. |
| Complete generated closure | Exact additive synthesis and `NativeLinkTransformation` derive conservative facts for actual optimized bodies. Final root and non-reclamation validators include getters, trace entries and cleanup. | Generated-construction/reclamation/provenance tests and final lifetime/root selectors. Stale, foreign, partial and modified payloads are refused. |
| Exception declarations and getters | `BridgeExceptionClosure.snapshots` reaches a fixed point including exported but never-thrown snapshot declarations and exceptions introduced by getters. Protected extraction owns its copied values. | `p3a-snapshot-surface-final.log`, five selectors; custom getter native evidence and allocation failures; unsupported projections and hidden getter reclamation/publication remain refused. |
| Value-only projection | Explicitly proved enum/custom-snapshot value modules may have an empty object lifetime inventory. No destruction state follows from emptiness. | `p3a-value-admission-final.log`, two selectors; checked snapshots, enum overrides and rejected String publication/unknown ownership. |
| Actual OrderBook compiler closure | Complete public API selects permanent Order and OrderBook storage, without synthetic cleanup or fabricated owners. Final internal PriceLevel and array storage proofs also pass. | `p3a/object-admission/run-8186120906615387038`: admission reasons, final roots, matching compiler identity and LLVM assembly/verification. P0 rollback/reconstruction controls still pass. |

Logs referenced above live under ignored `workspace/java-bridge/experiments/`;
payload evidence paths are relative to `workspace/java-bridge/evidence/`.
The durable [progress log](JAVA_BRIDGE_PROGRESS.md) records earlier checkpoints,
commits, corrected test setups and exact selections. Source changes passed the
license audit and `git diff --check`; no unfiltered suite was run.

## Matched payloads and limits

- Mixed lifetime native payload: `p3a/mixed-lifetime/run-12656671066873094957`,
  LLVM SHA-256 `2a5bd7c4934dc6e0a3b05c08f7c23ce249f711773901c224bddf53edecc9e299`.
  Eighteen macOS ARM64 O0/O3 children cover unlimited and 0-7 allocation budgets,
  unpublished rollback, exceptional slot deltas and destruction. Inspected O3
  scalar/reference getters use four instructions without helper calls or lifetime
  bookkeeping. A 100,000-pair diagnostic loop adds zero native allocations;
  raw O0/O3 times are 1,439,000/590,000 ns, not numerical acceptance.
- Existing final root/enum and root/String payloads retain LLVM identities
  `99b6bfe5f5f546d9244a14a03be78584cd462a00d861b15618bbb4bf42a0dfbc`
  and `d813cb8a5d695482b768f8e13f67729d4efbaa9f348383eccd7f3910bf3ae054`.
  Forty-eight additional native children pass after mixed-lifetime composition.
- Final OrderBook LLVM identity is
  `53a869fd7969d5409d340c3ffb3b49ba884084ce4c096a36fabde872ed9d3af6`.
  This is compiler/LLVM evidence only. Generated-Java workload, allocations and
  timing remain P4/P6 obligations.

These private harnesses exercise compiler-produced, proof-bound entries. They
do not implement Java weak-cache identity, root index/global references, host
counts, free refusals or Java custom snapshot classes. P3b/P3c/P3d must use these
contracts and replace harness bindings with generated adapters, then repeat the
relevant cases against those exact artifacts.

Unproved ownership shapes remain producer errors. In particular, fresh method
results whose root type has retained slots still need bounded initial-slot
reporting, and dependent views currently require one exact independent root
input. Neither limitation may be bypassed in a generator. Mixed fresh/existing
reclaimable results, root cycles, slot transfers, permanent holders of roots,
hidden publication, unknown effects and unproved destruction remain rejected.
P5 callbacks and P7 extensions remain deferred. D213 real x86-64 hardware checks
and final numerical acceptance remain pending; this checkpoint changes neither.
