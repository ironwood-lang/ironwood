<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC1 engine preparation

Date: 2026-11-04. GC1 complete, based on GC0 commit `82c52506`.
Production GC remains disabled. GC2 has not begun.

## Pre-change review

The production calloc/free boundary, source syntax, standard library, mandatory
reclamation proofs, native generation constants and D132/D133 remain unchanged.
GC1 adds an isolated C adapter and explicitly prepared dependency SDK, using the
existing runtime source tree, Clang/static-link pipeline and package copy paths.
No compiler analysis, layout or root-generation consumer changes. GC2 and GC3
remain separate gates. GC0's collection-time kind-filtered accounting is frozen.

Affected consumers are dependency preparation/verification, standalone native
fixtures, host source packages and optional prepared IDK inputs. Preparation may
download; verification and linking must work offline and fail closed. Current
programs must build without this SDK and contain no collector dependency.

Focused checks will pair valid SDK relocation with missing/altered libraries,
headers, adapter and configuration, wrong targets and identities; native root
scratch with rejected collector-owned scratch; private images with deliberately
exported collector symbols; rooted graphs with unrooted graphs; explicit eager
collection/reuse with startup, ordinary allocation and exhaustion controls.
Collection-time enumeration excludes sidecars and collector administration.
Memory experiments retain the sampled 16 GiB RSS guard and 60-second child limit.

Representative unchanged production tests are exactly:

- `runtime allocation and deallocation ABI works`
- `safe free accepts local allocation and ended aliases`
- `safe free rejects live aliases and escaped allocations`
- `catchable allocation failure boundaries run at O3`
- `trace bookkeeping preserves allocation counts and safe free`
- `pool release helpers preserve borrows and allocation-free reuse`

Packaging verification will inspect the affected staged inputs after relocation
and compile/run a current source program without the GC SDK. License, provenance,
tracked-file and diff checks are required. Linux recipes and ELF policies need
their own target qualification; macOS evidence cannot stand in for Linux runs.

## Prepared engine and exact identity

[`packaging/bdwgc.json`](../packaging/bdwgc.json) preserves the evaluation
manifest's configure/CFLAGS tuples verbatim. BDWGC **8.2.12** release archive
SHA-256 is
`42e5194ad06ab6ffb806c83eb99c03462b495d979cda782f3c72c08af833cd4e`.
No collector source is patched. Configure uses:

```sh
--disable-shared --enable-static --disable-threads --disable-parallel-mark
--disable-thread-local-alloc --disable-docs --disable-gcj-support
--disable-java-finalization --disable-disclaim --disable-throw-bad-alloc-library
--disable-register-main-static-data --disable-dynamic-loading
--enable-large-config --with-libatomic-ops=none
```

The evaluated CFLAGS are `-O3 -DEAGER_SWEEP -DGC_DISABLE_INCREMENTAL
-DSTACK_NOT_SCANNED -DDONT_ADD_BYTE_AT_END`. Build-only additions are `-fPIC
-fvisibility=hidden -DGC_NO_VISIBILITY`, explicit native target/sysroot flags
and a preparation-directory prefix map. LLVM/Clang is pinned to **23.1.0**.
`SOURCE_DATE_EPOCH=0`, `ZERO_AR_DATE=1` and LLVM deterministic archives remove
timestamps. The adapter uses C11 with `-Wall -Wextra -Werror -Wpedantic`.

The tested canonical `gc.config.sha256` is
`07d2aad5443852aa2ddb98121c3373e9eb8ea2b1be0f655ae658e171fb3dbce3`.
`configuration.json` is sorted-key, compact UTF-8 JSON, without trailing newline.
It includes the exact engine/build flags, adapter ABI `ironwood-gc-engine-1`,
reserved native ABI `ironwood-native-gc-1`, layout/root versions **1/1**, native
triple, full data layout, 64-bit little-endian pointers, LLVM stack-map version
**3**, LLVM version, platform SDK identity, startup policy and static/private
symbol policies. Actual SDK file hashes are separate in `build.json`, including
collector/adapter libraries, adapter object/source, every public header,
generated build/configuration headers, source archive, notices and recipe.
Fixture manifests additionally hash the exact object, libraries, source,
SDK manifest, export policy and final image. Current compiler/generation
constants are unchanged; these future identifiers only describe isolated inputs.

Offline verification compares the complete canonical configuration to the
native toolchain/platform, checks mandatory inputs and exact file inventory,
compares current adapter/recipe pins, hashes every payload and inspects actual
archive object formats, architectures and symbol visibility. Missing SDKs,
metadata, altered inputs and incompatible configurations fail before linking.
This is local integrity/compatibility validation, not a signed artifact scheme.
There is no compiler fallback, implicit download or automatic preparation.

## Pinned hook and license audit

The audited source is the checksum-verified archive, not an installed system GC.
`mark_rts.c:890-969` retains explicit root ranges and collector-internal roots;
`STACK_NOT_SCANNED` removes the call to `GC_push_regs_and_stack` at lines 952-954.
Its unused static helper definition still exists in preprocessed source.
`alloc.c:331-333` also removes stack-size accounting; `misc.c:277-419` removes
stack clearing. None of these is a production stack-map reader.
`GC_DONT_REGISTER_MAIN_STATIC_DATA` and `IGNORE_DYNAMIC_LOADING` disable main
static/library discovery. Preparation checks effective preprocessor macros,
absence of thread/parallel/interposition definitions and absence of the
stack/register root call, recording `configuration-audit.json`.

`include/private/gc_priv.h:380-392` sets `EXTRA_BYTES` and `MAX_EXTRA_BYTES` to
zero under `DONT_ADD_BYTE_AT_END`. This omits the extra byte otherwise used to
retain an object from a one-past-end pointer. Every fixture strong root is an
allocation base; a derived/one-past pointer cannot be its only lifetime evidence.
GC3 must preserve bases through LLVM optimization and GC6 must enforce foreign
boundaries. GC1 does not grant arbitrary C pointers production root coverage.
The public `gc_mark.h` kind/bitmap/enumeration interfaces are used only behind
the adapter, with fixed fixture kinds. No production descriptors are introduced.

Actual `README.md` copyright/warranty/permission text and source/header notices
were reviewed, including `alloc.c`, `mark_rts.c`, `misc.c`, `reclaim.c`,
`malloc.c`, `mallocx.c`, `os_dep.c`, `gc.h`, `gc_mark.h` and private headers.
The core notice-preserving permission grant differs from Ironwood's default;
GNU build helpers retain their own terms/exceptions. Full unmodified source
and verbatim source/header notices travel together in the SDK. The ledger and
third-party notices record `LicenseRef-BDWGC-8.2.12` and their exact locations.
Adapter and fixtures are original code, without collector implementation
translation or OpenJDK/HotSpot imports.

## Adapter and private image boundary

The six private adapter operations are configuration identity, initialization,
allocation, native scratch roots, explicit full collection and snapshot. Four
bounded fixture kinds distinguish pointer-free language cells, two-reference
language cells, reference-bearing sidecars and pointer-free trace storage.
They are qualification shapes, not GC2's production layouts.

Initialization checks the expected hash and refuses `GC_*` environment overrides
before touching the engine. It sets `GC_set_dont_precollect(1)` and
`GC_set_finalize_on_demand(1)`, initializes and disables collection. Allocations
remain disabled even through bounded exhaustion retries. Only `iw_gc_collect`
balances `GC_enable(); GC_gcollect(); GC_disable();`, and verifies that exactly
one collection completed. Root preparation precedes that window. The interface
has no user callback, finalizer registration/invocation, enable/disable, free or
realloc entry point. Engine-internal failure is not reported as collection
success. No managed allocation or user code runs in the enabled window.

Native caller-owned root scratch uses matched libc allocation/release; managed
scratch is rejected. Collector storage is never passed to libc free/realloc.
Single caller/external serialization remains a caller obligation. Collection
then enumerates marked bases under the allocation lock, after eager sweeping
and before allocation resumes. Only designated language kinds count. Sidecars
and four collector free-list arrays are excluded. Between collections, live
count is the last collection count plus successful subsequent language
allocations. No per-object ledger or finalizer accounting was introduced.

Archive inspection found **374 collector** and **6 adapter** externally bound
definitions, all private. No malloc/free/calloc/realloc replacement or worker
definition exists. The final Mach-O image statically contains the real engine;
`GC_init`, `GC_gcollect` and `iw_gc_collect` are local symbols, recorded as former
private externals. `MH_TWOLEVEL` is present and the export trie contains exactly
`_ironwood_gc1_probe`. No collector imports remain. Mach-O uses hidden objects,
an explicit export allowlist and two-level namespace. ELF uses hidden objects,
`local: *` version-map binding, `-Bsymbolic`, `--exclude-libs,ALL` and `-z defs`.
Both policies reject accidental public definitions, including names outside
the collector prefix. No load-order or `RTLD_LOCAL` assumption is used. Full
two-image runtime execution, process-collector collision and anchor isolation
remain GC6 work; symbol inspection does not claim that qualification.

## Concrete target setup and focused results

Executed host: **machost**, macOS arm64, SDK **27.2**, deployment target **14.0**,
triple `arm64-apple-macosx14.0.0`, Homebrew LLVM/Clang **23.1.0**, Python **3.14.7**,
GNU Make **3.81**, Java **21.0.1** for the six production tests. The selected CLT
SDK's `xcrun --show-sdk-version` failed on this host; explicit `SDKROOT` selects
the installed Xcode SDK. No system tool selection was changed.

```sh
export SDKROOT=/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk
python3 scripts/prepare-gc.py --prefix workspace/gc1-sdk-final \
  --llvm-home /opt/homebrew/opt/llvm@23 \
  --cache workspace/gc-evaluation-reproduce-2026-11-04
python3 scripts/prepare-gc.py --verify --prefix workspace/gc1-sdk-final \
  --llvm-home /opt/homebrew/opt/llvm@23
python3 scripts/test-gc-engine.py --sdk workspace/gc1-sdk-final \
  --llvm-home /opt/homebrew/opt/llvm@23 --output workspace/gc1-qualified
```

Choose new output/prefix directories. The cached archive is checked against the
pin; without it, only explicit preparation downloads the pinned release URL.
An additional clean preparation at `workspace/gc1-sdk-reproduce` produced
**byte-identical payloads and build manifest**, including libraries and adapter.

| Focused control | Result |
| --- | --- |
| Startup, ordinary allocation, exhaustion with two retries | Zero collections; null after 521,344 64-byte cells in a 32 MiB maximum heap |
| Explicit eager sweep and reuse | One collection, then 200,000 successful replacements without heap growth or deferred reclaimed bytes |
| Language/sidecar accounting | Language counts `4009,4,0,17,0,17,0,17,0`; sidecars `12,1,0,0,0,0,0,0,0`; four administration arrays excluded |
| Between-collection count | 4,009 live plus 123 successful allocations reports 4,132; total remains cumulative |
| Root/domain controls | Explicit graphs survive; unrooted/static/stack/native-scratch-only graphs and pointer-shaped numeric fields do not retain targets; zeroed storage verified |
| Nearby negatives | 25 rejected cases: missing/altered metadata, libraries, adapter and headers; wrong hash, target, ABI, layout/root versions, isolation; noncanonical/omitted metadata; actual wrong-target archive; public archive/image symbols; unavailable managed-free API |
| Environment override | Rejected before initialization, zero collections |
| Offline relocation | Verification and fixture link succeed with download API forbidden, including a path containing spaces |
| Current production behavior | All six exact tests in the pre-change selection passed; no compiler/stdlib/current allocator file changed |
| Package smoke | Host archive with whole optional SDK validates after relocation; removing SDK still permits current basic class compilation, O3 link and expected exit 42, with calloc/free imports and no collector symbols/dependency |

The extra administration kind accounts for the small exhaustion-count difference
from GC0; the maximum heap and zero-collection policy are unchanged. The committed
[compact evidence](gc-evaluation/gc1-2026-11-04-manifest.json) records actual
payload, input, output, table and log hashes. Raw logs, sources, archives and
binaries remain ignored under `workspace/gc1-*` and `dist/`.
The 16 GiB sampled RSS guard and 60-second per-child limit are preserved. These
small fixed-heap experiments are not resident-memory or throughput claims.

Linux arm64/x86-64 recipes require a native host, LLVM **23.1.0**, the existing
IDK **glibc 2.17** Conda sysroot (`sysroot_linux-aarch64` or `sysroot_linux-64`),
and lld. They use explicit `aarch64-unknown-linux-gnu` or
`x86_64-unknown-linux-gnu` targets with the selected sysroot and the ELF policy
above. Example on the matching host, after preparing the existing IDK toolchain:

```sh
python3 scripts/prepare-gc.py --prefix workspace/gc1-linux-sdk \
  --llvm-home "$IRONWOOD_IDK_TOOLCHAIN_HOME" --target linux-arm64
python3 scripts/test-gc-engine.py --sdk workspace/gc1-linux-sdk \
  --llvm-home "$IRONWOOD_IDK_TOOLCHAIN_HOME" --target linux-arm64 \
  --output workspace/gc1-linux-qualified
```

Use `linux-x86_64` on x86-64. The subsequent
[Linux x86-64 qualification](GC1_LINUX_X86_64_QUALIFICATION.md) passed native
engine/ELF controls, reproducible preparation, offline relocation and the six
production tests. Linux arm64 and full IDK/Conda archive assembly remain target
qualification gates; no hosted or paid validation ran. The shared packaging
helper was exercised with source inputs only and with a whole verified SDK.
IDK integration uses that same helper,
excludes `ironwood-gc/*` from Conda relocation and conditionally lists BDWGC in
`THIRD-PARTY-PACKAGES.tsv`. The actual IDK inventory writer passed with both
bundled and source-only manifests. Host/IDK source manifests explicitly record production
disabled and whether an SDK was bundled. No current bridge artifact acquires GC.

## Remaining gates and bounded next task

GC1 prepares and qualifies the engine boundary on machost and x86host; it does
not qualify production roots, layouts, Linux arm64 behavior, two images, reentry
or performance. The Linux follow-up normalizes temporary archive paths in audit
text hashes and adds a relocation regression, preserving actual library hashes.
License/provenance, tracked-file, Python/shell syntax and `git diff --check`
checks passed. Current native generation constants and mandatory free proofs
are untouched. No push, GitHub activity or unfiltered suite was performed.

**GC2 only:** describe and qualify all production allocation layouts and internal
kinds, large/overflow maps, arrays/String tails, initialization/zeroing, metadata
OOM and mark-stack recovery without conservative fallback. Extend the frozen
collection-time accounting audit and design allocator-domain-safe unpublished
file construction. Continue using isolated fixtures. Do not enable ordinary
allocation, source `Memory.collect()`, root generation, language cutover,
destructor removal or bridge API migration. Carry forward the independent
Linux arm64 qualification gate and require each target before using its inputs.
