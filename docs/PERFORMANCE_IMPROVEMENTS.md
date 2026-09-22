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

## Round 2, Stage 2: field alias experiment rejected

This stage compares against the accepted Stage 1 commit
`019bdf7a4efa583deeec720de59a3406d4b8079a`. A bounded field-only alias experiment
passed focused correctness checks but failed to establish a repeatable
performance benefit across the Mac and maintainer-run Linux comparisons below.
The maintainer approved removing the experimental compiler changes after the
twenty-pair Linux latency confirmation. No Stage 2 production optimization is
retained. Useful behavioral regressions and the findings are retained; additional
final-value propagation remained report-only. D173 records the final decision.

### Historical experiment: scope, proof and implementation

The pre-change producer/consumer map and fixed measurement plan are preserved in
ignored `workspace/perf-improvements/round2/stage2/PLAN.md`. The semantic member
collector gives inherited fields their declaring owner. Primitive generic
materialization resolves that physical owner consistently in layouts and field
accesses. The experimental `IrField.StorageIdentity` exposed the existing owner/name
identity without including receiver type, source span or pointee type.
`FieldAliasMetadata` emitted one mutable scalar TBAA descriptor and access tag per
identity. `LlvmEmitter` attached tags only to ordinary instance-field loads and
stores, with metadata numbers following the completed trace plan. Both helpers
and all field-tag attachments have now been removed from production source.

The proof concerns storage locations during valid object lifetimes:

- Two parameters can name the same object. Loads and stores to the same field
  share a tag, including base/derived access to one inherited slot. No receiver
  `noalias` or scoped alias metadata is introduced.
- Shadowed declarations have distinct slots. Reference generic substitutions
  share erased pointer storage and one tag, even when the semantic field types
  differ. Primitive shapes resolve distinct physical owners; no legal source
  cast reinterprets one shape as another.
- Arrays are invariant in Ironwood. The negative covariance case remains a
  compile-time error; Object/exact-array aliases are exercised natively. Array
  elements and headers receive no new metadata, so they remain unknown relative
  to field accesses. A reference field's tag describes its slot, not its target.
- Enum objects use the same mutable field tags during construction and later
  access. Their global storage is not made constant. Static field slots and
  initialization state are not newly tagged or folded.
- Runtime object headers/body accesses, bulk operations and native calls remain
  untagged. In particular, TCP native output pointers can address fields and
  Throwable runtime helpers mutate native body state. These operations still
  clobber potentially aliased field values. The independent Ironwood TBAA root
  cannot assert disjointness from a foreign runtime metadata tree.
- Pool reuse updates the same slots with the same tags. Free, destruction and
  allocation calls retain their effects; a new allocation may reuse an address.
  The experiment adds no lifetime extension or value-invariance assertion.

The metadata interpretation follows the LLVM
[TBAA contract](https://llvm.org/docs/LangRef.html#tbaa-metadata). Semantic
validation, escape/ownership analysis, source spans, exception control flow,
lazy initialization and D132/D133 are unchanged. There are no new checks,
allocations, TLS accesses, registry operations or steady-state helper calls.
Application and standard-library source, layouts, inlining policy, CPU policy
and published benchmark tables are unchanged.

### Separate final-value outcome

No new final-specific transformation is retained or timed. Static compile-time
constants already flow through `StaticConstantEvaluator`, typed static fields
and LLVM constant storage. D171 already substitutes proven enum object addresses
inside complete-state paths after verifying their publication shape.

Additional instance-field propagation needs a different proof: an allocated
object is readable before its final slots are assigned. The new fixture observes
a derived final field's zero value through a base-constructor virtual call, and
enum constructor code observes a final slot before assignment. A recursive
static initializer observes the default value of a runtime-initialized final
static field. An immutable array reference still permits element updates.
Constructor escape or a reentrant call does not establish completion; a normal
successful type ensure can return in initialization state 1. No blanket
substitution or invariant load is valid across these boundaries. LLVM's
[invariant-load contract](https://llvm.org/docs/LangRef.html#invariant-load-metadata)
is stronger than source finality.

OrderBook's final references point to mutable arrays, and its enum `index`
values are assigned by constructors during lazy initialization. Eliminating all
remaining loads would require a bounded construction/completion and reaching
value proof, including constructor effects, failure and lifetime boundaries.
This stage does not add that subsystem or start the deferred type/side
specialization stage. The experimental field metadata allowed ordinary LLVM
load elimination across unrelated field stores for either final or mutable
slots. Unknown array/native effects deliberately limited that propagation.

### Historical experiment verification

The following nine exact compiler checks passed for the experiment on macOS
ARM64 and subsequently on Linux x86_64. The first test was implementation-specific
and is not retained after rejection; the second is retained under its behavioral
name, `field aliases preserve mandatory safety`:

1. `field alias metadata follows typed storage identity`
2. `field alias optimization preserves mandatory safety`
3. `field aliases and final observations survive optimized artifact links`
4. `initialized specialization preserves adversarial native behavior at O0 O2 and O3`
5. `pool release helpers preserve borrows and allocation-free reuse`
6. `pool ownership rejects dangling and conflicting aliases`
7. `safe free accounts for reference-array element aliases`
8. `primitive generic specializations survive class archive and tree-shaking round trips`
9. `native Throwable trace storage survives refresh release and allocation failures`

The new fixture covers same/different receivers, inherited/shadowed fields,
primitive/reference generics, final/default observations, arrays, enum state and
free/reallocation. It executes from class and archive inputs at O3/native with
zero and two arguments. The accepted Stage 1 compiler also compiles and runs the
same fixture successfully. Paired double-free and live-alias cases remain errors
under off, warn and error modes. The historical structural check verified
identity against class layouts and confirmed non-field accesses stayed untagged.

The four existing OrderBook correctness/allocation checks passed separately for
both rebuilt variants at O3/native on both hosts. They check workload counters,
sample writes, zero allocations after setup, and count/overflow boundaries.
No full suite or unrelated packaging/TLS suite was run. Whitespace and license
checks passed.

Development failures remain in `new-tests.log` and `new-tests-fixed.log`: the
first fixture attempted unsupported array covariance and exposed an owned array,
and the first structural assertion incorrectly required exact reference types
instead of equal erased pointer representation. Only the fixture/assertion was
corrected. Successful final runs are in `focused-tests.log`, the two successful
checks in `new-tests-fixed.log`, and `runtime-field-test.log`. No memory-safety
proof was weakened to make the fixture pass.

### Actual code and fixed local measurements

Both source snapshots use the same LLVM 23.1.0, unchanged `-O3 -march=native`
pipeline and no PGO. Raw/optimized LLVM, native objects, linked disassembly,
complete tool commands, source and binary SHA-256 identities are retained.
On the Apple M5, the Bench initialized matcher changes from 205 to 201
instructions and 57 to 54 load instructions; its 16 call instructions are
unchanged. These counts include uncommon paths and are not dynamic costs.
For example, a maker field value remains available across a store to the
PriceLevel body, while writes to `executedSize` through another Order reference
still require same-field alias reasoning. The ordinary matcher retains 323
instructions, with 80 to 79 loads. Bench text shrinks 24,588 -> 24,460 bytes;
LatencyBench text shrinks 88,076 -> 87,756 bytes. Complete matcher diffs and
`code-summary.json` substantiate these observations; static counts do not prove
latency improvement.

The protocol was fixed before timing: eight interleaved latency pairs at
`10000 50000 1000`, then eight throughput pairs at `8 80`, reversing execution
order every pair. The Mac was unpinned. All 32 processes succeeded, executable
hashes matched, and all samples/outliers are retained. No additional Mac timing
run was added in response to the results.

| Metric, median across runs | Stage 1 | Field-only candidate | Change |
| --- | ---: | ---: | ---: |
| Average 8,000-operation batch, microseconds | 55.8995 | 55.6540 | -0.44% |
| Per-run p99, microseconds | 67.5 | 67.5 | 0.00% |
| Per-run p99.9, microseconds | 98.0 | 99.5 | +1.53% |
| Per-run p99.99, microseconds | 123.0 | 152.5 | +23.98% |
| Per-run maximum, microseconds | 148.0 | 434.5 | +193.58% |
| Throughput elapsed, nanoseconds | 555,874,000 | 561,647,500 | +1.04% |

These are medians of per-run statistics, not pooled percentiles. The candidate
has lower average latency in 6/8 pairs, with a median paired change of -1.14%.
Throughput elapsed is lower in 3/8 pairs, with median paired change +0.71%.
Candidate maximum batches include 1.478, 2.590 and 6.906 milliseconds. None are
discarded or attributed to a particular external cause without evidence.
The small typical-latency difference, adverse tails and slower throughput do
not justify performance acceptance. Mac measurements could not establish a Linux
gain; independent review and the following Linux comparisons preceded rejection.

### Linux build and initial comparison

The maintainer ran the corrected package on Ubuntu 18.04, kernel
4.15.0-188-generic, an Intel Xeon E-2288G and LLVM 23.1.0. Python 3.6.9 executed
the handoff successfully. All 3,021 baseline tracked files matched the preserved
Stage 1 archive; candidate sources matched the exact handoff manifest. Both
23-artifact hash manifests and all four timed binary identities validated.
Runtime objects were byte-identical across variants. Optimization flags,
workload and no-PGO policy remained unchanged.

Nine focused compiler checks and four OrderBook correctness/allocation checks
per variant passed. The fixed eight latency pairs and eight throughput pairs
completed with all 32 processes successful, pinned to CPU 1, reversing order
each pair. Raw report counts, arguments, command order and reported metrics
were independently checked. No samples were excluded.

Medians of per-process batch statistics at `10000 50000 1000` were:

| Metric, microseconds | Stage 1 | Candidate | Change |
| --- | ---: | ---: | ---: |
| Average | 89.1620 | 89.3340 | +0.19% |
| p99 | 92.3045 | 91.1030 | -1.30% |
| p99.9 | 118.9120 | 118.4870 | -0.36% |
| p99.99 | 139.0125 | 132.9055 | -4.39% |
| Maximum | 175.6530 | 163.6930 | -6.81% |

Average latency was lower in 3/8 pairs, while p99.99 was lower in 6/8.
Throughput median elapsed at `8 80` was 883,943,949.5 -> 888,030,728.5 ns
(+0.46%); the candidate was slower in 7/8 pairs. These small mixed differences
did not establish a benefit. Since latency takes priority, the maintainer
approved one predefined twenty-pair latency-only confirmation on the same
binaries, without rebuilding or changing machine settings.

Actual linked x86 initialized matcher code removes some field reloads but adds
register transfers and splits some memory updates. Static instruction counts
including padding change from 219 to 221, with 16 calls unchanged. Bench text
changes from 25,442 to 25,298 bytes; LatencyBench text from 89,362 to 89,010.
This is code-generation evidence, not a causal performance explanation.

### Linux latency confirmation and disposition

The confirmation used the same binary hashes, CPU 1 and `10000 50000 1000`.
It started candidate/baseline and reversed each subsequent pair, ten pairs in
each order. All 40 processes succeeded. Before/after executable/helper hashes,
commands, report counts and all raw metrics validated; no samples were excluded.

| Metric, median across twenty processes, microseconds | Stage 1 | Candidate | Change |
| --- | ---: | ---: | ---: |
| Average | 89.0855 | 89.3390 | +0.28% |
| p99 | 91.1385 | 90.3725 | -0.84% |
| p99.9 | 118.4015 | 118.5130 | +0.09% |
| p99.99 | 136.7985 | 136.4055 | -0.29% |
| Maximum | 164.9300 | 164.0515 | -0.53% |

These are medians of per-run statistics for 8,000-operation batches, not pooled
percentiles. Average latency was lower in only 8/20 pairs, with a +0.257% median
paired change. p99.99 was lower in exactly 10/20 pairs, with a -0.015% median
paired change. Its earlier apparent 4.39% benefit did not repeat convincingly.
Maximum latency was lower in 11/20 pairs. The evidence does not demonstrate a
repeatable overall benefit, nor does it prove a statistically established or
universal regression. Stop extending this candidate's measurement campaign.

IRQ 134 did not arrive on CPU 1 in either Linux comparison: its CPU 1 counter
remained 127,204,813. CPU 0 increased by 204 in the initial run and 423 in the
confirmation. In the latter, requested affinity was `0-15` but effective affinity
was `0` both before and after. CPU 9, CPU 1's SMT sibling, recorded no non-idle
ticks, and the governor remained `powersave`. Two frequency snapshots cannot
establish per-process frequencies. Do not attribute these differences to IRQ 134
on CPU 1, or assert another external cause without additional evidence.

The accepted outcome is to keep Stage 1 and remove the Stage 2 production
experiment. Retain the alias/final-observation fixture and its native-artifact
and mandatory-safety checks. Remove the metadata-shape test, `FieldAliasMetadata`,
the `IrField` storage-identity helper and all emitter attachments. No final-value
transformation, runtime change, directive, workload edit or inlining-policy
change survives. Stage 3 is not started by this decision.

### Evidence, Git state and Linux handoff

All new evidence is in ignored
`workspace/perf-improvements/round2/stage2/`. `baseline-source.tar` is an exact
archive of 019bdf7. `preserved-compiler-build/` and
`preserved-stage1-artifacts/` were copied before modifications or rebuilds;
`baseline-identities.json` records their identities. Rebuilt baseline and
candidate artifacts live in `baseline/` and `alias/`. Existing Stage 1 audit and
Linux review artifacts, and supplied Linux executables, were not modified.

The experiment was briefly published as `568e2a0`, then uncommitted and the
branch restored to 019bdf7 at the maintainer's explicit request. All experimental
files remained unchanged for evaluation; all comparisons used the preserved
019bdf7 baseline. The final retained change is tests and documentation only,
not the rejected optimization. Original candidate source and patches remain
recoverable in the preserved evidence rather than the production source tree.

`LINUX.md` and `run-linux.sh` describe and automate an isolated source/build/test
handoff. `linux-inputs.tar.gz`, `INPUTS.sha256`, `changed-files.json` and
`handoff-hashes.json` identify the reproducible baseline/candidate inputs. The
script rebuilds both snapshots, checks their four OrderBook tests, runs the nine
selected compiler checks, and applies the same fixed eight-pair latency then
throughput protocol. It captures raw samples and linked code and never writes
the supplied Stage 1 executables. CPU choice remains user-controlled; IRQ,
services and power settings are unchanged. The user subsequently ran this
handoff and `run-stage2-latency.sh`; the verified results are recorded above.
The preserved package describes the historical candidate, not current HEAD.

The handoff's Python helpers target Python 3.6+, including Ubuntu 18.04.
Coordinator review found a Python 3.7-only `text=True` argument in the ignored
OrderBook check helper; it was replaced with `universal_newlines=True` before
repackaging. All executed helpers, generated tool wrappers, inline hash checking
and imported `compare.py` were audited against 3.6 syntax and APIs. This was a
local grammar/API check; subsequent maintainer runs verified execution on
Python 3.6.9 and Linux. The superseded bundle is preserved in
`handoff-before-python36-fix/`; the new package identity
is in `handoff-hashes.json`. `sample-audit.json` and
`sample-audit-detailed.json` durably record the exact final Mac samples, order,
commands and binary-hash checks. No benchmark or compiler suite was rerun for
this packaging correction, and their existing evidence remains byte-identical.

The corrected input archive has SHA-256
`ce03dbc70bd77e8eb29e284882958a4a0f14881429aaeffe121ba37d4648a1e6`.
Both maintainer result archives are preserved under the evidence root's
`linux-evidence/` directory, alongside the existing candidate artifacts:

- `ironwood-stage2-results.tar.gz`, SHA-256
  `cd3af2761a310004eff810fdddff9de7bfd25d2788f5cfe69ee78adec614bcec`.
- `ironwood-stage2-latency.tZ9Mmd.tar.gz`, SHA-256
  `179e5a71ec8be0931dca94825a369f471a6f8bf7667e8e00090ff06c60ad031e`.

Published README and BENCHMARK tables are unchanged. Historical failed fixture
attempts and all successful benchmark samples remain preserved, not overwritten.

### Final cleanup verification

After removing the experiment, production compiler sources match accepted
Stage 1 exactly. A fresh compiler build contains the same 559 production class
entries with byte-identical payloads as the preserved Stage 1 compiler; neither
removed metadata helper remains in the build. The only retained source changes
are the two behavioral tests, their registration and the fixture.

Both retained compiler checks passed on macOS ARM64, followed by all four
deterministic OrderBook correctness/allocation checks at O3/native. License and
whitespace checks passed. Logs are preserved as `cleanup-tests.log` and
`restored/orderbook-check/checks.log` under the evidence root. No additional
timings, full suite or new Linux cleanup build were run. The Linux comparisons
above concern the preserved baseline and candidate builds, not a new cleanup
build.

## Round 2 Stage 3A: retained enum argument specialization

3A is accepted independently of the separate selective-inlining experiment.
The compiler specializes direct calls/invokes only for already-proven enum
constant identities after initialized-state specialization. It preserves full
argument evaluation, mutable enum contents, initialization, cleanup and source
traces. Dynamic arguments use the original function. D174 specifies the bounds
and fallback contracts. No ordinary LLVM threshold or inlining policy changes
are part of 3A.

The independent Linux comparison recorded median average batch latency -4.09%,
p99 -5.26%, p99.9 -3.06%, p99.99 -4.85%, and throughput elapsed -0.49%.
Average latency was lower in all 20 reversed-order pairs. The earlier Mac
average change was -5.65%. These observations and the constant-argument
mechanism support retaining the pass; no universal speedup is claimed.

Twelve focused compiler tests and four deterministic OrderBook correctness/
allocation checks passed independently on both hosts. The checks cover enum
identity versus mutable observations, recursion, clone bounds, mandatory safe/
unsafe reclamation, initialization states, artifacts and exact traces. No full
suite was run. Linked O3 code was inspected on ARM and x86.

Evidence root: `workspace/perf-improvements/round2/stage3/`. The independent
source is preserved in `3a/source.tar`, `3a/source-hashes.json` and
`3a/candidate.patch`; no eligibility or bound was changed for acceptance.
The verified returned archive is
`linux-evidence/ironwood-round2-stage3-8kxyha0p.tar.gz`, SHA-256
`e455e6f543f0232d81e66d3fd80f33ffd193f556e90771fc63805c9b69d31b1d`.
Use its enum variant for 3A and baseline variant for the comparison.

## Round 2 Stage 3: independent specialization and inlining candidates

Status: 3A enum specialization and 3B selective inlining are retained together
on `perf-improvements`, with 3A accepted separately and 3B uncommitted, starting from
`3bb64fc7139dd35c3423e28f28d1b250be4346b4`. The maintainer explicitly accepts
3A and delegates the technical decision for 3B. The disposition below retains
both measured policies and adds link controls with unchanged defaults. Exact
independent and combined snapshots and Linux evidence remain preserved. No PGO,
application/stdlib source changes, Stage 4 work, source `@Inline` directive,
global threshold default increase or published benchmark-table change was made.

### Provenance and pre-change review

The canonical root, both origin URLs, branch and exact HEAD matched the dispatch;
the initial working tree was clean. No branch/history changes, commits or pushes
were made. Full pre-change contracts, consumers, bounds, focused selections and
fixed protocols are in ignored
`workspace/perf-improvements/round2/stage3/PLAN.md`. That directory also contains
baseline source/build snapshots, every raw sample, test log, patch and hash map.
The coordinator-owned file was not changed.

All six handoff attachment hashes passed, as did both current Linux baseline
archive member hashes. Only validated regular baseline members were copied to a
fresh evidence directory; originals were preserved. The rejected Stage 2 alias
candidate was not used. Current Bench and LatencyBench raw LLVM are byte-identical
to the archived current Linux baseline, and all 559 baseline compiler class
payloads match the preserved starting build. Historical Ironwood and Native
Image attachments were disassembled only. The stripped Linux Native Image file
cannot support named-method attribution, and no new Native Image timing or
current-baseline identity is claimed.

### 3A: constant enum argument specialization

The independently tested pass follows only existing `IrEnumConstant` operands
and reference-copy SSA chains at direct calls/invokes. It copies a method body
with one parameter's uses replaced by an entry conversion of that exact enum
address. The complete signature and evaluated arguments stay intact. Dynamic,
null, load-derived and join-derived arguments keep their original call target;
indirect calls are unchanged. The pass does not assume enum contents or Order
fields are immutable. Existing publication/state proofs alone produce the
constant operands; no initialization ensure or field load is newly removed.

Limits are two clones per original target, 32 clones overall, original body cost
at most 256 typed instructions/terminators and at most 2048 extra operations.
Direct-call recursive targets are excluded; generated clones are not recursively
specialized. Source identity, original fallback functions, control-flow edges,
checks, call effects, ownership analysis and artifact formats remain unchanged.
Source/class/archive reconstruction reruns validation and the post-validation
passes. Clones retain original trace names and spans.

The new two named checks cover exact identities versus static loads and dynamic
arguments, mutable enum contents, recursive exclusion, clone limits, unchanged
ABI/CFG identity, safe/double cleanup in all unfreed modes, class/archive links,
O0/O3 execution and exact clone traces. Ten selected existing initialization,
trace, recursion, alias-safety, generic and artifact checks passed, as did all four
OrderBook correctness/allocation checks. No full suite ran. Exact source patch
and manifest: `3a/candidate.patch` and `3a/source-hashes.json` under the evidence
root; compiler and native artifacts use the `enum/` directory.

This generated six surviving typed enum clones for Bench and fourteen for
LatencyBench. LLVM subsequently inlined the specialized creation wrappers into
callers. The warm matcher still contains its MARKET and BUY comparisons: the
mutable pooled Order fields have no new reaching-value proof. On ARM, Bench's
warm matcher remains 204 static instructions, while `Bench.run` grows from
662 to 905 instructions and the standalone warm creation wrapper disappears.
This is a code-placement/constant-argument opportunity, not complete type/side
specialization of the matcher. A broader object-field proof was not attempted.

### 3B: bounded selective inlining

After archiving 3A, its verified source changes were removed and clean baseline
source was confirmed before implementing 3B. `SelectiveInlining` reads validated
typed CFGs and direct-call graphs. It selects METHOD bodies containing a cycle,
with 64-256 instructions/terminators and 1-4 direct call sites, all in callers of
at most 48 operations. Entry points, dispatch-table targets, lifecycle entries,
recursion in the direct-call graph and reachability between selected functions
are excluded. The
planner selects at most eight functions and 4096 estimated copied operations.
These are selection bounds, not final-code-size bounds after LLVM optimization.
The graph does not predict indirect callback cycles; LLVM still owns inlining
legality and later optimization, and no callback-heavy performance claim is made.
`LlvmEmitter` adds `alwaysinline` only to the selected set.

No IR instructions, signatures, exception edges, source locations, guards,
lifetime facts or artifact formats change. LLVM performs the inlining while
existing debug/probe metadata reconstructs original source traces. Mandatory
memory safety still runs before emission in every unfreed mode. Initialization
state 1/3 and failure timing remain ordinary instructions in the copied code.
The change adds no runtime bookkeeping, allocation, TLS access, registry lookup
or guard. It can inline selected functions at O0 too, which is covered natively.
D132/D133, LLVM 23 and the existing O3/native configuration are preserved.

Actual selection is recorded in `3b/selected-functions.json`. Bench selects the
general and warm matcher, Integer parsing and PrintStream custom output. Latency
selects the general matcher, warm groups 0/3, Integer parsing, StringBuilder
append and `Bench.run` group 4. The non-overlap rule leaves matcher group 4 under
the ordinary policy. No post-measurement policy tuning was performed.

Both new checks passed: eligible versus nonloop/large-caller/large-body/recursive
cases, all-mode mandatory cleanup safety, zero iterations, mutable arrays,
cleanup and exact original work/wrapper/main traces at O0/O3. The same ten
existing regressions passed, plus the selected PrintStream allocation,
StringBuilder ownership/allocation and numeric standard-library checks. Thus
15 distinct focused compiler checks and all four OrderBook correctness/allocation
checks passed for 3B. License and whitespace checks passed. New tests are listed
in `LOCAL_TESTING.md`; complete selections are in `focused-tests.json`.

### Machine-code observations

Both candidates retain identical captured runtime object bytes to baseline.
Complete linked ARM code, optimized IR, normalized hot-function diffs and static
counts are preserved. Counts include cold/error blocks and are not dynamic
instruction or spill estimates.

For 3B Bench, the standalone ARM matchers disappear. The warm createLimit body
contains 451 instructions versus the separate baseline wrapper's 262 plus
matcher's 204. `Bench.run` changes from 662 to 446 instructions as LLVM places
creation work in separate expanded wrappers. The entry side comparison can use
the caller argument directly; loop-carried MARKET/BUY tests still remain. The
result does not establish elimination of every type/side load or branch.

ARM executable `__text` sizes, baseline -> 3A -> 3B, are 24,588 -> 25,292 ->
26,380 bytes for Bench and 88,076 -> 90,444 -> 90,252 for LatencyBench. File-size
page padding hides much of this growth, so file bytes alone are not a code-size
measure.

Static x86 objects were generated on Mac with LLVM 23.1.0, the archived Linux
triple/layout and exact recorded Skylake feature set. The cross baseline's
optimized LLVM matches the actual Linux baseline except its ModuleID path,
including every hot function. The objects are unlinked and unexecuted; they do
not establish Linux correctness or performance. Their `.text` sizes are
12,980 -> 14,036 -> 14,564 bytes for Bench and 73,524 -> 76,724 -> 75,684 for
LatencyBench, excluding the separately reported unlikely section. The 3B x86
warm wrapper contains 459 instructions versus baseline wrapper 264 plus matcher
205. The actual archived Linux executables were also inspected directly.

### Fixed Mac measurements and limitations

Each candidate ran eight reversed-order pairs of latency `10000 50000 1000`,
then eight reversed-order pairs of throughput `8 80`, without concurrent builds
or tests. Each invocation performs its existing warmup. LLVM 23.1.0,
`-O3 -march=native`, existing inlining thresholds and no PGO were identical.
The host was unpinned macOS ARM64; these are diagnostic local results.

Latency values below are medians of per-process batch statistics in microseconds,
with 8,000 operations per batch, not pooled percentiles or individual order latency.
Each experiment has its own interleaved baseline sample set.

| Metric | 3A baseline -> candidate | Change | 3B baseline -> candidate | Change |
| --- | --- | ---: | --- | ---: |
| Average batch | 55.9115 -> 52.7535 | -5.65% | 55.5210 -> 51.0665 | -8.02% |
| p99 | 63.0 -> 60.0 | -4.76% | 65.0 -> 60.0 | -7.69% |
| p99.9 | 73.5 -> 71.5 | -2.72% | 73.0 -> 68.0 | -6.85% |
| p99.99 | 85.5 -> 99.0 | +15.79% | 89.0 -> 80.5 | -9.55% |
| Maximum | 130.5 -> 144.5 | +10.73% | 121.5 -> 112.0 | -7.82% |
| Throughput elapsed, ns | 548351000 -> 535348500 | -2.37% | 553192000 -> 515830000 | -6.75% |

Average latency and throughput elapsed were lower in all eight pairs for both
candidates. Median paired average changes were -5.79% (3A) and -8.25% (3B).
3B p99 was lower in 8/8 pairs, p99.9 in 7/8 and p99.99 in 5/8. Despite its lower
median maximum, 3B maximum was lower in only 3/8 pairs, with a +7.94% median
paired change. Extreme tails are noisy; no universal tail improvement is claimed.

The 3A parser stopped after the third pair's baseline successfully reported a
millisecond outlier. It was repaired to handle output units, then the same fixed
schedule resumed without discarding or rerunning any process. Original failure
records and stdout remain preserved; pair 3 has a timing gap and reduced
comparability. No additional favorable-result sampling was added. The complete
3B protocol was uninterrupted. These results favor 3B for the reviewable working
tree but do not establish a head-to-head ranking on Linux or additive gains.

### Linux handoff and disposition

`workspace/perf-improvements/round2/stage3/linux-bundle/` and its sibling
`ironwood-stage3-linux.tar.gz` contain the exact baseline source, independent
3A/3B patches including new files, source manifests, focused tests, fixed timing
helper and runner. `LINUX.md` gives commands. Preparation validates archive
members, applies each patch to a separate ordinary source snapshot and verifies
all source hashes. It never modifies the maintainer's checkout. LLVM 23.1.0 is
required; each variant uses its own compiler, stdlib and runtime sources.

The Linux protocol builds/tests all variants before measurement, then compares
each separately against baseline: 20 reversed-order latency pairs and eight
throughput pairs. An optional existing CPU is supplied through taskset.
Environment/IRQ/governor state is recorded without changing it; raw samples,
failures, commands, IR, disassembly and hashes are archived automatically.
Helpers use Python 3.6-compatible syntax/APIs; host grammar/API review and
prepare-only hash/patch validation are recorded. The returned archive below
confirms Linux compilation and execution. The runner did not capture its Python
version, so actual Python 3.6.9 execution cannot be independently confirmed.

### Verified Linux execution and recommendation

The maintainer returned `linux-evidence/ironwood-round2-stage3-8kxyha0p.tar.gz`
under the Stage 3 evidence root. Its SHA-256 is
`e455e6f543f0232d81e66d3fd80f33ffd193f556e90771fc63805c9b69d31b1d`.
All 15,003 member paths/types were checked before extraction into a fresh review
directory. Original archives and the delivered runner bundle remain unchanged.
`linux-evidence/latest-review-root.txt` identifies the extracted run;
`audit.json`, `code-audit.json` and `environment-audit.json` in its parent record
the independent review. No archived script or Linux executable was run on Mac.

The original source manifests match all 3,023 baseline files and 3,026 files per
candidate. Shipped build/measurement helpers and test selections are identical.
Each variant used its own compiler, stdlib and runtime source snapshot; all 23
recorded build hashes per variant verify. The source, raw LLVM and optimized IR
comparison confirms the accepted baseline, excluding the rejected alias pass.
All four captured runtime objects are byte-identical across variants. Commands
retain LLVM 23.1.0, O3/native targeting, the existing inlining threshold and no
PGO. The linked binaries have their own recorded hashes, rather than borrowing
identities from an earlier run.

Baseline passed its ten selected compiler tests, 3A passed twelve and 3B passed
fifteen. Each passed its license audit and four OrderBook correctness/allocation
checks. All 112 benchmark processes exited successfully with empty stderr.
Independent parsing of every raw stdout reproduces every reported summary.
The exact fixed schedule completed without retries, repairs, omitted samples or
extensions: 20 reversed-order latency pairs and eight throughput pairs per
candidate, with `taskset -c 1`, latency `10000 50000 1000` and throughput `8 80`.

Latency entries are medians of per-process batch statistics in microseconds,
with 8,000 operations per batch. They are not pooled percentiles or individual
order latency. Each candidate has its own interleaved baseline session. Changes
below compare those medians; lower elapsed time is better.

| Metric | 3A baseline -> candidate | Change | Lower pairs | 3B baseline -> candidate | Change | Lower pairs |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| Average batch | 89.067 -> 85.421 | -4.09% | 20/20 | 89.109 -> 82.988 | -6.87% | 20/20 |
| p99 | 90.980 -> 86.196 | -5.26% | 20/20 | 91.125 -> 84.321 | -7.47% | 20/20 |
| p99.9 | 118.482 -> 114.853 | -3.06% | 19/20 | 118.099 -> 112.0145 | -5.15% | 20/20 |
| p99.99 | 137.223 -> 130.5735 | -4.85% | 14/20 | 135.158 -> 127.8415 | -5.41% | 17/20 |
| Maximum | 163.6415 -> 158.4465 | -3.17% | 14/20 | 168.5285 -> 161.606 | -4.11% | 13/20 |
| Throughput elapsed, ns | 883096213.5 -> 878754821.5 | -0.49% | 7/8 | 883173040 -> 836876939 | -5.24% | 8/8 |

The median paired average changes are -4.13% for 3A and -6.85% for 3B. Splitting
by execution order gives -4.17%/-4.07% for 3A and -6.82%/-6.88% for 3B
(baseline-first/candidate-first). Both halves of each session also improve.
This supports the average-latency finding beyond a single favorable aggregate.
Extreme tails remain variable: 3B's per-pair maximum change ranges from -24.37%
to +13.90%, despite the lower median. The two candidates were not interleaved
directly with each other, and additive or universal gains are not established.

Actual linked Linux `.text` sizes, baseline -> 3A -> 3B, are 25,442 -> 26,498 ->
27,026 bytes for Bench and 89,362 -> 92,562 -> 91,522 for LatencyBench. Thus 3B
grows linked text by 6.23% and 2.42%, respectively. Disassembling the returned
ELFs reproduces their recorded function bodies. All six optimized modules match
the earlier cross-generated IR except the ModuleID path. In 3B the warm group-0
creation wrapper has 459 instructions and seven calls; baseline has a separate
264-instruction/16-call wrapper and 205-instruction/three-call matcher. In 3A
the wrapper disappears into its caller while the matcher remains unchanged.
These are whole-function static counts including cold paths, not dynamic work
counts; mutable loop-carried MARKET/BUY comparisons remain.

Before/after environment snapshots show Linux x86-64 kernel 4.15.0-188-generic,
unchanged `powersave` governor and no increase in numbered hardware IRQ counts
on CPU 1 during either candidate session. IRQ 134 increased only on CPU 0
(543/397 deliveries for 3A/3B), while CPU 9 was idle by recorded tick accounting.
This does not establish zero timer/IPI activity or identify a cause for tail
variation. Hardware performance counters and per-process environment boundaries
were not collected; each snapshot window covers both latency and throughput.

The independent comparison favored 3B for OrderBook: it has consistent average,
p99 and p99.9 improvements across all 20 Linux pairs, lower throughput elapsed
time in all eight pairs and the same direction of average gains on Mac. At this
review point the live compiler contained only 3B and no acceptance decision or
commit was made. The subsequent combined experiment and final disposition below
supersede that intermediate state. The evidence does not establish a need for
`@Inline` source syntax. No fresh Native Image comparison or Stage 4
application-work elimination was undertaken.

### Combined follow-up after Linux review

The maintainer authorized a further experiment to determine whether 3A adds value
on top of 3B. The new reference is the exact measured **3B compiler**, and the
candidate composes the original 3A pass with it. Neither policy was tuned. The
live checkout initially retained 3B; combination sources and evidence were isolated under
`workspace/perf-improvements/round2/stage3/combined-experiment/`.

The combined compiler passed the 17-test union of the focused selections and
all four OrderBook correctness/allocation checks on Mac. Its source manifest
contains 3,029 files. Code inspection confirms six enum clones for Bench and
fourteen for LatencyBench, with the same four/six methods selected by the inline
policy as in 3B alone. ARM linked text grows from 26,380 to 28,364 bytes for Bench
(+7.52%) and from 90,252 to 95,372 for LatencyBench (+5.67%). Both passes are
active; their interaction required measurement. Code/executable size is not an
acceptance criterion, following the maintainer's explicit performance priority.

`combined-experiment/ironwood-stage3-combined-linux.tar.gz` contains exact reference
sources, a composition patch, source manifests and a fixed 20-pair latency plus
eight-pair throughput protocol, directly comparing 3B with 3A+3B. It rebuilds and
checks both variants before timing. The runner now streams child output to the
console and files, prints phase/command/exit/elapsed status, emits a 15-second
heartbeat during quiet child execution, and records Python/tool versions. Live
output, failure propagation, failure archives and simulated measurement order/
summaries passed smoke checks. Source-only preparation from the delivered
archive verified both manifests with zero patch fuzz. Python 3.6 grammar/API
compatibility was checked locally; that interpreter was not available for an
actual run at preparation time. The returned run below now verifies execution
with Python 3.6.9 and the incremental Linux result.

### Verified combined Linux result

The returned `ironwood-stage3-combined-0v4rcx30.tar.gz` has SHA-256
`70a9a52f3ab517f3e839fc2e7b01452e9d78cf00c8d99f49df417a1bde076241`.
The original Downloads archive and a verified copy under
`combined-experiment/linux-evidence/` are preserved. All 10,005 member paths and
types passed validation before extraction into a fresh review directory. No
archived script or executable was run during review. The pointer
`combined-experiment/linux-evidence/latest-review-root.txt` locates the run;
its parent contains `audit.json`, `code-audit.json` and `environment-audit.json`.

All delivered helper/input hashes match, as do every one of the 3,026 reference
and 3,029 combined source files and 23 recorded build hashes per variant. The
rebuilt 3B reference matches all 560 compiler class payloads from the preceding
Linux run, and both reference optimized LLVM modules match except the ModuleID
path. Both variants use identical runtime objects and unchanged O3/native/LLVM
23.1.0 settings without PGO. The reference passed 15 selected compiler checks,
the combination passed 17, and each passed its license audit and all four
OrderBook correctness/allocation checks.

All 56 benchmark processes succeeded with empty stderr. Independent parsing
of every stdout reproduces the stored summaries and verifies the original
20 reversed-order latency pairs followed by eight throughput pairs on CPU 1.
No retries, repairs, omitted samples or protocol extensions occurred. The
reference below is **3B alone**, not the original compiler before Stage 3.
Latency values are medians of per-process 8,000-operation batch statistics in
microseconds; these are not pooled percentiles or individual-order latency.

| Metric | 3B reference | 3A+3B | Change | Combined lower pairs |
| --- | ---: | ---: | ---: | ---: |
| Average batch | 83.073 | 78.8855 | -5.04% | 20/20 |
| p99 | 83.988 | 79.772 | -5.02% | 20/20 |
| p99.9 | 112.175 | 108.0645 | -3.66% | 20/20 |
| p99.99 | 129.0555 | 123.535 | -4.28% | 13/20 |
| Maximum | 162.6475 | 155.5965 | -4.34% | 11/20 |
| Throughput elapsed, ns | 837975113.5 | 814985038 | -2.74% | 8/8 |

The median paired average change is -5.02%. Splitting by execution order gives
-5.11% when 3B runs first and -4.94% when the combination runs first; both halves
also improve. All average changes lie between -7.06% and -0.95%. Extreme tails
remain mixed: the per-pair maximum ranges from -22.75% to +26.58%. Earlier
baseline comparisons came from another session, so their gains are not added
to this incremental result or presented as a fresh original-baseline comparison.

Disassembly of the returned ELFs matches the captured function bodies. Raw LLVM
matches the corresponding Mac inputs. The combination retains the same four/six
inline-selected methods and six/fourteen enum clones in Bench/LatencyBench.
In the linked x86 code, the warm createLimit wrapper's 459 instructions and seven
calls become two enum-specific bodies of 264/265 instructions and six calls each;
other work moves into callers. These are static whole-function counts, including
cold paths, and do not prove that every mutable type/side test was eliminated.

The recorded host is the same Xeon E-2288G, kernel 4.15.0-188-generic, with Python
3.6.9, Java 25.0.4.1 and Clang 23.1.0. During the 211.37-second timing window,
`powersave` remained unchanged, CPU 1 had no increase in numbered hardware IRQ
counts, IRQ 134 increased only on CPU 0 by 430 deliveries, and CPU 9 was idle by
recorded tick accounting. These observations do not rule out timer/IPI activity
or identify the cause of tail variability. Hardware performance counters were
not collected.

### General-performance assessment and disposition

Retain 3A and 3B together. The maintainer explicitly accepts 3A independently
of 3B; the technical recommendation is to retain 3B with a link-time fallback.
This supersedes the earlier recommendation to require diverse-application
measurements before accepting either pass. We cannot validate every application,
and that is not the acceptance criterion for a compiler profitability policy.

3A uses proven enum identities to expose constants for simplification without
adding a runtime test. Confidence in its expected benefit is higher than for 3B.
3B removes call boundaries under a narrow structural rule where LLVM's current
cost model can refuse profitable inlining. Neither adds runtime bookkeeping;
both preserve semantic and safety contracts in the focused checks. Consistent
independent average gains on ARM and x86, plus the incremental Linux combination
gain, support retaining the combination. A favorable expected tradeoff is an
engineering judgment based on that mechanism and evidence, not a quantified
probability or a claim of measured coverage of other applications.

3B does not prove hotness or require useful simplification after inlining. It can
alter register pressure, spills, memory accesses and scheduling adversely. 3A
also does not guarantee useful work disappears in every specialization. Those
are concrete remaining performance risks. Code/executable size is not an
acceptance criterion. Further experiments should investigate specific generated
code or suspected regressions; an unbounded application survey is not required.

### Retained implementation and link controls

The working compiler now composes the exact measured enum specializer after
initialized-state specialization with the existing selective-inlining planner.
Neither eligibility rule nor its bounds changed. 3A is accepted as a separate
change; 3B and its link controls are accepted as the next separate checkpoint
before further compiler experiments. No push was requested.

LLVM diagnostics on the preserved baseline reject the ordinary matcher with
cost 1725 and the initialized matcher with cost 1335 against threshold 1000.
Those numbers describe estimated profitability at specific calls, not a legality
failure and not proposed thresholds. 3B emits `alwaysinline` for its selected
functions; it never changed the ordinary inline threshold.

The ordinary O3 threshold remains 1000. Two link-only options provide control:

- `--inline-threshold N` overrides LLVM's ordinary budget with a nonnegative
  signed-32-bit integer. It leaves the selected optimization pipeline intact.
  Other optimization levels use LLVM defaults when no override is supplied.
- `--selective-inlining=off` disables only 3B; `on` is the default. 3A,
  initialization-helper inlining and LLVM's ordinary inliner remain active.
  Lowering a budget cannot cancel `alwaysinline`, which is why these are
  separate controls. Even budget zero does not mean all inlining is disabled.

A global budget increase can be tested using these options, but cannot be
justified by one rejected call's cost. It would affect calls outside 3B's narrow
selection. The accepted default retains the measured policy instead.

Local verification after retention passed 18 focused compiler tests and four
OrderBook correctness/allocation checks. The new control test captures actual
LLVM arguments at default, zero and 2000 budgets and checks that 3A remains
active with 3B disabled; O0/O3 native tests preserve cleanup and exact traces in
both policy modes. Default raw LLVM, optimized LLVM apart from its ModuleID,
and linked ARM machine instructions match the measured combination. Runtime
objects also match. Full program objects differ in pseudo-probe records only;
their other file-backed sections match. No new performance run was needed for
unchanged default code. The new controls have not yet been executed on Linux.

The returned Linux Native Image comparison uses optimized `-O3 -march=native`
executables with `javac -g`, `native-image -g` and local method symbols retained,
without PGO or ML profile inference. Both builds and correctness smoke runs
passed. All 4,607 manifest file hashes and the seven delivered Java sources
match. Archive `ironwood-native-image-symbols-4cadp9dv.tar.gz` has SHA-256
`782514014bd6053c96664229c609796546d44d1270c8a61d53968896bdd5a049`.

Named symbols and DWARF layouts show that Native Image keeps `OrderBook.match`
out of line with six calls per benchmark cycle. It uses one unsigned array
bounds comparison where Ironwood often retains separate negative and upper
checks, and removes the unread `Order.resting` field and its stores. These are
specific work-elimination opportunities for separate follow-up experiments;
they do not reverse the measured inlining decision or establish a new speed
ranking. The returned timings are smoke checks, not a paired performance run.
Native Image is comparison evidence, not an implementation template or an
upper bound. Detailed disassembly and the audit remain under
`workspace/perf-improvements/round2/stage3/native-image-symbols/linux-evidence/`.

### Separate work-elimination experiments after the 3B checkpoint

3B and its link controls were committed locally as
`fc43a8f8c08299487b39a4a078990175ab7be85a`, without a push. At the maintainer's
request, the two opportunities from the Native Image audit were evaluated as
independent compiler experiments against that checkpoint. Evidence is preserved
under `workspace/perf-improvements/round2/stage3/work-elimination/`.
The maintainer accepted both optimizations and requested a joint commit after
the Linux review. The completed review and retention judgment follow the initial
local results below.

The bounds candidate replaces the negative-index and upper-bound predicates
with one unsigned i32 comparison. All array producers constrain lengths to
`0..INT32_MAX`, including the file-read path that constructs arrays directly.
The physical i64 length header, address calculation and exceptional CFG remain
unchanged. The store candidate runs after validation and closed-world pruning,
removing only primitive instance-field stores with no retained typed reader.
It matches declaring owner and layout index, protects native field addresses
and the runtime's String, Throwable and PrintStream layouts, and preserves
reference stores, object layout, RHS evaluation, checks and control flow.
Neither decision depends on guessed hotness or PGO.

Fourteen distinct focused compiler tests passed locally, including O0/O3 native
execution, source/class/archive paths, signed index extremes, zero-length arrays,
inherited/hidden/generic fields, native observers, cleanup, exact traces and
mandatory reclamation errors. All four deterministic OrderBook checks passed
on each of four independently rebuilt variants: baseline, bounds, stores and
both. The selected inlining set is identical across these variants. ARM linked
code and cross-generated, unlinked x86 objects show the separate negative-index
branch disappearing. All four raw field accesses to `Order.resting` disappear
from each benchmark entry point with store elimination; layouts remain intact.
The x86 objects were inspected, not executed on the Mac.

The fixed local protocol used six reversed-order latency pairs and four
throughput pairs per candidate, with the unchanged workloads. The Mac was
unpinned. Changes below compare medians of per-process statistics; positive
means more time. Percentiles describe 8,000-operation batches.

| Candidate | Average batch | p99 | p99.9 | p99.99 | Throughput elapsed |
| --- | ---: | ---: | ---: | ---: | ---: |
| Bounds | +1.21% | 0.00% | +2.29% | +30.23% | +1.94% |
| Stores | -1.35% | -1.89% | +0.77% | +25.63% | +2.26% |
| Both | -0.23% | 0.00% | -1.52% | -8.14% | +2.02% |

Average latency was lower in 2/6, 4/6 and 3/6 pairs respectively. Throughput
elapsed time was higher in all four pairs for every candidate. This is adverse
local evidence, not an established performance improvement. Removing operations
has a stronger semantic basis than predicting profitable inlining, but the
resulting register allocation, scheduling and layout can still cost performance.
No hardware counters identify the cause here. The recommendation at delivery was
one controlled Linux comparison of the four variants before accepting or
rejecting either implementation, without extending runs until a win appears.

Initial regression fixtures also exposed two existing frontend limitations,
reproduced with the committed baseline: assignment location checks can precede
RHS evaluation, and qualified field reads in destructors are rejected as possible
allocation/exception paths. These are not changed by this experiment. Corrected
fixtures explicitly capture RHS values before assignment and use supported
destructor cleanup. The store pass's structural checks additionally require every
non-store instruction and terminator to remain unchanged. Reproduction evidence
and the original failing test log are retained with the local evidence. D180
later corrected both frontend behaviors independently of these performance
passes and retained dedicated source/class/archive regressions.

The self-contained Linux bundle uses the exact measured source variants and
Python 3.6-compatible helpers. It verifies input and source hashes, builds and
checks all four variants before timing, then runs 20 reversed latency pairs and
eight throughput pairs per candidate against the committed baseline. Commands,
output, failures and quiet-child heartbeats appear on the console and in files.
The returned archive includes source identities, compiler commands, LLVM,
objects, linked disassembly and all 168 benchmark process outputs. No new Native
Image build, application edits, machine tuning or global inlining-policy change
is part of this experiment.

### Returned Linux work-elimination results and retention judgment

The returned `ironwood-work-elimination-itbsi30x.tar.gz` has SHA-256
`911e34e6b631410023c81addf548a5f471d6a82f968ff0e0dcda7ba48d8b4fdd`.
All 16,318 artifact-manifest hashes match. All 16 delivered input hashes and
the exact baseline/bounds/stores/both source manifests match, with
3,030/3,032/3,033/3,035 files respectively. The reference is committed 3B,
`fc43a8f`; the native artifacts use O3, native CPU selection, ordinary threshold
1000 and unchanged selective inlining, without PGO.

The runner completed on Python 3.6.9, Java 25.0.4 and LLVM 23.1.0 on the Xeon
E-2288G. All 3/5/7/15 selected compiler tests passed on the respective variants,
including the link controls newly exercised on Linux. The four OrderBook
correctness/allocation checks passed for every variant. All 168 benchmark
processes exited successfully with empty stderr. An independent audit reparsed
every raw output, verified binary hashes and taskset CPU 1 commands, checked
the fixed reversed pair order and reproduced every summary value.

Each row is a separate baseline comparison with 20 latency pairs and eight
throughput pairs. Negative means less time; latency describes 8,000-operation
batches. These are changes in medians of per-process statistics.

| Candidate | Average batch | p99 | p99.9 | p99.99 | Maximum | Throughput elapsed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Bounds | -3.09% | -2.99% | -1.91% | +1.49% | +6.16% | -7.32% |
| Stores | -1.13% | -0.84% | -0.54% | -0.18% | -0.96% | -4.75% |
| Both | -0.79% | -0.41% | -0.65% | +0.20% | -8.19% | -7.09% |

Average latency was lower in 19/20, 20/20 and 17/20 pairs, respectively;
throughput elapsed was lower in 8/8, 8/8 and 7/8. Average paired medians were
-3.19%, -1.14% and -0.56%. Splitting average changes by execution order retains
the direction: baseline-first/candidate-first medians are -3.19%/-3.16%,
-1.10%/-1.20% and -0.47%/-0.71%. The gains are not an artifact of one ordering.

Extreme-tail evidence is mixed. Bounds p99.99 was lower in only 8/20 pairs and
its maximum in 6/20; do not claim a tail improvement from its average gain.
The combination's p99.99 medians were 124.0785 versus 124.331 microseconds,
essentially unchanged in this run; 13/20 pairs were lower and the median paired
change was -2.75%. Those different statistics must not be conflated. No stable
extreme-tail guarantee is established for any variant.

All eight raw LLVM artifacts match the corresponding Mac inputs. Disassembly
regenerated from the returned ELFs matches every captured instruction. Runtime
objects and the emitted always-inline function sets are identical across all
variants. The representative initialized/enum-specialized createLimit clone
has 282 static instructions and 52 branch instructions in baseline, 266/44 with
bounds, 279/52 with stores and 264/44 with both. Counts include cold paths and
alignment instructions; they are not dynamic instruction measurements.

The bounds version has one unsigned comparison/branch where baseline tests
negative indexes separately. All four raw accesses to Order.resting disappear
from each entry point with store elimination. Comparing bounds with both in
the inspected warm clone, after normalizing addresses and padding, shows two
byte-store removals and two zero-vector-store offsets changing by one byte.
Its registers, stack operations, calls and branch structure otherwise match.
This is concrete work removal, without a changed inlining selection or new
calls/spills in that function. Address/layout changes and store combining can
affect timing, but this audit does not establish the cause of the weaker
combined average gain. No hardware counters were collected.

Across the three timing windows (203.20, 204.94 and 206.27 seconds), the governor
remained powersave. CPU 1 received no numbered hardware interrupts, and CPU 9
had only idle ticks. Other CPUs received network interrupts. These observations
do not exclude timer/IPI activity or identify a cause for individual outliers.

**Accepted disposition: retain both general compiler optimizations.** The
maintainer explicitly accepted the technical recommendation and requested
committing both together. Bounds-only is the strongest OrderBook variant in
this run, and adding store elimination does not preserve its full average gain.
The comparisons were each against baseline, not a direct paired bounds/both
experiment. Do not add independent gains or present their ranking as a universal
policy result.

Both transformations use semantic proofs rather than guessed hotness: the
redundant predicate is equivalent for every valid array length, and removed
stores have no permitted observer. Both independently improve the Linux run,
and the combination retains a substantial throughput gain and a modest average
latency gain. The inspected combined code removes work without introducing a
new hot-path mechanism. That supports a favorable expected overall compiler
tradeoff. Disabling a sound general elimination solely to preserve one binary's
best layout is not the chosen policy. The adverse Mac throughput result and
the Linux interaction remain real counterevidence to universal-speedup claims;
semantic safety does not imply monotonic wall-clock performance. No broad
application survey or repeat-until-positive run is required for this decision.

The archive, independently recomputed summaries, environment observations,
machine-code excerpts and detailed review are preserved under
`workspace/perf-improvements/round2/stage3/work-elimination/linux-evidence/`.
The unrelated assignment-order/destructor issues remain unresolved and separate.

## Round 2 Stage 4: retained enum-field propagation

**Accepted disposition: retain as a small latency win.** The maintainer approved
the compiler optimization and its commit after reviewing the Linux return.
The comparison is against `92174187a1c2dc2a5ed67d41f05a38aaf558bf54`, which
includes the accepted bounds and unread-store changes above. Both Ironwood and
Java application implementations, runtime and standard library remain unchanged.
Native Image was not rebuilt or timed; these percentages must not be combined
with its earlier comparison. Value propagation/load forwarding was subsequently
retained in D178, documented below; the bounded overwritten-store experiment
was subsequently rejected in D179.

### Proof and focused verification

Inside existing state-2 fast paths, the compiler substitutes proven final
`int`/`long` fields of exact enum receivers and provably pure accessor results.
Literal construction, exactly-once publication and bounded constructor
evaluation establish the facts. Dynamic values, unsupported instructions,
constructor delegation, constant-specific subclasses, foreign writes and
native field addresses decline the proof. Runtime initialization, constructors,
publication, fallback paths, caller checks, operand evaluation, layouts and
mandatory ownership validation remain. No new guards, alias metadata,
bookkeeping, PGO or application-specific rules are added. D177 and
[IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md#37-guarded-fully-initialized-specialization)
record the retained boundaries.

All 11 selected compiler tests passed on macOS ARM64 and Linux x86_64, including
proof/refusal cases, final metadata through generic rebuilding, cold/warm and
recursive/failed initialization, safety in every mode and source/class/archive
reconstruction. Both baseline and candidate passed the four unchanged OrderBook
correctness/allocation checks on each host. Deterministic report output matched,
smoke runs succeeded and invalid arguments were rejected as expected. No full
suite or local performance claim was needed for this experiment.

### Returned Linux measurements

The return archive `ironwood-stage4-enum-fields-g52qtzf2.tar.gz` has SHA-256
`4d4e43edb7e51d497a36485336c90a639f7ccc2bb2b61c4be76897506b02030b`.
All 8,160 artifact hashes, the exact inventory, 16 returned input files and the
3,035 baseline / 3,038 candidate source files matched the delivered manifests.
The measured patch SHA-256 is
`24b47a09d70ebfb34b68d3286c508f2d5bde3dd1e6a2ddc381fc52b27255ce71`;
only acceptance documentation changed afterward. Independent parsing reproduced
every summary value from all 56 successful process outputs with empty stderr,
verified timed executable identities and CPU binding, and checked pair order.

The Xeon E-2288G run used Python 3.6.9, Java 25.0.4 and LLVM 23.1.0. Both
snapshots were freshly built with O3, native CPU selection, ordinary inline
threshold 1000 and selective inlining enabled. Backend commands match after
path normalization. All benchmark children were pinned to CPU 1. Twenty latency
pairs used `10000 50000 1000`: 10,000 warmup and 50,000 measured batches of
8,000 operations each. Eight throughput pairs used `8 80`, with 80 million
measured operations per process. Odd pairs ran baseline first; even pairs ran
candidate first. Every sample was retained.

Negative changes mean less time. Absolute values are medians of per-process
reports, not pooled batch statistics. Latency is microseconds per batch;
throughput is elapsed milliseconds. The paired column is the median of each
pair's percentage change, which can differ from the ratio of the two medians.

| Metric | Baseline median | Candidate median | Change of medians | Median paired change | Candidate lower |
| --- | ---: | ---: | ---: | ---: | ---: |
| Average batch | 78.5840 | 77.2125 | -1.75% | -1.98% | 19/20 |
| p99 | 80.4970 | 79.1630 | -1.66% | -2.02% | 19/20 |
| p99.9 | 108.0175 | 106.9305 | -1.01% | -1.35% | 16/20 |
| p99.99 | 128.9960 | 125.2560 | -2.90% | +0.50% | 10/20 |
| Maximum | 155.6560 | 157.3910 | +1.11% | -0.38% | 10/20 |
| Throughput elapsed | 762.8639 | 760.2745 | -0.34% | -0.22% | 5/8 |

Average and p99 gains persist in both order groups: baseline-first versus
candidate-first paired medians are -1.84%/-2.05% for average and -1.91%/-2.52%
for p99. Throughput ordering splits disagree (+0.53%/-0.81% elapsed), and
extreme-tail direction agrees in only half the pairs. Retention rests on the
consistent modest latency gain and confirmed removal of unnecessary work,
not an established throughput, worst-case or general compiler speedup.

The 204.63-second timing window retained the existing powersave governor and
enabled turbo. CPU 1 and SMT sibling CPU 9 were listed in `isolcpus`; CPU 9
accumulated only idle ticks. Timer and other interrupts remained on CPU 1.
Before/after environment snapshots cannot explain individual outliers or
establish constant frequency throughout the run.

### Generated code and evidence

Independent disassembly of all four timed ELF executables matched the returned
instruction listings. Direct enum-index load sites in optimized LLVM fell from
6 to 2 for throughput and 14 to 2 for latency, matching the earlier ARM inspection.
These are static sites referencing exact enum globals, not dynamic execution
counts; two other setup reads remain in each candidate.

The specialized BUY limit path replaces an enum-field load and dynamic array
indexing with constant-offset access, retaining bounds protection. The dynamic
side matcher replaces enum-object selection and a field load with identity
comparison results. The x86 backend repeats that comparison in the inspected
sequence; this is not literally a single comparison instruction. Null and
bounds failure paths remain. These changes confirm the intended simplification,
although scheduling, register allocation and layout can also affect timings.

The return archive, source/binary identities, independently recomputed results,
disassembly excerpts and detailed review are preserved under
`workspace/perf-improvements/round2/stage4/enum-fields/linux-evidence/review-wdlc2nlx/`.
The measured compiler and test sources are unchanged at acceptance. This is
evidence for this workload on this host, not a guarantee of improvement for
every program or architecture.

## Round 2 Stage 4: retained field value forwarding

**Accepted disposition: retain for measured latency and throughput gains.** The
maintainer approved the compiler optimization and its commit after reviewing the
Linux return. `FieldValueForwarder` tracks exact integer/reference field
values along single-predecessor paths and models small leaf getter/setter calls.
Every same-slot write invalidates possible receiver aliases. Unknown effects,
initialization, reclamation, joins and exceptional edges discard facts. Stores,
checks, evaluated operands and layouts remain; no alias metadata or pooled-object
freshness assumptions are introduced. D178 records the proof boundaries.

The canonical checkout retains accepted D177 at `3f6d91c`. The Linux package
isolated this experiment against the original `9217418` reference: both
packaged source snapshots exclude D177, and only the forwarding implementation,
registration, tests and fixture differ. The working-tree composition passed
separate correctness checks. No combined performance comparison, application
change or new Native Image build is included.

Local validation on macOS ARM64 with JDK 25.0.4.1 (`--release 21`) and LLVM 23.1.0
passed 12 distinct focused compiler tests in the working checkout and 10 in the
independent candidate snapshot. After fixing the new pass's treatment of a plain
throw's nominal continuation, only affected or changed tests were rerun. New
native coverage uses O0/O3 source, class and archive paths; checks include exact
and possible aliases, inherited/hidden/generic fields, mutable reuse, unknown
and virtual effects, nulls, signed floating zero, try/finally cleanup and unsafe
free rejection in every mode. Existing alias, initialization, unread-store and
trace tests protect adjacent consumers. No unfiltered suite was run.

Both independently rebuilt source snapshots passed the four unchanged OrderBook
correctness/allocation checks, three smoke commands and five invalid-argument
cases. Deterministic report output was byte-identical. All application, runtime
and library sources match `9217418`.

Optimized LLVM retains fewer loads in relevant functions: the throughput
`Bench.run` body has 220 baseline load sites versus 210 candidate sites, and the
specialized BUY limit-creation body has 64 versus 61. ARM machine code confirms
three pool-counter reloads disappear from that limit path: after storing a
reference into a pool array, the candidate increments an already available
counter value rather than reloading the counter field. This follows typed
array/field storage separation, not an assumption that the pooled objects are
fresh. The enum payload loads remain, confirming D177 is absent from this
comparison. Static instruction changes do not establish a timing improvement.

The self-contained Python 3.6-compatible Linux handoff rebuilds both snapshots,
runs the 10 selected candidate compiler tests and both application check sets,
then measures 20 reversed latency pairs and eight throughput pairs on CPU 1.
Arguments remain `10000 50000 1000` and `8 80`. It preserves all 56 process
reports, source/binary identities, actual timed executable disassembly, LLVM,
commands and environment snapshots in a failure-aware return archive. Evidence
and packaging sources are under
`workspace/perf-improvements/round2/stage4/load-forwarding/`.

### Linux results and acceptance

The Linux return completed successfully on the Intel Xeon E-2288G, using Java
25.0.4 and LLVM/Clang 23.1.0. Both variants used O3, native CPU targeting, inline
threshold 1000 and the same selective-inlining settings, with no PGO. The ten
selected compiler tests passed, and both variants passed the four unchanged
OrderBook correctness/allocation checks, three smoke commands and five expected
invalid-argument rejections. Deterministic report outputs were byte-identical.

All 56 timed process records matched the planned CPU 1 binding, workload,
alternating order, successful exits, empty stderr and executable hashes. The
trusted local auditor and a separate raw-report parser reproduced the results.
No observations were discarded or retried. Each latency process measured 50,000
batches of 8,000 operations after 10,000 warmup batches; each throughput process
measured 80 million operations after warmup. These are medians of per-process
statistics, not pooled batch percentiles or individual-operation latencies.

Changes below use `(candidate / baseline - 1) * 100`; negative means less time.
The paired column is the median of the adjacent pairs' percentage changes, which
can differ from the change between the two medians.

| Metric | Baseline median | Candidate median | Paired median change | Change of medians | Candidate lower pairs |
| --- | ---: | ---: | ---: | ---: | ---: |
| Average batch latency | 78.4925 us | 74.6735 us | -4.93% | -4.87% | 19/20 |
| p99 batch latency | 79.5010 us | 75.8825 us | -5.08% | -4.55% | 19/20 |
| p99.9 batch latency | 107.4400 us | 103.7705 us | -3.58% | -3.42% | 19/20 |
| p99.99 batch latency | 122.7475 us | 119.6335 us | -1.95% | -2.54% | 13/20 |
| Maximum batch latency | 150.8965 us | 148.2315 us | +0.20% | -1.77% | 10/20 |
| Throughput elapsed time | 757.9214 ms | 732.4032 ms | -3.32% | -3.37% | 7/8 |

Average latency and p99 improve in both execution-order groups: paired changes
are -4.99%/-5.11% with baseline first and -4.79%/-4.96% with candidate first.
Throughput elapsed time changes -3.45% and -3.26% in those groups. The shorter
eight-pair throughput comparison supports the central latency result. Latency
pair 11 and throughput pair 4 are the respective reversals and remain included.
Extreme tails do not establish a consistent improvement: p99.99 has opposing
order-group directions, and maximum latency is evenly split.

CPU 1's SMT sibling 9 accumulated only idle ticks between timing snapshots.
The governor remained `powersave` with turbo enabled; endpoint frequency readings
were about 4.90 GHz. Interrupt activity remained present, and the aggregate
environment snapshots cannot explain individual slower processes. The result
does not establish a speedup for every workload, host or architecture.

The local LLVM disassembler independently decoded all four timed ELF binaries;
their instruction streams matched the returned disassembly. The x86 specialized
BUY limit path replaces three memory-form counter increments with register
arithmetic and stores of already available counter values, removing the redundant
memory reads while preserving checks and required stores. Its disassembled
instruction-line count rises from 264 to 267, including alignment instructions:
fewer memory reads do not require fewer x86 instructions. The LLVM load-site
reductions match the earlier ARM evidence. This supports the intended mechanism
without attributing the full measured gain to those three sites alone.

The returned 8,142 artifact hashes and 18 retained input files match their
manifests and the original delivery; all 3,035 baseline and 3,038 candidate source
files match the delivered source identities. The compiled application classes
and runtime object files are byte-identical across variants. Backend commands
match after normalizing variant and generated temporary paths. Exact identities:

- Baseline commit: `92174187a1c2dc2a5ed67d41f05a38aaf558bf54`.
- Candidate patch SHA-256: `b3ea2a0e98beae4681fca7ad7999a1b80dcf72dc9f6eefa47c198d8cff3c3534`.
- Delivered package SHA-256: `71dd4856cb1def7ef302f418972e364545b6737ad9b10d9c49a6228582ca7a31`.
- Return archive: `ironwood-stage4-load-forwarding-fzo7mhuc.tar.gz`, SHA-256
  `d3688bfebd3343e71cb682ad7c89dce6cba569a69043468b75b3387b5521bd08`.
- Baseline bench/latency ELF SHA-256:
  `b7b239dcfbcafb61b0f3cc6640ccb38521bbd17ce44a4ec7d2c0a65a505619e8` /
  `e9577f6c5ed97cc6755b7d12fa8f156b2e8171aeb274e845f4ee0e66b636ffe2`.
- Candidate bench/latency ELF SHA-256:
  `0e2e6c866c7523ba452b6a7d0f8e9d1916d9ef18b5e782ae3ed4cb609a3de4b2` /
  `ce0e6cea50978ed2dcf3f1db42376a6bbbb510afedf0782cee49e86ee1681480`.

The archive, independent calculations, machine-code excerpts and full review
remain under
`workspace/perf-improvements/round2/stage4/load-forwarding/linux-evidence/review-e2v1u8j5/`.
The measured compiler and test sources are unchanged at acceptance. Retention
does not imply additive gains with D177; combined performance and an updated
Native Image comparison remain unmeasured. The final independent Stage 4
experiment, overwritten-store elimination, was subsequently rejected in D179.

## Round 2 Stage 4: overwritten-store elimination experiment

**Accepted disposition: record a negative result and remove the candidate.**
The maintainer approved removal after the Linux review confirmed the local
finding: the bounded pass removed no stores from unchanged OrderBook and produced
no LLVM or native instruction change. The uncommitted implementation, final-link
registration, three dedicated test registrations, test class and integration
fixture were removed. D179 records the decision. Accepted D177 enum-field
propagation and D178 value forwarding remain unchanged.

### Experiment boundaries and local verification

The experimental `OverwrittenFieldStoreEliminator` ran after closed-world pruning
and unread-field store elimination at final link. It removed an earlier write
only when an exact receiver and declaring owner/layout slot was overwritten
before any possible observation or exceptional exit. Same-slot reads invalidated
all possible aliases. The proof followed acyclic single-predecessor paths and
unconditional edges; repeated null checks could continue it only after a
successful access established nonnull. Original checks and CFG edges remained.
Unknown branches, joins, backedges, calls/invokes, initialization, allocation,
native effects and reclamation ended the proof. Native-address-exposed fields
and runtime-owned layouts were excluded. Last writes, array/static stores,
operand evaluation and source identity remained.

The independent baseline was
`92174187a1c2dc2a5ed67d41f05a38aaf558bf54`. Both packaged snapshots excluded D177
and D178; only five compiler/test/fixture files differed. The checkout at
`85288ab` retained those accepted passes and received separate composition checks.
Both application implementations, runtime and library sources stayed unchanged.
No ownership/escape summaries, alias metadata, inlining policy or runtime
bookkeeping changed.

On macOS ARM64 with Java 25.0.4.1 (`--release 21`) and LLVM 23.1.0, 15 distinct
focused compiler tests passed in the working checkout and ten in the independent
candidate. Coverage paired exact overwrites with possible-alias observations,
receiver/owner/slot identity, native/runtime exclusions, unknown effects,
null/exception timing, generic/hidden fields, reference and floating values,
cleanup and mandatory safe/unsafe reclamation in every mode. Native cases ran
through source/class/archive paths at O0/O3. Existing alias, unread-store,
initialization and trace tests protected adjacent consumers; composition checks
covered enum-field propagation and value forwarding. No unfiltered suite ran.

Both fresh independent builds passed the four unchanged OrderBook correctness/
allocation checks, three smoke commands and five expected invalid-argument
rejections. Deterministic report output was byte-identical. The pass removed
stores in focused fixtures but zero stores in unchanged OrderBook:

| Local evidence | Throughput entry | Latency entry |
| --- | ---: | ---: |
| Compiler-emitted LLVM | byte-identical | byte-identical |
| Optimized LLVM, excluding generated ModuleID comment | identical | identical |
| Decoded ARM instruction streams | identical, 7,004 lines | identical, 23,057 lines |
| Optimized LLVM store sites, baseline/candidate | 366 / 366 | 1,635 / 1,635 |

Diagnostic LLVM DSE probes after O3, including expanded scan/walk/path limits,
removed no additional stores. Those probes did not change the candidate backend
flags or pipeline. No local timing comparison was run.

### Linux results and rejection

The return completed successfully on the Intel Xeon E-2288G with GraalVM JDK
25.0.4 and LLVM/Clang 23.1.0. Both variants used O3, native CPU targeting and the
same backend commands except build paths. All ten focused compiler tests passed.
Both variants passed four OrderBook correctness/allocation checks, three smoke
commands and five expected invalid-argument rejections. Deterministic reports
were byte-identical with empty stderr. All seven application class artifacts
and all four runtime object files per benchmark were byte-identical.

The trusted local auditor verified all 8,023 returned artifact hashes. All 22
original input hashes validated; the 20 inputs retained in the return matched
the delivery. All 3,035 baseline source files matched the exact `9217418` Git tree,
and all 3,038 candidate files matched the frozen candidate. The two source tar
inputs were intentionally replaced by extracted source trees in the return.
No returned scripts or executables were executed during this review.

Independent local LLVM 23.1.0 disassembly of all four actual ELF executables
matched the returned instruction streams, retaining addresses and operands.
Executable section bytes, addresses, lengths and hashes matched independently
parsed ELF sections. Both variants produced:

| Linux evidence | Throughput entry | Latency entry |
| --- | ---: | ---: |
| Compiler-emitted LLVM | byte-identical | byte-identical |
| Optimized/traced LLVM, excluding generated ModuleID comment | identical | identical |
| Compiler LLVM store sites, baseline/candidate | 286 / 286 | 482 / 482 |
| Optimized LLVM store sites, baseline/candidate | 369 / 369 | 1,645 / 1,645 |
| Decoded x86 instruction streams | identical, 7,237 lines | identical, 22,437 lines |
| Executable sizes, baseline/candidate | 306,848 / 306,848 bytes | 757,256 / 757,256 bytes |

Whole executable hashes differ: every differing byte is confined to the
`ironwood_trace` section, whose location and length are unchanged. Captured
`program.o` differences are confined to `.pseudo_probe`. Every other byte of
each executable and program object matches. This is code identity, not a claim
of whole-file identity. Static store-site counts are not executed-store counts.

The package's unchanged-code gate correctly skipped the 56-process timing
comparison. Status records success, skipped timing and no completed timing
comparison; there is no measurements directory. This is a successful negative
code-generation result, not an interrupted run. Smoke timing values were not
used as performance samples. No speedup or measured zero-percent change is claimed.

Exact identities:

- Candidate patch SHA-256: `3c355cf01812e429a8461982eba1d623a3ce4ad7be390be3b92e9f63368dda3d`.
- Delivered package SHA-256: `b6f7e06a7e45ef5e5ad34723753dcb35e263af3c746319bdf43653df0f5d9415`.
- Return archive: `ironwood-stage4-overwritten-stores-7cuc_bv5.tar.gz`, SHA-256
  `052aa5e3b9321ca53fa37ba8a88b0d8297150fddb8dd24da7b8964f1e09eda08`.
- Baseline bench/latency ELF SHA-256:
  `676eeaa17a5d7e0e7cd2a3c6a7634363b68bbb7b5a75b9461abc1847f1286858` /
  `e9126bc5deabf172c440e7f45b2ccc5abf42bf440b8d08aea3784d374c8e55eb`.
- Candidate bench/latency ELF SHA-256:
  `199998b6ceb93d34efed4ace73c51591ba8275e6c261062fdc944ce4d755ac65` /
  `cedf4b31a7cc068c0ba197c7378d1cd7f879b25ee2f1f52a686f338d9453c1c6`.

The frozen candidate, package and local evidence remain under
`workspace/perf-improvements/round2/stage4/overwritten-stores/`; the returned
archive audit, independent disassembly and full review remain in its
`linux-evidence/review-eydj4p63/` directory. The experiment is reproducible from
those preserved inputs even though its pass and dedicated tests are removed.

All three planned Stage 4 experiments have now been investigated: D177 and D178
were retained for independently measured gains; D179 was rejected for no
generated-code benefit on this workload. This does not establish that broader
interprocedural store optimization is impossible. Combined D177/D178 performance
and an updated Native Image comparison remain unmeasured.

After removal, compiler and integration source trees match accepted `85288ab`
exactly. A fresh build and four selected regressions passed: enum-field native
artifacts, forwarding native artifacts, forwarding safe/unsafe reclamation, and
unread-store source/class/archive behavior. License, documentation consistency
and diff whitespace checks passed. No new timing run or full suite was needed.
