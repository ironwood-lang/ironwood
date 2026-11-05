<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC0 baseline, consumers and migration contracts

Date: 2026-11-04. **GC0 complete; production GC is not implemented.**
Baseline: `bb0fbab690871cbdba00b0770532653ebdba713d`, including the planning
commit and evaluation. Work stayed on the existing local migration branch.
Current manual reclamation, diagnostics, destructor effects and native safety
proofs remain authoritative. This document fixes the future contract; it does
not relax a proof, implement GC1, or restart self-hosting.

Read with the [implementation plan](BDWGC_INTEGRATION_PLAN.md),
[engine evaluation](GC_EVALUATION.md), and
[compact evidence manifest](gc-evaluation/gc0-2026-11-04-manifest.json).
Paths below are relative to the checkout. Compiler Java paths are under
`compiler/src/main/java/ironwood/compiler/`, abbreviated **C/**; library paths
are under `stdlib/src/main/ironwood/ironwood/`, abbreviated **S/**.
Class/method names identify baseline source anchors, not promised future APIs.

## Acceptance and pre-change review

GC0's exit criteria are fulfilled:

- [x] Source and generated reclamation, reference owners, allocator domains,
  construction failures and resource cleanup have dispositions below. The
  manifest lists all 77 main-library destructor sites individually.
- [x] Shared analysis producers are mapped to consumers, with heap proof work
  separated from optimization, native lifetime and protocol requirements.
- [x] Bridge lifetime surface, reentry segments, image isolation, native
  compatibility identity and diagnostic accounting are decided concretely.
- [x] Exact focused tests, isolated accounting/policy/layout experiments and
  optimized-code/performance baselines have passed and are recorded.
- [x] The future supersession map preserves current behavior and the D051,
  D132/D133 and D188 obligations. Implementation gates remain explicit.

The [pre-change review](POOL_RELEASE_HELPER_REGRESSION.md#before-a-substantial-change)
was applied before the experiment. This milestone changes documentation and
isolated fixtures only. Preserve current mandatory reclamation proofs in every
unfreed mode, pool origin identity, native view confinement, constructor and
exception order, general defer captures and cleanup order. No compiler analysis,
runtime allocator, library body or generated bridge implementation is changed.
Safe/unsafe pairs are the local free and pool tests in the ledger; defer,
artifact, exception, JNI and allocation measurements cover their consumers.
Unknown effects remain unknown. Future work must revisit the map before deleting
analysis rather than treating a passing GC0 baseline as permission to delete it.

## Frozen future contracts

### Collection and accounting

Use pinned BDWGC **8.2.12**, archive SHA-256
`42e5194ad06ab6ffb806c83eb99c03462b495d979cda782f3c72c08af833cd4e`.
The exact evaluated configure/CFLAGS/startup tuple is in the linked manifests.
Use exact heap layouts and compiler/runtime roots, full nonmoving collection,
eager sweeping and one application thread. No finalizers, workers, write
barriers, automatic recovery collections, ambient stack/register/static/library
word scanning or incremental work are admitted.

`ironwood.lang.Memory.collect()` is public static void. Root/scratch preparation
occurs with collection disabled. Only its balanced internal window enables a
full collection; marking and sweeping finish before return, then collection is
disabled again. No user code, managed allocation or callback executes in that
window. Allocation failure never enables GC, including collector exhaustion
retries. Preserve catchable immortal OOM and bounded recursive-failure behavior.
Bad or incomplete metadata fails before reclaiming anything. Collection setup
OOM uses the established failure path without a partial collection.

**Keep both System counters.** `allocationCount()` counts successful
non-immortal language object, array and inline String allocations at the existing
allocation boundary, including storage allocated before a later operation or
constructor fails. It excludes native scratch, collector administration and
runtime-only sidecars. It is an event count, not requested bytes or source `new`
expressions. A library algorithm that creates more language objects must count
them; changing an algorithm is not a license to hide allocations. Keep the
existing 64-bit counter representation/wrap convention.

After collection, `liveAllocationCount()` reports
`L + (A - A_at_collection)`, where `L` is the enumerated number of unreclaimed
language allocations and `A` is the existing total counter. Before the first
collection, `L = A_at_collection = 0`. Reading it never collects or enumerates.
Allocations abandoned since collection, including failed-construction objects,
remain included until a collection reclaims them. Thus this is allocated,
not-yet-reclaimed storage, not a live reachability census. Keep per-image scope
for independent native images. No live counter decrement, allocation ledger,
finalizer, resource registry or per-write accounting is needed.

Feasibility is established for the selected profile:

- Pinned `include/gc_mark.h` exposes
  `GC_enumerate_reachable_objects_inner` under the allocation lock and
  `GC_get_kind_and_size`. `reclaim.c:818-854` visits marked object bases, including
  large objects, rather than dividing occupied block bytes by an object size.
- `alloc.c`'s `GC_finish_collection` clears free-list mark bits before
  `GC_start_reclaim(FALSE)`; `reclaim.c`'s eager path completes reclamation.
  Enumerate after the full eager collection returns and before allowing further
  managed allocation. Arbitrary enumeration between collections is unsuitable:
  new allocations and stale mark bits do not constitute an allocated-object map.
- The original [accounting probe](../scripts/gc-evaluation/accounting_probe.c)
  uses designated language, pointer-bearing sidecar and pointer-free trace
  kinds. It counts 4,009 language objects, 12 sidecars and three collector
  administration blocks after its first collection. Raw enumeration would
  overcount. The three other blocks agree with the collector's `misc.c`
  `GC_new_free_list_inner`, which allocates one PTRFREE array per new kind.
- Mixed 64/96-byte cells and 128 KiB objects, dead neighbors, a language object
  reachable only through a sidecar, and three subsequent hole-reuse rounds
  produce language counts `4009, 4, 0, 17, 0, 17, 0, 17, 0`. These match explicit
  graph expectations, with no free-list cells counted. After 123 subsequent
  allocations the formula reports 4,132 with no additional collection.

GC2 must qualify every production allocation kind and failure path against this
scheme, including arrays, Strings, large maps and metadata exhaustion. Keep a
fixed bounded set of language kinds distinct from internal sidecar kinds; do not
allocate a kind per class or infer language objects from arbitrary header words.
All genuinely internal memory must be excluded even if BDWGC enumerates it.
This is a concrete accounting choice, with implementation qualification remaining,
not a claim that the current runtime already performs enumeration.

### Generated Java lifetime API

The new generation uses a public final `RootHandle<T> implements AutoCloseable`
in `BridgeGeneration.supportPackage()`, currently the reserved
`ironwood.bridge.generated.g<generation>` namespace. Its public operations are
`T value()` and `void close()` with no checked exception. Construction is private
to generated support. There is no generated facade reclamation `free()` and no
generated reclamation `close()` on a source facade.

Fix constructor projection as well as the handle's name: generated source
facades have no public owning Java constructors. The support class `Roots`
exports `construct_<tag>(source constructor arguments)` returning
`RootHandle<Facade>`. The tag is the lowercase hexadecimal UTF-8 encoding of the
fully qualified binary source type name. Overloads preserve admitted constructor
parameter signatures; constructors retaining listeners use the registration
factory below instead. `Roots.retain(Facade)` overloads create an independent
anchor for each admitted facade type. The support namespace is reserved and
producer-validated; these operations cannot collide with source methods named
`close`, `free`, `value`, `retain` or `construct`. Existing generic/erasure and
JVM class/member-size checks still apply; unsupported signatures fail production.

Every non-immortal managed reference result escaping to Java, including alias
and dependent-view results, is projected as a `RootHandle<Facade>` with its own
anchor before delivery. Primitive/String copies, enum/permanent projections and
exception snapshots keep their value projection. Inputs remain facades obtained
from an open handle; retain their native referents through the invocation.
Source instance/static method names and source-defined `close()` behavior remain
source operations. A source method actually named `free()` after the language
cutover is an ordinary source method, not a bridge lifetime hook.

Handle closure is idempotent, releases only that foreign strong anchor and
invalidates that handle's `value()`. It does not collect, destroy managed
children, close a resource or invoke source/Java callbacks. Another independent
anchor, native root or suspended invocation can keep the object alive. Cached
facades share the existing image-local identity/liveness state; another open
anchor keeps that state usable. After the last foreign anchor is closed, stale
facade native access fails before dereference. Identity operations on an existing
closed facade remain safe; identity continuity across closing every anchor and
later re-export is not promised. Preserve identity during an open lifetime and
prevent an address reused for another allocation from reviving a stale facade.

Reserve native record/anchor storage before creating/publishing a result; commit
it before Java delivery. Delivery failure releases only unpublished anchors and
JNI acquisitions, even with a pending Java exception. No partial rollback may
destroy a managed graph already published by a constructor. Forgotten handles
retain their anchors; there is no Cleaner, weak-reference fallback or finalizer.
Handle counts and identity lookups belong to export/retain/release boundaries,
never ordinary scalar calls. Java-side liveness checks already accepted by
D190 remain; GC adds no scalar root-index lookup, TLS access or synchronization.

### Foreign resources, callbacks and suspended segments

The native image owns its heap; HotSpot cannot supply its reachability. A foreign
anchor contains an exact managed base, image identity and lifetime state in
native-owned storage. Enumerate active anchors only during collection. Do not
root the entire Java facade cache or retain closed anchor records indefinitely.
Native references to resources and native-only data remain separate domains.

Retained Java listeners need an explicit generated `ListenerRegistration
implements AutoCloseable` in the same support namespace, with idempotent
`void close()` and no checked exception. **Acquire it before the retaining call**
with `Listeners.open()`. Project retaining calls through
`Listeners.install_<tag>(registration, source arguments)` and retaining
constructors through `Listeners.construct_<tag>(registration, source arguments)`.
The tag is hexadecimal UTF-8 of compact sorted-key JSON with `owner`, `kind`,
`name`, `parameters` (ordered type identities) and `result` fields. Preserve the
ordinary projected result, including void or a constructor's RootHandle; do not
discard a source result to return a cleanup token. Omit an ordinary projection
that can install retained listeners without this preexisting lifetime owner.

The token roots its holders/proxies and owns all JNI registrations committed by
those invocations. Preallocate required bookkeeping before source mutation;
reconcile the actual final slots on both successful and exceptional exits before
delivering results or throwing into Java. Thus a source call that stores a
listener and then throws still has an explicit cleanup owner. Release unpublished
acquisitions on failure without undoing committed source stores or replacing the
original exception. Replacement, explicit unregistration and token closure
release the appropriate registration exactly once. Close detaches only matching
current slots; an old token cannot unregister their replacements. Retired active
callbacks keep their JNI reference until the existing callback retirement point.
Source `close()` may unregister listeners only when its body explicitly does so.
Closing an unrelated heap anchor cannot do that work. Unsupported retaining slot
shapes are rejected rather than leaked silently or handled by finalization.

This replaces cleanup authority currently tied to root destruction/listener
retirement, not a supposition that every listener has a source destructor.
`BridgeListenerNativeSources.iw_listener_release` pairs proxy destruction with
`DeleteGlobalRef`; `BridgeCallbackCarrierNativeSources.iw_callback_release_slow`
pairs carrier destruction with `DeleteGlobalRef`. Both are migration sites even
though generated .iron declarations alone do not expose these JNI effects.

All supported crossings have an owner and disposition:

| Boundary or retained value | Future root/lifetime contract | Baseline anchors in C/ |
| --- | --- | --- |
| Java to native scalar/static/constructor entry | Typed entry establishes a current managed segment. GC-capable entries have final effects/root maps. Noncollecting scalar entries add no root administration. | `bridge/BridgeProtectedEntryLowering`, `BridgeRootEntryLowering`, `BridgeCriticalSources` |
| Object export, alias/view conversion and native records | A strong anchor per open handle; replace managed incoming-owner counts and acyclicity restrictions with reachability. Keep world, liveness and native-resource validity checks. | `bridge/BridgeRootIndexSources`, `BridgeRootStateSources`, `BridgeIdentityCacheSources`, `BridgeRootFacadeNativeSources` |
| C-held inputs, converted arrays/Strings, final-slot records, pending result/exception | Explicit invocation-local exact root slots for values live across a collecting shim or Java execution. No arbitrary C-register scan. Preserve copy-back and exceptional delivery order. | `runtime/include/ironwood_bridge.h`, `bridge/BridgeArrayInputSources`, `BridgeStringInputSources`, `BridgeArrayValueSources`, `BridgeExceptionNativeSources` |
| Synchronous Java callbacks and automatic batches | Existing callback context owns a transition record, saved managed segment boundary and carrier roots. Batch storage obeys the same lifetime, not a separate hidden root system. | `semantic/BridgeCallbackContextLowering`, `BridgeCallbackReachability`; `bridge/BridgeCallbackNativeSources`, `BridgeCallbackBatchSources` |
| Native to Java to native reentry | Suspend the outer managed segment in the transition context before executing Java; nested native entry starts another segment. A collection visits current and every suspended segment in the image. | `bridge/BridgeSynchronousCallbackNativeSources`, `BridgeOwnedCallbackNativeSources`, `BridgeCallbackCarrierNativeSources` |
| Retained listeners and holder slots | Explicit registration tokens and active/retired invocation ownership as above. JNI references are not BDWGC roots; native proxies/holders are. | `bridge/BridgeListenerNativeSources`, `BridgeListenerSlotEntries`; `semantic/BridgeOwnedListenerSlots` |
| Java throwable carriers | Invocation context roots the managed carrier chain. Replace pointer-bearing `nextCarrier` long with described managed linkage. Delete JNI globals at translation completion even if the managed carrier is still reachable; never during GC. Escaping an invocation-bound carrier remains rejected unless copied to an independent safe snapshot. | `bridge/BridgeCallbackCarrierSources`, `BridgeCallbackCarrierCleanup`, `BridgeCallbackCarrierPolicy` |
| Borrowed Java-owned ByteView/direct bytes | Retain the Java storage owner/local JNI reference for the complete synchronous borrow, including supported reentry. Preserve bounds/read-only and nonretention proofs. A managed reference does not extend freed foreign storage. No new close operation on existing JVM-owned ByteView. | `bridge/BridgeByteViewSources`, `BridgeByteViewInputSources`; `semantic/BridgeByteViews`; `backend/LlvmByteViewEmitter` |
| Bootstrap, cached jclasses/loader and error classes | Matched bootstrap acquisition/rollback; committed image metadata lasts with the image. Preserve existing load/unload boundary. Metadata tables contain native/JNI pointers, not ordinary managed heap objects unless explicitly described. | `bridge/BridgeBootstrapSources`, `BridgeLoaderSources`, `BridgeRootFacadeNativeSources`, `BridgePermanentNativeSources`, `BridgeEnumNativeSources` |

**No native unwinding through HotSpot JIT frames is assumed.** The saved outer
segment supplies its native return PC/register/base information and a validated
walk boundary. Its roots are reconstructed at collection from stack maps and
unwind data up to that boundary. Transition capture is allowed only where Java
can reenter a collection-capable image, reusing callback/transition context;
ordinary native calls remain untouched. Treat unknown Java execution, including
callbacks and class initialization during JNI helper execution, as potentially
reentrant. A helper proved not to execute reentrant Java needs no such capture.
If a platform cannot capture and revisit every segment, reject that collecting
crossing before execution. Do not walk past an unwalkable foreign frame or
silently omit outer roots.

GC6 must test Java to A to Java to A, deeper reentry and Java to A to Java to B
to Java to A with outer-local-only managed roots; collection in exception
translation, finally and deferred callbacks; release of an anchor during reentry;
and subsequent reclamation controls. Qualify saved-register/derived-base cases,
copy-back/failure order and JNI global/local reference balances. Measure boundary
capture separately from scalar calls. No asynchronous callback/thread support is
added. D188's single caller or externally serialized caller obligation persists,
including sequential use from different Java threads. No runtime thread checks.

### Native image and artifact identity

Reserve native managed-memory ABI **`ironwood-native-gc-1`**, layout metadata
version **1**, root-map adapter version **1**, and bridge generation schema
**2** with projection **`objects-gc-v1`**. These identifiers describe the future
contract only; do not change current constants until their implementation.
GC4 subsequently versions the experimental owner/root contract as root ABI 2
and runtime-owner ABI 1, superseding the root-version reservation for images
with these owners. See [GC4](GC4_RUNTIME_LIFETIMES.md). Current production native
and bridge generation constants remain unchanged; future manifests must bind
the implemented root protocol rather than accept the old reservation as coverage.
Future generation manifests must include `memory.abi`, `gc.config.sha256`,
`layout.version`, `roots.version` and the new lifetime projection. Validate them
as required fields, not ignored extension properties. Bump other projection
identities that embed affected native layouts, even if their public API is scalar.

`gc.config.sha256` is SHA-256 of a canonical UTF-8 JSON object (sorted keys,
compact separators, no insignificant whitespace) containing the archive hash,
exact configure/CFLAGS tuple, adapter ABI, layout/root-map versions, target
triple, data layout/pointer width/endianness, LLVM stack-map format/toolchain
version, collector symbol isolation policy and static-link policy. SDK/native
build manifests also hash the actual collector archive, adapter, headers and
linked inputs. API identity alone never proves native compatibility.

All compilation units in one final native image must agree exactly on these
fields. Source/class/archive inputs are reanalyzed into that image; incompatible
native archives, missing configuration metadata, mismatched descriptors/root
sections, bridge facades or bootstrap generation refuse build/load before user
code executes. This applies to scalar-only bridges too, because an initializer
or failure path may allocate. A compatible C calling convention is insufficient.

Each independently loaded image statically links a private collector instance.
Hide or prefix **all** collector/adapter globals, symbols and public entry
points; use an explicit export allowlist on Mach-O and local/hidden binding plus
an export/version map on ELF. Do not rely on load order, `RTLD_LOCAL` alone or
the absence of an installed process collector. GC1 inspects archive and final
symbol tables; GC6 qualifies two independent images with distinct heaps,
anchors and collection counts. No image roots, scans, collects, frees or accepts
a raw managed address belonging to another. Cross-image exchange uses copied
values or that image's public bridge transport, never a shared opaque pointer.
Reentry back into an image visits that image's transition chain only. Preserve
unsupported unloading/hot reload; no process-wide collector singleton.

`.ironclass` remains format-1 source ZIP (`C/IronClass.FORMAT`); `.ironjar`
continues to embed those artifacts. They are not a stable typed-IR/native ABI.
Reparse and revalidate all embedded source and recompute effects after the
cutover. There is no new container epoch in GC0. Old `free`, `defer free`,
destructors and removed options must receive specific migration diagnostics;
never execute old typed cleanup or accept obsolete syntax as a no-op. Rebuild
stdlib archives and bundled bridge sources with the cutover.

Remove `--unfreed=off|warn|error`, `--explain-rejected-free` and
`@SuppressUnfreed` with GC5, with explicit obsolete-setting/directive errors.
Do not interpret an old free statement as an ordinary invocation. Ordinary
methods named `free` and `destructor`, including a void `free(...)` call captured
by general defer, become legal after keyword removal. Keep migration errors
distinct from normal overload/type errors and report the embedded source path
when obsolete syntax comes from a class/archive.

## Consumer and migration inventory

Disposition codes: **O** obsolete heap-reclamation work; **P** retained for
optimization; **R** retained for resource/protocol safety; **G** replaced by
collection effects or exact root/layout metadata. A mixed row explicitly splits
its outputs. Nothing below authorizes removing code during GC0.

### Source, generated cleanup and allocator domains

| Consumer and current source evidence | Disposition and migration owner |
| --- | --- |
| `C/lexer/TokenKind`, `lexer/Lexer`, `parser/Parser`, `ast/FreeStatement`, `DeferredFreeStatement`, `DestructorDeclaration`; matching parser/visitor forms in the Ironwood compiler sources | **O**, GC5/GC7: remove reclamation grammar and semantic dead-binding rules. Keep ordinary `defer` and useful obsolete-form diagnostics. Release identifiers `free` and `destructor`. The stopped compiler port is a repository consumer, not authority to restart self-hosting. |
| `C/semantic/FunctionAnalyzer` free emission, deferred-free cleanup, expression-end temporary cleanup and allocation-failure `IrRollbackInstruction`; `SemanticAnalyzer` destructor chains and generated constructor rollback | **O** for managed destruction; **R** explicit acquired-resource unwind; **G** partial-construction reference maps and pending cleanup/return/throw roots, GC4/GC5. Published partial objects remain valid; zero complete dynamic layout/header before constructors can collect. |
| `IrFreeInstruction`, `IrRawDeallocateInstruction`, `IrRollbackInstruction`, `IrDestroyArrayElementsInstruction`; `IrReleaseOwnedToStringResultInstruction`, `IrReleaseOwnedThrowableMessageInstruction` | **O** managed deallocation IR and owned-text release. **R** true native cleanup gets distinct typed operations; do not retain raw managed free as a hidden cutover escape hatch. `IrClass` destructor/rollback/text-ownership flags and matching array flags retire with their consumers. |
| `C/backend/LlvmEmitter` `ironwood.destroy`, `destroy_array_elements`, `rollback`, descriptor fields, release helper lowering; `C/ClosedWorldPruner`, `C/InitializedTypeSpecializer`, `C/semantic/PrimitiveGenericSpecializer`, `BridgeCallbackContextLowering` copies of these operations/metadata | **O** destruction entries and ownership flags; **P** pruning/specialization/initialization optimization stays; **G** preserve/recompute MayCollect and exact layouts after every clone/prune/devirtualization, GC2/GC3/GC5. |
| `runtime/src/ironwood_runtime.c` `try_allocate_object`, `try_allocate_array`, String factories, process arguments and bridge array/String copies | **G** collector language allocation kinds, exact zeroing/type header and original total-count boundary. Preserve array checks, identity, overflow handling and OOM; no automatic collection. |
| Same file `ironwood_deallocate`, `ironwood_release_owned_to_string_result`, `ironwood_release_owned_throwable_message`, `read_file_result` failure frees | **O** ordinary managed frees. All internal callers must migrate, not just source statements. **R** file-descriptor cleanup on every failure remains. |
| Same file `reserve_file_result` and final shrink use libc `realloc` on unpublished language results | **G/R**, GC2/GC4: cannot feed GC storage to libc realloc. Use matched native construction scratch and a final described language result, or a separately qualified adapter strategy with correct counting and no published-address invalidation. Every successful language allocation still counts. Do not mix domains to preserve an old allocation assertion. |
| Same file UTF-8/path conversion, directory sorting/listing scratch, trace capture scratch; `ironwood_case.c` borrowed UTF-16 operations | **R** bounded native scratch with matched malloc/realloc/free and failure cleanup; case conversion retains no managed input. A native call proven noncollecting needs no managed-root registration. **G** any managed inputs live across newly collecting calls. |
| `runtime/src/ironwood_tcp.c`, `ironwood_host.c`, `ironwood_tls.c` | **R** socket descriptors, getaddrinfo/freeaddrinfo, getifaddrs/freeifaddrs, host-capture blocks, SSL/BIO/CTX/provider state and matched releases. TLS state stores native OpenSSL pointers and a borrowed integer TCP descriptor, not a managed transport pointer. Host snapshots copy records before native capture release. No GC conversion of OS/library resources. |
| Directory handles in `ironwood_runtime.c` (`ironwood_directory_handle`, open/close directory), file descriptors, transient temporary paths | **R** explicit close and error cleanup. Raw handle integers are resource identities, not concealed managed roots. `S/nio/file/UnixDirectoryStream.close()` owns directory closure; its destructor frees only its managed path. |
| Literal Strings, enum/permanent objects, descriptors, trace metadata, immortal allocation-failure Throwable and static fields (`IrImmortalObject`, `IrProgram`, LLVM globals) | **G** exact reference slots for mutable immortal/static graphs and initializer failure references; exclude immortal storage from both counters. Native code-address/trace tables are not managed references. Preserve initializer failure/ordering semantics and D133 fast barrier. |
| Generated JNI native source in `C/bridge/Bridge*Sources`, typed shims in `Bridge*EntryLowering`/`BridgeEntryModule` | **O** copied-result/raw/proxy/carrier managed destruction; **R** matched JNI array/String acquisition/release, global references, native identity index/records and delivery rollback; **G** roots across collecting shims, facade anchors and segment boundaries. Full crossing table above covers every supported entry/exit family. |
| `C/SourceSetLoader`, `IronClass`, `IronJar`, `NativeLinkPipeline`; `backend/RuntimeLibrary`, `NativeBackend`, `LlvmToolchain`; bridge generation/package/paired archive producers; `scripts/package.sh`, `package-idk.sh` | **P** preserve source reconstruction, final-link pruning/specialization and the existing llvm-as/opt/llc plus isolated Clang runtime pipeline. **G** layout/root/config/generation validation and collector linkage. **R** source/licenses/notices in SDK, IDK, shared-image support and bridge jars. Runtime discovery currently locates source; NativeBackend compiles runtime components and injects late optimized trace metadata. Package scripts currently copy runtime/stdlib sources and the explicit license set. Extend those actual manifests/copy paths, not an unrelated packaging pipeline. |
| Pools and reference collections in `S/ds`, `S/pool` | **O** pool-owned graph destruction and external-item free restrictions whose sole effect is heap reclamation. **R** same-pool return, reuse, iterator and callback protocol; **G** physical slots remain exact references. Scrub obsolete ArrayList slots and retired map-entry keys/values/links. `ArrayList.clear()` currently resets only size; HashMap/IdentityHashMap/LinkedHashMap and copied-key maps release entries to pools. Do not infer untraced slots from logical size. |
| `S/ds` primitive containers and `S/lang/StringBuilder`, `StringBuffer`, `S/nio/ByteBuffer` | **O** backing array destruction; **G** owner/view reference layouts. Keep primitive clear cheap. `ByteBuffer.ownedStorage` is a managed byte array, unlike foreign ByteView bytes. Fast retaining resets and cold reference scrubbing need separately named, measured helpers for real consumers at GC5. |
| Examples, projects, tests, scripts, IDE fixtures and documentation programs | **O** removed syntax/destroy counters; **R** explicit close and pool release; **G** collect only at deliberately chosen phases and revise heap assertions. `projects/wget` Sink/Transport destructors free managed wrappers, while explicit `close` handles files/TLS/sockets. IDE lexer/grammar, completion, document symbols, templates and compiler-output parsers must migrate at GC7. |

The scan covered every tracked `.iron` path, Java cleanup IR/generators and all
runtime C allocation sites, not just public syntax. Lexical free/destructor counts
by repository area are in the manifest as search aids, not parser coverage
claims. Historical self-hosting evidence and scripts stay historical and stopped;
update executable repository consumers when selected, never restore the discarded
migration. Java bootstrap compiler allocations remain JVM-managed and are outside
this runtime migration.

### Destructor and constructor/resource audit

All **77** destructors in the **254** main-library `.iron` files were inspected.
The manifest records path, line, body hash and disposition for each:

- 76 contain only managed child/buffer/iterator/pool graph reclamation, including
  the nested Throwable rendering visitor. Remove that destruction; tracing keeps
  the correct graph. Counted-loop child destruction in pools, network snapshots
  and NetworkInterface belongs to this group too.
  Pool item destruction can dispatch an application-defined destructor; migrate
  those user effects explicitly too. A GC-era pool neither closes nor destroys
  its reusable items automatically.
- `S/lang/Throwable.iron`'s outer destructor additionally calls `releaseTrace`.
  Replace that native-memory lifetime with described GC-owned sidecars and
  pointer-free trace storage. It is not an OS-resource finalizer to preserve.
- No main-library destructor closes a file, socket, directory or TLS handle.
  In particular, Socket, ServerSocket, SocketDescriptor, NativeSocketImpl,
  TlsClient, UnixDirectoryStream and I/O wrapper destructors reclaim wrappers or
  buffers; their separate `close()` methods release resources. Do not invent
  implicit closure when removing those bodies.

Library acquisition/rollback still needs review at the actual acquisition, not
only destructor lists:

| Acquisition family | Existing cleanup and required future disposition |
| --- | --- |
| FileInputStream/FileOutputStream/RandomAccessFile and StreamSupport | Explicit descriptor close and native failed-open cleanup remain. Never use heap liveness as proof a descriptor remains open. |
| BufferedReader/Writer, InputStreamReader, OutputStreamWriter, PrintWriter | Path constructors create owned wrappers; destructors only free them. Explicit close propagates to owned/borrowed underlying streams according to existing source behavior. Buffer allocation occurs before owned stream acquisition in the relevant constructors. Audit every new post-acquisition throw during migration; add explicit failure close if acquisition is no longer last. |
| Socket/ServerSocket/SocketDescriptor/NativeSocketImpl/ProxySocketImpl | Preserve existing explicit close, connect/accept failure descriptor cleanup, proxy negotiation failure and stream-owner relations. Constructor managed-field rollback is removed; native handle rollback is retained. |
| TlsClient and `ironwood_tls.c` | Keep `abortHandshake`, connect/handshake failure finally paths, graceful close, OpenSSL release and borrowed TCP descriptor convention. The C state contains no managed reference to discover. Collection must never call SSL_shutdown or user code. |
| Files/UnixDirectoryStream and host/resolver snapshots | Native DIR/getaddrinfo/getifaddrs ownership uses explicit close/finally and native release. Path/String graph reclamation is separate. Native call failure closes partial acquisitions without needing a managed allocation to clean up. |
| Bridge root construction and unpublished delivery | Current rollback destroys owned fields and raw storage. GC keeps zeroed partial graphs, even if published before failure; explicitly undo only unpublished JNI/resource acquisitions. A source constructor publishing a native-resource capability still needs its actual protocol proof. |
| Custom application/test destructors | Managed frees disappear; observable destroy counters/order are removed-syntax tests. Move any native release, pool return or intentional protocol action into an explicit method plus defer/finally. Reject unmigrated syntax; never silently discard an effectful destructor body. |

Construction safety is not satisfied by deleting all current publication checks.
GC replaces heap-dangling concerns; final-field/type/initialization validity,
foreign resource confinement, checked exceptions and partial native acquisition
remain requirements. Future regression pairs must accept a published, partially
initialized managed object after a constructor throws and reject a foreign view
or JNI capability escaping its supported lifetime.

### Runtime reference owners and exact layout obligations

`runtime/src/ironwood_runtime.c` has separate owners that cannot be inferred
from ordinary field bitmaps:

| Owner | Disposition |
| --- | --- |
| `ironwood_throwable.trace_state` stores a metadata address as uint64; metadata has `primary`, first/last secondary links, secondary entries' `object`/`next`, trace buffer | **G** replace with described internal reference plus primitive state. Trace PCs/site pointers are native metadata; trace arrays contain no managed references. No permanently strong Throwable side table. |
| `ironwood_exception.object` in native `_Unwind_Exception` wrapper | **G/R** exact root while in flight; remove at wrapper cleanup on all catch/rethrow/delete exits. Keep wrapper native allocation/unwind ABI. Collection during finally/defer must retain its Throwable even when no source local does. |
| `active_implicit_failure`, `implicit_failure_metadata`, `emergency_association_metadata`, `emergency_secondary_owner`, `emergency_secondary`, emergency wrapper | **G/R** enumerate active emergency managed references, with bounded lifetime and clearing on completion. Do not root stale emergency links forever. Preserve existing bounded emergency TLS use on exception paths; introduce none on ordinary calls. |
| C local object/result/array/String pointers and bridge result/slot/array-input records | **G** exact roots at collecting boundaries; transient native frames are not ordinary source heap objects. Byte counts, handles and pointer-shaped numeric payload remain untraced. |
| Managed statics, enum/permanent objects, captured enclosing/local fields, inherited/generic fields, arrays and String tails | **G** compiler physical layout maps. Arrays scan physically stored reference elements, primitive arrays and inline UTF-16 do not. Dynamic descriptor is installed before superclass/initializer code can collect. Pending defer captures, return/yield and thrown values require stack roots even after local reassignment. |
| JNI global/local references, loader/class caches, native resource handles | **R** JVM/native-owned lifetimes, not managed heap edges. Add **G** roots only for the associated managed proxies/holders or C-held managed values. |

### Shared analysis: outputs to consumers

At baseline, `SemanticAnalyzer.analyze` constructs provisional escape and owned
field analyses, lowers bound functions, computes `ClosedWorldEffectAnalyzer`,
rebinds through `BorrowDispatchAnalysis`, then refines field/return/temporary
facts to convergence before final lowering. Bridge facts are projections bound
to the exact resulting IR. This dependency ordering makes deleting one class
without following its consumers unsafe.

| Producer/result | Actual consumers and disposition |
| --- | --- |
| `EscapeSummaryAnalyzer.EscapeSummary`: this/parameter escape, receiver-retained parameters, returned origins, borrowed returns, mayReturnFresh/null/nonorigin, fresh/nonreturn escape | `FunctionAnalyzer` alias/free/pool/temporary diagnostics (**O** heap proof); `OwnedArrayFieldAnalyzer` (**O** owned-heap destruction, **R** remaining native confinement); `SymbolicReturnOriginAnalyzer`, `BridgeConstructionFacts` and `BridgeResultOrigins` (**G** ordinary managed result anchoring, **R** foreign borrow/copy protocol). Escape/nonretention facts are **P** if consumed by an actual optimization, not blanket GC allocation elision permission. |
| `BorrowDispatchAnalysis` bound targets and value flow; `ValueClasses`; `ReentrantOperations` | Escape/field/symbolic refinement and `ClosedWorldEffectAnalyzer`, bridge call targets and reachability. **O** destructor receiver/reclaimed-set logic; **P** exact dispatch/devirtualization and specialization; **G** collection effects, final target closure and root liveness; **R** actual callback/resource reentry. Preserve conservative unknown targets. |
| `OwnedArrayFieldAnalyzer`, `OwnedArrayElementAnalyzer`, `ConstructedFields`, field-loan/must-reclamation proofs | `FunctionAnalyzer` field borrows and constructor/destructor safety, generated rollback and bridge construction/cleanup admission. **O** exclusive managed-field ownership and must-free requirements; **R** native/resource-backed view and initialization facts; **G** exact layouts and valid partial construction. Do not retain exclusive ownership restrictions on freely shared managed graphs. |
| `SymbolicReturnOriginAnalyzer`, `ReturnOrigin`, `FreshArrayElementAnalysis`, `FreshBorrowingFactoryAnalysis`, `MapCopyFactoryAnalysis`, `SetCopyFactoryAnalysis`, `CopiedMapReadAnalysis` | Escape summaries, field loans, copied container getters, fresh result reclamation and bridge result proofs. **O** independent-free/borrow lifetime restrictions for managed results; **R** copied storage versus retained native storage, callbacks/foreign views; **P** existing value/dispatch refinement where used; **G** reference-return root facts. Collection membership/copy behavior itself remains. |
| `TemporaryBorrowAnalysis`, `TemporaryListBorrowAnalysis` spans/sites | Escape fixed point, SymbolicReturnOriginAnalyzer and FunctionAnalyzer temporary-owner/iterator cleanup. **O** temporary destruction and no-free exceptions; **R** any reused proof establishing foreign storage confinement must be retained separately. There is no reason to keep a managed temporary nonescaping merely so it can be freed. |
| `PoolReleaseAnalysis` exact originating-pool identity, `PoolSemantics` | Escape/symbolic returns and FunctionAnalyzer transfer/borrow checks. **O** blocking independent managed free; **R** same-pool return and reuse contracts survive; **P** allocation-free reuse must remain measured. Wrong-pool release is not made safe by GC. |
| `ClosedWorldEffectAnalyzer` allocates/throwsOutward/published/reclaimed sets and exact constructor/destructor contexts | FunctionAnalyzer safety, temporary lowering, ReentrantOperations and bridge cleanup/construction/critical admission. **O** destructor allocation/throw/publication prohibitions and heap reclaimed sets; **R** exception/resource/callback contracts; **G** a distinct transitive MayCollect over final IR, including initialization, hidden calls and cleanup. Allocation does not imply collection. |
| `UnfreedAllocationTracker`, `UnfreedMode`, suppression and rejected-free explanation/witness/observer infrastructure | CLI, `CompilerPipeline`, source/class/archive loading, `FunctionAnalyzer`, owned-field/escape diagnostic evidence, IDE output parsers. **O** missing-free diagnostics, modes, directive and free-specific notes; keep ordinary source diagnostics/spans and deliberate migration errors. Diagnostic witnesses are not root metadata. |
| `BridgeConstructionFacts`, `BridgeRetentionAnalyzer`, `BridgeRootRetentionAnalyzer`, `BridgeNonReclamationAnalyzer`, `BridgeCleanupAnalyzer`, `BridgeDestructionAnalyzer`, `BridgeRollbackAnalysis` | `BridgeObjectAdmission`, final retention/nonreclamation projections, EntryModule, listener/root/carrier producers. **O** heap ownership acyclicity, destruction capability and non-reclamation admission of ordinary managed objects; **R** JNI/resource acquisition/retirement and supported foreign nonretention; **G** root anchors, collection effects and partial-object metadata. Revalidate projections after transformed IR. |
| `BridgeCallbackReachability`, `BridgeCriticalCalls`, `BridgeSynchronousCallbackProof`, `BridgeOwnedCallbackProof`, `BridgeCallbackCarrierLifetime`, `BridgeByteViews` | Context specialization, critical adapters, callbacks/batches, carrier cleanup and byte-view admission. **R** callback guards, invocation-owned JNI references, bounded foreign view lifetimes and unsupported-crossing refusal; **G** MayCollect plus suspended segments. Critical/noncollecting paths are admitted only with complete final proofs. |
| Specializers, `ClosedWorldPruner`, native-link transformations and `LlvmEmitter` layout/trace readers | **P** retain closed-world optimization and reachability; **G** recompute effects/maps after changes and preserve statepoint bases/sections. Late trace metadata injection cannot invalidate GC return PCs or vice versa. D132/D133 remain performance gates. |

The direct EscapeSummaryAnalyzer consumer files are explicitly listed in the
manifest, including all copy-factory helpers. Retaining an analysis for a
possible optimization is a migration classification, not a requirement to keep
its complete old implementation. GC7 may remove dead computations only after
the remaining consumers use narrower sound facts.

## Focused baseline and test disposition ledger

Environment: `machost`, macOS arm64; Ironwood **0.6.2-beta**; LLVM/Clang/opt/llc
**23.1.0**. Primary bootstrap/native test run: Oracle **Java 21.0.1**, javac
`--release 21`. The same 24 selections also passed with HotSpot **25.0.4.1**.
The defer driver's PATH version probe printed Java 25; its compiler launch used
the explicit Java 21 `JAVA_HOME`. Native benchmark processes need neither JVM.
No Linux qualification or new supported-target claim follows.

All 24 exact names below passed under each JDK. Run the primary selection with
repeated `--test` arguments, not substring filters:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home \
  ./scripts/test.sh --test 'runtime allocation and deallocation ABI works'
```

The manifest's `tests.exact_names` supplies the complete repeated selection and
log hashes. Unknown names fail. **PRES** means preserved behavior, **HEAP** changed
heap expectations, **SYNTAX** removed syntax, **REPL** replacement GC/resource
coverage. Mixed tests must split, not disappear with their heap assertions.

| Exact registered test | Disposition after cutover |
| --- | --- |
| runtime allocation and deallocation ABI works | HEAP/REPL: replace raw managed deallocation harness with allocator/collect/domain checks. |
| safe free accepts local allocation and ended aliases | SYNTAX; preserve alias/reference semantics in GC-root controls. |
| safe free rejects live aliases and escaped allocations | SYNTAX; replace dangling-managed checks with root survival plus separate foreign-lifetime negatives. |
| destructor and constructor effects are checked closed-world | SYNTAX/REPL: remove destructor effects, retain initialization/resource/callback effect negatives. |
| deterministic destructors and rollback run at O3 | SYNTAX/REPL: zeroed/published failed construction and explicit acquisition rollback. |
| unnamed temporaries match handwritten cleanup | HEAP/SYNTAX: no implicit destruction; retain operand/exception ordering and add root/reclamation controls. |
| deferred calls replay typed captures across independent exits | PRES; captured references become roots. |
| deferred calls preserve exit and failure order at O3 | PRES with destructor/free portions split out; add collection during cleanup. |
| deferred calls flush and close loopback sockets at O3 | PRES resource behavior; remove managed frees and use deliberate collect for heap assertions. |
| deferred free enforces local syntax and pending binding writes | SYNTAX; add obsolete-form diagnostics and ordinary method named free in defer. |
| finally preserves primary exceptions and exposes secondary exceptions | PRES; collect with primary/secondary graph in flight. |
| unfreed options preserve native output and artifact diagnostics | SYNTAX; replace with removed-option/directive errors across artifact inputs. |
| pool release helpers preserve borrows and allocation-free reuse | PRES pool protocol/allocation baseline; HEAP for immediate live-count restoration. |
| pool release helper proofs preserve mandatory safety | SYNTAX for heap-free negatives; PRES/REPL wrong-pool/foreign-resource safety. |
| pool release helper proofs survive artifact reconstruction | PRES source/class/archive equivalence; HEAP assertion migration. |
| catchable allocation failure boundaries run at O3 | PRES failure order/immortal OOM; REPL no-automatic-collection control. |
| allocation failure primary and secondary ordering runs at O3 | PRES; HEAP rollback counts replaced by post-collect counts. |
| native Throwable trace storage survives refresh release and allocation failures | PRES traces/bounded OOM; HEAP/REPL sidecar graph and no finalizer. |
| trace bookkeeping preserves allocation counts and safe free | PRES total-allocation boundary; SYNTAX free; REPL exact accounting after collect. |
| Java Bridge root JNI payloads preserve mutation and unpublished rollback | PRES mutation/delivery failure; HEAP/REPL anchors and published partial graph. |
| Java Bridge private long callbacks preserve nested frames and contain native unwinding | PRES failure containment; REPL explicit suspended-segment roots. Existing pass does not prove collecting JVM reentry. |
| Java Bridge byte-view proofs preserve typed bounds confinement and artifact parity | PRES; REPL collection inside supported borrow and refusal outside lifetime. |
| Java Bridge generated root state preserves refusal identity and isolated weak caches | HEAP/REPL handle/anchor closure, independent aliases, stale identity and image isolation. |
| Java Bridge production scalar libraries preserve ABI and warm-path allocation | PRES no new scalar maintenance; REPL new ABI identity and incompatible-image refusal. |

Apply the same disposition to the rest of the existing affected coverage:
Free/Unfreed/SuppressUnfreed/RejectedFree/DeferredFree test families and
`docs/self-hosting` free-proof evidence become removed-syntax/historical coverage;
TemporaryReclamation, destructor/owned-field/owned-array and rollback fixtures
split into changed heap and initialization/resource replacements. DeferTests,
finally, primitive/array/String/collection results, parser/type errors, dispatch,
class initialization, OOM and trace behavior remain, with only free/destructor
fragments and immediate reclamation assertions migrated. Bridge lifetime,
retention, non-reclamation and destruction tests change to anchors/root effects;
JNI containment, resource balances, ByteView confinement, loader identity and
critical scalar tests remain. Pool wrong-origin/reuse and callback protocol
tests remain even when their original unsafe-free assertion is removed.
This policy covers tests not executed in GC0; it is not a claim they were run.

Required replacement coverage is the plan's GC2-GC9 matrix: rooted versus
unrooted graphs, pointer-looking numerics, arrays/large maps, statics and
initializer failures, exceptions/secondary/carrier cycles, every optimization
level, deferred captures, derived addresses, partial construction, resource OOM,
foreign reentry/anchors and incompatible artifacts. GC0 does not preimplement
those future tests in an allocator-less compiler.

### Isolated experiments and performance fingerprints

The [GC0 driver](../scripts/gc-evaluation/run_gc0.py) builds only independent
fixtures against the already prepared pinned library, never production code:

```sh
python3 scripts/gc-evaluation/run_gc0.py \
  --evaluation workspace/gc-evaluation-2026-11-04 \
  --output workspace/gc0-baseline/collector
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home \
  python3 scripts/test-defer-performance.py --llvm-home /opt/homebrew/opt/llvm@23 \
  --case call --case pool --rounds 6
```

Use a fresh ignored output directory for reproduction. Prepare a missing
evaluation dependency tree with the existing [evaluation recipe](../scripts/gc-evaluation/README.md).
Collector children retain the sampled **16 GiB** RSS guard and **60-second**
timeout. Short runs can end between samples; sampled RSS is not exact peak RSS.
No resident-capacity, 5 GiB resident or self-hosting test was performed.
The tiny nonallocating defer timing cases are not memory-intensive experiments.

Results:

- Accounting probe: nine explicit collections, graph counts given above;
  no sidecars, collector administration or free-list cells counted as language
  objects. Initial development incorrectly assumed no collector administration
  would be enumerable; source inspection identified its three free-list arrays.
  The corrected test filters by kind and still asserts exact language/sidecar
  counts. No collector patch or language header ledger was added.
- Per-type probe: one kind, 1,024 descriptors; all 1,024 true children survive and
  1,024 numeric-only targets are reclaimed.
- Policy probe: zero startup/automatic collections; null after **521,408**
  64-byte allocations with 32 MiB max heap and two exhaustion retries. One
  explicit collection permits 200,000 replacements, with zero deferred reclaimed
  bytes during subsequent allocation.
- Deterministic pooled/rare workload: 4,096 64-byte nodes, allocation once per
  4,096 operations in the rare case, two million warmup mutations, 31 batches of
  five million operations, five processes per case, interleaved with seed
  `20261104`. All 20 processes have zero timed-window GC events and checksum
  `15067327215085515643`. Median of process medians, ns/operation:

| Configuration | Pooled mutation | Rare allocation |
| --- | ---: | ---: |
| calloc control | 1.1628 | 1.0632 |
| compiler-directed BDWGC | 1.1658 | 1.0784 |

The variability overlaps; this is a reproducible synthetic baseline, not a claim
of a production percentage regression or zero-cost allocation. Both fresh
executables have identical **108-byte** `_pooled_mutate` machine code, SHA-256
`846e8b129ff65ebe78a9c0c0c6c73885de9b83b3fc9bcff507b88c6144b0f5ef`.
Inspection shows loads/stores/arithmetic/branches with no calls, TLS, reference
barriers or registration. It matches the earlier evaluation fingerprint.

The actual Ironwood defer driver selected only **call** and **pool**, six
alternating rounds each, 32,000,000 operations/process plus its eight-iteration
behavior oracle. Both deferred and handwritten nested cleanup allocate zero
objects in the warmed window and have zero live delta. Normalized optimized
assembly is identical for each pair, with zero application or text byte delta.
Main function sizes are 1,308 bytes for call and 6,224 bytes for pool.
Deferred/nested batch medians were **37,054,500/36,958,500 ns** and
**75,627,500/75,591,000 ns**, respectively. Cold existing trace registration and
constructor rollback remain visible outside the warmed path; no claim is made
that all runtime helpers vanish. Manifest hashes preserve the exact optimized
assembly, binary and source inputs for later GC3/GC8 comparison.

The old native-caller-root result (17 callers, 30 collections and omitted-root
control) remains evidence from the evaluation manifest, not rerun production
root qualification in GC0. Likewise, existing two-image trace tests do not prove
collector isolation. GC6 must test actual private collectors.

Raw logs, dependency sources/archives, executables, disassembly and measurements
remain under ignored `workspace/gc0-baseline/` and
`integration-tests/target/defer-performance/`. Only original fixtures, compact
manifests and conclusions are committed. Driver usage initially rejected five
rounds because alternating rounds must be even; the recorded successful run
uses six. No unfiltered compiler/native suite, hosted run, push or GitHub write
was used.

`git diff --check`, the license audit (five existing OpenJDK-derived source
files), staged tracked-file check, Python syntax, relative links, JSON and source
fingerprint/inventory consistency checks passed.

## Decision supersession map

This is a **future activation map**. D315 records the accepted migration design
without marking current implementations superseded. Apply the following edits
with the milestone actually changing behavior, and synchronize memory/language,
bridge, CLI, feature comparison, examples and distribution docs at that time.

| Decision(s) | Planned treatment and activation |
| --- | --- |
| D005 | GC5 supersedes ordinary heap safe-free syntax/proof. Preserve native/resource lifetime safety through narrower contracts. No weakening beforehand. |
| D027, D019 memory portions, D029 no-GC assumption | GC5 supersedes no-collector/until-free storage semantics. Preserve native AOT, ordinary references and contributor provenance. D004 remains historically superseded: do not revive its automatic allocation-time GC. |
| D083 | GC4/GC5 replace managed deterministic destruction and child rollback with exact partial layouts and explicit native acquisition rollback. No collection-time user cleanup. |
| D084, D094, D102-D105, D169-D170 and copied-storage proof decisions D162, D251-D253 | Remove managed independent-free/dependent-borrow/exclusive ownership restrictions at GC5/GC7. Preserve real pool origin/reuse, copied values, initialization and native view protocols. Keep proof facts used by actual optimization or resource consumers. |
| D089, D090 exceptional rendering portion, D111, D121/D126 owned-rendering/trace cleanup portions | GC4/GC5 remove managed temporary/owned-text destruction flags/helpers, retain rendering output/failure order and construction-time trace behavior, migrate trace storage. |
| D140, D145, D184, D280 | GC5 removes missing-free modes/directive and free-specific explanation behavior. Keep migration errors and ordinary diagnostics/source spans. Historical evidence remains labeled as such. |
| D180 | Preserve Java assignment failure order; retire destructor-receiver proof portion with GC5. |
| D185 and temporary refinements | GC5 removes full-expression destruction and observable destructor effects, including hidden argument/receiver/text cleanup. Preserve operand evaluation and exception timing; GC roots cover unnamed values. |
| D168 | Keep general defer, capture/LIFO/exit/D051 behavior and allocation-free lowering. GC5 removes only deferred-free syntax, pending binding restrictions and heap-reclamation obligations. |
| D189 | GC6 supersedes generated facade free with the separate RootHandle lifetime surface. Source close remains resource closure. |
| D190-D192, D198 and later bridge ownership/destruction refinements | GC4/GC6 replace managed owner-dependency ledgers, acyclicity/destruction admission and borrowed-view free refusal with anchors/effects. Preserve image identity, safe facade access, foreign confinement, invocation/JNI cleanup and failure containment; apply the closed-epoch identity boundary above. Later supported callbacks remain supported only after segment qualification. |
| D234 | Keep JVM-owned bounded ByteView storage, permissions and synchronous borrowing. GC6 adds collection/reentry owner-root qualification, not a ByteView free/close hook. |
| D282-D290, D296-D308, D310 and adjacent free/field-loan refinements | GC5/GC7 retire constructor/destructor publication and must-reclaim computations insofar as they prove managed free safety. Preserve source/type validity, initialization, actual native resource and callback contracts. Treat the stopped Ironwood port as a migration consumer only. |
| D051 | **Preserve** primary exception and ordered secondary failures, independent explicit resource closure, finally and current try-with-resources exclusion. |
| D132/D133 | **Preserve** on-demand traces, outlined uncommon paths, initialization fast barrier and no per-call/per-operation GC maintenance on noncollecting hot paths. Statepoint spills and foreign transition capture require separate measured qualification. |
| D188 | **Preserve** caller single-thread/external serialization obligation without runtime thread checks, locks or collector workers. |

## Remaining implementation gates and bounded GC1 task

There is no unresolved GC0 contract blocker. Qualification risks remain gates,
not promises proved by the inventory:

- GC1: immutable engine build inputs, dependency license/notice review and
  redistribution, adapter ABI/config validation, target-specific build setup and
  symbol isolation. Reproduce policy/eager-reuse controls with strict warnings.
  Keep the production calloc/free allocator and source semantics unchanged.
- GC2: all production kinds/layouts, large/overflow maps, arrays/String tails,
  collector mark-stack recovery and metadata OOM with no conservative fallback;
  full accounting-kind audit and allocator-domain-safe file construction.
- GC3/GC4: final transitive MayCollect, every optimization mode, static roots,
  derived-base identity, spills/unpromoted stack regions, exception wrappers,
  emergency state, complete construction and native resource failure cleanup.
  Validate coexistence of root sections with late trace injection.
- GC5-GC7: coordinated syntax/stdlib/artifact cutover, explicit resource and
  registration migration, obsolete-slot clearing and narrower analysis deletion.
  Do not activate reclamation while a hidden reference owner is unmigrated.
- GC6/GC9: collecting JVM reentry without unwinding JIT frames, all suspended
  segments and JNI roots, independent images and sequential foreign callers,
  incompatible ABI refusal and all supported target readers/distributions.
- GC8: deterministic hot-path and phase-boundary measurements, realistic
  resident graphs under the unchanged 16 GiB guard. No automatic self-hosting
  restart or restoration of discarded migration work.

The next task is **GC1 only: prepare the pinned collector and narrow isolated
adapter/build/packaging inputs, with no production allocator or language
cutover**. Do not combine it with GC2 layouts or GC3 root generation.
