<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC3 Linux x86-64 native qualification

Date: 2026-11-04. **The selected GC3 standalone closure is qualified natively on
Linux x86-64. Production GC remains disabled.** This extends the
[GC3 completion record](GC3_ROOTS_AND_STATEPOINTS.md), with separate
[compact evidence](gc-evaluation/gc3-linux-x86_64-2026-11-04-manifest.json).

## Pre-change review

The follow-up to GC3 commit `6860bdfc` qualifies the same standalone experimental
closure on native Linux x86-64. Ordinary allocation, mandatory reclamation
proofs, D132/D133 hot-path contracts and the GC2 `ironwood-gc-engine-2` SDK with
`-DUSE_MARK_BYTES=1` must survive unchanged. Linux arm64 remains unqualified.

The affected consumers are the experimental NativeBackend target gate, ELF
stack-map admission and integrity contracts, collection-time target unwinding,
and the bounded native qualification driver. Trace attribution must retain its
existing ordering before late statepoints. No source syntax, public bridge,
exception lifetime or production memory-model change is in scope.

Qualification pairs rooted survival with unrooted reclamation and tests missing,
stale or invalid maps, wrong root bases, scratch exhaustion, incomplete native
boundaries, foreign/exception owners and legacy heaps. Native system unwind
registers and exact return PCs must be verified, with no skipped frames or
conservative fallback. Source/class/archive parity and all four optimization
modes use the existing compiler pipeline.

Focused verification selection: the two exact GC3 compiler tests; the 37-case
native root harness at O0/O1/O2/O3; optimized noncollecting and collecting code
inspection; the existing GC2 layout, allocation ABI, safe/unsafe free,
allocation-failure, trace allocation and pool reuse controls. Shared reader
changes also require focused macOS arm64 regression evidence. License, tracked
file, consistency and diff checks finish the change. No full suite is authorized.

The remote task uses a source export under ignored `workspace/gc3-linux/`,
preserving the existing checkout and its two modified scripts. Bound the whole
task directory to 8 GiB, retain 20 GiB free disk, and retain the native harness's
16 GiB process-group RSS guard and 60-second child timeout. Reuse the qualified
GC2 SDK and pinned tools without rebuilding or downloading dependencies.

The target review added two Linux-specific controls to the original 37:
stripped managed unwind tables and removed load-time RELRO protection. The
39-case matrix covers both failure modes in every optimization level. Actual
program-header checks protect the final linker contract, rather than assuming
that a section name implies read-only relocation storage.

## Qualified target and implementation

NativeBackend admits only the pinned LLVM 23.1.0 Linux x86-64 triple and exact
data layout, alongside the previously qualified macOS arm64 profile. Linux
arm64 remains refused. The explicit source/typed-IR closure, GC2-described Node
graph, static initialization slots, declared native anchor and scalar exception
fixture are the same as in the original GC3 record. There is no ordinary-program
activation, CLI memory model, public collection syntax or new callback escape.

The compiler pipeline remains typed IR, LLVM assembly, optimization, optimized
trace metadata, late statepoints, verification and llc. ELF trace identities
use their native spelling without Mach-O's leading underscore. The object
reader validates ELF64 little-endian sections, map versions/counts, absolute
function relocations and executable symbol storage. Section-symbol/addend
relocations must resolve exactly one named function; unknown, ambiguous or
interior targets refuse. The normalized whole-map digest binds all locations,
counts and return-PC offsets to the optimized function catalog.

The compiler generates `root-contract.c` and, for ELF, `root-contract.ld`.
The latter retains the `ironwood_gc_maps` input in the final `.data.rel.ro`
output. Writable input storage permits absolute function relocations in PIE;
the loader then protects the actual PT_GNU_RELRO range. Both the harness and
runtime check that this range covers the map. The final image has an unwind
header, no text relocations and no writable/executable load segment. Only the
qualified LLVM linker and this executable link contract are admitted.

The Linux reader uses the platform's `_Unwind_Backtrace`, `_Unwind_GetIP`,
`_Unwind_GetRegionStart` and `_Unwind_GetCFA`. A bounded native probe checked the
suspended-frame stack pointer convention. Each managed frame must match both
the exact return PC and function start. Root scanning waits for the next frame
to supply the actual parent SP; it does not infer bounds from a saved frame
size. RBP is recovered only for locations that use it. The known collector
frame and declared native boundary are the only native frames admitted.
Missing unwind coverage stops before collection, with no skipped frame or
conservative scan. PIE was exercised with native ASLR enabled.

Reusable scratch, exact static/native inventories, allocation-base validation,
collection-disabled preparation, unregister/clear behavior and exit-86 failure
semantics remain the GC3 root ABI. Engine ABI **ironwood-gc-engine-2**, root ABI
**ironwood-gc-roots-1**, all C interface fields and prepared payloads are
unchanged. New ELF contracts are generated with their object, not supplied by
an old SDK. The existing GC2 SDK verifies unchanged, including USE_MARK_BYTES;
the older GC1 SDK is rejected. No dependency was downloaded or installed.

The scalar exception helper declares the platform Itanium catch entry points
directly, so it needs no C++ development headers. The fixed int exception still
ends before managed cleanup collects. Linux links the C++ and unwind libraries
already installed with the pinned tool prefix; their hashes/package identities
are recorded. This does not qualify production Throwable, unwind-wrapper
owners, foreign/JNI segments, callbacks or suspended managed/native crossings.

## Direct native results

| Focused check | Result |
| --- | --- |
| Compiler-derived native roots | **156** checks: 39 cases at O0/O1/O2/O3, comprising 68 positive and 88 precollection refusal checks. Recursion reaches 18/17/17/16 mapped frames. |
| Root coverage | Allocation, caller chains, recursion, SSA merges, loops, derived/base values, stack regions and clearing, constructors, initialization, interfaces, pending return/yield and deferred captures, static and native roots survive and then reclaim unrooted graphs. Numeric lookalikes are not roots. |
| Exception boundary | Isolated int throw/catch cleanup preserves managed liveness in every mode; actual legacy exception and foreign owners still refuse. |
| Metadata failures | Version/width/endian/count/ABI/location/PC/slot/function corruption, missing maps, shortened inventories, scratch exhaustion, interior/one-past/legacy-heap roots and wrong boundaries refuse before collection. Stripped unwind tables and removed RELRO protection also refuse in every mode. |
| SDK identity/offline use | All 31 payload files verify after relocation to a path containing a space with network download disabled. Eight focused O3 native cases pass with that SDK; the stale GC1 SDK refuses. |
| Production controls | The exact GC2 layout test and six existing production controls pass, preserving allocation/free ABI, safe and unsafe free proofs, O3 allocation failure, trace allocation counts and pool reuse. |
| macOS regression | Both exact GC3 compiler tests, all 148 native cases, both ELF structural checks and nine focused O3 follow-ups pass. O3 program text and stack-map bytes are identical to the original qualified GC3 objects. |
| ELF isolation | PIE, actual RELRO map coverage and GNU unwind header pass at every level. No text relocations or dynamically exported collector definitions. The executable requires GLIBC versions no newer than 2.17. |

The exact compiler selections are
`GC3 collection effects and explicit closure fail closed` and
`GC3 late verification rejects missing strategies and undescribed stack regions`.
The native compiler driver also runs those proofs and source/class/archive
parity before producing all four optimization modes. Production controls are
the same seven exact selections in the original GC3 manifest; no full suite ran.

O3 `Node.read` is a **5-byte** load/return with no frame. The **48-byte** scalar
loop has no calls, stack spills, TLS, polling or registration. A collecting
`Node.step` is **16 bytes**, with one **8-byte SSA root spill**, one store/reload
and an 8-byte stack-map frame. Its push/pop supplies ordinary call alignment.
These are collection-capable-call costs, not overhead on noncollecting paths.
Current production lowering was unchanged, and deterministic pool reuse still
passes without warmed allocations. The original GC0 macOS call/pool instruction
fingerprints remain preserved by the GC3 record.

## Source preservation and resource bounds

The remote parent stays at `e113c12f` on the designated branch, with correct
fetch/push origin URLs. Its two human-modified scripts, complete diff and status
remain byte-for-byte unchanged. No branch, worktree, history or remote Git
activity was created. A plain archive of GC3 commit `6860bdfc` supplies the source
export; six reviewed compiler/runtime/harness files overlay it. The manifest
records the archive hash, every overlay hash and comparison of all archived
files. Raw harness reports name the enclosing old Git parent; they do not
identify the exported source. The archive and overlay identities do.

Task storage grew from **541,577,216** to approximately **660,549,632 bytes**,
about **0.62 GiB** in total, with over **103 GiB** free. The 8 GiB task cap and
20 GiB free-space reserve were never approached. The final matrix's largest
sampled process-group RSS was **703,720 KiB**, below the protective **16 GiB**
guard. Native children retain a 60-second limit and outer commands 900 seconds.
Sampling is not a continuous maximum or a resident-capacity claim. Sources,
SDKs, binaries, archives and raw logs stay in ignored task storage.

## Reproduction and bounded handoff

Use an exact source snapshot with the recorded overlays and the qualified GC2
SDK. Select the pinned JDK/tool prefix and put the existing task-local lld first
on PATH. Run checks sequentially against a source export: rebuilding the compiler
while a harness loads classes can invalidate that run.

```sh
./scripts/test.sh --test 'GC3 collection effects and explicit closure fail closed' \
  --test 'GC3 late verification rejects missing strategies and undescribed stack regions'
python3 scripts/test-gc-roots.py --target linux-x86_64 \
  --sdk /path/to/gc2-sdk --llvm-home "$IRONWOOD_LLVM_HOME" \
  --java-home "$JAVA_HOME" --output workspace/gc3-roots-linux
```

Use a fresh output path; `--level` and `--case` select bounded follow-ups.
License, tracked-file, source/hash consistency and diff checks accompany the
local commit. No push, GitHub activity, hosted job or self-hosting restart.

Stop at GC3. The next bounded task is **GC4 runtime-only lifetimes and failure
cleanup**, including Throwable/secondary/trace storage, native unwind wrappers,
emergency roots and resource failures. Preserve all legacy-layout refusals and
the closed Linux arm64 gate. Broad library roots, multi-image integration,
foreign reentry and production GC readiness remain later qualifications.
