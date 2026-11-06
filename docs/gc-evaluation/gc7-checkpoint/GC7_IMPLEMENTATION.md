<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC7 implementation checkpoint

Date: 2026-11-06. **GC7 is incomplete.** This local checkpoint removes the
ordinary whole-program managed-free refinement loop and principal function-level
free proof/lowering work, and migrates IDE/test-harness consumers. It does not
meet the repository-wide retirement exit criterion. See the
[disposition ledger](GC7_DISPOSITION.md), [pre-change review](GC7_PRECHANGE_REVIEW.md)
and [compact evidence](gc-evaluation/gc7-checkpoint-2026-11-06.json).

Baseline: `3afea8f8`, following `a91a13cd`, on
`codex/bdwgc-integration-plan`. The checkout and both origin URLs were verified;
there were no subsequent commits or unrelated edits. No fetch, push, main
integration, branch/worktree change, hosted workflow or self-hosting build ran.

## Removed work and remaining contracts

SemanticAnalyzer binds functions, computes dispatch/reference effects and lowers
final functions without the old owned-field/return/temporary convergence loop,
argument-reclaiming relowering, rejected-free readiness/evidence budgets or
ClosedWorldEffectAnalyzer invocation. Eager exclusive-field proof computation
is removed. Private destructor declaration and constructor-rollback function
builders are removed.

FunctionAnalyzer no longer has managed-free operand proofs/producers, deferred
free actions, use-after-managed-free verdicts, loop-back free verification,
full-expression temporary candidate processing, owned rendering release lists,
or free-specific witness collection. General defer still captures typed
operands and emits ordinary control flow. Pending exception/return/yield values,
D051 cleanup order, initialization, null checks and pool/resource provenance
remain separate from reclamation.

Legacy bridge rollback consumers must not receive a proved empty ownership
contract after the field proofs stop running. Their constructor capability is
explicitly unknown. GC6 managed admission uses exact layouts, roots and its
actual foreign/resource proofs instead. The focused regression pairs this
refusal with successful managed admission of the same shared graph.

The retained lazy encapsulation, escape, symbolic-return, pool and dispatch
analyses still have real foreign/protocol consumers. Their narrower replacement
and removal of remaining heap-only state are unfinished. In particular,
OwnedArrayFieldAnalyzer, its checker, obsolete AST/IR records, descriptor flags,
option enums, legacy bridge analyses and many old test/generator consumers still
exist. This is the concrete unresolved safety boundary: deleting these shared
facts indiscriminately would lose confinement/protocol proofs, while granting
nonretention or rollback by assumption would be unsound. No completion claim is
made for that dependency closure.

## Consumers migrated

The @Test generator creates managed runner/suite values without synthetic free
or a process-lifetime suppression premise. Its deterministic dispatch and invalid
suite coverage remain. The Eclipse grammar now follows the current lexer,
including previously uncategorized `defer`; former reclamation words receive
ordinary method scopes. Removed suppression is invalid directive syntax. LSP
symbols no longer traverse destructor declarations. New-project templates use
ordinary managed values and exercise alias survival across explicit collection.
Output-parser fixtures preserve general primary/related-note behavior, and LSP
tests cover unsaved removed-syntax diagnostics without missing-free warnings.
The Eclipse verifier's compiler classes are now included on its actual classpath.

GC5's three obsolete blanket bridge-output refusal expectations are replaced by
the existing actual GC6 object/callback/image qualification families. Stale SDK
and source/class/archive checks remain in the input driver. Other repository
consumer migrations are pending in the ledger; they were not silently classified
as historical or deleted.

## Verification

Host role `machost`, macOS arm64. Java 21.0.1, LLVM 23.1.0, pinned BDWGC 8.2.12
with USE_MARK_BYTES=1. The qualified Xcode SDK was selected explicitly. The GC6
SDK configuration remains
`0ac9acaa6ec276015c281b881473334ee3d457c736964070154994e18f836d5d`.
Runtime, descriptor/root protocols and SDK payload sources are unchanged, so no
physical ABI identity was reused for a changed layout. Ordinary roots remain
ABI 2 / owner ABI 1; bridges remain schema 2 / objects-gc-v1, root ABI 3 / owner
ABI 2 with suspended segments. The single-caller/external-serialization contract
and Linux arm64/arm64e gates remain.

The evidence manifest records exact commands/selections, reports and hashes:

- Build/bootstrap and main/testing artifact reconstruction succeed. The compiler
  check uses the 16 GiB process-group RSS guard and 60-second child bound. A
  bounded javap check of the actual SemanticAnalyzer class verifies absence of
  the old temporary proof, owned-element validation, argument-reclaiming relower
  and field-proof convergence calls. This substantiates removed computation,
  not GC8 compiler capacity or a compiler throughput claim.
- Four GC5 compiler controls, four GC6 compiler controls and three general defer
  parser/semantic/typed-capture selections pass. The new legacy-capability versus
  managed-admission regression and both @Test synthesis/negative selections pass.
- GC5 integrated, resource, pool, OOM and no-automatic-collection controls pass at
  O0/O3. They cover shared/cyclic graphs, partial construction, collecting cleanup,
  pending values, resource close, retained pools and failure ordering.
- Actual generated GC6 object and callback JVM consumers pass at O3 with
  -Xcheck:jni. These include anchors, aliases/views, listener ownership, suspended
  JVM reentry, carrier/JNI completion and the drivers' paired confinement controls.
- Class/archive native parity and ten stale SDK-input refusals pass. GC5 intrinsic
  tests retain source/artifact migration paths and altered intrinsic refusals.
- TM4E grammar generation/verification, Eclipse build, compiler-output primary/
  related-note parsing, template compilation and stdio LSP protocol tests pass.
  The highlighting source compiles; generated greeting and shared-result test
  programs run at O0/O3.
- O3 call/pool deterministic controls compare defer/finally kernels, inspect
  normalized assembly and run two alternating rounds of 32 million operations.
  Both retain zero warmed allocations/live delta and identical paired normalized
  application code. This is a bounded steady-state control, not GC8 acceptance.
- Diff, licenses, tracked-file and generated-grammar consistency checks apply to
  the final checkpoint. Raw logs, binaries, SDKs and the full candidate inventory
  stay ignored. No unfiltered/compiler-platform suite ran.

Unexecuted coverage includes the full pending consumer/test disposition,
standalone GC2-GC4 native fault matrices, GC6 image/carrier/critical-call matrices
beyond the selected JVM drivers, Linux requalification, and changed consumers
outside this checkpoint. Earlier qualification is preserved as historical
evidence, not represented as a rerun of this revision. No claims are made for
Linux arm64, arm64e, asynchronous/concurrent or unsupported foreign lifetimes.

## Unmet exit criteria and handoff

The repository still contains maintained old syntax/options, Java bridge
construction/free consumers, destructor/live-count assertions, and old compiler
AST/IR/proof structures. The ledger is not a completed disposition of every
mixed test family. Final-link rejection remains fail-closed, but structural
retirement is not finished. GC7 must remain open.

Complete that dependency and consumer migration, preserve paired native/resource
negatives, then rerun the focused affected controls before accepting GC7. Do not
begin GC8-GC10. The eventual GC8 handoff is limited to deterministic hot kernels
and repeated resident graph phases under the existing RSS bound; this checkpoint
does not authorize capacity acceptance, a full suite or self-hosting.
