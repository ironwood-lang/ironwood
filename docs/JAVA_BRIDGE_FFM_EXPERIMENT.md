<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7e0 ordinary FFM experiment

Subsequent decision: **D238 accepts JNI only**, closing P7e through its permitted
keep-JNI outcome. P7e1/P7e2 remain deliberately unimplemented. The maintainer
authorized [P7f combined qualification](JAVA_BRIDGE_P7_QUALIFICATION.md) in this
same task. The experiment and original recommendation below remain evidence.

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
- Assembly review found that Clang inlined the experiment's small failure
  helper, unlike production `BridgeValueNativeSources`' explicitly outlined
  helper. Match production's `noinline` declaration before final measurements;
  preliminary timings are superseded. This changes only experimental C.
- `3979a087`: isolated experiment and pre-change review.
- `27843e37`: match production's outlined JNI failure layout. All final target
  runs use these experiment sources, verified by SHA-256. Linux input snapshots
  include unchanged compiler classes and the compiler JAR identified below.
- Final runs completed on all three targets. License audit and whitespace
  checks passed. No production code or packaging behavior changed, so no
  compiler suite or packaging smoke run was warranted. P7e0 is complete;
  P7e1/P7e2 and P7f were not started.

## Results and recommendation

**Keep the flag-free JNI artifact.** Ordinary FFM has a small measured scalar
advantage, but this experiment does not justify adding a production backend and
deployment policy. On the physical Linux judge, the median saving is only
0.23 ns/call on Java 22 and 0.39 ns/call on Java 23, approximately 3-5%.
These are observations, not a statistical significance claim or an application
speedup. Native-access configuration is an additional consumer obligation.
The maintainer has not selected an optional artifact/versioned implementation
or its policy; that selection still precedes P7e1. Retaining JNI as the default
follows the existing plan and does not silently cancel later submilestones.

Each cell contains 21 timed samples from three independent JVM forks, with
eight warmup loops and seven measured loops of 5,000,000 calls per fork.
Fork order alternates. Both transports execute the same native `add(int,int)`
protected entry, with checked results and status handling. Timings below are
median **amortized ns/call**, with reciprocal throughput in millions of calls
per second. They are not sampled p50/p99 latency or OrderBook measurements.

| Target | Java | JNI ns/call | FFM ns/call | JNI M calls/s | FFM M calls/s |
| --- | --- | ---: | ---: | ---: | ---: |
| Linux x86-64, Estonia | 22 | 8.250 | 8.025 | 121.21 | 124.61 |
| Linux x86-64, Estonia | 23 | 8.245 | 7.854 | 121.29 | 127.32 |
| Linux ARM64, local VM | 22 | 3.724 | 3.312 | 268.56 | 301.93 |
| Linux ARM64, local VM | 23 | 3.626 | 3.302 | 275.76 | 302.83 |
| macOS ARM64, optional | 22 | 3.578 | 3.287 | 279.46 | 304.25 |
| macOS ARM64, optional | 23 | 3.598 | 3.295 | 277.90 | 303.49 |

Estonia used the physical Intel Xeon E-2288G, isolated CPU 1, kernel
4.15.0-188 and its existing `powersave` governor. No host settings changed.
Individual x86-64 samples ranged from 8.222-10.610 ns JNI and 7.823-11.320 ns
FFM; ranges overlap. Linux ARM64 used the existing Colima VM on this Mac,
not x86 translation. All targets used LLVM 23.1.0, Temurin 21.0.12.1+1 as
bootstrap, and consumers 22.0.2+9 / 23.0.2+7. Mac timings are supplementary.

All **252 measured rows** recorded exactly **0 Java allocated bytes and
0 Ironwood managed allocations**. Arena storage and MethodHandles are created
before measurement. The native allocation counter does not count arbitrary
JVM native-heap allocations; disassembly independently establishes that the
successful native add path has no heap calls. No broader allocator claim is
made. Java counts use the calling thread's `ThreadMXBean` counter.

### Functional and policy evidence

On each target:

- Ten O0/O3 functional children: JNI on 21/22/23 and FFM on 22/23. They cover
  boolean, signed byte/short/int/long, unsigned char, float/double raw bits
  (signed zero, subnormal, infinities and noncanonical NaNs), void effects,
  integer wraparound, successful one-time initialization, repeated failing
  initialization, null throws, allocation/reclamation and recursive unwind.
  Successful calls after failure prove continued execution. Initializer effect
  counts rule out retrying a failed operation.
- Ten separate allocation-limit-zero children contain native OOM and then
  complete a successful scalar call. JNI diagnostics were enabled for these
  and the O0/O3 functional children, but not for timed measurements.
- Closed and wrong-thread FFM result segments are rejected before the
  side-effecting `noop` entry executes. The private address table rejects an
  unknown entry ID. Four missing-image children report `UnsatisfiedLinkError`.
- The same Java 22 optional class bytes run on Java 22 and 23. Java 21 baseline
  constant pools contain no final-API FFM references and have class version 65;
  optional classes have version 66. The named-module build is separate.
- Twelve FFM launch-policy children plus two flag-free JNI named-module
  children run on 22/23. The outcomes below match on all targets.

| Launch | No native-access option | Matching option | Option for the wrong module |
| --- | --- | --- | --- |
| FFM class path | Success with restricted-call warning | Success without warning, `--enable-native-access=ALL-UNNAMED` | `IllegalCallerException` |
| FFM named module | Success with restricted-call warning | Success without warning, `--enable-native-access=ironwood.ffm.experiment` | `IllegalCallerException` |
| JNI class/module path | Success without warning | Not required | Not part of the JNI contract |

The runner never grants access programmatically or masks a denial. Java's
[restricted-method contract](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html#restricted)
is the reference; recorded child output is the evidence for these exact JDKs.

Each target also has eight diagnostic stack series: JNI/FFM, Java 22/23,
`-Xss512k`/`-Xss1m`. All complete success and contained native failure through
depth 8,192, then fail at the next tested depth, 32,768. These are coarse
observed brackets, not exact thresholds. Linux children terminate with SIGSEGV;
Mac children terminate with SIGILL; the signal alone does not establish the
precise faulting instruction. Core dumps are disabled. Child output and exit
status are retained; these fatal cases produced no `hs_err` file despite the
configured destination. This does not promise recovery from native
stack exhaustion, nor authorize unchecked recursion. Both transports have the
same observed bracket. The successful bounded cases are containment evidence;
the deliberate crashes are diagnostic limits, not passed safety proofs.

### Optimized code and experimental limits

All three O3 images contain a four-instruction protected add body: add, result
store, status-zero return setup and return. The FFM C pointer adapter is one
tail branch to that entry. JNI's pointer adapter is inlined into its native
stub, which reserves the 288-byte result frame, calls the same entry, checks
status and reads the result; its failure helper is explicitly outlined like
production. Neither successful native path allocates, performs TLS access,
looks up a registry or maintains tracing state. The extra FFM tail branch is
included in its timing. Original full disassembly and extracted
`scalar-code.txt` are retained.

The primitive entries remain hidden symbols. Mac exposes only `JNI_OnLoad`;
Linux additionally exposes normal linker init/fini and trace section boundaries.
Experimental JNI bootstrap provides only a fixed entry-address table. This
does not alter production image export rules or permit arbitrary lookup.

This experiment uses a single-thread confined, reusable FFM result frame. It
does not implement the concurrency/reentrancy storage policy that a transparent
producer backend would need. Cold translation intentionally reports only the
native failure type through a shared private JNI helper; complete production
exception projection is not qualified here. P7e1 would still require that
integration, payload/loading identity, full generated-artifact checks and all
its supported lifetime semantics. No critical downcalls, callbacks, reference
conversions, FFM upcalls or public API expansion are included. Java 24+ support
and the production refusal remain unchanged.

## Identities, evidence and reproduction

Final experiment source revision: `27843e37`, following `3979a087`.
Production compiler bytes are unchanged from the preceding P7d2 candidate:

```text
compiler/build/ironwoodc.jar
14c59cd2f7cda9b71dde336ffe7ba7164db755b25ba1cb1bf53463ff2bd9f836
input-27843e37.tar.gz (tracked files and prepared compiler outputs)
05baea10ae295be5b1d3b925ff6edcb4efc621844f201362451e580f1672bd40
estonia-evidence-27843e37.tar.gz (including adjacent native support sources/licenses)
c8e54a18a82f1ca4196654a5ca6a576d92be869752e8fa0e0c4beed58bc605c0
```

| Target | O0 image SHA-256 | O3 image SHA-256 |
| --- | --- | --- |
| macOS ARM64 | `2fdb392498adc485db7b1739c6ccf534a93b1da927b46a0e8a820bcfc9e19187` | `9d2bcbe832e1c865b57892e68a64ae222fe9e66b150261bc8a03143460ccccf6` |
| Linux ARM64 | `d7b6321bbc428b851226ae01311c2c102e38fd9d30e5d2e5b7e17a3ad6802d4e` | `c91037cae77ebec3c361d3564352e63738ff06b27515ca4bccf9bb3bf26ea021` |
| Linux x86-64 | `51ef3837f90dd6c56d1a107780c684c22a54561a8e8f8eec90bbc72c59776afd` | `b1784f96364b6170b2e092fc065a433ed736ca677e8f1c729a3a67abe702831d` |

Local evidence is under `workspace/java-bridge/p7e0/`:

- `mac-final/`: native Mac run, with experiment source hashes matching the
  final commit even though it ran immediately before that commit.
- `linux-arm64-27843e37/work/evidence/`: local ARM64 VM run.
- `estonia-27843e37/evidence/`: copied physical x86-64 run, all 362 catalogued
  files verified against `hashes.json` after transfer.
- `estonia-initial-and-host.txt`: CPU/kernel/isolation/governor inventory. Its
  initial timing section is superseded by the final run.
- `licenses-final.txt`, input/evidence archives, and runner stdout logs.

Each evidence directory has 84 samples, 98 recorded child commands/outcomes,
policy/stack summaries, compiler/consumer command arguments, version output,
LLVM, adapter sources, image hashes and disassembly. `validation/contents.sha256`
was checked before both Linux runs. Superseded preliminary evidence is retained.
No software was installed and no pre-existing host files were deleted.

Estonia retains the snapshot and full evidence at
`~/temp/java-bridge/p7e0-27843e37/work/`. The existing x86-64 container image is
`ironwood-bridge-linux-x86_64:1a18fe26577fb8c5`, identity
`sha256:dd4c1e82b2f999db86e75538f7d32adcaa364452f99a8f615eca693707c382a7`.
The ARM64 image is `ironwood-bridge-linux-arm64:05d5199baf46c922`, identity
`sha256:a03b0d3a764744079949a3adf5ecf6fe7ce82382ee35ca95360a38d453af8767`.

Use the [runner prerequisites and exact commands](../scripts/java-bridge/ffm/README.md)
with a new output directory. For the recorded Linux containers, mount the
snapshot checkout at `/work`, existing support SDK at `/support:ro` and pinned
JDK parent at `/jdks:ro`, set `IRONWOOD_BRIDGE_SUPPORT_HOME=/support`, and run
from `/work`. Estonia mounts must remain under `~/temp/java-bridge`; its existing
SDK/JDK parent directories are under
`ironwood-bridge-validation/workspace/java-bridge/`. Use `--cpu 1` on Estonia.
The runner produces all expected results and diagnostic evidence described
above without provisioning dependencies.

## Handoff

P7e0 is complete, with no production backend enabled. Only documentation changed
after the final experiment revision. A fresh task can resume from local
`java-bridge`, this report and the authoritative P7e/P7f sections of the plan.
Before P7e1, the maintainer must select whether to pursue the optional backend
and its explicit deployment policy. The recommendation is to retain JNI and
avoid that work for the measured gain; accepting that gate outcome is a
separate next-step decision. Do not infer permission for P7e1, P7e2 or P7f from
this completed experiment.
