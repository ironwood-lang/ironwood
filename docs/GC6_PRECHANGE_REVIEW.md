<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC6 pre-change review

Baseline: `c76a3a75`, on the existing local
`codex/bdwgc-integration-plan` branch. The checkout and both origin URLs match
the maintainer's designated checkout. GC5's normal executable qualification is
the starting point. No fetch, main integration, push or hosted work is selected.

## Contracts and implementation boundaries

GC0's generated lifetime surface, crossing table, suspended segments and image
isolation are accepted requirements. Preserve exact layout version 1, root ABI
2 and owner ABI 1 until a replacement protocol has direct qualification. Do not
turn their foreign-segment refusals into successful incomplete enumeration.
Keep the normal typed-IR/LLVM 23 pipeline, pinned BDWGC 8.2.12 with
USE_MARK_BYTES=1, explicit eager synchronous collection and disabled allocation
collection. No conservative fallback, source destruction, collection-time JNI
cleanup, Cleaner, finalizer or weak-reference cleanup is permitted.

Preserve D051 exception/copy-back order, D132/D133 noncollecting hot paths,
D188 external serialization, exact initialization and native-resource/ByteView
confinement. Collection, anchor release, JNI ownership and source close are
separate operations. An independently retained alias must survive another
handle's release. Address reuse must not revive stale facades. Reserve native
records before publishing source results, and release only unpublished foreign
acquisitions on delivery failure. Published managed stores survive exceptions.

New source will be original Ironwood code under MIT OR Apache-2.0. Do not import
HotSpot or OpenJDK implementation. Preserve the pinned dependency's existing
source, hashes and notices.

## Existing categories and consumers

| Producer or category | Consumers and GC6 disposition |
| --- | --- |
| `BridgeExportSurface`, `BridgeObjectAdmission`, `BridgeFinalRootRetention`, construction/result/retention analyses | Scalar and object producers, generic projections, permanent/enum values, source/class/archive reconstruction. Replace managed ownership/destruction admission with exact anchor/results facts; preserve actual foreign confinement and initialization proofs. |
| `BridgeRootStateSources`, `BridgeRootIndexSources`, `BridgeRootFacadeNativeSources`, identity caches and Java facade generation | Owning constructors, dependent views, identity and JNI records. Replace reclamation with GC0 Roots/RootHandle projection and independent anchors. Index work stays at export/retain/release boundaries. |
| `BridgeListenerNativeSources`, listener slots/proxies, `BridgeOwnedListenerSlots`, retaining constructors/calls | Listener replacement, explicit unregistration, active callback retirement. Acquire ListenerRegistration before mutation and reconcile actual committed slots on every exit. Do not make root release unregister source listeners. |
| `BridgeCallbackCarrierSources`, entries, cleanup, policy and lifetime analysis | Synchronous/retained callbacks, batches, exception snapshots and translation. Replace integer next links with managed references; separate JNI global release from managed carrier reachability. Preserve complete invocation-lifetime proof; unknown escape cannot become a leak permission. |
| `BridgeProtectedEntryLowering`, root/callback entry modules, context lowering, GcEffects | Constructors, static/object entries, Strings, copied arrays, callback dispatch, cleanup and initialization. Describe every managed native value across collecting boundaries; unknown foreign execution remains collecting. Recompute final effects after transformations. |
| `ironwood_gc_roots`, runtime owner enumeration, LLVM statepoints and root-section verification | Current and suspended native segments, C/JNI records, exact roots, exceptions and emergency roots. Never unwind HotSpot frames. Validate saved boundaries/machine state and all segments before reclamation. |
| Array/String/ByteView native generators and exception graph/snapshot generators | JNI acquisitions, conversion results, copy-back and Java delivery. Root conversions/pending results/exceptions; preserve original ordering on native/JNI/Java OOM. A native address or ByteView descriptor is not a managed base. |
| Bootstrap/loader/generation/package inputs, NativeBackend and SDK preparation | Required memory/config/layout/root/owner identities, actual payload hashes, scalar-only images, Mach-O/ELF export policy. Refuse stale inputs before user execution and isolate every image's collector and transition chain. |

Shared dispatch, escape, class initialization, generic specialization and pruning
also feed pool origin/reuse, native acquisition and ByteView confinement. Retain
those consumers. Broad dead-proof deletion and repository-wide fixture migration
belong to GC7. Linux arm64, arm64e, async callbacks, unloading and raw cross-image
managed addresses remain outside the qualified closure.

## Paired cases and focused verification selection

* Independent handles/alias/view results versus closed/stale facades, reused
  addresses and unpublished delivery failure; surviving source aliases versus
  dropped graphs and subsequent reclamation.
* Listener replacement/unregistration versus old-token closure; successful and
  throwing committed stores; active callback retirement and exact JNI balance.
* Java/A/Java/A, deeper nesting and Java/A/Java/B/Java/A with outer-local-only,
  saved-register and derived-base roots versus invalid/missing saved boundaries.
  Include release during reentry and a later dropped-root reclamation control.
* Direct/discarded/rethrown callback failures versus retained/returned/unknown
  invocation-bound carriers; exact chain retention versus pointer-shaped numeric
  JNI cookies; release JNI references even while carrier memory stays reachable.
* Strings, arrays/copy-back, borrowed ByteViews, exception translation, finally
  and deferred callbacks with collection and acquisition/delivery failure.
* Two actual JVM-loaded images with private counters/heaps and export allowlists
  versus stale/missing metadata and raw foreign-image addresses. Serialize calls
  from different Java threads without adding runtime thread checks.

Use actual JVM consumers for JNI and reentry qualification. Core root, reentry
and exception cases need O0/O1/O2/O3. Retain selected GC2 exactness/overflow/OOM,
GC3 root corruption, GC4 owners and GC5 exception/defer/preparation controls.
Inspect O3 scalar/critical code and run deterministic bridge controls; measure
transition capture separately. No unfiltered suite is authorized.

Every native/compiler child must use the existing 16 GiB process-group RSS guard
and bounded subprocess runner. Record actual exact test names, commands, SDK
identities, outcomes and remaining gates as each implementation slice becomes
testable. Run diff, license and tracked-file checks before local commits. Raw
logs, generated binaries and dependency archives stay in ignored output paths.
