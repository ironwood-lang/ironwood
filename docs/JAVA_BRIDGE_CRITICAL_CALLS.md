<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge critical calls and shared-image tuning

This report records the 2026-10-02 host investigation of the OrderBook bridge,
the two production changes it led to (D241 and D242), their measurements on the
maintainer's ordinary Linux host, and the verification that was run. It follows
the [host performance investigation](JAVA_BRIDGE_HOST_PERFORMANCE.md), which
reproduced the gap but found no missing optimization.

## Summary

The optimized engine code inside the bridge was never slow. Two costs outside
the engine made the bridge slower than Java on the ordinary host:

1. **JNI thread-state transitions.** The benchmark makes eight native calls per
   cycle. HotSpot's JNI wrapper costs about 7.6 ns per call on this Xeon, about
   61 ns per cycle, against 73 ns for the whole native cycle.
2. **Baseline x86-64 tuning.** LLVM's baseline x86-64 model assumes that
   unaligned 16-byte memory access is slow. That is true only for processors
   older than SSE4.2/SSE4A. The portable bridge payload therefore cleared and
   copied adjacent fields one word at a time. The official standalone build
   uses `-march=native` and never showed this.

D241 adds producer-selected critical calls: proved entries are called through
a handle that skips the thread-state transition, with the registered JNI method
kept as the fallback. D242 tunes portable x86-64 shared images for fast
unaligned 16-byte access without adding any instruction-set requirement.

| Scenario, x86host, Oracle JDK 21.0.1, 8M warmup + 80M measured | Median ms | ns/cycle | Million operations/s |
| --- | ---: | ---: | ---: |
| Standalone Ironwood (`-march=native`) | 734.7 | 73.47 | 108.9 |
| Java bytecode | 1483.6 | 148.36 | 53.9 |
| Bridge before this work (JNI, baseline tuning) | 1544.8 | 154.48 | 51.8 |
| Bridge now, JNI calls (D242 only) | 1464.0 | 146.40 | 54.6 |
| Bridge now, critical calls (D241 + D242) | 1090.8 | 109.08 | 73.3 |

Each row is the median of 15 fresh processes, run round-robin across the five
scenarios with no CPU pinning and no diagnostic JVM options. Ranges were
729.7-768.1, 1467.9-1530.2, 1536.3-1570.0, 1445.8-1643.4 and 1077.5-1133.2 ms.
The unit is one verified eight-operation cycle, not a per-call percentile.

With critical calls the bridge takes 29% less time than before and 26% less
time than Java on this host. It still takes 48% more time than the standalone
executable. That remaining gap is structural, as explained
[below](#remaining-gap), and is not claimed closed.

A supplementary macOS ARM64 run (Apple M-series, Temurin 21.0.12, three
processes each) measured 471 ms standalone, 671 ms Java, 749 ms bridge with JNI
calls and 588 ms with critical calls. Linux ARM64 was not measured.

## Where the time went

`perf` is unavailable to ordinary users on the host (`perf_event_paranoid` is 3)
and no host setting was changed. The attribution uses controls instead.

**Native code inspection.** Disassembly of the packaged `libbridge.so` shows the
complete matching engine inlined into each protected entry, with no trace
maintenance, TLS access, registry lookup or allocation on the successful path.
Each JNI adapter is a frame reservation, one call and a status branch.

**Crossing cost.** A scratch microbenchmark calls eight almost empty native
functions with the benchmark's exact argument shapes, in the same loop shape.
Each cell is one process, 8M warmup and 80M measured calls:

| Transport, ns per eight-call cycle | Java 21.0.1 | Java 22.0.2 | Java 23.0.2 |
| --- | ---: | ---: | ---: |
| JNI static natives | 61.0 | 60.9 | 60.9 |
| Ordinary FFM downcalls | 58.8 | 76.4 | 58.4 |
| Critical FFM downcalls | 18.8 | 18.6 | 18.3 |

Ordinary FFM keeps the same thread-state transition, which is why the P7e0
experiment measured only a 3-5% gain. Only the critical option removes it.
`-XX:+UseSystemMemoryBarrier` removes one locked instruction from the JNI
wrapper and recovered about 8% of the bridge time; it is a JVM launch option
and was not adopted.

**Native floor.** A C driver that loads the packaged library and calls its
adapters directly, with no JVM, measures the per-operation API itself:

| Native-only driver, ns/cycle | Baseline tuning | D242 tuning |
| --- | ---: | ---: |
| Eight calls through the library's adapters | 95-97 | 88-89 |
| Standalone executable, whole loop inlined | 81.7 | 74.5 |

**Tuning.** Rebuilding the standalone benchmark with single features isolated
the difference between portable and `-march=native` code:

| Standalone build, three runs each | Observed ms |
| --- | ---: |
| Baseline x86-64 | 817-838 |
| `+sse3`, `+ssse3`, `+sse4.1` or `+popcnt` alone | 820-866 |
| `+sse4.2` | 749-755 |
| `x86-64-v2`, `x86-64-v3`, `native` | 733-752 |
| Baseline instruction set with `-slow-unaligned-mem-16` | 743-801 |

SSE4.2 instructions are not what helps. LLVM clears its slow-unaligned-access
assumption when SSE4.2 or SSE4A is present. The generated assembly differs in
`movq $0, field` sequences becoming `xorps` plus `movups`, which are SSE2
instructions available on every x86-64 processor.

## Remaining gap

At 109 ns per cycle the bridge spends about 74 ns in engine work, about 14 ns
in eight non-inlined native entries (register saves, type-initialization
guards and adapter frames that the standalone loop does not have), and about
21 ns on the Java side (eight downcall stubs, register spills around each call
and four facade identity lookups). The standalone executable inlines all eight
operations into one loop; a Java caller cannot.

Scratch variants bound what else is available on the Java side. Removing the
pending-failure load from every call saved about 3%, and is now done for void,
address and 32-bit results. A strong direct-mapped facade cache saved about
2.5%; it would give up weak identity and was not adopted. An application that
needs standalone speed should expose coarser native operations, which is an
API design choice and not a transport change.

## What changed

- **D241, critical calls.** `ironwoodc --java-bridge --critical-calls=on` adds
  a second native adapter and a constant method handle for every binding whose
  signature and complete native closure qualify. See the
  [producer guide](JAVA_BRIDGE_USAGE.md#critical-calls) for the contract.
- **D242, shared-image tuning.** x86-64 bridge images pass
  `-mattr=-slow-unaligned-mem-16` to `opt` and `llc`. The instruction set stays
  baseline; `native.cpu` remains `baseline-x86_64`. The option is recorded as
  the native build input `cpu.tuning`. ARM64 images are unchanged.
- The OrderBook bridge workflow selects critical calls in `link.sh` and grants
  native access in its launch scripts. Engine sources, benchmark drivers and
  operation counts are unchanged.

## Change review

The affected contracts were identified while reading the producer and before
editing it; this write-up was recorded alongside the implementation.

Invariants that must survive: mandatory ownership, retention and
non-reclamation proofs in every `--unfreed` mode; typed exception containment
with exact native traces; one live weak facade per permanent object; D132/D133,
with no bookkeeping on valid paths; Java 21-23 support and Java 24+ refusal;
flag-free JNI behavior and byte-identical declarations when the option is off.

Shared machinery and consumers:

- `BridgeGeneration`: an optional `calls` key. Consumers are generation
  matching, `fromManifest`, packaged pairing, assembly and distribution.
- `BridgeJavaSources`: a critical index per binding and a support-native
  inventory. Consumers are bootstrap registration, loader preflight, the
  package manifest and the assembler's declaration reconstruction.
- `BridgePermanentJavaSources`, `BridgeFixedEnumSources` and
  `BridgePermanentNativeSources`: additional private members and adapters for
  selected bindings. Existing JNI declarations and adapters are emitted
  unchanged. Callback and value projections are not touched.
- `NativeBackend.linkShared`: one tuning argument for x86-64 shared images.
  Ordinary executables keep their existing arguments.
- New `BridgeCriticalCalls` analysis reads the final admitted program through
  the existing `BridgeCallTargets` edges. It feeds only transport selection.
  No admission, lifetime or effect proof consumes it.

Paired cases covered by the tests: a memory-only closure and the
same closure reaching console output, a clock or an unaudited String operation;
a selected generation and the unselected generation of the same source;
success and failure for each result carrier; linked handles and each fallback
route (consumer override, denied native access); a forged critical index and
mismatched declarations against exact regeneration.

## Verification record

Run with the modified compiler on Linux x86-64 (x86host, scratch clone) and
macOS ARM64. No unfiltered suite was run.

- New focused tests, passing on macOS ARM64 with Temurin 21.0.12 and on Linux
  x86-64 with Oracle 21.0.1, Temurin 22.0.2 and Temurin 23.0.2 as both producer
  and consumer JDK:
  - `Java Bridge critical calls admit only memory-only closures`
  - `Java Bridge critical calls keep JNI declarations and exact adapter inventories`
  - `Java Bridge critical calls preserve values failures and JNI fallback`
- Existing focused selections, run with the final compiler. macOS ARM64, 12
  selections: private enum structure and native behavior, permanent Java
  facades, the actual OrderBook producer and allocation tests, the object, root
  and retaining producers, assembly, generated loader, generation identities
  and the paired-jar producer. Linux x86-64, 10 selections: the same list
  without the three object/root/retaining producers, which need pinned macOS
  JDK paths, plus the Linux producer. All pass.
- The default artifact is unchanged: OrderBook Java sources generated without
  the option are identical to the previous compiler's after normalizing
  identity hashes, and the manifest gains only the `cpu.tuning` build input.
- OrderBook `java-bridge/test.sh` passes on both hosts: exact demonstration
  output with critical calls under checked JNI, with the JNI override, and with
  default native-access policy.
- A failure consumer produces identical output, including the first native
  trace frame, for critical and JNI transport on Java 21, 22 and 23. It covers
  status, address, 32-bit and 64-bit result carriers and the generic enum entry.
- Module-path launches: granting the artifact's module links every handle;
  granting only another module selects JNI without a warning; no option links
  the handles and the JVM prints its restricted-method warning.
- A rooted fixture (`PriceEngine` with `free()`) selects seven of eight
  bindings, returns correct values and failures for long, int, boolean, float,
  double and void, and still refuses access after `free()`.
- A one-target assembly and a distribution package of the critical OrderBook
  jar both succeed, and the assembled jar runs the demonstration and benchmark.
- Interpreter-only execution and the Parallel, Serial and Z collectors run the
  critical jar correctly.

Not verified: Linux ARM64; JVMs other than HotSpot; multithreaded callers,
which the bridge's confinement contract already excludes; safepoint latency
under long-running critical entries, which is a stated producer obligation.
Scratch drivers, variants and raw samples are under `~/temp/java_bridge` on
x86host; they are measurement inputs and not product code.
