<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Performance improvement verification

## Stage 1 outcome: reject the measured candidate

The conservative typed-IR pass passed the selected correctness checks and
removed redundant guards, but repeatedly slowed the local deterministic
OrderBook benchmark. Following coordinator review, this stage leaves production
compiler behavior unchanged. Only this report is proposed for commit. The
implementation, regression tests, proposed optimization documentation and
decision text are preserved as a re-applicable patch in ignored workspace;
none is an accepted production change or decision.

No workload-specific name or threshold was added to production code to select
a favorable result. The later guarded state-2 specialization investigation can
reuse the correctness analysis below, but successful ensures alone still do
not prove state 2. Linux execution remains unverified and is deferred to the
maintainer's later run. There is no Linux speedup claim.

## Stage 1 pre-change review

The following review records the candidate's implementation plan, before its
measurements caused rejection. Its new test registrations live in the archived
patch, not in the current production test list.

Starting revision: `2bca0b4a0ca93f2d7937d6257dc2dae5ebc4a29e` on the authorized
local `perf-improvements` branch. Host: macOS ARM64, Java 21.0.1, LLVM 23.1.0.
This stage evaluates redundant type-initialization checks only. Earlier Linux
counter attribution is a hypothesis, not evidence of this change's benefit.

### Contracts and consumers

- Preserve lazy first active use, prerequisite order, recursive state-1 partial
  observations, state-3 failure identity, and enum publication only after a
  successful constructor. Never move an ensure onto a zero-trip loop or untaken
  branch. Preserve mandatory reclamation proofs in every unfreed mode.
- `FunctionAnalyzer` emits typed ensures after excluding types with no
  initialization work through `TypeInitializationAnalysis`. No existing typed-IR
  repeated-check optimization was found. Run the new pass after all semantic,
  ownership and specialization analysis, before LLVM emission. Source, loose
  class and archive reconstruction use the same compiler pipeline.
- Consumers include ordinary and initializer methods, exception cleanup CFGs,
  SSA phi predecessors, closed-world reachability and trace metadata. Removing
  a redundant invoke must repair its deleted exceptional edge and unreachable
  cleanup blocks. The backend state machine and public language remain intact.
- A normal ensure establishes only state 1 or 2 within the current synchronous
  function activation. State 1 belongs to an initializer above that activation;
  it cannot finish or fail until the activation exits. State 2 is permanent.
  This fact permits another ensure of exactly the same type to be omitted,
  but does not prove initialized fields, prerequisites, or enum publication.
  Facts start empty at function entry, intersect at joins, and are discarded on
  every exceptional edge. No facts are exported from callees or returned from
  recursive calls. In particular a callee observing state 1 may return to an
  initializer that subsequently fails and is caught by an outer caller.
- Preserve D132/D133: add no runtime state, allocation, tracing, or helper calls.
  No code versioning, eager initialization, enum-address substitution, array
  layout, bounds-check or global inline-policy change belongs to this stage.

### Focused verification selected before implementation

New checks, registered under these exact names:

- `dominated initialization checks preserve typed control flow`: positive
  repeated, both-branch, pre-loop and normal-invoke dominance; negative one-arm,
  zero-trip, failed-invoke, caught-exception and distinct-type cases; deleted
  unwind edges, live phi inputs and unreachable blocks; idempotence.
- `dominated initialization checks preserve recursive failures at O3`:
  native lazy branch/loop behavior, recursive partial state, a recursive
  observation followed by caught outer failure and repeated exception identity,
  and enum unpublished-field observations during construction.

Existing focused checks:

- `static initialization lowers through typed IR and private LLVM state`
- `static initialization ordering cycles and failures run at O3`
- `enum constants initialization and switches lower through typed IR`
- `enum constant-specific bodies lower as immortal final subtype storage`
- `enum identity ordering lookup switching and initialization run at O3`
- `static initialization survives source-path class-path and archive round trips`
- `enums survive source class individual-file archive and tree-shaking round trips`

Also run `projects/OrderBook/test.sh`, deterministic paired `10 100` throughput
samples, inspect matching `-O3 -march=native` generated IR and machine code,
`git diff --check`, and `./scripts/check-licenses.sh`. No unfiltered suite.
Preserve baseline binary, compiler jar, classes, emitted LLVM, disassembly,
hashes and logs in ignored `workspace/perf-improvements/stage1/before/` before
implementation. Keep existing artifacts separately in `stage1/preexisting/`.
Local timings cannot establish a Linux speedup. Record completed results and
any changed verification scope below before handoff.

## Candidate implementation and proof

The archived `TypeInitializationCheckEliminator` ran in `CompilerPipeline`
after semantic validation, ownership analysis and generic specialization. It
computed function-local must facts using exact type names, an empty entry set,
intersection at joins, and a descending fixed point over reachable CFG blocks.
Non-entry blocks started at the set of mentioned types so loop backedges did
not suppress an existing entry proof. Normal ensure-invoke edges added a fact;
all exceptional edges discarded facts. The pass inferred no prerequisite facts.

It deleted repeated ensure instructions and converted redundant ensure-invokes
to normal jumps. It then removed unreachable blocks and obsolete phi inputs,
preserving surviving labels, values, spans and function identity metadata, and
updated the program entry-point reference. A throw-invoke's nominal normal
label remained reachable for LLVM structural validity, even though it cannot
execute. No runtime, emitter, memory-safety analysis, or inline policy changed.

The per-activation state-1-or-2 proof is narrower than a completed-initialization
proof. A type already initializing when a synchronous function observes it
belongs to an ancestor initializer. That initializer cannot leave state 1
until this activation exits. A type that starts initializing inside an ensure
must reach state 2 for that ensure to return normally; state 2 is permanent.
Therefore a second ensure of the same type in this activation has no work.
This does not permit assuming initialized fields, immutable enum publication,
or facts in the caller after a return. The native regression explicitly
observed partial state, caught a nested initializer failure, returned into an
outer initializer that then failed, and checked that subsequent caller accesses
still threw the identical cached exception. Threads, suspension and resumable
execution would require revisiting the proof. Exceptional-edge clearing is an
intentional conservative limitation.

## Correctness and artifact results

All nine exact focused checks listed above ultimately passed. The first run
passed eight and found one incorrect expected count in the new structural
test: a case containing entry check T, invoked check U, and catch-path check T
must retain three checks because exceptional edges clear the fact set. Only
that assertion changed, and rerunning the failing test passed. No production
change followed the successful native checks. The full suite was not run.

The native regression returned 42 both with the preserved baseline compiler
and with the candidate. It also covered zero-trip and untaken-path laziness,
repeat first-use counts, and repeated null/non-null enum observations before
and after constant publication. Existing checks covered prerequisite ordering,
cycles, failure identity and exact uncaught traces, enum constant bodies, and
source-path, loose-class and archive reconstruction.

`projects/OrderBook/compile.sh`, `link.sh`, and `test.sh` passed with the candidate:
four native tests, six Java tests, native/Java throughput and latency argument
checks, allocation-free sample collection and pool recovery checks, and
byte-for-byte native/Java report equality. Candidate source passed
`./scripts/check-licenses.sh` and `git diff --check`.

## Generated-code evidence

Both builds used Java 21.0.1, LLVM 23.1.0, `-O3 -march=native` and the repository's
unchanged `default<O3>`, `-inline-threshold=1000`, `-enable-partial-inlining`
pipeline. Optimized LLVM was reproduced with those same flags and
`-mcpu=native`; disassembly came from the actual linked Mach-O executables.

| Static code measure | Before | Candidate |
| --- | ---: | ---: |
| Emitted ensures in `Bench.run` | 6 | 1 |
| Initialization-state loads in optimized `Bench.run` body | 8 | 3 |
| Of those loads, `Order.Side` | 6 | 1 |
| Of those loads, `Order.Type` | 2 | 2 |
| Instructions in native `Bench.run` body symbol | 520 | 481 |
| Conditional branches in that symbol | 89 | 82 |
| Initialization-state loads in optimized `OrderBook.match` | 4 | 3 |
| Instructions in native `OrderBook.match` | 316 | 289 |

The surviving raw `Bench.run` Side check remains in `for.body.1`, after the
loop condition, and ordinary enum-pointer loads remain. The native body symbol
is `Bench.run.28.for.body.1.lr.ph`. The pass changed only two OrderBook functions
at typed-IR emission: `Bench.run` (six ensures to one) and
`Order.Side.invertedIndex` (three to one). The latter is subsequently inlined
into matching code. `OrderBook.createLimit` remained at three optimized state
loads and 308 native instructions. Static instruction counts include cold
paths and are not dynamic execution counts or a performance proof.

## Paired local measurements and rejection

Each process ran the unchanged deterministic workload, validated its counters
and full pool recovery, and reported internal measured nanoseconds. Each
comparison used 12 before/after pairs with alternating order. The second
comparison started with the candidate, reversing the first comparison's
starting order. Neither executable used PGO.

| Warmup / measured operations, millions | Before median ns | Candidate median ns | Candidate time change |
| --- | ---: | ---: | ---: |
| 10 / 100 | 715,762,000 | 735,278,000 | +2.73% |
| 8 / 80, official argument pair | 571,728,000 | 590,207,500 | +3.23% |

At 10/100 the before range was 704,394,000 to 725,863,000 ns; the candidate
range was 726,382,000 to 769,574,000 ns. At 8/80 the before range was 565,784,000
to 576,472,000 ns; the candidate range was 577,597,000 to 632,323,000 ns. The
candidate was slower in every pair in both comparisons. These are local
observations, not confidence intervals or a causal microarchitectural diagnosis.

A diagnostic isolation used the saved baseline compiler plus the same typed-IR
pass to transform only `Bench.run`, only `Side.invertedIndex`, or all functions.
Eight rotating and reversed four-way rounds gave medians of 716,810,000 ns for
baseline, 715,271,000 ns for the run-only variant, 718,111,500 ns for the side-only
variant, and 731,842,500 ns for the full candidate. The isolated changes were
near baseline while the combination remained slower. The all-functions variant
emitted byte-identical LLVM to the normal candidate. This suggests an interaction
with later code generation or layout but does not establish its cause. Function
selection was confined to the ignored diagnostic harness and is not a proposed
compiler profitability rule. No general profitable alternative was established,
so the experiment was rejected rather than choosing a workload-specific subset.

## Evidence locations and reproduction

All artifact paths below are relative to ignored
`workspace/perf-improvements/stage1/` in the canonical checkout:

- `before/` and `after/`: runnable `orderbook-bench`, `ironwoodc.jar`, raw
  `orderbook-bench.ll`, reproduced `orderbook-bench.opt.ll`, actual executable
  disassembly `orderbook-bench.asm`, `SHA256SUMS`, and build/link logs. The
  baseline also retains source-bearing class artifacts and the baseline native
  regression result in `regression-run.json`.
- Each variant directory has extracted `Bench.run`, `OrderBook.createLimit`,
  and `OrderBook.match` `.ll`, `.opt.ll`, and `.asm` files. `code-summary.json`
  records the counts above.
- `focused-tests.log`, `typed-retest.log`, and `after/orderbook-tests.log`
  preserve the exact tests and results, including the corrected assertion.
- `paired-throughput.json` and `paired-throughput-summary.json` contain the
  first 12 pairs. `paired-8-80-after.json`, its `.summary.json`, and `.log`
  contain the second comparison. `run-pairs.py` replays alternating pairs:
  `python3 workspace/perf-improvements/stage1/run-pairs.py 8 80 12 after`.
- `isolation/` contains `EmitVariant.java`, its compiled harness, variant
  executables and LLVM, `timings.json`, `summary.json`, and `build.log`. It uses
  the baseline compiler jar, applies the pass to typed IR, then invokes the
  ordinary emitter and native backend. It does not edit LLVM semantics.
- `rejected/candidate.patch` and `rejected/files/` retain the implementation,
  test registrations, structural tests, native fixture and proposed docs.
  `rejected/source-sha256.txt` identifies the exact archived source. The patch
  remains available for investigation with its known local regression; it is
  not part of production behavior.
- `restored/` records the restored compiler/project rebuild and baseline IR
  comparison. `ARTIFACT_SHA256SUMS` identifies the matching before, candidate
  and isolation binaries, compiler jars and code evidence. `preexisting/`
  retains artifacts copied before the initial
  reproducible baseline build; measurements used `before/`, not these copies.

The tracked compiler, native runtime, project sources, test registrations,
authoritative optimization docs and decisions were restored to the starting
revision after verifying every file against the archived candidate. README
benchmark tables and `docs/BENCHMARK.md` were never changed. No commit, push,
branch switch, worktree, or additional task was created by this stage.

### Restoration verification

After removing the experiment, the Java 21 compiler and all three OrderBook
executables were rebuilt through the existing scripts. The restored
`-O3 -march=native` throughput build emitted byte-identical LLVM to the saved
baseline, and its `8 80` correctness run exited zero. This single post-build run
was not used as a performance sample. The focused baseline check
`static initialization lowers through typed IR and private LLVM state` was
rerun to refresh test artifacts and verify restoration. Final checks confirmed
that production tracked files match the starting revision, only this new report
remains, and whitespace/license checks pass. `restored/verification.json` and
`restored/focused-check.log` record the checks.

## Stage 2 pre-change review: guarded fully initialized specialization

Starting revision: `a4a7a9d47517717e922e1b5cd1140a65bbbd165b`, on the authorized
`perf-improvements` branch. The production baseline excludes the rejected
stage-1 pass. Stage 2 artifacts are separate under ignored
`workspace/perf-improvements/stage2/`; stage-1 evidence remains untouched.

### Contracts, implementation boundary and consumers

- A new typed operation may only read whether a type's private state is 2.
  It must not invoke initialization. States 0, 1 and 3 select the original
  body, with original active-use timing, prerequisites, partial observations,
  cached failure identity and exceptional cleanup. Zero-trip loops and untaken
  branches can perform a harmless state read but no new source side effect.
- State 2 is permanent in the current synchronous language. A guarded fast
  region can omit ensures of exactly the proven types, across ordinary calls
  and exceptional edges. Normal ensure completion supplies no such fact.
  Only final compiler-owned enum fields with immortal enum initial values may
  replace a load by that object's address, and only under their owner's state-2
  proof. Mutable fields and initializing enum publication retain normal loads.
- Specialize typed IR after semantic, ownership, effects and primitive generic
  validation. Do not alter safety acceptance in any unfreed mode. Original
  source, class payload and archive reconstruction share the final pipeline.
- Use bounded function-body versioning with entry state tests for profitable
  loop-containing roots and direct callees. Keep each root's two CFGs in one
  source function, so guarding adds no source trace frame. Guardless callee
  clones retain original source identities and spans but unique native linkage.
  Do not redirect virtual/interface dispatch without an existing direct target.
  Bound types, functions, body size and total copied instructions independently
  of application names; recursive call graphs must terminate deterministically.
- Consumers to inspect and cover: SSA values, phi predecessors, normal/unwind
  edges and unreachable cleanup; exhaustive IR visitors; direct call linkage;
  `ClosedWorldPruner` type/function/enum reachability; `LlvmEmitter` dispatch,
  source probes and public trace metadata; optimized native trace finalization.
  No wrapper frames, mutable trace state, TLS, allocations or per-operation
  bookkeeping may be introduced. D055, D132 and D133 remain behavioral contracts.

### Focused selection before implementation

New registered checks will cover typed fast/fallback structure, only-state-2
proofs, immutable enum identity, direct-callee propagation, recursive graph and
code-size bounds; and native cold/warm calls, zero-trip/untaken laziness,
recursive unpublished enums, cached failures, prerequisites and cleanup/traces.
Run existing exact checks for static initialization typed lowering, ordering
cycles/failures at O3, enum lowering and constant bodies, enum native semantics,
static/enum source-class-archive round trips, and exact O3 uncaught traces.
Pair safe compilation with unsafe free rejection in all three unfreed modes.

Before rebuilding preserve the baseline compiler, source-bearing classes,
OrderBook binary, emitted/optimized LLVM and actual disassembly. Run focused
registered tests, OrderBook correctness, license checks and `git diff --check`.
Inspect actual `-O3 -march=native` state loads, enum pointer loads, calls, spills
and size. Measure paired alternating and reversed official `8 80` runs. A
repeatable material local regression requires improvement or rejection with
evidence retained. Linux execution and speedup claims remain deferred.

## Stage 2 outcome: retain the guarded candidate for independent review

The implemented candidate passes the focused semantic, reconstruction, native
trace and OrderBook checks. Final alternating and reversed-start measurements
show small local median reductions of 0.33% and 0.80%, with overlapping timing
ranges and mixed individual pairs. This is evidence against the repeatable
material regression seen in stage 1, not a claim of a statistically established
or universal speedup. The executable grows by 16,816 bytes, or 7.51%. The
candidate remains enabled and uncommitted for coordinator review and stage 3's
independent audit. Linux execution is deferred to the maintainer.

### Implemented proof and bounded policy

`InitializedTypeSpecializer` runs in `CompilerPipeline` only after successful
semantic, ownership/effect and primitive-generic validation. The analysis-only
artifact-writing path remains unchanged; final source reconstruction applies
the same optimization. No stage-1 dominated-check elimination was re-enabled.

Each root has read-only typed state tests followed by two CFG alternatives in
one `IrFunction`. Only every required type comparing equal to 2 selects the
fast alternative. No guard invokes an initializer, writes state, allocates, or
produces a trace frame. All states 0, 1 and 3 select the original blocks, which
are retained without changing instructions, active-use positions, phi inputs or
exception edges. A cold call remains on that fallback for its whole activation,
even if its first iteration completes initialization. A later call can enter
the fast path. A zero-trip or untaken path can read private state but cannot
trigger new initialization work.

State 2 is permanent in Ironwood's synchronous execution model. Those entry
facts therefore survive direct calls, recursion and caught exceptions; a type
already at 2 cannot return to initializing or failed state. The pass infers no
fact from normal ensure completion, no prerequisite fact, and no return fact
from a callee. In the fast CFG it deletes only ensures for the entry-proven
types, repairs deleted invoke edges, removes unreachable cleanup blocks and
filters obsolete phi predecessors. An explicit exhaustive `IrCfgRenamer`
copies operands and labels while preserving spans. Guard prefixes and copied
body labels occupy separate collision-checked namespaces.

Internal direct calls can target guardless versions under the same facts.
Their native linkage is unique while their owner, source method, callable kind,
filename and spans remain original. LLVM's existing probe/inline metadata and
trace finalization remain unchanged. Dispatch tables retain original targets;
unresolved interface/virtual calls gain no speculative type-state fact.
Reachability explicitly handles the new state-test operation and direct enum
operands, retaining required classes, initializers, concrete enum objects,
publication fields and strings. Existing direct-call traversal retains clones.

An enum load becomes an `IrReferenceConversionInstruction` from the immortal
`IrEnumConstant` only when its owner is entry-proven state 2, the field is
final, and the complete typed program has exactly one store of that exact
object from the declaring class initializer. This is an identity proof, not a
claim that mutable fields inside the enum object are constant. Mutable static
references and unproved publication shapes retain loads. Recursive state-1
observers always enter the original path and keep null/unpublished observations.

The application-independent policy is documented in D171 and
`IMPORTANT_OPTIMIZATIONS.md`: loop-containing reachable method roots; at most
four guard types, 1,200 operations per body, 32 functions and 4,096 copied
operations per group; total copied-body budget at most 8,192 operations or half
the input operation count, whichever is smaller, plus bounded guard prefixes.
The estimated removable ensures/enum loads must be at least twice the guard
count. Deterministic local-benefit ordering chooses roots. A monotone type-set
fixed point and finite visited worklists handle recursive graphs, with one
callee version per group and an explicitly charged extra copy for a recursive
root. There is no application-name selection, new PGO, global inlining change,
eager initialization, runtime bookkeeping, array-layout or bounds-check change.

### Completed focused verification

All 13 distinct registered checks below pass. No unfiltered suite ran.

- `initialized specialization preserves guarded typed control flow`
- `initialized specialization preserves mandatory safety in every mode`
- `initialized specialization preserves lazy recursive and failed states at O3`
- `initialized specialization preserves exact callee traces and cleanup at O3`
- `initialized specialization survives source class and archive reconstruction`
- `static initialization lowers through typed IR and private LLVM state`
- `static initialization ordering cycles and failures run at O3`
- `enum constants initialization and switches lower through typed IR`
- `enum constant-specific bodies lower as immortal final subtype storage`
- `enum identity ordering lookup switching and initialization run at O3`
- `static initialization survives source-path class-path and archive round trips`
- `enums survive source class individual-file archive and tree-shaking round trips`
- `uncaught stack traces are stable at O3`

Structural assertions verify the fallback equals the original blocks, exactly
the intended types are guarded, recursive callees have bounded guardless
versions, source identities/spans and reachable phi predecessors survive,
mutable references remain loads, repeated publication prevents substitution,
oversized roots are skipped, ordinary ensures never imply state 2, and the pass
is idempotent. They also require actual guards in the native fixture's `lazy`,
`observe`, `enumValue`, `broken`, `ordered` and `warmed` methods, and the trace
fixture's `loop`, so profitability skipping cannot make these checks vacuous.
The reconstruction test requires its emitted guardless leaf in each source,
class-directory and archive build and executes cold, zero-trip and warm calls.
Safe allocation/free code is accepted, and a nearby live-alias free is rejected
before specialization, in `off`, `warn` and `error` unfreed modes.

The native state fixture exits 42 and checks first-use counts, zero iterations,
untaken branches, recursive partial zero/null values, nested initialization
failure followed by outer failure, exact repeated failure identity, superclass
and default-interface prerequisite ordering, recursive warmed callees, and
`finally` execution. The trace fixture warms an enum then throws from the
guardless leaf through the guarded loop, executes cleanup, and rethrows the
same object. Its exact trace is `Main.leaf:18`, `Main.loop:26`, `Main.main:37`,
with no synthetic or duplicate source frame. Both new fixtures also produce
identical exit codes, stdout and stderr using the preserved baseline compiler
and candidate compiler at O3.

The first trace assertion used a Java-style uncaught banner. The observed frames
and lines were correct; only that assertion was corrected to the existing
`uncaught Ironwood exception:` banner. The failing log and successful focused
retest are preserved. No production fix was needed for that assertion.

OrderBook compile/link/test scripts passed: four native tests, six Java tests,
throughput/latency argument checks, allocation-free collection and pool recovery,
and byte-identical native/Java reports. License audit and `git diff --check`
pass. The report, compiler/optimization docs and D171 are synchronized; published
README benchmark tables and `docs/BENCHMARK.md` remain untouched.

### Actual optimized code and size

Both builds use Java 21.0.1, LLVM 23.1.0, `-O3 -march=native`, and the unchanged
`default<O3>`, `-inline-threshold=1000`, `-enable-partial-inlining` pipeline.
The new baseline's emitted LLVM is byte-identical to stage 1's preserved
baseline. Disassembly is from actual linked macOS ARM64 executables.

The candidate selects `Bench.run` as one root with three tests: `Math`,
`Order.Side`, and `Order.Type`. It creates seven guardless callees, of which
`createLimit` and `match` survive as separate optimized native symbols; the
others inline. Native entry code has three `ldrb` state loads, one `cmp`, two
`ccmp` instructions and one conditional fallback branch. These reads execute
once per `run` activation. The fast loop materializes enum object addresses
with `adrp`/`add` and passes those addresses directly. Traversing its 28 optimized
LLVM blocks proves zero state loads and zero enum static-pointer loads after
the guard. The original fallback still has eight of each after LLVM inlining.

| Measure | Baseline | Guarded candidate |
| --- | ---: | ---: |
| `createLimit` optimized state / enum-pointer loads | 3 / 3 | 0 / 0 in clone |
| `match` optimized state / enum-pointer loads | 5 / 5 | 0 / 0 in clone |
| `createLimit` native instructions / conditional branches | 308 / 54 | 263 / 48 in clone |
| `match` native instructions / conditional branches | 316 / 52 | 204 / 39 in clone |
| `createLimit` stack frame / stack load-store instructions | 80 bytes / 13 | 48 bytes / 9 |
| `match` stack frame / stack load-store instructions | 112 bytes / 16 | 16 bytes / 2 |
| `createLimit` / `match` static native call sites | 38 / 24 | 36 / 16 in clones |
| Root native body instructions | 520 | 390 |
| Linked executable bytes | 223,872 | 240,688 |

Instruction, branch and call counts include cold error paths, not just executed
hot operations. Stack load/store counts include saved registers and should not
be read as dynamic spill counts. In particular specialized `match` retains only
the frame/link-register save and restore in its 16-byte frame. Baseline root
code was LLVM-outlined as `Bench.run.28.for.body.1.lr.ph`; the candidate's 390
instructions cover the combined `Bench.run` guard, fast and fallback body, so
that row is not a comparison of identical native scopes. The original callee
bodies remain for fallback calls, accounting for size growth despite smaller
fast callees. Bounds/null checks, object-field loads, ordinary calls and cold
throw helpers remain. No safety checks or trace bookkeeping contract changed.

### Final paired official-argument measurements

Each process executes the unchanged deterministic `8 80` workload and validates
counters and full pool recovery. The final candidate was rebuilt after all
source changes. Two 16-pair comparisons alternate execution order, with the
second comparison reversing the starting order. No compilation or native test
suite ran concurrently with these samples.

| Starting order | Baseline median ns | Candidate median ns | Candidate change |
| --- | ---: | ---: | ---: |
| Baseline first, then alternating | 570,909,000 | 569,053,000 | -0.33% |
| Candidate first, then alternating | 574,261,000 | 569,674,500 | -0.80% |

Candidate wins 8/16 pairs in the first comparison and 11/16 in the second.
Median within-pair percentage changes are -0.25% and -0.65%. First-comparison
ranges are 566,248,000 to 576,686,000 ns baseline and 560,003,000 to 583,836,000 ns
candidate. Reversed-start ranges are 567,954,000 to 585,033,000 ns baseline and
561,024,000 to 581,335,000 ns candidate. These overlapping samples support only
a small observed local benefit, with noise and layout effects unresolved. They
do not establish significance, broad-workload profitability or Linux behavior.

An earlier 12-pair preliminary build measured -0.80% median time. It is retained
separately in `after-initial/` and `initial-pairs/`, not mixed with the final
samples. The final policy was not tuned to application names or selected pairs.

### Stage 2 evidence and handoff

All paths below are under ignored `workspace/perf-improvements/stage2/`:

- `before/` and `after/`: compiler jars, source-bearing classes, runnable native
  binaries, raw and optimized LLVM, actual disassembly, extracted root/callee
  bodies, build/link logs, and baseline/candidate regression artifacts.
- `new-tests.log`, `focused-tests.log`, `retest.log`, `license-check.log` and
  `after/orderbook-tests.log`: exact checks, initial banner assertion failure,
  correction and passing results.
- `regression-comparison.json` and `run-regressions.py`: baseline/candidate
  exit/output/trace comparison for the two new native fixtures.
- `paired-8-80-before.json`, `paired-8-80-after.json`, matching `.summary.json`
  files and logs: all 32 final pairs. Reproduce from the canonical root with
  `python3 workspace/perf-improvements/stage2/run-pairs.py 8 80 16 before`, then
  the same command ending in `after`.
- `code-summary.json`, `code-evidence.py`, `verification.json`,
  `verify-evidence.py` and `after/Bench.run.fast.opt.ll`: static counts, fast CFG
  traversal, paired differences and baseline equivalence checks.
- `ARTIFACT_SHA256SUMS` and `source-sha256.txt`: retained binary/code evidence and
  exact changed-source identities. `env.sh` records the local Java/LLVM paths.

Baseline executable SHA-256:
`4d4f5a775dc5c6956c35b9d5d858fd3f12801b7ae53e4f91224e80fee53ca377`.
Candidate executable SHA-256:
`2368643693fbcc11f0d46581c8a3f8e5b8885b278aa66ab1703c9028a785d345`.
Baseline compiler SHA-256:
`64b4ab8352679179889492005e4a2f61d65ee3b57b25cf3ec24fe5b2781a5033`.
Candidate compiler SHA-256:
`5fd91de8e8d9827df579cfd9ae6713928239bcf37207111b284c085b2b21e97b`.

No commit, push, branch switch, worktree, extra task or subagent was created.
`COORDINATOR.md` and stage-1 artifacts remain untouched. Review must weigh the
small local timing benefit against code-size growth. Stage 3 should independently
audit adversarial initialization/enum/exception cases and the typed CFG copier;
stage 4 should validate the final committed implementation's machine code and
benchmark evidence. Neither later stage nor Linux execution is claimed complete.

## Stage 3 pre-change review: independent regression audit

Starting revision: `ad4cfc09820fec84f3e405609ce177fe9ca2becd`, on the authorized
`perf-improvements` branch. Stage 2 is now committed locally by the coordinator.
This stage leaves its changes uncommitted and keeps separate evidence under
ignored `workspace/perf-improvements/stage3/`.

The audit follows the state-2 producer through root guards, direct-call groups,
enum publication, CFG copying, pruning and LLVM emission. D055 requires unchanged
state-0 timing, prerequisites, recursive state-1 zero/null observations and
state-3 failure identity. Normal ensure completion is not a state-2 proof.
Mutable aliases and indirect dispatch must retain their original behavior.
D132/D133 require original trace identities and cleanup without added continuous
bookkeeping. Ownership validation must still precede specialization in every
unfreed mode; no ownership semantics are being changed.

Additional coverage will target actual specialized nested loops, switches,
break/continue, phi joins and exception/defer cleanup; recursive roots and mutual
recursion; callees shared by independently guarded groups; enum constant bodies,
mutable static aliases and unresolved dispatch; and independent guard/body/group
and total-copy bounds. Native fixtures will assert typed specialization first
to prevent profitability skipping from making semantic tests vacuous. Existing
reentrant/cached-failure fixtures will also run below O3 because the pass is
independent of backend optimization level.

Initial exact check selection:

- `initialized specialization preserves adversarial CFG and dispatch structure`
- `initialized specialization bounds recursive and shared clone groups`
- `initialized specialization preserves adversarial native behavior at O0 O2 and O3`
- `initialized specialization preserves recursive states and traces at O0 and O2`
- `initialized specialization preserves guarded typed control flow`
- `initialized specialization preserves mandatory safety in every mode`
- `initialized specialization preserves lazy recursive and failed states at O3`
- `initialized specialization preserves exact callee traces and cleanup at O3`
- `initialized specialization survives source class and archive reconstruction`

The existing safety check pairs accepted reclamation with rejected live-alias
free in all modes. New cleanup coverage will likewise pair safe captured cleanup
with unsafe double cleanup. Any demonstrated production defect will first be
retained against the committed compiler, then receive the smallest fix and
focused rerun.
Production-lowering changes additionally require OrderBook correctness, paired
measurements and optimized-code inspection. No full suite, hosted jobs or Linux
execution is planned here. Source changes require the license audit and every
change requires `git diff --check`.

## Stage 3 outcome: focused regressions, no production defect demonstrated

Independent inspection and the selected adversarial checks found no production
correctness defect in the stage-2 implementation. This stage adds four registered
checks, two native fixtures and this report. Compiler, runtime and library source
remain byte-for-byte unchanged from the starting commit. Every entry in the
rebuilt compiler jar matches the preserved committed build, ignoring ZIP container
timestamps. This is a bounded audit, not a claim that all bugs are eliminated.

### Audit findings and proof boundaries

- Pipeline placement remains after semantic, ownership/effect and primitive
  generic validation. Artifact writing retains unspecialized source-bearing
  classes, and final reconstruction reapplies the pass. The new state test is a
  boolean read of exactly state 2. It neither invokes nor writes initialization.
  Earlier ownership visitors never consume specialized output; the exhaustive
  CFG copier and generic/borrow visitors account for the new instruction.
- The permanence proof is valid for the current synchronous state machine.
  Direct-call demand propagation is a finite union; neither ordinary ensures
  nor indirect targets create a completed-state fact. Root fallbacks are retained
  verbatim. Removed ensure invokes lose only their now-impossible unwind edges;
  unreachable blocks and obsolete phi inputs are removed before renaming.
- Enum address substitution requires a final compiler-owned enum field and one
  exact-object publication store in its declaring initializer. It does not
  freeze object contents or mutable aliases. Pruning follows direct enum operands
  into publication storage, constant-specific subtypes and names. Ordinary
  dispatch tables keep original targets; source identity and spans survive in
  direct clones. No new runtime state or per-operation bookkeeping was added.
- CFG review covered every current instruction and terminator case in
  `IrCfgRenamer`, including nested operands, optional results, phi predecessor
  labels, switch cases, landing-pad values and throw/invoke destinations. Native
  LLVM assembly and optimization check the emitted SSA at O0, O2 and O3.

### Added coverage and exact results

All nine checks listed in the stage-3 pre-change selection passed. Only selected
checks ran. The four additions are implemented in
`compiler/src/test/java/ironwood/compiler/InitializedTypeAuditTests.java`.
The license audit and `git diff --check` pass.

`initialized_specialization_control.iron` exercises nested loops, integer switch,
break/continue, mutable joins, explicit throw/catch, finally, captured deferred
calls and deferred free. Cold and warm calls produce the same checked sums;
caught failure preserves object identity, captured cleanup records `123`, and
live allocations return to the pre-call count. Mutable enum aliases change
between iterations and between activations; both constant-specific method bodies
remain reachable. An unresolved interface target reaches a previously untouched
initializer only when actually called, including from a warmed guarded root.
Zero iterations and untaken dispatch leave that initializer untouched.

Typed assertions require guards in `control` and `dispatch`, a fast switch,
invoke, phi and deferred free, preserved mutable loads, and retained indirect
dispatch. `control` guards `Main`, `Mode` and `Mutable`; `dispatch` guards only
`Main` and `Mode`, never `Hidden`. The latter's barrier stays inside `Late.get`.
The combined control CFG contains 204 blocks and 36 phis. Assertions compare
every phi's actual predecessor set, reject dangling or duplicate labels, and
require copied SSA locals to be disjoint from fallback locals. A second typed
case deliberately collides with the generated label prefix. Reversible renaming
also verifies that source metadata and non-name instruction data survive.

`initialized_specialization_graph.iron` exercises a loop-containing recursive
root, its mutually recursive callee, and a shared leaf used by two independent
groups with different guard sets. All three roots must specialize. The shared
leaf has two distinct retained versions; the recursive root and mutual callee
have one guardless version each. Recursive edges target those versions without
repeating entry guards. Cold and warm recursive calls both return 14.

Boundary checks accept a 32-source-function group and reject 33, accept four
guard types and reject five, and isolate the 4,096-operation group limit while
each body remains below 1,200 and the total budget remains sufficient. A 20-root
case selects 16 roots, adding 6,560 body operations plus 32 guard operations
under the 8,192-operation budget. The existing oversized-body regression also
passes. Recursive root copies remain charged to the operation budget.

The two new native fixtures pass at O0, O2 and O3, each exiting 42 with empty
stdout/stderr. Each native check first asserts the required typed guards and
then checks reconstructed LLVM for specialization. All six configurations also
match executables built with the preserved pre-stage-2 compiler, whose LLVM has
no specialization. The existing recursive/failed-state fixture and exact
three-frame callee trace pass additionally at O0 and O2; their O3 checks pass too.
Those cases cover prerequisites, state-1 zero/null publication, caught nested
initializer failure, cached failure identity, zero-trip paths and exact cleanup.
The existing source/class/archive check passes with its explicit specialized-leaf
assertion in all three reconstructed builds.

Safe captured cleanup is accepted and a nearby explicit-plus-deferred double
free is rejected before specialization in all three unfreed modes. The existing
accepted allocation/free and rejected live-alias pair also passes in every mode.
No ownership or dispatch-analysis fix was needed, so additional unrelated pool,
generic or library suites were not selected.

Two test-authoring corrections were necessary. The new enum/interface overrides
initially omitted required `@Override` annotations. The first structural dispatch
expectation also omitted the valid `Main` guard required by its initialized static
helper. Both failures were in the new fixture/assertion; production code was not
changed. Original failure logs and focused successful reruns are retained.

### Evidence and handoff

Stage-3 evidence is under ignored `workspace/perf-improvements/stage3/`:

- `new-tests-initial.log`, `control-retest.log`, `focused-tests.log` and
  `final-structure.log`: exact registered checks, authoring failures and reruns.
- `candidate/`: source-bearing classes, emitted LLVM, executables and captured
  output for the new fixtures at O0/O2/O3 and existing state/trace fixtures at
  O0/O2. Existing O3 and source/class/archive results are in the focused log.
- `reference/`, `compare-reference.py` and `reference-comparison.json`: six
  pre-stage-2 versus candidate comparisons, with compiler/link logs and binaries.
- `AuditEvidence.java` and `typed-evidence.txt`: root/clone identities, guards,
  CFG/phi counts, enum substitutions and additional-operation measurements.
- `committed-compiler.jar`, `source/`, `source-sha256.txt`, `verification.json`
  and `ARTIFACT_SHA256SUMS`: preserved compiler, exact test sources and evidence
  identities. `license-check.log` and `diff-check.log` record final audits.

No production lowering changed, so this stage did not repeat OrderBook timings
or machine-code measurements; stage 4 owns validation of the final reviewed
candidate. Stage-2 code-size growth and noisy local timing remain unresolved
tradeoffs. Linux execution and performance remain unverified. The tests cover
representative transformation boundaries, not every IR operation or possible
control-flow graph, and the permanence proof still depends on synchronous
execution without threads or suspension.

Changes are uncommitted for coordinator review. No branch switch, worktree,
commit, push, new task or subagent was created. Stage-1/stage-2 artifacts,
`COORDINATOR.md`, README benchmark tables and `docs/BENCHMARK.md` are untouched.

## Stage 4 plan: final code validation and Linux handoff

Starting revision: `58671134cdbed446457cb67c7e8124f643669d07`, on the explicitly
authorized local `perf-improvements` branch. Production baseline:
`2bca0b4a0ca93f2d7937d6257dc2dae5ebc4a29e`. This stage changes no compiler
behavior. It leaves reviewable uncommitted scripts/documentation for the
coordinator, with new evidence under ignored `workspace/perf-improvements/stage4/`.

1. Verify saved baseline compiler/classes/code hashes and reuse them without
   modifying earlier evidence. Rebuild final Bench and LatencyBench with Java
   21, LLVM 23, `-O3 -march=native`, no PGO. Confirm production lowering still
   equals stage 2 and run existing OrderBook correctness and report parity.
2. Collect two alternating official `8 80` throughput comparisons with reversed
   starting order, initially eight pairs each, plus four alternating official
   `10000 50000 1000` latency pairs. Preserve every raw output, command, hash and
   summary. No builds/tests run during measurement. Report ranges and paired
   changes without significance or Linux claims; repeat only for a concrete
   failure or unresolved measurement issue.
3. Inspect actual linked ARM64 entry guards, fast/fallback loops, state and enum
   pointer loads, calls, frames, size and surviving null/bounds checks. Attempt
   representative x86 code generation using identical explicit target/CPU
   settings without rewriting target-sensitive IR or claiming Linux execution.
4. Add a small Python 3.6-compatible paired runner and Linux instructions with
   fresh output directories, strict failure/timing validation, optional CPU
   affinity and all samples retained. Test small fixtures, failures, paths with
   spaces and real short runs. Explain an offline bundle transfer and pinned
   source builds of both revisions using the same Linux Java/LLVM toolchain.
5. Run focused license/whitespace checks and record exact results and artifact
   identities. No full suite, remote access, installation, branch/worktree
   changes, commit, push, further task, or stage-5 optimization is authorized.

## Stage 4 outcome: validated candidate, small noisy gains and material size cost

The final committed production candidate passes OrderBook correctness and
report parity. Its throughput machine code matches the reviewed stage-2
candidate exactly. Two new eight-pair throughput comparisons have median times
1.64% and 0.28% lower than baseline; four latency pairs have a median batch mean
0.21% lower. Ranges overlap and individual pairs disagree. This supports keeping
the result as a local experimental candidate for the maintainer's Linux test,
not a statistically established, broad-workload or Linux performance claim.

The size tradeoff is significant: the throughput executable grows 7.51%, and
the newly measured latency executable grows 50.91%. Latency's executable text
nearly doubles as multiple workload and reporting groups specialize. No further
compiler tuning was attempted. Any acceptance beyond the experimental branch
should weigh that cost against the small observed gains. No new correctness
defect was demonstrated, and stage 5 remains deferred.

### Build identity and correctness

The host is Apple M5, macOS 26.6.2 ARM64, Java/Javac 21.0.1 and Homebrew LLVM
23.1.0. Both variants use `-O3 -march=native`, the existing `default<O3>` pipeline,
`-inline-threshold=1000`, partial inlining and no PGO. No toolchain was installed.

All 227 hashes in the stage-1, stage-2 and stage-3 manifests verified before
work. The eight source-bearing baseline classes match between stages 1 and 2.
Baseline throughput binary/compiler/classes were copied into stage 4 without
changing earlier evidence. Baseline LatencyBench was linked using that saved
compiler and those classes. Runtime, stdlib and OrderBook sources are unchanged
from the original production baseline. Candidate compiler source is unchanged
from stage 2; the rebuilt jar has identical member contents to stage 2, with
different archive metadata. Candidate project classes and raw throughput LLVM
also match stage 2. Actual linked throughput disassembly is identical after
excluding its filename banner. The report records the new executable hash,
rather than assuming unchanged machine code implies an unchanged file hash.

`scripts/build.sh`, OrderBook compile/link scripts and `projects/OrderBook/test.sh`
passed: four native tests, six Java tests, throughput/latency argument checks,
allocation-free collection, pool recovery, warmup exclusion, report bucket
selection and byte-identical native/Java reports. Stage 3 already covered
compiler initialization, trace, reconstruction and mandatory memory safety;
this report/script-only stage did not repeat those suites. No full suite ran.

### All official local samples

Each throughput process executes 8 million warmup and 80 million measured
operations, or 1 million and 10 million eight-operation cycles. Its output is
the internal measured duration. Each process also verifies workload counters
and complete pool recovery. The first eight-pair comparison starts baseline
first and alternates; the second starts candidate first and alternates. No
builds/tests ran concurrently. All 32 process samples are retained, including
slower candidate results; no additional official throughput run was selected.

| Eight-pair comparison | Baseline median ns | Candidate median ns | Median change | Candidate lower pairs |
| --- | ---: | ---: | ---: | ---: |
| Baseline first | 572,490,500 | 563,129,000 | -1.64% | 6/8 |
| Candidate first | 570,865,500 | 569,269,500 | -0.28% | 5/8 |

Baseline-first ranges are 566,658,000 to 578,717,000 ns baseline and 557,340,000
to 579,746,000 ns candidate. Reversed-start ranges are 566,496,000 to 577,270,000
ns baseline and 561,466,000 to 578,708,000 ns candidate. Median within-pair
changes are -1.54% and -0.32%. Between-run variability is large relative to the
observed gain; these samples do not provide a significance estimate.

Four alternating latency pairs use `10000 50000 1000`: 10,000 warmup batches and
50,000 measured batches, each with 1,000 eight-operation cycles. Every process
therefore executes 80 million warmup and 400 million measured operations.
Reported latency describes an 8,000-operation batch, including its clock reads,
not one order. Clock calibration, setup, validation, histogram work and printing
are outside batch intervals. All eight full reports, stderr and exit statuses
are retained; no latency sample or tail was removed.

| Four-pair latency measure | Baseline | Candidate |
| --- | ---: | ---: |
| Median of reported per-process mean batch times | 57.3065 us | 57.188 us |
| Range of mean batch times | 57.205 to 57.843 us | 56.506 to 57.451 us |
| Range of per-process maxima | 151 to 205 us | 113 to 277 us |

The median mean change is -0.21%; the median within-pair mean change is -0.10%,
with the candidate lower in 3/4 pairs. The candidate also has the largest
individual maximum, 277 us. The reported 99% bucket maxima span 63 to 65 us
baseline and 64 to 65 us candidate; 99.9% maxima span 70 to 84 us and 71 to 87 us.
Four process repetitions, rounded reports and the observed 1,000 ns smallest
positive clock delta do not establish a tail-latency improvement. Full six-tail
reports and clock calibration outputs are preserved for inspection.

### Actual ARM64 code and size

The linked throughput `Bench.run` entry loads three state bytes (`Math`,
`Order.Side`, `Order.Type`), performs `cmp`/two `ccmp` operations and branches to
fallback unless all equal 2. The fast loop forms enum addresses with `adrp/add`
and calls the specialized `createLimit`/`match` bodies. Traversal of all 28
reachable optimized fast blocks finds zero initialization-state loads and zero
enum static-pointer loads. Both specialized callees also have zero of either;
baseline `createLimit` has 3/3 and `match` 5/5. Fallback bodies remain available.

| Actual ARM64 symbol measure | Baseline | Candidate fast callee |
| --- | ---: | ---: |
| `createLimit` instructions / conditional branches | 308 / 54 | 263 / 48 |
| `match` instructions / conditional branches | 316 / 52 | 204 / 39 |
| `createLimit` static calls / frame bytes | 38 / 80 | 36 / 48 |
| `match` static calls / frame bytes | 24 / 112 | 16 / 16 |
| `createLimit` stack load/store instructions | 13 | 9 |
| `match` stack load/store instructions | 16 | 2 |

These counts include cold throw paths and register saves/restores, not dynamic
operation or spill counts. The throughput root changes from LLVM's 520-instruction
outlined baseline body with a 112-byte frame to a 390-instruction combined
guard/fast/fallback root with a 96-byte frame. These are different native scopes,
so their raw count difference is not a direct loop instruction saving.

Null checks (`cbz`), signed-negative index checks (`tbnz` bit 31), unsigned
length checks (`cmp`/`b.ls`), field loads, pool mutations and ordinary calls
remain. For example the fast `createLimit` checks pool availability, array
nullness, negative index and upper bound before loading its order. Fast `match`
still reads the selected enum's index field. Direct enum identity substitution
does not make mutable object fields constant. No new steady-state allocation,
TLS access, trace maintenance or registry lookup appears in these regions.

LatencyBench inlines collection and cycle work into `main`. Actual code retains
the outer guards and, in the general collection route, a three-byte state test
after the opening clock read for each batch. Once initialized, the inner cycle
uses direct enum addresses and guardless callees. The guarded collection route
can instead reuse its entry proof across batches. This is not a claim that all
latency-path state reads disappear. Main's frame is 208 bytes baseline versus
192 bytes candidate; candidate main has 4,726 instructions versus 3,681, and
14 surviving specialized native symbols. The raw typed roots include `main`,
`collect`, `Bench.run`, `Bench.results` and `Bench.appendPercentile`; different
groups retain multiple `createLimit`, `match`, `appendTime`, `appendPercentile`
and `String.formatFixed` versions.

| Linked ARM64 size | Baseline | Candidate | Change |
| --- | ---: | ---: | ---: |
| Throughput executable | 223,872 B | 240,688 B | +7.51% |
| Throughput `__text` | 21,516 B | 24,012 B | +2,496 B |
| Latency executable | 360,352 B | 543,792 B | +50.91% |
| Latency `__text` | 43,532 B | 84,108 B | +40,576 B |
| Latency `__DATA_CONST,__const` | 138,240 B | 238,112 B | +99,872 B |
| Latency probe sections combined | 38,642 B | 68,607 B | +29,965 B |

Executable growth includes immutable metadata, exception/trace information,
alignment and linker contents as well as executable instructions. LLVM probes
provide static metadata here, not continuously executed trace bookkeeping.

### Representative x86-64 code, with explicit limitations

LLVM 23 successfully optimized the unmodified emitted throughput modules with
`-mtriple=x86_64-unknown-linux-gnu -mcpu=x86-64`, the same O3/partial-inlining
settings for both variants. The raw modules have no host triple or data layout;
LLVM selected the explicit x86-64 layout. No ARM-native CPU flags or rewritten
target-sensitive IR were used. The existing Java `OptimizedTraceMetadata.inject`
helper finalized metadata with Linux symbol naming (`false`), then `llvm-as`
and `llc -O3 --relocation-model=pic` generated assembly and ELF objects using
those same explicit target/CPU flags. Disassembly includes relocations.

This is a generic x86-64 code-generation comparison, not the user's CPU's
`-march=native` result. No Linux runtime, C library or linker was used, and the
objects were neither linked nor executed. It does not validate Linux ABI
integration, stack unwinding, correctness or performance. Previously supplied
Linux executables were not relabeled or reused as candidate binaries.

The candidate x86 root uses three RIP-relative `cmpb $2`/`jne` tests, direct
RIP-relative `lea` enum addresses and calls to specialized callees. Its 72
reachable optimized fast blocks contain zero state and enum-pointer loads.
Null `test`/`je`, negative-index `test`/`js`, and unsigned bounds `cmp`/`jbe`
checks remain. Static counts include cold paths and alignment instructions:

| x86-64 ELF object symbol measure | Baseline | Candidate fast callee |
| --- | ---: | ---: |
| `createLimit` instructions / branches / calls | 318 / 54 / 38 | 268 / 48 / 36 |
| `match` instructions / branches / calls | 303 / 52 / 24 | 211 / 39 / 16 |
| `createLimit` stack adjustment excluding return address | 56 B | 24 B |
| `match` stack adjustment excluding return address | 72 B | 40 B |

The baseline outlined root has 522 instructions and 72 bytes of stack adjustment;
the combined candidate root has 678 and 88 bytes. More cancellation work is
inlined on x86 than ARM, so smaller callees do not imply a smaller root or less
stack use everywhere. Object `.text` grows from 9,140 to 12,564 bytes; the separate
378-byte unlikely text section is unchanged. These are program objects without
the linked runtime and must not be compared as Linux executable sizes.

### Reusable Linux handoff and runner verification

[OrderBook performance comparison instructions](../projects/OrderBook/PERFORMANCE_COMPARISON.md)
give the exact post-commit bundle creation/verification commands, offline
transfer, fresh source extraction, pinned baseline/final revision builds, paired
runs, optional CPU affinity and small optional `perf` groups. They keep both
binaries before measuring and require the same existing Linux Java 21/LLVM 23
toolchain. The compact incremental bundle uses
`2bca0b4a0ca93f2d7937d6257dc2dae5ebc4a29e..refs/heads/perf-improvements`,
avoiding transfer of the repository's approximately 1.85 GiB packed history.
It requires the baseline in the user's existing complete Linux clone and is
not a standalone clone source. A read-only baseline preflight precedes a fresh
`--local --no-checkout --dissociate` clone; the existing clone remains untouched.
The fresh repository verifies the bundle, fetches its branch into `FETCH_HEAD`,
checks that hash against the transferred `.revision`, and extracts both pinned
source archives. If the baseline is missing, obtain a separate complete clone
containing it before retrying. No unpushed remote branch is needed.
No provisional pre-stage-4 bundle was created; the coordinator must create it
after committing these scripts/instructions so they are included.

`projects/OrderBook/compare.py` validates subprocess status and throughput's
positive integer ns, checks latency counts and timing units, preserves every
stdout/stderr and command, records/rechecks executable hashes, and rejects
existing output directories. Failures retain partial evidence. Optional Linux
`taskset` applies the same explicit CPU to both variants. No samples are trimmed.
The script neither builds nor modifies binaries or Git state. Reported percent
changes are descriptive; zero-baseline minimum ratios are explicitly `null`.

Five focused fixture tests cover alternating/reversed order, paths with spaces,
argument forwarding, hashes/summaries, existing-output protection, failed and
empty/non-numeric/zero/negative/fractional/extra-text timing output, timeout,
missing executable, invalid/overflowing arguments, latency units and count
mismatches. The initial zero-minimum latency fixture found a division-by-zero
in summary generation. The runner was corrected and that test passed on rerun;
the other four tests had passed. The initial failure log is retained. Two real
short pairs each of throughput (`0 1`) and latency (`2 5 1000`) also passed,
followed by all official runs. Python 3.6 grammar and documented Bash syntax
were checked locally; actual Python 3.6 and Linux affinity execution remain
unverified. The license audit and explicit new-Python SPDX checks pass.

### Stage 4 evidence and exact identities

All evidence is in ignored `workspace/perf-improvements/stage4/`:

- `baseline/`, `candidate/`: preserved compiler/classes, Bench/LatencyBench
  executables, raw/optimized LLVM, actual disassembly, extracted functions,
  build logs and `x86-64/` assembly/ELF objects with section reports.
- `throughput-baseline-first/`, `throughput-candidate-first/`,
  `latency-baseline-first/`: all official stdout/stderr, `metadata.json`,
  `samples.jsonl` and `summary.json`; matching top-level logs retain runner output.
- `short throughput/`, `short latency/`, `runner-tests.log`, `runner-retest.log`,
  and `orderbook-tests.log`: real smoke samples and focused verification.
- `environment.txt`, `build-commands.json`, `code-commands.json`,
  `build-code.py`, `FinalizeTrace.java`, `inspect-code.py`, `code-summary.json`,
  `arm64-sections.txt`, `latency-code-summary.json`, `latency-machine-summary.json`:
  exact local tools, code-generation commands, inspection helpers and counts.
- `preservation-verification.json`, `build-verification.json`,
  `additional-verification.json`: earlier manifest/class verification, compiler
  member/LLVM/code equality, class hashes and full tail extracts.
- `ARTIFACT_SHA256SUMS`, `source-sha256.txt`, `license-check.log`,
  `syntax-check.log`, `diff-check.log`, `final-verification.json`: final evidence
  identities, new source identities and focused audits.

| Artifact | SHA-256 |
| --- | --- |
| Baseline compiler | `64b4ab8352679179889492005e4a2f61d65ee3b57b25cf3ec24fe5b2781a5033` |
| Candidate compiler | `e93002b446bd89022729b334550e6dc83d3aa6afdecc4880038b9201d316e565` |
| Baseline throughput | `4d4f5a775dc5c6956c35b9d5d858fd3f12801b7ae53e4f91224e80fee53ca377` |
| Candidate throughput | `9d0b04d16394427daeb215603a21fbbcf9cd6e7ed7a0068b999ad94737495442` |
| Baseline latency | `a10c5510d6382681437d786aaec66a3f00c67dfed9904218dd958e9c74b38352` |
| Candidate latency | `adf84bef260ae1c170a5a6f01f4333305e52cc5dcbf11fb28cae443f06723858` |

Changes remain uncommitted for coordinator review on `perf-improvements`.
`git diff --check` passes. Production compiler, earlier evidence,
`COORDINATOR.md`, README benchmark tables and `docs/BENCHMARK.md` are unchanged.
No branch switch, extra checkout/worktree, commit, push, remote execution,
new task or subagent occurred. Stage 4 is the final authorized overnight stage.

## Stage 5 pre-change review: array bounds and access costs

Fresh maintainer authorization supersedes the earlier stage-4 stopping point.
The measured baseline for this stage is `6ca8c1047e7ed9148769f08b6321e7e98f4c99eb`,
on the existing `perf-improvements` branch. Root, fetch/push URLs, branch, HEAD
and clean tracked state were verified, and origin was fetched without changing
main. Baseline compiler/classes, stdlib, Bench/LatencyBench and code evidence
are preserved in ignored `workspace/perf-improvements/stage5/baseline/` before
any rebuild; `preserved-sha256.json` identifies all 864 preserved files.

### Contracts and proposed experiment

- `FunctionAnalyzer.resolveArrayTarget` captures the array and promoted int
  index, emits the null check, then the bounds predicate. Assignment/update
  evaluation and exception edges stay in typed IR. Do not reorder operands,
  move checks across side effects, or change the current RHS evaluation order.
- Every published array length is in `[0, Integer.MAX_VALUE]`. Runtime
  `try_allocate_array` takes a nonnegative `int32_t`; process arguments and
  runtime-created arrays use this boundary. `read_file_result` may grow private
  storage and write its final length before publication, but limits byte count
  to `INT32_MAX`. Source `.length` is read-only. The existing 64-bit header and
  allocation ABI remain unchanged.
- Test one unsigned 32-bit bounds comparison after loading and truncating the
  64-bit length. For valid lengths, every negative int maps to an unsigned
  value at least `2^31`, larger than any length. Nonnegative values preserve
  their mathematical value. Thus `unsigned(index) < length` is exactly the
  current signed-nonnegative AND unsigned-upper-bound predicate, including
  empty arrays, `INT_MIN`, `INT_MAX`, and wrapped arithmetic. This is a lowering
  of the existing typed predicate, not a new frontend proof or missing check.
- Array references and elements remain mutable. No alias assumption, loop
  length hoist, invariant-load metadata, new no-wrap flag, speculative access,
  or reuse of a check for a different captured array is authorized by this
  proof. Bounds checks after field replacement must use the new array. Null,
  bounds, negative-size and cleanup exceptions retain their existing spans and
  priority. Ownership and mandatory rejection of unsafe frees remain unchanged
  in every unfreed mode; no exemption, allocation, TLS or bookkeeping is added.
- All primitive/reference/nested arrays, pools and collections consume the
  common predicate. Source compilation and class/archive reconstruction feed
  the same typed IR and LLVM emitter. Initialized-state specialization and CFG
  renaming retain the same bounds instruction and source identity.

### Focused verification selection, before implementation

Use exact `scripts/test.sh --test` selections: `arrays lower to inspectable typed
IR and LLVM`, `array types and indices are checked`, `safe free accounts for
reference-array element aliases`, `uncaught array failures report deterministic
source traces`, `catchable implicit runtime failures run at O3`, and `implicit
runtime failures survive source class and archive round trips`. Add a focused
edge fixture if retained: valid first/last accesses and zero-length traversal
paired with empty/negative/equal-length/INT_MIN/INT_MAX/overflow failures;
side-effecting index/null precedence; changed array references; primitive,
reference and nested accesses. Exercise that fixture at O0/O2/O3 and through
source/class/archive consumers, and compare against the preserved compiler.
Existing focused OrderBook correctness/report parity covers pooling and valid
path allocation behavior. Run licenses and `git diff --check`; no full suite.

Inspect actual linked ARM64 O3 code and explicitly targeted x86-64 code,
including residual length loads, sign extensions, branches and loop bounds.
Use the existing runner for eight alternating official `8 80` throughput pairs
in each starting order and four official `10000 50000 1000` latency pairs.
Retain every sample and failure. Reproduce the simple 64-bit merged predicate
if needed to distinguish it from narrowing. Reject a consistently slower or
unsupported candidate instead of forcing a production change. Linux runtime
performance for this stage remains unverified until a fresh user-run comparison.

The first narrowing experiment passed all six selected checks but did not show
a convincing local win. Its linked ARM address arithmetic uses signed extension
because LLVM no longer sees the separate nonnegative predicate. A bounded second
experiment will expose the already-proven length range on the bounds length
load (`!range`), allowing LLVM to infer a nonnegative index on the successful
edge. This does not assert immutability or move a load across a mutation. The
same producer proof and focused verification apply; record both experiments.

## Stage 5 outcome: three rejected lowerings, production unchanged

No stage-5 compiler optimization is retained. Three equivalent predicates
passed the focused edge checks and removed negative-index branches, but their
local measurements do not support replacing the current lowering. `narrow32`
has mixed throughput and worse median batch latency; `range32` improves the
four-pair latency median while regressing both throughput medians; `wide64`
regresses throughput in 15/16 pairs and has worse median batch latency. The
compiler and test-assertion edits were removed with focused reverse patches.
Only this report remains changed, uncommitted for coordinator review.

The earlier user-run Linux comparison belongs to the stage-5 **baseline**:
`6ca8c10` versus `2bca0b4`, with 3.15% lower pooled median throughput time and
2.72% lower median of per-process mean batch latency. Current Ironwood and
no-PGO Native Image were effectively tied on throughput; Ironwood's measured
batch mean was 2.69% lower. These coordinator-validated results are context,
not measurements of any stage-5 experiment. Published tables remain unchanged.

### Experiments and proof boundaries

All three use the existing `IrArrayBoundsCheckInstruction`, its captured
operands, normal/exception CFG edges, source spans and unchanged 64-bit array
header. They are original small emitter experiments, with no upstream import:

| Experiment | Predicate after loading the 64-bit length |
| --- | --- |
| `narrow32` | `icmp ult i32 index, trunc(length)` |
| `range32` | Same, with `!range !{i64 0, i64 2147483648}` on that load |
| `wide64` | `icmp ult i64 zext(index), length`, without a separate sign test |

For `wide64`, negative indices become values from `2^31` through `2^32-1`, all
above the largest published length. The same range proof makes narrowing exact
and justifies the metadata in `range32`. Range metadata asserts a value range
at the load; it does not assert immutable storage. No no-wrap flag, lifetime
assumption, check hoist or alias exemption was added by the emitter. Existing
LLVM optimizations may derive their own facts after the unchanged safety edge.

The length-producer audit includes allocation, process arguments, file metadata,
network snapshots and `read_file_result`'s private growable result. The last
path sets the final header length before publication after enforcing
`byte_length <= INT32_MAX`. Actual maximum-sized allocation was not attempted;
the full range claim comes from these producer constraints and the arithmetic
proof, supplemented by extreme-index tests on small arrays.

The plain 64-bit experiment is a fresh reproduction of the reported strategy,
not a claim to reproduce an unavailable historical patch byte for byte.
It was built by recompiling only the emitter against the saved compiler into
an isolated jar in ignored workspace. The two narrowing experiments were built
with `scripts/build.sh`. Every benchmark links the same preserved application
classes using `-O3 -march=native`, the unchanged O3/partial-inlining settings
and no PGO. No workload or global inline threshold changed.

### All local runtime evidence

Host: Apple M5 ARM64, macOS 26.6.2, Java/Javac 25.0.4.1 with `--release 21`,
LLVM 23.1.0 and Python 3.14.7. The preserved stage-4 compiler was built with
Java 21.0.1; the current default JDK was already 25 when this stage ran. No
toolchain or global setting was changed. The restored source built with this
JDK emits byte-identical Bench/LatencyBench LLVM and identical disassembly to
the preserved baseline, despite Java class-file differences. Each
experiment has two eight-pair throughput comparisons, alternating within each
comparison and reversing the starting variant between them, followed by four
alternating latency pairs. Arguments are exactly `8 80` and
`10000 50000 1000`. Builds/tests/code generation did not overlap measurement.
The existing comparison runner is unchanged. All 120 process samples, including
outliers, full latency reports, commands, binary hashes and stderr, are retained.
There was no additional official rerun chosen to improve an experiment's result.

| Experiment / first variant | Baseline median ns | Experiment median ns | Time change | Experiment lower pairs |
| --- | ---: | ---: | ---: | ---: |
| narrow32 / baseline | 552,379,500 | 550,668,000 | -0.31% | 4/8 |
| narrow32 / experiment | 552,077,500 | 556,136,000 | +0.74% | 1/8 |
| range32 / baseline | 552,349,500 | 562,512,000 | +1.84% | 0/8 |
| range32 / experiment | 560,028,500 | 563,166,500 | +0.56% | 4/8 |
| wide64 / baseline | 560,865,500 | 574,931,500 | +2.51% | 1/8 |
| wide64 / experiment | 563,365,000 | 578,714,000 | +2.72% | 0/8 |

Median within-pair changes are respectively -0.07%, +0.52%, +1.61%, +0.40%,
+3.13% and +2.99%. Baseline/experiment ranges in the same row order, in ns:
539,219,000-564,659,000 / 549,368,000-558,335,000;
547,666,000-557,625,000 / 549,600,000-562,330,000;
549,573,000-572,978,000 / 559,061,000-579,372,000;
545,719,000-567,532,000 / 555,274,000-576,723,000;
554,588,000-628,177,000 / 568,967,000-593,107,000;
550,813,000-572,583,000 / 561,067,000-584,636,000.

| Four-pair batch latency | Baseline median mean us | Experiment median mean us | Change | Experiment lower pairs |
| --- | ---: | ---: | ---: | ---: |
| narrow32 | 55.685 | 56.3535 | +1.20% | 1/4 |
| range32 | 56.3455 | 56.0245 | -0.57% | 4/4 |
| wide64 | 56.5695 | 57.5065 | +1.66% | 1/4 |

The ranges of reported process means are 55.526-56.220 / 55.600-57.025 us,
55.940-57.392 / 54.521-56.796 us, and 55.719-56.900 / 56.609-58.205 us.
These are 8,000-operation batch measurements, not single-order latency. Tails
are particularly noisy: the range32 comparison contains a 5,659 us baseline
maximum and a 2,993 us experiment maximum. Narrow32 has an 888 us maximum and
wide64 a 1,337 us maximum. None was dropped. Baseline medians drift between
comparison periods, so absolute times should not rank the experiments across
periods. No confidence interval, tail improvement, universal speed claim or
stage-5 Linux runtime improvement is established.

### Optimized code and remaining costs

Actual linked ARM64 disassembly confirms that all three remove the separate
`tbnz` negative-index checks while preserving null and upper-bound branches.
Narrowing changes the length read/compare from 64 to 32 bits. Without range
metadata, address arithmetic needs signed extension (`sxtw`); `range32` lets
LLVM use unsigned extension (`uxtw`). This is observable code evidence, not a
proven explanation of the timing differences. All three retain the same call
counts and the original 96/48/16-byte frames for the following hot symbols.

| Actual ARM symbol | Baseline instructions / conditional branches | narrow32 | range32 | wide64 |
| --- | ---: | ---: | ---: | ---: |
| Bench.run, including fallback | 390 / 49 | 382 / 41 | 381 / 41 | 382 / 41 |
| specialized createLimit | 263 / 48 | 258 / 43 | 258 / 43 | 259 / 43 |
| specialized match | 204 / 39 | 198 / 33 | 198 / 33 | 199 / 33 |

Counts include cold paths, not dynamic operations. Throughput `__text` is
24,012 / 23,884 / 23,820 / 23,884 bytes in baseline/narrow32/range32/wide64 order;
latency `__text` is 84,108 / 83,468 / 83,404 / 83,852 bytes. Whole executables
remain 240,688 bytes for throughput and 543,792 bytes for latency because of
layout/alignment. Size savings did not determine rejection; runtime evidence did.

In the focused ordinary `sum` loop, baseline LLVM already hoists bounds work
out of each iteration, retaining a preheader check relating truncated `.length`
to the 64-bit header. Both 32-bit experiments eliminate that remaining check;
the 64-bit merge retains it. `edge-checks/inlined-sum-loop.ll` records each
optimized region. Helpers were inlined, so an initial standalone-function
extract was empty; the corrected evidence follows the inlined blocks.
This does not show that all loops need a new typed loop-proof pass.

Hot OrderBook accesses are often through mutable pool/side references, counters,
and object fields. Repeated field/length loads and checks survive around stores
and calls. A successful check on an earlier captured array does not prove a
later reloaded field denotes that array, nor that an updated index remains in
range. A broader alias/field-effect proof or immutable-length load treatment
would need additional lifetime and mutation reasoning. No such new analysis or
ABI redesign was introduced merely to remove these loads.

For representative x86 code, all four raw throughput modules were optimized
with `-mtriple=x86_64-unknown-linux-gnu -mcpu=skylake`, then passed through the
existing trace finalizer using Linux naming and LLVM `llc -O3` to ELF objects.
No IR target text was rewritten. Baseline/createLimit/match counts are
716/286/219 instructions for the root and two callees, versus 679/276/208 for
range32; conditional branches are 109/48/39 versus 91/43/33. The separate
negative `test/js` disappears and the header access becomes `cmpl`; the
range-aware version avoids the added sign extensions seen without metadata.
Null branches, upper-bound failures and ordinary calls remain. Fast initialized
paths retain their stage-2 structure without added initialization bookkeeping.

These are explicitly targeted, unlinked objects, not the user's native-CPU Linux
executables or executed evidence. The supplied current Linux Bench/LatencyBench
hashes were independently verified and Bench was disassembled read-only; it
still has the expected sign and upper-bound checks. Neither Linux executable
was run on macOS. Any Linux performance conclusion for these experiments needs
new user-run measurements.

### Focused checks, restoration and reproducibility

- Initial narrow32 `scripts/test.sh` selection: all six exact tests in the
  pre-change review passed, including paired safe/unsafe array alias checks,
  read-only length rejection, trace checks and source/class/archive consumers.
- Archived `array_bounds_edges.iron`: baseline plus all three experiments each
  exit 42 at O0/O2/O3 and after O3 linking from an `.ironjar`, 16 successful
  native executions. It covers first/last/empty accesses; load/store/update at
  -1, length, `INT_MAX` and wrapped `INT_MIN`; null versus index-expression
  failure ordering; replaced local array references; nested/reference/primitive
  arrays; explicit cleanup; and no managed allocation on valid access/loop paths.
- Range32 repeats the exact typed-lowering, deterministic array trace and
  implicit-failure source/class/archive tests against the rebuilt classes:
  all three pass. `projects/OrderBook/test.sh` passes its four native and six
  Java tests, CLI checks and byte-identical reports, including allocation-free
  sample collection and pool recovery.
- License audit passes. After removing the emitter and assertion changes,
  `scripts/test.sh --test 'arrays lower to inspectable typed IR and LLVM'`
  passes again against the rebuilt original compiler. No full suite ran.
- Restored compiler source and emitted Bench/LatencyBench LLVM are identical
  to the baseline; linked disassembly is also identical after removing its
  filename banner. The jar is not byte-identical: 78 member contents differ
  after rebuilding unchanged source with the current JDK. An initial assertion
  expecting identical members exposed the toolchain difference; the report
  uses the actual Java 25 environment and records native-code equality instead.
  The initial saved/preexisting jars have identical member contents.
  Earlier evidence and all 864 original
  stage-5 preservation hashes are checked without rewriting them. The final
  tracked diff contains only this report; no authoritative semantics changed.

Evidence remains under ignored `workspace/perf-improvements/stage5/`:
`baseline/`, `narrow32/`, `range32/`, `wide64/`, `restored/` hold compiler jars,
source snapshots, executables, raw/optimized LLVM, code/section reports and
edge-check logs. Each experiment has both throughput directories and its
latency directory with all raw samples. Build/comparison commands, exact
sources/patches, `code-summary.json`, `artifact-identities.json`,
`preserved-sha256.json`, `ARTIFACT_SHA256SUMS`, focused logs and
`final-verification.json` make the result inspectable.

| ARM artifact | Baseline SHA-256 | Rejected range32 SHA-256 |
| --- | --- | --- |
| Compiler | `e93002b446bd89022729b334550e6dc83d3aa6afdecc4880038b9201d316e565` | `5f2bd71f0041aff12e755594ca9d97f4b0b31a3a4854e76b01617eb6d2724045` |
| Bench | `9d0b04d16394427daeb215603a21fbbcf9cd6e7ed7a0068b999ad94737495442` | `3cb280231e3d03baa579818e7abb7a1af5ab57da607dd1d3a584f0d045ae94aa` |
| LatencyBench | `adf84bef260ae1c170a5a6f01f4333305e52cc5dcbf11fb28cae443f06723858` | `b161138acbf048e684770ce6c4e602c54b89dcebd7cd0b7148e3c1b30d8a869b` |

There is no new production candidate requiring Linux validation. For optional
continued investigation, `LINUX_EXPERIMENTS.md` and
`stage5-linux-experiments.tar.gz` provide three exact emitter patches and Bash
commands to build fresh sources pinned to `6ca8c10` for both variants, with
`-O3 -march=native`, CPU 2, the unchanged Python 3.6-compatible runner and all
official samples retained. The commands' Bash syntax and zero-fuzz patch
application are checked locally; Linux execution remains unverified. Package
SHA-256: `9a87f0c525780f29789392530ef84857d9e32a1a5c62f8636c868ab27730d933`.

No commit, push, merge, branch switch, worktree, new task, subagent, global setting
change or edit to coordinator-owned `COORDINATOR.md` occurred. The outcome is a
bounded negative result with preserved experiments, not a performance guarantee.

## Round 2, Stage 1: native target layout accepted

The second investigation starts from `08c47861afab6d8bd4f4d60b5c1ee57fffd56b44`.
This stage is distinct from the rejected guard-elimination Stage 1 above.
Following code review and maintainer-run Linux comparisons, retain the native
target-consistency correction described in D172 and `COMPILER.md`. It does not
add PGO, change inlining thresholds or change OrderBook source.

### Implementation and review

The backend obtains the target triple and data layout from the configured
LLVM 23 Clang with the runtime's CPU and optional TLS flags. It attaches them
to a temporary module before `llvm-as`, so assembly, optimization, trace
finalization and code generation agree on layout. Runtime compilation and
final linking receive the same triple. Portable classes, archives and raw
emitted LLVM remain unchanged.

Review covered mixed-width heap and inherited fields, allocation sizes,
array/String/Throwable/runtime layouts, enum globals, trace finalization,
runtime-object caching and TLS deployment flags. The executable C/LLVM layout
probe fails with the old backend and passes with the candidate. This establishes
a target/layout disagreement, not an identified existing Ironwood source
miscompile. Ownership proofs, lazy initialization, exception timing and
mandatory memory safety are unchanged; no steady-state bookkeeping is added.

All eight implementation/test/documentation files in the handoff were verified
byte-identical to the Linux-tested candidate at final review. No code repair
was needed after those measurements. Eleven selected compiler checks passed on
macOS ARM64 and Linux x86_64, covering layout, artifacts, default/native CPU,
initialization, enums, arrays, traces, String/runtime operations and safety.
Four OrderBook correctness/allocation checks and one local TLS numeric-peer
scenario passed on macOS. The new layout and mixed-object tests are documented
in `LOCAL_TESTING.md`; their accepted final runs supersede retained development
fixture failures. License and whitespace checks passed. No full suite was run.
Linux ARM64 and optional Linux TLS runtime execution remain unverified.

### Code and measurement evidence

Both variants use `-O3 -march=native`, the same optimization policy, unchanged
workload and no PGO. Linked Linux code moves four matcher counters from offsets
68/76/84/92 to 72/80/88/96, combines two updates with a vector add, and inlines
`Order.cancel`. The executable's text section grows from 24,770 to 25,442 bytes.
All four runtime objects are byte-identical across variants. These are plausible
mechanisms, not a separate causal measurement of each code-generation change.

The initial Linux throughput comparison at `8 80` retains all eight alternating
pairs: median elapsed time changes from 907,608,678.5 to 883,897,517 ns, 2.61%
lower time or 2.68% more operations per second. The candidate is lower in all
eight pairs. The original four-pair latency sample was inconclusive.

A subsequent 20-pair uninstrumented latency comparison at `10000 50000 1000`
has a 0.65% lower median of per-process average batch times, but mixed tails
and occasional sustained slow processes. A 20-pair perf diagnostic pass showed
a 0.78% lower median cycle count alongside an 8.18% higher median per-run
p99.99. Its snapshots also showed AHCI IRQ 134 arriving on benchmark CPU 1.

The final 20-pair diagnostic pass uses the same binaries and arguments, with
IRQ 134 moved to CPU 0. All 40 processes succeeded, hashes matched before and
after, and every requested counter reported 100% counting time. Within the
recorded run windows IRQ 134 increased by zero on CPU 1 and 682 on CPU 0.
The governor remained `powersave`; `irqbalance` was already inactive when the
IRQ was moved. Both variants ran under the same sudo/perf/taskset wrapper.

For this final pass, medians across the 20 processes per build are:
average batch time 93.0275 -> 92.5400 microseconds (-0.52%); per-run p99
94.6635 -> 93.6535 (-1.07%); p99.9 121.4775 -> 120.2535 (-1.01%); p99.99
132.6225 -> 132.3510 (-0.20%); maximum 157.0470 -> 159.4005 (+1.50%).
These are 8,000-operation batch latencies and medians of per-run statistics,
not pooled percentiles. Average latency is lower in 14/20 pairs; whole-process
cycles are lower in 17/20 pairs, with a 0.81% lower median and approximately
0.075% more instructions. Counters include startup, warmup and reporting.

The previous large p99.99 disadvantage did not recur. Separate sessions cannot
prove IRQ interference was its sole cause. Retain all outliers; extreme tails
and sustained slow processes remain noisy. The evidence supports roughly
0.5-1% better typical batch latency on this Linux workload, not a universal
latency improvement or proof of zero regression. Earlier unpinned macOS results
were mixed: throughput median elapsed -1.22%, median batch average +1.08%.

### Preserved evidence

Detailed source/build/test/code evidence is under the ignored
`workspace/perf-improvements/round2/stage1/audit.82z6i7cy/`; the initial Linux
review is in `linux-review.3DBuw4/` beside it. Maintainer-supplied archives:

- Initial Linux build/results: `linux-results.tar.gz`, SHA-256
  `b898f872e88b88b061e771e870fe7786b9e52df6a6dbdc0a4dc85f45f12798ab`.
- Latency repeat: `ironwood-stage1-latency.FzykP5.tar.gz`, SHA-256
  `ff0fe101cc32e1c9cf2d876cc210a4fdf77c11a62dafd7e077f58b6bee570e93`.
- Initial perf pass: `ironwood-stage1-perf.eeCJsA.tar.gz`, SHA-256
  `14bff69e99bebe8c2ac5dcab2f4eaf08e8197612c51f1a031d555cb491df554e`.
- IRQ-adjusted perf pass: `ironwood-stage1-perf.lt4Ske.tar.gz`, SHA-256
  `66b711e1c20558d09e85bc9c6793195d1e52ff2e32e357ef31b9004667c18143`.

Stage 1 acceptance does not establish a new Native Image comparison. Published
benchmark tables are unchanged. Further alias/final-value work is a separate
Stage 2 candidate and must be compared with this accepted layout baseline.
