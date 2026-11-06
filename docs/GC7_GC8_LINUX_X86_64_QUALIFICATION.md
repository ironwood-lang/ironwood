<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC7 and GC8 native Linux x86-64 qualification

2026-11-06. **GC7's selected migration/consumer checks pass, and GC8 is qualified
within the physical Linux x86-64 workloads and limits below.** Read the
[pre-recorded plan](GC7_GC8_LINUX_X86_64_PLAN.md) and
[compact verification manifest](gc-evaluation/gc7-gc8-linux-x86_64-verification.json).
The [original macOS report](GC8_IMPLEMENTATION.md) retains its measurements.
GC9/GC10, distribution acceptance and self-hosting remain separately scoped.

## Inputs and bounded execution

Both checkout roots and origin fetch/push URLs were verified. Local work
continues from `04f09a36`, following GC7 `9d2edc20`, on the existing migration
branch. The remote checkout remains at its old GC1 checkpoint with its two
pre-existing script edits intact. Current sources were exported into ignored
`workspace/gc7-gc8-20261106/source/` there, with full per-file SHA verification.
This is source staging, not another Git checkout/worktree or branch. Snapshot
and overlay hashes identify the tested tree; inherited parent Git revisions in
older drivers are not its source identity. No old compiler/runtime binary was
used as a current input. Nothing was pushed or integrated from main.

`x86host` is physical Intel Xeon E-2288G hardware, eight cores/sixteen threads,
about 16 GiB RAM, Ubuntu 18.04.6, without translation or virtualization. This is
a shared host without task-selected CPU pinning, frequency locking or thermal
isolation. The final host probe reports a powersave governor and an inherited
eight-CPU affinity mask (0, 5, 6, 7, 8, 13, 14, 15), no CPU cgroup quota and no
smaller memory cgroup limit. Individual historical child masks were not sampled. LLVM,
Clang, opt and llc are 23.1.0. The rebuilt bootstrap uses Java 21.0.10-internal;
production critical calls use Temurin 23.0.2+7, and JNI controls also use Java 21.
Native executables require no JVM. Python 3.14.6 runs the measurement tools.

The collector SDK was rebuilt from current sources and verified offline. It uses
BDWGC 8.2.12, USE_MARK_BYTES=1 and glibc 2.17 sysroot inputs. Configuration
`97dad8a30c8f55de5169e5cd02483f61cd25e70d0a0baca4cf4245ddc04ab129`
binds sysroot installation receipt
`f1dcba2c3ef87eec42905fdd16aab8e399919c7b565e615aa027bf3da1dddb68`.
It differs from the previous Linux profile only in that receipt. Cold dependency
discovery now selects one exact listed configuration/receipt pair, retaining
the earlier Linux and macOS profiles. Missing, unknown, mismatched and stale
inputs still refuse. No runtime, statepoint pass, hot lowering or SDK recipe
changed. Ordinary roots 2/owners 1 and managed schema 2/objects-gc-v1,
roots 3/owners 2 remain unchanged.

Each native, JVM and compiler/toolchain child keeps the 16 GiB summed
process-group RSS guard and 60-second deadline. The guard sleeps 50 ms between
`ps` observations, plus command/scheduling overhead. It can miss brief peaks,
double-count shared pages and cannot account for detached process groups.
Guard samples are not exact process high-water measurements. A separate 30 GiB
free-disk floor bounds staging; no unrelated files or old evidence were removed.

## Critical execution and controls

The workloads and boundaries match the original GC8 plan: 4,096 retained nodes,
reference/index updates, deliberate spacing, and one 32-byte allocation per
4,096 operations. Seven warmed batches contain 4,194,304 operations each. Rare
batches allocate exactly 1,024 objects; the other warmed kernels allocate zero.
The observer independently checks zero collections and exact 0/256 allocations
over 1,048,576 operations. The engine remains disabled outside source collection.

Call/pool defer and equivalent nested finally use 32-million-operation batches,
event/capture checksums, zero allocation/live deltas and independent small
oracles. OrderBook uses the maintained eight-operation `Bench.run` cycle,
four million warmup operations and seven 32-million-operation batches, checking
matches, volume, IDs, emptiness and restored pool capacity. It allocates zero
during warmed execution. Cold setup, warmup, explicit collection and final reuse
remain separate. Reporting is outside the timed windows.

Each primary label has six independent processes in alternating forward/reverse
order. Uncertainty uses deterministic 10,000-resample bootstrap intervals over
the six process medians, not seven times as many independent samples. The clock
reports 1 ns resolution; 100,000 reads found a 16 ns minimum positive step and
zero repeated timestamps. These are batch throughput measurements, not
individual-operation latency tails.

| Workload | Median ns/unit | Process-median 95% bootstrap interval |
| --- | ---: | ---: |
| Reference rewrite | 1.8141 | 1.7263 to 1.8908 |
| Same binary, A/A label | 1.8111 | 1.7239 to 1.9382 |
| Index/value control | 1.7619 | 1.7079 to 1.8930 |
| Reference rewrite, spaced placement | 2.0699 | 2.0272 to 2.2236 |
| One allocation per 4,096 operations | 1.8176 | 1.7082 to 2.0051 |
| Ordinary calls with defer | 1.5729 | 1.1089 to 2.1108 |
| Equivalent nested finally | 1.7664 | 1.5072 to 2.0398 |
| ArrayObjectPool with defer | 3.8683 | 3.7399 to 4.0070 |
| Equivalent pooled finally | 3.7790 | 3.6822 to 4.3605 |
| Maintained OrderBook cycle | 10.1161 | 10.1002 to 10.3390 |
| Same OrderBook binary, A/A label | 10.1062 | 10.0980 to 10.2700 |

The first Linux timing matrix is preserved separately. Different executable
paths had supplied different managed argv[0] strings. The corrected driver fixes
argv[0] and keeps mode strings in the same allocation-size bucket, retaining
native executable selection and all process guards. This reduces avoidable cold
allocation/layout differences without changing the measured code.

The longer same-path control uses six alternating processes per label, the same
268,435,456-operation count, fixed argv[0], a single executable path, and an
identical-binary A/A label. Call medians are 1.0861 ns (defer), 1.0807 ns (finally)
and 1.0789 ns (identical); pool medians are 3.6875 / 3.6945 / 3.6875 ns.
Their process-median intervals overlap. One identical pool process takes
5.1103 ns/operation despite identical bytes/path; the manifest preserves it.
The initial short call/pool differences do not persist in the longer control.
Identical actual instructions and the A/A variation explain the attribution
limit; no source-form GC maintenance regression was found. This does not remove
host variability or establish a universal regression threshold.

Cold reference setup ranges from 78.9 to 94.1 microseconds, deliberately spaced
setup 143.3 to 161.1 microseconds and OrderBook setup 8.5 to 28.8 microseconds.
Rare setup has one 248.9-microsecond observation. Post-collection reference reuse
ranges from 1.537 to 1.890 ns/operation, rare from 1.689 to 1.876, spaced from
1.997 to 2.302 and OrderBook from 10.102 to 10.435. These final single batches
are observations, not independent speedup distributions. Rare/reference primary
intervals overlap; this shared-host experiment does not resolve a small scheduled
allocation cost separately from placement and timing variation.

The separate final complete source collections take 89.0 to 96.1 microseconds
for reference, 92.4 to 97.5 for rare, 155.5 to 162.4 for spaced and 16.3 to 21.8
for OrderBook. They are outside primary timing and finish before the reuse batch.

The deliberately spaced/reference comparison uses one binary and one reference
loop with different cold placement. Its locality cost is separate from collector
maintenance. Index and reference implementations have different valid optimized
instructions, including forwarded values, so their difference is not an isolated
GC tax. The rare branch performs scheduled allocation, zeroing, headers and
existing counters without collection. These current matched controls do not
reproduce GC0's manual-heap model or historical C/LLVM experiments. There is no
reliable matched pre-GC Ironwood baseline, migration percentage or cross-CPU
regression claim.

| Workload | Median ns/unit | Process-median 95% bootstrap interval |
| --- | ---: | ---: |
| Proved scalar critical call, per call | 3.9715 | 3.8879 to 4.0148 |
| Same critical call, A/A label | 3.9784 | 3.8759 to 4.0674 |
| Forced JNI, per call | 8.7236 | 8.6972 to 8.7704 |
| Retained listener, per event | 3.1020 | 3.0702 to 3.9363 |
| Explicit collecting reentry, per event | 10,136.9531 | 10,034.3750 to 10,279.5625 |

Production scalar critical calls, forced JNI, retained callbacks and explicitly
collecting reentry remain separate. Each label has six independent processes
with four warmups and seven batches: four million scalar calls, 200,000 callback
events or 32 collecting events. Callback batching is enabled; ns/event is not
ns/transition. Checked-JNI functional runs, allocation/event/checksum assertions
and exact requested-collection counts accompany timing.

## Actual O3 paths

Inspection covers actual linked ELF callers and inlined loops, not just helper
symbols. The manifest binds raw disassembly, normalized instruction streams and
binary hashes. Normalization removes instruction PCs and resolved address
prefixes before symbols only. It retains instruction/opcode changes, immediates,
RIP-relative displacements, operands, symbols and offsets. Nearby changed
instruction/helper controls pass.

* The nonallocating rewrite loop loads references from physical array slots,
  updates values and stores the next reference with `movq %rdi, 0x8(%rsi)`.
  Arithmetic, loads/stores, branches and existing null/bounds checks remain.
  There is no write barrier, GC poll, TLS, root registration or registry lookup.
  Only the scheduled rare branch calls `ironwood_allocate`.
* `ironwood_run` is instruction-identical between each defer/finally pair:
  356 instructions for call and 2,083 for pool. Their normalized caller SHA-256
  values are `52e7970911d77f632d10acf96bef17514d36fbab2666a50d7dc63cb4c7c58050`
  and `fa1e52af402bfebf9e35a1dffbae9a8f209ae92fb394ffc5eaad6d7c196b2570`.
  Call bodies inline arithmetic. Pool operations retain membership, bounds and
  uncommon capacity/growth checks. Allocation branches are not taken during
  warmed reuse. Trace registration, argument conversion, accounting reads and
  reporting are outside the loops; there is no defer registration/allocation.
* Initialized OrderBook cycles execute maintained order/pool helpers and source
  protocol checks without per-operation GC administration.
* The scalar `ironwood_bridge_entry_4` is multiply/add, outcome store and
  return. Its `iw_critical_2` adapter has the existing 296-byte outcome frame
  and ordinary call, without GC TLS, root/segment registration or registry lookup.
  Callback callers spill/reload the live reference across the callback, including
  `movq %rax, 0x10(%rsp)` before the transition. The JNI callback wrapper calls
  current-segment, suspend and resume helpers, with stack capture only there.
  The direct source collection in the static-rooted kernels remains outside
  timing. These boundary costs are reported independently of scalar/reference
  loops; ordinary bridge ABI/outcome cost is not claimed to disappear.

Required statepoint spills at collection-capable boundaries and foreign segment
capture are accepted boundary work. They are absent from the proved ordinary
noncollecting mutation/scalar windows. D051 and D132/D133 are preserved; no
safety rule was relaxed to obtain a result.

## Resident graphs and resources

Normal Ironwood compilation builds AST-like trees, shared symbol references,
cyclic type links, physical work arrays, map/pool scratch and current/obsolete
published snapshots. Payloads vary over eight sizes,
`scale * (256 + (id & 7) * 128)` bytes. Every byte is touched and consumed, with
independent payload and reference-link checks after collection. Each phase also
runs 32 caught exceptions with trace refill and cleanup, 32 open/read/close
operations and 32 explicitly closed exported handles.

Three independent processes per size run 14 replacement phases. Graph creation
allocates exactly `2*N+1` objects per phase, plus one initial reusable work array;
exceptions, streams and exported values add 96 allocations per phase. Five
initial map/pool allocations survive; after final drop the work array makes six.
The ordinary O0/O3 graph executable separately exercises root ABI 2.

| Boundary | 4,096 nodes, scale 2 | 16,384 nodes, scale 8 |
| --- | ---: | ---: |
| Fully touched payload per graph | 5.5 MiB | 88 MiB |
| Current graph survivors, phase 0 | 8,199 | 32,775 |
| Intentionally retained obsolete graph, phases 1/2 | 16,392 | 65,544 |
| Cleared obsolete/scratch roots, phases 3 to 13 | 8,199 | 32,775 |
| Final drop survivors | 6 | 6 |
| Collector capacity plateau | 33,509,376 B | 413,229,056 B |
| Phase 3 reusable free blocks | 24,457,216 B | 277,671,936 B |
| Final-drop free blocks | 33,452,032 B | 413,073,408 B |
| Largest process RSS high water | 87,617,536 B | 486,842,368 B |
| Largest phase-observed PSS | 85,730,304 B | 484,994,048 B |

The manifest records every build/collection/drop boundary as ranges across the
three processes, with totals, cached live counts, heap/free/reclaimed storage,
native allocation statistics, RSS/peak/PSS and resources. Obsolete retention is
intentional and exactly explained; phase 3 clears those roots and scratch slots.
Map values identify the current graph; final drop clears physical map/pool slots.
Replacement graphs reuse high-water capacity after current, obsolete and
replacement graphs have overlapped. RSS can stay at that high water or fall:
one large process reaches 352,923,648 B by phase 13 while capacity remains
reserved. Free blocks exclude reusable cells in partial blocks. This is resident
work and reuse evidence, not virtual reservation or a claim that all reclaimed
memory returns to the OS.

Small free-block bytes decrease by 20,480 B between phases 3 and 13 with the
same capacity and survivor count. Partial-block/size-class settling is consistent
with this diagnostic; after 200 native warmup phases, the block control below
has fixed free-block bytes through all three blocks. The large repeated graph
already has fixed free-block bytes from phase 3 onward.

The Linux observer reads process RSS from `/proc/self/statm`, kernel process
high water from `getrusage` (KiB converted to bytes), and PSS from
`/proc/self/smaps_rollup` (KiB converted to bytes). PSS apportions shared resident
pages; it is not an exact process/cgroup charged-memory census. glibc 2.17
`mallinfo` reports allocator used plus mmapped bytes, and arena plus mmapped
capacity; signed-counter overflow refuses. It excludes native mappings outside
glibc allocation, including much JVM storage, and is not a private-image ledger.
All these observers are cold and separate from primary timing.

The detailed reporter warms JVM/native caches. A separate native block control
runs 200 warmup phases and three 100-phase blocks without per-phase JNI/reporting.
Each block has exactly 100 source collections and 825,700 language allocations.
Across all three blocks collector capacity is 33,509,376 B, free blocks
24,162,304 B, survivors 8,199, glibc usage 20,263,920 B and capacity 26,365,952 B.
RSS is 79,507,456 / 79,511,552 / 79,515,648 B; PSS likewise advances one 4 KiB
page per observation. This small reporter/process drift does not imply exact
zero RSS growth, while measured native allocator and collector storage are fixed.
It does not support extrapolation to an unbounded run.

Every observed resident boundary retains seven descriptors, sixteen JNI globals,
zero anchors and zero facades. Post-collection sidecars are zero and collector
administration is six. Allocation/build/cleanup never increments collection count;
every explicit request increments it once and returns with the engine disabled.
Allocation also leaves the previous reclaimed-byte diagnostic unchanged: later
allocation is not finishing deferred sweeping. Resource closure is explicit.

Complete source `Memory.collect()` durations include preparation, eager engine
work and post-collection accounting. Stable phases 3 to 13 take 0.456 to 0.813 ms
at the small size and 5.125 to 7.282 ms at the large size. The retained/transient
phase reaches 11.301 ms. Engine-only time is not separately instrumented and
pause optimization is not this milestone's objective.

## Focused verification and corrections

All selected current-source Linux checks pass:

* GC7 compiler controls for removed forms/ordinary names, 48-class structural
  retirement, shared graphs/results, partial publication and final initialization,
  copied/borrowed confinement, collecting snapshot getters, pool origins,
  source/class/archive parity and stale bridge manifests;
* GC7 native shared graphs at O0/O3, retained visitor and constructor dispatch at
  O3, plus ordinary GC8 graph fixtures at O0/O3;
* exact SDK-profile acceptance/refusal pairs and retained GC2-GC6 layout,
  collection-effect, owner, foreign-context and callback-binding compiler tests;
* GC5 canonical intrinsic, dispatch/initialization/defer/specialization effects,
  single-model diagnostics and final-link legacy-deallocation refusals;
* GC2 native layout/array/sidecar/metadata/failure/overflow controls, including
  120 actual overflow recoveries and 65,536 numeric-target checks;
* GC5 integrated, pool, explicit resource success/failure, no-automatic-collection
  and allocation/preparation/construction OOM cases at both O0/O3 (31 children);
* 16 native-input cases, covering actual source/class/archive links and nearby
  ABI/configuration/pass/runtime/root-reader/payload/unmanifested-input refusals;
* production GC6 objects with critical calls and JNI fallback, callbacks with
  forced batch fallback, exception carriers, suspended collecting reentry and
  nearby foreign-segment refusals, and independent private images;
* 32 real checked-JNI metadata load cases: original acceptance, fifteen missing
  and fifteen stale bindings, and missing-manifest refusal;
* eight measurement arithmetic/statistics/ELF-normalization/fixed-argv/identity/
  disk-floor controls, plus current production GC8 measurement consumers.

Diff, license, tracked-file, report-link, text-policy and tested-source-identity
checks pass. New executable inputs match the fully verified remote snapshot.

The changed cold profile selection and artifact setup also pass focused macOS
compiler checks. The existing macOS SDK links/runs the no-collection fixture;
its observer rebuild and a small checked-JNI resident run pass. This preserves
the original supported path without rerunning its performance matrix.

The initial missing ignored `workspace/` directory exposed a GC7 artifact-test
setup assumption; creating its parent fixes fresh-tree execution. Initial GC7
native runs had been assigned exit zero by the follow-up wrapper, while those
fixtures deliberately return 42; the corrected expectation preserves failures
for inspection. A POSIX feature macro initially hid macOS malloc declarations;
restricting it to Linux restores the existing observer, verified on macOS.
The Java 23 metadata positive control loaded correctly but warned about native
access; the standard native-access launch flag fixes that qualification command
while preserving strict empty stderr and all stale/missing refusals.
The rebuilt SDK initially refused the old configuration allowlist, correctly;
exact named-profile binding fixes that cold discovery issue without weakening
payload/source checks. No runtime/hot-path correction was required.

No Linux guard/deadline termination occurred. The largest sampled group RSS
across qualification tools is 1,595,620 KiB; resident workload groups reach
475,432 KiB. New ignored staging/output occupies 2,816,132 KiB (about 2.69 GiB),
with 101,463,470,080 B (about 94.5 GiB) free at the final probe, above the 30 GiB
floor. No limit was raised. Logs, SDKs, generated sources, complete native-support
bundles, jars and binaries remain ignored. Only tools, compact evidence and
conclusions are committed. No unfiltered suite or hosted workflow ran.

## Reproduction and acceptance boundary

Use the recorded Java/LLVM/sysroot/native-support inputs and a fresh ignored
output directory. Set `JAVA_HOME`, `IRONWOOD_RUNTIME_HOME`,
`IRONWOOD_STDLIB_HOME`, `IRONWOOD_GC_HOME` and `IRONWOOD_BRIDGE_SUPPORT_HOME`
to the matching current tree/SDK/support. Include matching lld in PATH. Rebuild
the bootstrap and current SDK, rather than mixing historical outputs.

```sh
export IRONWOOD_GC_HOME="$PWD/workspace/gc8-linux-sdk"
python scripts/prepare-gc.py --prefix "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --target linux-x86_64 --jobs 4
python scripts/prepare-gc.py --prefix "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --verify
python scripts/test-gc8.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc8-linux-reproduction --mode build
python scripts/test-gc8.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc8-linux-reproduction --mode timing
python scripts/test-gc8.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc8-linux-reproduction --mode controls
python scripts/test-gc8.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA_HOME" \
  --output workspace/gc8-linux-reproduction --mode memory
python scripts/test-gc8-foreign.py --sdk "$IRONWOOD_GC_HOME" \
  --llvm-home "$IRONWOOD_LLVM_HOME" --java-home "$JAVA23_HOME" \
  --output workspace/gc8-linux-foreign-reproduction
python scripts/test-gc8-tools.py
```

The manifest records exact focused compiler names and native commands. GC7
fixtures are `gc7_shared_graphs.iron` (O0/O3), `gc7_retained_visitor.iron` and
`gc7_constructor_dispatch.iron` (O3), all with expected exit 42. The visitor
receives a directory containing a three-byte text file. Ordinary graph entry is
`gc8.Gc8GraphMain` at O0/O3. Retained controls use the same SDK/LLVM/Java inputs
with `test-gc-layouts.py`, selected `test-gc-integrated.py` cases and the focused
GC6 drivers listed in the manifest, each in a fresh output directory.

**Acceptance:** GC7's selected current migration/consumer contracts pass on
physical Linux x86-64. GC8's accepted Linux kernels have no unexplained GC
administration or unresolved measured hot-path regression. Defined repeated
resident graphs show exact retention/reclamation and storage reuse; native
allocator capacity and resource counts stabilize. Collection is explicit,
synchronous and eager. The earlier macOS acceptance is preserved.

Limits remain the small process sample, shared powersaving host, bounded graph
sizes and run duration, partial native-memory observations, and absence of a
matched historical Ironwood baseline. This does not qualify every GC7 consumer,
all JDK/optimization/fault combinations, older-OS distribution behavior or the
whole compiler/stdlib suite. Linux arm64 and arm64e remain unqualified;
unsupported foreign/asynchronous lifetimes remain refused. GC9 owns broader
target/SDK/IDK/native distribution acceptance. GC9/GC10, self-hosting and I1/I2
were not started; no compiler build was used as the resident workload.
