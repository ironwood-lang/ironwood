<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Independent post-integration memory audit

Audit base: `d5ccd69c`, local `codex/bdwgc-integration-plan`, 2026-11-07.
Canonical root and both origin URLs verified; starting tree clean. No remote
fetch/integration, publication, branch change or self-hosting is authorized.
Java remains the bootstrap. The local audit changes and tested inputs are bound
by the [verification manifest](gc-evaluation/post-integration-verification.json).

Continuation from local audit commit `ec6b54d6`: the
[native harness migration](GC_NATIVE_HARNESS_MIGRATION.md) resolves the four open
executable inputs with fresh macOS/Linux arm64 tests. The original manifest stays
unchanged as evidence of the initial audit. The current residual ledger and
verdicts below include that continuation and the subsequent
[physical x86-64 verification](GC_X86_POST_AUDIT.md) of `cb908a9f`, which closes
the requested matching-hardware coverage gap. Both earlier manifests retain
their original results and provenance.

## Pre-test behavior matrix and review

Expected results come from MEMORY.md and independently counted fixture graphs,
not previous completion counts. All native/compiler children retain the 16 GiB
process-group RSS guard and 60-second deadline. Preparation has a separate
600-second bound. New disk output is capped at 16 GiB with a 50 GiB free floor.
Raw logs, generated code, binaries and SDKs stay under ignored workspace paths.

| Contract | Positive observations | Negative / release controls | Planned selection |
| --- | --- | --- | --- |
| Removed syntax and options | Ordinary free/destructor methods, deferred ordinary calls | Useful errors from source and reconstructed class/archive; final-link symbol refusal | Exact GC7 migration/artifact and GC5 final-link compiler tests |
| Accounting | Objects, inherited objects, arrays, dynamic Strings, failed construction; cached live plus allocation delta | Immortals, native scratch and runtime sidecars excluded; reads do not collect | New normal-path audit fixture, layout/owner controls |
| Graph lifetime | Independently populated/shared/cyclic graphs survive repeated collections | Clear persistent slots, return helper frames, collect to warmed baseline; repeat replacement | New normal-path audit fixture; integrated and GC7 consumers |
| Capacity | Bounded heap fails without implicit collection, then explicit collection permits replacement | Retained-root exhaustion control, dropped-but-uncollected exhaustion control | New qualification-only normal-path capacity fixture |
| Exact roots and exceptions | Statics/init, pending return/yield, defer captures, partial construction, real exception graphs | Missing/corrupt layout/root/owner/boundary metadata refuses; preparation OOM reclaims nothing | Focused roots/lifetimes and normal integrated O0-O3 |
| Intentional retention | Populated pools/cache slots retain identity and graph | Explicit reference cleanup / lifetime release returns to baseline | New graph fixture and current pool/container controls |
| Foreign lifetime | Anchors, listeners, JVM callback/reentry, independent images | Closure independent of collection, invalid protocols refused, balanced JNI/resources | Focused GC6 objects/callbacks/segments/images |

Source review covers all maintained tracked text regardless of extension,
embedded programs/templates, generated current references, IDE grammars and
packaging inputs, plus actual generated IR and native symbols. Historical
evidence receives explicit path/content dispositions; no directory is excluded
from candidate detection.

Licensing review: new audit code is original MIT OR Apache-2.0 work. No imported
implementation or library port is planned. The shared-analysis pre-change review
was read before implementation: exact descriptors/roots, conservative effects,
initialization, native acquisition, pool origins, D051 and D132/D133 must remain.
Initially only audit tooling, fixtures and evidence change. Any discovered
production fix requires a producer/consumer map and paired safe/unsafe selections
here before editing shared analysis. No tracking ledger, automatic collection,
new public mode or hot-path instrumentation is permitted.

## Results and verdicts

Additional pre-test oracle: twelve normal-program exception phases construct
three real Throwable subclasses, each with one child, for exactly six language
allocations. A cause and a deferred secondary keep all six reachable; trace and
association sidecars must not change either language counter. Three collections
per retained phase must preserve contents, followed by zero survivor delta after
the static root is cleared and helper frames return.

### Findings and fixes

1. **Resolved in the continuation: four registered native harnesses were not migrated.**
   At `ec6b54d6`, `integration-tests/runtime/filesystem_services.c`,
   `filesystem_scratch.c` and `process_services.c` called `ironwood_deallocate`
   on language objects. `throwable_trace_storage.c` used the removed Throwable
   `trace_state` field and `ironwood_throwable_trace_release`. Their registered
   compiler tests all failed first at missing `gc_mark.h`. A second, independent
   syntax-only compilation with the verified SDK include path exposed the
   retired calls/fields. These were obsolete executable test inputs, not useful
   migration negatives, matched native frees or frozen evidence. Their calloc/free
   accounting, hand-built type metadata and trace ownership assumptions required
   substantive harness migration; deleting
   their assertions or restoring the old runtime ABI would discard fault
   coverage or violate the contract. Newer GC tests are not counted as replacements
   for these filesystem/process/trace fault matrices.
   The continuation migrates all four to real managed allocations, exact current
   layouts and explicit native roots, while preserving native cleanup/faults.
   Five registered selectors now pass on both ARM targets, including newly
   registered publication coverage. Current sidecar refresh, failure and emergency
   assertions replace obsolete release assertions. No production ABI is restored.
2. **Fixed: active tests required obsolete destruction behavior.** The filesystem
   shared-result test required a missing-free error for unused results; its
   focused run failed. It now requires successful, diagnostic-free compilation.
   The lazy archive test expected a destruction counter to increment despite no
   method changing it; its native process exited 1 instead of 42. It now checks
   zero automatic cleanup and passes the actual archive/native route.
3. **Fixed: consumer scripts and labels drifted from their sources.** Eighteen
   negative example wrappers expected old alias/ownership errors. They now
   require the actual removal diagnostic; all eighteen pass. DeferredCleanup's
   runner expected old output including automatic reclamation; the reproduced
   mismatch is fixed and the native example passes. Empty per-scenario manual-mode
   branches are removed. The stdlib summary and compiler expectation now use
   retention names and the current individual expected counts, totaling
   176 passed / one skipped / 177 total. This total is a consistency correction,
   not a fresh all-suites result. The two selected retention suites pass 11 and
   25 cases separately. Misleading pool test names are corrected.
4. **Fixed: obsolete generators and dead test code survived retirement.**
   Java differential generators no longer silently strip legacy statements from
   current fixtures; the existing inputs need no such translation. The unused
   `BridgeIdentityFixtureSources.java` is removed: neither retired identity test
   has a remaining caller. Its original `d5ccd69c` SHA-256 is
   `791c076f8c4d7036c7baeb08e8175ca5126cd3a428774bc9fa8973ad7bea8118`.
   Unregistered `LoopReachabilityTests.reclamationProofs` is removed; current
   loop completion/reachability tests pass. The original versions remain in Git
   and the earlier GC7 ledger is preserved, rather than rewriting its claims.
5. **Fixed: current explanations taught removed lowering.** COMPILER.md still
   described owned-rendering release instructions; IMPORTANT_OPTIMIZATIONS.md
   still described and showed managed rollback helpers. Source inspection of
   rendering consumption, throw matching/emission and actual LLVM output supports
   their replacement descriptions. Current roadmap/NIO/optimization obligations
   and stale source comments are reconciled. Existing Ironwood comment edits have
   unchanged executable tokens; pool test identifier renames are separately
   recorded. The sole production Java edit changes a comment. No runtime,
   collector, shared-analysis algorithm or hot lowering changes.
6. **Fixed: focused driver defects.** Defer performance control defaulted to a
   removed case named `free`; it now selects `call`, with dead case accounting
   removed. A short rebuilt call/control run passes, without a timing claim.
   GC6 callback diagnostics accepted only the Mach-O frame prefix. The Linux JVM
   consumer succeeded but the driver rejected expected `GC3 ELF frame pc=` lines.
   The filter now admits the matching platform's exact diagnostic prefix only
   when diagnostics are requested. The Linux O3 driver passes after the fix;
   checked JNI diagnostics are not suppressed.

### Residual inventory and actual output inspection

[audit_retirement.py](../scripts/gc-evaluation/audit_retirement.py) scans every
tracked and nonignored untracked file, independent of extension. It also opens
ZIP source containers recursively, including checked-in historical class fixtures.
It searches word fragments and related AST/IR, release, rollback, facade and
metadata names, including ordinary false positives such as freeze and process
destroyForcibly. Six opaque inputs are verified PNG branding assets. There are no
maintained tracked HTML files; current IronDocs emits Markdown. Its current
development reference passes regeneration comparison. A temporary HTML negative
also verifies that a new extension cannot bypass the check.

The [residual ledger](gc-evaluation/post-integration-residuals.json) lists each
candidate path, its exact content digest, and a disposition for every matched
line. Historical/released evidence is individually content-bound, including
stopped self-hosting harnesses; it is not excluded from detection. No broad
directory allowlist conceals new files. Existing GC7/GC10 reports retain their
original conclusions and provenance. The current pre-change checklist's removed
mode wording is corrected without changing that incident's historical results.

Retained cases have these specific meanings:

| Residual family | Disposition and source/output evidence |
| --- | --- |
| Parser and CLI spellings, negative source strings and reconstructed artifacts | Removal diagnostics; not accepted cleanup syntax. All four source forms now have source/class/archive rejection coverage, including embedded source identities. Removed ordinary and bridge options refuse. |
| Ordinary free/destructor methods, classes, parameters and historical fixture names | Ordinary lookup/mangling. Native names control observes only its two explicit calls, including general defer; collection and scope exit add none. Counter identifiers do not grant cleanup behavior. |
| Final-link and bridge forbidden-symbol strings | Fail-closed checks and deliberate injected negative calls. Every listed legacy managed symbol refuses final linking. No such call appears in positive generated output. |
| Runtime and generated JNI C free calls | Matched native scratch, UTF/path/file buffers, unwind wrappers, anchors, registrations, listener/copy records and resource acquisitions. Collector storage is allocated separately; no production managed deallocation caller exists. |
| C destructor attributes in native interposers | Native test infrastructure reports/closure at process exit; not Ironwood destructors or managed finalization. |
| Descriptor padding and owned_message | IrClass has no cleanup capability fields. The emitter always emits two null pointers and two false flags; actual LLVM agrees. The C Throwable name mirrors the retained source message slot; it has no release producer. ABI/layout checks remain exact. |
| Prior decisions, reports, released references and stopped harnesses | Dated/frozen historical records or explicitly superseded proposals, each with a path/content disposition. The unselected region proposals describe possible future APIs, not available source reclamation. |
| Four runtime C harnesses and shared test setup | Migrated executable probes. Remaining free calls balance native scratch; trace_state is a field of current trace metadata. Current C layouts and explicit roots use the real engine, with zero managed objects/sidecars after final root drop. No retired managed API remains. |

Positive native/JVM builds supply 66 generated C/Java/LLVM source files for a
separate actual-output scan. Twenty-four fresh generated jars additionally
contain 44 HTML documentation entries and 262 source entries; all are inspected
and content-bound in the manifest. No obsolete facade reclamation API or legacy
managed cleanup call is present. The
generated facades use anchors and registrations, with source methods separate
from lifetime closure. Actual native counter disassembly shows cached loads and
arithmetic only, with no collection, helper call or reachability scan. Runtime
source has one GC_enable/GC_gcollect window, reached through explicit collection.

The tripwire is deliberately conservative: changing any content-bound candidate
requires renewed review. It detects unexplained additions; it cannot prove native
pointer provenance, discover every possible synonym, or replace semantic tests.

### Fresh behavior and independent count oracles

| Normal-path oracle | Expected | Observed |
| --- | --- | --- |
| Inherited objects, shared child/cycle, reference array, int/byte/char arrays and dynamic String | Exactly eight successful allocations; eight survivor delta while rooted; zero after helper return/root drop and collection | Pass, twelve replacement phases, three retained collections per phase |
| Published failing constructor | Storage counts once despite failure; correct dynamic type/value survives; zero delta after dropping publication | Pass; allocation delta 1 and rooted survivor delta 1 |
| Primary/cause/deferred secondary exception graph | Three Throwable subclasses plus three children; six counted allocations; trace/secondary sidecars excluded; zero survivor delta after drop | Pass at O0-O3 on both ARM targets, twelve phases with three retained collections each; O3 archive parity also passes |
| Cached live count | Root drop alone does not change the cached count; completed collection does; later language allocations add immediately | Pass, including failed-construction and bounded-exhaustion controls |
| Immortal literals, native scratch and sidecars | No language allocation count for image literals, collector scratch or runtime-only sidecars | Pass in normal graph/exception counts and supplementary layout/owner controls |
| Populated and inactive cache slots | One node remains retained after resetForReuse; explicit clearUnusedReferences/clear removes its final reference | Pass, twelve reset/clear replacement phases, exact +1 / +0 survivor deltas |
| Pools | Retained values preserve identity/data; get/release reuse allocates nothing; pool graphs become collectible after helper exit | Integrated O0-O3 and selected retention suite pass; no collection closes resources |
| Bounded actual capacity, independent of counters | Retained roots exhaust; dropped but uncollected roots still exhaust; explicit collection enables equal-size replacement | Pass, six measured cycles: fifteen 1 MiB arrays on machost, fourteen on armvm, each under a 16 MiB collector cap |

The capacity helper injects only a cold GC_set_max_heap_size call into normally
emitted startup IR, then uses the real NativeBackend, pinned pass, unchanged SDK,
runtime, allocation failure and canonical Memory.collect. It is qualification
tooling, not a new public memory mode. An initial Linux run exposed an unwarmed
heap geometry assumption: twelve arrays before the first sweep versus fourteen
afterward. A diagnostic six-phase run observed 12,14,14,14,14,14. The final test
warms exhaustion/drop/collection before comparing measured phases; it never
weakens root tracking. Retained and dropped-without-collection failure controls
still run in every phase. Counter equality alone cannot make these allocations
succeed, so the replacement result establishes actual reusable capacity.

Core initialization/statics, pending return/yield, collecting defer captures,
construction, real exceptions and resource cleanup also pass through the ordinary
compiler/runtime integration fixtures. Root/owner probes add actual optimized
native frames, missing/corrupt map/location/boundary/owner refusals, emergency
delivery and preparation OOM/corruption controls. Preparation failure reclaims
nothing. General defer and D051 ordering survive. GC6 actual JVM controls cover
anchors, listener replacement/retirement, saved A/Java/A and A/Java/B/Java/A roots,
protocol refusals and independent images. Collection does not perform explicit
source close or JNI lifetime release. The continuation separately restores the
four native fault matrices; these observations are not substituted for them.

### Commands, target bounds and fresh results

Use a new ignored output directory for each invocation. The manifest records
actual child commands, source/SDK/tool identities, report/log hashes, exits and
sampled peaks. These are stages, not counts of independent language features.

| Selection | machost: macOS arm64 | armvm: native Linux arm64 | x86host: physical Linux x86-64 |
| --- | --- | --- | --- |
| integrated / oom / resources / pools | 48 stages, O0-O3 pass | 48 stages, O0-O3 pass | 48 stages, O0-O3 pass |
| layouts / arrays / sidecars / metadata / failure / construction | 13 stages pass | Not rerun separately | 13 stages pass |
| Focused exact roots | 92 stages, O0-O3 pass | 68 stages, O0-O3 pass | 108 stages, O0-O3 pass |
| Focused exception owners | 73 stages, O0-O3 pass | 49 stages, O0-O3 pass | 73 stages, O0-O3 pass |
| Managed object JVM consumer | 5 stages, O0/O3 pass | 3 stages, O3 pass | 3 stages, O3 pass |
| Callback JVM consumer | 5 stages, O0/O3 pass | 3 stages, O3 pass after diagnostic filter fix | 3 stages, O3 pass |
| Suspended JVM segments | 34 stages, O0-O3 pass | 20 stages, O0/O3 pass | 20 stages, O0/O3 pass |
| Independent production images | 4 stages pass | 4 stages pass | 4 stages pass |
| New graph/accounting/cache/names/capacity controls | O0-O3 normal cases pass; final O3 all modes and archive parity pass | Same; allocator warmup correction applied | 40 stages including exceptions, O0-O3 and O3 archive pass; fourteen 1 MiB replacements per bounded phase |
| New exact exception-phase oracle | 13 stages, O0-O3 and O3 archive pass | 13 stages, O0-O3 and O3 archive pass | Included in the 40-stage normal audit |
| Restored native fault harnesses | Five exact selectors pass | Five exact selectors pass | Five exact selectors pass |

The three exact SDK profiles verify before execution and again at completion:
machost `f6cdb6e12017ba8d203b7ab46c4f870585e4860c7f5de6ea88bad1c0d60b78ab`,
armvm `d8434872c7bd54595bc73e03ec9b245dc4c58748f328d2854b7e6ee030120912`,
x86host `5e536f53ea4a5a695f0176a8133b4168443d09225521e13f0881ba1d4a3ef347`.
No verified runtime/pass/collector input changed, so no SDK rebuild was needed.
The largest sampled native-driver child group is 1,785,436 KiB. Samples are not
exact high waters. Linux containers additionally have a 4 GiB memory limit and
disabled network. The initially stopped ARM VM is stopped again after testing.
Disk output remains below the 16 GiB cap and free space above the 50 GiB floor.
The x86 continuation uses a tighter 4 GiB new-work cap: about 2.3 GiB used,
with about 82.5 GiB free. Its staged source and existing parent checkout remain
unchanged; no SDK or prior cache was rebuilt or replaced.

Representative commands, with SDK/LLVM/JAVA21 set to the exact qualified paths:

```sh
python3 scripts/prepare-gc.py --prefix "$SDK" --llvm-home "$LLVM" --verify
python3 scripts/test-gc-integrated.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --case integrated --case oom --case resources --case pools --output "$OUT/integrated"
python3 scripts/test-gc-audit.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --output "$OUT/audit"
python3 scripts/test-gc-audit.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --case exceptions --output "$OUT/exceptions"
python3 scripts/test-gc-roots.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --case allocate --case pending --case capture --case initialize --case constructor --case exception --case derived --case static --case bad-location --case boundary --output "$OUT/roots"
python3 scripts/test-gc-lifetimes.py --sdk "$SDK" --llvm-home "$LLVM" --java-home "$JAVA21" --case unwind --case pending --case published --case trace --case emergency --case oom --case preparation-catch --case preparation-corrupt --case owner-version --output "$OUT/owners"
./scripts/test.sh --test 'GC5 canonical collection intrinsic validates reconstructed declarations' --test 'GC7 removed forms retain migration errors and ordinary identifiers' --test 'GC5 final link rejects every reachable legacy managed deallocation family'
python3 scripts/gc-evaluation/audit_retirement.py
./scripts/update-irondocs.sh --check
./scripts/check-licenses.sh
./scripts/check-tracked-files.sh
git diff --check
```

Exact executed selections, including platform-specific metadata mutations and
foreign drivers, are in the manifest. Initial test-authoring failures (an invalid
array expression statement and unsupported direct source-link invocation), the
unwarmed capacity oracle, a mistaken exact selector and a concurrent build/read
race are retained as failed attempts, not collector failures or successful runs.
One outer log parser expected JSONL but received a successful control driver's
pretty-printed JSON; that command's complete JSON was validated separately.

The initial audit lacked an authorized matching-hardware x86-64 connection.
The maintainer subsequently supplied one; fresh current-source execution is
recorded in the x86 continuation and its separate manifest. Prior physical-host
GC9 results remain historical; no translated run is relabeled as fresh hardware
coverage. arm64e and the previously unsupported
foreign lifetimes remain unqualified. No full compiler/stdlib/platform suite,
hosted workflow, release, Git network operation or self-hosting ran.

### Separate verdicts

**Feature retirement: complete within the audited inventory and execution scope.** Production source/IR/runtime
and fresh ordinary/JVM outputs show no active managed destruction, rollback,
temporary release or legacy facade reclamation path. Removed syntax/options
refuse usefully and ordinary names work. The four maintained registered C
harnesses are migrated and pass on all three requested targets. The content-bound residual
check passes with zero unexplained matches and zero open obsolete inputs.

**GC correctness: passes the requested focused three-platform audit.**
Fresh exact counts, graph preservation/drop, sidecar exclusion, explicit-only
bounded reuse, failure safety and supported foreign roots pass as detailed above.
No production GC correctness defect was found. The GC-aware filesystem/process/
trace fault matrices pass on all three requested targets, together with fresh
O0-O3 ordinary OOM/resource and focused root/owner controls. Current-source
physical x86-64 execution closes the outstanding platform criterion, including
independent accounting/graph/capacity and supported JVM controls. No unresolved
finding remains in this audit's requested scope. This is focused verification,
not an exhaustive qualification of every program, platform or JDK.
