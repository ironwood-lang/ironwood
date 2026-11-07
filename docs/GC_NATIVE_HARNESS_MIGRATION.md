<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native fault harness migration

Entry revision `ec6b54d6`, local `codex/bdwgc-integration-plan`. Canonical root,
origin fetch/push URLs and clean tree verified. All work stays local; no remote
Git, hosted work, branch change or self-hosting is authorized.

## Pre-change review and coverage map

Original MIT OR Apache-2.0 test infrastructure only; licensing mechanics and
shared-analysis regression review read. Production collector/runtime/compiler
algorithms, exact descriptors, root/owner ABIs, conservative effects, D051 and
D132/D133 remain unchanged. No SDK input should change. Verify its exact identity
before use. Compiler/native children retain 16 GiB process-group RSS and 60-second
limits; raw outputs stay ignored, with a 16 GiB output cap and 50 GiB free floor.

The affected consumers are four registered C runtime fault harnesses and their
three Java launch paths. They include the runtime translation unit to inject
native faults. A shared C-only qualification setup will bind current physical
layouts, initialize actual sidecar kinds, supply explicit native roots and perform
explicit phase collection through the real engine. These direct C probes do not
qualify compiler stack maps; existing ordinary Memory.collect programs and exact
root/owner drivers continue to cover that separate contract. No collector shim,
managed release API or production tracking is introduced.

| Harness | Preserved faults / behaviors | Changed observation |
| --- | --- | --- |
| filesystem_services | Name collisions/exhaustion, close failure, post-create managed OOM and entry removal, long stems, Linux random fallback, real paths/invalid UTF-8/access, rename races and each cross-device copy failure | Native malloc/descriptor balance stays immediate; managed allocations count separately and reclaim after helper return and explicit collection |
| filesystem_scratch | Exact/growing/shrinking size hints, malformed UTF-8, too-large sizes, managed-result OOM, native growth OOM, long path/cwd OOM, normalization and repeated streaming interruptions/failures | Separate managed success/attempt counters from native scratch counts; root surviving inputs across phase collections; verify the old compaction boundary no longer allocates |
| process_services | Pre/post-fork failures, command encoding OOM, interrupted wait, 200 launches, inherited descriptor boundary and child reaping | Command arrays retain String elements through exact descriptors; explicit resource balance independent of graph collection |
| throwable_trace_storage | Common suffix, refreshed trace, ordered secondary graph, metadata/storage failure and bounded emergency delivery | Current trace_metadata/trace_status and managed storage bases; refresh/drop makes old sidecars collectible, without source release |

Paired controls: root a graph and collect repeatedly with contents/counts intact;
drop roots after helper frames return and collect to zero language/sidecar counts.
Allocation failures count no failed allocation; native faults close resources
immediately; collection neither closes resources nor performs user cleanup.
Emergency trace delivery must not allocate. Retained trace/secondary children must
survive even when only their owning exception is explicitly rooted.

Focused verification: the four existing exact registered selectors, a new
filesystem publication selector, and small ordinary/root/owner selections at
O0-O3 where relevant. Run on macOS arm64 and the available cached native Linux
arm64 environment. Do not run an unfiltered compiler, stdlib or platform suite.
Record unavailable matching x86-64 evidence honestly. Only after fresh passes
remove the four open residual dispositions, update the audit and require the
retirement check to exit zero. Run diff, license and staged tracked-file checks.

## Results

The former launchers compiled a runtime translation unit without its required
collector headers or libraries. Fixing that alone exposed retired managed
deallocation, obsolete Throwable fields and native-allocation assumptions. The
test-only RuntimeHarnessBuild now validates the production SDK using normal
GcDependency discovery, compiles both C inputs and links the real engine and
BDWGC. Unused compiler root-walk sections are discarded normally. No root or
boundary verifier is stubbed and no fabricated compiler protocol is admitted.

gc_harness_alloc.h injects failures only into the included runtime translation
unit. Failed language allocations and runtime-only sidecars are distinct from
native malloc/calloc/realloc scratch. gc_harness_state.h binds actual C String,
array and Throwable layouts, rejects a deliberately misaligned descriptor,
initializes real sidecar kinds and registers the actual emergency native objects.
It supplies only explicit native roots and runtime owners, removing them after
each collection. C local variables are intentionally outside this direct-probe
root contract. Final collection runs after helper return and clears the persistent
error root. All probes finish with zero language survivors and zero sidecars.

All managed release calls are removed. Native frees still balance native scratch,
and descriptor closure/child reaping remain immediate. The scratch reader now
observes one native input buffer plus one exact managed result, rather than
native-calloc storage for the result. It explicitly checks that final String
compaction does not call realloc; native growing-buffer realloc OOM remains a
separate exercised fault. The formerly unregistered publication branch now has a
focused selector. The trace probe simulates frame acquisition with deterministic
pseudoprobe bytes, then exercises actual decoding, capture and managed graphs;
the root/owner driver separately checks real optimized native frames.

| Independent oracle | Expected | Observed on both targets |
| --- | --- | --- |
| Four file reads | One successful language allocation each, native heap delta zero; retain path/text across collection | +1 each; exactly three survivors including the error root, with independently checked UTF-16 contents |
| File/path OOM | Managed-result failure and native growth/path/cwd failures allocate no successful language object; no scratch/descriptor leak | Unchanged total and native baseline for each injected fault |
| Process command graphs | Error + two paths + three arrays + 3/1/2 command Strings = 12 objects | Exactly 12 through four collections; command execution still exits 7, descriptors balanced, no child left unreaped |
| Trace refresh / secondary graph | Two metadata + two trace-storage sidecars; refresh reclaims old storage; secondary link retains both exceptions | Four sidecars before/after refresh; five with secondary link, three language survivors through three collections |
| Three trace allocation faults | Metadata/native scratch/trace-storage failures leave 0/1/1 sidecars while rooted; subsequent refresh recovers | Exact counts and captured content; language total unchanged by capture |
| Emergency capture | Fixed registered storage; no language, sidecar or native scratch allocation | Captured trace, zero allocator attempts and zero sidecars |
| Drop phase | Only the error root after helper return; zero after clearing it | One, then zero language survivors; zero sidecars/native scratch/descriptors |

Five exact selectors pass at O3 on macOS arm64 (machost) and native Linux arm64
(armvm). Focused roots/owners pass 33 stages per target at O0-O3: trace,
trace-fail, emergency, pending and preparation-catch. Normal compiler/runtime
Memory.collect OOM/resources cases pass 30 stages per target at O0-O3, including
constructor failure, preparation failure and explicit resource closure. The
[compact manifest](gc-evaluation/native-harness-migration.json) records exact
commands, input/SDK hashes, result/log hashes and sampled RSS. The original
[audit manifest](gc-evaluation/post-integration-verification.json) stays frozen.

Commands use exact qualified SDK/LLVM/Java 21 paths and a new ignored output
directory. The registered selectors are listed in LOCAL_TESTING.md. Additional
fresh selections:

```sh
python3 scripts/prepare-gc.py --prefix "$SDK" --llvm-home "$LLVM" --verify
python3 scripts/test-gc-lifetimes.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --case trace --case trace-fail --case emergency --case pending --case preparation-catch --output "$OUT/lifetimes"
python3 scripts/test-gc-integrated.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --case oom --case resources --output "$OUT/integrated"
python3 scripts/gc-evaluation/audit_retirement.py
```

Both SDK identities verify before use and at completion, unchanged:
machost `f6cdb6e12017ba8d203b7ab46c4f870585e4860c7f5de6ea88bad1c0d60b78ab`,
armvm `d8434872c7bd54595bc73e03ec9b245dc4c58748f328d2854b7e6ee030120912`.
No SDK input changed or needed rebuilding. Native/compiler children retain the
16 GiB process-group RSS and 60-second limits; orchestration has separate finite
deadlines. Linux additionally uses a 4 GiB container limit with network disabled.
The initially stopped ARM VM is stopped again. No hosted, remote Git, full-suite,
self-hosting or matching x86-64 execution ran. Fresh matching x86-64 remains the
precise outstanding platform criterion of the broader GC audit.

The content-bound residual inventory retains only justified native frees, current
metadata names, ordinary identifiers, removal negatives and historical records.
The four open obsolete-input dispositions are closed: 867 candidate files and
8,918 matched lines have dispositions, with zero unexplained or open matches.
A temporary new HTML match fails the check with exactly that unexplained path;
removing it restores success. This does not turn
the ledger into an automatic allowlist or exclude a source directory.
IronDocs regeneration comparison, license, diff and staged tracked-file checks
pass. The largest final native-driver RSS sample is 992,144 KiB. New ignored
output stays below 0.3 GiB, with more than 240 GiB disk space available.

**Feature retirement: complete within the audited scope.** The four executable
findings are fixed and freshly verified; no runtime API was restored.

**GC correctness: the restored fault matrices and focused ARM controls pass.**
The broader audit remains incomplete for fresh matching-hardware x86-64 evidence;
this continuation makes no new capacity, performance or three-platform claim.
