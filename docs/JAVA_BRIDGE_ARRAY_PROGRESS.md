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
- Initial proof checkpoint: `BridgeArrayInputs.readOnly` composes P0 borrowing,
  retention and array non-reclamation with a conservative complete-call-closure
  effect check. Primitive-array ABI descriptors grant no export permission.
  The new exact array proof test passed in all unfreed modes, including all eight
  kinds, helpers, loops, aliases, negative effects and source/class/archive
  reconstruction. The three existing model/ABI/proof tests passed. License audit
  and `git diff --check` passed. One initial test compile typo and a missing
  classification for fixed native trace capture were corrected before passing.
- Next: protected conversion and matching adapters, then native verification.
  Producer arrays remain rejected until complete admission is available.

## Pending evidence

P7b transport, platform, performance and packaging checks are pending. The
initial analysis checkpoint alone does not complete either P7b1 or P7b2.
