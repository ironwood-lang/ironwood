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
the one selected by the proof before emitting a located note. If source detail
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
