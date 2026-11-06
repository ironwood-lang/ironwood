<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC8 critical-path and resident-memory qualification

2026-11-06. **GC8 is complete within the native macOS arm64 workload and host
bounds below.** Current Ironwood compilation has no unexplained GC administration
in the accepted noncollecting kernels. Repeated resident graphs reuse storage,
and native resource counts stabilize. This is neither a zero-overhead claim nor
a pre-GC regression percentage. GC9 target/distribution acceptance, GC10 and
self-hosting remain separate and unstarted.

Read the [experiment plan and pre-change review](GC8_EXPERIMENT_PLAN.md) and
[compact verification manifest](gc-evaluation/gc8-verification.json). Work starts
at GC7 `9d2edc20`, following `e8ea963d`, on the existing local migration branch.
No compiler, runtime, collector, standard-library or production bridge generator
implementation changed. No speculative optimization or safety exemption was
needed. Added original fixtures, qualification adapters, measurement drivers and
input-integrity checks are the implementation of this measurement milestone.

## Inputs, host and comparison limits

Primary measurements use native Apple M5 arm64 hardware, ten physical/logical
cores, 32 GiB RAM, macOS 27.0.1 build 26A434, AC power. Both translation and
hypervisor indicators are zero. Observed system memory free percentage was 71%.
This is a shared desktop without affinity, frequency locking or thermal isolation.
There is no new physical Linux timing result and no translated control promoted
to physical-host qualification.

The Java bootstrap was rebuilt from current GC7 sources with Oracle Java
21.0.1+12-LTS-29. Native executables require no JVM. Production bridge measurements
use Temurin 23.0.2+7, actual FFM critical calls and forced JNI fallback. LLVM,
Clang, opt and llc are pinned to 23.1.0. The verified, rebuilt GC7 SDK retains:

* BDWGC 8.2.12 and USE_MARK_BYTES=1;
* GC configuration `728c45bd8ecedf7d4fc5bb64ffd25edc85bb37115f768cad38a81eb118e0f86b`;
* Xcode SDK 27.2 settings digest
  `82165b15ee21419d4cffaba8895797391b908ac9499ba70f561f3e1bd16758d6`;
* ordinary roots 2 / owners 1, and bridge schema 2 / objects-gc-v1,
  roots 3 / owners 2.

Default command-line tools selected SDK 27.0. Verification refused that mismatch;
all accepted builds explicitly select matching Xcode SDK 27.2. Verified runtime
inputs did not change, so another collector SDK rebuild was unnecessary. The
manifest binds the compiler jar, actual SDK manifest/archives, LLVM executables,
workload sources, native binaries and disassembly. Reused builds in the driver
must match their binary, source, compiler and SDK sidecars; nearby stale-input
controls refuse changes to each category.

GC0's manual-heap Ironwood defer results and historical calloc/BDWGC C/LLVM
experiments remain historical. Their compiler/runtime inputs cannot provide a
matched current-language manual allocator baseline. No old binaries were mixed
with current SDK inputs, and no second supported memory model was introduced.
Current A/A, source-form, index/reference, layout and allocation comparisons
answer narrower questions. They cannot establish a migration-wide speedup or
slowdown percentage.

## Primary timing

Each label has **six independent processes**, in forward/reverse alternating
order. Reference, rare, locality, application and foreign cases have seven warmed
batches per process; call/defer and pool/defer have one 32-million-operation
measurement per process plus separate small behavior oracles. Distributions use
process medians. Deterministic 10,000-resample bootstrap intervals resample those
six process values, not batches as additional independent observations. The
small sample and shared host limit generalization.

`CLOCK_MONOTONIC`, also used by native System.nanoTime(), reports 1,000 ns
resolution. The 100,000-read probe found a 1,000 ns minimum positive step and
97,787 zero deltas. Timed batches last milliseconds or longer. Results below are
batch throughput, never individual-operation latency percentiles.

| Native workload | Median ns/operation | Process-median 95% bootstrap interval |
| --- | ---: | ---: |
| Reference rewrite | 0.7453 | 0.7379 to 0.8013 |
| Same binary, A/A label | 0.7379 | 0.7160 to 0.7676 |
| Index/value control | 0.7036 | 0.6698 to 0.7381 |
| Reference rewrite, deliberately spaced layout | 1.0250 | 1.0163 to 1.0439 |
| One allocation per 4,096 operations | 0.8204 | 0.7794 to 0.8596 |
| Ordinary calls with defer | 1.3598 | 1.2836 to 1.3821 |
| Equivalent nested finally | 1.3849 | 1.3102 to 1.4530 |
| ArrayObjectPool get/release with defer | 2.5996 | 2.5772 to 2.6047 |
| Equivalent pooled finally | 2.5499 | 2.4460 to 2.6126 |
| Maintained OrderBook cycle | 5.6949 | 5.6685 to 5.7254 |
| Same OrderBook binary, A/A label | 5.6448 | 5.6227 to 5.6722 |

The rewrite fixture retains 4,096 nodes, two physical arrays and optional 4,096
padding nodes. Visits use `(i * 17) & 4095`, primitive updates, reference stores
and subsequent reads. Each warmed batch has 4,194,304 operations. The rare case
replaces node zero exactly **1,024 times**, allocating 32-byte nodes; reuse,
reference, index and layout controls allocate zero. Native allocation/live-delta
assertions and an independent closed-form checksum cover every batch. A separate
observer build reads actual engine collection counts before/after the same source
kernels: zero timed-window collections, disabled engine and exact 0/256 language
allocations over its 1,048,576-operation controls.

Reference and index paths are current implementations with different valid
instructions; the optimizer forwards index values and removes unnecessary index
stores. Their difference is not an isolated collector tax. The spaced/reference
comparison uses the **same binary and timed reference loop**, changing only cold
allocation placement and retained padding. Its substantial locality penalty is
therefore separated from ongoing collector work. The rare path adds the scheduled
allocation/zeroing/header/counter operations and reloads possibly changed state;
it adds no automatic collection. These mechanisms explain the differences.

OrderBook calls the maintained `Bench.run` eight-operation cycle, rather than a
replacement workload. Four million warmup operations precede seven batches of
32 million operations. Match count, volume, final IDs/price, emptiness and full
pool capacity are checked outside timing, with **zero warmed allocations**.
Deferred call/pool fixtures preserve their event/capture checksums and zero
allocation/live deltas. Identical optimized callers support the overlapping
defer/finally measurements; timing alone is not the acceptance proof.

Cold source setup is separate: reference setup took 42 to 73 microseconds,
spaced setup 81 to 86 microseconds and OrderBook construction 2 to 4 microseconds.
Warmup precedes explicit collection. The final source collection and subsequent
reuse batch are separate again. Post-collection reference batches range from
0.661 to 0.745 ns/operation, rare from 0.740 to 0.781, spaced from 0.942 to 0.965,
and OrderBook from 5.589 to 5.781. Those single final batches are observations,
not a separate statistically established speedup.

## Foreign paths and actual O3 execution

| Production bridge path | Median ns/unit | Process-median 95% bootstrap interval |
| --- | ---: | ---: |
| Proved scalar critical call | 1.6723/call | 1.6622 to 1.7427 |
| Same critical call, A/A label | 1.6639/call | 1.6601 to 1.6665 |
| Same operation, forced JNI | 3.5900/call | 3.5815 to 3.6300 |
| Maintained retained listener | 4.9283/event | 4.9090 to 4.9983 |
| Callback with explicit collecting reentry | 6,498.7/event | 6,355.5 to 9,130.9 |

Each process warms four batches and measures seven: four million scalar calls,
200,000 callback events or 32 collecting callback events per batch. Callbacks use
the maintained ResultProcessor/ResultListener implementation with generated
registration ownership. The producer selects four of nine bindings for critical
calls; collection, listener mutation and callbacks correctly keep JNI. Callback
batching is enabled, so per-event throughput is **not** per-transition latency.
Reentry executes a full requested source collection per event. Zero language
allocations, exact event/checksum recurrences and expected explicit-request counts
are checked outside every timed batch. Checked-JNI functional runs and the GC6
callback/carrier lifetime controls separately cover cleanup and nested collection.

Inspection uses the **actual linked O3 binaries**, including `ironwood_run`, the
outlined rewrite loop, initialized OrderBook callers and their callees, bridge
entries, critical adapters and callback transition paths. The manifest preserves
raw disassembly hashes and per-function instruction hashes. Normalization removes
instruction PCs and resolved address prefixes before symbols only. It retains
opcodes, immediates, operands, symbol identities/offsets and unresolved addresses.
Tests prove that instruction, immediate and helper-target changes survive it.

* The measured nonallocating reference loop uses loads/stores, arithmetic,
  branches and existing null/array checks. `str x13, [x12, #8]` is the reference
  assignment. No barrier, poll, TLS, root registration or registry lookup occurs.
  The rare branch calls the ordinary allocator only at its scheduled boundary.
* Actual `ironwood_run` instruction streams are identical for each defer/finally
  pair. Their normalized hashes are
  `1bafe8b449438ed43f9a9a389c01495824d2b8c0c96f6ddcba59341443d0ce7e`
  for calls and
  `df66b15ddfac0d3049062054fcad54f4017d053d6855251174ecf54d7461cdd1`
  for pools. The ordinary call loop is inlined arithmetic. The pool loop preserves
  existing membership/bounds/growth checks; growth branches are not taken during
  warmed reuse. There is no defer action allocation or registration.
* OrderBook's measured initialized cycle calls ordinary maintained order/pool
  helpers. Existing source null, capacity and protocol checks remain. No new GC
  per-operation administration appears. Static trace registration is cold;
  reporting, accounting reads and source collections surround the timing window.
* The scalar bridge body is multiply/add, result store and return. Its existing
  outcome adapter has an ordinary stack frame/call, without GC TLS, segment
  capture or root registration. This is not a claim that all bridge ABI cost
  vanishes. The callback bridge captures/resumes suspended native segments at
  Java-execution boundaries; batching amortizes that transition. Source references
  live across collection-capable calls have required statepoint spills.
  Collection-boundary and foreign capture work is accepted separately and does
  not appear in the proved noncollecting scalar or mutation loops.

**Acceptance:** no unexplained hot GC maintenance or unresolved hot-path
regression remains in these kernels. There is no universal percentage threshold
and no unfavorable kernel hidden in an unrelated application average.

## Resident graphs, retention and reuse

The normal frontend compiles AST-like binary trees, shared symbol references,
cyclic type links, physical work arrays, a fixed symbol table/scratch pool and
published current/obsolete snapshots. Payload lengths vary across eight sizes:
`scale * (256 + (id & 7) * 128)` bytes. Every byte is written and consumed, with
an independent checksum and reference-link checks after collection. Each phase
also runs 32 caught exceptions with trace refill and defer/finally, 32 file
open/read/close operations, and 32 exported handles with explicit closure.

There are three independent processes at each size, each with 14 replacement
phases. The source allocation count per steady graph is exactly `2*N + 1`:
nodes, payload arrays and the published array. Initial work-array creation adds
one. Exception, stream and exported Graphs values add **96** temporary language
allocations per phase; collector sidecars are correctly excluded from that count.

Five initial allocations are the symbol-table object, key/value arrays, scratch
pool array and scratch value. The retained reusable work array makes six after
dropping all results. One published graph therefore has `2*N + 7` survivors.
An obsolete graph intentionally retained during phases 1 and 2 adds `2*N + 1`.
Phase 3 clears that root and all scratch slots. Symbol values always identify
the current graph; their actual physical slots clear at the final drop. Existing
GC5 standard pool/map cleanup and provenance controls pass separately.

| Boundary | 4,096 nodes, scale 2 | 16,384 nodes, scale 8 |
| --- | ---: | ---: |
| Touched payload per graph | 5.5 MiB | 88 MiB |
| Phase 0 survivors | 8,199 | 32,775 |
| Retained control survivors, phases 1 and 2 | 16,392 | 65,544 |
| Cleared obsolete root, phases 3 to 13 | 8,199 | 32,775 |
| Final drop survivors | 6 | 6 |
| Collector capacity plateau | 34,816,000 B | 415,039,488 B |
| Phase 3 free blocks | 25,763,840 B | 279,482,368 B |
| Final-drop free blocks | 34,758,656 B | 414,883,840 B |
| Largest exact process RSS high water | 82,722,816 B | 486,424,576 B |
| Largest phase-observed physical footprint | 61,178,792 B | 465,077,424 B |

The compact manifest gives every build/collection/drop boundary, including ranges
across the three processes, totals, cached live counts, collector diagnostics,
current RSS, exact high water and charged physical footprint. Post-collection
sidecars are zero and collector administration is six in every repeated phase.
No ordinary build or cleanup changes the engine collection count. Allocation
also leaves the preceding reclaimed-byte diagnostic unchanged: later allocation
is not completing deferred sweeping. Collection requests increment the count once
and return with the engine disabled.

The large heap reaches its high water while current, obsolete and replacement
graphs overlap. Later physically touched replacements use that capacity without
further growth. Large-process RSS falls to 356,089,856 to 357,613,568 B by phase
13 even though heap capacity remains reserved. This is real resident work,
not a sparse virtual-span capacity test. Neither reuse nor correct collection
requires RSS to fall or all reusable storage to be returned to the OS.

Small free-block counts initially drift as size-class partial blocks settle.
The extended 1,044-phase diagnostic reaches a fixed free-block value after about
150 phases, with and without trace/resource/export work. Final graph removal
restores nearly all capacity. Free-block bytes exclude reusable cells within
partially occupied blocks, so they are not a complete free-storage census.

The detailed Java reporter also allocates host objects and warms JVM/native
caches. The separate native-block control removes per-phase JNI/reporting:
200 source warmup phases, then three 500-phase blocks, each building, consuming,
closing resources and explicitly collecting before replacement. Between the last
two blocks, measured current RSS is exactly **75,431,936 B**, physical footprint
**53,887,864 B**, default native malloc usage **22,846,608 B**, malloc capacity
**46,137,344 B**, collector capacity **34,816,000 B**, free blocks **25,473,024 B**
and survivors **8,199**. Each block has exactly 500 engine collections and
4,128,500 successful language allocations. This isolates the stable native image
from the earlier reporter/JVM warmup drift. Default-zone statistics are explicitly
partial process-native observations, not all JVM mappings or a per-image ledger.

All final resident processes retain **seven file descriptors, sixteen JNI global
references, zero foreign anchors and zero facade records** at the observed phase
boundaries. The globals are fixed bootstrap/class metadata, not leaked listeners.
The GC6 callback qualification additionally checks replacement, active retirement,
nested reentry, carrier completion and forced batch fallback with balanced JNI
ownership. Collection performs none of the explicit resource closure.

Complete source Memory.collect() durations include root/scratch preparation,
eager engine work and post-collection enumeration. For stable phases 3 to 13 they
range from **0.216 to 0.405 ms** at the small size and **1.981 to 3.533 ms** at the
large size. The two-graph/transient phase reaches **4.713 ms**. Engine-only timing
is not separately instrumented; the reported source duration is the acceptance
measurement. Pause-time optimization is not part of this milestone.

## Bounds, failures and verification

Every native, JVM and toolchain child retains the **16 GiB summed process-group
RSS guard and 60-second deadline**. Sampling sleeps 50 ms between `ps` snapshots,
plus command/scheduling overhead. Shared pages may be counted more than once,
short peaks can be missed and independently detached groups are not covered.
The observer's current RSS/physical footprint and macOS getrusage high water
are process-local measurements with different scopes. A sampled guard peak is
not an exact process high water. No limit was raised.

The planned 4,200-phase native-block candidate hit the deadline after reporting
3,200 phases. Its combined guard/deadline diagnostic and raw hashes are preserved;
phase observations were below 83 MB. It is an **incomplete experiment**, not a
successful capacity result. Reducing work to 1,700 phases produced the accepted
bounded control. Earlier fixture naming/signature and conservative admission
refusals were diagnosed before measurements, not counted as passes. The plan
records the specialization/snapshot and String-copy/callback scope limits; no
unsupported boundary was opened to obtain a result.

Focused successful verification includes:

* measurement checksum arithmetic, process-level statistics, instruction-preserving
  normalization and stale binary/source/compiler/SDK refusal controls;
* ordinary graph executables at O0 and O3 through root ABI 2;
* GC2 layout, array, sidecar, metadata, failure and overflow controls, including
  120 actual overflow recoveries and 65,536 numeric-target checks;
* GC5 O3 integrated, pool, resource success/preparation-failure, allocation OOM,
  construction and no-automatic-collection controls;
* exact GC2/GC3/GC4/GC6/GC7 compiler selections for maps/effects/owners,
  callback contexts and pool origins;
* actual GC6 O3 checked-JNI callbacks and forced batch fallback, plus current
  production critical/JNI/callback/reentry measurement consumers;
* SDK offline verification, diff, license and tracked-file checks.

No unfiltered compiler/native suite, hosted workflow or new platform/distribution
matrix was run. Earlier GC2-GC7 results remain historical except for the focused
controls explicitly rerun here. Raw logs, SDKs, generated source, jars, binaries
and assembly remain under ignored `workspace/gc8/`, about 50 MiB at completion.
Only fixtures, tools, compact hashes/results and conclusions are committed.

## Reproduction and bounded handoff

From the canonical checkout, select matching tools, then use a fresh ignored
output directory. The driver rebuilds the bootstrap, compiles normal native
fixtures and the qualification observer, runs primary timings, records actual
assembly, then runs separate resident observations. It compiles its three Java
qualification helpers directly; an existing compiler test-class tree is not needed.

```sh
export SDKROOT=/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk
python3 scripts/test-gc8.py \
  --sdk workspace/gc7-sdk-final --llvm-home /opt/homebrew/opt/llvm \
  --java-home /Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home \
  --output workspace/gc8-reproduction
python3 scripts/test-gc8-foreign.py \
  --sdk workspace/gc7-sdk-final --llvm-home /opt/homebrew/opt/llvm \
  --java-home workspace/java-bridge/jdks/temurin-23-macos-arm64/jdk-23.0.2+7/Contents/Home \
  --output workspace/gc8-foreign-reproduction
python3 scripts/test-gc8-tools.py
```

For the retained controls, use the same SDK/LLVM/Java arguments with
`scripts/test-gc-layouts.py --case layouts --case arrays --case sidecars --case metadata --case failure --case overflow`,
`scripts/test-gc-integrated.py --level O3 --case integrated --case pools --case resources --case no_collect --case oom`
and `scripts/test-gc6-callbacks.py --level O3 --batch-fallback`, each with its own
fresh `--output`. Exact compiler selection names and raw result hashes are in
the manifest. `gc8.Gc8GraphMain` is the ordinary O0/O3 graph entry.

GC9 receives broader native-target and distribution qualification, including
physical Linux evidence, Linux arm64/arm64e gates and the producer's macOS adapter
minimum-version warning observed during this measurement build. This host run
does not accept an older OS or a different target. Preserve exact layouts,
fail-closed metadata, suspended roots, image isolation, handle/registration
lifetimes, native provenance and explicit JNI cleanup. D051 and D132/D133 remain
mandatory. GC10 documentation closure and any new self-hosting/I1/I2 attempt
require their own scoped work. No GC9/GC10 implementation or self-hosting work
has begun here.
