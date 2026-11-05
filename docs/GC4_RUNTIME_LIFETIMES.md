<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC4 runtime lifetimes and failure cleanup

Date: 2026-11-04. **GC4 complete for the selected macOS arm64 experimental
runtime. Production GC remains disabled.** Read with the
[integration plan](BDWGC_INTEGRATION_PLAN.md), [GC3](GC3_ROOTS_AND_STATEPOINTS.md)
and [compact evidence](gc-evaluation/gc4-2026-11-04-manifest.json).

## Pre-change review

Baseline: `403742ae`, following `6860bdfc`. Preserve production allocation,
syntax, mandatory free proofs, native/bridge identities, D051 cleanup ordering,
D132/D133, GC2 engine-2 exact descriptors and USE_MARK_BYTES. Linux arm64 remains
unqualified. No public collection API, language cutover or bridge migration.

Affected contracts and consumers:

- Experimental closure admission, collection effects and LLVM exception lowering
  consume typed exception operations. Permit only the explicitly selected GC4
  representation; legacy integer links, destructor/rollback and unknown foreign
  calls must still refuse. Production analysis remains authoritative.
- GC3 image integrity, stack maps and target readers reconstruct managed roots.
  A versioned native-owner inventory extends preparation, before enabling GC.
  Native wrappers, emergency ownership and anchors retain exact allocation bases
  for their actual lifetimes. No root work belongs on noncollecting calls.
- Throwable metadata, secondary entries, trace payloads and carrier chains use
  GC2 internal kinds, separate from language allocation accounting. Collection
  must never perform source destruction, native release or JNI cleanup.
- Explicit acquisition transactions own unpublished native resources. Commit
  transfers cleanup authority; failure releases only remaining acquisitions in
  reverse order without allocating, collecting or changing published stores.
  Existing production resource implementations and bridge producers retain their
  current behavior pending GC5/GC6 integration.

Paired cases: rooted exception/secondary cycles versus later reclamation;
pointer-bearing fields versus address-shaped integers; active wrappers and
anchors versus released/replaced owners; caught/rethrown identity versus stale
metadata and incomplete owner coverage; published failed construction versus
unpublished native acquisition rollback. OOM and preparation failure must leave
collection disabled and resource cleanup available. General defer captures,
LIFO, primary/secondary order and pending return/yield values remain live through
collection.

Focused verification selection: dedicated GC4 compiler admission/effect proofs;
compiler-derived native O0/O1/O2/O3 exception/cleanup fixtures; exact internal
layout, emergency/OOM, native anchor and resource failure controls; the GC3 root
matrix and existing seven production controls. Inspect O3 noncollecting code
against GC3; run call/pool deterministic controls if hot lowering changes.
Use the existing 16 GiB process-group RSS guard and 60-second child limit.
Finish with license, tracked-file and diff checks. No full suite or hosted work.

The implementation review additionally covers the late LLVM pass: collecting
invokes with managed values live on exceptional edges need Itanium aggregate
landing pads and nonmoving exceptional relocation identities. The original
LLVM-API pass runs the pinned rewrite, retains its gc-live operands/maps, resolves
exceptional relocations, then verifies. It is selected only for root ABI 2;
ordinary output and GC3 use their existing pipeline. Native O0-O3 survival tests
must cover values used only after an exceptional edge. Never disable verification
or substitute text repair, conservative scanning or a noncollecting annotation.

## Implemented ownership and representation

`runtime/src/ironwood_gc_lifetimes.c` is an original, isolated runtime component
linked by `scripts/test-gc-lifetimes.py`. The production runtime object is not
linked into this closure. No source syntax, production allocator, free proof,
destructor, native ABI or generated Java API was changed.

The fixture first passes ordinary source analysis. Its explicit GC4 projection
replaces Throwable's hidden integer metadata slot with `<gcMetadata>`, a typed
reference, preserving message/cause/owned-message and initialization storage.
Inherited layouts must have that exact physical prefix. Native unwind handles
remain distinct from the managed Throwable SSA values returned by landing pads.
Legacy unprojected `traceState`, `nextCarrier`, borrowed ByteView and unknown
native calls still refuse. This is not automatic admission of arbitrary programs.

GC2 descriptors describe four internal allocation shapes:

| Shape | Strong managed references | Primitive/native contents |
| --- | --- | --- |
| Exception metadata | primary, first/last secondary, trace | count and trace status |
| Secondary occurrence | Throwable and next occurrence | none |
| Trace snapshot | none | bounded native instruction PCs |
| Invocation carrier | failure and next carrier | opaque native cookie |

All have collector-owned storage and internal accounting kinds. Trace refill
replaces its reference and abandons the old snapshot for collection; no managed
block reaches libc realloc/free. Secondary graphs support cycles and preserve
flat occurrence order. There is no strong table of observed Throwables.
Language allocation counts exclude these blocks and the immortal emergency OOM.
The trace qualification concerns native snapshot storage and refill, not the
complete production StackTraceElement renderer; GC5 integrates the representation
with the existing source trace API and optimized attribution.

Native wrappers keep a scoped root from creation through `_Unwind_RaiseException`,
cleanup-only pads, nested delivery and resume. Catch transfers the exact object
into compiler-described SSA and deletes the wrapper without collecting between
the two actions. Rethrow creates a new wrapper for the same Throwable. Wrapper
cleanup clears its root before freeing native storage. Two simultaneous wrappers
are tested, including collection while neither Throwable needs a source local.

Native root records use caller-owned storage and explicit open/replace/close.
Independent records retain independently; closing one does not destroy or close
its referent. Creation and replacement accept allocation bases, with an exact
exception for the runtime's single declared immortal OOM object. Cross-image
bases cannot be accepted by this image's collector. This is the common native
anchor mechanism, not GC6's public RootHandle identity or stale-facade protocol.

Emergency ownership has an explicit allocation-free begin/end scope, one reusable
native unwind wrapper and 64 secondary occurrence slots for one active primary.
Failed sidecar construction restores the old sequence before using emergency
storage. Nested secondary order survives failure after each internal acquisition.
The emergency owner enumerates managed links in its immortal object explicitly;
an arbitrary native or legacy malloc pointer is not an immortal. End requires
completed wrapper ownership and clears all emergency and immortal managed links.
Repeated scopes reclaim old graphs. Exhausting the bounded association/wrapper
capacity is fatal, rather than recursively allocating or replacing the original
exception. Trace allocation failure remains best-effort and permits later refill.

## Owner protocol, preparation and artifacts

**ironwood-gc-roots-2** requires **ironwood-gc-runtime-1** coverage bits 1 (unwind),
2 (emergency) and 4 (native anchors). The compiler emits the required mask, in
addition to its root ABI. Startup checks exact masks, protocol version, executable
enumerator, image-owned immortal table, static/native inventories and the entire
GC3 map integrity contract. Foreign segments/reserved classes refuse. ABI 1
retains its zero-owner gates; stale objects lacking the new owner-requirement
symbol cannot link with the reader. Mutated valid-looking PCs, spill slots and
function relocations also refuse before collection.

Enumeration copies active owner values into the GC3 reusable native scratch
while collection is disabled, after complete stack reconstruction. No owner
callback, managed allocation or release runs in the enabled window. Collection
remains explicit, synchronous and eager, then unregisters/zeros scratch. Bad
coverage, domain or capacity terminates with the existing exit-86 preparation
refusal, before resuming managed code. The future catchable collection-setup OOM
entry remains GC5 work; resource rollback is available even when preparation fails.

LLVM 23's rewrite assumes token landing pads for exceptional gc.relocate, while
Ironwood's Itanium pads need exception/selector aggregates. The small original
`compiler/src/main/cpp/GcNonmovingStatepoints.cpp` pass invokes the existing LLVM
rewrite through its C++ API, resolves only exceptional relocations as identities
for this nonmoving heap, and preserves every statepoint gc-live operand. LLVM
verification runs afterward; no optimizer follows it. See LLVM's
[nonmoving simplification](https://releases.llvm.org/23.1.0/docs/Statepoints.html#simplifications-for-non-relocating-gcs).
It requires ABI 2 and the pinned development headers/library. The normal compiler
and ABI-1 path do not load this pass. No LLVM implementation was imported.

The **engine-2 SDK**, its payload hashes/configuration and **USE_MARK_BYTES=1**
are unchanged. Roots/owners and the LLVM pass are separately built, versioned
development inputs with hashes in the evidence, not silently added to an old
prepared SDK. GC2 exact descriptors, overflow recovery and allocator domains
remain mandatory. GC5/GC9 must bind the implemented owner/root identity into the
integrated SDK/native generation; production generation is unchanged here.

## Native-resource and failure ownership

GC0's 77 main-library destructor dispositions remain applicable: 76 reclaim only
managed child graphs; Throwable's additional releaseTrace frees memory-only
metadata, now collector-owned in this runtime. No main-library destructor closes
a file, socket, directory or TLS handle. Reviewed native-release consumers:

| Consumer | Explicit cleanup authority and GC disposition |
| --- | --- |
| FileInputStream/FileOutputStream and stream wrappers | Existing explicit close; acquire backing managed buffers before owned native streams. Collection has no close hook. |
| Socket/ServerSocket and socket implementations | Existing close and connect/accept failure cleanup retain descriptor ownership. Managed wrapper rollback is separate. |
| UnixDirectoryStream | close consumes DIR handle; its destructor only frees the managed path. |
| TLS state and TlsClient | Existing handshake failure/finally, abort and explicit close release SSL/BIO/CTX/provider/native state before transport closure. The borrowed TCP descriptor is not a managed link. |
| Files/host/resolver native scratch | Matched native release on every exit; no collector-managed storage passed to libc realloc/free. |
| Bridge listener retirement | Currently proxy destruction plus DeleteGlobalRef in `iw_listener_release`; GC6 must use explicit registration retirement plus anchor release. The GC path refuses this legacy producer. |
| Bridge callback carriers | Currently managed destruction plus DeleteGlobalRef in `iw_callback_release_slow`; GC6 must separate invocation JNI retirement from collectible memory. The exact carrier shape and common anchors exist now; generated adoption remains refused. |

`iw_gc_acquisitions` uses fixed caller-provided records. Reserve before native
acquisition, arm immediately after success, commit when cleanup authority transfers,
and abort the remaining records in reverse order. Abort needs no allocation or
collection and does not touch committed source stores. Its release functions are
native, nonthrowing and noncollecting; they are never user close/destructor calls.
File-descriptor and native-scratch fixtures cover failure before either acquisition,
after each acquisition, capacity failure, committed ownership, repeated abort,
heap exhaustion and root-preparation failure. Collection leaves open resources
and release counters unchanged. Final counts balance exactly.

Typed constructor fixtures publish a zero-initialized Node with a child, then
either collect and succeed or throw the original managed exception. The caller
performs no recursive managed rollback. Both published graphs survive, including
the child's untouched zero fields, and later reclaim after clearing the store.
This does not relax current source construction proofs or admit publication of
foreign capabilities. GC5 owns that coordinated source change.

## Verification and costs

Executed locally on machost, macOS arm64, Java 21.0.1, LLVM 23.1.0, SDK 27.2,
deployment 14.0. Explicit SDKROOT was necessary: the default selected SDK was
27.0 and correctly failed offline SDK identity verification. No dependency was
downloaded or rebuilt. Raw output stays ignored under `workspace/gc4-*`.

- The initial **108 checks** cover 27 native cases at O0/O1/O2/O3. Focused
  constructor/emergency and null-throw/resource follow-ups add **20 executions**,
  updating the changed cases and bringing the combined coverage to **30 distinct
  scenarios in all four modes**. Each report records its exact input hashes.
- Real managed primary/secondary throws cross nested source finally/defer code;
  captured values survive local reassignment, LIFO order is 2/1/3 and the original
  primary identity survives secondary failures. Native cleanup/resume exercises
  live and simultaneous wrappers. Catch/rethrow, cause/secondary/carrier cycles,
  trace refill/failure and eventual zero language/internal live counts pass.
- Actual bounded heap exhaustion performs zero implicit collections. Managed and
  immortal emergency roots survive explicit collection and clear afterward.
  Sidecar failure budgets 0 through 4 preserve nested occurrence order and permit
  allocation-free delivery/cleanup. One hundred repeated independent-anchor
  replacement/release cycles per mode reclaim the expected graphs.
- Missing/stale owner versions/masks, foreign segments, stale root ABI and
  valid-looking stale map data refuse before reclamation. Preparation scratch
  failure reports zero collections and balanced native resources on termination.
- All **148 GC3 native controls**, both structural ELF object checks, and **15
  exact compiler/production tests** pass. These preserve legacy allocation/free,
  mandatory safe/unsafe proofs, pool reuse, production traces/OOM, constructor
  rollback, finally ordering and deferred socket close. The updated GC4 compiler
  proofs also execute in each native generation, including later focused runs.
- O3 Node.read remains **8 bytes**, scalar **48 bytes**, byte-identical to GC3.
  Neither contains root registration, TLS, polls or helper calls. The production
  call/pool deterministic benchmark uses two alternating rounds of 32 million
  operations per variant. Warmed allocations and live delta are zero; normalized
  assembly is identical to GC3 and to handwritten cleanup. Deferred/nested medians
  are 39,641,500/36,493,500 ns (call) and 77,915,000/76,068,000 ns (pool). These
  bounded timings are not a throughput or tail-latency promise.
- The 16 GiB sampled process-group RSS guard and 60-second child limit remain in
  all GC native drivers. The initial GC4 matrix's largest sample is **1,058,544
  KiB**, including compiler/pass-build children. No resident-capacity claim.

Focused reproduction, from the checkout root with an existing verified GC2 SDK:

```sh
export JAVA_HOME=/path/to/jdk21
export SDKROOT=/path/to/qualified/MacOSX27.2.sdk
./scripts/test.sh --test 'GC4 runtime owners require exact exception layouts and effects'
python3 scripts/test-gc-lifetimes.py --sdk workspace/gc2-sdk \
  --llvm-home /path/to/llvm23 --java-home "$JAVA_HOME" \
  --output workspace/gc4-results
```

Use a fresh output directory. `--case published --case published-success --case
emergency-steps` selects the constructor/emergency follow-up; `--case null-throw
--case resources` selects the final follow-up. GC3 and the 15 exact production
selections, commands, report hashes and code fingerprints are in the manifest.
License/tracked-file, Python syntax, documentation consistency and diff checks
accompany the local commit. No full suite, push, hosted workflow or PR occurred.

## Exit decision and bounded handoff

The admitted experimental runtime's memory-only blocks no longer rely on source
destructors/finalizers. Explicit resource failure cleanup remains available when
managed/native allocation or collection preparation fails. **GC4's selected exit
criteria are met.** Current production behavior is unchanged.

Linux x86-64 retains its qualified GC3 ABI-1 closure but refuses GC4 owners until
native lifetime qualification. Linux arm64 retains unqualified engine/layout/root
gates; arm64e remains refused. General source output, full stdlib/trace API
integration, borrowed ByteView, generated bridge APIs, JNI reference retirement,
suspended JVM segments, shared libraries/two images and final SDK/IDK integration
remain explicit gates. No JNI path ran here, so no JNI balance or GC6 reentry
qualification is claimed. No unwinding through HotSpot frames is assumed.

**GC5 only:** integrate these layouts/owners and explicit resource cleanup into
the coordinated language/stdlib cutover, bind the actual root protocol into
native artifacts, preserve all paired tests and general defer behavior, then
implement the specified public collection intrinsic as part of that cutover.
Continue refusing unqualified targets/foreign owners. Do not begin bridge API
migration or self-hosting as a side effect. This task did not begin GC5 or GC6.
