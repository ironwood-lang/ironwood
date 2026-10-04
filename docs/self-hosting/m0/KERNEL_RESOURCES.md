<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Direct seed kernel resource evidence

The [exact archive](kernel-resources.tar.gz) and
[manifest](kernel-resources-manifest.json) retain 451 files for 104 fresh serial
processes, two rejected comparison controls, tooling, identities and raw output.
There are 52 runs each for original J0 and the distinct qualified J0-D247 seed.
All 52 paired result hashes match across seeds. The pinned production sources
RejectedFreeEvidence, ClosedWorldEffectAnalyzer and SemanticAnalysisObserver
are unchanged between their identities. This does not relabel original J0.

The procedure uses Java 21, the qualified 4 GiB heap/8 MiB stack profile,
cleared Java option variables and macOS /usr/bin/time -l. Commands, seed/tool
hashes, hardware, process wall/user/sys/RSS and raw stderr are retained.
Reproduce with `python3 scripts/self-hosting/measure-kernels.py --output NEW_PATH`
for J0-D247. Add `--identity docs/self-hosting/m0/qualified/identity.json
--baseline-label original-J0` for the original seed. Qualification requires two
fresh repeats of every selected configuration; no warmup or forced GC is used.

## Exact measured workloads

The evidence workload invokes the actual optional explanation store, not a
substitute collection or ownership-proof algorithm. Construct 8/32/128 distinct
allocation keys and six populated fact maps. Node hashes collide; array-slot
value keys use colliding spread indices. Each of 64 iterations saves a complete
version, replaces the first event/join with distinct equal-looking values,
saves a second version, intersects those versions and restores the first.
Intersection must discard exactly the replaced identity event/join and preserve
binding allocation identity and another node's event/join. Strong snapshot-key
storage retains all 128 versions until explicit close, so GC retirement does
not substitute for close. The measured phase includes close. Its exact results
include association totals, 64 correct intersections, snapshot/invocation
high-water units and final live budget zero. The 128-node case reaches 98,304
saved associations and 100,224 invocation high-water units without truncation.

This is explanation-storage evidence. The real FunctionAnalyzer branch-store,
join and detach workloads in [RESOURCE_MEASUREMENTS.md](RESOURCE_MEASUREMENTS.md)
remain the measurements of ownership-proof snapshots. Neither one establishes
native allocation lifetimes or replaces the paired mandatory safety tests.

The effect workload constructs real typed IR before timing. F0 calls F1 and so
on, with the final function making a conservative foreign call. Source-order
fixed-point rounds propagate effects one function per round. Measure chains of
8/32/128 functions with 65 parameters, and independent parameter widths 8/257
with 16 functions. Constructor plus analyze are timed; IR construction and
observerProjection/string verification are outside the phase. Every function
must publish, return and possibly reclaim all parameter bits, allocate and throw
outward. Retained-observer runs require exactly chain length plus one rounds,
including the final unchanged round. Its real interface callbacks count and
check rounds; null-observer runs remain separate. All projection results are
byte-identical with observation/sampling off or on. The extensional projection
is emitted by named key in input function order, consistent with the reviewed
consumer contract; no diagnostics or typed IR are normalized.

Both kernels have sampling off/on configurations and two fresh repeats; effect
also has retained observation off/on. The worker readiness/start gates, heap at
phase start, sampled used heap/frame maxima, sample count and maximum gap use the
same procedure as complete compiler measurements. Adapter loop/control checks
are included in phase cost. Construction, result verification and serialization
are separate; whole-process cost includes all of them. This is a cold seed
measurement, including JVM compilation, rather than a warmed steady-state claim.

## Qualification and observed values

Both 52-run qualifiers pass exact result comparisons, expected fixed-point
counts, sampling sentinels and explicit explanation-budget cleanup. A changed
result byte and a removed fresh repeat each cause qualification to fail. The
archive retains those rejection commands and raw errors. All adapter compilation
uses Java 21 --release 21, -Xlint:all and -Werror.

| Baseline | Maximum process wall | Maximum process RSS | Maximum sampled used heap | Maximum sampled frames |
| --- | --- | --- | --- | --- |
| Original J0 | 0.174 s | 243.703 MiB | 151.678 MiB | 46 |
| J0-D247 | 0.175 s | 245.734 MiB | 151.758 MiB | 43 |

Original phase ranges are 11.724..49.558 ms for evidence and 14.618..94.189 ms
for effects. Ordered ranges are 11.862..56.779 ms and 14.695..95.378 ms.
Construction reaches 27.811 ms and projection/result verification 9.892 ms in
these runs. Sampled/unsampled process-wall median ratios span 0.982..1.111 for
original J0 and 0.932..1.108 for J0-D247. Values below one reflect variation,
not a speed benefit. Maximum sampling gaps are 1.919 ms and 2.124 ms respectively.

These are observations, not budgets or upper bounds. Sampled heap is used heap,
not live-object accounting; sampled frames are neither stack bytes nor guaranteed
depth maxima and remain subject to the pinned trace cap. Completing under the
8 MiB worker stack is required. LLVM/Clang child costs, canonical corpus expansion,
numerical budget selection and the full dependency/ordering inventory remain
open before M0/S0 can pass. No native pilot has been evaluated.
