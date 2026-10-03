<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7b primitive array implementation log

The maintainer authorized P7b on 2026-09-28. The authoritative boundary is
[P7b in the implementation plan](JAVA_BRIDGE_PLAN.md#p7b-copied-primitive-arrays).
Work stays on local `java-bridge`; Estonia work stays in `~/temp/java-bridge`.
P7c-P7f and further callback performance tuning are outside this task.

## Pre-change review

Affected contracts and consumers: `BridgeAbi` describes transport without
granting export permission; semantic borrowing, retention and non-reclamation
facts remain mandatory. Export selection, entry lowering, generated Java/JNI,
final native proofs and reconstruction must agree on each admitted shape.
Array inputs require identity coalescing, valid native array descriptors,
protected allocation, no callbacks/reentry and cleanup on every exit. Unknown
effects remain failures. Scalar paths must not acquire array bookkeeping.

P7b1 proves read-only one-dimensional primitive inputs before enabling adapters.
P7b2 then adds mutation and bounded results. Copy-back order and failure
precedence must be documented before P7b2 is enabled. The initial conversion
design uses noncritical JNI regions and adapter-owned temporary state, with
native array allocation inside protected typed entries. Repeated Java identities
share conversion state and native storage. Conversion ownership never grants
source code permission to free an input.

Paired cases: reads/helper reads/loops and repeated aliases versus writes,
retention through helpers/fields/statics, source `free`, unknown calls and
callbacks; scalar results versus excluded reference results; matching source,
class and archive facts versus stale facts. Transport adds null/empty/large
arrays, all eight kinds, bit/UTF-16 edge cases and failed acquisition/cleanup.
P7b2 adds alias/fresh results, write-then-throw, native/Java allocation failures
and copy-back failure precedence.

Focused verification: new array proof and native consumer tests, adjacent ABI,
String, object-retention and callback admission regressions; pinned Java 21-23
and LLVM 23 on macOS ARM64, Linux ARM64 and Estonia x86-64. Inspect optimized
code and measure deterministic array conversion/compute throughput and latency.
Run license and diff checks; exercise packaging for changed producer contents.
No unfiltered compiler suites, installations, pushes or publication.

## Checkpoints

- Starting revision: `7d50049d`; canonical checkout/origin verified; clean tree.
- Read the plan, licensing policy and regression pre-change review. Located the
  existing P0 borrowing, retention and array non-reclamation analyses.
- Initial proof checkpoint, commit `389ee8c9`: `BridgeArrayInputs.readOnly` composes P0 borrowing,
  retention and array non-reclamation with a conservative complete-call-closure
  effect check. Primitive-array ABI descriptors grant no export permission.
  The new exact array proof test passed in all unfreed modes, including all eight
  kinds, helpers, loops, aliases, negative effects and source/class/archive
  reconstruction. The three existing model/ABI/proof tests passed. License audit
  and `git diff --check` passed. One initial test compile typo and a missing
  classification for fixed native trace capture were corrected before passing.
- Read-only transport checkpoint: static and instance inputs use noncritical JNI
  regions, adapter-owned staging and a typed protected array conversion. Equal
  Java identities share one native allocation. Source code never receives the
  raw JNI buffer as an array. All acquired staging/native storage is released on
  success and failure; normal scalar adapters have no array state.
- The three new exact fixtures passed on macOS ARM64, Linux ARM64 virtualization
  and Estonia physical x86-64 (isolated CPU 2). Each target additionally passed
  34 Java 22/23 checked-JNI replays. Production source/class/archive jars cover
  O0/O3, all eight kinds, null/empty/large inputs, distinct/repeated aliases,
  scalar and instance calls, native allocation budgets 0/1/2, and native bounds
  and null exceptions. Separate fault images cover first/second staging malloc
  and JNI region failures, unchanged inputs, zero target calls and zero live
  staging buffers. Native counters prove one copy per distinct identity and
  return to baseline after repeated calls. An invalid two-public-type fixture
  was corrected into separate source files before the passing instance run.
- Seven adjacent Mac checks passed: String input/result proofs, root/permanent
  String conversions, complete object admission, owner callback proof, and
  generated scalar/String JNI adapters (including fault and allocation paths).
  License audit and `git diff --check` passed.
- Evidence: `workspace/java-bridge/arrays/{regressions.log,mac-replay.json}`;
  Mac producer `evidence/p7b/arrays/run-3728421012479401792`, fault image
  `evidence/p7b/array-faults/run-17708435144121359502` under
  `workspace/java-bridge/`; Linux ARM64 `arrays/linux-arm64-readonly/evidence`;
  Estonia `~/temp/java-bridge/p7b-readonly-20260928/work/evidence`.
  Linux input archive SHA-256:
  `4b08b97ec1675e0508f3cfaba5217f37528ad247bf300c62876f6e9b6ad29985`.
- Read-only transport was committed as `be738c65`.
- P7b2 in progress: matching P0 mutation/alias/fresh-result proofs and protected
  transport are implemented. The first production test passed on Mac for
  source/class/archive, O0/O3, all primitive kinds, instance methods, null/empty/
  large results, alias identity, write-then-throw and native budgets 0/1/2.
  A separate fault image passed 31 child JVM cases for partial/all copy-back
  failures, continuation after a failed copy, primary exception preservation,
  diagnostic allocation failure with stderr fallback, abandoned fresh-result
  cleanup, and Java result allocation/copy failure. Compiler rollback cleanup
  callees now enter the complete effect closure, with P0 reclamation unchanged.
  A static preallocated exception fixture was rejected by existing retention
  proofs; the fixture now measures ordinary thrown-exception process-lifetime
  allocations against a matching scalar failure instead of weakening proofs.
- Next: verify added String/array composition coverage, additional negative
  proofs, final platform/JDK qualification and conversion performance/machine
  code. No remaining P7 milestone is implicitly authorized.
- All 12 focused Mac tests passed after copy-back integration, including the six
  array fixtures and adjacent String/value proofs and generated JNI consumers.
  Source/class/archive producer payloads, two fault images, alias String results
  and static/instance String/array composition passed. Final added object-result
  composition and fresh String failure cleanup are being verified separately.
  Ordinary root-result delivery continues the established explicit-free/index
  lifetime, including failed facade delivery; this is not array temporary storage.
- The new array benchmark runner passed its functional smoke with identical
  checksums for native Ironwood, Java and the generated bridge, plus native
  allocation/live-storage checks. Full timings and Java 22/23 final replays remain.
  It records batch-average call latency, not individual-call percentiles.
  The previous Estonia read-only evidence archive has been streamed to
  `workspace/java-bridge/arrays/estonia-readonly/evidence.tar.gz`.
- Final additional Mac composition checks passed: array inputs with existing and
  fresh root results, root-slot retention on success and store-then-throw, alias
  and fresh String results, and cleanup when copy-back abandons those Strings.
  The final fault image runs 45 checked-JNI child cases. Two adjacent native
  regressions passed: primitive callback values/nesting/exception identity and
  protected exception getter allocation/Java-delivery failures.
- Final Linux source archive SHA-256:
  `84726ea32349973244d5a1e9dc6568983720d2311e6fd6becf21385e1c232be5`.
  It contains the current source and pinned Java 21 compiler/test classes, with
  a per-file manifest. Runs are in `arrays/linux-arm64-values` locally and
  `~/temp/java-bridge/p7b-values-20260928` on Estonia. Final JDK replays and
  measurement runs are still in progress; no final qualification claimed yet.

## Final checkpoint

P7b1 and P7b2 implementation and qualification are complete. Mutable/result
transport was committed as `e7a2a47d`, following `389ee8c9` and `be738c65`.
The [final evidence report](JAVA_BRIDGE_ARRAY_EVIDENCE.md) records measurements,
commands, payload hashes and the exact supported boundary.

- Final six array fixtures passed on Mac, local Linux ARM64 virtualization and
  physical Linux x86-64 Estonia. Each target passed 148 additional Java 22/23
  checked-JNI child replays. Mac passed the adjacent regressions noted above,
  the final negative reconstruction assertion and Java 24 pre-extraction refusal.
- Full deterministic native/Java/bridge benchmarks passed on all three targets:
  27 groups each, matching checksums and native live-count baselines. Input calls
  allocated zero Java bytes; fresh results allocated expected Java arrays.
  Optimized ARM64 and physical x86-64 payload disassembly confirms vector kernels
  and no array machinery on scalar-only success paths. Numerical acceptance
  remains maintainer review; copied-array calls do not claim a universal speedup.
- Linux's inspection runner initially selected a support library. The actual
  bridge image was re-extracted from each unchanged timed jar and disassembled;
  original inspection output and correction records are preserved. The runner
  now selects only the bridge basename and uses null for unavailable native
  Java-allocation measurements. These fixes changed no measured code or samples.
- All 735 production compiler/runtime/stdlib source files in the final Linux
  snapshot match this checkout. Three-target jar assembly passed with identical
  native bytes; three checked-JNI Mac smoke consumers passed on the combined jar.
- Estonia read-only and final archives were streamed to the Mac, fully read and
  SHA-256 verified. Final archive hash:
  `c2d62af34a95390987480de6617203a8840a8d7a6dd6f889ebe5f75b76073901`.
  Only our two generated `p7b-*-20260928/work` directories were removed afterward,
  reclaiming about 4.0 GiB. Transferred input archives, top-level logs, existing
  JDK/SDK files and Docker images were preserved. No installation was needed.
- No implementation remains within P7b's accepted boundary. P7c-P7f remain
  pending; P7c0 requires the planned public API/lifetime review before coding.

## P7b2 failure ordering

The maintainer chose the following exhaustion policy on 2026-09-28: keep the
original failure primary, attaching copy-back failures when possible; if the JVM
cannot aggregate the report, explicitly report unavailable copy-back diagnostics
on stderr. Do not reserve Java diagnostic objects on successful calls.

Copy back each distinct non-null input in first-parameter order, before result
or exception delivery. Attempt later inputs even if an
earlier copy fails; clear captured JNI exceptions before further JNI operations.
On native failure, its translated throwable is primary. Otherwise the first
copy-back failure is primary. Attach indexed copy-back diagnostics in parameter
order. Aggregation failure never replaces that primary throwable. All conversion
storage and any fresh result abandoned during Java delivery must be reclaimed.
Acquisition failure before native execution leaves Java inputs unchanged. A
failed copy-back is reported as a partial commit, never as a successful call.
