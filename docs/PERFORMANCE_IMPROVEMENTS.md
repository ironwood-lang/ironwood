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
