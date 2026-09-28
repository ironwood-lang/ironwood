<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge private-entry performance

D224 keeps the existing Java OrderBook API and improves physical Linux x86-64
throughput. It adds private constant-enum entries and increases the permanent
weak cache's initial bucket count. The engine algorithm and eight calls per cycle
are unchanged. Linux ARM64 still trails Java. Numerical acceptance and release
readiness remain open.

## Final assembled jar, three scenarios

These are new measurements of the corrected final assembled jar, not renamed
prototype results. Each supported JVM has three fresh seeded shuffled forks,
10M warmup/50M measured operations, using the settled JVM warmup described below.
Latency uses the separate original 64-operation batch harness. Every fork is
retained, including slow bridge and Java observations.

| Physical Linux x86-64, Java 21 | Million operations/s | ns/eight-operation cycle | Mean ns/64-operation batch | p99 ns/64-operation batch |
| --- | ---: | ---: | ---: | ---: |
| Ironwood-only native executable | 64.51 | 124.00 | 1,023 | 1,033 |
| Java-only bytecode in JVM | 32.34 | 247.39 | 1,846 | 1,815 |
| JVM calling Ironwood native OrderBook | 46.37 | 172.53 | 1,401 | 1,432 |

The bridge has 43.4% greater throughput than Java in this sample and 71.9% of
standalone native throughput. It still takes 39.1% more time per cycle than
standalone. Bridge forks range from 168.49 to 179.17 ns; Java ranges from 202.87
to 254.37. A mean can exceed p99 because rare long pauses affect the mean.

| Linux ARM64, Java 21 | Million operations/s | ns/eight-operation cycle | Mean ns/64-operation batch | p99 ns/64-operation batch |
| --- | ---: | ---: | ---: | ---: |
| Ironwood-only native executable | 174.14 | 45.94 | 385.64 | 422 |
| Java-only bytecode in JVM | 123.12 | 64.98 | 648.61 | 718 |
| JVM calling Ironwood native OrderBook | 104.76 | 76.37 | 646.37 | 687 |

The ARM bridge still takes 17.5% more time per cycle than Java. Its forks range
from 74.98 to 83.03 ns. The goal of beating Java on all Linux targets is unmet.

| Target | JDK | Java ns/cycle | Final bridge ns/cycle | Java million operations/s | Bridge million operations/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| Physical Linux x86-64 | 22 | 202.88 | 167.85 | 39.43 | 47.66 |
| Physical Linux x86-64 | 23 | 207.90 | 166.79 | 38.48 | 47.96 |
| Linux ARM64 | 22 | 65.40 | 75.50 | 122.33 | 105.96 |
| Linux ARM64 | 23 | 65.97 | 75.69 | 121.27 | 105.70 |

Optional Mac Java21 observations are 45.65 ns/cycle native, 67.20 Java and
72.24 bridge: 175.26, 119.05 and 110.75 million operations/s. Mac clock resolution
quantizes native batch p99 to 1,000 ns; do not interpret it as individual-call
latency. `private-enum-final/final-summary.json` includes all three targets,
supported JVMs, raw fork values and latency statistics. The physical archive
SHA-256 is `96ee0ae1260f4048597dd999f7bd622edd4116c4f575f1e39fdbdff127753ea1`.

The final jar passes 18 loading cases on each target: Java21-23, class path,
module path and executable jar, each with and without checked JNI. The actual
OrderBook consumer checks exact behavior and zero warm allocation with escape
analysis disabled. Both Mac Java24 class/module launches refuse before extraction.
These 54 passing launches are distinct from the broader loader-fault matrix.

## Ten-fork comparison before the manifest-only correction

Physical Estonia uses its Intel Xeon E-2288G, isolated CPUs 1-4 and 9-12, pinned
Temurin 21.0.12.1+1 and the existing governor. Linux ARM64 uses native ARM64 Colima
virtualization on Apple M5, six vCPUs and 8 GiB. No translated timing is used.
Task builds and bulk transfers finish before measurements.

Throughput has ten fresh, seeded shuffled forks, 10 million warmup operations
and 50 million measured operations each. The settled JVM control invokes the
unchanged engine loop ten times during warmup, one million operations each,
so the first loop exit is exercised before measurement. Standalone native uses
one ten-million-operation warmup. The production project benchmark is unchanged;
the separately recorded original protocol gives the same improvement direction.
Cycle times are medians; each cycle has eight operations. Throughput is eight
billion divided by ns/cycle. All observations, including slow forks, are retained.

Latency is a separate original harness: three forks, 20,000 warmup batches and
100,000 measured batches of 64 operations. Mean and p99 columns are medians of
fork statistics, not merged percentiles or individual-operation latencies.
Clock overhead is included.

| Physical Linux x86-64, Java 21 | Million operations/s | ns/eight-operation cycle | Mean ns/64-operation batch | p99 ns/64-operation batch |
| --- | ---: | ---: | ---: | ---: |
| Ironwood-only native executable | 64.52 | 123.99 | 1,049 | 1,056 |
| Java-only bytecode in JVM | 32.34 | 247.36 | 1,783 | 1,810 |
| JVM calling Ironwood native OrderBook | 47.65 | 167.90 | 1,381 | 1,411 |

The prior bridge measures 174.59 ns/cycle in this comparison; all ten paired
settled forks improve, as do all ten original-protocol pairs (177.92 to 170.85).
A volatile-enum control improves from 180.62 to 172.96 ns, nine of ten pairs.
The new bridge is about 35.4% slower per cycle than standalone native. Java's
settled fork range is 198.41-253.06 ns and the bridge's is 167.27-171.77 ns.
The Java variation limits generalization of its median speedup.

| Linux ARM64, Java 21 | Million operations/s | ns/eight-operation cycle | Mean ns/64-operation batch | p99 ns/64-operation batch |
| --- | ---: | ---: | ---: | ---: |
| Ironwood-only native executable | 171.68 | 46.60 | 389.74 | 426 |
| Java-only bytecode in JVM | 122.65 | 65.23 | 654.97 | 725 |
| JVM calling Ironwood native OrderBook | 105.88 | 75.56 | 635.68 | 683 |

The prior bridge measures 78.12 ns/cycle; nine of ten settled pairs improve.
Original-protocol medians are 78.03 to 77.30 ns, seven of ten pairs. Volatile-enum
medians are 78.45 to 77.01 ns, eight of ten pairs. The bridge remains 15.8% slower
per cycle than Java and 62.1% slower than standalone native in this sample.
A lower batch-latency statistic does not change that throughput result.

## Supported JVM observations

Java22/23 use three fresh forks each with the same settled warmup and workload.

| Target | JDK | Java ns/cycle | Prior bridge ns/cycle | D224 ns/cycle |
| --- | ---: | ---: | ---: | ---: |
| Physical Linux x86-64 | 22 | 247.13 | 179.96 | 170.23 |
| Physical Linux x86-64 | 23 | 202.22 | 183.31 | 167.05 |
| Linux ARM64 | 22 | 66.22 | 78.26 | 75.19 |
| Linux ARM64 | 23 | 64.81 | 78.44 | 78.43 |

ARM Java23 throughput is effectively unchanged, with only one of three pairs
improving. Physical Java22 latency initially worsens: mean/p99 1457/1530 ns
become 1507/1551 ns. A ten-pair repeat of that exact harness gives 1428.5/1457
versus 1417/1456; a separate ten-pair longer-warmup control gives 1422.5/1439
versus 1391/1403. Keep the original negative observation. These results show
fork variation and do not establish an across-the-board latency improvement.

## Mechanism and safety

The generated facade selects a private native entry for each of two enum
constants. The enum argument disappears from that private ABI and typed entry,
which avoids token conversion and exposes the known branch to native inlining.
Null and ineligible signatures retain their original entry. No public overload,
batching API, strong cache, eager native initialization or lifetime exemption is
introduced. Every generic root and additional entry participates in the existing
mandatory proofs and final native transformation checks.

The artifact-wide weak table starts with 256 rather than 16 buckets. A separate
physical control changes only that generated cache class, retaining identical
native payloads. It removes the observed collisions in those forks; combined
with private entries, settled medians improve from 175.59 to 167.98 ns. Per-root
caches remain 16 buckets. Hit-path logic, weak recreation and growth are unchanged.

Independent JNI controls support the maintainer's point that individual crossings
are cheap: approximately 7.25 ns on physical x86 and 2.71 ns on ARM64. The matched
eight-call controls measure about 60.10 and 22.26 ns per cycle respectively.
These include loop/argument work and are neither an exact subtraction model nor
a proved performance floor. The bridge cannot be described as native-equivalent
from these measurements. See the [optimization log](JAVA_BRIDGE_OPTIMIZATION.md#matching-jni-boundary-controls).

## Evidence and qualification

Raw evidence is below `workspace/java-bridge/evidence/optimization/`:

- `private-enum-wide/comparison-summary.json`: all 118 throughput observations
  and 30 latency observations per Linux target, plus derived comparisons.
- `private-enum-wide/x86/physical-evidence.tar.gz`: complete physical comparison
  and six Java21-23 production/fault cache component children; SHA-256
  `cc59f7da3059f542bfcbebaa424ce8d390123d742d39d88737ddecf5041318a7`.
- `private-enum-wide/x86/latency-repeat`: all 40 follow-up latency forks.
- `private-enum-entry/cache-control-evidence`: isolated capacity experiment.
- `private-enum-final`: corrected producer, source hashes, three host artifacts,
  assembly, payload identities, machine-code comparison and final-jar checks.

The first combined jar correctly failed loader preflight because its manifest
listed shared conversion helpers repeatedly. The manifest correction is covered
by the assembly regression with both private variants and null. It changes
packaging inventory only. The rebuilt Linux native `.text` sections are
byte-identical to the measured host payloads. Exact hashes remain distinct;
previous host-jar timings are not relabeled as final assembled-jar measurements.

The corrected combined jar SHA-256 is
`b2f4a19d10a49290133259de2faef67cd699c52b96c2930e98f31dec7205f17e`.
Its generation is
`5a01c81bd295ad4288139876f33d79a1f4a1abcde2f5b368f60d81c3995bb260`.
`private-enum-final/combined/identity.json` records all payload hashes and verifies
host bytes against assembly. Assembly changes only Support source/class and
manifest, preserving every other packaged entry.

Focused tests cover exact private ABI, boolean carriers, helper-name collision,
source exceptions, cold/null/failed enums, identity, forged metadata rejection,
generic unsafe branches in every unfreed mode, weak collection/recreation,
entry/growth allocation failures and strict zero allocation on warmed hits.
Root lifetime and conservative unknown-effect rejection remain unchanged.
The cache component harness now warms the exact measured checked loop. Its old
intermittent 184-byte observation is retained; diagnostic runs did not establish
the allocation's source, and no tolerance was introduced.

Final numerical review is pending. Physical x86 hardware measurements are real.
The separate refreshed 114-case loader matrix hit a disk-capacity blocker; a new
RAM-backed scratch run is in progress, preserving every original assertion and
cleaning only new per-child extraction files after byte-identity checks. It does
not authorize deletion of pre-existing files. Existing remote evidence, archives,
images and containers remain preserved. P6b and release readiness are not complete.
P5/P7 and Java24+ support remain deferred.
