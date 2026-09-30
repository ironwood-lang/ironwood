<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# OrderBook host performance investigation

## Finding, 2026-09-29

The maintainer's host result is reproducible: the ordinary Java Bridge OrderBook
is slightly slower than Java and substantially slower than standalone Ironwood.
The preserved D224 bridge behaves almost identically to the current bridge in
that environment. No missing OrderBook optimization was found.

The earlier reported Linux advantage was conditional on the validation
container's CPU security mitigation and JVM scheduling environment. It does not
establish superiority on the ordinary host. The historical measurements remain
valid for their recorded environment, but that distinction was insufficiently
explained in the performance conclusion.

Both checkouts were clean on `java-bridge` at `94feadec`. The explicitly authorized
Estonia checkout is `/home/developer/temp/Ironwood`, on the same Xeon E-2288G and
Linux 4.15.0-188-generic used earlier. Original artifacts, source and host settings
were preserved. The investigation changes documentation, not compiler/runtime
code or the official OrderBook sources and scripts.

## Reproducing the reported host result

Oracle JDK `21.0.1+12-LTS-29`, original `Bench`, 8M warmup and 80M measured
operations, no CPU pinning or diagnostic JVM flags. Three fresh processes per
scenario, in seeded shuffled order; every process verifies the original workload.
The unit below is one eight-operation cycle, not a per-call latency percentile.

| Scenario | Median ns/cycle | Million operations/s |
| --- | ---: | ---: |
| Current standalone Ironwood | 73.08 | 109.47 |
| Java bytecode | 147.87 | 54.10 |
| Current Java Bridge | 153.76 | 52.03 |
| Preserved D224 Java Bridge | 154.34 | 51.83 |

The maintainer's 31-process medians were 73.44, 151.94 and 154.71 ns/cycle for
native, Java and current bridge respectively. This focused reproduction agrees
with the reported direction. It does not replace those 31 observations with a
larger statistical claim. The old standalone binary measures 82.70 ns/cycle here;
the official current native build uses `-march=native`, while that old baseline
and both bridge payloads use the portable CPU target.

## Isolating the environment effect

The host reports `Seccomp: 0` and
`Speculation_Store_Bypass: thread vulnerable`. The existing validation container
reports `Seccomp: 2` and `Speculation_Store_Bypass: thread force mitigated`.
The container's forced speculative-store-bypass mitigation penalizes these
workloads differently.

To isolate that effect without disabling any protection, a diagnostic wrapper
enables `PR_SPEC_FORCE_DISABLE` for store bypass only in its own process and
descendants. It does not change host settings or relax the Docker policy.
The constants were checked against Estonia's installed Linux `prctl.h`.

The following comparison holds the Oracle JVM, exact artifacts, CPU mask
`1-4,9-12`, driver and operation counts fixed. Both sides use the original D224
settled driver: ten 1M-operation warmup invocations and one 50M-operation timed
invocation. Medians of three shuffled process forks:

| Host child configuration | Java ns/cycle | Current bridge ns/cycle | D224 bridge ns/cycle |
| --- | ---: | ---: | ---: |
| Ordinary host mitigation state | 148.57 | 153.03 | 152.96 |
| Force store-bypass mitigation in the child | 247.48 | 169.09 | 169.08 |

That single security-state change recreates the former bridge advantage using
the maintainer's Oracle JVM, without a container. It is therefore unnecessary
to attribute the reversal to a JVM vendor difference or a compiler regression.
Enabling mitigation for comparisons is a diagnostic control, not a recommendation
to alter security policy or slow a baseline to obtain a favorable ratio.

CPU affinity also matters. With the same settled protocol pinned to CPU 1,
ordinary host Java/current bridge measure 86.95/151.70 ns/cycle. With the child
mitigation enabled they measure 132.94/168.10. Standalone current Ironwood under
the mitigated CPU-1 control measures 122.07 ns/cycle; its ordinary CPU-1
8M/80M control measures 73.66. The affinity observations establish sensitivity;
this investigation does not attribute them to a particular JIT optimization.

Replaying the old container image with pinned Temurin `21.0.12.1+1`, its original
CPU mask and settled protocol gives old native/Java/old bridge medians of
124.18/248.70/169.83 ns/cycle, reproducing the historical 124.00/247.39/172.53
comparison. The current bridge's first three container forks were
173.67/190.00/187.62; these slower observations are retained, not discarded.
Seven additional paired bridge forks give ten-fork old/current medians of
170.03/173.69 ns/cycle, with ranges 168.34-172.85 and 168.16-190.00 respectively.
The current artifact has more variability in that container cell. This small
median difference is not the host speedup reversal, and matching hot instructions
does not establish identical performance under every JVM/code-layout condition.

## Code and artifact checks

- The OrderBook Ironwood and Java source trees have no diff from `694ada30`.
- All six original Java engine/throughput class files match the earlier
  measured files byte for byte, including `Bench.class`.
- Current and historical generated `OrderBook`, `Order` and permanent cache
  sources match after normalizing generation identities. Remaining generated
  Java differences concern exception helpers and startup whitespace.
- All 40 native OrderBook and bridge-entry function disassemblies match after
  normalizing instruction addresses and symbolic relocations. The specialized
  enum entries and the previous inlining are still present.
- The current payload manifest confirms `O3` and `baseline-x86_64`.

The current native payload SHA-256 is
`8598c310621803162d1c1eb699043dea7e474ca9e32a805c9a04e825574cadaa`;
the preserved D224 payload is
`28adfa344775e462ce38b61d9a50d2a3c6bf8a537de8a3d78eb8228b7793f4f6`.
Whole-image identities differ because compiler/runtime generations and cold
support code changed; matching hot instructions do not claim identical images.

## Evidence and next work

Local evidence is under `workspace/java-bridge/orderbook-regression-20260929`;
Estonia retains the corresponding directory beneath its checkout's `workspace`.
`compare.py` records explicit commands, JVM versions, input hashes, every exit
status, stdout/stderr and every measurement. `force-mitigation.py` is the isolated
child control. `compare-initial.py` preserves the earlier runner revision before
process-security status was added to metadata. `BenchSettled.java` is copied
unchanged from the earlier experiment. Generated-source diffs and normalized
machine-code comparison results are retained locally.

The initial eight comparison cells contain 108 successful benchmark processes
with no stderr and intact workload verification. The small collection archive
`results-complete.tar.gz` has SHA-256
`6febd3a072d5d4a17894773e4dafaef8cf3e8ab02b5d07c10343429a9266cf63`.
The fourteen follow-up bridge processes also pass, for 122 successful timing
processes overall. `results-repeat.tar.gz` has SHA-256
`d931bf4552106ddbedfb55ae2ddc08122a709c84d57311669d03731ca177f256`.
Two harness corrections are recorded: running the container with the host UID
resolved Git's ownership refusal before measurements; expanding collection
globs in the shell corrected an incomplete first archive. Neither changes or
discards benchmark observations.

The remaining host gap is real. The public workload still makes eight JNI
entries per cycle and performs facade cache lookups for returned orders. Native
standalone execution can optimize across those API boundaries. This experiment
does not separately attribute nanoseconds to each component and does not claim
JNI alone accounts for the gap.

Further optimization should use the maintainer's ordinary host configuration
as a primary acceptance case, with container/security-state comparisons clearly
separated. Preserve the original API and safety proofs; do not relabel automatic
batching or a different workload as this benchmark. No production performance
fix or general Linux speedup is claimed by this investigation.
