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
