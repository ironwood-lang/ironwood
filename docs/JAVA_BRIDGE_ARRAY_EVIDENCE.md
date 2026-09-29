<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7b copied primitive array evidence

P7b1 and P7b2 are implemented and qualified under D233. This adds proved
one-dimensional primitive array inputs, mutation, input-alias results and fresh
invocation-owned results. The [producer guide](JAVA_BRIDGE_USAGE.md) specifies
the enforced boundary and failure ordering. P7c was subsequently implemented and
qualified in the [byte-view evidence](JAVA_BRIDGE_BUFFER_EVIDENCE.md); P7d-P7f
remain pending. Numerical performance acceptance remains maintainer review.

## Functional qualification

All three targets passed the six focused array fixtures with pinned Java 21
production and checked-JNI child consumers. Each target then passed 148 child
consumer replays across Java 22 and 23, including the fault-injected images.

| Target | Execution | Producer | Consumer replays |
| --- | --- | --- | --- |
| macOS ARM64 | Apple M5, Mac17,2, macOS 26.6.2 | Temurin 21.0.12.1+1, LLVM 23.1.0 | Temurin 22.0.2+9 and 23.0.2+7: 148 passed |
| Linux ARM64 | Local Colima ARM64 virtualization | Same pinned versions, glibc 2.17 support SDK | Same pinned JVMs: 148 passed |
| Linux x86-64 | Physical Estonia, Xeon E-2288G | Same pinned versions, glibc 2.17 support SDK | Same pinned JVMs: 148 passed |

The six fixtures cover the input proof, read-only producer, staging failures,
mutable/result proof, mutable/result producer and copy-back failures. Source,
individual classes, class directories and archives preserve admission facts.
Production consumers cover source O0, classes O3 and archive O3. Cases include
all eight primitive kinds, null/empty/large values, floating-point bit patterns,
UTF-16 edges, equal-but-distinct inputs, repeated aliases, write-then-throw,
alias identity and fresh/null results. Unknown effects, retention, reclamation,
callbacks and unsupported result origins remain rejected.

The final copy-back fault fixture runs 45 checked-JNI child cases per JVM/target:
first, second and all copy failures; continuation after a failed copy; native
versus copy failure precedence; diagnostic aggregation failure; abandoned fresh
arrays and Strings; and Java result allocation/copy failure. Separate staging
faults cover allocation and region failures before execution. Acquired staging
buffers and converted native arrays return to baseline. Native exception
allocations are compared with the corresponding scalar failure because thrown
exceptions retain the accepted process lifetime. They are not incorrectly
counted as leaked conversion storage.

Mac additionally passed adjacent String/value admission and generated adapter
checks, root-result/retention composition including store-then-throw, primitive
callback exception identity, and protected exception getter failures. An extra
negative source/class/archive reconstruction assertion was added after the Linux
snapshot and passed on Mac; no production compiler code changed afterward.
Java 24 refused the final mutable-array artifact before native extraction.
License audits and focused package generation checks passed. No unfiltered
compiler suite was run.

## Measurement method

The [array example](../examples/java-bridge/arrays/README.md) runs identical
deterministic work through pure Ironwood, Java bytecode and generated Java Bridge
facades. `read` sums an `int[]`; `update` XORs each element and sums it; `fresh`
creates and fills a new array. The driver changes an input element on every call
and checks matching checksums across all three scenarios. Native fresh results
are explicitly freed. The bridge reclaims native conversion/result storage.

Each operation/size/scenario uses a separate process, three warmup batches and
seven measured batches. Sizes 1 and 64 use one million calls per batch; size 4096
uses 50,000. JVMs use `-Xms128m -Xmx128m`. Timing runs omit `-Xcheck:jni`;
functional child consumers use it. Reported latency is the median **batch-average
nanoseconds per call**, including the driver loop and input update. Throughput
is its reciprocal in millions of calls per second. These are not individual-call
latency percentiles or a multi-fork statistical study; raw minima/maxima are
preserved in `summary.json` and every batch in `samples.csv`.

Estonia children were pinned to isolated CPU 1, inside the existing x86-64
container. The host retained its powersave governor and turbo behavior; no fixed
clock is claimed. Linux ARM64 timings are virtualized measurements. Mac timings
are supplemental. Official OrderBook code and benchmark results are unchanged.

### Linux x86-64, Estonia

Each cell is **ns/call / million calls/s**.

| Operation | Elements | Pure Ironwood | Java | Java Bridge |
| --- | ---: | ---: | ---: | ---: |
| read | 1 | 2.12 / 472.031 | 3.51 / 284.681 | 104.64 / 9.557 |
| read | 64 | 11.89 / 84.117 | 11.99 / 83.376 | 141.82 / 7.051 |
| read | 4096 | 621.25 / 1.610 | 219.08 / 4.565 | 1782.69 / 0.561 |
| update | 1 | 3.77 / 265.146 | 4.14 / 241.410 | 127.52 / 7.842 |
| update | 64 | 16.41 / 60.941 | 14.88 / 67.196 | 177.97 / 5.619 |
| update | 4096 | 829.75 / 1.205 | 1207.42 / 0.828 | 2390.28 / 0.418 |
| fresh | 1 | 31.12 / 32.133 | 6.19 / 161.619 | 101.15 / 9.887 |
| fresh | 64 | 46.71 / 21.406 | 30.14 / 33.184 | 139.82 / 7.152 |
| fresh | 4096 | 411.46 / 2.430 | 1634.31 / 0.612 | 1765.06 / 0.567 |

### Linux ARM64, local virtualization

Each cell is **ns/call / million calls/s**.

| Operation | Elements | Pure Ironwood | Java | Java Bridge |
| --- | ---: | ---: | ---: | ---: |
| read | 1 | 1.19 / 838.624 | 1.46 / 686.784 | 39.36 / 25.408 |
| read | 64 | 3.72 / 269.087 | 10.10 / 98.996 | 64.05 / 15.614 |
| read | 4096 | 136.87 / 7.306 | 966.38 / 1.035 | 777.65 / 1.286 |
| update | 1 | 1.25 / 798.627 | 1.50 / 665.720 | 51.80 / 19.306 |
| update | 64 | 8.44 / 118.478 | 14.11 / 70.854 | 83.22 / 12.017 |
| update | 4096 | 204.33 / 4.894 | 983.85 / 1.016 | 1115.40 / 0.897 |
| fresh | 1 | 12.03 / 83.131 | 3.12 / 320.849 | 35.41 / 28.241 |
| fresh | 64 | 20.63 / 48.477 | 13.01 / 76.878 | 58.19 / 17.185 |
| fresh | 4096 | 413.24 / 2.420 | 799.38 / 1.251 | 879.93 / 1.136 |

### macOS ARM64, supplemental

Each cell is **ns/call / million calls/s**.

| Operation | Elements | Pure Ironwood | Java | Java Bridge |
| --- | ---: | ---: | ---: | ---: |
| read | 1 | 2.22 / 449.640 | 1.65 / 606.735 | 149.00 / 6.712 |
| read | 64 | 2.84 / 352.485 | 10.13 / 98.675 | 167.30 / 5.977 |
| read | 4096 | 131.64 / 7.596 | 922.37 / 1.084 | 1540.29 / 0.649 |
| update | 1 | 1.20 / 831.255 | 9.17 / 109.039 | 207.75 / 4.814 |
| update | 64 | 7.30 / 136.968 | 13.57 / 73.685 | 237.37 / 4.213 |
| update | 4096 | 185.62 / 5.387 | 961.38 / 1.040 | 1851.24 / 0.540 |
| fresh | 1 | 20.01 / 49.968 | 3.38 / 296.165 | 89.67 / 11.152 |
| fresh | 64 | 21.14 / 47.308 | 12.00 / 83.349 | 95.59 / 10.461 |
| fresh | 4096 | 420.14 / 2.380 | 765.23 / 1.307 | 1108.50 / 0.902 |

## Allocation and machine code

Every warmed read/update JVM batch measured zero Java allocation. Each bridge
call in this example measured one native allocation: a converted input for
read/update or the fresh native result. Read/update additionally use one staging
`malloc` for their nonempty input; this is outside the native object counter.
Alias coalescing limits conversion to one per distinct identity. A fresh result
allocates an ordinary Java array: 24, 272 or 16,400 bytes for these three sizes
on both JVM scenarios. All temporary native live counts return to baseline.

The production `-O3` images retain conversion outside the element loops.
ARM64 read/update use vector loads, XOR and widening reductions without
per-element bridge helpers. Baseline x86-64 read/update likewise use SSE2
loads, XOR and widening reductions (`pcmpgtd`, `punpckldq`, `paddq`); these
portable images do not assume the host's AVX2 capability. Scalar-only `iw_value_0` calls its protected entry
and checks status; its successful path has no array conversion, JNI array
operation, staging allocation or array identity bookkeeping. Failure reporting
is outlined. The complete disassemblies are paired with the measured jars.

Copied arrays deliberately incur acquisition, native allocation/copy and cleanup;
mutable arrays add copy-back. These tiny kernels do not establish a performance
win for copying. The evidence must not be read as a JNI-crossing-only benchmark.
P7c's separate bounded-buffer contract is the planned path for reusable zero-copy
storage; it has not been implemented or admitted by this work.

## Reproduction and evidence

Use the canonical `java-bridge` checkout, Java 21 in `JAVA_HOME`, pinned LLVM 23
tools on `PATH` and the matching existing Linux native support SDK configured
through `IRONWOOD_BRIDGE_SUPPORT_HOME`. No dependency installation is implicit.
On Estonia, work only inside `~/temp/java-bridge` with the existing qualified
container and SDK/JDK mounts. Run the focused tests individually:

```sh
./scripts/test.sh --test 'Java Bridge array input proofs preserve confinement and artifact parity'
./scripts/test.sh --test 'Java Bridge primitive array producer preserves values aliases and acquisition cleanup'
./scripts/test.sh --test 'Java Bridge array staging failures preserve inputs and release every acquired buffer'
./scripts/test.sh --test 'Java Bridge array value proofs distinguish mutation aliases fresh and retained results'
./scripts/test.sh --test 'Java Bridge mutable array producer preserves writes aliases and fresh results'
./scripts/test.sh --test 'Java Bridge array copy-back faults preserve primary failures and reclaim results'
python3 examples/java-bridge/arrays/benchmark.py --cpu 1 --output workspace/java-bridge/array-measurement-new
```

Omit `--cpu 1` on Mac or a host where CPU 1 is unavailable. The measurement
directory must not exist. Expected results are six fixture passes, matching
checksums, zero temporary native live-count growth, zero warmed Java allocation
for input calls, and 27 timing groups. Preserve `commands.json`, all logs,
`samples.csv`, `summary.json`, `payloads.json`, `bridge.properties`, both
disassemblies, jars and native executable. The runner never installs tools.

Detailed local evidence is below `workspace/java-bridge/arrays/`:

- Mac: `macos-performance/`, `mac-final-replay.json`,
  `final-mac-fixtures.log`, `final-composition.log`, `final-retention.log`,
  `exception-regressions.log`, `negative-artifact-parity.log`, `java24-refusal/`.
- Linux ARM64: `linux-arm64-values/evidence/` and its `performance/` directory.
- Estonia: `estonia-values/evidence.tar.gz` and extracted `evidence/`, including
  `performance/`; host details in `estonia-values-host.txt`.
- The matching fixture jars, generated sources, failure images and child command
  records are retained under `workspace/java-bridge/evidence/p7b/` on each
  producing checkout, included in Estonia's archive. Fault images are separate
  from measured production images.

The Linux input snapshot is `values-input-final.tar.gz`, SHA-256
`84726ea32349973244d5a1e9dc6568983720d2311e6fd6becf21385e1c232be5`.
Its per-file manifest includes production source and pinned compiler classes.
It was captured on `be738c65` with the implementation changes subsequently
committed in `e7a2a47d`. The later negative-test assertion, documentation and
benchmark inspection corrections do not change production compiler bytes.

An inspection-only runner bug initially selected a packaged Linux support
library for disassembly. Inspection was repeated against the actual
`libbridge.so` from each original timed jar. The original inspection files and
`inspection-correction.json` are preserved; no jar or timing sample was changed.
Native Java-allocation summaries were also corrected from a sentinel-derived
negative number to unavailable (`null`). The checked-in runner selects the
bridge basename explicitly and reports this unavailable value correctly.


## Payload identities

All three benchmark jars share these exact identities:

| Identity | SHA-256 |
| --- | --- |
| Compiler | `df8dfa250de15713dcee209f61fbd348ae22775b5dfaf21ea91f2b8628d03bba` |
| Generation | `897e3994e37b29d0fc1a643d5c70ded097caf6e75c5bc3e7eabe55a0864b2ddc` |
| API | `8123c66e3d0b4507acdc59b1488d89bcbd992015c49b0eb240af9532a6dfcc6b` |
| Program | `4f75b3401c1e83441e0391ba360bb88773fb61ead1ca45233856bbcbdaee58b8` |

| Target | Jar SHA-256 | Native bridge SHA-256 |
| --- | --- | --- |
| macOS ARM64 | `f0429391542383ad59617a83aa43becdea649446baff15bb6f931472f22285c2` | `8ef9f1913ce6cc2bb0f023b5581758982b9a84e4947c948ce61a35f6bab08777` |
| Linux ARM64 | `d98587cdad17d5c99aa79e532a978f352044b05bf0486118373945bbee6ac45b` | `fef4dfb1983c73f2ccd8f21eb16586264c1092c14f749300a8ee9d51b7988af0` |
| Linux x86-64 | `582e66c19eb24656bb95362faea4de6772a8d45f2beea699db510e7b9b1a28e4` | `a2815e2fc71685ce25f60307d0f5618ee0aeaf3ba95ceb9dce3a7183a11d72a2` |

The three-host jar assembled successfully with all native payload bytes unchanged.
Three checked-JNI Mac consumers passed for read, update and fresh-result paths.
Assembly evidence is in `workspace/java-bridge/arrays/combined/`; combined jar
SHA-256: `45d594ce141c73b818aa9df0c72ebcec0642b4f2dea3791d82341488591cd82c`.
This focused packaging check does not complete P7f's future combined qualification.
