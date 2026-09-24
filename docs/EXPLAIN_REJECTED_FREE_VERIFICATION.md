<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Explain rejected free: planning verification

Dated findings from the review of the
[implementation plan](EXPLAIN_REJECTED_FREE.md). These records preserve checks,
results, and limitations at the revisions named below; they are not an additional
set of implementation requirements. The plan is authoritative for current
contracts, coverage, and remaining work. Numbered section references below refer
to that plan. The option and explanation collectors remain unimplemented.

## Initial plan

The plan was checked against `dfd3c9be55bec9763bd3dcc71f640c764b56c276`:

- All four negative source examples were compiled individually with the current
  compiler and `--unfreed=off`. Each failed with its documented primary reason
  and primary source location, without producing class output.
- Local document/source links and every existing exact test name in the command
  selections were checked against the repository.
- The added notes and new command-line option remain proposed. Their output,
  performance, and semantic parity have not been implemented or tested.

This was focused documentation validation. No full compiler or native suite was
run, and these results do not mark any implementation milestone complete.

## Diagnostic determinism review, 2026-09-23

The section 3.2 prerequisite was committed in `0bb8933` as a change to the two
diagnostic selections in `lowerFreeOperand`. It leaves ownership maps, snapshot
equality, state transitions, and rejection-category precedence unchanged. The
explanation option and evidence collector are still unimplemented.

The new [FreeDiagnosticTests](../compiler/src/test/java/ironwood/compiler/FreeDiagnosticTests.java)
regression failed against the unchanged compiler: reverse-order slot stores
without a join selected slot 3 instead of the intended lowest slot, 0. After the
selection fix, seven focused tests passed:

- `safe free selects stable blockers across fresh compiler processes`
- `safe free accepts local allocation and ended aliases`
- `safe free rejects live aliases and escaped allocations`
- `safe free accounts for reference-array element aliases`
- `safe free tracks ownership independently across duplicated finally paths`
- `unfreed diagnostics track receiver-retained allocations`
- `data structures retain inserted references for safe-free analysis`

The new regression covers creation/retention-order variants and accepted cleanup
under every unfreed mode, plus identical complete diagnostic output across eight
fresh JVMs. The license audit passed. These checks establish the two repaired
selections, not determinism of every compiler diagnostic or a compilation-time
performance claim. M0 must still audit other candidates and record any further
stabilization separately before explanation-mode implementation.

## Skipped-refinement review, 2026-09-23

The section 5.6 example was reproduced through `bin/ironwoodc --unfreed=off`
using Java 21 on `PATH`. It produced the missing-override error and all three
secondary reclamation errors shown in that example, with no class output. Adding
the annotation compiled cleanly.

The new [FreeAnalysisReadinessTests](../compiler/src/test/java/ironwood/compiler/FreeAnalysisReadinessTests.java)
records the current user-code fallback rejection, accepts the annotated
non-retaining case, rejects a retaining variant, and confirms a later body error
does not lose the refined proof for the safe free. Failed analyses expose no
typed program or LLVM output. It intentionally does not freeze the number or
wording of secondary library errors as a permanent recovery contract.

Four focused tests passed:

- `safe free distinguishes earlier errors from refined dispatch`
- `mandatory @Override enforces override intent in both directions`
- `borrow dispatch uses exact overloads defaults and receiver flow`
- `borrow dispatch rejects retaining and unknown receiver flows`

The license audit and diff whitespace checks passed. This review changes the
plan and baseline tests only. Phase readiness, limited-analysis notes, and the
option itself remain unimplemented; their on/off comparisons belong to M1.
Production analysis and existing diagnostic output are unchanged.

## Rejection-site inventory review, 2026-09-23

Reviewed the emitters and supporting checks listed in section 3.4 against
`0e7990e`. Six supplied source fixtures were compiled independently through
`bin/ironwoodc --unfreed=off` using Java 21. Every fixture failed without producing
class output:

| Fixture | Observed existing diagnostics and primary locations |
| --- | --- |
| `LoopDemo`: allocation before a loop, free in its body | Carried-local error at 6:9 and repeated-free proof error at 7:13. |
| `DeferDemo`: parameter, already-freed local, two aliases registered | Shared eligibility error at 5:20 and 12:20; duplicate deferred free at 20:20. |
| `Holder`: constructor stores a parameter into the field | Uncertain destructor field ownership at 12:14. |
| `Owner`: destructor defers an observer before freeing its field | Pending deferred-call field rejection at 11:14. |
| Owned-elements fixture: constructor stores one object in two array slots | Distinct-fresh-entry rejection at the field declaration, 6:20. |
| Two-pool fixture: checkout from the first, release to the second | Wrong-pool transfer rejection at the argument, 20:23. |

An additional primitive-local `defer free` fixture reproduced the same primary
eligibility wording as the parameter and already-freed cases. It confirms why
the non-reference predicate must be excluded structurally. The primitive generic
specialization guard was verified in source only, not through a CLI reproducer.

This review changes only this plan: the inventory, scope, evidence routing,
milestones, and future verification requirements. No compiler or test behavior
was changed, and no compiler suite was needed. Document/source references,
registered test names, reason strings, and diff whitespace were checked. Notes
and the option remain unimplemented.

## Cleanup-exit review, 2026-09-23

Reviewed cleanup entry routes, exceptional-state merging, and the D090/D091
contracts against `e3c7b90`. The three supplied fixtures were reproduced through
`bin/ironwoodc --unfreed=off` with Java 21: `DupCleanup` produced three identical
rejections at 11:20, `FinallyDup` three at 19:18, and `OneExit` one at 8:20.
All failed without class output. The empty `work` bodies still contribute
exceptional edges in this lowering; the explanation must not claim they are
guaranteed to throw at runtime or assign the merged exception copy to one call.

The new [CleanupDiagnosticTests](../compiler/src/test/java/ironwood/compiler/CleanupDiagnosticTests.java)
preserves these messages, counts, and primary locations. For both deferred and
source-written finally cleanup it checks the 1/2/2/3 count progression when
calls/returns are removed or retained. It also covers the one-return failure,
a mirrored normal-exit-only failure, and accepted controls without publication.
Failed analyses expose no typed program or LLVM output.

Verification passed:

- `rejected cleanup frees preserve per-exit diagnostic multiplicity`
- `safe free tracks ownership independently across duplicated finally paths`
- `deferred free preserves ownership across cleanup predecessors`
- License audit, document/source consistency, and diff whitespace checks.

The first run of the new test exposed a test-only comparison between a `Path`
and a string. After correcting that assertion, only the failed test was rerun;
the two existing tests had passed. This review changes the plan and baseline
tests, not production analysis. Exit-note rendering, note-budget enforcement,
and the option itself remain future implementation work.

## Selected-reason review, 2026-09-23

Reviewed the reason producers and snapshot transfers in section 3.5 against
`945358d`. All four supplied source fixtures were reproduced independently with
`bin/ironwoodc --unfreed=off` on Java 21. `TwoStores` selected the second field;
`EscapeThenMerge` selected the earlier static publication; `MergeThenArray` and
`ArrayThenMerge` selected their respective first uncertainty. Their primary
locations were 11:14, 11:14, 12:14, and 12:14. Each failed with one error and
without class output.

The new [FreeReasonSelectionTests](../compiler/src/test/java/ironwood/compiler/FreeReasonSelectionTests.java)
checks those primary messages and target spans, reversed escape order, escape
after uncertainty, repeated same-field publication, remaining blockers after
the selected one is removed, and accepted controls after all blockers are
removed. Failed analyses expose no typed program or LLVM output.

Three focused tests passed:

- `rejected free preserves escape and uncertainty reason selection`
- `safe free rejects unknown identities and uncertain control flow`
- `safe free accounts for reference-array element aliases`

The license audit, document/source consistency, and diff whitespace checks
passed. This review changes the plan and baseline tests only. Production reason
selection is unchanged. Evidence collection and the proposed first-note
locations are implementation requirements, not verified explanation output.

## Incoming-join-facts review, 2026-09-23

Reviewed `lowerIf`, normal try/catch continuation, exceptional-state merging,
switch flow, and `mergeOwnership`/`mergeFlowOwnership` against `d10023d`. The four
section 5.8 fixtures were reproduced through `bin/ironwoodc --unfreed=off` on
Java 21. Each produced one rejection without class output: `DifferentFields`,
`OneBranch`, and `FieldOrCall` used the if-branch conflict reason at 14:14,
11:14, and 18:14 respectively; `SameField` retained its static-field escape
reason at 13:14.

The new `joinedReasons` group in
[FreeReasonSelectionTests](../compiler/src/test/java/ironwood/compiler/FreeReasonSelectionTests.java)
preserves these primary messages and target spans. It also checks that changing
both branches to store into the same field still rejects, accepts controls with
publication removed, and accepts the later free when the publishing branch
returns before reaching the join. Failed analyses expose no typed program or
LLVM output.

Three focused tests passed:

- `rejected free distinguishes incoming branch facts without changing join reasons`
- `rejected free preserves escape and uncertainty reason selection`
- `safe free rejects unknown identities and uncertain control flow`

The license audit, document/source and proposed-location checks, and diff
whitespace checks passed. This review changes the plan and baseline tests only.
Production joins, snapshot equality, and primary diagnostics are unchanged.
The path labels, aggregate descriptions, and note locations remain proposed
M3 behavior; the option is still unimplemented.

## Deferred-target review, 2026-09-23

Reviewed deferred-call preparation, pending-operand retention, deferred-free
registration, binding-write protection, and cleanup execution against `d9d2658`,
with D168 and `DEFER_PLAN.md` as the language contract. `DeferredFreeAction`
stores a resolved local and spans, not a captured value; its checks and execution
resolve that local in the current ownership environment.

The four supplied fixtures were reproduced through `bin/ironwoodc --unfreed=off`
on Java 21. `CallCapture` compiled and produced class output. The other three
each produced one error without class output: `CallCaptureRejected` at 12:14,
`FreeBinding` at 7:9, and `PendingFree` at 8:14.

The new `deferredTargets` group in
[CleanupDiagnosticTests](../compiler/src/test/java/ironwood/compiler/CleanupDiagnosticTests.java)
preserves those outcomes, primary messages, and locations, plus accepted controls
removing the binding write or early alias free. Failed analyses expose no typed
program or LLVM output. Three focused tests passed:

- `deferred calls capture values while deferred free binds locals`
- `deferred calls retain captures and mandatory ownership proofs`
- `deferred free enforces local syntax and pending binding writes`

The license audit, document/source and proposed-location checks, and diff
whitespace checks passed. This review changes the plan and baseline tests only;
production compiler behavior and deferred-action representations are unchanged.
The option and exact note assertions remain future implementation work.

## Evidence-availability review, 2026-09-23

Reviewed alias binding, invocation arguments, escape/uncertainty producers,
reclamation recording and restore, and field-proof producers/consumers against
`895abad`. Operation spans must be threaded where ownership helpers receive
only operands and reasons. Existing reclamation spans lack path membership.
Field rejection text is mostly absent; its existing map is also consumed by
identity tracking, so new explanation records must remain separate.

All four supplied fixtures were reproduced through `bin/ironwoodc --unfreed=off`
on Java 21. Each produced one error without class output: `TwoPathFree` and
`ReturnedFree` at 11:14, `HolderLocal` at 13:14, and `PairLocal` at 14:14.
Their messages match section 5.10's recorded baselines.

The new [FreeEvidenceBaselineTests](../compiler/src/test/java/ironwood/compiler/FreeEvidenceBaselineTests.java)
preserves those messages and target spans, both field destructor variants, and
accepted controls removing the repeated free or proving field ownership with
appropriate detachment/destructor cleanup. Failed analyses expose no typed
program or LLVM output. Three focused tests passed:

- `rejected free preserves branch reclamation and field proof boundaries`
- `safe free rejects double free and post-free use`
- `private backing arrays are freed only after proven detachment`

License audit, document/source links, registered test names, proposed locations,
and diff whitespace checks passed. Only the plan and baseline tests changed;
production compiler behavior, reclamation recording, reason maps, and ownership
guards remain unchanged. Related-note output and its assertions remain planned.

## Summary-witness lifecycle review, 2026-09-23

Reviewed escape rounds, symbolic-return rounds, final summary transformations,
outer refinement, and effect convergence against `bef49fd`. Section 6.4 now
chooses optional per-analyzer maps, first-discovery immutable dependencies, and
final-instance-only consumption, with no extra semantic run. Symbolic-return
effects and audited borrowing overrides need explicit evidence handling; raw
escape witnesses alone cannot explain every final call effect.

Five CLI reproductions used `bin/ironwoodc --unfreed=off` on Java 21. `Chain`
and `Cycle` each produced one error at 24:14 and 39:14 respectively, without
class output. The safe-cycle control and `Case` compiled with class output.
`Case` plus `OverrideError.iron` produced the missing-annotation error, two
identical cleanup rejections at `Case.iron:33:20`, and two existing library
cleanup errors, without class output.

The new [FreeSummaryEvidenceTests](../compiler/src/test/java/ironwood/compiler/FreeSummaryEvidenceTests.java)
preserves these user-code primaries and target spans, accepts removal of the
retaining store and annotation repair, and rejects actual publication by `use`
after completed refinement. Failed analyses expose no typed program or LLVM
output. Three focused tests passed:

- `rejected free preserves call chains cycles and final borrow refinement`
- `safe free distinguishes earlier errors from refined dispatch`
- `temporary constructor helpers preserve mandatory ownership proofs`

License audit, document/source links, registered test names, proposed note
locations, and diff whitespace checks passed. This review changes the plan and
baseline tests only. No production summary, refinement, or diagnostic behavior
changed. Witness graphs, exact notes, and on/off semantic-pass comparisons are
implementation requirements, not verified features of the current compiler.

## Dependency-compilation review, 2026-09-23

Reviewed `Main`, `SourceSetLoader`, `IronClass`, and `IronJar` against `f242649`.
Ordinary compilation and linking both analyze reconstructed dependency source;
source-path inputs are available only during compilation. Final linking explicitly
rejects source paths and positional source inputs. Section 5.12 records the legal
matrix and the distinct application/library note identities.

The new [FreeDependencyDiagnosticTests](../compiler/src/test/java/ironwood/compiler/FreeDependencyDiagnosticTests.java)
uses the actual compiler and archive CLI entry points on Java 21. It passed the
focused selection `rejected free in dependencies preserves compile and link source locations`:

- Sink compiles independently and packages with `ironjar --create`.
- Compiling Keeper with source-path, class-directory, and archive dependencies
  produces the same single rejection at the correctly loaded Sink 12:14, with
  its source excerpt and no application class output. Loader checks preserve
  the library and application source identities/content separately.
- Keeper also compiles against the earlier compatible Sink implementation.
  Linking those valid application classes against the original Sink classes or
  archive rejects at the dependency's reconstructed source, with no executable
  or requested LLVM output.
- Quiet compiles, links, and runs with exit status zero and no output in all
  three compilation/artifact workflows, including source-path-produced classes.

License audit, document/source links, test registration, exact example and
proposed-note locations, and diff whitespace checks passed. Production loading,
CLI rules, ownership analysis, and diagnostics are unchanged. The related notes
and their option-off/on comparisons remain planned M4/M5 behavior.

## Ownership-contract review, 2026-09-23

Reviewed the section 1 decisions and amendments, dependent-borrow and retaining
edge checks in `FunctionAnalyzer`, and `BorrowDispatchAnalysis` against `ede1053`.
The plan now distinguishes reusable helper ownership, originating-pool checkouts,
and caller-owned children borrowed by wrappers. Dispatch notes describe final
possible targets for the current compilation, with conservative fallback rather
than a claim that a particular runtime call occurs.

The new [FreeOwnershipContractTests](../compiler/src/test/java/ironwood/compiler/FreeOwnershipContractTests.java)
records the section 5.13 rejected primaries and accepted controls, including
closing a wrapper, a second retaining owner, and retaining implementations
excluded or admitted by receiver flow. Four focused tests passed on Java 21:

- `rejected free respects helper pool wrapper and dispatch contracts`
- `borrow dispatch uses exact overloads defaults and receiver flow`
- `unfreed diagnostics track receiver-retained allocations`
- `rejected free in dependencies preserves compile and link source locations`

The first verification attempt found missing SPDX headers in generated sources
from the [dependency-compilation review](#dependency-compilation-review-2026-09-23)
fixture. Its generator now uses the blank line after the package for the header, preserving diagnostic line numbers; loader
content assertions include that header. The rerun passed, including native
dependency controls. A post-generation license audit, exact proposed-note
locations, decision/source links, and diff whitespace checks also passed.

This review changes the plan and tests only. Production ownership and dispatch
behavior are unchanged; owner notes, witness selection, and option-off/on
comparisons remain future implementation requirements.

## Documentation-timing review, 2026-09-23

Reviewed the plan against `17d5c43`, the current `Diagnostic` record, language
server translation, IronDocs formatting, and the affected guides. Commits
`30e4e66` and `dfd3c9b` delivered CLI controls with compiler documentation and
D182/D183; D168 also demonstrates a decision whose status tracks milestones.

This review chooses a new decision in M1 alongside the implemented API, then
updates that same entry through M5. Section 6.6 assigns practical guidance to
`MEMORY_MANAGEMENT.md`, detailed explanation limits to `MEMORY.md`, and gives
explicit M1 requirements for compiler docs, README, help, and focused test
guidance. M2 to M4 update coverage as it lands; M5 audits existing documentation.
The proposed coverage paragraph preserves M1's already-required boundary and
limited-analysis notes instead of promising silence for every unsupported chain.

Verification: checked local links, milestone consistency, existing API consumers,
historical commit file lists, and `git diff --check`. This is a plan-only change;
no compiler suite or license audit was needed. The option and related notes
remain unimplemented, and current user guides do not advertise them as available.

## CLI help and usage-error review, 2026-09-23

Reviewed `Main.run`, `CommandLine.parse`, `usage`, and `printUsage` against
`d60f9f8`. Direct invocations of the existing compiler jar on Java 21 confirmed
that `--help` returns 2 with usage on stderr and empty stdout; the unimplemented
`--explain-rejected-free=true` currently returns 2 with an unknown-option error
followed by usage, also entirely on stderr.

Section 2.1 now specifies the compact shared-options sentence and a targeted
no-value error for every equals form. M1 and section 8.1 require parser tests
for exact messages, streams, status, empty/arbitrary values, unknown spellings,
and bare/repeated flags in compilation and linking. Existing help behavior is
preserved. This review changes only the plan; the proposed parser and help
changes remain M1 work. Local links, wording consistency, and `git diff --check`
passed; no compiler suite or license audit was needed for this document edit.

## Per-change baseline review, 2026-09-23

Reviewed `scripts/test.sh`, `scripts/build.sh`, the existing fresh-JVM diagnostic
regression, and this plan against `e32b86a`. The test script rebuilds and loads
the current compiler; subprocess tests can exercise it again, but an older
revision needs an explicit independent build. This review did not reproduce
the historical deferred-cleanup IR line counts, which are not needed to establish
that unrelated compiler changes can invalidate a fixed M0 output comparison.

M1 now delivers a per-change comparison script following section 8.3; registered
tests retain same-build off/on parity and exact note expectations. M0 findings
stay attributed to their revisions. The procedure preserves exit statuses and
failure evidence, limits path normalization, distinguishes pre-commit `HEAD`
from post-commit parent selection, and avoids text-based note stripping.
Documentation-only changes require consistency checks rather than rebuilds.

Local links, milestone/comparison consistency, and `git diff --check` passed.
This is a plan-only change; no comparison script or compiler behavior was added,
and no compiler builds, suites, or historical IR comparisons were run.

## Storage-budget timing review, 2026-09-23

Reviewed snapshot creation/restoration in `FunctionAnalyzer`, summary rounds in
`EscapeSummaryAnalyzer`, and outer refinement in `SemanticAnalyzer` against
`c894d81`. Snapshot creation copies entries for present allocations; future
evidence adds cost dependent on sharing and simultaneous retention. Repeated
summary construction likewise requires measuring live and cumulative costs.

The plan now fixes output caps separately from provisional storage limits. M0
measures workload shape, M1 enforces initial budgets, M3/M4 measure the implemented
collector/witnesses and tune storage, and M5 records final values and evidence.
Section 9 specifies nested-tree, sequential-join, snapshot, and witness stress
families, distinguishing source size and peak retention from allocation churn.

Local links, budget/milestone consistency, and `git diff --check` passed. This
review changes only the plan. No instrumentation, collector, stress fixtures,
or memory measurements were implemented or run; those remain milestone work.

## Artifact-parity review, 2026-09-23

Reviewed `IronClass.write`, `IronJar.write`, `NativeBackend` trace-section mapping,
`OptimizationReportTests`, and D132/D183 against `63f3b81`. Both ZIP writers set
entry times to zero; native tests already distinguish probe-order variation
from instruction/relocation/unwind changes and execute unmodified binaries.

Using the existing Java 21 compiler, compiled `examples/deferredcleanup` twice
from identical source paths into different scratch directories under Tokyo and
Los Angeles time zones, then packaged each class directory. All six corresponding
class files and the archives were byte-identical. Scratch files were removed.
This checks existing artifact determinism, not the unimplemented option's parity.

Removed timestamp allowances throughout the plan and added section 8.4's exact
class/archive/IR checks and native comparison policy. Section 9.1 records the
reported macOS `llc` issue separately; the isolated native reproduction and Linux
variability were not tested in this review. Local links, comparison consistency,
and `git diff --check` passed. No production code or regression tests changed.

## Disabled construction and bundled-source scope review, 2026-09-23

Reviewed `withUnfreedChecks`, ownership snapshots, `CompilerPipeline` source
selection, and `SemanticAnalyzer.lowerFunctions` against `65b7680`. The existing
nullable tracker is a useful construction pattern, but its input-source filter
must not control explanations. Collector lifecycle also depends on phase
readiness; a null collector must not suppress reduced-mode boundary notes.

The new [FreeBundledSourceTests](../compiler/src/test/java/ironwood/compiler/FreeBundledSourceTests.java)
confirms a retaining `KeepingWriter.write` override produces exactly the bundled
`Writer.scalar` destructor error with no program/LLVM output under all three
unfreed modes. Removing the static store accepts under all three modes without
diagnostics. The primary retains the bundled source identity and destructor span.

Two focused tests passed on Java 21:

- `rejected free in bundled Writer follows retaining user overrides`
- `safe free distinguishes earlier errors from refined dispatch`

License audit, registered test names, local links, proposed note locations, and
`git diff --check` passed. The plan now requires guard/producer review plus
lifecycle tests for disabled construction, keeping profiling for measured costs.
These tests establish current rejection/acceptance only; collector absence and
enabled notes remain future M1/M4 checks. Production compiler code is unchanged.

## Diagnostic-consumer review, 2026-09-23

Reviewed `CompilerOutputParser`, the Eclipse builder command, language-server
translation/URI conversion, IronDoc reporting, and `DiagnosticFormatter` against
`6acd1ae`. Eclipse and the language server do not currently request explanations;
IronDoc does not run ownership analysis. The parser finalizes an error at its
first location, so the plan now requires located primaries and complete primary
blocks before notes, with exact no-note formatting compatibility.

Extended the existing standalone `VerifyCompilerOutput` with proposed located
and unlocated note fixtures, cross-file locations, LF/CRLF, and neighboring
unlocated errors. On Java 21 it passed its real-compiler diagnostic check and
all new primary-preservation assertions, without Eclipse or language-server
packaging. M1 must still connect these assertions to real note-aware formatter
output; the synthetic fixture does not implement or validate that future renderer.

Recorded unlocated/client-fallback note presentation and editor-openable archive
URIs as separate future IDE questions, including the existing dependency-primary
URI limitation. License audit, local links, consistency checks, and
`git diff --check` passed. No production parser, IDE, or compiler behavior changed.

## Loop and destructor regression selection review, 2026-09-23

Reviewed the registered loop, cleanup, field-factory, containment, and destructor
receiver fixtures against `4853eab`, following the pre-change review in
`POOL_RELEASE_HELPER_REGRESSION.md`. Section 8.2 now assigns exact tests and
expected outcomes to M3/M4. The deferred-call ownership test supplies the direct
pending-call destructor case; factory and containment tests cover uncertain
field proofs, not pending-call note locations.

Added `loop back-edge rejections preserve both primary diagnostics` to pin
`LoopDemo`'s two errors before M3 adds evidence: exact text, source, start
locations, severity, count, and order, with no program/LLVM output. Immediate
break and fresh per-iteration allocation controls compile under `--unfreed=off`.

Six focused tests passed on Java 21:

- `loop back-edge rejections preserve both primary diagnostics`
- `finally transfers preserve ownership at destinations and loop back edges`
- `literal-true loops preserve reclamation proofs`
- `owned buffer fields require a fresh unescaped factory result`
- `data structure builder cleanup requires ordered containment`
- `proven destructor receivers preserve mandatory safety`

The license audit passed within `scripts/test.sh`. Checked all 39 distinct
section 8.2 selections against the built runner's `--list` output; an unknown
selection returned status 2 with the documented error. Local links, consistency
checks, and `git diff --check` passed. Production compiler code is unchanged;
option-on parity and exact explanation notes remain future milestone work.

## Output-format convention review, 2026-09-23

Reviewed `DiagnosticFormatter`, `SourceSpan`, parser block/condition spans, the
existing fragment-only formatter test, and Eclipse source-echo parsing against
`fec4047`. The current formatter uses per-location gutters, a first-line,
single-caret fallback for multiline spans, and platform separators. Parsed blocks end
at the closing brace when present, but recovery and synthetic cleanup contexts
do not justify guessing that boundary.

Section 2.2 now fixes those renderer conventions before golden tests, requires
precise supported note spans, and defines CRLF-to-LF golden normalization without
other whitespace changes. M1 and section 8.1 require full-output tests; later
producers must verify multiline operands, branch conditions, and cleanup exits.
Section 6.6 carries the conventions into the M1 decision record.

Checked section 5's complete excerpts against the per-location gutter/caret
rules, local links, text-policy constraints, and `git diff --check`. This review
changes only the plan; no compiler, parser, or test behavior changed, and no
compiler suite or license audit was needed.

## Milestone execution review, 2026-09-23

Reviewed the plan's contracts, evidence collection, diagnostics, integration,
and verification requirements, existing baseline commits, and the required
pre-change review against `3d41fbf`. M1, M3, and M4 had accumulated several
independently risky changes without internal stop points.
The blanket pending status also failed to credit committed preparation.

Section 7 now retains M0 through M5 as coverage milestones while defining
ordered, independently verified checkpoints. It credits existing M0 work and
keeps measurement/schema gaps open; puts the comparison harness before API and
analysis changes; separates eligibility, local state, joins, cleanup, loops,
summary lifetimes, field proofs, and the later element validator; and requires
storage measurements before moving beyond control-flow and whole-program work.

The first public CLI delivery follows all M1 gates. The shared API decision and
documentation instead ship at M1b, when that architecture changes; M1e updates
the same decision and delivers user-facing CLI guidance. This refines section
6.6's former single-commit M1 packaging without delaying documentation or claiming
the option exists in intermediate commits.

Checked checkpoint dependencies and coverage against the existing acceptance
bullets and review requirements, plus local links, text policy, and
`git diff --check`. Only this plan changed. No implementation milestone was
started or completed, and no compiler suite or license audit was needed.

## Bundled Writer receiver-fallback review, 2026-09-23

Reviewed `BorrowDispatchAnalysis`, its handoff from `SemanticAnalyzer`, bound
targets and combined summaries in `EscapeSummaryAnalyzer`, the field analyzer's
attached-argument rejection, and bundled `Writer`/`StringWriter` against `4137fd8`.
The dispatch analysis visits all lowered bodies and applies its type-compatible
fallback when a call has no observed targets, independently of entry-point
presence. No-entry-point seeding is a distinct source of unknown receiver inputs.
`StringWriter.write(int)` has its own body and does not call `Writer.writeScalar`.

Removed section 5.14's missing-entry-point explanation. M4c now requires the
entry-point matrix and call-site fallback provenance; it may not infer an actual
call to the retaining override or guess an uncalled method as the cause. Exact
final witness assertions remain implementation work, not a claim established
by the current primary-only regression.

Extended `rejected free in bundled Writer follows retaining user overrides`
with no main, empty main, and StringWriter-only main, retaining/non-retaining
variants, and the StringWriter main without the subclass. All 21 analyses passed
on Java 21 across the three unfreed modes: nine rejections preserve the single
bundled destructor primary and no program/LLVM; twelve controls accept without
diagnostics. The focused `scripts/test.sh` invocation, its license audit, local
links, text-policy checks, and `git diff --check` passed. Production compiler and
standard-library code are unchanged.

## Evidence-budget isolation review, 2026-09-23

Reviewed the budget, snapshot, analyzer-lifetime, milestone, and stress-test
requirements against `ffeb308`, including `EscapeSummaryAnalyzer`'s traversal
of all supplied types and refinement rounds. One routine invocation quota could
let earlier unrelated facts consume space needed by the rejected-free chain.

Sections 2.3, 6.2, 6.4, and 9 now make method/fact limits primary, retain bounded
local structures and version accounting, and reserve the invocation limit for
an explicitly reported emergency stop. Retired storage does not consume a
permanent cumulative quota. M4a/M4e distinguish local exhaustion, isolation,
and the aggregate stop; M5 records their values and observed events separately.

The planned M4b/M4e import test keeps the user's locations and relevant proof
facts fixed, verifies that the added library really generates witnesses, and
requires identical notes below the stop even when unrelated methods exhaust
their own caps. A separate forced-stop case checks reporting and safety parity.
The collector is unimplemented, so no enabled-memory or note-stability result is
claimed yet. Local links, budget terminology, text policy, and `git diff --check`
were checked; only the plan changed, with no compiler suite or license audit.

## Test-observation seam review, 2026-09-23

Reviewed `SemanticAnalyzer` constructors, refinement, and `lowerFunctions`,
`CompilerPipeline` analyzer creation, and escape/symbolic-return/effect rounds
against `2784fd4`. The loop-local pass index and short-lived function analyzers
cannot be inspected by tests through today's public pipeline. Package-local
access alone would not establish the required lifecycle observations.

Section 6.7 now permits a nullable package-private observer and narrow test
factory, naming their injection path, callback sites, actual-field observations,
round-count semantics, and null-default guards. Tests can use real bundled-source
preparation without a public debug API or duplicated loader. M1c introduces the
seam; M1d/M3/M4 extend it alongside collectors and witnesses. Normal entry points
and cost measurements remain observer-free, and test records cannot retain
analysis graphs or influence proof decisions.

The plan now requires non-vacuous phase assertions, observed/null-observer parity,
and final-iteration versus analyzer-rebuild distinctions. Updated the telemetry
prohibition to allow these dormant hooks explicitly. Local links, checkpoint and
observation terminology, text policy, and `git diff --check` were checked. This
is a plan-only change; no observer or compiler behavior is implemented yet.

## Unspecified-order collection review, 2026-09-23

Reviewed `OwnershipSnapshot`'s immutable map/set copies and
`EscapeSummaryAnalyzer`'s retained-parameter iteration, environment copies/joins,
and immutable summary-origin sets against `17b1938`. Identity maps are only one
source of unspecified iteration; insertion-order containers can also inherit an
arbitrary order when restored from those copies.

Section 4 now covers all unspecified-order collections, explicit stable keys,
bounded representative selection before truncation, and separation from semantic
traversal. Sections 6.3/6.4 apply this to restored join evidence and M4 discovery
ties while preserving causal dependencies and the actual first derivation.
Section 8.1 requires permuted-collection, truncation, and fresh-JVM checks;
renumbering internal ordinals alone is not an observable explanation change.

Checked ordering/causality consistency, local links, checkpoint identities, text
policy, and `git diff --check`. This changes only the plan; collection-order tests
for explanation evidence remain unimplemented, and no compiler suite or license
audit was needed.

## Predecessor-free catch review, 2026-09-23

Traced `lowerTry`, `analyzeDeadCatch`, and return cleanup against `02817d4`.
Compiled section 8.1's `DeadCatchCleanup` and its direct-free variant with Java 21
and `--unfreed=off`: each produced exactly one static-field escape rejection,
at 16:18 and 14:18 respectively, with no class output. Removing the publication
accepted both variants. Temporary fixture directories were removed.

The plan now distinguishes this analysis origin from an incoming exception path.
The qualifier, observer assertions, and registered baseline remain future M0a/M3c
work; these four CLI checks validate current behavior only. No compiler code or
registered test changed.

## M0a pre-change selection, 2026-09-23

Base revision: `e5741c8`. The pending `OwnedArrayElementAnalyzer.Checker`
change concerns only which already-invalid recorded object supplies its first
second-pass primary. D104, the accepted creation-array cleanup contract, the
first-pass rejection priority, and artifact suppression are affected consumers.
The intended candidate order is first successful recording store in the existing
instruction traversal. No proof predicate or ownership state may change.

The focused rejected fixture records two fresh objects, then publishes one to a
static field and stores the other in an unrelated array. Reverse recording order
independently of allocation and offending-operation order. Single-object field
and other-array failures pin within-object precedence; a repeated store pins the
earlier pass. An otherwise identical constructor without either invalid use is
the accepted control. The two-reason fixture produced both field and other-array
primaries across fresh Java 21 compiler processes at this base, with the same
field-declaration primary span. Compare status, full primary blocks, and output
absence across unfreed modes. Select the existing `creation-array cleanup proves
distinct fresh elements` test and register a new focused competing-reason test;
run fresh compiler JVMs as well as in-process checks. M0 changes use the manual
section 8.3 comparison with isolated base and candidate builds for the accepted
control and representative rejection, allowing only the intentional selected
reason change. Run `git diff --check` and the license audit through `test.sh`.

The focused prerequisite uses `LinkedHashMap` only for `recorded`, preserving
`putIfAbsent`, operand equality, all predicates, and both validation passes.
`creation-array cleanup proves distinct fresh elements` passed. The new
`creation-array competing second-pass diagnostics follow first store` passed
in all three unfreed modes with eight fresh JVMs for each recording order. Its
first run found a missing-free error in the fixture's temporary array under
`--unfreed=error`; freeing that temporary array fixed the fixture, and only the
failing selection was rerun. The registered accepted control produced a program
and LLVM; rejected cases produced neither.

For the manual M0 comparison, `e5741c8` was exported with `git archive` and
built independently under Java 21. `StandardLibrary.discover().locate` for
Object, String, System, and PrintStream resolved to each build's own freshly
built archive. Both archive SHA-256 values were
`2d293bf63442e383b1efc6015ffe83f147fd5507a58b68895c3ebdafdb904d47`.
The safe fixture compiled to three byte-identical `.ironclass` files, emitted
byte-identical linked LLVM, and both native programs exited 0. Known output
directory roots were the sole stdout substitution. For the two rejected
recording orders, both builds exited 1 and emitted no files. When the field
failure was recorded first, the base selected the other-array reason and the
candidate selected the field reason; every remaining primary-block line was
identical. When the array failure was recorded first, both selected that reason
with identical complete diagnostics. No proof, class, LLVM, or native-output
difference was observed in this selection. The exact comparison inputs and raw
logs are in `/tmp/ironwood-m0a-parity.ijSnJ8` for this local run.

## M0a reconciliation and completion, 2026-09-23

Reviewed current source at `a1061a0` after the separate owned-element fix
`e3860ef` and catch-baseline addition `a1061a0`. The earlier mixed-owner and
array-slot stabilization and eight-process checks are already committed in
`0bb8933`; they were credited, not rerun. The existing primary-only selections
for cleanup, loop, selected reasons, joins, deferred targets, summary chains,
dependency compile/link, helper/pool/wrapper/dispatch, bundled sources, and IDE
parser behavior are recorded above and in section 8.2. No option or collector
is claimed by these baselines.

The new registered `predecessor-free catch preserves direct and cleanup
primaries` selection passed its four Java 21 `--unfreed=off` cases. It pins one
static-field error at 16:18 in the finally copy after a return from the checked
catch, one at 14:18 for a direct free in that catch, and accepted controls with
the publication removed. Rejected cases expose neither a typed program nor
LLVM. The earlier CLI observations in the predecessor-free catch review were
not repeated as another implementation claim. The new test does not assert an
exception predecessor, since `analyzeDeadCatch` enters with none recorded.

Current producer/consumer reconciliation:

| Path | Current fact and remaining explanation work |
| --- | --- |
| Ordinary and deferred free, destructor field free, both loop checks, and late owned-element validation | The section 3.4 in-scope emitters still choose their existing primaries. The name/type branches, wrong-pool transfer, pending-binding write, standalone use-after-free, primitive-specialization guard, and parser errors remain explicit exclusions. `prepareDeferredFree` still short-circuits reference, identity, borrow, and maybe-freed checks in that order. |
| Local bindings and ownership saves | Declaration, statement assignment, and assignment-expression writers update the environment without binding-source history. `snapshotOwnership` saves state/reason; `restoreOwnership` reinstates them; `mergeOwnership` copies equal facts or writes a general conflict. `mergeExceptionalEnvironment` has common-operand and phi routes. M1d needs an optional overlay for both routes, including absence and replacement. |
| Reason producers | `escape` accepts the latest non-freed escape, `blockReclamation` accepts only the first ACTIVE uncertainty, and `makeUncertain` skips owned-field origins. Direct constructor escapes, `markEscaped` propagation, pool conflict, inexact array slots, mixed-reference joins, and direct join/restore assignments remain distinct producer routes. Evidence must update only with the accepted selected reason, including identical text at different stores. |
| Retention and source context | `addRetainedBorrow`, `recordReceiverBorrow`, array stores, and `markEscaped` have allocation identities but no operation source argument. Deferred calls keep evaluated operands without per-argument expression spans. Local source bindings, publication operations, owner acquisition, and role spans are still missing; sections 3 and 6.2 assign them to M1d/M2/M3. |
| Cleanup entries | `lowerDeferredTail`, `lowerTry`, catch completion, return, transfer, yield, and pending-exception routes reach `emitCleanupAction` with separate ownership states. They do not retain diagnostic-only exit identities. `analyzeDeadCatch` is the separate predecessor-free checking route; it needs the direct-catch qualifier as well as qualifiers through any cleanup it enters. M3c remains responsible. |
| Summaries and late proofs | `SemanticAnalyzer` retains the last selected `escapeSummaries` and `ownedArrayFields` when refinement stabilizes, then performs final lowering and owned-element validation. Escape rounds, symbolic-return enrichment, and audited-borrow transformations precede final call effects. Provisional instances cannot supply final notes. `sameProofsAs`, summary equality, temporary-borrow equality, and effect equality must stay evidence-free. M4 owns supported summary and field witnesses. |

The remaining exact pairs for explanation coverage are: M1c's completed versus
skipped refinement, each eligible ownership predicate versus its adjacent
excluded name/type or non-free predicate, and direct versus cleanup catch
routes; M1d's live alias versus reassigned alias at each of the three writers,
common-operand versus phi merge, and earlier free versus fresh replacement;
M2a's field/static/array publication versus nonpublication, repeated same-text
stores, selected uncertainty versus ignored updates, and restored sibling facts;
M2b's retaining versus proven non-retaining calls, constructor receiver/argument
roles, multiline operand spans, and independent-fresh versus published results;
M2c's live versus terminated container/wrapper loans, multiple owners, dependent
helper versus caller-owned child, and reassigned owner names; M2d's direct and
helper same-pool return versus wrong/unknown-pool release and independent free.
These are future enabled-note checks, not missing safety baselines to rerun now.

`OwnershipSnapshot` immutable copies, identity sets/maps, summary origin sets,
and recursive retention propagation still contain unspecified-order iteration.
The first recorded owned-element candidate is now ordered independently of its
`HashMap` lookups. No further primary variant was observed in the existing
fresh-process baselines, but those repetitions do not prove global diagnostic
determinism. Future evidence must select by the section 4 stable keys before
truncation; any demonstrated semantic first-reason variation needs a separately
reviewed stabilization fix before exact-message parity for that case. This is
the remaining ordering audit gate, not authorization to reorder proof traversal.

M0a is complete at `e3860ef` and `a1061a0`, with the plan/record closeout in
the following documentation commit. `git diff --check` and the focused license
audit passed for both source/test changes. M0b measurement and numeric budgets
remain open.

## M0b workload measurement and provisional storage design, 2026-09-23

Base: `3df8ba59968a7caae0f8776da0ac8fe0229abfa4` on Darwin arm64 with
Oracle GraalVM Java 21.0.1 and pinned LLVM 23.1.0. The unmodified compiler jar
SHA-256 was `2b70f11ae98b45fc9a58726f73796043989f60dba5f4446717b62fd94506bd42`.
Each timed invocation used its own JVM, output directory, and explicit
`IRONWOOD_STDLIB_HOME`; there was one warm-up and three measured runs in
alternating workload order. `/usr/bin/time -l` measured direct compiler
processes, excluding `scripts/test.sh` rebuild time and native linking. The
table reports median wall time, its three-run range, median peak resident size,
and largest observed peak. These are dated baselines, not future thresholds.

| Workload | Outcome | Wall median (range), seconds | Peak RSS median (largest), MiB |
| --- | --- | ---: | ---: |
| Small successful source | accepted, one class | 1.43 (1.37 to 1.48) | 481.2 (505.0) |
| OrderBook source compilation | accepted, eight classes | 1.90 (1.88 to 1.91) | 596.2 (598.0) |
| Standard library source compilation | accepted, 247 classes | 6.20 (6.16 to 6.31) | 1095.4 (1293.7) |
| Mixed owner and array-slot rejection | two errors, no classes | 1.13 (1.08 to 1.14) | 474.7 (475.9) |
| Five-level nested branch publication | one error, no classes | 1.16 (1.10 to 1.16) | 476.5 (479.1) |
| Duplicated deferred cleanup | three errors, no classes | 1.13 (1.11 to 1.18) | 478.0 (485.8) |
| Recursive call cycle | one error, no classes | 1.16 (1.12 to 1.16) | 473.1 (474.3) |

Inputs were the current standard-library sources, OrderBook's Main, Bench, and
LatencyBench through its source path, a one-class accepted source, and the
registered `FreeDiagnosticTests` mixed/slot, `CleanupDiagnosticTests` defer,
and `FreeSummaryEvidenceTests` Chain/Cycle source bodies. The nested generator
made a balanced two- or five-level `if` tree, one fresh array in the method,
one distinct static destination at each leaf, and a final `free`; a control
replaced each publication with a branch-local primitive declaration. The
five-level input has 32 leaves. Temporary source SHA-256 values were Small
`4c327b05664f1cf7db3a9a2d3e6330244b838e683ada14e4d273f787918adb87`,
MixedSlots `f730931cc61f812993883408a9c299c82a7b03a927cef261f5284ac85bf30ca8`,
Nested5 `7b535b177d52d1829f5dcf1dc7869e0dcf1da3fb8eb53f4e96d02117c1586940`,
DupCleanup `e4075642f25088af879ce4a35799dd58e0d83662aafe174e83998168f6a12f66`,
and Cycle `6892f907d35d8290b977b49a06313d25e39ed6ed23fc8314435ef7160567539c`.
The two-level and five-level rejected nested inputs, accepted five-level
control, Chain, and other cases were also compiled in the shape run. Temporary
inputs and raw timing outputs are under `/tmp/ironwood-m0b-inputs` and
`/tmp/ironwood-m0b-timing` on this machine; they are not production files.

The [temporary probe patch](EXPLAIN_REJECTED_FREE_M0B_PROBE.patch) applies to a
`git archive` of that base and remains outside committed compiler source. Its
SHA-256 is `a605e81f28c44ac235ef47b8227d8b261d89b9fe80dd34b79322149ea12d4819`;
`patch --dry-run -p1` succeeded on a fresh export. It counts allocation
registrations and present identities, ownership snapshot saves/entries, normal
and exceptional joins, immediate predecessors, cleanup action copies, summary
analyzer builds/escape rounds, symbolic-return rounds, and outer refinement
passes. A weak-reference sample with forced GC every 100 snapshot saves records
observed simultaneously reachable snapshots and their entries. These sampled
peaks are lower bounds, not exact lifetime maxima; the probe's own references
and forced collections make its runs unsuitable for timing. `maxSummaryFacts`
in its raw output is the number of registered callable summary-map entries,
not the number of distinct effect facts. Analyzer and save counts include
provisional and final work, not just the last selected analysis. The instrumented
and unmodified builds generated the same standard-library archive bytes,
produced the same selected primary blocks for the compared rejection inputs,
and produced identical small accepted `.ironclass` bytes.

| Shape | Small | OrderBook | Standard library | Nested5 | DupCleanup |
| --- | ---: | ---: | ---: | ---: | ---: |
| Maximum present allocations in one method | 4 | 7 | 15 | 4 | 4 |
| Snapshot saves, all analyzer instances | 6,692 | 8,588 | 38,727 | 7,002 | 6,714 |
| Total allocation entries copied into saves | 2,470 | 4,400 | 33,224 | 2,780 | 2,492 |
| Largest single snapshot, entries | 4 | 5 | 15 | 4 | 4 |
| Sampled reachable snapshots / entries | 33 / 43 | 65 / 114 | 103 / 243 | 30 / 30 | 25 / 25 |
| Ownership joins / largest immediate predecessor count | 1,648 / 46 | 2,060 / 52 | 8,931 / 80 | 1,710 / 46 | 1,652 / 46 |
| Cleanup copies | 124 | 156 | 691 | 124 | 130 |
| Summary builds / escape rounds / symbolic rounds / outer passes | 5 / 18 / 23 / 2 | 5 / 23 / 30 / 2 | 5 / 20 / 20 / 2 | 5 / 18 / 23 / 2 | 5 / 18 / 23 / 2 |

The largest observed method-level save totals across its two lowerings were
456 in `Files.walkEntry`, 340 in `String.formatFixed`, and 310 in
`Nested.check` at depth five. `Files.walkEntry` also produced 48 cleanup
copies across those lowerings; `DupCleanup.example` produced six. The largest
standard-library present set was 15 and the largest single snapshot had 15
entries. The baseline compiler analyzes bundled methods even for the small
program, so whole-invocation totals cannot be attributed to its user method.
The sampled live-snapshot result for Nested5 is lower than Nested2's, which
illustrates the sampling limitation; no exact peak or collector-memory claim
is inferred from those values.

An every-save forced-GC cross-check completed for Nested5 (49 reachable
snapshots / 60 entries), DupCleanup (49 / 60), and OrderBook (74 / 155), with
the same acceptance and primary outcomes. The standard-library every-save
attempt was stopped after more than 14 minutes because collecting at each of
the 38,727 saves was too costly. Its 100-save observation of 103 / 243 remains a
sampled lower bound, not an exact peak. The every-save run is excluded from
the uninstrumented timing baseline. M1d must measure the actual collector and
its live accounting; these M0b observations only set provisional limits.

Provisional evidence accounting uses one **retained unit** for each event node,
relationship or dependency edge, map association, snapshot reference, path-label
segment, retained summary version, deduplication key, and root. A shared immutable
node is charged to its producing scope once; every holder pays for its edge or
reference. These counts bound retained structures, not bytes. Transient candidate
selection must also stay within the corresponding local cap, rather than building
an unbounded list before choosing survivors. The first implementation measures
actual heap and cumulative allocation in M1d; M3/M4 revisit branch and summary
storage, and M5 publishes final limits.

| Provisional cap | Value and lifetime |
| --- | --- |
| Function-local evidence | 4,096 live units per callable across its current evidence and saved states. Within that, at most 2,048 snapshot associations and 512 path-label units. Retire unreachable snapshots and their holder charges; do not count all historical saves as still live. |
| Local alternatives | At most 128 retained representatives at one join and 64 event/alternative units for one allocation's selected-reason context, both charged to the function cap. Select survivors by section 4's stable keys while collecting. |
| Summary witness | 64 live units per callable/effect/role fact and 2,048 live units per callable across summary phases and simultaneously retained analyzer versions. Immutable dependency versions consume units until their roots retire. Rebuilding an analyzer does not reset charges on still-live versions. |
| Field or element checker | 1,024 live units per field/checker scope, including operation associations and the selected failed predicate. |
| Invocation emergency stop | 1,048,576 live units across all current roots, methods, facts, and checkers. This is a separate latched safety stop, never an ordinary first-come allowance divided among methods. Retired roots release aggregate charges; the stop itself remains latched for the invocation. |

The schema keeps compiler identity keys and reused `SourceFile`/`SourceSpan`
references: immutable allocation origins and events, current local-binding
associations, owner/child relations, an evidence pointer for each accepted
`blockingReason` update, and copy-on-write snapshot associations outside proof
records. Summary instances own versioned fact witnesses keyed by callable,
effect, and operand role; only the final selected instance may explain a final
effect. Duplicated cleanup copies own separate path/exit associations. At a
local cap, replace the affected association with a bounded omission marker and
continue other scopes. At the invocation stop, cease detailed collection and
use the distinct stop boundary. Neither path may reuse a stale cause or change
proof, convergence, primary diagnostics, or output artifacts. Reserve bounded
per-primary exit/boundary note space under the fixed eight-note and four-hop
output limits. Primary diagnostics and compiler proof state are outside the
evidence cap. No collector has been implemented or its memory measured in M0b.

M0b completed with the measured shape, timing baseline, temporary probe
recipe, explicit sampling limitation, provisional units and numeric caps in
`d7129e3` and `b5b1941`. No production collector or compiler
behavior changed in M0b.

## M1a pre-change selection, 2026-09-23

Base: `b5b1941e293425465e1d46fa76ec83ffc8f7acca`. M1a changes only a local
comparison script, its controlled-failure tests, and documentation. Its
consumers are independent base/candidate builds, package-private standard
library discovery, process outcome capture, artifact comparison, and later
checkpoint verification records. It must not alter compiler proof, source
loading, diagnostics, or generated output. Use the section 8.3 pair and the
section 8.4 exact-byte rules. The initial safe fixture is a small accepted
compile/link/run control; the unsafe fixture is MixedSlots with two ownership
errors and no artifacts. Script tests must reject a crash, timeout, usage/tool
failure, changed primary, missing or changed artifact/IR, false path mapping,
and a wrong or missing standard-library archive even when fallback sources
exist. The focused existing selections are `safe free selects stable blockers
across fresh compiler processes` and `rejected free preserves escape and
uncertainty reason selection`; the new script test runs only its controlled
fixtures and these named cases are not repeated unless the harness touches
their compiler behavior.

## M1a comparison harness verification, 2026-09-23

The [comparison script](../scripts/compare-explain-rejected-free.py) exports
the explicit base with `git archive`, builds it and the current checkout with
their own standard-library roots, and checks actual `StandardLibrary.locate`
results through a package-private helper. It records compiler/tool versions,
source hashes, raw process streams/statuses, discovery paths, and exact artifact
hashes under a preserved scratch directory. Only paired output roots and the
verified archive paths are mapped in process text. Source input paths, primary
messages/spans/order, class/archive bytes, and LLVM bytes are not normalized.
The script compares option-off builds; the flag remains unavailable.

Final paired run: base `be6c7a5470eed8d9194688cfd917282b5d94dfc4`
against the M1a working tree, Oracle GraalVM Java/javac 21.0.1, LLVM/Clang
23.1.0, and `--unfreed=off`. Raw logs and `report.json` are in the printed
local scratch directory ending `ironwood-parity-ehu4ar7b`. Both builds resolved
`Object`, `String`, and the fixture-required bundled types from their own
fresh `compiler/build/ironwood-stdlib.ironjar` with matching archive SHA-256
`2d293bf63442e383b1efc6015ffe83f147fd5507a58b68895c3ebdafdb904d47`.
The conflicting inherited home and working-directory control still selected
the base archive. Removing the base archive revealed a class-file fallback;
the harness rejected it, then restored the scratch archive.

| Fixture | Observed pair | Artifacts |
| --- | --- | --- |
| `small-accepted` | Both status 0; identical compile/link streams and native status 0. | Exact `Main.ironclass`, `.ironjar`, and LLVM bytes matched. |
| `mixed-slots-rejected` | Both status 1; same two ordered `cannot free` primaries at 25:14 and 39:14. | No class, archive, LLVM, or native output in either run. |

`PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-compare-explain-rejected-free.py`
passed five controlled tests. They reject unexpected status, usage, crash, tool
failure, timeout, unrelated error text, changed primary span or final newline,
stale/changed class artifacts, changed LLVM bytes, and a wrong archive path.
`./scripts/check-licenses.sh` and `git diff --check` passed. The initial run
with this host's default Java 8 stopped at the build gate; rerunning with the
recorded Java 21 passed. A first path mapper treated the checkout's source
paths as build paths; the final mapper limits substitution to output and archive
paths, and the unit test preserves source locations. This verifies the harness
on these controls, not future explanation notes, summary witnesses, or the
complete legal classpath matrix reserved for M5a.

M1a implementation and the checks above were committed in `8a24cd1`; the
pre-change contract selection was committed separately in `be6c7a5`.

## M1b pre-change selection, 2026-09-23

Base: `c0c2723d2884c76593ec7e9d999c52d458bf392d`. Shared consumers are
`Main.printDiagnostics`, IronDoc's formatter call, the language server's
primary-only `AnalysisEngine`, the standalone Eclipse output parser, and any
tests comparing full diagnostic records. The affected contract is an immutable
related-note list with old constructors/accessors preserved, full record equality
including notes, and byte-identical no-note formatting. A primary without both
source and span, a warning, or an ineligible error keeps no notes. M1b has no
ownership producer or CLI flag. The accepted controls are a located synthetic
error with cross-file, unlocated, and multiline notes plus existing no-note
error/warning/global rendering. The rejected controls are malformed blank note
text and attempted attachment to unlocated or warning primaries. Run the named
`diagnostic formatting includes location and source` selection, new full-block
formatter assertions, the standalone Eclipse parser against actual formatter
output, and M1a's accepted/rejected off/off fixtures. Compare no-note text and
class/archive/LLVM artifacts against this base; do not change ownership proof,
diagnostic order, or IDE behavior.

## M1b diagnostic API and renderer verification, 2026-09-23

The implementation adds `DiagnosticNote` and a copied immutable note list on
`Diagnostic`, keeps the old constructors and primary accessors, and renders
related blocks after the complete primary. It attaches notes only to errors
with a primary source and span; warning and incomplete-location attempts retain
the old no-note text. Full record equality now includes notes. IronDoc still
calls the shared formatter, while `AnalysisEngine` reads only primary message,
severity, source, and span from the default pipeline. Eclipse remains a
primary-only text consumer. D184 records this API and the pending CLI state.

Historical comparison: base `b5c813bb94ebde8490e3fdedbad7fca4f910b58b`
against the M1b working tree, both with the explanation flag absent. The
preserved `ironwood-parity-muhf601g` report has Oracle GraalVM Java 21.0.1 and
LLVM 23.1.0, fixture hashes, discovery paths, raw streams, and artifact hashes.
The accepted small control passed compile, link, and native exit 0 with exact
class/archive/LLVM bytes. MixedSlots retained the two ordered primaries at
25:14 and 39:14, status 1, and no output artifacts. Each build resolved its
required bundled types from its own freshly built archive; the wrong-home,
working-directory, and missing-archive isolation controls passed.

Focused checks passed:

- `diagnostic formatting includes location and source` and `structured
  diagnostic notes preserve primary and related blocks` (two tests). The new
  test checks complete primary and cross-file note blocks, one- versus
  two-digit gutters, single-line and multiline carets, unlocated notes,
  immutable storage, full equality, no-note warning/global output, rejected
  attachment to warnings or incomplete primaries, LF/CRLF golden comparison,
  indentation/caret/final-newline mismatch detection, platform separators,
  and actual CLI diagnostic final newline.
- The standalone `VerifyCompilerOutput` command recovered two unchanged real
  primaries and parsed both proposed and actual formatter note blocks under LF
  and CRLF, including cross-file and unlocated notes between adjacent unlocated
  errors. No Eclipse workbench or language-server build was run.
- `IronDocs comments, CLI, links, and reproducible library documentation`
  passed as the focused formatter consumer check. The focused test script's
  license audit and `git diff --check` passed.

No ownership note producer, explanation flag, evidence storage, or CLI
explanation behavior exists at M1b. The synthetic tests validate the shared
API/renderer boundary; M1c must exercise real eligible rejection sites and
M1d must measure the first collector's storage cost. No ownership proof or
generated-artifact format changed.

M1b API, renderer, tests, and D184 were committed in `3b1ff6c`; the
pre-change selection was committed in `b5c813b`.

## M1c pre-change selection, 2026-09-23

Base: `c4343bf5d1cdecc1d2257d5fa558e6531593be0f` on
`explain-rejected-free`. M1c affects the pipeline's semantic
construction, outer refinement, escape/symbolic/effect analysis rounds, final
function lowering, and every section 3.4 diagnostic emitter including late
loop and owned-element checks. Consumers include ordinary and dependency
compilation, native link, bundled standard-library source, and the default
CLI/IDE paths. Observer callbacks must be null-guarded and detached from proof;
the separate explanation setting must default off and stay independent of
`UnfreedMode` and the original input-source filter. A completed-refinement
rejection gets a truthful unsupported-detail boundary until M1d/M2 add facts;
a skipped-refinement rejection gets exactly section 5.6's fixed limited-analysis
note. Ineligible parser/name/type, wrong-pool, pending-binding-write,
use-after-free, and specialization guards remain note-free.

The paired cases are section 5.6's missing-`@Override` skip versus its corrected
safe control and retaining implementation, an unrelated final-body error after
completed refinement with an independent unsafe free, and accepted/rejected
local aliases, deferred registration/cleanup, destructor fields, loop back
edges, owned elements, and source/class/archive dependencies. Select existing
`safe free distinguishes earlier errors from refined dispatch`, `safe free
rejects live aliases and escaped allocations`, `deferred free enforces local
syntax and pending binding writes`, `rejected cleanup frees preserve per-exit
diagnostic multiplicity`, `loop back-edge rejections preserve both primary
diagnostics`, `creation-array cleanup proves distinct fresh elements`,
`rejected free in dependencies preserves compile and link source locations`,
and `rejected free in bundled Writer follows retaining user overrides` as
their machinery is touched. New registered tests must assert observer-positive
phases and actual null-observer/disabled absence, precise note count/location,
primary and artifact parity, and eligible/excluded rows. Use M1a's two-fixture
off/off comparison at each code commit; add targeted fixtures when these two
do not exercise the changed emitter. Do not run the full suite.

### M1c observer seam, first implementation commit

The package-private nullable `SemanticAnalysisObserver` and test-only pipeline
factory bridge are in place. Default pipeline/semantic constructors pass no
observer; a package-local test bridge receives only scalar pass outcomes and
source/callable identities. The first hooks report entered outer refinement
iterations, their stable outcome, completed versus skipped refinement, and
provisional/final lowering of user callables. They report collector absence
truthfully because M1d has not introduced one. All callback sites guard the
observer reference; no global or thread-local observer or production counter
was added. The explanation setting is separately carried through the pipeline,
but no diagnostic producer reads it yet, and the CLI remains unavailable.

`explanation observer records completed and skipped refinement` passed with a
safe compile control (entered/stable refinement, provisional and final lowering)
and a missing-`@Override` unsafe control (zero entered iterations, final-only
lowering, no collector). Observed and null-observer runs agreed on ordered
primary projections and successful LLVM. The first test attempted full
`Diagnostic` equality across separate parses and failed because `SourceFile`
identity differs; the corrected assertion compares message, severity, source
path, and span as section 8.1 requires. The M1a off/off harness passed its
accepted native and mixed-owner/slot rejection fixtures against base
`bd27b3a57a74f1e6286f8b22b9522dbeafaf4724`, with raw report under the
printed scratch directory ending `ironwood-parity-di2vo5fx`. The focused
script's license audit and `git diff --check` passed.

This first change did not yet cover analyzer instances, inner rounds, eligible
notes, exclusions, or dependency/bundled-source readiness. It introduced no
collector storage or cost claim.

The seam and its first tests were committed in `3c05be3`.

### M1c analyzer-instance and inner-round observation

The second implementation change reports each actual escape, symbolic-return,
owned-field, and effect analyzer instance with an observer-only token and phase.
It reports each entered escape sweep and symbolic/effect fixed-point round,
including stable terminating rounds. The final selected escape, symbolic,
field, and available effect instance tokens come from the analyzers consumed by
final lowering. The already-computed field `sameProofsAs` result is reported
without rerunning that comparison. No analyzer object or mutable summary map
is passed to the observer; token allocation and round callbacks are guarded by
the nullable observer.

The same registered observer test passed with positive instance and round counts
for completed refinement, a final selected token present among created
instances, and no refinement-phase instances or provisional lowering when
earlier errors skip refinement. It compared observed with null-observer
primaries and accepted LLVM. The M1a off/off comparison passed both fixtures
against base `3c05be3744502ee57702134fd4607cc2e44223a8`; raw records are
under the printed scratch directory ending `ironwood-parity-nbhsn6me`.
`./scripts/test.sh` supplied a passing license audit and `git diff --check`
passed. This change supplies lifecycle/round counts, not final semantic
projections or an explanation collector. M1c remains open for those projections,
eligibility/readiness notes, and the promised source-scope/exclusion checks.

### M1c local rejection and readiness boundaries

The third implementation change attaches a single note to the 11 eligible
local `free` rejection paths in final lowering when the separately carried
explanation setting is enabled. Completed refinement reports a category-specific
unsupported-detail boundary, without claiming a stored witness. Skipped
refinement uses section 5.6's exact limited-analysis text. The primitive
non-reference guard remains note-free. The default pipeline remains disabled,
and no CLI option or evidence collector exists yet.

`explanation readiness gives local boundaries without changing primaries`
passed with a completed escaped-allocation rejection in each `--unfreed` mode,
an earlier missing-`@Override` error that skips refinement, and an accepted
safe `free`. It asserted identical ordered primary projections between enabled
and disabled runs, an exact single note and source location on the completed
rejection, the exact limited-analysis note on the skipped rejection, no note on
the unrelated override error, and identical accepted LLVM. The M1a off/off
comparison passed both fixtures against base
`c7d1dfac17902ff85c2c58986778858fc208ad29`; raw records are under the
printed scratch directory ending `ironwood-parity-a3sbbu3a`.
`./scripts/test.sh` supplied a passing license audit and `git diff --check`
passed. The other 10 local paths have been wired but not individually exercised;
deferred, destructor, loop, owned-element, dependency, bundled-source, and
observer projection coverage remain for M1c. No high-water or artifact-cost
claim is made for this change.

The fourth implementation change routes eligible deferred registration,
destructor field, loop back-edge, and late owned-element rejections through the
same final-readiness gate. Deferred registration classifies its existing
short-circuit conditions in order, keeping the non-reference type guard
note-free. The primitive destructor field guard is also note-free. The
owned-element checker retains its per-field/function first-failure behavior.

`explanation eligibility covers deferred destructor loop and owned elements`
passed for unknown deferred ownership, duplicate registration, uncertain
destructor ownership, both loop back-edge primaries, and one owned-element
reason. It also checked non-reference deferred and destructor targets stay
note-free, plus enabled/disabled ordered-primary parity and disabled note
absence in each fixture. The M1a off/off comparison passed the accepted native
and mixed-owner/slot fixtures against base
`a848fc22c79fdf33436a1f4f23ab2ec3c75619a4`; raw records are under the
printed scratch directory ending `ironwood-parity-p1drvi81`.
`./scripts/test.sh` supplied a passing license audit and `git diff --check`
passed. The test did not exercise every subcondition or owned-element reason;
source/class/archive and bundled-source scope, skipped readiness in these late
sites, and final observer projections remain open for M1c. No collector or
storage claim is made here.

The fifth implementation change adds guarded, detached string projections of
the selected escape, symbolic-return, owned-field, and available effect proofs
to the nullable observer. The observer cannot retain mutable summary maps or
analyzer instances; its projection methods are called only inside the non-null
observer guard. The package-local test bridge records these values. The first
focused observer run failed because its skipped-refinement assertion expected a
selected effect analyzer, although that analyzer exists only in the completed
refinement path. The corrected assertion checks the three actual selected
proof analyzers in skipped mode. The focused observer test then passed: selected
projections agree with explanations enabled and disabled in a completed safe
compile, observed and null-observer accepted LLVM/primaries agree, and skipped
observed/null runs agree on primaries and notes.

`explanation notes retain dependency source class and archive identities`
passed with the same unsafe library `free` loaded from each source form. It
checked the original library display path and span, enabled note and disabled
absence, primary-message parity, and observed final lowering of that library
callable. `explanation notes retain bundled Writer source and final readiness`
passed with a retaining user override that rejects a bundled Writer destructor
field free. It checked the bundled source/span, completed-refinement boundary,
primary parity, and observed final lowering. These API tests use the real
pipeline and loader; public CLI transport remains M1e. The M1a off/off harness
passed its accepted native and mixed-owner/slot fixtures against base
`e95fdbaa8f68da50fdd8334169b3be9d79889540`; raw records are under the
printed scratch directory ending `ironwood-parity-y0jxz_o3`.
`./scripts/test.sh` supplied a passing license audit and `git diff --check`
passed. Later body-error readiness, broader exclusions, and callable-kind
observation remain for M1c. No collector or storage measurement was added.

### M1c completion: readiness, exclusions, and scope

The final M1c selection passed `explanation readiness and exclusions preserve
eligible note boundaries`: a later unrelated body error retained completed
refinement while its unsafe `free` received the completed boundary; parser,
name, type, pending-binding-write, standalone use-after-free, and wrong-pool
transfer diagnostics remained note-free. Deferred, destructor, both loop
back-edge, and owned-element rejections received the exact fixed limited note
after an earlier override error skipped refinement. The local readiness test
also passed with `@SuppressUnfreed` under OFF, WARN, and ERROR, preserving the
mandatory rejection and single explanation note.

The observer test passed with actual final lowering events for a user static
initializer, constructor, destructor, and method. Its completed run recorded
escape, symbolic-return, owned-field, and effect selected projections;
the skipped run recorded its three available selected proof projections.
Selected projections were detached strings and identical with explanations on
and off. The dependency source/class/archive and bundled Writer tests passed
again with earlier-error variants: each retained its original library primary
location and exact limited-analysis note. These checks use the actual loader
and pipeline; CLI flag transport and link invocation remain M1e.

Eight existing focused selections from the pre-change list passed on the
current built classes: earlier-error/refined dispatch, local aliases and
escapes, deferred syntax/pending writes, cleanup diagnostic multiplicity, both
loop primaries, creation-array proof, dependency compile/link locations, and
bundled Writer override behavior. The latest `./scripts/test.sh --test` runs
built all current test classes and passed the license audit; the eight named
selections then ran separately through `CompilerTests --test`, not as a full
suite. The section 8.3 off/off harness passed its accepted native and
mixed-owner/slot rejected fixtures against base
`541e514133516167574fff2b8fe9db64a21861ab`; raw records are under the
printed scratch directory ending `ironwood-parity-4eqxi1me`.

The first wrong-pool exclusion fixture also produced eligible companion pool
cleanup errors, so it could not establish that the transfer error itself was
note-free. The corrected fixture uses the existing direct wrong-pool checkout
pattern and passed. No safety rule or primary diagnostic changed. Source audit
found every section 3.4 eligible emitter routed through the readiness gate;
the primitive specialization error remains an excluded backend guard with no
ordinary-source reproducer. All producer methods constructing detached proof
projections are called only under `if (observer != null)`; default public
pipeline/semantic constructors and normal CLI construction pass no observer.
The actual local evidence collector is absent in all M1c modes and phases;
there is no snapshot/accounting lifetime or enabled-cost measurement yet.
M1d owns that first storage and cost gate. `git diff --check` passed.

M1c is complete for internal eligibility and readiness. Its notes are honest
unsupported-detail boundaries after completed refinement and the exact
limited-analysis boundary after a skip. The public CLI option, local witnesses,
source spans for causal events, note caps, and richer M2 through M4 evidence
remain pending; this checkpoint makes no supported-detail claim for them.

M1c pre-change contracts were committed in `bd27b3a`. The implementation and
verification commits are `3c05be3`, `c7d1dfa`, `a848fc2`, `e95fdba`,
`541e514`, and `9f70fcb`, in order. Each implementation change has its own
focused checks and off/off base comparison recorded above.

## M1d pre-change selection, 2026-09-24

Base: `2d13e04` on `explain-rejected-free`. M1d adds a nullable, final-phase,
completed-refinement function collector and first retained source facts. It
touches `FunctionAnalyzer` allocation registration, local binding writers,
selected blocking-reason updates, frees, and ownership save/restore/merge; the
`SemanticAnalyzer` observer reports the actual collector field. Consumers
include ordinary and dependency/bundled lowering, cleanup copies, loop checks,
and default compiler/IDE paths. All ownership, escape, return, field, effect,
and snapshot equality and fixed-point comparisons remain proof-only. Mandatory
safe-free acceptance/rejection is identical across OFF/WARN/ERROR and explanation
modes. D132/D133 forbid any valid-path runtime bookkeeping or generated-code
change. Evidence nodes, snapshots, maps, arguments, and strings are constructed
only behind the enabled collector guard; disabled saves use a shared empty
value. No source text is copied per event. An unavailable join receives an
honest boundary, never an arbitrary predecessor.

The first implementation commit will isolate nullable collector construction,
source origins, and snapshot lifecycle with producer-time unit accounting:
4,096 live function units, 2,048 snapshot associations, 512 path-label units,
and the distinct 1,048,576-unit invocation emergency stop from M0b. The second
adds current alias binding locations and reassignment invalidation. The third
associates the selected reason and earlier-free event with the same allocation
and path as the primary, without changing `Reclamation` ordering or the
`blockingReason` selection guards. Test-only budget inputs can force local and
invocation exhaustion. The output remains within eight notes per primary; M3a
owns bounded alternative path histories. Revisit the selection if implementation
needs a different storage lifetime or touches a further proof consumer.

Paired fixtures: safe `new`/free and reassigned-away alias versus live alias;
fresh replacement after a free versus double free; non-retaining call versus
field/static publication; direct free versus deferred/finally copies; equal
proof states with different predecessors versus an unavailable join. Exercise
source/class/archive dependency reconstruction and bundled Writer as scope
controls, not as a reason to infer unsupported callee witnesses. Run the
registered M1c eligibility/observer selections as the collector lifecycle is
touched, plus `safe free rejects live aliases and escaped allocations`,
`safe free rejects double free and post-free use`, `rejected free preserves
escape and uncertainty reason selection`, `rejected cleanup frees preserve
per-exit diagnostic multiplicity`, `deferred free preserves ownership across
cleanup predecessors`, and focused pool helper safety only if shared call
binding or summary machinery changes. New tests must assert actual positive
and negative collector presence, shared empty disabled snapshots, live and
cumulative evidence counts, capped fallback, selected source spans, unchanged
primaries/typed IR/LLVM, and no generated artifact metadata. Run section 8.3's
two-fixture off/off comparison against each implementation base. Measure the
prebuilt base/current compiler off/on on section 9's small, OrderBook, failing,
and bounded stress inputs, reporting median/variation, peak memory, cumulative
allocation, and cap high water separately; do not use a full suite.

### M1d nullable collector, origins, and snapshot lifecycle

The first implementation step adds a function-local collector only in enabled,
final, completed lowering. It records reused source/span objects at allocation
origins and holds evidence snapshots in a weak identity side map, separate from
`OwnershipSnapshot` and its proof equality. Restore and merge use the exact
proof snapshot object, never semantic equality, and discard origin detail when
one incoming path lacks it. A missing/truncated snapshot clears current detail
instead of reusing a stale source. Disabled and skipped lowering allocate no
collector or side map; the observer reports the shared empty evidence path.
The existing 4,096 function-unit, 2,048 snapshot-association, and 1,048,576
invocation-unit provisional limits are enforced before retaining facts or
snapshot copies. The invocation stop latches. No path-label units or other
evidence families exist yet. Weak keys release saved associations when the
corresponding proof snapshots are collected; all remaining charges retire when
the function analyzer closes. They can remain conservatively charged until
collection, so ordinary-workload cap behavior still needs M1d measurement.

`rejected-free evidence snapshots retain identity and enforce storage limits`
passed with two equal-text, distinct proof snapshot objects carrying different
origins. It checked restore, merge, empty merge, missing-snapshot clearing,
local snapshot-cap fallback, invocation-stop latching, and zero live invocation
charges after close. In its bounded control the snapshot-association high water
was exactly 3, below the 4-unit test cap; the live function charge stayed below
20 units. `explanation observer records completed and skipped refinement`
passed with collector-positive enabled final lowering, collector-negative
disabled/provisional/skipped lowering, positive origin and snapshot events,
disabled empty saves, selected-proof parity, and identical accepted LLVM across
enabled/disabled and observed/null runs. The local readiness test passed with
unchanged primaries and notes under all `--unfreed` modes. `./scripts/test.sh`
passed its license audit and the focused tests. Four additional exact
`CompilerTests --test` selections passed on those built classes: dependency
source/class/archive notes, bundled Writer notes, safe-free alias/escape
rejections, and cleanup diagnostic multiplicity. No full suite was run. The M1a
off/off harness passed both accepted native and mixed-owner/slot fixtures
against base `fb8a584e865c0444cb10510721d2405c70baf192`; raw records are
under the printed scratch directory ending `ironwood-parity-5blmxwda`.
`git diff --check` passed.

This step records origins but does not yet render source-origin notes, local
binding sites, selected reason events, or earlier-free paths. The direct unit
limits are retained-unit counts, not heap bytes. M1d remains open for binding
and reason producers, forced pipeline exhaustion, selected local golden output,
snapshot high-water and cumulative allocation measurements, and enabled cost.

The follow-up accounting change releases a source-site node as soon as no
current origin or retained snapshot references it. Restore and merge release
the old current associations, then charge the selected replacement as one
all-or-nothing group; this avoids arbitrary survivors if a cap is reached.
Weak snapshot retirement releases its associations and site references. The
focused storage test passed with an exact 11-unit live high water and three
snapshot associations in its two-path control, plus an unreferenced current
origin whose live charge fell from two to zero on unavailable restore.
`explanation observer records completed and skipped refinement` passed again;
the M1a off/off harness passed both fixtures against base
`b4874556f99cd40af229b6e6307142afe1bc1849`, with raw records under the
printed scratch directory ending `ironwood-parity-f24h0v84`.
`./scripts/test.sh` passed its license audit; `git diff --check` passed. Heap
bytes and ordinary-workload retained-snapshot peaks remain unmeasured at this
intermediate point. M1d remains open.

### M1d current local bindings and join invalidation

The second producer step records a local symbol's current allocation identity
and initializer or right-hand expression span at declaration, ordinary
assignment, and assignment-expression writes. Reassignment and scope exit
retire the current association. Evidence snapshots retain binding identities
and their source sites alongside origins; restore replaces current facts, and
merge keeps a binding only when every incoming path agrees on allocation and
source site. No binding fact enters ownership snapshots or proof equality.
The selected live-alias rejection checks that the stored allocation is still
the one selected by the proof before emitting the located cause and unlocated
observer-state notes from section 5.1. If source detail
was lost at a join or cap, it keeps the existing primary and the honest
unsupported-detail note. Alternative predecessors remain M3a work.

`rejected-free local bindings retain current source and invalidate reassignment`
passed for all three writers, exact right-hand offsets, paired reassignment-away
acceptance, normal common and differing-source joins, and exceptional
common-operand and phi joins. In each case the disabled and enabled primaries
matched. The differing-source joins produced an unlocated boundary instead of
an arbitrary predecessor site. The storage selection passed with a direct
two-snapshot control: one binding association per saved path, replacement and
restore by identity, no binding after incompatible merge, and exactly eight
live units after the restored current binding was unbound. Closing released
all invocation charges. `explanation observer records completed and skipped
refinement`, the local readiness selection, safe-free live alias, double-free,
selected-reason, cleanup multiplicity, and deferred cleanup predecessor
selections passed on the current built classes. `./scripts/test.sh` passed its
license audit for the focused new selection. The M1a off/off harness passed
both fixtures against base `bc22f37b54b0461b9f1b2eecfad0015768aaa736`;
raw evidence is under the printed scratch directory ending
`ironwood-parity-ijy4bqid`. `git diff --check` passed. This step does not
yet add selected-reason or earlier-free evidence, forced pipeline exhaustion,
or cost measurements. M1d remains open.

The final M1d golden check aligned the local-alias note text with section 5.1
and asserted the complete rendered primary, related source block, carets, and
unlocated observer-state note. Common-site normal and exceptional joins retain
both notes; differing-source joins retain only the honest boundary. The focused
local-binding selection and two-fixture off/off comparison passed again, with
the latter's raw records under the scratch directory ending
`ironwood-parity-hgxj7l0o`.

### M1d selected-reason identity and earlier-free path state

The third producer step adds one optional selected event per allocation. An
accepted escape, ACTIVE-only reclamation block, or non-owned uncertainty
replaces the reason event, even when its text is identical to the old reason.
Ignored attempted updates leave the selected event untouched. A successful
free replaces it with the actual `Reclamation` statement's source and span.
Evidence restore follows the exact proof snapshot; merge retains an event only
when every incoming path shares that event identity. Conflicting ownership
joins install an unlocated general-reason event instead of retaining a direct
cause. The existing proof states, reason strings, `Reclamation` list, and
primary selection remain unchanged. M2 supplies source operations for reason
events, while M3 supplies alternative-path descriptions.

`rejected free attributes only a current earlier reclamation path` passed
for a unique prior free, a returning branch whose free cannot reach the later
rejection, two distinct reaching branch frees with the unavailable boundary,
and a fresh replacement that points to the second allocation's free. It
asserted identical disabled and enabled primaries and no program or LLVM for
each rejection. The collector storage selection passed same-text event
replacement, distinct path identities, incompatible-join invalidation,
restore, free replacement, and zero invocation charges after close. The
focused reason-selection, branch/field boundary, local-binding, observer,
cleanup multiplicity, double-free/use-after-free, and readiness selections
passed on current built classes. `./scripts/test.sh` passed its license audit
for the new selection. The M1a off/off harness passed both fixtures against
base `12ec9cdade5fee7dd18a787d72f5f75860883ed2`, with raw records under
the printed scratch directory ending `ironwood-parity-mqy2hseg`.
`git diff --check` passed. Reason source locations, forced pipeline limits,
and enabled cost measurements remain open within M1d.

### M1d forced pipeline evidence limits

A package-private immutable test budget input now reaches only the enabled
final function collector. Normal constructors pass null and retain the default
4,096 function, 2,048 snapshot-association, and 1,048,576 invocation limits.
The observer cannot change budgets. The focused real-pipeline selection forced
function limit 1, snapshot limit 1, and invocation limit 1 separately. Each
rejected input kept the option-off primary and suppressed program/LLVM output;
the limited cases emitted unlocated fallback rather than a stale earlier-free
or alias site. The function and snapshot runs reported local truncation, the
invocation run reported a latched emergency stop, and their observed high-water
counts stayed within the forced cap. Disabled analysis reported zero collectors,
zero origins, and no notes. An accepted new/free program under the invocation
stop produced LLVM identical to option off. `./scripts/test.sh` passed its
license audit and the focused selection. The M1a off/off harness passed both
fixtures against base `79ce6e286ec7967fa6c9862384e46857586e61e8`, with
raw records under the printed scratch directory ending
`ironwood-parity-7ytt3tpl`. `git diff --check` passed. M1d remains open for
the measured collector cost, live heap, and cumulative allocation gate.

### M1d initial collector cost and completion

The final cost check used the independently built pre-collector compiler at
`fb8a584e865c0444cb10510721d2405c70baf192` with explanations off, and
the current compiler with explanations off and on. The base and candidate jar
SHA-256 values were `04c4e0c256d2a13a106f493976f10412e0778d0557bf039cd28727784ac862a9`
and `f804aee04640e1557dd7a699b1760fc415a6b6d4e105558289cbf046c5b086f1`.
The candidate jar includes the final section 5.1 golden wording and the
invocation high-water observer. Both builds used Oracle GraalVM Java 21.0.1
on Darwin arm64, identical source bytes, `--unfreed=off` in the internal
pipeline, and each build's own standard-library archive. The small and
four-source OrderBook inputs compiled successfully. Mixed owner/slot, five-level
nested publication, duplicated cleanup, and recursive-cycle inputs rejected;
all 247 bundled sources analyzed successfully. Source inputs, hashes, exact
probe versions, raw runs, and machine-readable summaries are under
`/tmp/ironwood-m1d-cost/report.json` (SHA-256
`dc98f46585fef3e1be780bb8d68ebd52d5b59f8e83d3b36fffe0519b886197d6`)
and its `raw/` directory.

Each mode had one warm-up and three measured runs, with order rotated each
round and a fresh JVM per run. The timer surrounded direct source analysis or
compile plus LLVM emission, excluding compiler rebuild, JVM startup, native
linking, and a pre-timer `System.gc()`. The probe measured main-thread
cumulative allocated bytes with `ThreadMXBean`, sampled used Java heap every
10 ms, and used `/usr/bin/time -l` for peak process RSS. The heap sample is a
lower bound on peak Java heap; neither it nor RSS isolates collector objects.
The retained-unit high-water counters below give the collector-specific
storage bound. All values in the table are measured-run medians with the
three-run range in parentheses.

| Workload | Mode | Wall s | Main-thread allocation MiB | Sampled peak heap MiB | Peak RSS MiB |
| --- | --- | ---: | ---: | ---: | ---: |
| small | base-off | 1.465 (1.416 to 1.466) | 1173.1 (1172.5 to 1173.9) | 206.2 (206.1 to 218.6) | 436.1 (435.0 to 452.8) |
| small | current-off | 1.387 (1.385 to 1.393) | 1167.9 (1166.0 to 1172.6) | 213.6 (209.8 to 222.3) | 455.0 (451.8 to 459.7) |
| small | current-on | 1.421 (1.410 to 1.429) | 1171.9 (1171.0 to 1177.7) | 218.6 (214.5 to 226.2) | 441.6 (432.1 to 467.7) |
| OrderBook | base-off | 1.734 (1.729 to 1.738) | 1629.7 (1628.3 to 1636.4) | 215.4 (205.0 to 241.9) | 481.6 (479.1 to 489.0) |
| OrderBook | current-off | 1.728 (1.702 to 1.757) | 1624.5 (1610.6 to 1636.8) | 213.3 (212.4 to 242.2) | 478.5 (460.9 to 481.2) |
| OrderBook | current-on | 1.733 (1.732 to 1.737) | 1630.2 (1626.3 to 1644.3) | 211.3 (209.0 to 234.6) | 484.8 (479.4 to 492.5) |
| mixed | base-off | 1.053 (1.031 to 1.075) | 954.3 (952.5 to 957.0) | 174.1 (173.8 to 181.8) | 420.5 (417.1 to 424.1) |
| mixed | current-off | 1.033 (1.032 to 1.057) | 953.0 (952.5 to 956.3) | 174.4 (170.1 to 185.8) | 421.9 (409.1 to 422.5) |
| mixed | current-on | 1.039 (1.037 to 1.080) | 959.0 (955.2 to 959.1) | 178.4 (173.8 to 189.2) | 415.8 (414.5 to 425.1) |
| nested5 | base-off | 1.132 (1.058 to 1.135) | 1027.4 (1025.0 to 1027.8) | 206.0 (202.6 to 214.4) | 433.3 (414.5 to 437.8) |
| nested5 | current-off | 1.085 (1.068 to 1.092) | 1025.5 (1022.9 to 1026.6) | 206.4 (198.7 to 207.0) | 422.1 (414.1 to 434.8) |
| nested5 | current-on | 1.140 (1.107 to 1.146) | 1030.5 (1030.2 to 1031.0) | 205.8 (202.4 to 206.3) | 431.0 (425.4 to 434.0) |
| cleanup | base-off | 1.090 (1.063 to 1.090) | 1012.8 (1009.5 to 1013.6) | 206.3 (205.9 to 209.6) | 429.6 (421.5 to 444.4) |
| cleanup | current-off | 1.059 (1.043 to 1.059) | 1010.4 (1010.3 to 1011.1) | 201.7 (197.6 to 206.2) | 421.9 (413.9 to 442.3) |
| cleanup | current-on | 1.097 (1.062 to 1.108) | 1015.7 (1012.0 to 1017.2) | 198.7 (194.0 to 206.3) | 419.0 (418.4 to 423.9) |
| cycle | base-off | 1.086 (1.066 to 1.100) | 1014.5 (1013.1 to 1014.9) | 202.3 (198.0 to 210.3) | 420.8 (418.3 to 426.5) |
| cycle | current-off | 1.088 (1.085 to 1.093) | 1014.8 (1011.7 to 1015.5) | 202.9 (194.0 to 206.6) | 421.1 (414.7 to 424.8) |
| cycle | current-on | 1.094 (1.089 to 1.124) | 1018.4 (1016.4 to 1020.1) | 201.7 (198.3 to 202.6) | 422.3 (419.5 to 430.9) |
| standard library | base-off | 5.590 (5.580 to 5.597) | 14786.9 (14718.8 to 14794.1) | 687.4 (685.3 to 718.2) | 1309.8 (1277.2 to 1328.8) |
| standard library | current-off | 5.649 (5.570 to 5.681) | 14812.3 (14802.9 to 14813.1) | 706.0 (692.3 to 710.2) | 1324.4 (1312.8 to 1349.1) |
| standard library | current-on | 5.665 (5.581 to 5.724) | 14822.2 (14780.2 to 14862.3) | 699.6 (671.8 to 712.7) | 1317.9 (1285.0 to 1350.9) |

The option-off wall ranges overlap the base on OrderBook, mixed, cycle,
cleanup, nested5, and the standard library. The small run was faster with the
candidate in the three-run block. Its current-off RSS median was 18.9 MiB
above base-off, so a separate six-run alternating check investigated that
apparent growth: base-off median RSS was 450.7 MiB and current-off 440.1 MiB;
sampled heap medians were 220.4 and 218.5 MiB. The first standard-library
timing block also placed current-off cumulative allocation below base-off,
while the final block placed it slightly above; this does not establish a
repeatable option-off allocation increase. Enabled cumulative allocation was
about 3 to 6 MiB above current-off on the six small/medium fixtures and
9.9 MiB at the standard-library median. Enabled wall and memory ranges
overlap current-off ranges on most workloads; nested5's wall range did not
overlap in the final three-run block, so its observed enabled overhead is
reported as 0.055 s at the median. This is an initial cost observation, not
a universal performance claim.

The current enabled collector observations were:

| Workload | Final collectors | Evidence saves / restores | Function high water / 4,096 | Snapshot associations high water / 2,048 | Invocation high water / 1,048,576 |
| --- | ---: | ---: | ---: | ---: | ---: |
| small | 564 | 3,346 / 1,708 | 694 | 332 | 694 |
| OrderBook | 645 | 3,613 / 1,840 | 694 | 332 | 694 |
| mixed | 568 | 3,363 / 1,718 | 694 | 332 | 694 |
| nested5 | 564 | 3,501 / 1,770 | 768 | 406 | 768 |
| cleanup | 565 | 3,357 / 1,713 | 694 | 332 | 694 |
| cycle | 569 | 3,366 / 1,716 | 694 | 332 | 694 |
| standard library | 2,722 | 19,389 / 8,921 | 2,171 | 1,787 | 2,171 |

No representative run reached a local cap or the invocation stop. The
standard-library snapshot cap had 261 associations of headroom; it merits
rechecking as M2 through M4 add evidence. Weak snapshot keys can remain
charged until collection, so these are conservative live-unit peaks, not
exact reachable-object counts. Forced function, snapshot, and invocation
limits were verified above. The storage accounting includes current/saved
origins, local bindings, selected events, and associations, but the heap
sample is total process Java heap and does not attribute bytes to these
families individually. No generated artifact includes the collector.

The final focused local-alias golden and observer selections passed, as did
the two-fixture M1a off/off harness against `4bf7a0d224a826925fe6dff4f6403ffa8bd356b4`;
raw parity records are under the scratch directory ending
`ironwood-parity-hgxj7l0o`. `./scripts/test.sh` passed the source license
audit; `git diff --check` passed. The current implementation fulfills M1d's
local explanation, bounded evidence, forced fallback, semantic isolation,
and initial cost criteria. M1d is complete. M1e is the next checkpoint; M3
alternative-path histories have not started.

M1d commits, in order: pre-change selection `fb8a584`, nullable collector
and origins `b487455`, retained-charge correction `bc22f37`, local bindings
`12ec9cd`, selected events and earlier-free paths `79ce6e2`, forced pipeline
limits `4bf7a0d`, and final local golden/cost record `551caa7`. Each
implementation step had its focused checks and independent off/off comparison
recorded above. The branch remains local and clean at this checkpoint.

### M1e pre-change selection, 2026-09-24

Base: `865a4c641a916b1bcc3fa8441a2be88a35a8dcff`. The M1d collector and
local notes are complete. M1e changes the `Main.run` parser, help text, and
pipeline construction only; semantic proof, selected reasons, and source
artifact serialization must remain unchanged. Consumers are source compilation,
separate native linking, stderr/stdout, the shared formatter, and the practical
guides. Accepted controls are bare and repeated flags before a source filename
and on link. Rejected neighbors are valued options, a misspelled option, and an
unsafe free that must retain its primary and status with or without notes.

The new exact selection will be `explain-rejected-free CLI parses and transports
the invocation option`. It will assert both help aliases, all value forms,
unknown-option handling, empty stdout and status 2 for usage, repeated flags,
source and link transport, stderr/status parity, no added success report, and
library and skipped-refinement output. Reuse `explanation readiness gives local
boundaries without changing primaries`, `explanation notes retain dependency
source class and archive identities`, and `structured diagnostic notes preserve
primary and related blocks` for neighboring contracts. Run the M1a off/off
comparison against this base and inspect enabled/disabled CLI output. `git diff
--check` and the source license check remain required. M1e adds no collector
storage or safety rule, so the M1d cost measurements remain its current cost
baseline; no new storage budget measurement is due here.

### M1e CLI delivery and completion

The pre-change selection was committed in `bdc282b`; the parser, transport,
focused test, and section 6.6 user guides were committed in `c46f691`.
`Main.run` now accepts the exact bare flag for source compilation and native
linking, including duplicates, while valued forms take the targeted usage
path. It passes the setting to the existing internal pipeline without changing
the default or serializing evidence. The help text replaces only the specified
shared-options line, and D184 records delivered M1 coverage.

The exact `explain-rejected-free CLI parses and transports the invocation
option` selection passed. It checked both help aliases, five valued forms in
source and link invocations, a misspelling, empty stdout/status 2 and full
usage for errors, no artifacts on malformed options, source rejection primary
and status parity, duplicate note suppression, successful source and native
link output, and enabled/disabled LLVM equality. A library compiled safely
alone rejected its free when a retaining application override was present;
the CLI showed a boundary note in the dependency. Two independently safe
class builds using compatible versions of that library formed a rejected
link; the option added a note while preserving the primary, status, and absence
of executable and LLVM outputs. An earlier `@Override` error triggered the
exact limited-analysis note at a separate free.

Three neighboring exact selections passed: `explanation readiness gives local
boundaries without changing primaries`, `explanation notes retain dependency
source class and archive identities`, and `structured diagnostic notes preserve
primary and related blocks`. The M1a independent off/off harness passed the
small accepted and MixedSlots rejected fixtures against
`865a4c641a916b1bcc3fa8441a2be88a35a8dcff`; its evidence is under
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-o4o251_m`.
The focused script's source license audit and `git diff --check` passed. No full
suite ran. Native bytes from two separate links in the CLI test differed even
with fixed output paths, so that test compares emitted LLVM and outcomes; the
independent off/off harness supplies its selected native artifact comparison.
M1e introduces no new evidence storage. M1d's measured collector costs and
high-water units remain the current cost baseline. M1e is complete; M2a is
next, and no M2 source-event detail is claimed here.

### M2a pre-change selection, 2026-09-24

Base: `e646f70` (full SHA to be recorded with the parity run). M2a may add
optional source sites to selected reason events and incoming array stores. It
must preserve `AllocationInfo` state/reason guards, primary selection and
location, ownership snapshot equality, pass counts, disabled collector absence,
and D132/D133 valid-path runtime behavior. Consumers include direct instance,
static, and array stores, `mergeValue` owner uncertainty, proof snapshot
restore/merge, the CLI formatter, and source/class reconstruction. The nullable
collector, not proof records or typed IR, owns all added sites. Selected event
replacement follows accepted reason updates even when text repeats; ignored
updates never replace the witness. A join lacking bounded context must retain
the honest boundary until M3a.

Use `rejected free preserves escape and uncertainty reason selection` and
`rejected free distinguishes incoming branch facts without changing join
reasons` for the existing safe/unsafe and selected-reason fixtures; extend them
with exact first-note locations for TwoStores, EscapeThenMerge,
MergeThenArray, and ArrayThenMerge. Add controls that remove each publication,
repeat a same-field store, restore a prior path, and exercise ignored updates
after escape, uncertainty, and free. For helper-owner uncertainty, pair
same-owner/nullable accepted merges with mixed-owner and non-borrowed rejected
merges, including exceptional flow where relevant. Reuse `rejected-free evidence
snapshots retain identity and enforce storage limits` and `explanation observer
records completed and skipped refinement` for storage, disabled guards, and
convergence. Run the M1a off/off comparison at the explicit base and focused
`git diff --check` plus source license audit. New site associations require
forced-cap coverage and updated high-water accounting; no semantic or runtime
rule change is authorized.

#### M2a direct-event slice, 2026-09-24

Commit `d3aefca` adds selected direct field/static/inexact-array stores, a
conditional-reference event, and one supported incoming-array store site. The
right-hand expression span is passed alongside the existing assignment write
span; typed IR and proof records are untouched. Array sites live in the bounded
optional collector, follow snapshot restore/merge, and retire when slots cease
to be current. The first note uses a source site only when its event still
matches the selected reason. Missing or distinct incoming sites remain an
unlocated boundary pending M3a.

`rejected free preserves escape and uncertainty reason selection` passed with
exact first-note lines for TwoStores, a repeated same-field store,
EscapeThenMerge, MergeThenArray, ArrayThenMerge, an instance field, and an
inexact array store. Accepted controls in that selection remained accepted;
DifferentFields retained its join boundary. `rejected free distinguishes
incoming branch facts without changing join reasons`, `explanation observer
records completed and skipped refinement`, `rejected-free evidence limits
preserve pipeline safety and truthful fallback`, and `rejected-free evidence
snapshots retain identity and enforce storage limits` passed. The storage
selection now checks array-site replacement, distinct snapshots, retirement,
forced local truncation, and zero invocation charges after closure.

The M1a off/off harness passed small accepted and MixedSlots rejected fixtures
against `e646f70b144ac114bd4539e6006a27f767610b36`; evidence is under
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-xu36joc7`.
`./scripts/test.sh` passed its source license audit, and `git diff --check`
passed. A single enabled all-standard-library observer run after the change
analyzed 247 sources and finished 2,722 collectors with 2,171/4,096 function
units, 1,787/2,048 snapshot associations, and 2,171/1,048,576 invocation
units at high water. It reported no local truncation or invocation stop. This
is an accounting check, not a new timing or heap measurement; M1d remains the
only measured cost baseline. M2a is still open: helper-owner uncertainty,
ignored-update and restore combinations, and the remaining direct-site
coverage checks have not been signed off.

#### M2a completion, 2026-09-24

Commit `bcbfa43` finishes M2a. A selected known-array-slot rejection now
points to its actual store; replacing that slot with null accepts the paired
control. `markEscaped` shares one recursive implementation while passing a
direct event span only to the allocation actually stored. The new exact
`rejected free keeps selected event sites across updates and restores` test
passed for escape after uncertainty, ignored updates after escape,
uncertainty, `FREED`, and `MAYBE_FREED`, and different restored branch stores.
The new exact `rejected free keeps borrowed owner uncertainty on its accepted
merge path` test passed two-owner and mixed borrowed/unborrowed rejections,
same-owner and nullable accepted controls, and a catch path that did not inherit
the normal merge's conflict. Those owner-merge notes remain boundaries until
M2c/M3a can name proven relationships and incoming paths.

The final seven exact selections passed together: the two new selections plus
`rejected free preserves escape and uncertainty reason selection`, `rejected
free distinguishes incoming branch facts without changing join reasons`,
`rejected-free evidence snapshots retain identity and enforce storage limits`,
`explanation observer records completed and skipped refinement`, and
`rejected-free evidence limits preserve pipeline safety and truthful fallback`.
The selected-reason selection also checks instance/static/inexact array sites,
a conditional reference, an incoming array store, a known slot, its clear
control, and a conflicting-join boundary. The M1a off/off harness passed the
small accepted and MixedSlots rejected fixtures against
`b6204628b8d4e5f2cbfe9d193414c875c7beefb5`; evidence is under
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-2a476dmf`.
The focused script's source license audit and `git diff --check` passed. No
unfiltered suite ran.

The added array-site storage received a focused cost check on a rejected
branch-and-array fixture (SHA-256
`3abc28ab2622159c9b4cd7736596da7479d9d5359dfebf110b103e3e93be5220`).
Both builds used Java 21 and their own standard-library archive. Each of
base-off, base-on, current-off, and current-on had one warm-up plus three
measured fresh JVM runs, rotated in order. The timer excludes rebuild and JVM
startup; main-thread cumulative allocation, 10 ms sampled peak Java heap,
and process peak RSS use the same M1d probe. Measured medians with three-run
ranges are:

| Mode | Wall s | Main-thread allocation MiB | Sampled peak heap MiB | Peak RSS MiB |
| --- | ---: | ---: | ---: | ---: |
| base-off | 1.049 (1.041 to 1.116) | 1011.1 (1010.5 to 1012.7) | 206.0 (157.2 to 206.3) | 427.1 (421.0 to 432.4) |
| base-on | 1.069 (1.061 to 1.124) | 1015.1 (1012.7 to 1017.9) | 206.0 (198.3 to 206.3) | 417.8 (409.8 to 456.0) |
| current-off | 1.046 (1.044 to 1.112) | 1009.7 (1008.2 to 1010.6) | 193.9 (189.9 to 210.0) | 406.9 (404.2 to 427.8) |
| current-on | 1.066 (1.063 to 1.072) | 1014.1 (1012.7 to 1015.4) | 198.6 (198.0 to 206.5) | 419.0 (414.6 to 426.5) |

The measured wall and allocation ranges overlap across the compared builds;
the sample does not establish a repeatable regression. Sampled heap is a
lower bound and total process RSS does not isolate the collector. The raw
measurement report is `/tmp/ironwood-m2a-cost/report.json` (SHA-256
`e229f8d6fea20b732bd1f77a744d0c8ad8466a42f73a1c19f92c35329e17726b`).
An exploratory four-mode run also included the small accepted fixture, but
its aggregation script failed after the invocations and did not save raw
results; it is not used for this cost conclusion. The standard-library
high-water accounting in the preceding slice remained below every cap.

M2a is complete. M2b is next. Call roles, missing identity, owner/pool
relationships, alternative paths, and callee chains remain outside delivered
detail and retain explicit boundaries where eligible.

## M2b pre-change selection (2026-09-24)

The affected proof producers are final resolved-call summaries, unknown dispatch,
constructor publication, and result identity. The consumers are ordinary and
deferred free diagnostics; source roles must survive operand lowering and any
cleanup replay without entering proof equality, call effects, or emitted IR.
The selected escape reason still controls which event receives a note. Call
notes use the actual receiver or argument source span, including multiline
operands, and describe only the final local summary effect. Missing identity
notes identify a parameter or result binding when that origin is known; a
mixed or unavailable origin remains an explicit boundary. No callee chain or
polymorphic target is inferred here.

Focused pairs: retaining versus proven non-retaining direct calls, receiver
versus argument publication, constructor argument and receiver publication,
multiline operand location, fresh versus published factory result, parameter
versus unknown result identity, and final reason replacement after a later
call. The existing exact summary, reason-selection, owner-contract, readiness,
and CLI tests are selected consumers. Add a dedicated exact M2b selection for
the new notes. After implementation, run those focused selections, the M1a
off/off accepted and rejected artifact harness, `git diff --check`, and the
source license audit. The standard-library enabled collector high-water and
focused cost remain a release gate under section 6.6; measure only if the
added per-call diagnostic storage changes material cost.

M2b implementation is `b214996`; its pre-change selection is `d0cfc31`.
The direct call, dispatch fallback, constructor publication, constructor
delegation, and prepared-call replay paths now retain receiver/argument source
spans before lowering discards expression roles. Only the accepted final local
escape reason receives a located note. A retained call effect reports what
the final summary permits at that operand, while an unproved polymorphic effect
states the non-retention boundary. No callee operation or dispatch target is
invented. An unknown identity uses the current operand's source span: a
parameter origin is named when it still matches that parameter, and a current
expression is named otherwise. If that span is unavailable, the existing
missing-identity boundary remains. This is source provenance for a failed
proof, not a claim of fresh ownership or a reconstruction of assignments.

The new exact selection covers retained argument and receiver, constructor
argument and receiver, two multiline cast operands with their actual full
spans, selected later call, non-retaining calls, proven fresh factory output,
published factory output, and parameter identity. It checks primary parity,
no notes with the option off, and no typed program or LLVM for rejection. The
final seven focused selections passed: that new selection, reason selection,
event restore, summary baselines, owner/dispatch contracts, forced evidence
limits, and local binding invalidation. The M1a off/off comparison passed both
accepted and rejected fixtures against `d28f3571e2b22b3673699e494b00d98c1017b1b1`;
its evidence directory is
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-13mz7q7k`.
`git diff --check` and `./scripts/check-licenses.sh` passed. No full suite ran.

An exploratory version retained every unknown reference binding and reached
2,518/4,096 function units and 2,041/2,048 snapshot associations with local
truncation during an enabled 247-source standard-library analysis. It was
removed before commit. The final version uses existing operand source spans
and adds no persistent identity map. The same observer probe completed 2,722
collectors with 2,171/4,096 function units, 1,787/2,048 snapshot
associations, and 2,171/1,048,576 invocation units, without local truncation
or invocation stop. Those high waters match the M2a accounting baseline. This
is a storage check, not a new wall-time or heap measurement. The initial parity
attempt used the host Java 8 and failed its base build at `javac --release`;
the Java 21 rerun passed both fixtures. Call and identity notes do not yet
describe the retaining owner, a callee path, path alternatives, or deferred
captured operands. M2b is complete; M2c is next.

## M2c pre-change selection (2026-09-24)

The proof relationships are `retainedBorrows`, exact constructor/setter
retention, container insertion and successful clear, wrapper-factory retention,
and dependent helper results. `lowerFreeOperand` selects an owner by allocation
list index before alias, state, or array checks. An explanation must follow
that selected owner, not the nearest source call or a current local that has
been reassigned. The owner-local lookup may name only a current environment
binding to that allocation; otherwise use a proven type plus creation site.
The relationship site must be the actual insertion/constructor/setter or
helper acquisition, with honest fallback when unavailable. Clearing or freeing
an owner must remove its evidence without changing proof state. Snapshot
restore and merge may retain only a relationship site common to supported
incoming paths; branch-specific differences remain M3a boundaries. Recursive
escape propagation must not turn a child's parent operation into a direct
store claim. The attached-field check must name only the proven field and
current load/attachment context, not an unproved whole-class failure reason.

Focused pairs: live and ended container/wrapper loans, two live owners with
only one released, reassigned owner name, dependent iterator and view versus
independent owned object, and attached field before/after supported detachment.
Keep the owner-contract and selected-reason groups, add a dedicated exact M2c
selection, then run the M1a off/off accepted/rejected harness, source license
audit, `git diff --check`, and enabled standard-library high-water accounting.
If relationship storage changes cost materially, measure focused enabled and
disabled compilation rather than claiming no cost from accounting alone.

M2c implementation is `707bc6d`; pre-change selection is `9486274`. An
optional owner/child relationship site now follows the proof's retaining
container, encapsulated setter/constructor, and fresh wrapper-factory edges.
It is copied, restored, merged only when the same site is common, charged to
the existing local/snapshot/invocation budgets, and retired when a borrower
is cleared or freed. A selected owner note names a current local only when
the environment still maps it to that allocation. Otherwise it names the
constructed type and, when retained, the creation site. Dependent iterator
notes identify a proven root owner and the helper source expression; pool
items and ambiguous nested borrows keep the boundary until M2d. Attached
field notes identify the proven field and field-load site, not an unobserved
attachment store or whole-class proof failure. Recursive escape propagation
passes the parent escape site to the child's selected reason; a separate
retaining-operation note is added only when one matching escaped owner is
supported. Branch-specific alternatives and callee internals remain later
work.

The dedicated exact owner selection passed container insertion, encapsulated
setter, a live list view, dependent iterator, two owners with one or both
released, reassigned owner with another live alias or only type/creation
fallback, and attached field before/after detachment. It compared option-off
primaries, option-on notes, accepted IR, and rejected artifact absence. The
storage selection passed relationship replacement, distinct snapshot sites,
restore, differing-site merge, owner retirement, and forced local cap. Seven
focused selections passed: those two plus owner/dispatch contracts, stable
primary blockers across fresh processes, selected reasons, M2b call sites,
and field/branch boundaries. The M1a off/off harness passed its two fixtures
against `51e734cb990af6b71759231293143fe026cb143e` at
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-6_74iarq`.
`git diff --check` and `./scripts/check-licenses.sh` passed. No full suite ran.

An enabled 247-source standard-library observer run remained valid with 2,722
collectors, 2,171/4,096 function units, 1,787/2,048 snapshot associations,
and 2,171/1,048,576 invocation units; no local truncation or invocation stop
occurred. A separate synthetic 30-owner rejected source reached 2,264
function units and 2,008 snapshot associations before the next save hit the
snapshot cap; the collector reported truthful local truncation, no invocation
stop, and all 30 primary errors. This stress case is outside the detailed
coverage guaranteed below the cap and is a storage limitation, not a changed
safety outcome.

The 30-owner source (SHA-256
`8749ec01753e11091bd06d7029cefea2f5392eaa80b3ae7a166abbef477ecd1e`)
also supplied a focused cost check. Independent base/current Java 21 builds
used their own bundled standard libraries. Each of base-off, base-on,
current-off, and current-on had one warm-up and three measured fresh JVM
runs, rotated in order; each yielded 30 errors. The in-process timer excluded
rebuild and JVM startup. Main-thread cumulative allocation, 10 ms sampled
peak Java heap, and process peak RSS used the M1d probe. Medians with
three-run ranges were:

| Mode | Wall s | Main-thread allocation MiB | Sampled peak heap MiB | Peak RSS MiB |
| --- | ---: | ---: | ---: | ---: |
| base-off | 1.123 (1.074 to 1.183) | 987.3 (985.6 to 988.1) | 202.3 (190.8 to 202.6) | 422.1 (412.7 to 422.3) |
| base-on | 1.096 (1.094 to 1.100) | 993.6 (990.7 to 993.8) | 202.6 (194.6 to 202.9) | 418.5 (413.8 to 423.7) |
| current-off | 1.084 (1.080 to 1.091) | 984.7 (981.9 to 985.5) | 198.9 (198.6 to 206.3) | 421.5 (421.2 to 426.7) |
| current-on | 1.104 (1.088 to 1.154) | 991.0 (988.4 to 993.0) | 206.3 (198.2 to 214.9) | 431.4 (421.3 to 436.3) |

Wall and allocation ranges overlap across compared builds; this small sample
does not establish a repeatable increase. Current-on RSS has a higher median
than base-on, with narrowly overlapping ranges; total RSS does not isolate
the collector. Sampled heap is a lower bound. The raw report is
`/tmp/ironwood-m2c-cost/report.json` (SHA-256
`e9207018452eca8d0bf2ac5e2d075d9acefe7e1a7aa9a884cbf10ca57dedd347`).
M2c is complete. M2d is next.

## M2d pre-change selection (2026-09-24)

The existing pool proof distinguishes a checkout's dependent borrow, a successful
return to the originating pool, a conservative lifetime bound after an
independently owned object is passed to `release`,
and destruction of the pool while an item is checked out. The explanation must
follow those relationships and the actual checkout site; it must not infer a
pool from a type or method spelling. The wrong-pool/unknown-pool release error
must remain note-free. Accepted return and pool destruction must remain accepted
in both option modes. A borrowed payload cannot be freed independently, and a
successful return does not destroy it. The owner helper and wrapper notes from
M2c must keep their separate meaning and owner identity.

Consumers include `FunctionAnalyzer`'s checkout, release, helper-borrow, free,
snapshot, and merge paths; `RejectedFreeEvidence`'s bounded optional storage;
the owner-contract fixtures; and CLI diagnostic formatting. Pair direct and
helper release with wrong/unknown-pool and independent-free failures, including
borrowed payload aliases, successful same-pool return, checked-out pool teardown,
iterator teardown, and wrapper termination. Keep section 5.13 dispatch witness
work assigned to M4b. Run exact owner-contract, owner-note, pool-release safety,
and selected-reason tests plus a dedicated pool-note selection. Run the M1a
off/off accepted/rejected harness, enabled standard-library high-water check,
`git diff --check`, and the source license audit. If storage grows materially,
compare focused enabled/disabled cost with the M1d probe.

#### M2d completion, 2026-09-24

The pre-change selection is `66db1c4`. Implementation `f84ea84` records an
optional checkout site on the proved pool-value borrow and propagates it through
supported aliases and common-site joins. A successful direct `release` records
its argument site for the existing conservative lifetime association. Both
sites use the already bounded, snapshotted evidence collector; neither changes
the pool proof, semantic state, generated IR, or runtime. Tests in `f84ea84`
and `da3629d` cover direct checkout, an alias, a reassigned pool local, same-pool
return, helper return, checked-out pool teardown, external-return independent
free, wrong-pool direct release, ambiguous originating pools, and an unsafe
helper. The iterator and wrapper owner tests retain their separate wording.

The direct wrong-pool and ambiguous-origin release errors have the same primary
projection in both option modes and no related notes. The unsafe cross-method
helper is rejected when its call summary makes the caller's pool escape; that
rejection is an eligible `free` and can have a local call-site note. It does not
produce the direct release primary in this fixture. M4b still owes the callee
witness. Passing an external object to `release` is outside D104's caller
contract. Its rejected independent free now says the compiler conservatively
blocks that free and explicitly does not promise pool cleanup of the external
object. Missing pool identity or source evidence remains a boundary rather
than a guessed owner. The source and memory guides and D184 state this limit.

The seven selected tests for safe/unsafe local frees, reason selection, owner
contracts, owner sites, the dedicated pool notes, and pool helper safety passed
on the implementation before the final external-return wording refinement.
After that refinement, the dedicated pool selection passed again, including
the added wrong/ambiguous/helper negative controls. Accepted controls compared
both option modes and their LLVM, while rejected controls compared ordered
primary fields and checked suppressed program/LLVM output. The M1a off/off
comparison passed both `small-accepted` and `mixed-slots-rejected` against full
base `66db1c4820a5992bc7cbd297e22399fdaf021732` with final implementation
diff SHA-256 `e28bca8f3056cf59ee2205804882e744de94b3d235521b22e18baede909c14fb`.
Fixture manifest SHA-256 values were
`341994c3d704c1f9470469fa3d054c78b4d6d74b5f877a1de0d1623429e30661`
and `b0cf98d83c41115a8885d1bf4f94fc2436191ed002a278f5010b7f6cc86b3568`.
The final comparison evidence directories are
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-4muavu6v`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-9mneaea_`.
The extra test-only commit did not change compiler behavior or the comparison
fixtures. Java 21 and pinned LLVM 23 were used; no full suite ran.

An enabled analysis of all 247 standard-library main sources remained valid,
finished 2,722 collectors, and reached 2,171/4,096 function units,
1,787/2,048 snapshot associations, and 2,171/1,048,576 invocation units at
high water. No local or invocation stop occurred. These are the same maxima
recorded for M2c, so this run found no material growth on that workload. It is
storage accounting, not a new timed or heap cost comparison; M2c's focused
cost probe remains the latest such measurement. `git diff --check` and the
source license audit passed. M2d is complete. The requested work stops here;
M3 through M5 remain planned.

## M3a pre-change selection (2026-09-24)

The existing `AllocationStateSnapshot` equality, reachability filtering, reason
selection, `mergeValue` owner checks, and mandatory safe-free outcome remain
authoritative. A diagnostic overlay may retain distinct events for equal proof
states, but cannot add semantic joins, reorder states, or infer an `ACTIVE` path
from absent allocation evidence. The overlay must identify the join caller's
actual incoming routes before a source label is printed. `SameField` and
`TwoPathFree` require equal-state alternatives; `DifferentFields`, `OneBranch`,
and `FieldOrCall` require different-state/reason alternatives; `ReturnedFree`
must omit a non-reaching return path. Unknown or capped evidence must produce
an explicit boundary and no unsupported all-path claim.

Affected consumers are `lowerIf`, conditional references, switch dispatch and
result joins, `lowerTry`, catch/exception edges, general flow joins, `mergeValue`,
and later loop/cleanup consumers. `RejectedFreeEvidence` owns live/snapshot/
invocation accounting and retirement. Before implementation, use paired
publishing/non-publishing branches, equal/different stores, a retaining call,
earlier-free alternatives, returning branch, missing-allocation predecessor,
and helper-owner normal/exceptional controls. Select the exact registered
`rejected free distinguishes incoming branch facts without changing join reasons`,
`rejected free preserves escape and uncertainty reason selection`,
`rejected free preserves branch reclamation and field proof boundaries`,
`safe free accepts local allocation and ended aliases`, and
`safe free rejects live aliases and escaped allocations` tests. Add focused
M3a note, exceptional/switch, and bounded-join tests; run the M1a off/off
accepted/rejected harness before each implementation commit, `git diff --check`,
the source license audit, and a representative enabled storage/cost check.
Revisit the selection if the changed consumer set expands. No hot runtime
lowering is planned, so native benchmark and machine-code checks are not part
of this selection.

## M3a ordinary-if slice (2026-09-24)

Commit `647a485a8d2e0fa9518ca5db23b3bbd031308611` adds bounded,
snapshot-backed incoming alternatives at reachable ordinary `if` joins. It
preserves equal proof states and their distinct events, including two field
stores and two earlier frees. A predecessor with the same event and state as
every other predecessor retains the single earlier event instead of inventing
duplicate branch causes. An absent or active incoming fact is rendered only as
that fact. Captured alternatives have a six-entry limit and disclose omissions
or missing source evidence; incomplete capture suppresses aggregate claims.

The exact tests `rejected free explains both ordinary branch witnesses`,
`rejected free attributes only a current earlier reclamation path`,
`rejected free preserves escape and uncertainty reason selection`,
`rejected free distinguishes incoming branch facts without changing join
reasons`, `rejected free preserves branch reclamation and field proof
boundaries`, `safe free accepts local allocation and ended aliases`, and
`safe free rejects live aliases and escaped allocations` passed. The new
cases compare option-off/on primary fields and artifacts and assert both
source alternatives for the four section 5.8 fixtures. The M1a independent
off/off comparison passed accepted and rejected fixtures against
`227f07d6357027de75226a88141c1e488d6b8dad`; evidence directories are
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-m1xgrxef`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-1uusa5l9`.
`git diff --check` and `./scripts/check-licenses.sh` passed. The Java 21,
LLVM 23 enabled standard-library probe compiled 247 sources through 2,722
collectors with no errors or truncation; high water was 2,171/4,096 local,
1,787/2,048 snapshot, and 2,171/1,048,576 invocation units. This is
storage accounting, not a timed cost comparison. Switch, conditional,
try/catch, exceptional, general-flow, and helper-owner joins, plus absent
allocation and large nested-join coverage, remain in M3a. No full suite ran.

## M3a switch-statement slice (2026-09-24)

Commit `f1aba11ac8799098b04f945c2d4879a6b3c3d5b1` carries source-backed
case/default and grouped-label alternatives through classic and arrow switch
statements. Classic group entry distinguishes direct dispatch from fallthrough.
Continuation evidence includes reachable case transfers and the unmatched
path when a switch has no `default`. It uses existing ownership snapshots and
does not change branch reachability or proof equality.

The exact `rejected free explains switch dispatch and continuation paths`,
`classic switch selectors labels scope and returns are checked`, and `modern
switch selectors labels exhaustiveness results and yield are checked` tests
passed. The new selection covers classic/arrow case stores, grouped labels,
fallthrough, and a no-default continuation with option-off/on primary parity,
suppressed artifacts, path labels, and event lines. The M1a independent off/off
comparison passed accepted and rejected fixtures against full base
`1d8f4da6b1fa032286b0813a737f013af484e5d9`; evidence directories are
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-64w2pnhh`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-_3010pya`.
`git diff --check` and the source license audit passed. An enabled analysis of
247 standard-library main sources was valid, completed 2,722 collectors, and
had high water of 2,171/4,096 local, 1,787/2,048 snapshot, and
2,171/1,048,576 invocation units, with no truncation. This is storage
accounting; it does not measure timed cost. Switch-expression result joins,
conditional expressions, try/catch, exceptional and general-flow joins,
helper-owner paths, absent allocations, and bounded nested joins remain in
M3a. No full suite ran.

## M3a expression-join slice (2026-09-24)

Commit `221999905f89512c246acd9a8618b49ceb31fd3e` associates incoming
ownership evidence with conditional true/false, evaluated/skipped short-circuit
operands, and classic/arrow switch-expression dispatch and yields. Switch
expression result labels identify the source arm and retained yield event.
Proof states, result phis, reachability, and emitted IR are unchanged.

The exact `rejected free explains conditional and short-circuit expression
paths`, `modern switch selectors labels exhaustiveness results and yield are
checked`, `rejected free preserves escape and uncertainty reason selection`,
and `rejected free distinguishes incoming branch facts without changing join
reasons` tests passed. The new cases compare option-off/on primary fields,
artifact suppression, predecessor labels, and event lines. The M1a independent
off/off accepted and rejected fixtures passed against full base
`ecc7e8fae8b730a259c94ebe1a483d239c83f4e6`; evidence directories are
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-p1act1f2`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-5hhv8uc_`.
`git diff --check` and the source license audit passed. The enabled 247-source
standard-library probe remained valid through 2,722 collectors; high water
was 2,171/4,096 local, 1,787/2,048 snapshot, and 2,171/1,048,576
invocation units, without truncation. This is storage accounting, not a timed
cost comparison. Try/catch, exceptional and general-flow joins, helper-owner
paths, absent allocations, and bounded nested joins remain in M3a. No full
suite ran.

## M3a try/catch and exceptional slice (2026-09-24)

Commit `7267d4bd1a0c52e9ebb684337023d69e5e2e9f95` retains separate
normal completion evidence for the try body and each reaching catch clause.
Exception handler joins label possible operation edges separately from
explicit-throw edges. The labels are captured from each original predecessor
before the existing ownership merge; semantic state and reachability rules
remain unchanged.

The exact `rejected free explains try catch and exception predecessors`,
`predecessor-free catch preserves direct and cleanup primaries`, `safe free
tracks ownership independently across duplicated finally paths`, and `safe
free rejects live aliases and escaped allocations` tests passed. The new
selection checks option-off/on primary parity and artifact suppression, actual
normal try and catch store lines, exception-edge labels, and source identity.
The M1a independent off/off accepted and rejected fixtures passed against full
base `7cc131c9003e7a8ee715bc14d6e4418f0b2357e2`; evidence directories
are `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-7k_aiuz_`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-8e0up9nj`.
`git diff --check` and the source license audit passed. The enabled 247-source
standard-library analysis stayed valid through 2,722 collectors and had high
water of 2,432/4,096 local, 1,838/2,048 snapshot, and 2,432/1,048,576
invocation units, with no truncation. This is 261 more local/invocation units
and 51 more snapshot associations than the preceding expression slice; it is
storage accounting, not a timed cost comparison. General-flow joins,
helper-owner paths, absent allocations, and bounded nested joins remain in
M3a. No full suite ran.

## M3a labeled-flow slice (2026-09-24)

Commit `0445516a1a54feeb76f4ece3830862833a74a091` carries source-backed
break and normal-completion alternatives for non-loop labeled statements.
The publishing break reaches the later free; the normally completing path
does not inherit its store. Removing the store accepts the same program.

The exact `rejected free explains labeled transfer and normal predecessors`,
`safe free rejects unknown identities and uncertain control flow`, and
`rejected free distinguishes incoming branch facts without changing join
reasons` tests passed. The new selection checks option-off/on primary parity,
source identity, path labels, the retained store line, and suppressed artifacts.
The M1a independent off/off accepted and rejected fixtures passed against
full base `299f0216fb9bc57bc2c10cbb4fc2077d03ccf0d1`; evidence
directories are
`/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-olr_4vou`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-kbmora5i`.
`git diff --check` and the source license audit passed. The enabled 247-source
standard-library probe stayed valid through 2,722 collectors; high water
remained 2,432/4,096 local, 1,838/2,048 snapshot, and 2,432/1,048,576
invocation units with no truncation. This is storage accounting, not a timed
cost comparison. Helper-owner paths, absent allocations, and bounded
nested/sequential joins remain in M3a. No full suite ran.

## M3a helper-owner merge slice (2026-09-24)

Commit `6067df602ff35b357cc4a0edd5f0ee8d4c775c67` attaches caller-labeled
incoming helper bindings to each owner only when `makeUncertain` actually
accepts the conflicting-owner reason. Two-owner notes identify which incoming
helper belongs to the affected owner and which belongs to the other owner,
plus the affected owner's allocation. A mixed ordinary reference gets an
honest condition anchor because its assignment has no retained owner binding.
The common-operand early return, same-owner and nullable helpers, and an
already escaped owner preserve their existing safety and selected evidence.
The exceptional catch path does not acquire the normal route's owner conflict.

The exact `rejected free keeps borrowed owner uncertainty on its accepted
merge path`, `rejected free preserves escape and uncertainty reason selection`,
`rejected-free evidence snapshots retain identity and enforce storage limits`,
and `safe free accepts local allocation and ended aliases` tests passed. The
owner selection compares option-off/on primary fields and source locations
for both owners, the mixed path, and normal versus exceptional completion.
The M1a independent off/off accepted and rejected fixtures passed against full
base `06014f1d67eb748f0bbe554889a22b891ed1871c`; evidence directories
are `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-yds0kv9p`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-9wz5cxys`.
`git diff --check` and the source license audit passed. The enabled 247-source
standard-library probe stayed valid through 2,722 collectors and had high
water of 2,432/4,096 local, 1,838/2,048 snapshot, and 2,432/1,048,576
invocation units without truncation. This is storage accounting, not a timed
cost comparison. Absent allocations and bounded nested/sequential joins
remain in M3a. No full suite ran.

## M3a bounded-join and absent-identity selection (2026-09-24)

Test-only commit `f4649505a11ae639cc6fb0ffe8679593c2cd1653` registers the
exact `rejected free bounds nested joins and discloses incomplete alternatives`
selection. Eight nested stores are retained in deterministic source order up
to six alternatives; the diagnostic remains within eight notes and discloses
the omitted paths. A second join after intervening stores selects only its
current witnesses. A branch with an unlocated merged-reference reason reports
missing source evidence and makes no all-path claim. These tests compare
option-off/on primary fields and rejected artifact suppression. The separate
absent-allocation control merges a branch-created allocation with a path where
it does not exist. Its local then lacks a proven fresh identity, and the
existing primary rejects the free; the note anchors the condition and does
not present the absent path as an active non-escape path. This is an identity
boundary, not a located join alternative. The existing `OneBranch` selection
separately exercises an actually `ACTIVE` predecessor.

The exact bounded selection, `git diff --check`, and the source license audit
passed. No compiler implementation or parity fixture changed in this commit,
so the helper-owner slice's independent off/off comparison remains current.
No timed or new storage measurement was due for this test-only change. Loop
join contexts and exceptional environment routes still need M3a review. No
full suite ran.

## M3a loop joins and completion (2026-09-24)

Commit `2113d502432009e66c77a93df59c9c5df0c61af4` carries source-backed
condition-exit, break, continue, normal-body, and update back-edge labels through
the existing while, do-while, for, and enhanced-for ownership merges. The new
exact `rejected free explains loop condition and break predecessors` selection
checks one rejection and one accepted no-store control per loop form. Its four
rejections retain the option-off primary message, span, severity, source,
diagnostic count, and artifact suppression. Each explained rejection has the
condition-exit and break alternative, the direct-store location, and the
supported some-path aggregate. The exact `loop back-edge rejections preserve
both primary diagnostics`, `safe free rejects unknown identities and uncertain
control flow`, and `rejected free explains try catch and exception predecessors`
selections also passed. Loop-back-edge error explanations remain M3d.

The exceptional environment path was reviewed against section 6.3. Its
preceding `beginExceptionHandler` merge already captures labeled exceptional
ownership inputs. The common-operand route only preserves the operand; the phi
route propagates common borrowing and merges allocation identities. Neither
route runs `mergeValue`'s conflicting-owner update, and no new semantic check
was added. An identity loss may still have only a source-region boundary when
the exceptional predecessors have no retained distinct event. This is not a
claim that an exception occurred or that an absent identity was active.

The M1a independent off/off accepted and rejected fixtures passed against full
base `8841a3b2f918461d583ed4b2e295f99dc3df31df`; evidence directories
are `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-3wnag80z`
and `/var/folders/1w/2s1ghghj21z7hbvrll476k5m0000gn/T/ironwood-parity-vqu8bf0c`.
`git diff --check` and the source license audit passed. The enabled 247-source
standard-library probe stayed valid through 2,722 collectors, with high water
of 2,432/4,096 local, 1,838/2,048 snapshot, and 2,432/1,048,576 invocation
units. Neither cap truncated or stopped collection. This is storage accounting,
not a timed cost comparison. Earlier M3a slices and this loop slice cover the
section 5.8/5.10 branches, equal-state distinct witnesses, absent-identity
boundary, non-reaching predecessors, supported aggregate classifications,
and bounded nested/sequential joins. M3a is complete; M3b is next. No full
suite ran.
