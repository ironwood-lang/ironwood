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

## Resumed integration and acceptance ledger

Resume baseline: `a91a13cd`. Intermediate commits do not complete GC6. Carrier
unit evidence cannot replace generated-shim/JVM integration. The dependent gates
are foreign root/reentry protocol, Roots/RootHandle anchors and facade epochs,
ListenerRegistration and committed slots, complete String/array/ByteView and
exception crossings, production image/native identity, then target/performance
qualification. At resume, all remained pending apart from the recorded carrier
unit slice. The [final report](GC6_IMPLEMENTATION.md) records their qualification.

The root implementation saves native machine frame state at existing Java
transition boundaries, without reading managed root values there. Collection
uses immutable exact maps with saved PC/SP/FP/stack extents to reconstruct bases.
Only native frames up to an image-owned entry boundary are traversed. No unwind
step enters HotSpot. Each image owns its active/suspended chain. Administration
remains outside ordinary noncollecting calls. Version the new root/owner protocol;
ABI 1/2 retain their gates. Changed consumers include root startup/readers,
compiler effects/final verification, LLVM emission/statepoints, generated entry
and callback contexts, SDK hashing and the qualification driver.

Paired checks: current/suspended roots and dropped roots after resume; unknown
transition contexts; stale PCs/boundaries/machine state; A/A and A/B/A nesting;
preparation OOM before Java execution or collection. Retain ordinary ABI-2 root
and owner controls and final-link refusals. Actual typed compiler shims in
JVM-loaded images at O0-O3 must pass before this gate is accepted.

Generated protected entries must distinguish opaque native unwind handles from
typed managed Throwable objects. This affects root/String/enum/scalar entry
lowering and exception extraction. Preserve typed Throwable results at every
landing pad, caught operation, result slot and snapshot call. Pair real JVM
constructor-throw translation with rejection of opaque managed exception values
before LLVM emission. Keep ordinary final-link rejection controls selected.

Copied array and ByteView confinement consumers need a managed-heap mode. Retain
input escape, reference publication, writable-copy-back and native resource
checks; replace only manual non-reclamation premises with the admitted exact GC
path. Explicit collection has no publication or callback effects. Pair collecting
array/view borrows with retained/returned descriptor, reference-store and unknown
effect rejection. LLVM must carry borrowed descriptors in the native address
space, with Java storage owners retained by JNI locals, never as managed bases.

Generic projections retain the existing finite production domain and final-bound
input rules. Erased managed results choose a facade kind from the final image's
actual type IDs, then receive independent anchors. Pair bounded construction and
unbounded finite result domains with unknown-domain/dependent-input refusal.
Keep typed nulls in the descriptor's native address space, and preserve source
names that coincide with Java support types or similarly prefixed packages.

Snapshot discriminator IDs in common generated Java must be stable across
source/class/archive input order. Keep native heap type IDs unchanged; use
sorted nominal snapshot IDs for the managed bridge transport. Verify identical
generation/support sources after reconstruction and actual JVM exception
translation against the matching C metadata at O0-O3.

Managed callback composition keeps the exact generated proxy inventory and the
explicit context specialization. Callback-capable initialization remains refused
where its cached failure could outlive invocation JNI ownership. Complete carrier
completion is mandatory, including caught, replaced and secondary failures.
Foreign calls are collecting; canonical proxy validation does not make Java a
leaf. Pair synchronous callback success/rethrow with rejected carrier escape and
unknown proxy/context inventory. Native invocation records must root carrier
heads, proxy inputs and converted managed values through translation completion.

Listener registrations own independently rooted holder/proxy records and JNI
references. Reserve token, slot and retirement bookkeeping before mutation.
Reconcile final source slots on success and failure; old-token closure clears a
slot only while that token still owns its current binding. Active callbacks keep
their listener reference until native invocation completion. Callback facade
arguments receive temporary independent handles, explicitly closed after Java
dispatch; Java can retain an independent handle during that callback. Pair this
scope with closure/reentry and stale-facade controls. No collector cleanup hook,
source cleanup, asynchronous use or thread bookkeeping is introduced.

For compiler-generated secondary associations, propagate carrier taint to the
primary exception and prove the primary graph remains invocation-contained.
Reject preexisting/unknown primary graphs and throwable publication or storage
outside fresh built-in exception construction. Pair this with a caught carrier
stored globally and a fresh primary published before a failing `finally` block.
Collection may raise an implicit OOM occurrence; it is not a no-throw operation.
Context specialization changes dispatch, so transformed managed construction
facts carry no inherited ownership/borrowing assertions. Original native/resource
proofs stay mandatory before composition; final managed IR still rejects legacy
reclamation. Verify the final fact/program binding and unchanged legacy consumers.

Actual generated callbacks exposed coalesced Mach-O compact-unwind regions for
native JNI helpers. Their region start need not equal a helper's function address.
Use the validated unwind range to recognize that transition, while capturing
every exact managed caller regardless of when transition recognition succeeds.
Keep strict exact return-PC/function map matching and the unmapped-LLVM refusal.
Supply the entry boundary's machine stack value directly from the compiler-owned
dispatcher, avoiding both a preliminary unwind and reliance on compact-unwind
function identity. Validate that boundary against the later walk before Java or
collection. Pair coalesced helper coverage with stale boundary/hash/missing-map
controls, O0-O3 JVM nesting and ordinary ABI-2 controls. Rebuild the SDK after the
root header/reader change; measure boundary entry and transition capture separately.

Registration retirement uses an image-local active callback-invocation count.
When a binding loses its final use during reentry, its preallocated listener
record retains the JNI global/proxy root until the outermost active callback
invocation completes. This also protects an outer source local that loaded a
different listener before Java unregisters both slots. No source-load tracking,
per-scalar administration, TLS, synchronization or registry scan is introduced.
The count changes only at the existing callback-context boundary. Token closure
uses noncollecting compiler-owned conditional field clears, not source methods.
An in-flight operation retains its token until final-slot reconciliation, then
honors closure before delivery. A retaining projection adopts matching final
input bindings, including a repeated installation of the same listener; a new
token supersedes the previous token's ownership of that matching final slot.
Pair old-token closure, same-listener replacement, skipped/throwing writes,
constructor failure publication, source unregistration and active retirement.

Borrowed views and copied arrays may cross only validated canonical proxy bodies
whose Java signatures cannot receive those native descriptors or converted
arrays. Keep source borrowing, view confinement and listener-slot attribution;
permit those exact foreign instructions in the borrow effect walk without
changing their collecting/reentrant classification. Consumers are managed API
selection, entry composition and native array copy-back. Pair collection and
reentry while Java storage stays locally owned with retained view/array and
unknown foreign-edge refusals. The original legacy analysis stays unchanged.

Automatic batching retains the existing counted-loop scheduling proof after
mandatory managed listener and carrier admission. Only the proved long/void
proxy, loop and protected entry are cloned; their roots and collecting foreign
instructions remain in the final verified image. Native frame fields retain the
fixed data offset. Never copy a linked exact-root record into a batch frame.
Reuse private Java direct scratch by nested invocation depth, retaining the
ordinary JNI fallback when optional scratch allocation fails. Administration
belongs only to a selected callback batch invocation. Pair nested collection,
registration closure and callback failures with rejected impure loop schedules;
inspect O3 append/dispatch code and run the existing deterministic batch control.

Linux JVM qualification distinguished GCC's current-frame SP from a source
`__builtin_dwarf_cfa()` value. Use an explicit dispatcher frame pointer on x86-64
and validate it against the unwinder's recovered RBP at the exact native function.
Save that walk's actual dispatcher SP separately as the last managed frame's
upper bound. This handles outgoing stack arguments without stepping into Java.
Hash and validate both boundary values; retain the arm64 SP convention. Pair
O0-O3 Linux JVM nesting, stack-passed arguments and boundary corruption controls.
Rebuild and reverify both affected SDK identities after the root contract change.

Qualification-only fault shims inject native record and JNI allocation failure
at acquisition/delivery boundaries, with no production counters or polling.
Pair pre-publication failure with committed constructor/listener stores, failed
Java handle delivery, independent surviving aliases and exact JNI balances.
JNI null allocation results must raise the reserved Java OOM when the JVM did
not supply a pending exception. Preserve the source failure and array copy-back
order when both conversion and source completion fail. Cleanup never invokes
collection, source closure, Java callbacks or managed destruction.

Packaging adopts schema 2 and objects-gc-v1 for every managed bridge, including
scalar-only APIs. Common Java generation binds memory/layout/root/owner/segment
versions. Each host's generation manifest also requires gc.config.sha256, bound
to its target-specific native build identity and actual SDK/archive/adapter/root
reader payload hashes. Assembly preserves those fields per target; it must not
invent a target-independent collector configuration digest. Restore, assembly,
loader preflight and native bootstrap reject incomplete or incompatible pairings
before user execution. Preserve identical common facade/support sources across
source/class/archive reconstruction and target assembly. Pair scalar-only valid
manifests with missing/stale versions, configuration, payload and schema controls.

Linux bridge linking must use the existing pinned private GCC support lookup
policy. Remove executable-only PIE and producer LLVM library/RPATH flags from
shared images; preserve them for ordinary executables. Actual ELF dependency and
RPATH audits must pass before the JVM consumer runs. Collector symbols remain
local under the version map and cannot import a process collector. Pair a valid
relocated library with the existing rejected producer-path and export-leak cases.

Recheck the image's facade cache immediately before publishing a newly created
facade. A JNI acquisition can reenter Java after initial lookup; reuse an owner
published by that nested operation and release only the outer unpublished JNI
candidate. Keep the candidate's exact base root until that decision. No Java
execution occurs between final lookup and cache insertion. This administration
is confined to result export; scalar/native source bodies remain unchanged.

Final qualification also covers facade publication reentry at a JNI global
acquisition, and a copy-back JNI failure after a source exception. Fault shims
must target the user copy-back rather than snapshot translation arrays, preserve
the original throwable/diagnostic and leave production adapters uninstrumented.
ELF synthesizes start/stop trace symbols even with a local version script; request
hidden start/stop visibility and audit the final dynamic export table. Enable
only the public managed admission/linker path after both target matrices pass.
Preserve raw shared IR refusal, reconstruction parity, per-target assembly hashes
and the established packaging source/notices. No broad consumer migration is
included. The final-slot listener convention gives newer nested matching
operations precedence without injecting source-store bookkeeping.

Cross-target assembly must preserve each SDK's distinct configuration/build
inventory without treating those files as common Java/source content. Store the
SDK documents under their configuration digest, merge them with byte equality
on duplicate paths, and keep the common unmodified BDWGC archive/notices shared.
Pair actual macOS/Linux scalar host artifacts with a two-target assembly and
real JVM load, plus missing/stale per-target configuration refusals. This changes
packaging and common-content selection only, not runtime/root protocols.
