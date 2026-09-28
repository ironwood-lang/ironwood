<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge Linux x86-64 hardware evidence

This page preserves the original `2559e145` candidate evidence. See the
[optimized candidate report](JAVA_BRIDGE_OPTIMIZED_PERFORMANCE.md) for the current
implementation, measured ARM64 results and pending x86-64 refresh.

Estonia completes the selected physical x86-64 correctness checks and performance
collection, including D213's deferred P0-10 stack experiments. The nine supported
platform/JDK cells now have matching execution evidence. **Final numerical
acceptance remains the maintainer's review; P6b and release readiness remain
open.** No production change or expanded support was needed. P5/P7 are deferred;
Java 21-23 remain supported and Java 24+ remains refused.

## Hardware, identities and execution

SSH alias `estonia` identifies host `hivelocity`: physical Intel Xeon E-2288G,
eight cores/16 SMT threads, with no detected hypervisor. Ubuntu 18.04.6 runs
kernel 4.15.0-188-generic and Docker 20.10.21. Containers used the maintainer's
isolated CPUs `1-4,9-12` (four physical cores and their SMT siblings), UID/GID
1001, no network and no core dumps. The existing powersave governor was retained.
There were no concurrent bridge builds, transfers or compression during timing;
ordinary host activity was not disabled. Host inventories and exact commands
are retained. No host packages were installed or configuration changed.

All remote work stays under `/home/soliveira/temp/java-bridge`. The development
and minimal JVM images match the offline handoff manifest. The pinned toolchain
is LLVM 23.1.0; consumer JDKs are Temurin 21.0.12.1+1, 22.0.2+9 and 23.0.2+7.
Host Python 3.6.9 was used only for compatible transport/setup operations;
container Python 3.14.7 runs the qualification tools.

Initial checkout: `6f60819002963c4e021d77ba162e7f9179d962a4`.
Continuation: `d533f1a63e8841478324f1248f2f2e850713c5d2`.
Production compiler revision remains `2559e145`; all five assembled candidate
jar hashes and compiler/runtime content identities match
[the frozen P6a candidate](JAVA_BRIDGE_P6_EVIDENCE.md#p6a-candidate).

The initial proof stage passed 14 cases and failed its extraction-only generated
loader fixture: it had a hardcoded macOS ARM64 fake payload on a Linux host.
Commit `d533f1a6` selects the executing host's target, deployment floor and resource
filename. It changes only that test file, preserves all assertions and the
explicit three-target matrix, and passes strict compilation and the exact test
on Mac ARM64, Linux ARM64 and Estonia. The original failure/log/exit remain intact;
the recorded continuation verifies the single-file diff and successful retry
before running only unfinished stages. No candidate/native bytes changed.

Retaining-call tooling committed in `fef82f3e` was run as identical archived
source outside the tracked Estonia checkout. Its source/class/jar hashes are in
`retention-supplement/result.json`. The checkout was not relabeled as that later
revision. The tool was also run on both ARM64 hosts before collection closed.

## Focused results

| Check | Result |
| --- | --- |
| Generated fixtures | 17 selected cases pass, including original P0-10 O0/O3 stack envelope/limits, root retention/failures, forced reuse, identity, enums, exceptions, exhaustion and actual OrderBook allocation |
| Compiler proof/guard cases | 14 initial passes plus the corrected extraction test; all 15 selected contracts covered |
| Frozen candidate | Audit and 90 plain/checked class-path, module-path and executable-jar launches pass |
| Loaders | 114 scenarios pass; six macOS-only deployment-floor cells explicitly not applicable |
| Generated public stack | Six bounded O0/O3/JDK cells pass; separate 512k/1m limit children retain unsuccessful depths and crash logs |
| Supported-JDK fixture replay | 196 unchanged generated consumers pass on Java 22 and 196 on Java 23 |
| Minimal JVM | Version O0/O3 and actual OrderBook pass with checked JNI; OrderBook also disables escape analysis and reports zero warm Java allocation |
| Main performance | 132 records with validated identities, deterministic checksums and zero warm scalar/instance/cache-hit Java allocation |
| OrderBook batch latency | 30 reports preserve workload verification, clock controls and raw percentiles |
| Retaining calls | 63 observations across three forks per JDK; state/lifetime assertions pass and every warmed observation reports zero Java bytes |

Limit-child crashes are preserved diagnostics, not claims of stack-overflow
recovery. Forced fatal controls retain their exact expected exits. The separate
Java 24 refusal checks against the final candidate ran on macOS as required by
the plan. No unsupported cell or translated run is counted as hardware evidence.

## Performance observations

The method and limitations in [the ARM64 report](JAVA_BRIDGE_PERFORMANCE.md)
apply: unchecked JNI, three fresh forks per mode/JDK, seven warmed micro
observations per fork, consumed results and matched handwritten JNI arithmetic.
Cells are median [minimum, maximum] nanoseconds per operation. OrderBook rows
are per eight-operation empty-to-empty cycle, not per operation. Different host
hardware/OS configurations prevent attributing cross-host differences to the ISA.

| Case | Java 21 | Java 22 | Java 23 |
| --- | ---: | ---: | ---: |
| Handwritten JNI scalar | 7.30 [7.22, 10.69] | 7.26 [7.22, 9.85] | 7.33 [7.22, 9.61] |
| Generated static scalar | 8.11 [8.02, 10.83] | 8.09 [8.02, 10.88] | 8.05 [8.02, 10.80] |
| Handwritten JNI receiver | 7.32 [7.22, 10.33] | 7.26 [7.22, 9.96] | 7.25 [7.22, 9.89] |
| Generated instance scalar | 8.42 [8.42, 11.02] | 8.45 [8.42, 11.11] | 8.45 [8.42, 11.12] |
| Cached object return | 98.06 [97.69, 101.73] | 99.05 [98.84, 102.48] | 102.31 [101.79, 108.23] |
| Copied String | 257.36 [217.70, 363.81] | 254.06 [220.18, 361.88] | 246.89 [221.13, 365.07] |
| Mapped checked exception | 24341.28 [15324.52, 41066.33] | 24285.03 [15607.53, 33494.17] | 24515.81 [15932.03, 36812.06] |
| Native scalar batch, per iteration | 0.85 [0.81, 1.25] | 0.85 [0.81, 1.24] | 0.85 [0.81, 1.27] |
| Java OrderBook cycle | 260.44 [258.13, 285.56] | 301.28 [260.39, 303.17] | 306.02 [302.55, 312.12] |
| Bridge OrderBook cycle | 633.89 [631.52, 646.74] | 617.21 [616.80, 618.49] | 636.32 [633.38, 636.32] |
| Native batch OrderBook cycle | 129.81 [128.50, 131.73] | 129.08 [128.57, 129.64] | 129.50 [128.97, 129.55] |

Standalone native OrderBook: 124.40 ns/cycle [124.34, 127.59]. Standalone native
recurrence: 0.802 ns/iteration [0.802, 2.679].

| JDK | Scalar delta vs handwritten JNI, ns | Ratio | Cold first use, ms |
| --- | ---: | ---: | ---: |
| 21 | 0.81 | 1.11 | 376.39 [373.90, 376.72] |
| 22 | 0.84 | 1.12 | 367.63 [366.85, 385.97] |
| 23 | 0.72 | 1.10 | 395.29 [393.80, 395.87] |

Retaining-call medians are 161.42 [160.80, 162.58], 158.10 [157.31, 159.53]
and 156.30 [155.63, 158.38] ns on Java 21/22/23 respectively. These alternate
root/enum arguments while preserving a permanent Catalog, including conversion
and retention commit. They are not isolated native slot-write costs. Each fork
warms one million calls and measures seven groups of 200,000. Native state and
retained-root free refusal are checked outside timing. Native allocation
obligations use the separate instrumented correctness fixtures.

Per-operation OrderBook is slower than the paired Java loop and coarse native
batch on this workload. The coarse native batch approaches the standalone native
cycle cost. These results identify the cost of frequent boundary/object-return
conversions; they do not justify weakening identity or lifetime guarantees.
Strings and mapped exceptions have their required result allocations, recorded
separately. Java-only recurrence and clock-control loops show very small logical
iteration costs and possible loop optimization, so they are not assumed scalar
instruction-cost baselines. Cold first use includes extraction/loading after JVM
startup. Sampled micro latency includes clock/dispatch overhead without subtraction.

## Code inspection and batch latency

`performance/micro-disassembly.stdout` shows receiver entry
`ironwood_bridge_entry_4` as load/add, result store, zero status and return;
static `ironwood_bridge_entry_6` performs the wrapping multiply/add, result store,
zero status and return. Neither typed entry calls a helper.
`iw_permanent_4`/`iw_permanent_6` reserve 0x120 stack bytes plus a saved register,
call the typed entry once, branch on status, and return the result carrier.
Their successful scalar paths contain no allocation, cache/registry lookup,
TLS/trace access, synchronization or thread check. Failure translation stays on
the failure branch. ABI/frame/status instructions remain measured overhead.
JIT compilation/inlining logs show scalar and instance facades inlined into the
consumer; these logs are not a claim to have inspected HotSpot machine assembly.

OrderBook latency uses 20,000 warmup batches, 100,000 measured batches, eight
cycles/64 operations per batch. Values below are median [minimum, maximum] of
three reported batch p99 values in microseconds, with clock overhead included.
They are harness observations, not service-latency guarantees.

| JDK | Paired Java | Per-operation bridge | Coarse native batch |
| --- | ---: | ---: | ---: |
| 21 | 1.838 [1.806, 2.042] | 4.955 [4.947, 5.243] | 1.128 [1.121, 1.151] |
| 22 | 1.889 [1.854, 1.890] | 4.910 [4.882, 5.006] | 1.130 [1.122, 1.147] |
| 23 | 1.843 [1.843, 1.847] | 5.110 [5.083, 5.168] | 1.128 [1.117, 1.129] |

Standalone native batch p99 is 1.044 [1.034, 1.047] microseconds. Full reports
retain averages, extrema, clock controls and percentiles through 99.999%.

## Evidence collection and remaining gate

The remote checkout retains `workspace/java-bridge/evidence/p6b/x86-estonia-1`,
its exact fixture directories, original failure/retry evidence and all payloads.
Top-level `host-evidence` retains preflight, affinity, images, minimal consumers,
commands, timing boundaries and container states. All task containers exited;
none was removed. The later tooling bundle was delivered and hash-verified but
was not applied to the recorded qualification checkout.

Complete archive: `estonia-evidence-d533f1a6-v2.tar.zst`, retained both under
`/home/soliveira/temp/java-bridge` and locally in
`workspace/java-bridge/evidence/p6b/estonia-archive`.
Compressed size: 1,589,842,601 bytes. Inventory: 28,529 entries, including 20,297
regular files totaling 62,255,765,897 bytes. Archive SHA-256:
`202a72e1df28dd0af01259d50d3510345e03e3b8d0e1a587f198cfd2b3b5f672`.
Inventory SHA-256:
`b2b3e494b8424aa5380bb7007cb97ca9752c80babeb574831562b3a20e1c7346`.

Local `verification.json` confirms compressed transport SHA, every regular-file
SHA, embedded inventory, member types/sizes/permissions and link targets. It
streams archive contents without extracting or following intentional negative-test
symlinks. The first compression attempt rejected incompatible Python API options;
its empty file and failure log are preserved. The first local verifier compared
full filesystem modes with tar permission modes; after confirming tar's separate
type representation, the corrected check passed without changing the archive or
weakening content hashes. Both verification attempts remain in `experiments`.

The local `p6b/estonia-review` tree is a convenient selected text/report copy,
not the complete artifact archive. Earlier partial fixture transfers are retained
and are not used as a completeness claim. The full archive includes the failed
proof stage, exact successful retry and continuation helper. Final archive
verification/container-state records and the later tooling supplement sit beside
the immutable archive, since they were produced after it closed.

Focused source checks, strict Java compilation, all nine retaining-call cells,
runner plan inspection, documented handoff hash verification, shell syntax,
license audit and `git diff --check` pass. No unfiltered compiler suite ran.
No implementation or hardware-only x86-64 work remains within the authorized
scope. The maintainer must accept these numerical observations or request
optimization before P6b closes. Java 25 is a separate later policy decision,
with [D209 findings](JAVA_BRIDGE_JAVA25.md); this run keeps Java 21-23. Nothing
was pushed, merged into main or released.
