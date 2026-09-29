<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7e0 ordinary FFM experiment

## Scope and pre-change review

The maintainer authorized only P7e0, starting from `0823e89d` on local
`java-bridge`. Canonical checkout, both origin URLs and a clean tree were
verified. P7e1 and P7e2 remain unimplemented. No producer, runtime, ownership
analysis, public facade or supported Java version changes are intended.

The experiment reuses `CompilerPipeline.compileBridge`, `BridgeEntryModule`
and the production native linker. Both transports call the same protected
primitive entries. Private C pointer adapters preserve the result/status ABI;
their addresses are supplied by experiment-only JNI initialization, so no
production export policy is relaxed. Java 21 baseline classes contain no FFM
references. Separate Java 22 experiment classes run unchanged on Java 23.

Affected contracts: primitive bits and calling convention, initialization,
exception containment, invocation-local result storage, D132/D133 steady-state
costs and Java native-access policy. No shared compiler machinery is changed.
Protected errors stay within native code before either transport returns.
No critical calls, upcalls, automatic permission grants or replay fallback.

Focused verification selection:

- Pair JNI and ordinary FFM scalar results, including all primitive widths,
  void, boundary values, signed zero and noncanonical NaN payloads.
- Pair successful initialization with failed initialization, null throws,
  allocation exhaustion and child-process small-stack execution.
- Check closed/confined result segments reject FFM calls before native entry;
  loading and missing-symbol failures must be explicit.
- Run Java 21 JNI independently; compile optional sources with Java 22 only.
  Run class-path and named-module launches on 22/23 with no grant, the matching
  grant and a mismatched grant, recording actual warnings/failures.
- Measure warmed JNI/FFM loops in separate children, repeated on physical
  Linux x86-64 CPU 1 and local Linux ARM64, with checksum and JVM/native managed
  allocation deltas. Inspect optimized native code for steady-state native
  heap allocation and wrappers. Allocation-counter coverage is stated exactly.
- Record commands, source/image hashes, pinned tool versions and raw evidence;
  run license checks and `git diff --check`. No compiler suite is needed for
  an isolated experiment unless a compiler change becomes necessary.

## Checkpoints

- Pre-change review recorded. Existing protected-entry and production-linker
  fixtures inspected. Java 22 FFM package and Linker specifications consulted:
  [package contract](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html),
  [Linker contract](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/Linker.html).
- Implemented an isolated runner under `scripts/java-bridge/ffm`. The first
  attempt accidentally selected the implicit constructor with primitive roots;
  the existing compiler rejected it. The builder now selects its exact 16
  primitive methods; no compiler restriction changed.
- macOS ARM64 preliminary run `workspace/java-bridge/p7e0/mac-second` passed
  Java 21 JNI, Java 22/23 JNI and FFM at O0/O3, policy, OOM, lifetime and stack
  probes. All 84 measured rows had zero Java bytes and zero Ironwood allocation
  deltas. The runner now asserts the observed policy outcomes explicitly.
- Pending: matched Linux runs, optimized-code review, final evidence and
  deployment recommendation. The flag-free JNI artifact remains the default.
