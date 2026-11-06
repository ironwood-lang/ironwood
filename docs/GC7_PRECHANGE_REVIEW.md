<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC7 pre-change review

Baseline: `3afea8f8`, following `a91a13cd`. The designated checkout is clean,
on `codex/bdwgc-integration-plan`, with canonical fetch and push URLs. All work
stays local. GC8-GC10 and self-hosting are excluded.

Read before implementation: repository guidance, LICENSE_MECHANICS, the BDWGC
integration plan, GC0 inventory, GC5/GC6 reports, GC6 pre-change review, MEMORY,
and the shared-analysis regression lessons. Changes are original Ironwood work;
no upstream implementation is imported.

## Contracts and dependency review

GC5 already rejects source reclamation and disables ordinary producers. It still
constructs escape and owned-field summaries repeatedly, re-lowers calls based on
reclaimed arguments, and refines temporary/field/return proofs to convergence.
GC7 must remove this computation, not merely suppress its diagnostics. Follow
remaining consumers before removing their inputs.

| Facts | Disposition and consumers |
| --- | --- |
| Exclusive managed ownership, must-free, temporary destruction, rollback and diagnostic witnesses | Remove after consumer migration. No optimization may justify retaining unused heap proofs. |
| Dispatch/value flow, null-check elimination, initialized types and MayCollect | Retain for actual optimization, exception ordering and exact roots. Unknown effects stay conservative. |
| Pool origin and release helpers | Retain same-pool and at-most-once protocol; managed aliasing is legal. Preserve allocation-free reuse. |
| Foreign views, copied arrays, native acquisition and carrier escape | Retain confinement, resource/JNI rollback and explicit completion. Collection is not resource closure. |
| Bridge reference results and partial constructions | Exact managed layouts, independent RootHandles and roots replace managed destruction premises. |
| Source/class/archive reconstruction | Preserve format-1 embedded source diagnostics and revalidation; stale native inputs refuse. |

No physical root, owner or descriptor ABI is to change incidentally. A physical
change requires identity changes and rebuilt qualified SDK inputs. Keep ordinary
root ABI 2 / owner ABI 1, bridge schema 2 / objects-gc-v1, root ABI 3 / owner ABI 2,
suspended segments, exact layouts and fail-closed verification. Keep BDWGC
8.2.12, USE_MARK_BYTES=1, explicit synchronous collection, no allocation-triggered
collection, D051 ordering and D132/D133 steady-state constraints. Preserve Linux
arm64/arm64e and unsupported lifetime gates and external serialization.

## Inventory and verification selection

Start from GC0's dispositions, then scan tracked current inputs. Cover bootstrap
Java and checked-in Ironwood compiler consumers without compiling I1/I2; runtime,
stdlib, examples/projects, integration fixtures, generators, benchmarks, scripts,
IDE and packaging inputs, and documentation programs. Record historical and
migration-negative inputs separately. Preserve frozen hashes and provenance.

Pair removed source/options with ordinary free/destructor names; unsafe-free
migration diagnostics with shared/cyclic managed graphs and collection; same-pool
helper acceptance with known wrong-pool rejection; foreign view/copy acceptance
with retained/returned/unknown escape rejection. Keep defer capture reassignment,
pending return/yield/exception, assignment failure and D051 order. Keep explicit
file/socket/TLS/JNI closure independent of anchors and collection.

Focused compiler selections will include GC5 intrinsic/effects/migration/final
verification, general defer parser/semantic/typed captures, pool protocols,
initialization, artifact parity and changed IDE generation/output parsing.
Native selections will include GC5 integrated/resources/pools/OOM, relevant
GC2-GC4 controls, and GC6 object/listener/view/carrier/reentry/image controls after
shared changes. A bounded compiler measurement must demonstrate removal of the
obsolete work without claiming GC8 capacity acceptance. Hot lowering changes
require O3 inspection and deterministic controls.

Every compiler/native subprocess uses the existing 16 GiB process-group RSS
guard and bounded timeout. No unfiltered suite or reconstruction of it through
all groups. Revisit selections as scope changes; record exact executed commands,
results and unexecuted boundaries in the implementation report. Run diff,
license, tracked-file and applicable generator checks. Keep logs and binaries
ignored. The disposition ledger must link retired explicit test families to
replacement behavior, separating mixed tests rather than deleting their useful
coverage.

## Completion run from e8ea963d

The completion run closes all six stages. The concrete dependency map is:

- The checkpoint leaves isOwned/borrowedReturnField queries attached to sets
  that are never populated. Remove these dead ownership alternatives and their
  users; retain the lazily computed private-field confinement proof as a distinct
  retention fact. Do not infer nonretention from absence of ownership.
- Preserve actual escape/return provenance, dispatch, pool origin, final-field
  initialization and GC effects. Remove temporary-free special cases, witnesses,
  must-free state and destructor/rollback contexts from those producers.
- Production managed bridge admission already uses GC6 roots/layouts. Remove old
  destructive admission/builders and contracts; keep copied-input/view retention,
  listener final-slot reconciliation, carrier confinement and JNI cleanup.
- Migrate each maintained source/generator and classify each old test family
  before deleting its AST/IR structures. Preserve source evaluation and exception
  order, explicit resources and pool reuse. Frozen reports/hashes stay unchanged.
- Keep physical descriptor layout stable using explicit reserved ABI slots where
  needed; any changed verified runtime input requires SDK identity regeneration
  and qualification. No old ownership meaning may remain in semantic metadata.

Focused safe/unsafe pairs are same/wrong pool; shared aliases and cycles versus
removed syntax; deferred capture/assignment order; initialized/uninitialized
finals; accepted synchronous borrowed views versus retained/returned/unknown
foreign lifetimes; managed anchors/listeners versus stale handles and incomplete
JNI completion. Retain source/class/archive and embedded migration diagnostics,
GC2-GC6 exact-root/failure controls, and O3 deterministic call/pool checks.
Per-child RSS/time bounds remain mandatory. No full suite or self-hosting runs.

The private exception descriptor no longer carries String ownership bits. Its
String results use exact protected result roots. Receiver confinement remains
required: a snapshot graph can contain an invocation-owned JVM carrier, and an
escaping getter could publish that carrier during JNI translation. Pair a
collecting, allocating getter with a getter publishing its receiver, and rerun
the generated callback/JNI completion controls after this shared-analysis change.
The discarded semantic proof prototypes in checked-in Ironwood source are retired;
its AST/lexer/parser data consumers and generated IR catalog are synchronized
without building I1/I2 or running self-hosting qualification.

## Final retained-provenance audit

The detached aggregate-element pass still computed a whole-program freshness
fixed point solely to grant independent managed element identity. Remove
FreshArrayElementAnalysis, its lazy cache/call and the remaining recursive
cancelTemporaryArray no-op. Shared array elements need exact roots, not a
managed detachment certificate.

The new paired pool-origin family exposes a distinct real contract: a known
checkout stored/loaded through a constant array slot loses its originating pool.
Preserve that narrow compile-time fact in ProtocolSnapshot, with exact common
facts at joins and conservative invalidation after unknown mutation. This is
pool provenance, not a reclamation proof or runtime registry. Pair same/wrong
pool through aliases, array slots and return helpers, and rerun pool/native
controls plus GC6 borrowed-view/callback/carrier confinement after this shared
analysis change. No physical ABI or verified runtime input changes here.

The final producer search also found an unreachable explicit-throw matcher left
as a null-returning stub, plus its rollback-capable LLVM helper. Remove that
matcher, absorbed-block state, and rollback/initializer helper switches; retain
the actual bundled cold-throw optimization. Check D051 exception ordering and
O3 call/pool controls. Remove unused field-confinement diagnostic and source
tracking state; the on-demand confinement result still feeds container/pool and
foreign reference provenance. No conservative effect is replaced by a constant.

Focused optimizer controls exposed that the old cold-throw matcher did not
recognize GC5's call/jump/throw shape. Preserve this real optimization with a
small exclusive-predecessor chain matcher, with constructor exceptions allowed
to propagate normally and exact roots across calls. Do not reintroduce managed
rollback. Paired cold-throw/null behavior, D051 and optimized call/pool controls
cover this change. The same native selection found floating-parse operands still
spelled as raw ptr while their declarations require managed address space 1;
correct that operand spelling and retain both float/double typed-IR controls.

The constant-return audit found additional detached copy/getter proof fragments
that could no longer produce a certificate after exclusive owned-field removal.
Delete MapCopyFactoryAnalysis, SetCopyFactoryAnalysis, CopiedMapReadAnalysis,
constructor copied-element/cache calls, single-root list getter stubs and their
unused metadata. Keep only body-derived simple wrapper field origins and
primitive nonretaining read facts consumed by pool provenance. This removes
unproductive proof computation instead of granting a blanket nonretaining effect.

After tracing the remaining field checker consumers, none needs exclusive
private-field confinement. They need an exact final field identity after the
existing definite-initialization checks, plus actual constructor store/return
summaries. Replace the entire checker with FinalFieldOrigins.isStable (instance,
final, reference). This grants no nonretaining effect and no lifetime exemption;
constructor publication and unknown calls still invalidate exact provenance.
Native/foreign confinement remains computed from protected IR and callback
carrier effects. Add a paired same/wrong-pool getter-wrapper case, then rerun
initialization, native-confinement, GC6 snapshot/carrier/callback and pool controls.

The documentation audit exposed another managed-only restriction:
Files.walkFileTree rejected visitors retaining ordinary Path/BasicFileAttributes
values to justify temporary reclamation. Those values are managed, while each
native directory stream is explicitly closed by traversal. Remove this validator
and its borrowing-summary override; remove walkFileTree/walkEntry from the
audited nonretaining whitelist so retaining/unknown callback effects stay real.
Replace the rejection with accepted retained graphs and collection after walk,
and retain traversal depth/link/failure/native-close controls. Removing private
empty releaseRenderedString methods/calls preserves toString operand evaluation,
copying and exceptions; no source-resource cleanup is removed. Review used the
OPENJDK_PORTING behavioral contract checklist; public signatures are unchanged.

The focused socket ledger found a GC5 emitter mismatch: endpoint output-field
GEPs now have managed address space 1, but the native TCP query expects raw
output addresses. Add explicit address-space casts at this noncollecting native
boundary, preserving the actual C ABI and leaf effect. These casts lower to no
machine operation. Compile/run default and custom socket ledgers, inspect the O3
query path, preserve zero repeated-operation/source-close allocation counts,
and retain native close/error checks. This does not change an ABI identity or
introduce bookkeeping/collection on socket paths.

A paired pool-builder check exposed a real gap after publication-proof retirement:
a factory returning a newly allocated object whose constructor publishes this was
still classified fresh/unescaped. Preserve this actual pool contract through
existing constructor escape summaries, including explicit/implicit superclass
calls, and mark the returned allocation's fresh origin escaping. Do not reject
ordinary managed publication or restore a whole-program reclamation/publication
proof pass. Pair quiet/publishing constructors, superclass publication, unknown
factory effects and cached builders, then rerun pools and GC6 confinement/JNI
controls. Placeholder missing-method summaries must remain conservative and may
not manufacture fresh/nonretaining facts to suppress obsolete diagnostics.

The final declaration/consumer audit found a leftover cleanupBodies rejection
stub, an unproduced PERMANENT origin, and String ownership-result queries used
only by their own old test assertions in BridgeRetentionAnalyzer. Delete these
and their unused helpers; all live entry paths continue to include initialization.
Preserve the mixed control-flow tests' reachable/unreachable unwind and reference
effect assertions. Recheck those tests, copied/borrowed input refusals and GC6
carrier/callback proofs plus actual checked-JNI callbacks. These removals grant no
new retention result and do not change the existing foreign-effect fixed point.
