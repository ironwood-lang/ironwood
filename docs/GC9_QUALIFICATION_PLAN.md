<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC9 target and distribution qualification plan

Recorded before implementation, 2026-11-06. Canonical checkout and both origin
URLs verified; clean `codex/bdwgc-integration-plan` at `e16152d4`, following
`04f09a36`. Keep changes and commits local. Preserve subsequent human work.
No main integration, tags, hosted workflows, release or self-hosting work.

## Environments and budgets

| Target | Available execution | Initial inputs and gates |
| --- | --- | --- |
| macos-arm64 | Native Apple arm64 `machost`, Darwin 27, 32 GiB RAM | LLVM 23.1.0, GC7 SDK cached; root ABI 2 and managed ABI 3 qualified by GC6-GC8. Exact Apple SDK profile still required. |
| linux-arm64 | `armvm`, existing Colima `ironwood-tests`, Apple VZ on matching arm64 hardware, 6 CPUs / 8 GiB | Cached toolchain container `f879993337d6`, LLVM 23.1.0 / Java 21.0.10-internal / glibc 2.17 sysroot. Engine recipe exists; collection reader, native discovery and bridge gates refuse. No existing GC execution qualification. |
| linux-x86_64 | Cached container `6e36ffeb44aa` under Rosetta on `armvm`; emulated execution only currently discovered | Physical `x86host` GC8 records are historical evidence, not this milestone's execution. No configured remote endpoint discovered yet. Keep physical and emulated results distinct. |

The existing VM was stopped and is started without rebuilding its images or
changing the default Docker context. Use new ignored `workspace/gc9/` outputs,
isolated Linux compiler build mounts and unique package version labels. Do not
overwrite other SDKs, packages, VM caches or checkouts. Record full image IDs,
source hashes, Java/LLVM versions, SDK settings/receipt and payload hashes in
the result manifest before accepting any result.

Keep the existing 16 GiB summed process-group RSS guard and 60-second deadline
for native/compiler/JVM children. The VM additionally has its existing 8 GiB
memory ceiling. Bound preparation at 600 seconds and packaging/extraction at
900 seconds with the same RSS guard. Use at most four preparation jobs. Check
disk before each archive/copy/extraction: host has about 270 GiB free, keep a
30 GiB floor and cap new outputs at 20 GiB. VM data filesystem initially has
about 15 GiB free; keep an 8 GiB floor and cap additional VM-local storage at
4 GiB. Store large outputs on the host mount. Do not reclaim unrelated data.

## Pre-change contract and consumer review

Preserve BDWGC 8.2.12, USE_MARK_BYTES=1, explicit synchronous eager collection,
allocation that never collects, exact descriptors, nonmoving statepoints,
ordinary root ABI 2 / owner ABI 1, managed schema 2 / objects-gc-v1 with roots 3 /
owners 2, D051 and D132/D133. Unknown native/resource effects stay conservative.
arm64e remains unsupported. Do not use another architecture's unwind behavior
as evidence for Linux arm64.

Potential implementation touches the collection-time ELF root walker, saved
segment capture, dispatcher boundary capture, exact SDK discovery and bridge
admission. Their consumers are ordinary native programs, exception/defer/init
paths, runtime owner enumeration and real JNI callback/reentry. Inspect actual
ELF AArch64 unwind CFA, FP, return-PC and SP map locations before choosing its
reader. No steady-state source bookkeeping or shared semantic-analysis change
is planned. Revisit this review if scope expands. New admission requires focused
direct evidence, not an allowlist edit alone.

## Focused selections and output modes

* Prepare and offline-verify SDKs with `prepare-gc.py`, reusing pinned source.
  Pair valid source/configuration/payloads with missing, altered, stale and
  wrong-target controls in `test-gc-inputs.py` and packaging checks.
* `test-gc-layouts.py`: layouts, arrays, sidecars, metadata, failure and overflow.
  Include real C/LLVM layouts, numeric lookalikes and exact overflow recovery.
* `test-gc-roots.py`: actual optimized callers, statics/init/cleanup, exceptions
  and nearby missing/corrupt maps/unwind controls at O0/O1/O2/O3.
* `test-gc-lifetimes.py` and selected `test-gc-integrated.py` cases: exception
  owners, pending values, construction publication, resources, no_collect and
  bounded OOM. Select affected cases rather than historical full matrices.
* `test-gc6-segments.py`: O0/O1/O2/O3 real checked-JNI nested A/Java/A and
  A/Java/B/Java/A, saved state and boundary corruption. Follow with objects,
  callbacks, metadata and independent production images as affected.
* Inspect actual Mach-O/ELF sections, relocations, protections, exports/imports,
  dependencies and PIE/ASLR. Deployed executables run outside checkout/IDK
  with documented dependencies and without Java/LLVM.

## Distribution routes

Use `package.sh` and `package-idk.sh`, then their package smoke runners after
inspection. They must not invoke an unfiltered compiler suite. Add a focused
selector if needed, preserving default release checks. Host compiler packages
need external supported JDK and LLVM plus platform native link tools. IDKs
bundle Java/LLVM/pass and GC/TLS/Linux bridge support SDKs, but macOS still needs
Apple SDK/tools and honors invalid explicit SDKROOT/DEVELOPER_DIR refusals.

Install/extract into paths with spaces. Clear checkout Java/LLVM/runtime/stdlib/
GC/TLS/bridge overrides for IDK execution and restrict PATH. Verify all separately
copied SDK payload hashes before and after first-launch relocation and later
launches. Check source/class/archive reconstruction, explicit collection, defer,
exceptions/resources, local TLS fixtures, production bridge loading, handles,
registrations, collecting reentry and independent images. Keep per-target
configuration identities during assembly and use matching producer/Javadoc tools.

Audit final staged source, recipes, manifests, licenses/notices and native
payloads together, including BDWGC, OpenSSL/CA and Linux GCC support obligations.
Normal link and offline verification must not download. Missing/stale/altered
inputs must refuse preparation/package/link/load, including scalar-only images.

## Acceptance boundary

GC9 stays incomplete until all three official targets and both output modes
have direct focused execution and all required local distribution paths pass.
Record unavailable environments and failing exit criteria without weakening
gates. Preserve historical GC8 limits. GC10 receives only a bounded handoff;
do not start its documentation audit, self-hosting, I1 or I2.

## Distribution review amendment

Direct relocation exposed two cold packaging faults: symlink SDK selections
were copied as links, and Conda-pack rewrote the exact sysroot receipt. Preserve
resolved SDK trees and the original receipt bytes. A restricted macOS IDK link
also exposed pass compilation through the bundled linker rather than selected
Apple tools. Linux pass compilation currently consumes host C++ headers.

Prepare the unchanged first-party nonmoving pass once in an IDK, outside Conda
relocation, with a source/payload/LLVM/host manifest and relative LLVM lookup.
Normal IDK linking must verify and load it, refusing incomplete or stale payloads.
Source/host compiler packages retain pass compilation with documented external
C++ build inputs and selected Apple tools. The source hash remains bound by the
GC SDK; no memory ABI or C++ pass semantics change. Pair relocated native/bridge
links with missing/altered pass refusals. Record producer tool hashes separately
from relocated Conda tool bytes. Recheck native dependency paths before deciding
whether ordinary Linux outputs need the existing private support delivery.

Actual Mach-O inspection found retained maps in a writable custom segment.
Move their object section into dyld-protected `__DATA_CONST`, preserving map
bytes, relocations, return PCs, root/owner versions and instruction bodies.
Startup must require that segment's read-only-after-fixups flag. Pair valid
collection and a forked write-protection probe with a re-signed image missing
that protection. Rebuild all source-bound SDK profiles, then recheck changed
Mach-O roots/owners/reentry and the unchanged ELF path with refreshed identities.
This is metadata retention/protection, not hot instruction lowering.

The new write probe changed C frame layout and reproduced compact-unwind
coalescing of `main` and the ordinary native boundary. Require retained Mach-O
function starts and cache the exact declared boundary extent at startup. Keep
exact managed return-PC/function matches; no unmanaged frame may be skipped.
Pair collection under the coalesced encoding with wrong-boundary, stripped and
malformed function-start controls, plus ABI-3 suspension/reentry. ELF keeps its exact FDE
identity rule. No per-call bookkeeping or conservative fallback is introduced.

## Native x86-64 continuation, 2026-11-07

The maintainer now authorizes SSH to `x86host` inside `~/temp/Ironwood-GC/`.
It is the physical Intel Xeon E-2288G host used by the historical GC8 record,
with Ubuntu 18.04.6, Linux 4.15, glibc 2.27, about 16 GiB RAM and no detected
virtualization. Its inherited affinity covers eight logical CPUs. About 95 GiB
is free. Keep at least 50 GiB free and limit new staging, packages and evidence
to 16 GiB. Check before archive creation, extraction and copying. Preserve all
unrelated caches, prior evidence and the old remote checkout's two script edits.

Export local `e5cabbed` into a new ignored source staging directory under that
checkout; verify the archive and per-file hashes. This is not another checkout,
branch or Git worktree. Record subsequent exact overlays separately. The parent
remote Git revision is not the staged source identity. Keep all commits local
in the canonical Mac checkout on the existing migration branch; no push/main
integration or release is authorized.

Reuse verified cached LLVM 23.1.0, Java 21 and pinned Temurin 23, collector source,
TLS and support inputs first. Prepare missing IDK dependencies in task-owned
storage through pinned recipes. Do not change shared toolchain prefixes. Rebuild
the GC SDK from current source; preserve exact receipt/configuration binding.
If another profile is required, review cold discovery, native linking and bridge
metadata consumers, pair exact acceptance with missing/mixed/stale refusals and
execute the profile before reporting acceptance. No runtime or analysis change
is planned. Preserve the prior GC9 contracts and arm64e refusal.

Run current root/exception/suspended-reentry O0-O3 selections, focused layout,
overflow, OOM/resources, anchors, registrations, JNI cleanup, independent-image
and invalid metadata controls. Then exercise actual host packages and restricted
relocated IDKs, preparation/package/link/load failures, local TLS/support smoke
paths and final redistribution contents. Build the third common-content jar
with matching pinned producer/Javadoc tools; assemble all three target identities
and execute on all three matching architectures. Reuse the passing ARM evidence
when production source is unchanged; recheck affected consumers after any fix.

Retain 16 GiB group RSS and 60-second native/compiler/JVM children, 600-second
preparation and 900-second package/extraction stages. Inspect runners before use;
no unfiltered suite, hosted workflow, GC10, self-hosting or I1/I2 work is included.

The native host-package smoke exposed an over-restrictive test PATH: it removed
the explicitly supplied external lld while selecting external Java/LLVM. Keep
the host caller's tool PATH, with all compiler/SDK overrides still cleared and
Java/LLVM/support inputs explicitly selected. IDK checks keep `/usr/bin:/bin`.
Pair the recorded missing-linker refusal with a successful explicit-tool PATH,
and rerun affected host smokes on all three targets. No product linker, SDK
profile validation or runtime behavior changes with this test correction.

Final version inspection found that host packaging copied the repository's
VERSION even when build.sh used an explicit version override. Copy the built
compiler's authoritative VERSION resource into the host stage. Pair the
recorded override mismatch with a rebuilt matching archive and assert launcher
output agrees with packaged VERSION in the focused driver. Check unchanged
default-version ARM archives and IDK version output separately. This changes
packaging metadata only; no compiler/runtime/SDK payload needs a new identity.
