<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Original J0 resource procedure and selected corpus

This measurement adapter qualifies only the pinned macOS arm64 development
profile in qualified/identity.json. It uses the exact original J0 JAR and copied
standard library, Java 21, the 4 GiB heap/8 MiB worker-stack profile, cleared Java
option variables, and serial fresh processes. It launches no LLVM/Clang children.
Their costs and total native compile/link costs require separate retained runs.
No native pilot is implemented or evaluated by these measurements.

`measure-resources.py --all --repeat 2 --output PATH` admits seventeen focused
workloads, independently repeated. The destination must be new. `--workload NAME`
selects a focused subset. It freezes exact sources and a hash/shape/outcome
manifest before running javac or any measured JVM. This is a selected resource
corpus, not an invocation of the compiler test suite.

| Family | Exact workloads | Shape and outcome |
| --- | --- | --- |
| Existing ownership references | BranchJoin, SlotOrder | Accepted cleanup versus mandatory array-alias rejection, with original source bytes. |
| Source volume | Volume16, Volume64, Volume256 | That many called static methods, independent of depth. Accepted, expected executable exit zero. |
| Control-flow depth | Control16, Control64, Control128 | That many nested if scopes, independent of source method count. Accepted, expected executable exit zero. |
| Generic type depth | Types8, Types16, Types32 | Nested DepthBox type arguments, null value with no owned allocation. Accepted, expected executable exit zero. |
| Ownership snapshots | Ownership8, Ownership32, Ownership128 | That many branch-store/join/detach operations on one node/array before final reclamation. Accepted, expected executable exit zero. |
| Real inference example | GenericInference | Frozen original example, including target-typed inference and deferred cleanup. Its own executable return contract applies. |
| Real retained source capture | CapturedAliases, CapturedAliasesUnsafe | Frozen example and a paired variant activating exactly its documented unsafe free. Accepted captured state is intentionally process-lived under the original proof; unsafe variant must fail mandatory safety. |

The repeated branch sequence is an actual FunctionAnalyzer snapshot/join workload,
not a map-copy microbenchmark. The separate original EffectContractProbe includes
the field-retained compiler-defined observer, target-cache use, fixed-point
rounds and cross-word bit-vector unions. M0.3's exact native pilot selection must
identify both slices and their reached dependencies; neither this table nor a
resource measurement marks the global dependency inventory complete.

## Measurement boundary

Every workload runs frontend and complete modes with sampling off and on. The
frontend phase is Lexer plus Parser on the frozen source. The complete phase is
CompilerPipeline.compile with missing-free warn, followed by NativeLinkPipeline
finish and final LLVM emission, matching the original reference adapter's final
LLVM path. compile already emits an intermediate LLVM string; its cost is
included explicitly rather than hidden. Failed source compilation ends after
the actual diagnostics and has no LLVM success artifact.

Library-input hash/origin verification and source reading precede phase timing
and sampling. Worker creation precedes the phase as well. A readiness/start gate
lets the sampler begin before compilation. The heap-at-start includes verification
setup still retained by the JVM. Phase timing includes all compiler work on the
worker and observer callbacks when enabled. It excludes subsequent structural
serialization. Process wall/RSS from macOS /usr/bin/time -l includes JVM startup,
verification, worker creation, compiler work and output serialization. Complete
resource runs serialize small IR counts, diagnostics and final LLVM, avoiding
the large full typed-IR serialization in the comparison adapter; canonical
typed-IR references remain separate evidence. No forced GC is used.

The sampler requests a 1 ms pause between observations, samples used Java heap
as totalMemory minus freeMemory, and samples the measured worker's Java frame
count. Its work and scheduling delay extend the real interval; sample count and
maximum observed gap are retained per run. Heap/frame maxima are lower bounds.
Frames are not bytes. Zero maxima in sampling-off records mean unmeasured, not
zero usage. Worker completion under -Xss8m is the stack-limit qualification;
stack-byte high-water remains unavailable. Sampler allocation and safepoint
costs are visible through paired sampling-off runs. Neither sampled heap nor
whole-process RSS is a measurement of native live object bytes.

## Retained observer and evidence counters

BranchJoin, SlotOrder, Ownership128 and CapturedAliasesUnsafe additionally run
observed explanation-on complete configurations with sampling off/on, twice.
The runner compiles SemanticObserverBridge from the exact original Git object,
records its SHA-256, and uses the real package-private analyzer factory seam.
CompilerPipeline retains its factory, SemanticAnalyzer and its summary analyzers
retain the compiler-defined observer, and later fixed-point/lowering/snapshot
notifications update the same Counts holder. Its map/list allocations and retained
state are included in that observed run, separately from the null-observer baseline.

Counters record real save/restore calls (shared empty separately), collector and
snapshot association high-water, invocation/budget high-water and final live
units, summary methods/facts/units, field failures/units, effect analyzer/round
counts, root high-water and evidence retirement. They are associations and
logical accounting units, not allocated bytes or a proof of native reclamation.
The observed seam copies selected fact maps; resource output deliberately emits
only counters, so unspecified observer-map iteration is not compared as semantic
order. Primary verdict/diagnostic sequence and emitted LLVM must remain identical
across observer/sampler settings, while explanation notes are compared only
between configurations that enable them.

Raw javac/process stdout/stderr, exact argv, source/adapter hashes, /usr/bin/time
wall/user/sys/RSS, metrics and artifact hashes are retained. Verification must
check repeated/configuration outcomes and sampling overhead before selecting
numerical budgets. Budget selection remains pending until all admitted runs and
the separate native-tool measurements pass; these limits will be fixed before
native pilot evaluation.

## Initial observations and open ordering failure

[resources-original.tar.gz](resources-original.tar.gz) and its
[exact-byte manifest](resources-original-manifest.json) preserve 1,219 files:
152 initial serial processes, twelve strict-input follow-up processes, source
and adapter bytes, raw logs, qualification reports and two focused input/frame
probes. Initial source decoding used replacement-capable new String, but every
one of the seventeen byte strings strictly validates as UTF-8, so no source was
substituted. The corrected adapter uses the same Files.readString UTF-8 call as
ReferenceCapture. Both reject malformed bytes with MalformedInputException;
all twelve BranchJoin strict-adapter follow-ups match semantic artifact bytes.
Original adapter bytes remain archived rather than replaced by the correction.

All 152 initial processes complete with their expected source verdicts. The
qualifier rejects one exact explanation difference: SlotOrder's selected store
span is line 18 in three observed/explanation-on runs and line 19 in the fourth.
The primary error, source span, counters and all other compared artifacts agree.
No note, diagnostic or IR is sorted/normalized. These failed reference bytes
precede any compiler ordering refactor and must remain retained when the fix
creates a new qualified comparison baseline. The source path under review is
OwnershipSnapshot's Map.copyOf knownArraySlots, consumed by mergeOwnership's
first selectBlocked store witness. Fixing that path does not close the other
snapshot/retained-owner/optional-budget ordering paths.

Observed upper values in this selected initial corpus, not budgets:

| Phase | Fresh process wall | Process RSS | Sampled used heap lower bound | Sampled frames lower bound |
| --- | --- | --- | --- | --- |
| Frontend | 0.460 s | 287.875 MiB | 46.851 MiB | 777 |
| Complete, including observed runs | 2.162 s | 452.516 MiB | 218.679 MiB | 1,024, capped |

Sample-on/off median wall ratios span 0.927..1.077 in frontend and
0.953..1.038 in complete runs; values below one reflect measurement variation,
not proof that sampling is free or beneficial. The largest observed sampling
gap is 10.659 ms despite the requested 1 ms pause. Observer and explanation are
enabled together in the additional configurations, so these runs measure their
combined overhead rather than separating those two contributions. Counters
finish with zero live budget units and retired summary/field evidence; the
largest selected ownership workload records 3,989 evidence saves, 1,966 restores
and 5,868 invocation high-water units. These remain logical counts, not bytes.

The retained effective flags set MaxJavaStackTraceDepth=1024. A focused thread
probe completes 1,500 recursive calls under the pinned stack profile and
returns only 1,024 sampled frames. Therefore a capped trace is explicitly
truncated; no frame budget or stack-byte claim may be derived from it. Completion
of the deep inputs is still required. The [direct kernel measurements](KERNEL_RESOURCES.md)
now qualify original and ordered explanation-store/effect results separately
from these compiler workloads. The [repaired ordering baseline](ordered/DELTA.md)
also passes its selected comparisons. [LLVM/Clang and total pipeline accounting](NATIVE_RESOURCES.md)
now qualify separately. Expanded canonical corpus captures and explicit pilot
closure remain required before budgets are selected.
