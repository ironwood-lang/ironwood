<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC1 Linux x86-64 qualification

Date: 2026-11-04. The isolated GC1 engine is qualified on **x86host**, in addition
to the earlier macOS arm64 run. This historical profile predates
[GC2](GC2_LAYOUTS_AND_QUALIFICATION.md); its changed collector configuration
requires fresh Linux qualification. Production allocation,
mandatory reclamation proofs, source semantics and generation constants remain
unchanged. This follow-up closes the Linux x86-64 engine/build/ELF gate; Linux
arm64, full IDK assembly and GC6 two-image execution remain separate gates.

## Setup and bounded storage

The maintainer supplied SSH access and the task directory `~/temp/Ironwood-GC/`.
The empty directory received an exact shallow snapshot of GC1 commit `e113c12f`,
without copying repository history or changing the canonical local branch.
Both origin URLs remain `https://github.com/ironwood-lang/ironwood.git`.
The qualification source patch described below is recorded by source hashes in
the [Linux evidence manifest](gc-evaluation/gc1-linux-x86_64-2026-11-04-manifest.json).

Host: Linux **4.15.0-188-generic**, x86-64, glibc **2.27**. Reused tools are
Conda-forge LLVM/Clang **23.1.0**, the **glibc 2.17** `sysroot_linux-64` package,
JDK **21.0.10-internal**, and Python **3.14.6**. Missing lld was prepared in the
task directory with **lld 23.1.0 and libllvm23 23.1.0**, using a separate package
cache. Existing development environments were not modified. Exact package
builds, source URLs and package hashes are recorded in the manifest.

Preparation and qualification had a sampled **8 GiB task-directory disk cap**,
**20 GiB free-space reserve** and **900-second outer command limit**. Fixtures
retain their **16 GiB RSS guard** and **60-second child limit**. Initial available
disk was about 104 GiB. Task-local Conda download/metadata caches were removed
after validation; the tool prefix, SDKs and raw evidence remain for reuse.
The final retained footprint is approximately 0.42 GiB, with about 104 GiB free.
No hosted/paid validation, system package installation or full suite ran.

For reproduction, use the matching LLVM/sysroot prefix and Python, place the
task-local lld `bin` first on PATH, and choose fresh output directories:

```sh
export PATH="$PWD/workspace/gc1-linux/lld/bin:$IRONWOOD_LLVM_HOME/bin:$PATH"
"$IRONWOOD_PYTHON" scripts/prepare-gc.py --target linux-x86_64 \
  --llvm-home "$IRONWOOD_LLVM_HOME" --cache workspace/gc1-linux/cache \
  --prefix workspace/gc1-linux/sdk-final-first
"$IRONWOOD_PYTHON" scripts/prepare-gc.py --target linux-x86_64 \
  --llvm-home "$IRONWOOD_LLVM_HOME" --cache workspace/gc1-linux/cache \
  --prefix workspace/gc1-linux/sdk-final-second
"$IRONWOOD_PYTHON" scripts/test-gc-engine.py --target linux-x86_64 \
  --llvm-home "$IRONWOOD_LLVM_HOME" --sdk workspace/gc1-linux/sdk-final-first \
  --output workspace/gc1-linux/qualified-final
```

## Reproducibility correction

The first two preparations produced identical libraries and all SDK payloads.
Their manifests differed only in archive symbol-table hashes: ELF `readelf`
includes each temporary input archive path in member headings. The audit now
normalizes that path to `{archive}` before hashing its text. Actual library
hashes, object-target checks, visibility checks and interposition rejection are
unchanged. A focused regression compares the complete audit result for each
archive with its relocated copy while network access is forbidden.

Two subsequent clean preparations produced **byte-identical payloads and build
manifests**. Their actual collector libraries also match the pre-correction
libraries. The updated script and relocation regression passed a fresh macOS
preparation and the existing GC1 qualification, including all 25 negative
controls. The recipe-source checksum changed, so older SDKs must be prepared
again for use with the current verifier. Collector configuration identity did
not change on either target.

## Results and remaining gates

The Linux canonical configuration hash is
`5620b4a6bf863383dedbbda0fc4eb0b0e6e200a7a6aa96bac6540a67dae6605e`.
The archive pin, evaluated configure/CFLAGS tuples, eager sweeping, large heap,
disabled threads/incremental/ambient discovery and balanced explicit window are
unchanged. The target is `x86_64-unknown-linux-gnu`, 64-bit little-endian, with
the reserved ABI/layout/root versions from GC0. Full configuration and actual
library/header/adapter/input hashes are in the manifest.

- Startup, ordinary allocation and two exhaustion retries performed **zero
  collections**. A 32 MiB maximum heap returned null after **521,728** 64-byte
  cells. One explicit collection permitted **200,000** replacements without
  heap growth or deferred reclamation.
- Collection-time language counts were `4009,4,0,17,0,17,0,17,0`, with matching
  sidecar counts and four excluded administration arrays. The subsequent 123
  allocations reported 4,132 live, preserving GC0's accounting contract.
- Explicit roots retained graphs; ambient roots and pointer-shaped numeric
  fields did not. Native/collector allocator-domain boundaries, zeroed storage,
  environment refusal and all **25 negative controls** passed. Offline SDK
  relocation, linking and whole-SDK packaging-input verification passed.
- All **382 collector** and **six adapter** archive definitions were hidden.
  The linked ELF image exports only `ironwood_gc1_probe`; `GC_init`,
  `GC_gcollect` and `iw_gc_collect` bind `LOCAL`, and `DF_SYMBOLIC` is present.
  Its only dynamic dependency is `libc.so.6`, with required GLIBC versions no
  newer than **2.17**. No process-collector or load-order assumption is used.
- The same **six exact production tests** listed in the GC1 pre-change review
  passed on JDK 21. A current basic program compiled and linked at O3 with an
  explicitly nonexistent GC SDK location, returned **42**, retained calloc/free
  imports and contained no collector symbols. Compiler, runtime allocator,
  standard library and generation sources did not change.

Raw logs, downloads, SDKs and binaries remain ignored. Recorded paths use
checkout/toolchain placeholders or the neutral account `developer`; no private
host name is committed. License, provenance, tracked-file, diff and focused
syntax/evidence checks accompany this follow-up.

There is no known GC1 blocker for starting GC2's isolated layout work on tested
macOS arm64 or Linux x86-64. Linux arm64 still requires its own native run.
Complete IDK/Conda archive assembly and independent two-image runtime execution
remain unexecuted; the latter belongs to GC6. This result does not qualify
production layouts, roots, reentry, resident capacity or performance, and does
not activate production GC.
