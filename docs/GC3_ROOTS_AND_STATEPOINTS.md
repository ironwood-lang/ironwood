<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC3 effects, roots and statepoints

Date: 2026-11-04. **GC3 complete for the selected macOS arm64 experimental
closure. Production GC remains disabled.** Baseline: `64c11fa3`, preserving all
GC0/GC1/GC2 commits. Read with the [integration plan](BDWGC_INTEGRATION_PLAN.md)
and [compact evidence](gc-evaluation/gc3-2026-11-04-manifest.json).

## Pre-change review

Preserve production calloc/free, mandatory reclamation proofs in every unfreed
mode, source syntax, D051 cleanup ordering, D132/D133, current native generation
identities, and GC0 accounting. Use the GC2 SDK, ironwood-gc-engine-2 and
USE_MARK_BYTES. Do not collect any legacy allocation or permit reachable managed
deallocation in the development closure.

Affected producers and consumers: final typed call/dispatch graphs and type
initialization prerequisites feed collection effects; IrCfgRenamer and native
specialization/pruning copy those operations; LlvmEmitter consumes final effects
and physical reference types; LLVM optimization, trace finalization and late
statepoint generation determine final PCs and roots. Static/native slot owners
and target unwind/stack-map readers feed collection-time unmanaged scratch.
Effects are recomputed from the final graph rather than cached on cloned methods.
Unknown callback effects remain unknown and prevent experimental admission.

Selected checks: exact compiler tests for transitive effects, cloning,
initialization, dispatch and refusal; bounded native O0/O1/O2/O3 qualification
with compiler-derived roots, recursion, SSA merges, loops, derived bases,
stack regions, static roots, pending values and cleanup control flow. Pair
rooted survival with unrooted reclamation and stale/missing/invalid metadata
refusal before collection. Preserve source/class/archive reconstruction and
the six GC1 production controls. Inspect O3 noncollecting code, measure root
spills separately, and retain the 16 GiB sampled RSS and bounded child limits.
No full suite, hosted jobs, foreign/exception lifetime migration or cutover.

## Explicit supported closure and refusals

The supported image is the standalone executable built by
`scripts/test-gc-roots.py`, from `integration-tests/cases/gc3_closure.iron`,
the compiler-owned development IR in `GcRootTests`, the fixed native probe,
the scalar C++ exception fixture and the verified GC2 SDK. It is not a CLI
memory-model option or a supported way to compile an arbitrary application.
All managed allocation in that image uses GC2 descriptions and `iw_gc_new`.
The production runtime object and its calloc/free allocator are not linked.

The selection includes ordinary reference-bearing Node storage, Object's empty
constructor, source constructors/methods and interface implementation, mutable
managed statics, initialization prerequisites, recursive callers, SSA merges,
loops, pending returns/yields, reassigned deferred captures, physical field
projections and bounded explicit stack slots. C-held strong references consist
of the single declared native anchor. Other C locals at the native boundary are
not implicit roots. The probe uses returned references only after checking rooted
survival and never dereferences the deliberately reclaimed controls.

The source is validated under the current language and reclamation rules first.
Only this harness replaces the empty `Main.phase` body with collection IR.
It explicitly selects physical classes and success-only constructor bodies;
legacy destruction/rollback metadata is not projected into experimental types.
Those omitted paths are not reachable functions in this image. No production
class, constructor, destructor or ownership rule is changed.

`GcExperimentalClosure` inventories **every class and array in its input** through
IrAllocationLayout, verifies reference types, effects and supported operations,
and refuses legacy destruction, rollback, managed deallocation, native pointer
signatures and unknown calls. Passing the full source program fails rather than
silently filtering out Throwable, ByteView or other unsupported owners. The
standalone selection is explicit, not an omission policy for full programs.

Legacy Throwable storage, hidden carrier links, borrowed ByteView, native file
construction, tagged String/printing carriers, general native callbacks,
foreign/JNI roots and suspended segments remain unqualified. General source
throws/invokes are refused. Arbitrary aggregate managed allocas, escaping field
addresses and unrecognized root locations also refuse output or startup.
The wider GC2 allocation-family qualification remains valid; this checkpoint
does not claim native GC3 execution for every library allocation family.

The isolated exceptional case invokes a fixed helper that throws only an int.
Its catch disposes of that native exception **before** collecting in managed
cleanup. This qualifies invoke/landing-pad liveness, not production Throwable,
native unwind-wrapper roots, catch/rethrow identity or resource failure cleanup.
There is no attempt to unwind HotSpot frames or recover a suspended foreign
segment. Nonzero exceptional/foreign owner gates refuse startup.

## Effects and transformation ordering

IrGcCollectInstruction is a dedicated compiler-owned operation. GcEffects
computes a fixed point over current functions and initialization prerequisites.
Direct calls include constructors and generated cleanup/closure bodies; virtual
and interface calls union resolved class/array dispatch targets. Invoke calls
and all cleanup/exception CFG blocks participate. Missing bodies, unresolved
targets, native callbacks and unreviewed operations have both MayCollect and
unknown effects. Unknown never means noncollecting. Allocation itself is a leaf:
it does not schedule a collection, while separate initializer, constructor,
failure or cleanup edges can collect or prevent admission.

Effects are not cached on IrFunction or serialized into source artifacts.
Admission and emission recompute them after the real native specialization,
pruning and field passes. IrCfgRenamer preserves collection and root operations.
The exact compiler test checks actual enum argument clones retain collection IR
and effects. Native O3 qualification also exercises LLVM's partial inlining and
outlined recursive helpers. Every optimized function must retain its GC strategy
before statepoint rewriting; a lost strategy refuses compilation.

The development path uses the existing LlvmEmitter function/instruction lowering,
NativeBackend, NativeTarget and LlvmToolchain. Its order is:

1. Explicit closure admission, final effects and physical GC2 layouts.
2. Target triple/data layout before llvm-as, using the verified SDK profile.
3. The normal default O0/O1/O2/O3 optimization pipeline and tuning.
4. Existing optimized trace-metadata injection, with its function catalog retained
   for root-integrity attribution.
5. `rewrite-statepoints-for-gc,verify` in **every** mode, then stack-region checks.
6. llc object generation, relocation/unwind inspection, section retention and
   compiler-generated root-contract C data, then Clang compilation/native linking.

No IR optimizer runs after the statepoint rewrite. llc determines final machine
locations and return PCs. Debug/probe metadata and optimized trace identities
remain available; no continuous trace maintenance or new ordinary-call trace
registration is introduced. Normal production emission rejects development GC
IR and otherwise follows its existing pipeline.

## Managed values, stack storage and root ABI

Managed references and derived field addresses use LLVM address space 1; native
type/code/metadata addresses use address space 0. Field/array GEPs and memory
operations preserve this distinction. Field projection IR retains the typed base
and physical field identity, and cannot escape as an allocation base. Late LLVM
statepoints recover base/derived relationships and spill live bases. Nonmoving
collection permits copying bases into unmanaged scratch without relocating them.

IrGcRootSlot/Load describes real program stack storage. Slots are allocated and
zeroed at function entry; program stores/loads and explicit clearing retain their
ordinary meaning. Every collection-capable call carries a deoptimization bundle
`{1230455603, slot_count, native_slot_addresses...}` in addition to LLVM's SSA
gc-live list. A late verifier refuses an undescribed managed alloca or unsupported
aggregate. The O0 stack test has a deopt-only root, so SSA roots cannot mask an
omitted stack-slot reader. Clearing a slot permits reclamation during a later
collection in the same activation.

The separate **ironwood-gc-roots-1** development ABI uses LLVM stack-map version
**3**, 64-bit little-endian pointers, C/fastcc conventions 0/8 and flags zero.
Only qualified SP/FP indirect base spills, null constants and described native
slot addresses are admitted. Arbitrary register/location expressions refuse.
Map versions, counts, extents, reserved fields, constants, pairs, live-outs,
relocation forms, executable targets and unwind coverage are checked.

The compiler emits immutable static slot addresses, including managed statics
and initialization failure slots. Initialization states are primitive; legacy
failure objects remain refused. The native component supplies its own immutable
slot inventory. Startup requires exact table identities/counts for both, so a
shortened inventory cannot silently drop roots. Both inventories must be
provided even when empty.

The optimized trace catalog binds map functions to stable function identities.
A compiler-generated FNV-1a-64 map digest includes all map bytes, substituting
those identities for linker-relocated function pointers. Startup recomputes it
after ASLR/PIE relocations, before interpreting locations. Full SHA-256 input,
object and image hashes accompany the evidence. These are compatibility and
integrity checks, not a signed-artifact security scheme. Valid-looking stale
spill offsets, PCs or function relocations are refused, not merely malformed
headers.

## Reader, collection window and failure behavior

The reader has no fixture-specific frame/root count. Mach-O discovery validates
load commands and image ranges and finds the reader's own image through dyld.
ELF support uses the matching dl_iterate_phdr image, PT_LOAD permissions,
PT_GNU_EH_FRAME and named section boundaries. NativeBackend and runtime gates
currently admit only the qualified macOS arm64 profile; arm64e and both Linux
GC3 runtime targets refuse. ELF arm64/x86-64 object readers and relocation forms
passed structural cross-compilation checks, not native execution qualification.
Linux x86-64's GC2 engine/layout evidence is not GC3 root-reader evidence.

ld64 can discard an anonymous LLVM stack-map section despite a section-start
reference, leaving an empty placeholder. GcRootSections validates the object
and sets S_ATTR_NO_DEAD_STRIP. ELF uses SHF_ALLOC/SHF_GNU_RETAIN and the named
`ironwood_gc_maps` section; older linker integration must retain it explicitly.
Missing/stripped maps refuse load before a collection. No conservative fallback
is available.

Startup reserves reusable native scratch while the engine is disabled. At each
collection the reader unwinds from its one known native collection frame to the
explicit native boundary. **Every intervening frame** needs an exact return-PC
map and matching unwind function start. It never subtracts one from a return PC
or skips an unknown/unwalkable frame. Stack locations and region addresses must
fit the actual unwound frame range. Roots must be null or allocation bases;
interior, one-past and legacy malloc roots refuse preparation.

Only after the complete walk and both slot inventories succeed does the reader
register scratch and call the GC2 adapter's balanced full-collection window.
No managed allocation, user callback or native owner enumeration occurs with
collection enabled. It then unregisters and zeros scratch. Metadata, coverage,
capacity or domain failures occur before enabling collection. The development
harness returns startup refusal or terminates with exit 86 before resuming
managed code; its controls verify zero collections. Engine-internal failure
stops execution and is never reported as a successful collection. This does not
implement the future catchable production setup-OOM/Throwable path.

GC2's **ironwood-gc-engine-2**, **USE_MARK_BYTES** and macOS configuration hash
`6fd39c7be9fc6ab1d5d5aea2e07610efad3113b4d1da6679aa3f6bad086f48fa`
are unchanged. The new root component and root-contract payload are built
separately; no prepared engine payload or adapter interface changed. The normal
offline SDK verifier remains mandatory and rejects stale GC1 SDKs. Current
production native/bridge generation identities remain unchanged.

## Verification and measured costs

Executed on machost with Java 21.0.1, LLVM/Clang 23.1.0 and the GC2 macOS arm64
SDK profile, SDK 27.2/deployment 14.0. The committed manifest records exact input,
report, code, map and image hashes. Raw binaries, archives and logs stay ignored.

| Focused check | Result |
| --- | --- |
| Two exact GC3 compiler selections | Effects, initialization, dispatch, constructor/cleanup edges, actual enum clones, explicit admission, managed alloca/strategy refusal and source/class/archive LLVM parity passed. |
| Native closure | 148 checks: 37 scenarios across O0/O1/O2/O3. Rooted objects/graphs survive, numeric lookalikes do not, and clearing roots reclaims graphs. Recursion reached 18/17/17/16 mapped frames respectively; O3 partial inlining accounts for the difference. |
| Exceptional/pending state | Scalar native invoke/catch cleanup, constructors, source defer captures and pending return/yield values passed in all modes; actual legacy managed exceptions remain refused. |
| Nearby negatives | Missing/stale maps, ABI/version/width/endian/counts, valid-looking stale slot/function/PC data, shortened static/native inventories, scratch exhaustion, wrong walk boundary, interior/one-past/legacy-heap roots and unqualified exception/foreign owners refused before collection. |
| Target structure | Compiler-derived ELF arm64/x86-64 objects assembled, optimized, statepoint-rewritten and validated for unwind/absolute relocations/retention. No Linux native GC3 qualification claimed. |
| Production controls | Exact GC2 layout test and all six GC1 production controls passed, preserving allocator/free behavior, mandatory proofs, O3 allocation failure, traces and pool reuse. |
| Production hot baseline | Deterministic call/pool defer workloads, two alternating rounds of 32 million operations per variant: correct checksums, zero warmed allocations/live delta; normalized assembly byte-identical to GC0's recorded baseline and to handwritten cleanup. |
| Bounds | 16 GiB sampled process-group RSS guard and 60-second child limit. The main qualification's maximum sampled group RSS was 881,888 KiB, including compiler children. No resident-capacity or self-hosting claim. |

O3 `Node.read` is just a load and return. The scalar loop has arithmetic/branches
and no calls, spills, TLS, polls or root helpers. A collecting `Node.step` or
derived load uses one **8-byte SSA root spill**, a store/reload and a 32-byte
frame including ordinary FP/LR preservation and alignment. The explicit stack
region is separate program storage; O3 can retain an additional SSA spill.
These costs are collection-capable-call costs, not hidden ordinary-call traffic.

The production call/pool batch medians were deferred/nested
38,040,000/35,050,500 ns and 77,690,500/75,279,500 ns. Two rounds are a bounded
control, not a latency guarantee; the compiled kernels and GC0 fingerprints are
identical. GC8 still owns application throughput/memory qualification.

After source-format/comment cleanup, all four generated text sections and
stack-map sections were byte-identical to the qualification input. Focused
constructor/yield/stack-clear runs passed again; the legacy-heap refusal was
added and checked separately in all modes. No broader suite was repeated.

Reproduction from the canonical checkout, with an already verified GC2 SDK:

```sh
./scripts/test.sh --test 'GC3 collection effects and explicit closure fail closed' \
  --test 'GC3 late verification rejects missing strategies and undescribed stack regions'
python3 scripts/test-gc-roots.py --sdk workspace/gc2-sdk \
  --llvm-home /path/to/llvm23 --java-home /path/to/jdk21 \
  --output workspace/gc3-results --cross-objects
```

Choose a fresh output directory. `--level O3 --case stack` selects a bounded
follow-up; unknown case/level names fail. SDK verification and image generation
are offline. License/provenance, tracked-file, Python syntax, documentation
consistency and diff checks accompany the local commit. No hosted/paid job,
unfiltered suite, push or GitHub write was performed.

## Bounded handoff

**GC4 only:** migrate runtime-only Throwable/secondary/trace representations,
native unwind-wrapper and emergency-root lifetimes, memory-only sidecars and
explicit native-resource failure cleanup. Extend/version the owner/root protocol
with focused exceptional survival/reclamation tests. Preserve this closure and
noncollecting code baselines. Do not add source Memory.collect, remove free or
destructors, change bridge APIs, begin GC5/GC6 or restart self-hosting.

Carry the separate Linux native root-reader qualification gates forward. Linux
arm64 also still lacks GC2 native qualification. Broad stdlib roots, actual
exceptions, foreign reentry, libraries/two images, IDK assembly and application
performance remain later gates. GC3 completion is not production GC readiness.
