<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC5 checkpoint Linux x86-64 qualification


Historical partial-checkpoint qualification. The integrated cutover and current
evidence are recorded in [GC5_IMPLEMENTATION.md](GC5_IMPLEMENTATION.md). This
record remains evidence for its explicitly limited pre-cutover source snapshot.

Date: 2026-11-05. Baseline: `3ff18cdd`, the explicitly incomplete
[GC5 checkpoint](GC5_IMPLEMENTATION.md). **Its implemented subset is qualified on
native Linux x86-64**, with one artifact-test setup fix and
[compact evidence](gc-evaluation/gc5-linux-x86_64-2026-11-05-manifest.json).
Normal native collection remains refused. This does not complete GC5's
coordinated language/runtime/library cutover.

## Pre-change review

The task extends the checkpoint's direct native evidence to x86host. Preserve
the exact source, Java 21, LLVM 23.1.0, pinned BDWGC 8.2.12 engine-2 with
USE_MARK_BYTES, root ABI 2/runtime-owner ABI 1 and all current admission gates.
No compiler/runtime semantic change is planned. Revisit this review before
fixing any platform failure. Do not relax normal-source or foreign-root refusals.

Scope update during verification: the new intrinsic artifact test assumes that
its ignored `workspace/` parent already exists. A fresh source export exposes
that fixture setup failure. Reproduce it with the scratch parent absent, then
create the parent explicitly in the fixture and rerun only that failed selection
on Linux and macOS. This changes test setup, not compiler/runtime behavior or
the intrinsic's validation requirements. Preserve the six unfinished selections
from the initial bounded run as a separate successful follow-up.

Affected contracts and consumers are canonical intrinsic validation and embedded
source reconstruction; dispatch, initialization, cleanup and specialization
collection effects; final mixed-heap rejection; the nonmoving LLVM pass; ELF
unwind/root reconstruction; exact runtime-owner coverage; preparation OOM and
explicit native resource cleanup. Existing heap proofs, pool protocols, trace
APIs and allocation failure remain production controls, not GC cutover evidence.

Paired cases include rooted survival versus later dropped-root reclamation;
catchable scratch exhaustion with zero collections versus corrupt roots after
capacity exhaustion; a throwing failure continuation versus a returning one;
resource cleanup after failure versus collection without resource closure;
valid owner/maps versus stale coverage, PCs, spill slots and function identities;
and source/class/archive parity versus forged or malformed intrinsic declarations.
Keep stripped-unwind and actual ELF RELRO-protection refusals.

Focused selection: four exact GC5 compiler selections and the thirteen retained
checkpoint controls; the GC4/GC5 owner matrix and retained GC3 root matrix at
O0/O1/O2/O3; offline verification of the existing qualified SDK; final ELF symbol,
PIE, unwind, RELRO and glibc inspection; O3 read/scalar/collecting-step fingerprints
against the native GC4 baseline. Identical machine code is the no-change control;
do not claim a new throughput result from it. No unfiltered suite is authorized.

Use an exact plain source export inside the authorized remote task directory's
ignored workspace. Preserve its existing parent checkout, tracked files and two
modified scripts byte-for-byte. Do not fetch, switch branches, create a worktree,
commit remotely or push. Reuse the installed pinned JDK/LLVM/lld and existing GC2
SDK without downloads, installation or dependency rebuilds.

Bound the enclosing remote task folder to 8 GiB and retain at least 20 GiB free.
Keep the 16 GiB sampled process-group RSS guard, 60-second native/compiler child
bounds and a 900-second outer bound. Check disk use before transfer/extraction,
during runs and afterward. Keep raw logs, archives, source exports and binaries
ignored; commit only compact evidence and conclusions.

## Source and toolchain

Exported `3ff18cdd58fc9b3d94bb622ff112884ae9fee4f3` into a new plain source
directory under `workspace/gc5-linux-3ff18cdd-20261105/` in the authorized remote
task folder. The archive is 8,256,222 bytes and contains 5,478 files totaling
39,961,107 source bytes. Every file was checked against its archive digest.

The only overlay adds `Files.createDirectories(Path.of("workspace"))` to
Gc5IntrinsicTests before creating artifact scratch. The original fixture fails
when that parent is absent; the repaired fixture passes with the parent still
absent at entry. The same exact selection passes on machost with Java 21.
All other 5,477 exported files remain byte-identical. The native matrices ran
the unmodified checkpoint; this later test-only fix changes no native producer,
compiler/runtime implementation, source intrinsic authority or generated code.

Reused installed JDK 21.0.10-internal, LLVM/lld 23.1.0, GCC 11 development headers
and the qualified glibc 2.17 GC2 SDK. The host LLVM pass retains GC4's
`--gcc-toolchain=/usr` convention; generated programs retain the qualified
sysroot. The actual pass loads in pinned opt and final LLVM verification remains
enabled. No dependency download, installation, collector rebuild or patch occurred.

All 31 SDK payloads pass offline verification with network access forbidden.
Configuration SHA-256 remains
`443a7d4df11b69f991a7f7eebebe5d18948bd43ea19a727ab9e4d1f6f7a500e6`;
engine-2, USE_MARK_BYTES, root ABI 2 and runtime-owner ABI 1 are unchanged.
Root/owner/pass inputs remain separately source-built development components,
not an assertion that the old prepared SDK now implements normal GC generation.

## Results

| Focused check | Result |
| --- | --- |
| Compiler selections | **17 exact selections pass** across bounded runs: four GC5 checks and thirteen retained checkpoint controls. Source/class/archive intrinsic validation, malformed/spoofed declarations, MayCollect propagation, mixed-heap refusal and legacy release rejection remain enforced. |
| Owner/preparation matrix | **140 native executions**, 35 scenarios at O0/O1/O2/O3: 84 positives and 56 precollection refusals. This retains all 128 GC4 Linux cases and adds 12 preparation-catch/corrupt/returning executions. |
| Retained native roots | **156 native executions**, all 39 existing Linux root scenarios at every optimization level: 68 positives and 88 refusals. |
| Catchable preparation OOM | Original immortal identity reaches the typed catch using the bounded emergency wrapper. Zero collections occur on failure; both successful language allocations remain counted. Explicit resource abort balances both acquisitions, dropped anchors permit a later successful collection and language/internal live counts reach zero. |
| Refusal order | A corrupt root after scratch exhaustion still refuses before collection instead of delivering catchable OOM. Returning failure continuations also refuse. Both preserve explicit native cleanup. Missing/stale owners, ABI, maps, unwind coverage and actual RELRO controls remain negative cases. |
| Final ELF images | All four owner images are PIE, retain unwind headers and protected root maps, have no text relocations or dynamic collector definitions, and require GLIBC no newer than 2.17. |
| O3 machine code | Node.read is **5 bytes**, Main.scalar **48 bytes** and collecting Node.step **16 bytes**, byte-identical to the committed Linux GC4 fingerprints. Read/scalar have no calls, polls, TLS or root registration; step retains its one root spill. No new timing or throughput claim. |

The initial combined rebuild and 17-test selection reached the unchanged
60-second guard after ten passes, one fixture setup failure and six unfinished
checks. Those six passed without rebuilding. The artifact failure was reproduced
separately with absent scratch, repaired and rerun on Linux and macOS. Tests,
safety checks and limits were preserved. Private reporting helpers also needed
module-name and JSON-line-output corrections; these did not change tested code
or require native reruns. The evidence records these dispositions and log hashes.

The owner matrix's largest sampled process-group RSS is **1,113,544 KiB**;
the root matrix's is **726,184 KiB**, below the unchanged **16 GiB** guard.
Native children retain 60-second limits; outer runs retain the 900-second and
disk guards. These are sampled measurements, not continuous maxima or capacity
claims. No full compiler suite, hosted build, Java Bridge/JNI run or self-hosting
work occurred.

## Preservation and disk bounds

The remote parent remains at `e113c12fc1993709f8bcfa542f39bea7ccba6f71` on
`codex/bdwgc-integration-plan`, with canonical origin URLs. All **5,427 tracked
files**, both pre-existing modified scripts, its complete diff/status and Git
index remain byte-identical. No remote Git write, branch/worktree change, fetch
or push occurred. Raw logs can report that enclosing older Git revision; the
verified export and one recorded overlay identify the actual tested source.

The enclosing task folder grew from **769,662,976** to approximately
**874,598,400 bytes**, about **100 MiB** added and **0.82 GiB** total, against the
**8 GiB** cap. Approximately **103 GiB** remains free against the **20 GiB**
reserve. Existing task sources, SDKs and evidence were preserved. Archives,
generated files and raw logs stay ignored on the remote host and under local
`workspace/gc5-linux-followup/`; only the fixture fix, documentation and compact
evidence are committed.

## Reproduction and bounded continuation

Use the exact source export plus the recorded test overlay, the existing
qualified SDK and installed toolchain, with pinned lld first on PATH. Rebuild
and run only exact compiler selections from the evidence; split build/test
commands when their combined duration reaches the guard. Retain the same limits.

```sh
python3 scripts/test-gc-lifetimes.py --target linux-x86_64 \
  --sdk "$GC2_SDK" --llvm-home "$IRONWOOD_LLVM_HOME" \
  --java-home "$JAVA_HOME" --output workspace/gc5-owner-results
python3 scripts/test-gc-roots.py --target linux-x86_64 \
  --sdk "$GC2_SDK" --llvm-home "$IRONWOOD_LLVM_HOME" \
  --java-home "$JAVA_HOME" --output workspace/gc5-root-results
```

Use fresh output directories. `--case` and `--level` select bounded follow-ups.
The manifest records exact selections, report hashes, source/overlay identities,
input payloads, code fingerprints, artifact regression and preservation checks.
License, tracked-file, diff, JSON, source-hash and documentation-link checks
accompany the local commit.

This closes the Linux x86-64 qualification gap for the **implemented private
checkpoint only**. GC5's normal type/Throwable layouts, allocator domains,
reclamation lowering, complete library migration and verified native-input
binding still require implementation. Continue GC5 before GC6. Linux arm64 and
arm64e gates remain closed; no generated RootHandle/listener API, JNI ownership,
foreign transition, JVM reentry or multi-image qualification is claimed.
