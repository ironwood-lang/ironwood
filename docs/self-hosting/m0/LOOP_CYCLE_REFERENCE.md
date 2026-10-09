<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Source loops and cyclic effect reference

M2's selected frontend corpus includes nested source `for` loops at depths
16/64/128, and LoopForms with `for`, `while`, `do` and enhanced array iteration.
The latter reclaims its primitive array after iteration and returns zero when
the accumulated result is eight. LoopFormsUnsafe instead frees that array in
the enhanced loop body. Its mandatory error names the still-observable
enhanced.source.0 alias. All three unfreed settings reject it, including off;
the missing-free setting cannot disable this safety obligation.

Original J0 and ordered J0-D247 each retain twenty final fresh canonical captures:
five sources, explanations off/on and two repeats. Every raw token/span, AST,
diagnostic, typed-IR, final-IR, LLVM and status artifact agrees across the twenty
cross-seed pairs. The four actual AST kinds are ForStatement, WhileStatement,
DoWhileStatement and EnhancedForStatement. These observations supply fixture
coverage; the independent finite model still defines consumer coverage.
Four ordered-seed compile/link/run controls use `--unfreed=error` and `-O3` and
exit zero. A separate three-command rejection check uses the final unsafe source.

The effect-cycle adapter constructs actual IrFunctions whose call edges form
F0 -> F1 -> ... -> F0. The last function also has a foreign call that seeds
allocation, outward throw and all published/returned/reclaimed parameter bits.
The actual ClosedWorldEffectAnalyzer propagates those facts to every function.
Sizes are 8/32/128 functions with 65 parameters, plus sixteen functions with
8/257 parameters, exercising cross-word vectors. Optional retained observer
calls record exactly count + 1 rounds. The extensional output uses explicit
input function order; it does not sort diagnostics, IR or hash-selected witnesses.

The selected cycle reference contains forty fresh processes per seed: five
cases, observer off/on, sampler off/on and two repeats. Every result agrees
across those configurations and forty cross-seed pairs. A deliberately changed
allocation fact with its artifact hash updated fails exact repeat parity;
removing a repeat fails configuration completeness. Native M2 must enforce this
finite call/foreign-call/return/value/type boundary and match the facts. Other
IR variants and class-dispatch inputs remain gated by their model treatment.

Resource measurements use the pinned macOS arm64 profile and separate setup,
phase and verification/projection costs. The cyclic phase includes actual
analyzer construction/analyze and adapter control, while input construction and
result assertions/projection are separate. Sampled used heap and capped Java
frames are lower bounds, not live-object bytes or stack bytes. Fresh-process
cost includes startup and all three phases. Source-loop complete compilation
includes seed analysis and LLVM text generation, with no native tool children.

The first loop/cycle timings overlapped canonical captures and other review
activity. Preserve them as provisional observations; isolated serial replays
provide the final resource reference. Neither measurement set is a native
compiler implementation or a selected native budget. The original and ordered
identities and earlier evidence archives remain unchanged.

Final isolated maxima follow. RSS is bytes reported by macOS time; used heap
is the sampled JVM value. Each column can have its maximum in a different run.

| Workload | Fresh-process seconds | RSS bytes | Sampled heap bytes | Sampled frames | Maximum sample gap ms |
| --- | --- | --- | --- | --- | --- |
| Source-loop frontend | 0.428837 | 268238848 | 49131664 | 465 | 1.548292 |
| Source-loop complete compilation | 2.913859 | 714063872 | 338988192 | 1024, capped | 10.348541 |
| Cyclic effects, original J0 | 0.176272 | 258473984 | 158952176 | 42 | 1.980041 |
| Cyclic effects, ordered J0-D247 | 0.223643 | 261570560 | 158926848 | 46 | 1.945583 |

Sampled/unsampled fresh-wall median ratios are 0.9755-1.0152 for the loop
frontend and 0.9755-0.9943 for complete compilation; cyclic original ratios
are 0.9465-1.0926 and ordered ratios 0.8894-1.0758. These short-process noise
observations are not a claim that sampling improves performance. The source
loop stack cap remains an explicit lower-bound limitation.

The deterministic loop-cycle-reference.tar.gz archive and its JSON manifest
retain final captures, provisional overlapped measurements, preceding comments,
all controls, exact source versions and raw stdout/stderr. Archive qualification
compares every member's byte count and SHA-256; it does not normalize output.

Reproduce the five canonical workloads with freeze-corpus.py `--workload`;
measure the four positive loops with measure-resources.py, then qualify with
qualify-resources.py. Run measure-kernels.py `--kernel-set effect-cycle` for each
seed. qualify-loop-controls.py checks native behavior; its `--unsafe-only`
option repeats only changed unsafe source. qualify-loop-cycle.py checks raw
cross-seed parity and the two deliberate mismatch controls. Two development
checker attempts used wrong AST metadata keys; their reconstructed source and
error descriptions are labeled separately from raw process evidence.

Earlier forty loop captures inherited an inaccurate positive-program comment
in the unsafe variant. Those raw bytes are retained as an explicit predecessor;
final captures change only that comment and the resulting source offsets/hash.
Production compiler, runtime and standard-library source are unchanged.
