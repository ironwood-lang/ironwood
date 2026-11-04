<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC2 layouts and qualification

Date: 2026-11-04. **GC2 complete for the isolated macOS arm64 qualification.**
Production GC remains disabled.

## Pre-change review and verification selection

Preserve current calloc/free allocation, mandatory ownership proofs in every
unfreed mode, constructor rollback, D051 cleanup order, D132/D133 and all current
native generation identities. Add only dormant compiler descriptions and the
isolated qualification adapter. No effect, root or statepoint integration.

Derive layout facts on demand from final resolved IrClass/IrArrayType storage,
including PrimitiveGenericSpecializer, callback-context lowering, cloning,
ClosedWorldPruner and UnreadFieldStoreEliminator consumers. Do not cache maps
before these transformations or reconstruct offsets from source declarations.
Pair reference slots with address-shaped primitives, inherited/hidden/generic
fields, failed and partial construction, zero defaults and free-list recovery.
Refuse legacy pointer-bearing integer representations explicitly.

Select focused compiler checks for descriptor derivation, C/LLVM agreement and
transformation stability; strict C11 native checks for each kind, metadata OOM,
actual mark-stack overflow/recovery, array/String tails, native construction
scratch, accounting and rooted/unrooted pairs. Retain the six production controls
listed in GC1_ENGINE_PREPARATION.md. SDK verification must reject old identities,
headers and adapters. Preserve the 16 GiB sampled RSS guard and bounded children.
No unfiltered suite, remote qualification or self-hosting work is authorized.

The pinned collector's small-cell recovery accelerators scan numeric words.
Use its existing USE_MARK_BYTES configuration to remove those accelerators;
verify the effective preprocessed recovery code and qualify actual overflow.
Do not modify collector source. The configuration and adapter identities change,
so old GC1 SDKs must be rebuilt. Linux targets require fresh qualification.

## Authoritative allocation-layout inventory

This inventory covers the current bootstrap compiler and native runtime, not
just the four GC1 test shapes. **C/** means
`compiler/src/main/java/ironwood/compiler/`; **R/** means `runtime/src/`.
The physical source of truth is `IrClass.fields` in storage order and
`IrArrayType.type.elementType`, after primitive specialization. Field names,
owners and layout indices retain inherited/hidden storage identity.

The frontend path is `C/semantic/SemanticAnalyzer.collectMembers`: it copies
inherited layout fields, inserts `<enclosing>` and `<capture:...>` fields,
adds enum name/ordinal storage, then source instance fields. `buildIrTypes`
creates IrClass. `FunctionAnalyzer` lowers ordinary and generated construction
to IrAllocateInstruction, arrays/initializers/multidimensional construction to
IrArrayAllocateInstruction, and String operations to the IrString* family.
Invokes carry those same allocation instructions on throwing paths.
`PrimitiveGenericSpecializer.specializeClass/field` resolves physical primitive
substitutions, including inherited owners. A reference generic field and its
`long` specialization therefore have different maps.

`C/backend/LlvmEmitter.emitClass`, allocation/invoke emission and outlined throw
helpers consume those physical types. Sizes and field addresses use LLVM GEPs.
`emitArrayTypeMetadata`, String intrinsics, stack-trace materialization and the
native entry's argument conversion reach the same runtime allocator boundaries.
`C/semantic/BridgeCallbackContextLowering`, `InitializedTypeSpecializer`,
`EnumArgumentSpecializer`, `ClosedWorldPruner` and
`UnreadFieldStoreEliminator` copy classes or functions. The latter removes
unobserved primitive stores, never storage or reference stores. GC2 recomputes
immutable descriptions from the final program rather than propagating a cached
map through these copies. Source/class/archive reconstruction similarly
recomputes from revalidated source; no artifact or generation format changed.

| Family and actual consumers | Current allocator and physical layout | Managed edges and exact experimental scanner | Language count and failure cleanup |
| --- | --- | --- | --- |
| Ordinary classes, inherited fields, hidden enclosing/capture fields, anonymous/local classes | IrAllocateInstruction -> `ironwood_allocate` -> `try_allocate_object`; calloc, first-word type pointer, flattened resolved fields with ABI padding | IrAllocationLayout FIXED; reference/array/erased reference fields only. Small word bitmap or arbitrary-length offset map. Primitive padding/bits are never edges. | One successful non-immortal allocation, including later failed constructors. Current rollback/deallocate remains authoritative. Experimental abandoned storage survives until collection. |
| Reference generics and primitive generic specializations | Same boundary; specialized IrClass contains resolved physical field types | Recompute per specialization, including hidden and inherited slots. Reference `T` is traced; physical long/int/etc is not. No map indexed solely by source generic name. | Same event rule and existing rollback. Metadata refusal precedes allocation. |
| Enum instances and constant bodies | Generated initialization and ordinary allocation; hidden String name, numeric ordinal and resolved body fields; static references retain constants | Same FIXED scanner, including parent and body fields. Enum permanence is a root/lifetime policy, not a reason to guess fields. | Successful heap allocations count. Literal/global immortal storage does not. Initialization failure cleanup stays current. |
| Generated managed holders/proxies, library pool/map/list nodes, ByteBuffer owner/view wrappers | Generated .iron sources in `C/bridge/BridgeListenerProxies`, listener/callback source producers and ordinary stdlib construction; IrAllocateInstruction | Same FIXED layout. Typed holder/proxy/owner/backing-array references are edges. JNI/native resource numbers are not. The legacy carrier exception is refused below. | Language events count, even for pool-owned objects. Current resource/pool cleanup stays intact. GC must never use logical pool/list size to omit physical links. |
| Reference arrays, array initializers, varargs, multidimensional outer/inner arrays, cloned/copied arrays | IrArrayAllocateInstruction -> `ironwood_allocate_array`/`try_allocate_array`; header `{type,size_t length,size_t element_size,u32 element_kind,u32 reserved}` then inline payload | ARRAY kind 9, exact mark procedure visits **every** physically stored reference slot in `[0,length)`. Outer multidimensional elements are ordinary array references. Headers and unused block rounding are excluded. | Each successful array allocation counts, including every inner array. Current generated cleanup remains. Experimental partial graphs are zero/null initialized and collectible. |
| All eight primitive arrays, including runtime filesystem metadata results | Same header; kind 1..8, strides 1,1,2,2,4,8,4,8 | ARRAY pointer-free descriptor, full default zeroing. No payload bits are scanned. | One per successful array; overflow/negative-length checks precede successful allocation. Failed native operations retain current cleanup. |
| Strings: constructors/copies, concatenation/formatting, case/replacement/repeat/join/ranges, environment/properties, paths, directory names, trace rendering and bridge UTF conversion | IrString* and native helpers -> `allocate_string`/`try_allocate_string` -> `try_allocate_object`; `{type,i32 utf16_length,i32 utf8_length,u16 units[]}` | STRING pointer-free descriptor; two source int fields validated against the runtime special tail. Numeric UTF-16 and lengths are not roots. Fresh/copy result identity is preserved. | One successful non-immortal result. Some validation fails before allocation; later failure still counts any successful language storage. Current error/UTF/native-buffer cleanup remains. |
| Process argument array and Strings; stack-trace result array, StackTraceElement objects and their Strings; native bridge converted arrays/Strings | Native entry/`ironwood_process_arguments`, LLVM trace materializer, bridge conversion helpers call the same ordinary/array/String boundaries | No extra heap family. All typed references in StackTraceElement/holders are FIXED edges, arrays use their physical kind, Strings use STRING. | Count each language allocation, not one source expression. Native argv/JNI temporary buffers are excluded and use matched cleanup. |
| Unpublished file byte[]/String result | `read_file_result` initially creates a counted calloc language result, `reserve_file_result` and final optional shrink use libc realloc on that unpublished storage | **Direct collector substitution is refused.** GC storage cannot enter those realloc/free paths. The isolated native-scratch construction strategy below yields a described ARRAY/STRING result. | Current production behavior unchanged. Experimental scratch does not count; final successful language materialization counts once. Close native descriptor and free native scratch on every failure. |
| Throwable and subclasses, generated `_ForeignFailure` | Current Throwable prefix `{type,message,cause,u64 trace_state}`; trace_state encodes a native metadata pointer. Carrier `nextCarrier` also encodes a managed link in long | **REFUSED**, including inherited Throwable storage and the legacy carrier link. Future representation must split primitive state from an explicit metadata reference, and make nextCarrier a typed reference. A source bitmap over the current longs is unsound. | Throwable is a language object; metadata is not. Existing trace release/destructor/rollback and JNI release remain. No collector activation for these legacy layouts. |
| Exception metadata and secondary-exception cells | `create_exception_metadata` and `append_secondary_object` use native calloc; trace capture uses native calloc/realloc | Future memory-only sidecars use the described internal FIXED kind: metadata has primary, first/last secondary and trace edges; secondary cells have object/next edges. Isolated offsetof-based sidecar fixtures qualify these graphs. Current native representation is **not** collectable. | Excluded from language total/live. Native failure paths free partial metadata/entries and retain bounded emergency fallback. Later GC4 installs explicit references, replacing native memory-only lifetime cleanup. |
| Trace elements/PC buffers/text/codec scratch | Trace capture and UTF/path helpers use stack or matched native malloc/calloc/realloc/free; trace elements contain immutable native site pointers | Future retained memory-only trace buffers use pointer-free internal FIXED storage. Site/code addresses and trace numbers are not managed edges. Transient native scratch remains native. | Excluded. Realloc/free is legal only in native domain; optional resize failure retains original storage. Bounded emergency storage remains immortal. |
| Tagged concatenation/printing parts and bridge context/frame addresses | `LlvmEmitter.emitStringConcatenation/concatPayload`, IrStreamWriteInstruction and bridge frame operations; stack `{kind,reserved,u64 payload}` or native call/frame transport, not heap allocations | STRING payload is a managed address encoded in u64; numeric variants are not references. GC3 must preserve the original typed base or describe the tag-selected stack slot across collecting evaluation/calls. Bridge context/address longs refer to native frames whose managed fields need explicit roots, not tracing of arbitrary longs. Current noncollecting native concat/print operations are unchanged. | Excluded transient storage; result String still counts. No conservative heap/stack bitmap for payload words is accepted; collecting use without exact bases/root metadata remains unsupported until GC3. |
| In-flight `_Unwind_Exception` wrapper, emergency/global exception state | `raise_language_exception`, cleanup/take/delete, bounded emergency globals/TLS; wrapper contains managed object plus native unwind ABI | Keep native wrapper allocation and unwind ABI. Its object slot and active emergency links need GC3/GC4 exact roots. These are native root owners, **not** language allocation kinds; no ambient scan is supplied. | Excluded. Matched wrapper cleanup on catch/rethrow/delete; current fatal native-wrapper OOM path unchanged. Collecting crossings without these roots remain unavailable. |
| Directory handle, resolver/interface/host snapshots, TLS/OpenSSL state, native strings/paths and descriptor buffers | `R/ironwood_runtime.c`, `ironwood_tcp.c`, `ironwood_host.c`, `ironwood_tls.c`; libc/platform/OpenSSL allocators and explicit releases | Native-only pointers/resources, no retained Ironwood managed input in TLS state. Descriptor/handle/addrinfo/ifaddrs numbers are not heap edges. Borrowed input arrays require caller roots at future collecting boundaries. | Excluded. close/closedir/freeaddrinfo/freeifaddrs/native free/OpenSSL releases stay matched; failure releases acquired resources. Do not route them through a collector scanner. |
| Java Bridge root records/index, listeners/retirement links, callback frames/batches, result records, JNI arrays/String acquisitions and caches | `C/bridge/BridgeRootIndexSources`, `BridgeListenerNativeSources`, `BridgeCallback*Sources`, array/String entry/value helpers; native malloc/calloc and JNI APIs | Native structures holding managed addresses require GC3/GC6 explicit foreign/invocation root enumeration. JNI references are JVM/native resources, never BDWGC edges. ByteView is a borrowed `{native data,length,permission}` descriptor without an object header: managed allocation/array descriptions are **REFUSED**. | Native bookkeeping/JNI storage excluded; generated managed holders/proxies still count. Current matched rollback/releases remain; no finalizer/GC callback replaces them. Foreign collection/reentry is not enabled. |
| Immortal literals, descriptors, PrintStream globals and allocation-failure objects; statics and class-initialization failure slots | LLVM constants/globals and bounded runtime storage, no ordinary successful allocator event | Not collector allocations. Mutable managed slots need immutable static root tables in GC3. Header/type/code/member metadata pointers are native data, not managed fields. | Excluded from both language counters. No GC free/finalizer. Failed class initialization still needs its managed exception rooted later. |
| Collector free-list cells and administration | Six bounded kinds create six internal free-list arrays; unused cells have first-word next link and zeroed remaining words | Indirect descriptor at type offset 8 reads zero from a linked free cell; terminal null is handled by the engine. USE_MARK_BYTES recovery preserves descriptor dispatch. Free-list marks are cleared before enumeration. | Both free cells and administration excluded from language counts. Actual free-cell/reuse and recovery tests pass. |

No additional source heap allocator exists in the audited compiler/runtime paths.
Library backing storage, compiler-generated temporaries, throw helpers and native
result conversion all reduce to the families above. Resource-owned native state
and native root records deliberately remain separate domains. Their future roots
are GC3/GC4/GC6 gates, not undisclosed heap scanners.

## Compiler descriptions and experimental representation

`IrAllocationLayout.of(IrProgram)` inventories every physical class and array,
including explicit refusal records. Both full-program GcLayoutEmitter entry
points refuse output if any family is unsupported; they cannot silently filter
out Throwable or ByteView. List-based entry points select a deliberate standalone
fixture closure. They do not authorize omitting a family from a production image.
Interfaces have no allocated storage. Noncontiguous fields, unknown physical
kinds, unexpected String storage and legacy hidden links fail closed.

C emission uses ABI `sizeof`/`offsetof`; LLVM emission uses resolved field types
and constant GEP/ptrtoint expressions. LLVM receives the verified target triple
and data layout before llvm-as/opt/llc. The strict C executable asserts agreement
with the actual production array/String headers and compares every size, kind,
field count and reference offset in the separately compiled C and LLVM
descriptors. No handwritten class offsets are duplicated. Current supported
experimental targets are 64-bit little-endian; SDK verification rejects other
widths/endian layouts. Only macOS arm64 was executed for this GC2 profile.

The experimental metadata struct is `{version,shape,language,element_kind,
size,reference_count,references}`. `iw_gc_describe` validates it and makes an
immutable native copy, including offsets, within a cold setup budget. Native
metadata lasts with the image; there is no per-object registry or unregister
operation. A failed reservation/calloc refuses the descriptor. The compiler's
current production typeinfo/ABI, LLVM output and native generation remain
unchanged. The isolated type header holds identity, collector descriptor and
layout; future integration must extend production type metadata coherently.

Objects retain exactly one first-word type pointer and their original physical
fields/tail. **There is no object descriptor footer.** Two described kinds cover
language storage and internal sidecars. They share indirect descriptor dispatch
through the type pointer. Small maps use exact high-bit-first word bitmaps;
references outside the bitmap range use one registered exact offset-map mark
procedure. Reference arrays use that procedure with physical length and stride;
primitive arrays/String payloads use descriptor zero. Numeric values are never
submitted to marking. The adapter retains GC1's four historical test kinds;
they are not claimed as production coverage and do not grow per source class.

All kinds request complete zeroing, including pointer-free allocations. The
single-caller, disabled-collection allocation boundary installs the **final
dynamic** header and array/String metadata before returning. No source callback
or permitted collection occurs inside that boundary. Superclass constructors
must retain the dynamic header; they only initialize their fields. Fixtures
collect before field initialization, after only a base reference is installed,
and after derived initialization. Null fields, abandoned construction, allocation
identity, type alignment and later reclamation are covered. This
qualifies heap layout ordering, not compiler-generated roots inside constructors.

## Overflow exactness and failure behavior

Pinned `mark.c` has small-cell `GC_push_marked1/2/4` recovery accelerators under
USE_PUSH_MARKED_ACCELERATORS. They scan words despite a bitmap/custom descriptor.
GC1 did not exercise actual stack overflow and cannot qualify this path. GC2
adds the unmodified collector's **`-DUSE_MARK_BYTES=1`** configuration, which
removes these accelerators and preserves `GC_push_obj` descriptor dispatch in
MS_INVALID/MS_PARTIALLY_INVALID heap rescanning. The preparation audit checks the
effective macro and preprocessed absence of all three accelerator functions,
and records the mark.c hash. No collector implementation was patched. This is
a cold collector metadata/recovery choice, with no new ordinary-call/write
bookkeeping and no production performance claim.

A wide 65,536-child graph caused **120 actual overflow diagnostics at 4,096
entries**, followed by growth to 8,192 entries and successful synchronous
recovery. Exactly 131,073 language allocations survived; all 65,536 objects
whose addresses occurred only in numeric fields were unmarked. Removing the
root reclaimed the surviving graph. This is not inferred from graph depth.
Free-list cells, including a deliberately rooted already-reclaimed small cell,
produce no spurious language count or invalid descriptor dereference.

A real **4,096-byte native descriptor budget** admits five large descriptions,
then refuses the next reservation. No approximate/conservative descriptor is
created, no language allocation event is hidden and no collection occurs during
failure. Previously valid descriptors remain usable. This tests exhausted setup
headroom, not whole-process OS memory exhaustion. The ordinary native calloc
failure branch also returns null. Bad version/shape/offset/overflow descriptions
are refused before managed allocation. Oversized described objects, sidecars,
reference arrays and Strings exhaust the bounded heap without enabling GC or
incrementing language counts. Engine-internal unrecoverable failure is not
converted into successful collection or an approximate scanner.

## Accounting and native file construction

GC0/D315 accounting is unchanged: total counts successful non-immortal language
object/array/String events, even if later construction fails. Internal sidecars,
trace storage, descriptors, native scratch, administration and free cells do not
count. After eager collection, enumeration under the allocation lock filters
language kinds, then live reads use `L + (A - A_at_collection)`. No per-object
allocation ledger, live decrement, finalizer or per-write count was added.
The fixtures cover all described language families, both internal pointer maps
and pointer-free traces, large objects, failure after allocation, and subsequent
allocations. Six kind-administration arrays are excluded. The old four-shape
accounting/reuse tests still pass with this expanded bounded kind set.

`reserve_file_result` and its final libc shrink are incompatible with collector
storage. The qualified replacement **strategy**, isolated in the native fixture,
uses matched native construction scratch, with checked growth and failed-growth
preservation. Decode/read/size checks and native descriptor closure finish before
a single exact-size described byte[]/inline String is materialized and copied.
Optional shrinking can affect **native scratch only**, and failed shrink keeps
the original pointer. Empty input creates a distinct zero-length result without
reallocating a null scratch pointer or copying from it; growth overflow is refused. The fixture exercises initial scratch failure, growth
failure, pre-materialization failure, successful optional shrink, failed optional
shrink, and abandonment after successful language materialization. Pipe/read/close
and descriptor invalidation checks are real; selected allocation/I/O failure
points are deterministic fixture injections. UTF-16 copying includes a surrogate
pair; this does not replace or requalify production UTF-8 decoding.

Scratch is not language storage, so failure before materialization has zero
language events; success creates one, and a later failed operation still leaves
that event counted. This preserves the frozen **event definition**, rather than
counting scratch as a fictional language allocation. Compared with the current
algorithm, malformed input can now fail before any language allocation and no
longer contributes its early unpublished result event. This follows the changed
allocation algorithm, not a counter exemption. Production migration and native
resource/failure tests belong to GC4; the existing allocator and decoder remain
untouched. Directly replacing calloc while retaining realloc/free is explicitly
unsupported experimental output.

## Verification and reproduction

The compact [evidence manifest](gc-evaluation/gc2-2026-11-04-manifest.json) records
input/output hashes and focused results. Raw logs, generated descriptors, SDKs,
archives and binaries stay ignored under `workspace/gc2-*`.

On machost: Java **21.0.1**, Clang/LLVM **23.1.0**, macOS arm64 SDK **27.2**,
deployment **14.0**. The new canonical configuration SHA-256 is
`6fd39c7be9fc6ab1d5d5aea2e07610efad3113b4d1da6679aa3f6bad086f48fa`.
Adapter ABI is **ironwood-gc-engine-2**; reserved future native ABI/layout/root
versions remain unchanged. Configuration/payload verification rejects stale GC1
SDKs and mixed old/new headers, libraries or adapters. Rebuild the SDK instead
of relabeling a manifest. Existing private archive and final-image audits remain
required. No source package gains production collector linkage.

```sh
export JAVA_HOME=/path/to/jdk21
export SDKROOT=/path/to/MacOSX.sdk
./scripts/test.sh --test 'GC2 exact layouts follow resolved storage and refuse legacy hidden links'
python3 scripts/prepare-gc.py --prefix workspace/gc2-sdk-new \
  --llvm-home /path/to/llvm23 --cache workspace/gc-cache
python3 scripts/test-gc-layouts.py --sdk workspace/gc2-sdk-new \
  --llvm-home /path/to/llvm23 --java-home "$JAVA_HOME" \
  --output workspace/gc2-layout-results
python3 scripts/test-gc-engine.py --sdk workspace/gc2-sdk-new \
  --llvm-home /path/to/llvm23 --output workspace/gc2-engine-results
```

Use fresh output directories. `--case NAME` on the layout tool runs an individual
native case after descriptor generation/compilation, for focused follow-ups.
The tool reuses GC1's **16 GiB sampled RSS guard** and **60-second child limit**.
A separate 5 GiB atomic engine virtual span touches only two payload pages and
makes no language zeroing/resident-capacity claim. The described resident control
fully checks zeroing and writes **128 MiB**. These are bounded controls, not
self-hosting capacity or throughput evidence.

Focused results:

| Check | Outcome |
| --- | --- |
| Compiler-owned maps and fail-closed boundaries | Registered exact compiler test passed; actual primitive specialization, class pruning and enum function clones preserve maps; legacy/inherited Throwable, ByteView array and malformed storage refuse output. Production Shape layout still matches. |
| Separate C/LLVM compilation | 21 complete descriptor comparisons passed, including inherited/hidden fields, enum body, holder, reference/primitive generic forms and a frontend-generated 160-field, 80-reference map. 1,024 types share the bounded kinds. |
| Arrays/Strings | All eight primitive kinds, reference arrays, multidimensional graph, zero/short/large tails, zeroing, pointer-shaped payloads and length refusal passed. Actual production header/tail static assertions passed. |
| Initialization and recovery | Null/default objects, base-only initialization, later fields, abandoned storage, free cells and 120 real mark-stack overflows passed with exact counts and numeric-target rejection. |
| Failure and accounting | Five large metadata reservations exhaust the 4,096-byte budget. Four described allocation families fail safely at the heap limit, with no implicit collection/event count. Sidecars/traces and all six administration arrays are excluded. |
| Native construction | Checked native reserve/realloc/shrink, descriptor closure, byte result and surrogate-pair String copy passed, including selected failure points and post-allocation abandonment. |
| SDK and isolation | Two clean preparations produced byte-identical payloads/build manifests. Offline relocation, private archive/final Mach-O inspection and 27 nearby negative controls passed. An actual old GC1 SDK was rejected. |
| Production controls | All six exact GC1 controls passed on Java 21: runtime allocation/deallocation, accepted/rejected safe free, O3 catchable allocation failure, trace accounting and allocation-free pool release. |
| Bounds | 5 GiB engine-only virtual span touched two payload pages; 128 MiB described payload was fully zero-checked and written. Sampled RSS is recorded, not claimed as a continuous maximum. |

License/provenance, tracked-file, Python syntax, metadata/link consistency and
diff checks accompany the local checkpoint. Source-only and whole-SDK packaging
input staging use the same current verifier; no ordinary image enables GC.
No unfiltered compiler/platform suite was run.

## Remaining integration gates and bounded handoff

GC2 describes all audited allocation families with an exact scanner or the
explicit refusals above. It does **not** make current Throwable/native sidecars,
foreign crossings or source programs safe to collect. Production allocation,
source free/destructors, mandatory reclamation proofs and current generation
identities are unchanged. Collection effects, statepoints and compiler/runtime
root readers have not been implemented.

The new configuration needs fresh Linux x86-64 qualification. Earlier GC1 Linux
x86-64 evidence remains historical evidence for its old profile. Linux arm64
remains unqualified. No remote, hosted or paid jobs ran. Full IDK assembly,
foreign reentry/two-image execution, exception migration, resource cleanup and
performance qualification remain later gates.

**Next bounded task: GC3 only.** Add compiler-owned collection effects and
statepoint/root metadata for an explicitly supported experimental closure,
including final specialization/pruning, exact static/native roots and target
readers. Keep collection disabled in ordinary programs; reject unsupported
exception/foreign roots and the GC2-refused legacy layouts. Do not implement
source Memory.collect, remove destructors or perform the language/bridge cutover
as an incidental continuation of GC2.
