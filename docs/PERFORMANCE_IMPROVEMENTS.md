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
