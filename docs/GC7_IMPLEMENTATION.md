<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC7 implementation

Date: 2026-11-06. **GC7 is complete.** The compiler implements one managed
memory model. Maintained consumers use explicit collection, resource lifecycle
operations and GC6 anchors/registrations. The former managed-free proof fixed
point, its subordinate heap-only analyses, AST/IR producers and legacy bridge
projection builders are removed.

Read the [disposition ledger](GC7_DISPOSITION.md),
[pre-change review](GC7_PRECHANGE_REVIEW.md), and
[compact verification](gc-evaluation/gc7-verification.json). Exact retired test
families and input hashes are in the linked machine ledgers. This completes the
`e8ea963d` checkpoint, preserving `3afea8f8` and `a91a13cd` beneath it on
`codex/bdwgc-integration-plan`. The original checkpoint reports are archived
[byte for byte](gc-evaluation/gc7-checkpoint/manifest.json). Historical reports
retain their original claims and hashes.

## Six completed stages

1. Inventory and disposition: compare GC0's inventory with actual compiler Java,
   checked-in compiler Ironwood, runtime, standard library, examples, projects,
   fixtures, generators, benchmarks, scripts, IDE, packaging and current programs
   in documentation. Classify lexical matches by meaning. Record intentional
   migration negatives and frozen history separately.
2. Consumer migration: remove managed reclamation syntax, options, destructor
   dependence and immediate-reclamation assertions. Preserve operand evaluation,
   exceptions, explicit resource close, native acquisition cleanup and pool
   release/reuse. Use explicit collection at completed workload boundaries.
3. Compiler retirement: delete obsolete forms, release/rollback lowering,
   semantic metadata, proof passes and cached evidence. Replace genuine remaining
   consumers with the narrower facts described below.
4. Test migration: retire obsolete verdicts explicitly, split mixed families,
   and retain functional, initialization, resource, pool, artifact and foreign
   lifetime coverage. Add shared-graph and paired safe/unsafe regressions.
5. Tooling and documentation: regenerate authoritative grammar/IR catalogs,
   migrate highlighting, completion, symbols, templates, launch settings and
   diagnostic parsing, and synchronize current guidance/specifications/programs.
   D324 supersedes the incomplete checkpoint and obsolete reclamation premises.
6. Verification and local delivery: run focused guarded compiler, native, JVM,
   artifact, IDE and deterministic hot-path controls; retain compact evidence;
   run repository checks and commit locally. GC8-GC10 and self-hosting were not
   started. No fetch, push, main integration, branch/worktree change, pull request
   or hosted workflow is part of this work.

## Structural retirement and remaining analyses

Removed Java and checked-in Ironwood free/deferred-free/destructor AST forms,
visitor branches, managed deallocation/destruction/rollback/owned-result-release
IR forms, destructor dispatch and ownership fields. Ordinary identifiers and
methods named `free` and `destructor` remain legal. Parser/CLI recognition is
limited to useful removed-form diagnostics; it cannot select an older model.
Missing-free modes, annotations, tracking, suppression, rejected-free explanations,
witnesses and readiness/cache state are gone.

SemanticAnalyzer no longer constructs owned fields and refines fields, returns
and temporary reclamation until convergence. ClosedWorldEffectAnalyzer,
OwnedArrayFieldAnalyzer, OwnedArrayElementAnalyzer, TemporaryBorrowAnalysis,
TemporaryListBorrowAnalysis and FreshArrayElementAnalysis are deleted, together
with detached map/set/copy-result proofs, receiver-publication reclamation proofs
and private managed lowering builders. FieldRetentionAnalysis is deleted rather
than retained under a new name. There is no replacement managed-free fixed point
or must-free/temporary-reclamation scan.

Residual cleanup-dispatch stubs, test-only immortal/borrowed String ownership
result queries and an unproduced permanent origin are also removed. Mixed
control-flow tests retain live phi inputs, unwind reachability, reference effects
and native exception cleanup; those contracts do not require String ownership
certificates.

The retained facts have actual consumers:

| Fact/analysis | Actual purpose and boundary |
| --- | --- |
| Escape and symbolic return summaries | Pool-builder freshness/origins, input retention and foreign confinement. Actual reference effects remain conservative, including unknown/missing bodies. Their real fixed points do not prove managed reclamation. |
| FinalFieldOrigins | Recognize a final instance reference after existing definite-initialization checks; carry actual constructor/getter value origins. No exclusivity or nonretention certificate. |
| Per-function reference identities, array-slot facts and protocol joins | Keep known pool origins through aliases, constant slots and wrappers. Join only common facts; invalidate after unknown/escaped mutation. No heap registry or detached-element proof. |
| Dispatch/value flow and generic specialization | Resolve actual targets and values for lowering and optimization. |
| Initialization/null/exception facts | Definite initialization, final assignment, D051 ordering and existing checks. Managed publication during partial construction is accepted. |
| Pool/native acquisition protocols | Reject known wrong-pool use, cached/publishing builders and invalid native-resource transitions; preserve native acquisition failure cleanup. |
| GcEffects, layouts, roots and bridge carrier/confinement analyses | Conservative MayCollect, exact nonmoving statepoints, suspended/foreign roots, copied-input and borrowed-view boundaries, listener reconciliation and explicit JNI completion. |

The paired pool matrix exposed and fixed an actual dependency: an allocation
returned by a pool builder is not unescaped when its constructor publishes the
receiver. Existing constructor escape summaries now include explicit and implicit
superclass calls. Unknown summaries cannot manufacture fresh/nonretaining facts.
This does not prohibit ordinary managed publication or restore its former proof
pass. Same/wrong-pool controls cover aliases, arrays, helpers and final-field
wrappers; quiet/publishing constructors and unknown/cached factories are paired.

Files.walkFileTree no longer rejects retaining managed Path/BasicFileAttributes
values to justify temporary destruction. Its nonretaining override is removed,
so real callback effects flow conservatively. Native directory streams still
close in finally. A native regression retains callback values across collection.
Private empty releaseRenderedString methods and their calls are removed while
preserving rendering evaluation, copy behavior and exceptions.

Legacy bridge admission, permanent/owned/manual projections, rollback and
managed destruction builders are deleted. One managed image/facade path remains.
Handle/registration closure is separate from source resource closure. Snapshot
getters may allocate and collect, but must not publish invocation-owned foreign
carriers. Exact foreign lifetime refusals remain enforced.

The final managed verifier recognizes forbidden legacy call symbols without
retaining obsolete IR records for injection tests. Reduced IR cloning, generic
specialization, emission and final link have no managed-deallocation producer.
Libc frees are confined to the native domains listed in the disposition ledger.

## Runtime, ABI and SDK inputs

Only canonical Memory.collect() reclaims unreachable managed storage,
synchronously. Allocation never collects. No finalizers or compiler-injected
reclamation remain. Exact layouts, root verification and nonmoving statepoints
remain fail closed. Removed semantic descriptor fields become reserved null/false
physical padding, preserving the existing physical layout rather than silently
changing an ABI. The compiler emits only null/false in those slots; no semantic
IR field can request cleanup metadata there. Exact layout/root verification is
unchanged. The C Throwable layout's `owned_message` spelling is only the physical
mirror of the retained causeMessage text field, with no ownership/release effect.

Ordinary executables retain root ABI 2 / owner ABI 1. Managed bridge schema 2 /
objects-gc-v1 retains root ABI 3 / owner ABI 2 and suspended segments. Changed
verified runtime inputs were rebuilt with pinned BDWGC 8.2.12 and
USE_MARK_BYTES=1; old payload identities were not reused:

| Target | New GC configuration SHA-256 | SDK/sysroot identity |
| --- | --- | --- |
| macos-arm64 | `728c45bd8ecedf7d4fc5bb64ffd25edc85bb37115f768cad38a81eb118e0f86b` | Xcode SDK settings `82165b15ee21419d4cffaba8895797391b908ac9499ba70f561f3e1bd16758d6` |
| linux-x86_64 | `27c2bc0d5de40df986063ea05fed548dc6450160b803eaf3dc1b4b344c5b446d` | sysroot record `82573c10a11db164255da8c2d7fe5a254e46db1e8771866b84c6a1f63ad4a070` |

Packaging/loader paths always validate managed metadata. Source/class/archive
production, host assembly, duplicate-target refusal, independent production
images and stale metadata/native-input checks exercise the reduced implementation.
Compiler/runtime content identities continue to prevent assembly of stale
producer inputs. Linux host-pass compilation now selects host GCC/sysroot inputs
explicitly instead of inheriting the SDK Clang's unrelated default sysroot.

## Focused verification

All compiler/native/JVM children use the 16 GiB process-group RSS guard and a
60-second bound. The compact manifest records exact selections/commands, result
and log hashes, sampled RSS, SDK identities and ignored evidence locations.
Focused selections were revisited after shared-analysis and lowering changes;
no unfiltered suite or equivalent traversal of all groups ran.

- The primary test script builds the Java 21 bootstrap compiler and standard
  library/testing artifacts. Removed forms/options are rejected in ordinary and
  bridge entry paths; ordinary free/destructor declarations and calls succeed.
  The bounded 48-class compiler check verifies obsolete analysis classes are
  absent and SemanticAnalyzer bytecode lacks the old proof/refinement calls.
  This establishes removed work, not a compiler capacity or throughput result.
- Final shared-analysis selections cover all four GC5 compiler contracts, GC6
  object/callback/carrier/suspended-segment contracts, eight shared graph source/
  class/archive families, partial publication versus final initialization errors,
  copied/borrowed foreign inputs, collecting snapshot getters versus publication,
  pool provenance, stale generations and the bounded compiler check. Additional
  focused selections retain construction dispatch, file-tree depth/link/cleanup,
  pool safety and artifact reconstruction.
- GC5 integrated, resource, pool, OOM and no-automatic-collection controls pass at
  O0/O3. Native GC7 programs cover cycles, shared arrays, partial construction,
  capture reassignment, pending values, assignment/failure ordering, explicit
  close and pool reuse. Pool/collection/benchmark library tests and current
  reclamation/defer examples pass. Managed visitor retention and constructor
  virtual-dispatch publication pass with explicit collection.
- Actual JVM checks cover GC6 objects with JDK 23 critical calls and forced JNI,
  callbacks with checked JNI and batch fallback, explicit JNI carrier completion,
  suspended segments, and independent production A/B images/private exports.
  Object and callback checks were rerun after the constructor-summary correction.
  Source/class/archive scalar packages and a host-assembled package run; duplicate
  targets fail. All 32 real load-time metadata cases and 16 native-input checks
  pass, including missing/stale schema, projection, ABI and verified SDK inputs.
- Migrated micro, retention, listener and OrderBook workloads pass bounded quick
  checks. OrderBook retains its eight-operation pooling workload, exact demo,
  per-operation/batch/latency paths and zero warmed native allocations. Generic
  getter/setter examples run their quick controls. Bridge basics and the project's
  compile/link/test scripts pass. Baseline Java workloads remain independent.
- TM4E generation/verification, Eclipse build/output-parser/template checks,
  stdio LSP diagnostics/symbols, generated IR catalog exactness and checked-in
  compiler source syntax pass. The latter is repository-consumer validation,
  never a self-hosted compiler build. A separate bounded syntax audit parsed
  428 changed maintained Ironwood consumers without errors.
- Current IronDocs/pooling/benchmark documentation programs and HelloEclipse
  tests run. Collection-rendering retains 21 text families; copied-map pool OOM
  phases retain functional/failure coverage. Immediate live-count premises are
  replaced only at deliberate completed phases; allocation expectations remain.
- O3 call/pool controls retain identical normalized defer/finally application
  code, zero warmed allocations/live delta and two alternating 32-million-operation
  rounds. The real cold throw optimization now matches call/jump/throw without
  rollback; explicit throw and O0/O3 null behavior pass. Float/double parse
  operands use the declared managed address space. TCP output-field queries
  explicitly cast managed addresses at the noncollecting C boundary; inspected
  O3 code uses address arithmetic and the existing leaf call with no cast helper
  or bookkeeping. Default/custom socket ledgers retain zero repeated-operation
  and source-close managed allocations and explicit descriptor close checks.
- Local translated Linux x86-64 object/callback O3 consumers pass with rebuilt
  SDK inputs. These are local Rosetta/container controls, not new physical-host
  qualification; the final constructor-effect correction was rechecked on macOS
  and in platform-independent compiler selections. Existing Linux qualification
  and all platform gates remain explicit.
- Diff, license, tracked-file, generated-content and historical-hash checks pass.
  Logs, binaries, dependency archives and SDK trees stay ignored.

## Limits and bounded GC8 handoff

Unexecuted coverage includes the full compiler/stdlib suite, exhaustive OOM and
native fault matrices, every changed runnable consumer, all optimization levels
for every fixture, the full JDK/benchmark matrix and release/distribution smoke
beyond the focused changed paths. The subsequent
[native Linux x86-64 follow-up](GC7_GC8_LINUX_X86_64_QUALIFICATION.md) passes
current GC7 compiler/artifact/native consumers and retained GC2-GC6 controls on
physical hardware. It also extends the separately scoped GC8 measurements.
Earlier GC2-GC6 qualification is historical evidence, not relabeled as a complete
rerun. Linux arm64 and arm64e remain unqualified. Unsupported foreign lifetimes
remain refused, and the single-caller/external-serialization contract is unchanged.

GC7 leaves no pending consumer migration or managed-free proof retirement.
Its original verification does not itself accept GC8 capacity/performance.
The subsequent [GC8 report](GC8_IMPLEMENTATION.md) and linked Linux follow-up
record that separate qualification. GC9/GC10, self-hosting and I1/I2 remain
outside these completed stages.
