<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Optimized Java Bridge measurements

This report preserves the e3150db6 measurements. The subsequent D224 private
entries, larger permanent cache and final assembled-jar results are in
[the current performance report](JAVA_BRIDGE_D224_PERFORMANCE.md).

The optimized bridge beats Java-only throughput on physical Linux x86-64 with
all three supported JDKs. On Java 21 it delivers 45.18 million operations/s,
versus Java's 38.47 million and standalone Ironwood's 64.52 million. It still
trails Java on the tested Linux ARM64 workload and remains slower than standalone
native on both Linux targets. Numerical acceptance and release readiness remain
open; the refreshed x86 loader matrix is still blocked by disk space.

At this checkpoint, joint-bitcode, cache-capacity and enum-continuation experiments did not
establish a repeatable improvement sufficient for adoption. The final
continuation repeat used identical machine code to its earlier promising
prototype but reversed its small Java21 gain. These experiments are retained
separately in [the optimization log](JAVA_BRIDGE_OPTIMIZATION.md#continuation-experiment-disposition-not-retained);
the measurements below still describe the unchanged frozen e3150db6 candidate.

## Same OrderBook workload, three execution scenarios

These measurements use the unchanged project `Bench` algorithm and verified
empty-to-empty eight-operation cycle. The Java Bridge scenario uses ordinary
Java callers of the generated native OrderBook through the existing API, with
eight JNI calls per cycle. It is not the separate batching control.

Throughput: three fresh forks, 10 million warmup and 50 million measured
operations each, compared with 1M/2M in the historical report. Cycle time is the
median of the three elapsed-time observations divided by their cycle counts.
Throughput is eight billion divided by that cycle time. Latency: three forks,
20,000 warmup and 100,000 measured batches, each containing 64 operations.
Columns show the median of the three batch means and of the three p99 values,
not a merged percentile or individual-operation latency. Clock overhead remains
included. The latency harness is separate from the throughput harness.

Later diagnostic runs found a first-loop-exit JIT recompilation after the single
warmup invocation. The original observations below remain intact, but should
not be interpreted as entirely compilation-free steady state. A separate
ten-invocation warmup control, with the same total operations, eliminates this
benchmark recompilation in the inspected forks. Its measurements and cache
collision profiles are recorded in the optimization log; they are not silently
substituted into this report.

### Physical Linux x86-64, Java 21

x86host, Intel Xeon E-2288G, existing powersave governor, isolated CPUs
1-4 and 9-12. No concurrent task builds, tests or bulk transfers during timing.
The scratch controller preserves every runner assertion and records its exact
patch/hash. It inventories and removes only newly generated JVM extraction
temporary files between completed child processes, keeping disk growth bounded.

| Scenario | Throughput, million operations/s | ns/eight-operation cycle | Mean ns/64-operation batch | p99 ns/64-operation batch |
| --- | ---: | ---: | ---: | ---: |
| Ironwood-only native executable | 64.52 | 123.99 | 1,024 | 1,035 |
| Java-only bytecode in JVM | 38.47 | 207.94 | 1,779 | 1,806 |
| JVM calling Ironwood native OrderBook | 45.18 | 177.05 | 1,485 | 1,521 |

The bridge delivers 17.4% greater throughput than Java and takes 14.9% less time
per cycle. Its cycle time is still 42.8% greater than standalone native. Fork
ranges are native 123.92-124.05 ns, Java 206.74-273.57 ns and bridge
174.75-183.91 ns. Retain the Java fork variation when interpreting the median.
The batch control is a separate scenario and is not substituted for these results.

| JDK | Java-only ns/cycle | Bridge ns/cycle | Java-only million operations/s | Bridge million operations/s |
| --- | ---: | ---: | ---: | ---: |
| 21 | 207.94 | 177.05 | 38.47 | 45.18 |
| 22 | 206.97 | 180.54 | 38.65 | 44.31 |
| 23 | 209.54 | 177.40 | 38.18 | 45.09 |

### Linux ARM64, Java 21

Apple M5 host, Colima ARM64 virtualization with six vCPUs and 8 GiB. All local
builds/tests finished before timing; the evidence transfer was explicitly paused
and its output size checked stable. Mac timing followed Linux timing sequentially.
No translated execution contributes timing evidence.

| Scenario | Throughput, million operations/s | ns/eight-operation cycle | Mean ns/64-operation batch | p99 ns/64-operation batch |
| --- | ---: | ---: | ---: | ---: |
| Ironwood-only native executable | 176.74 | 45.26 | 378.60 | 417 |
| Java-only bytecode in JVM | 116.99 | 68.38 | 641.58 | 709 |
| JVM calling Ironwood native OrderBook | 103.93 | 76.97 | 665.78 | 688 |

The bridge cycle range is 76.82-77.66 ns; native is 45.12-45.72 ns and Java is
65.36-69.25 ns. The bridge has about 12.6% greater cycle time than Java and 70.0%
greater than standalone native in this run. Lower bridge batch p99 in one cell
is not a claim of generally better latency: retain all fork ranges and the
separate harness/clock limitations.

### Supported JDK observations on Linux ARM64

| JDK | Java-only ns/cycle | Bridge ns/cycle | Java-only million operations/s | Bridge million operations/s |
| --- | ---: | ---: | ---: | ---: |
| 21 | 68.38 | 76.97 | 116.99 | 103.93 |
| 22 | 69.76 | 77.50 | 114.68 | 103.22 |
| 23 | 69.34 | 78.72 | 115.38 | 101.63 |

### macOS ARM64, Java 21

Mac performance is optional under the maintainer's direction. The same producer
and unchanged workload measure 46.31 ns/cycle native-only, 61.27 ns Java-only and
75.32 ns through the bridge, respectively 172.73, 130.56 and 106.21 million
operations/s. The raw batch-latency reports retain the different clock resolution;
the native Mac p99 is quantized to 1,000 ns and should not be interpreted as an
individual-call latency or compared without that limitation.

## What changed and what the JNI controls show

- `cf04f74c`: permanent facade cache hits execute in generated Java callers,
  avoiding native-to-Java cache callbacks on the warmed path (D220).
- `2be10c1c`: generated enum arguments use private primitive JNI carriers,
  preserving exact token mapping, null and cold initialization behavior (D221).
- `e26e02c6`: medium native-library loops inline through small callers (D222).
- `234c6e7e`: proved initialized contexts start at exported library roots and
  share their facts with callees (D223).
- `42dd0d12`: assembled jars preserve the new private conversion-helper inventory.
  The regression test includes nested permanent identity and malformed metadata.
- `e3150db6`: longer OrderBook measurement settings and explicit count metadata.

The final x86host Java21 handwritten scalar JNI call measures 7.23 ns; the
generated scalar measures 8.03 ns and cached object return 10.03 ns. The final
Linux ARM64 Java21 handwritten scalar JNI call measures 2.85 ns; the generated
scalar measures 3.54 ns. Cached permanent object return is 4.01 ns,
versus about 61.72 ns in the original report. All warmed scalar, receiver and
cache-hit observations report zero Java allocation. The earlier independent
matching-call-shape controls measure 7.25 ns for one JNI crossing on x86host and
60.10 ns for its eight-call cycle; Linux ARM64 measures 2.71 ns and 22.26 ns.
These are small absolute costs. The old callback-heavy implementation did much
more work than a JNI transition. The controls are not an exact additive model
and do not prove that the remaining gap is unavoidable.

No public API, workload, ownership proof, exception containment requirement,
weak-identity guarantee or Java-version boundary was relaxed. Joint LLVM/adapter
optimization, a larger general LLVM inlining threshold and a changed cache-drain
policy were not adopted. A follow-up ten-fork physical x86 comparison also
regresses with joint LLVM optimization. Constant-enum scratch helper entries
improve that experiment, but their expanded scratch API is not a production
change. Compiler-only continuation specialization is under investigation. See
[JAVA_BRIDGE_OPTIMIZATION.md](JAVA_BRIDGE_OPTIMIZATION.md) for experiments and
focused verification details. Further ARM64 improvement remains an open outcome;
do not relabel these measurements as meeting the requested speed target.

## Candidate identity and verification

The immutable candidate is `optimization/candidate-e3150db6`, produced after the
assembly repair with compiler content identity
`be9308458112c8a27091137e690267b9bcd7ef43be65d2c6e11f2d595991c5bd`.
OrderBook assembled jar SHA-256:
`12d272a34d41c2f5b4a93b3cc8c2ad31c4a3b65ce8b6befe7f7c0c0872fc4de3`.
All five cases passed their multi-target assembly checks. Rosetta launches are
explicitly translated checks, not hardware performance or stack evidence.

Both ARM64 targets pass fixed-candidate checks, supported-JDK fixture replays,
loader checks and bounded/adaptive stack probes. Mac also passes the separate
Java24 refusal. Per target, 132 performance records pass input-identity,
independent checksum and warmed-allocation validation; 30 latency reports retain
workload assertions, and the retaining-call supplement has 63 observations with
its lifetime/allocation assertions. Linux's 17 fixture worlds were rebuilt from
the immutable jar after an earlier overlapping classes-directory build made that
older run's artifact identities unsuitable for qualification. The original run
and every failure/experiment remain preserved.

Raw evidence under `workspace/java-bridge/evidence/optimization`:

- `final-{linux-x86_64,linux-arm64,macos-arm64}`: candidate, stack, performance,
  latency and retention results with commands, exits, JDK/payload identities,
  disassembly and JIT logs. Both ARM64 directories also include loader checks.
- `final-comparison.json`: all three targets, derived values and fork ranges.
  `final-arm64-comparison.json` preserves the earlier ARM-only report.
- `final-code`: the exact three OrderBook native payloads, symbol lists and
  disassembly; no out-of-line matching-loop symbol remains.
- `qualification-linux-arm64-repaired`: 17 successful immutable-jar fixture tests
  and 196 consumer replays per Java22/23. Mac has 194 per JDK; Linux's two extra
  cases are passive enum-access checks.

x86host passes the 36 selected compiler/native checks, the repaired assembly
regression, 196 asserting consumers on each of Java22/23 and 30 minimal-JVM
launches of the fixed candidate. Bounded temporary-cache runners also pass the
90 final candidate launches, six bounded stack cells with separate adaptive
child-failure diagnostics, 132 performance records, 30 latency reports and
63 retaining-call observations. All original assertions and artifact-pairing
checks are retained; every cleaned temporary file has a recorded hash.

The larger final loader stage has not run: its explicit 12 GiB free-space
precondition stopped it while about 4 GiB remained. Its retained jar fixtures
alone require about 5.9 GB across O0/O3, independently of temporary-cache cleanup.
The completed final x86 evidence and experiments are backed up and independently
verified in `x86host-final-archive`: 2,462 files, 1,533,814,764 bytes, archive
SHA-256 `0f0547d4e6ee59b320be490ca6c63e941a47d64de89380ff6f7161c93ffed657`.
The earlier 13,508-file fixture backup is also verified. All remote originals
remain while permission to remove verified non-temporary duplicates is pending.
This refreshed candidate qualification is distinct from the original candidate's
already-completed D213 physical-hardware qualification.

Java21-23 remain the baseline; Java24+ refusal, P5/P7 deferrals and the recorded
D209 Java25 findings/product decision remain unchanged. No push or release.
