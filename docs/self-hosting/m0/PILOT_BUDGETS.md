<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Fixed M2 pilot resource budgets

These limits are fixed on 2026-10-04 before any native pilot is implemented or
evaluated. They apply only to the qualified macOS arm64 Apple M5/32 GiB profile,
the pinned original/ordered seeds, and the finite frontend/operation input roles
in FRONTEND_PILOT.md, OWNERSHIP_PILOT.md and OPERATION_MODEL.md. Other hosts need
their own baseline and numerical decision before qualifying a native run.
Do not extrapolate these limits to a full S3/S4 compiler build or S7 resident.

| Native M2 workload | Fresh-process wall maximum | Process RSS maximum | Measured operation-phase maximum |
| --- | --- | --- | --- |
| Lexer/parser/source construction | 2 seconds | 1 GiB | Recorded separately; no additional phase cap |
| Actual seven-field ownership snapshots | 1 second | 512 MiB | 250 ms |
| Private explanation snapshot store | 1 second | 512 MiB | 250 ms |
| Effect chains/cycles and cross-word vectors | 1 second | 512 MiB | 500 ms |

Every run must finish within every applicable cap; two fresh repeats per selected
configuration are the minimum. Use the same input shapes, snapshot iteration and
retained-version counts, observation/explanation cases and artifact work as the
reference. Geometric scales and reverse predecessor/current-store roles remain
separate cases. Successful smaller cases cannot substitute for the maximum
scale. The frontend comparison corpus comprises the 36 frozen source workloads;
21 of those have dedicated Java resource measurements. The remaining fifteen
canonical sources still receive the same native cap, without claiming individually
measured Java timings for them. Native tokens/spans/AST/diagnostics must match
the canonical source references independently of this resource gate.

The exact frontend names are BranchJoin, SlotOrder, Volume16/64/256,
Control16/64/128, Types8/16/32, Ownership8/32/128, GenericInference,
CapturedAliases/CapturedAliasesUnsafe, TextBlocks, ClassicSwitch, ModernSwitch,
InstanceOfPatterns, MultidimensionalArrays, DeterministicResources, UnicodeSource,
NumericForms, ShiftGenerics, BadEscape, BadRadix, UnterminatedComment,
UnterminatedBlock, TruncatedParse, Loops16/64/128 and LoopForms/LoopFormsUnsafe.
Their original canonical archive and final loop archive pin every source byte.
Ownership uses 8/32/128 nodes, shapes 1-4, 64 iterations and 128 held branch
versions plus a merged comparison version, explanation off/on and ample/tight
invocation budgets. Explanation uses 8/32/128 forced-collision nodes and 64
iterations/128 held keys. Effects use chains and cycles of 8/32/128 functions
with 65 parameters, plus sixteen functions with 8/257 parameters, observer
off/on. Paired sampling off/on and two repeats cover each applicable mode.

The representative compiler-source consumer is also mandatory before G1. M1.1/
M1.3 deliver and hash their collection/text helper `.iron` sources. M2.1 supplies
the translated Lexer/Parser/AST consumer sources and completes the combined
manifest against the 376-method frontend closure and its finite model before
qualification. Helpers precede consumers; M1 does not deliver frontend translation.
M2.1 must run both Java and native lexers/parsers on that real
source bundle, compare every unit's tokens/spans/AST/diagnostics, and measure the
entire bundle in one invocation while retaining its required input/output graphs.
It has the same already fixed 2-second/1-GiB frontend and stack limits. Per-file
success cannot substitute for this combined retention workload. Retain fresh J0
resource runs and exact bundle bytes before evaluating native results. This
concrete M1/M2 obligation supplies section 3's real compiler-slice gate without
claiming that future translated sources are already frozen or measured in M0.

Budget derivation uses 88 frozen frontend runs, 96 ownership runs, 24 explanation
runs and 160 effect runs. pilot-budgets.json pins each archive/member SHA-256,
retains the exact maximizing run labels and computes headroom. It excludes all
112 complete-compiler runs in these selected records, the overlapped loop/cycle
timings, aggregate-only/failed setup versions and deliberately corrupted controls.
The original SlotOrder explanation instability is preserved in full compilation;
it does not affect these original frontend token/AST measurements. Ordered D247
remains the selected downstream reference for the repaired diagnostic path.

| Workload | Java fresh wall maximum | Java RSS maximum | Budget/wall headroom | Budget/RSS headroom | Budget/phase headroom |
| --- | --- | --- | --- | --- | --- |
| Frontend | 0.459568 s | 301858816 bytes | 4.352 | 3.557 | n/a |
| Ownership | 0.164455 s | 97845248 bytes | 6.081 | 5.487 | 4.049 |
| Explanation | 0.131990 s | 84328448 bytes | 7.576 | 6.366 | 4.403 |
| Effects | 0.223642 s | 261570560 bytes | 4.471 | 2.052 | 3.832 |

These deliberately bounded feasibility allowances tolerate cold-process noise,
serialization and different native layouts. They are thresholds to test, not
performance predictions or permission to introduce avoidable hot-path overhead.
Code/executable size remains unconstrained. Do not raise a limit after observing
a failing native result; record the failure and limiting representation/proof,
then return to its M1 preparation. Any later accepted budget revision needs its
own rationale and preserved preceding results.

The numeric choices round above the measured maxima with at least twofold RSS
headroom and approximately fourfold wall/phase headroom in the tightest groups.
Larger frontend RSS covers retained source/AST outputs and the mandatory real
translated-source bundle; direct kernels admit only their bounded graph roles.
The allowances accommodate bootstrap/serialization differences while keeping
one invocation well below the 32-GiB development-machine capacity. They do not
forecast costs of unimplemented code. The 8,176-KiB native stack choice uses the
actual recorded macOS main-thread limit instead of borrowing the JVM worker's
different 8,192-KiB setting or inventing a native high-water measurement.

Use macOS `/usr/bin/time -l` process rusage for RSS, with the already qualified
byte unit. Sampled used Java heap is not native live-object memory or an RSS
upper bound. Phase boundaries separate input construction and result projection
from actual operations; the fresh-process cap includes them all. Canonical full
compiler/LLVM comparison is separate from frontend or direct-operation timing.
Measure native LLVM/J0 build costs separately; the existing 32 pipeline references
are preparation cost evidence, not qualification of a compiler-sized S3/S4 build.

Externally limit the native pilot main stack to 8,176 KiB, the qualified host's
main-stack capacity, and record effective configuration and exit status. J0's
worker uses 8,192 KiB; capped Java frame samples do not establish byte usage.
Native stack depth/high-water must be reported using separate attributable
evidence; if unavailable, say so and leave the corresponding G1 evidence open.

The reproducible M2 enforcement procedure is: set the soft and hard stack limit
to 8,176 KiB in the launcher before exec, verify it in the run record, launch each
fresh native case under `/usr/bin/time -l -o RUSAGE_FILE`, and retain stdout,
stderr, exit status and monotonic elapsed wall time from the controlling runner.
The runner times out at the selected fresh-wall cap and records that failure.
Read the measured operation interval from the adapter's one start/end timing
pair, then compare wall, kernel-reported peak RSS and phase time against every
applicable JSON threshold. `check-pilot-budget.py` supplies this strict numeric
check and rejects missing/nonfinite measurements; semantic/model/repeat/retirement
qualification remains separately mandatory. Never count sampled RSS or Java
association units as the required kernel peak/retired-byte observations.
No per-call bookkeeping, trace registry or added valid-path checks are authorized
to obtain a measurement. A stack overflow, timeout or memory failure is a failed
case, never a diagnostic comparison success.

After the last comparison and proved retirement, outstanding temporary backing
storage must be zero relative to the retained input/result baseline. Keep actual
allocation events, retained bytes and process RSS distinct from snapshot entry
and optional-budget association counts. Use existing allocator observations where
available; unavailable physical accounting is not a measured zero. Input AST/IR,
source spans and required outputs may survive the invocation as specified. Native
free operations still require compiler proof, including shared child versions.
Explain-on/off primary facts must match, optional store close must end at zero
units, and every mandatory safety-error case must remain rejected. Missing-free
warning count is not a gate and cannot disable those errors.

Measurement processes must run serially without competing capture, compiler,
test, license or archive jobs. Preserve any detected overlap as provisional and
rerun only affected measurements in isolation. Clear the recorded Java option
variables for J0; record effective native environment, tool/seed/library identities
and exact argv. Sampling/observation overhead and maximum gaps remain separately
visible. Resource qualification cannot waive a semantic, model or lifetime gate.

Reproduce the numerical derivation with record-pilot-budgets.py `--output NEW_PATH`.
It reads only frozen Java reports and preserves an existing destination. This
decision completes numerical selection for M0.3's bounded pilots; the global
dependency/ordering classifications and final S0 reconciliation remain open.
