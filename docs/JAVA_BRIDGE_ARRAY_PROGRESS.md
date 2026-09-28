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
- Next: collect conversion performance/machine-code evidence and complete P7b2
  mutation/result proofs and failure ordering. No mutable or array-result API is
  admitted yet. No remaining P7 milestone is implicitly authorized.

## Pending evidence

Read-only performance/machine-code evidence and P7b2 remain pending. Final
qualification must use the final implementation bytes. Passing read-only
transport checks does not complete P7b.
