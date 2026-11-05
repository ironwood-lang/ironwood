<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC5 integrated cutover verification

Baseline: `23190dcd`, following the qualified private checkpoint. This task must
reach the normal compiler/runtime/required-library checkpoint before completion.
The prior GC5 records remain historical checkpoint evidence.

## Pre-change review

Retain D051, D132/D133, explicit synchronous collection, complete zeroing,
successful-language-allocation accounting, exact physical references and native
resource/pool protocols. Allocation, counter reads and resource closure do not
collect. Unknown effects stay conservative. Native/shared/bridge outputs remain
closed until GC6; Linux arm64 and arm64e remain unqualified.

The implemented design will put the engine descriptor/layout prefix in the
actual language type metadata, preserving one dynamic type pointer and constant
field/dispatch offsets. Bind those immutable physical layouts during cold image
startup. Use a dedicated internal allocation domain for real Throwable metadata,
secondary occurrences and pointer-free trace buffers, including explicit roots
for live native wrappers and bounded native emergency representations. Preserve
source trace rendering and optimized source attribution. No source destructor,
managed rollback, implicit temporary or rendered-result release may survive
final linking. Retire producers rather than filtering their instructions.

Affected consumers: parser/CLI and source/class/archive reconstruction;
FunctionAnalyzer cleanup, exception values and construction; shared dispatch,
escape/initialization/pool facts; pruning/specialization; GcEffects and final
managed-heap verification; normal LLVM emission, entry/throw/init helpers and
late trace/statepoint passes; runtime allocator domains, file construction,
trace/secondary/emergency ownership; all required library source and artifacts;
physical reference slots and pooled entry reuse. GC7 owns broad dead-code deletion.

Paired verification: removed syntax versus ordinary free/destructor identifiers;
canonical collect versus malformed/spoofed intrinsics; all source/class/archive
forms; aliases/cycles/shared containers versus dropped roots; collecting
initialization/constructors and published failed construction versus explicit
native acquisition rollback; deferred captures/pending values and D051 failures;
trace refresh/secondary graphs and bounded OOM; preparation OOM versus corrupt
metadata; reference clearing versus intentional pool retention; actual ABI,
layout/configuration/pass identities versus stale native inputs. Test root and
exception cases at O0/O1/O2/O3, inspect O3 kernels and run deterministic call/pool
controls. Retain GC2-GC4 qualified controls where their contracts remain relevant.

Use Java 21/LLVM 23, pinned BDWGC 8.2.12 and USE_MARK_BYTES. Rebuild and verify
changed SDK inputs without downloading the already cached dependency. Retain
16 GiB process-group RSS and bounded children, the remote 8 GiB task cap/20 GiB
free reserve and no unfiltered suites. Commits remain local on the existing branch.

## Native immortal graph review

The real emergency representation exposes a further shared contract: an immortal
base may own managed references, but those references must remain live only while
the base is reachable from source or an active native owner. Unconditionally
rooting emergency payload fields would retain dropped secondary graphs. Merely
omitting them would lose graphs reached through managed containers.

Use exact cold registration of image-owned native bases and their bound physical
types. Root preparation expands directly rooted native graphs. Exact mark
procedures expand native graphs encountered through managed fields. Traversal is
bounded, allocation-free during collection, and revisits native graphs on every
mark-stack recovery traversal. Unreachable emergency payloads and their associated
native ownership state are cleared after collection without callbacks. No pool
cleanup or source callbacks participate. Ordinary allocation and field access do
not consult the native inventory. The fixed reference mark-procedure choice must
be requalified with GC2 overflow controls and collection latency evidence.

Consumers: engine descriptors/overflow recovery, native root preparation, real
Throwable and emergency ownership, compiler immortal inventories and native image
metadata. Paired cases: directly rooted OOM versus dropped OOM; a surviving
managed container pointing to an emergency graph versus a dropped container;
repeated collection of a retained graph versus emergency-slot clearing; native
cycles and shared subgraphs; overflow recovery with native edges. Keep normal
noncollecting O3 kernels and deterministic pool/call controls unchanged.

The real runtime uses 64 fixed native emergency occurrence cells, matching GC4's
bounded emergency capacity. Their exact layouts include the owning metadata and
source exception references. A later OOM must not detach occurrences from an
older surviving primary. Explicit collection clears cells whose owning graph is
dead; only cells belonging to the active delivery are temporary native roots.
The immortal OOM's current metadata may be reset on reuse, as with the existing
cached failure identity. Exhausted bounded emergency association storage refuses
instead of silently dropping D051 occurrences or collecting automatically.

## Exceptional-only statepoint roots

The normal O3 D051 fixture exposed a GC4 pass limitation: LLVM derives emitted
base/derived stack-map pairs from gc.relocate users. Removing the invalid Itanium
exceptional relocation could remove the only user describing a pending primary,
even while the statepoint retained a gc-live operand. The second collecting
cleanup then reclaimed that primary. O0 and the earlier native-owner fixtures
had masked this case.

For each exceptional relocation, use LLVM's proven base operand to spill the
base at its associated collecting invoke. Describe these native stack slots in
the existing ABI-2 deopt region inventory. Describe each spill only at its associated statepoint. Late verification
requires an immediately preceding typed store and restricts spill uses to that
store and deopt description. Stale slot contents are untraced at other PCs, so
there is no entry initialization, continuation clearing, per-defer registration,
heap action storage, TLS or ordinary call maintenance. Preserve exceptional
identity replacement and final LLVM verification. Ordinary managed stack regions
retain their existing whole-function coverage checks, now including invokes. Paired controls: a pending primary used only on an
unwind path versus dropped primaries; normal/exceptional continuations, nested
cleanup, captures and pending return/yield; O0/O1/O2/O3 and the retained GC3/GC4
metadata-corruption and overflow controls. Reinspect noncollecting O3 kernels.

LLVM 23 partial inlining can create an internal outlined function without copying
the parent's GC strategy. Preserve the existing O3 optimization policy. Compare
the compiler-emitted and optimized definition inventories: only new internal
definitions created by the trusted optimizer acquire the managed strategy.
Existing definitions losing their strategy still fail verification. Verify both
inventories and the final module before statepoint rewriting. Paired controls:
new internal outline versus an original missing strategy, externally visible
new definition, and unannotated input. This is backend metadata propagation,
without a source-language exception or an assumption that unknown calls are leaf.

The native main boundary must retain its frame when invoking ironwood_run.
An O3 allocation-failure control exposed main-to-run tail-call elimination and
therefore a missing approved boundary. Disable tail calls on that cold entry
function, matching managed functions. Check no-collection, allocation-failure
and preparation-failure entries at every optimization level and retain missing
boundary/unwind negative controls.

Native entry argument allocation makes the real OOM/Throwable dependency
mandatory even for scalar source. Load the bundled definition before analysis
and retain its actual hierarchy during pruning. Preserve dead-code pruning of
unrelated classes. String-concatenation descriptor arrays have compile-time
sizes, but old non-entry allocas made LLVM report dynamic frame sizes at O0.
Hoist those typed compiler temporaries into each function's entry block,
including concatenations in invoke terminators. Keep the root reader's dynamic
frame refusal. Paired checks: file/path construction at O0/O3, concat loops,
scalar native entry and no-collection empty maps; existing map-corruption controls.

The final timed-entry comparison includes ironwood_run, not merely the new cold
main boundary. The call kernel preserves the prior 25 instructions. The pool
control exposed extra callee-saved spills after the heap cutover, with a small
timing difference inside overlapping samples. Make the already-populated slot
an explicit early-return path and keep growth/building in a separate source
helper. Preserve checkout identity, pointer advancement only after success,
preload/null behavior, growth and builder failure ordering. Paired verification:
populated reuse versus empty/growing slots, null/throwing/collecting builders,
known same/wrong-pool returns, deterministic defer/finally kernels and alternating
GC4/GC5 timings. Do not add runtime bookkeeping or a new inlining restriction.
