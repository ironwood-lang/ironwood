<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC2 Linux x86-64 qualification

Date: 2026-11-04. **GC2 is qualified natively on Linux x86-64**, using
GC2 commit `f506c026`. No implementation or collector-source correction was needed.
Production GC remains disabled. Linux arm64 is not qualified.

## Scope and pre-change verification selection

Run the new USE_MARK_BYTES profile and ironwood-gc-engine-2 adapter natively on
x86host, preserving the canonical local branch and all prior commits. The remote
GC1 checkout and its uncommitted qualification changes remain intact. An exact
archive of the committed GC2 source is extracted into ignored task storage;
no branch, worktree or history is created or changed there. The source archive
hash identifies that snapshot independently of the parent GC1 Git metadata.

Select two fresh SDK preparations, complete manifest/payload comparison,
compiler-owned C/LLVM layout agreement, all nine bounded native layout cases,
actual mark-stack overflow/recovery with numeric-lookalike rejection, failure
and accounting controls, ELF/private symbols, offline relocation and stale SDK
rejection. Run the exact GC2 compiler test and six GC1 production controls,
plus an O3 current-program check without a collector SDK. No unfiltered suite,
GC3 implementation, source collection, language/bridge migration or paid jobs.

Reuse pinned LLVM/Clang 23.1.0, the glibc 2.17 sysroot, Java 21, Python and the
existing task-local lld. Retain the 16 GiB sampled RSS/60-second fixture limits,
8 GiB task-directory disk cap, 20 GiB free-space reserve and 900-second outer
limit. Keep raw logs, dependencies, source exports, libraries and binaries
ignored. Commit only compact evidence and conclusions after qualification.


## Source identity, tools and bounded storage

The maintainer authorized this SSH follow-up in `~/temp/Ironwood-GC/`.
The existing parent checkout remains at GC1 commit `e113c12f`, with its two
uncommitted qualification-script changes byte-for-byte intact. GC2 runs from
`workspace/gc2-linux/source/`, a plain source export without new Git metadata.
All **5,436 exported files** were compared with the archive after qualification
and remained unchanged. No compiler, runtime, adapter, qualification script,
configuration, branch or history was changed on the remote machine.

Actual source revision: `f506c026f105428e7359c7e7cba00f4b5b667bd9`.
Its `git archive --format=tar.gz` SHA-256 is
`b0e5fbd082e0f9309904c02f7d7fed60166b9cccc609b7752759df6d0d849bb8`.
Raw fixture reports query Git and therefore name the enclosing old parent
revision. The compact [evidence manifest](gc-evaluation/gc2-linux-x86_64-2026-11-04-manifest.json)
records that distinction, the source archive/tree identity, actual source hashes
and preservation checks. These hashes identify the tested GC2 source.

Reused setup: Linux **4.15.0-188-generic**, x86-64, host glibc **2.27**;
Conda-forge LLVM/Clang/lld **23.1.0**, pinned **glibc 2.17** sysroot,
JDK **21.0.10-internal** and Python **3.14.6**. Package identities and hashes
are in the manifest. No environment/system packages were installed or changed.
The existing task-local lld and checksum-verified collector source cache were
reused; no dependency download was required.

The task directory started at **447,459,328 bytes**. Peak sampled usage across
preparation, compilation and qualification was **541,544,448 bytes**, about
**0.50 GiB**. About **90 MiB** of additional reproduction storage was retained,
leaving more than **103 GiB** free. The 8 GiB task cap and 20 GiB reserve never
approached their limits. Raw sources, SDKs, logs and binaries remain ignored;
no existing cache or human work was removed. The guard used 900 seconds per
outer operation, with the native fixtures retaining their 16 GiB sampled RSS
and 60-second child limits.

## Native results

The new canonical Linux configuration SHA-256 is
`443a7d4df11b69f991a7f7eebebe5d18948bd43ea19a727ab9e4d1f6f7a500e6`.
It includes **USE_MARK_BYTES** and adapter ABI **ironwood-gc-engine-2**.
The collector archive pin and unmodified source, disabled ambient discovery,
threads/incremental collection and automatic scheduling remain unchanged.
Effective preprocessing confirms descriptor-preserving recovery. This evidence
qualifies the GC2 profile; the earlier GC1 Linux profile has a different identity.

| Check | Result |
| --- | --- |
| Clean preparation | Two preparations produced byte-identical payloads and complete build manifests. |
| Compiler-owned descriptors | All **21** separately compiled C/LLVM descriptions agree, including inheritance, hidden fields, enum bodies, holders, generic reference/primitive storage and the large 80-reference map. **1,024** types share bounded kinds, without an ordinary-object footer. |
| Arrays, Strings and construction | All eight primitive array kinds, reference arrays, multidimensional graphs, zero/large payloads, inline Strings, partial/base construction, zero initialization, native construction scratch, empty results and failure cleanup passed. |
| Actual overflow recovery | **120** overflows at **4,096** entries recovered with exactly **131,073** surviving language allocations. All **65,536** numeric-lookalike targets were unmarked. Removing roots reclaimed the graph; free cells were excluded. |
| Metadata/allocation failures | A 4,096-byte budget admitted five large descriptors, then refused exhaustion without approximation. Oversized language objects, sidecars, reference arrays and Strings failed without collections or successful-allocation events. |
| Frozen accounting | Language counts `4009,4,0,17,0,17,0,17,0` and matching sidecars passed. All six administration arrays were excluded. The subsequent-allocation formula and abandoned-construction events passed. |
| Policy and reuse | Startup and ordinary/exhaustion allocation performed zero collections. A 32 MiB heap returned null after **521,728** cells; one explicit collection permitted **200,000** replacements without heap growth or deferred reclamation. |
| SDK boundaries | Offline relocation/linking, all **27** nearby negative controls, an actual stale GC1 SDK refusal, and source-only/whole-SDK packaging-input staging passed. |
| ELF isolation | All **382 collector** and **nine adapter** archive definitions are hidden. The shared image exports only `ironwood_gc1_probe`; GC_init, GC_gcollect, iw_gc_collect and iw_gc_new are LOCAL, DF_SYMBOLIC is present, and libc.so.6 is the only dynamic dependency. Required GLIBC versions are at most **2.17**. |
| Production behavior | The exact GC2 compiler test and all six GC1 production controls passed. A current O3 program built with a nonexistent GC SDK location, exited **42**, retained calloc/free imports, contained no collector symbols and required no newer than GLIBC **2.17**. |
| Capacity categories | The separate engine-only **5 GiB virtual span** touched two payload pages. The described **128 MiB payload** was fully zero-checked and written. These are bounded controls; sampled RSS does not establish a continuous maximum or resident-capacity promise. |

All nine native layout cases and the selected seven compiler tests passed on
the first run. No source fix, conservative fallback, weakening of an ownership
proof or expanded/full suite was needed. The six production test names remain
exactly those in the [GC1 pre-change selection](GC1_ENGINE_PREPARATION.md#pre-change-review).
The additional registered test is
`GC2 exact layouts follow resolved storage and refuse legacy hidden links`.

## Reproduction and handoff

From an exact GC2 source export on the matching native Linux host, select the
pinned tool prefix, JDK and Python, and put the prepared lld directory first
on PATH. Use fresh
output directories and the existing checksum-verified archive cache:

```sh
python3 scripts/prepare-gc.py --target linux-x86_64 \
  --llvm-home "$IRONWOOD_LLVM_HOME" --cache /path/to/gc-cache \
  --prefix workspace/gc2-sdk-first
python3 scripts/prepare-gc.py --target linux-x86_64 \
  --llvm-home "$IRONWOOD_LLVM_HOME" --cache /path/to/gc-cache \
  --prefix workspace/gc2-sdk-second
cmp workspace/gc2-sdk-first/build.json workspace/gc2-sdk-second/build.json
python3 scripts/test-gc-layouts.py --target linux-x86_64 \
  --sdk workspace/gc2-sdk-first --llvm-home "$IRONWOOD_LLVM_HOME" \
  --java-home "$JAVA_HOME" --output workspace/gc2-layouts
python3 scripts/test-gc-engine.py --target linux-x86_64 \
  --sdk workspace/gc2-sdk-first --llvm-home "$IRONWOOD_LLVM_HOME" \
  --output workspace/gc2-engine
```

Build the compiler and test classes first with `./scripts/test.sh`, selecting
only the seven exact names above. Preserve the same disk/RSS/time guards.
The normal SDK verifier and fixture tools remain unchanged.

There is **no known GC2 blocker on macOS arm64 or Linux x86-64**. Linux arm64
still needs its own native qualification. This closes the fresh Linux x86-64
profile gate before GC3, without claiming production GC readiness. GC2's explicit
legacy-layout refusals, GC3 effects/statepoints/root readers, GC4 exception and
resource migration, GC6 foreign roots/reentry/two-image execution and complete
IDK assembly remain separate gates. The next bounded implementation task is
GC3 for a supported experimental closure. No GC3 work, production allocation
switch, source Memory.collect, destructor removal, bridge cutover, hosted/paid
job, push or GitHub activity occurred in this follow-up.
