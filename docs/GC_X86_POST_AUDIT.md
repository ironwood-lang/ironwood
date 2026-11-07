<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Physical x86-64 post-integration verification

Entry source `cb908a9f`, local `codex/bdwgc-integration-plan`. The maintainer
explicitly authorized SSH execution inside `~/temp/Ironwood-GC/` on x86host.
Canonical local and remote origin fetch/push URLs and branches are checked.
No remote Git synchronization, branch/worktree creation, publication or
self-hosting is authorized. Java remains the bootstrap.

## Pre-test matrix and safeguards

The remote parent checkout is old GC1 with two existing script edits. Preserve
its content/history and stage an exact archive of the local audited source in a
new ignored directory beneath that checkout. Bind every staged file to the
archive inventory; an inherited parent Git revision in a driver report is not
its tested source identity. Reuse existing cached tools, GCC native support and
GC9 native-host SDK only after verification against the current source. Do not
silently rebuild or requalify a different SDK identity.

All compiler/native/JVM children retain the 16 GiB process-group RSS guard and
60-second deadline. Outer orchestration also guards descendant groups and has
finite deadlines. Check a 50 GiB disk-space floor and a tighter 4 GiB cap on new
work while running. Preserve prior artifacts and caches. Raw outputs and source
archives stay ignored; commit only compact hashes, commands and conclusions.

Original MIT OR Apache-2.0 evidence tooling only, with no imported source. The
licensing mechanics and shared-analysis review are read. No implementation change
is planned; exact descriptors, root/owner ABIs, explicit-only synchronous eager
collection, conservative effects, D051 and D132/D133 remain unchanged. Any defect
requiring implementation changes needs a revised producer/consumer review first.

| Contract | Independent positive oracle | Paired negative/drop control | Focused selection |
| --- | --- | --- | --- |
| Retired features | Ordinary names have only two explicit calls; artifact parity | Removed syntax/options and reachable legacy instructions refuse | Exact compiler migration/artifact/final-link selectors; normal audit names |
| Current native fault fixtures | Actual managed results/roots, trace refresh/secondary storage, native scratch/resource balances | Managed/native OOM, publication failures, helper-return root drop to zero | Five registered harness selectors at O3 |
| Accounting and graph survival | Eight independently populated objects/arrays/String values, sharing/cycles; six real exception objects; failed construction counts once | Clear persistent slots, return helper frames, collect to baseline; sidecars excluded | Normal audit O0-O3 plus O3 archive parity |
| Actual capacity reuse | Replacements succeed in a capped 16 MiB heap after explicit collection | Retained and dropped-but-uncollected exhaustion still fail; warmed six-phase comparison | Normal audit bounded reuse O0-O3 |
| Exact roots and failure delivery | Init/statics, pending values, defer captures, partial construction and actual native exception frames | Missing/corrupt layout/root/location/boundary/owner metadata and preparation OOM refuse before reclamation | Selected layouts/roots/lifetimes and integrated O0-O3 |
| Intentional retention/resources | Pools/caches retain graphs and identity; explicit close remains independent | Slot cleanup/helper return allows collection, native balances remain stable | Normal audit retention; integrated pools/resources |
| Foreign ownership and reentry | Root handles/listeners, real JVM callbacks, suspended native roots, independent images | Exact protocol refusals and explicit JNI/resource completion | Objects/callbacks/images O3; segments O0/O3 |

## Results

All selected behavior tests pass on physical Intel Xeon E-2288G hardware,
Ubuntu 18.04.6 / Linux 4.15.0-188 / glibc 2.27. No virtualization is detected.
The existing Java 21.0.10-internal, LLVM/Clang/lld 23.1.0 and private GCC support
are reused without modification. The native-host SDK configuration remains
`5e536f53ea4a5a695f0176a8133b4168443d09225521e13f0881ba1d4a3ef347`;
offline verification passes before testing and at completion. No SDK rebuild,
compiler/runtime fix or test-input change was needed.

The exact archive of `cb908a9fe03e32de1f0d27932d2308607fb7fca2` is 8,019,583
bytes, SHA-256 `0a71fae967dbb9fac534f39f92fc4f754ef40d90aee15bbb348c42778b27260f`.
All 5,293 staged regular files match the archive inventory before and after
execution, with zero overlays. Driver reports that inherit `e113c12` from the
parent checkout are explicitly rebound to this inventory and audited revision.
The parent's branch, origin URLs, history and two script edits remain unchanged.

The [compact manifest](gc-evaluation/post-integration-x86_64.json) records exact
driver/child commands, per-stage exits, logs/artifact hashes, tool/SDK identities,
guards, source provenance and machine-code observations. Raw outputs remain
ignored beneath `workspace/gc-post-audit-x86-cb908a9f/` on x86host. Earlier ARM
and native-harness manifests stay unchanged. Stage counts include compilation
and linking; they are not counts of independent language features.

| Fresh selection | Observed result |
| --- | --- |
| Five native fault selectors | All five pass at O3; exact process graph count 12, file result increments +1, trace sidecar counts 4/5 and failure 0/1/1, final managed/native balances zero |
| Four compiler migration/artifact selectors | All four pass: canonical intrinsic declarations, removed forms/ordinary names, source/class/archive parity and legacy managed-deallocation final-link refusal |
| Normal audit | 40 stages pass: graphs, failed construction, real exceptions, cache retention, ordinary names and bounded capacity at O0-O3; O3 archive parity |
| Integrated / OOM / resources / pools | 48 stages pass at O0-O3, including preparation failure and explicit closure |
| Layouts / arrays / sidecars / metadata / failure / construction | 13 stages pass |
| Exact roots | 108 stages pass at O0-O3, including missing/corrupt maps, stale ABI/version, protected-map refusal, boundaries, scratch failure and missing unwind |
| Exception owners | 73 stages pass at O0-O3: unwind/nested/rethrow/cycles, trace/storage failure, emergency/OOM, resources, partial publication, pending/yield, owner ABI and preparation failure/corruption |
| Actual JVM object handles / callbacks | Three stages each pass at O3 with checked JNI; anchors, listener ownership and explicit completion remain separate from collection |
| Suspended JVM reentry | 20 stages pass at O0/O3, including A/Java/A and A/Java/B/Java/A, three protocol refusals and optimized transition-code checks |
| Independent production images | Four stages pass, including the actual checked-JNI consumer |

### Expected versus observed oracles

The graph family makes eight independently counted language allocations:
inherited objects, shared/cyclic references, reference and primitive arrays and
a dynamic String. Twelve build/retain/drop phases each retain all eight through
three collections, verify contents/identity/sharing, then return to zero survivor
delta after root cleanup and helper return. Failed constructor storage counts
once and preserves the published value until its root is dropped. Twelve real
exception phases retain exactly six language objects via cause/deferred-secondary
graphs; trace/association sidecars add nothing to the language counters. Dropping
the root returns to baseline. Cache reset keeps one object while populated;
explicit slot cleanup reduces the survivor delta from +1 to zero. All observations
match at O0-O3, with the selected O3 archive cases matching their class inputs.

Each optimization level independently demonstrates actual reuse in a 16 MiB
bounded heap. After warmup, six measured phases each admit fourteen 1 MiB arrays.
Retained roots still exhaust capacity. Dropped roots without collection still
exhaust capacity, with no automatic collection. Canonical Memory.collect then
permits fourteen replacement arrays, preserving repeated survivor/counter
expectations. This establishes available storage independently of counter reads;
RSS need not decrease. Qualification injects only the existing cold heap cap
into normally emitted startup IR, with no public memory mode or runtime ledger.

Actual x86-64 O3 disassembly of both counter functions contains cached global
loads and arithmetic followed by return, with no call or branch. The machine code
does not collect or scan reachability. The supplemental generated-output scan
checks 114 current C/Java/LLVM files plus 22 HTML and 87 source entries in freshly
generated jars. None contains a legacy managed cleanup or facade reclamation API.
Legitimate native frees and ordinary source names remain legal and are not banned.

The first supplemental jar-reader attempt incorrectly applied its 64 MiB text
inspection budget to all bundled native/archive payloads and refused a large jar.
The corrected reader retains the same 64 MiB limit for selected HTML/source
entries, and never reads unrelated native/archive payloads. Its rerun passes.
This was an evidence-tool error after all behavior tests had passed; no compiler,
runtime, source test or disk/memory limit changed. The failed log hash is retained.

### Commands and resource bounds

Commands run in the staged source directory with JAVA_HOME, IRONWOOD_LLVM_HOME,
IRONWOOD_GC_HOME, IRONWOOD_RUNTIME_HOME, IRONWOOD_STDLIB_HOME and the cached private
GCC support path set explicitly. PATH selects the pinned lld before system tools.
Each native/compiler/JVM child uses the existing 60-second/16 GiB guard; outer
orchestration has a 600-second limit and counts descendant process groups. Exact
resolved paths and environment/tool identities are in the manifest. Representative
executed commands, using new ignored output directories:

```sh
python scripts/prepare-gc.py --prefix "$SDK" --llvm-home "$LLVM" --verify
python scripts/test-gc-audit.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/audit"
python scripts/test-gc-integrated.py --case integrated --case oom --case resources --case pools --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/integrated"
python scripts/test-gc-layouts.py --case layouts --case arrays --case sidecars --case metadata --case failure --case construction --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/layouts"
python scripts/test-gc-roots.py --case allocate --case pending --case yield --case capture --case initialize --case constructor --case exception --case derived --case static --case bad-location --case missing-map --case boundary --case missing-unwind --case scratch --case stale-abi --case stale-version --case writable-map --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/roots"
python scripts/test-gc-lifetimes.py --case unwind --case nested --case rethrow --case cycles --case trace --case trace-fail --case emergency --case oom --case resources --case published --case pending --case yield --case owner-version --case preparation-catch --case preparation-corrupt --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/lifetimes"
python scripts/test-gc6-objects.py --level O3 --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/objects"
python scripts/test-gc6-callbacks.py --level O3 --diagnostics --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/callbacks"
python scripts/test-gc6-segments.py --level O0 --level O3 --diagnostics --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/segments"
python scripts/test-gc6-images.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/images"
```

The five exact native selectors are listed in LOCAL_TESTING.md. Four additional
exact CompilerTests names are recorded in the manifest's compiler-proofs command.
Only those selections run; no unfiltered suite or reconstruction of one is used.
The roots child adds three focused protocol/protection negatives before that
phase begins; both orchestration script versions are hash-bound in the manifest.

New work uses about 2.3 GiB, within the 4 GiB cap; about 82.5 GiB remains free,
above the unchanged 50 GiB floor. The largest native-driver child sample is
1,785,436 KiB, below the unchanged 16 GiB guard. Sampling does not establish exact
high-water RSS. Existing SDKs, archives, caches, checkouts and host settings remain
intact. No package/release rebuild, hosted workflow, Git network operation,
new performance comparison or self-hosting runs.

### Separate verdicts

**Feature retirement: complete within the audited inventory and execution scope.**
The four migrated fixtures and removal/ordinary-name controls now pass on all
three requested matching architectures; current generated outputs contain no
legacy managed reclamation API. The updated content-bound ledger retains narrow
dispositions for native cleanup, current metadata, legal names, rejection tests
and frozen evidence, with zero unexplained or open matches.
The final inventory covers 869 candidate files and 8,942 matched lines. A fresh
temporary HTML control is detected as the sole unexplained path, and its removal
restores success. JSON/document-link, license, diff and staged tracked-file checks
pass; prior evidence manifests and all executable/SDK inputs are unchanged.

**GC correctness: passes the requested focused three-platform audit.** Physical
x86-64 execution closes the previously unmet platform criterion. Exact counts,
rooted contents/identity/sharing, drop/reclamation, actual reusable capacity,
failure safety and supported foreign/resource balances pass without production
changes. No unresolved finding remains in this audit's requested scope. This does
not extend qualification to arm64e, unsupported foreign lifetimes, other JDK
matrices or an exhaustive set of programs.
