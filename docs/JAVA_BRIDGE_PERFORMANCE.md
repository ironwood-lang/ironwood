<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge ARM64 performance observations

This page preserves the original `2559e145` candidate evidence. See the
[optimized candidate report](JAVA_BRIDGE_OPTIMIZED_PERFORMANCE.md) for the current
implementation, measured ARM64/x86-64 results and remaining qualification.

These measurements complete the authorized ARM64 collection. The separate
[physical x86-64 report](JAVA_BRIDGE_X86_EVIDENCE.md) completes D213 hardware
collection. Numerical acceptance is **pending maintainer review**. No release
or universal speedup is claimed.

## Inputs and method

Evidence lives in `workspace/java-bridge/evidence/p6b/performance-macos-arm64`
and `performance-linux-arm64`. Each directory has exact commands, stdout/stderr,
exits, pinned JDK checks, `prepared.json`, full `payloads.json`, disassembly,
JIT logs, `measurements.json` and independently validated `summary.json`.
The producer/compiler/runtime content identities match the P6a candidate.
The per-operation OrderBook test uses its exact assembled candidate jar.
Micro and native-batch fixtures use separate, recorded generations and unchanged
production compiler mechanisms. They are measurement inputs, not product APIs.

The Mac is Apple M5 on macOS 26.6.2 (25G83). Linux is Ubuntu 24.04.4/kernel
6.8.0-117 in a Colima ARM64 VM on that Mac, with six vCPUs and 8 GiB. The two
measurement runs were sequential after bridge validation/preparation finished.
Ordinary desktop background activity was not disabled; process inventories and
operator notes are recorded. These results do not compare independent machines.

Each JDK/mode uses three fresh JVM forks. Micro measurements retain seven
warmed observations per fork and consumed deterministic results; the table
shows pooled medians and full observed ranges. OrderBook uses three forks,
one million warmup and two million measured operations per fork, eight
operations per verified empty-to-empty cycle. Native executables use the same
baseline target policy and LLVM 23.1.0/O3. The handwritten JNI baseline performs
the same wrapping scalar arithmetic or native receiver-field load/add, with
matching optimization/JNI headers. Measurement has no checked-JNI flag.

Cold numbers measure first generated native use, including extraction/loading,
after JVM startup. They are separate from warm calls. Sampled p50/p95/p99 latency
is retained per fork in `summary.json`; it includes clock and mode-dispatch
cost, with a separate clock control. Do not subtract that cost as a constant or
interpret sampled percentiles as production service latency.

The Java-only recurrence has very small observed logical-iteration costs. This
suggests loop optimization; the exact HotSpot transformation was not established.
Keep those observations in the raw data, but use the handwritten JNI operation
for boundary comparisons, not the Java loop as an assumed instruction-cost
baseline. Batch figures are divided by 256 native iterations per crossing.

## Warm observations

All numbers below are nanoseconds. Micro rows are per operation; OrderBook rows
are per eight-operation cycle. Each cell is median [minimum, maximum].

### macOS ARM64 hardware

| Case | Java 21 | Java 22 | Java 23 |
| --- | ---: | ---: | ---: |
| Handwritten JNI scalar | 2.94 [2.86, 6.45] | 2.86 [2.82, 3.00] | 2.83 [2.81, 3.02] |
| Generated static scalar | 3.89 [3.36, 6.24] | 4.22 [3.67, 7.26] | 4.45 [3.77, 6.92] |
| Handwritten JNI receiver | 2.90 [2.83, 3.09] | 2.92 [2.83, 3.14] | 2.95 [2.77, 3.37] |
| Generated instance scalar | 4.31 [3.51, 6.00] | 3.69 [3.43, 5.58] | 4.58 [3.99, 6.78] |
| Cached object return | 115.75 [114.69, 120.67] | 116.07 [114.90, 121.82] | 116.47 [115.76, 120.75] |
| Copied String | 281.04 [244.16, 425.34] | 279.07 [255.36, 377.18] | 279.28 [256.63, 372.01] |
| Mapped checked exception | 17465.04 [16940.58, 23601.46] | 17527.67 [17096.42, 22327.58] | 17219.58 [16476.83, 22700.04] |
| Native scalar batch, per iteration | 0.98 [0.91, 1.31] | 1.00 [0.91, 1.26] | 1.02 [0.91, 1.34] |
| Java OrderBook cycle | 66.32 [63.43, 72.65] | 64.44 [64.31, 68.46] | 68.72 [65.06, 68.73] |
| Bridge OrderBook cycle | 719.27 [716.68, 738.57] | 724.34 [716.74, 737.44] | 714.05 [709.32, 729.37] |
| Native batch OrderBook cycle | 76.02 [74.06, 98.07] | 78.48 [75.82, 79.83] | 77.64 [75.68, 78.28] |

Standalone native OrderBook: 52.98 ns/cycle [44.60, 87.05]. Standalone native recurrence: 1.306 ns/iteration [0.995, 1.939].

Generated static JNI overhead relative to the handwritten JNI median:

| JDK | Additional ns | Ratio | Cold first use, ms |
| --- | ---: | ---: | ---: |
| 21 | 0.95 | 1.32 | 144.66 [133.36, 317.10] |
| 22 | 1.36 | 1.48 | 155.76 [154.98, 180.11] |
| 23 | 1.62 | 1.57 | 165.67 [145.90, 177.22] |

### Linux ARM64 virtualization

| Case | Java 21 | Java 22 | Java 23 |
| --- | ---: | ---: | ---: |
| Handwritten JNI scalar | 2.86 [2.69, 3.07] | 2.84 [2.75, 3.48] | 2.87 [2.81, 3.09] |
| Generated static scalar | 3.45 [3.35, 4.12] | 3.58 [3.44, 3.84] | 3.55 [3.48, 3.88] |
| Handwritten JNI receiver | 2.99 [2.85, 3.14] | 3.01 [2.90, 3.11] | 2.96 [2.84, 3.36] |
| Generated instance scalar | 3.60 [3.51, 3.81] | 3.55 [3.49, 3.90] | 3.58 [3.53, 3.81] |
| Cached object return | 61.72 [60.62, 62.77] | 60.46 [59.47, 62.07] | 60.52 [59.37, 60.97] |
| Copied String | 95.38 [89.98, 113.00] | 107.55 [91.56, 134.09] | 101.23 [93.24, 123.20] |
| Mapped checked exception | 9639.50 [8686.44, 10510.45] | 10006.58 [8839.06, 10794.65] | 10124.41 [8856.10, 10765.57] |
| Native scalar batch, per iteration | 0.88 [0.87, 0.90] | 0.90 [0.87, 0.95] | 0.88 [0.87, 0.91] |
| Java OrderBook cycle | 66.98 [66.84, 67.08] | 67.19 [67.07, 67.25] | 76.00 [66.73, 76.60] |
| Bridge OrderBook cycle | 349.86 [343.60, 352.08] | 350.23 [346.27, 360.77] | 353.74 [347.03, 383.70] |
| Native batch OrderBook cycle | 51.60 [51.03, 51.88] | 51.33 [50.85, 52.80] | 51.37 [51.30, 51.58] |

Standalone native OrderBook: 46.03 ns/cycle [45.69, 46.25]. Standalone native recurrence: 0.942 ns/iteration [0.940, 0.946].

Generated static JNI overhead relative to the handwritten JNI median:

| JDK | Additional ns | Ratio | Cold first use, ms |
| --- | ---: | ---: | ---: |
| 21 | 0.59 | 1.21 | 131.33 [123.12, 146.64] |
| 22 | 0.74 | 1.26 | 140.04 [137.30, 149.30] |
| 23 | 0.68 | 1.24 | 143.64 [140.39, 157.39] |

## Allocation and code inspection

Every measured warmed generated scalar, instance and cache-hit object loop
reports zero Java allocated bytes on all six ARM64/JDK combinations. Native
allocation and weak-facade recreation obligations are verified by the separate
identified counter fixtures, including actual OrderBook. Strings and exceptions
have separately recorded nonzero Java allocations as required by their results.
Standalone recurrence checksums were independently checked using modular affine
exponentiation; OrderBook retains the project's exact workload verification.

The final micro disassembly shows the same successful ARM64 paths on both OSes.
`iw_permanent_4`/`ironwood_bridge_entry_4` implement receiver load/add;
`iw_permanent_6`/`ironwood_bridge_entry_6` implement the wrapping static multiply
and add. On macOS the symbols have an underscore prefix. The handwritten
baseline is `Java_PerformanceConsumer_bare` and `bareInstance`.

The generated successful scalar path has one typed-entry ABI call, a status
branch, result-carrier load/store and a 0x140-byte adapter stack frame. Error
translation is on the failure branch. It has no allocation, identity/cache
lookup, TLS/trace maintenance, synchronization or thread check. These ABI/frame
instructions remain measured overhead; the result is not claimed identical to
the minimal handwritten baseline. Permanent calls contain no liveness check.
The typed arithmetic entry itself has no call. Java compilation logs show facade
methods inlined into the consumer; the large benchmark loop helper is sometimes
kept separate by HotSpot policy, as recorded in the logs.

Object-return conversion, copied strings and exceptions retain their separate
costs. Per-operation OrderBook is substantially slower in this workload than
its coarse native batch, particularly with repeated object-return conversions.
Those are observations for the maintainer's accept/optimize decision, not a
reason to weaken identity/lifetime guarantees. P7 optimization/API expansion
remains deferred. No target/JDK outside the measured six ARM64 combinations is
covered by these numbers.

## OrderBook batch latency

Separate collection in `p6b/orderbook-latency-{macos-arm64,linux-arm64}`
retains 30 verified reports per host: three native forks and three forks for
each Java/bridge/coarse-batch mode on each pinned JDK. It uses the unchanged
project latency reporter, 20,000 warmup batches and 100,000 measured batches of
eight cycles (64 operations). Every run verifies the workload; native collection
also preserves its zero-allocation assertion. Clock overhead is included.

The following are medians of the three reported 99th-percentile batch values,
in microseconds, followed by their full fork range. They are observations of
this batch harness, not service response-time guarantees.

| Host | JDK | Paired Java | Per-operation bridge | Coarse native batch |
| --- | --- | ---: | ---: | ---: |
| macOS ARM64 | 21 | 0.708 [0.708, 0.709] | 5.750 [5.750, 5.958] | 0.750 [0.750, 1.042] |
| macOS ARM64 | 22 | 0.709 [0.709, 0.750] | 5.791 [5.708, 5.791] | 0.750 [0.625, 0.750] |
| macOS ARM64 | 23 | 0.833 [0.750, 0.834] | 5.833 [5.792, 5.875] | 0.750 [0.750, 0.750] |
| Linux ARM64 VM | 21 | 0.709 [0.709, 0.709] | 3.001 [3.000, 3.001] | 0.500 [0.500, 0.500] |
| Linux ARM64 VM | 22 | 0.709 [0.709, 0.834] | 3.001 [3.001, 3.043] | 0.500 [0.500, 0.500] |
| Linux ARM64 VM | 23 | 1.125 [0.709, 1.167] | 3.043 [3.001, 3.292] | 0.501 [0.501, 0.501] |

Standalone native batch p99 is 1.000 [1.000, 1.000] microseconds on macOS
and 0.417 [0.417, 0.458] on Linux. The Mac native clock control reports a
1,000 ns smallest positive delta, with zero-duration samples at this batch size;
its coarse quantization prevents a fine latency comparison to JVM nanoTime.
Raw reports preserve clock controls, averages, extrema and percentiles through
99.999%, without subtracting overhead or claiming portable tail behavior.

## Retaining-call supplement

`p6b/retention-{macos-arm64,linux-arm64}` measures the unchanged assembled
`roots-O3` candidate, rather than using checked-JNI fixture timings as performance
results. Each `Holder.change` alternates an independently owned Item and enum
while preserving its permanent Catalog argument. These are complete call costs,
including conversion and retention reconciliation, not isolated slot-write costs.

Each pinned JDK has three fresh forks, one million warmup calls and seven
observations of 200,000 calls per fork. Results below are median [minimum,
maximum] nanoseconds per call across the 21 observations. Native state and
refusal to free the retained root are checked outside timing, followed by clear
and explicit root cleanup. The published Catalog remains permanent until process
exit. Full commands, source/class/jar hashes, counters and JIT logs are retained.

| Host | Java 21 | Java 22 | Java 23 |
| --- | ---: | ---: | ---: |
| macOS ARM64 | 341.34 [339.92, 368.75] | 341.19 [336.85, 343.89] | 341.79 [337.29, 370.71] |
| Linux ARM64 VM | 72.98 [71.81, 74.21] | 75.13 [73.80, 94.23] | 74.72 [73.76, 75.80] |

All 63 observations per host report zero Java bytes per warmed call. Native
allocation obligations remain covered by the separate identified counter
fixtures; this timing run adds no native instrumentation. Numerical acceptance
remains pending; the separate x86-64 hardware report includes the same supplement.
