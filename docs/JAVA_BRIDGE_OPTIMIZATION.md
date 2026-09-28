<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge performance investigation

## Authority and checkpoint

The maintainer requests deep optimization of the unchanged public OrderBook API,
with native-only, Java-only and Java-to-native throughput and latency compared
on matching hardware. Work starts from `d61dc4cd` on local `java-bridge`.
No push, main merge, release, worktree, new JDK support or P5/P7 API expansion.
Estonia access, its isolated CPUs and workspace restrictions remain applicable.
The previous candidate/evidence stays immutable. Its performance is not accepted.

## Contracts and consumers before changes

Preserve final admission proofs, conservative unknown effects, source/class/archive
parity, typed exception containment, native initialization ordering, one live
facade per object, weak recreation, committed retention before delivery and exact
root ownership. No extra scalar bookkeeping or checks on caller thread misuse.
Do not eliminate safety checks or assertions, change the benchmark workload, or
substitute batching for the existing per-operation API. Code size is no limit.

The initial changes affect paired private JNI declarations, adapter generation,
bootstrap inventories and generated Java wrappers. Shared native analysis and
runtime stay unchanged initially. Permanent results can return an internal address
after the protected entry and commits, then look up the existing weak facade in
Java. Keep private native conversion as a cold fallback for cache misses, so no
public address constructor or new public user API is introduced. Enum parameters
can pass declaration-order tokens from Java while native typed entries retain
initialization, conversion and exception containment. Verify exact ordinal/token
mapping before selecting this transport.

Consumers: pure permanent classes, nested enums, mixed root/permanent returns,
custom snapshots, loader registration, null/cold/exceptional calls and facade GC.
Nearby negative cases: tampered native declarations, wrong generations, failed
wrapper allocation, enum preparation failure, post-mutation delivery failure,
unsafe roots/retention and incomplete export shapes remain rejected or contained.

Focused first verification uses existing exact selections from
`scripts/java-bridge/qualification-tests.json`: permanent identity, permanent
delivery failure, cold enum conversion, enum host failure, object collisions,
mixed root/retention delivery and actual OrderBook warm allocation/weak recreation.
Add source-shape checks and paired null/cold/warm behavioral assertions for changed
transports. Inspect O3 adapters and benchmark unchanged direct calls before
expanding to the affected target/JDK qualification. Failure controls may require
new injection locations, but their original behavior assertions must remain.

## Investigation sequence

1. Remove Java callbacks on permanent object cache hits; preserve cold fallback.
2. Remove JNI enum field reads with proven token transport.
3. Measure each change against unchanged engine/workload and inspect optimized
   code. Investigate cache, adapter ABI, native engine and JVM call-site costs
   further wherever measurements show a remaining gap.
4. Commit focused verified changes. Rebuild affected candidates and rerun the
   necessary positive/negative, allocation, packaging and hardware checks.
5. Present the three requested scenarios side by side, with exact artifacts,
   throughput and latency units. Report any remaining performance gap candidly.

## Evidence

Baseline: `JAVA_BRIDGE_X86_EVIDENCE.md` and `JAVA_BRIDGE_PERFORMANCE.md`.
New experiments belong in `workspace/java-bridge/evidence/optimization` and
`workspace/java-bridge/experiments/optimization`, preserving earlier evidence.

### First checkpoint: permanent cache hits in Java

Paired JNI bindings now record address-return transport and a private cold
conversion declaration. Bootstrap inventories include those helpers. Java checks
null and performs the existing weak cache lookup directly; only misses reenter
native wrapper creation. Native commits/exception containment precede returning
an address. Support exposes read-only lookup; cache insertion stays nonpublic and
all raw-address native functions stay private. Source assertions enforce this
shape alongside null, identity, zero-allocation and GC-recreation behavior.

Mac Java21 development comparison, same benchmark and three fresh forks:
old bridge 694.56 ns/cycle, new bridge 260.90, Java 68.36, native 44.98.
This is an intermediate improvement, not performance acceptance or qualification.
Evidence: `optimization/cache-java/comparison/result.json` and its exact commands,
hashes and outputs. O3 disassembly confirms ordinary adapters no longer invoke
permanent wrapping; cold helpers retain it. Focused permanent identity/delivery
failure, retention commits, object collision and actual OrderBook allocation/
weak-recreation tests all pass (five exact selections), with strict test
compilation, initial license audit and diff checks. See
`experiments/optimization/cache-java-contracts.log`.

First checkpoint committed as `cf04f74c`.

### Second checkpoint: primitive enum argument transport

Generated callers map Java declaration ordinals to the compiler's name-assigned
native tokens and pass private primitive parameters. Null remains token -1;
protected typed entries still initialize and convert the enum. This removes JNI
field reads and exception queries without broadening the public API. Mixed
root/enum declarations retain their existing receiver and reservation indexing.

Cold enum/initializer containment and enum host-failure selections pass at O0/O3.
Preparation failure injection moved to the same transport preparation point,
preserving original assertions for entered count, acquired/released strings,
native allocation, live storage and successful recovery. An initial overly broad
fixture replacement failed C compilation; restricting it to the two intended
string-bearing entries fixed that failure. Logs: `enum-token-first.log` and
`enum-token-focused.log` under `experiments/optimization`. License audit passes.

Mac Java21 intermediate comparison: native 47.84, Java 73.09, old bridge 693.51,
new bridge 95.66 ns/eight-operation cycle. See
`optimization/enum-tokens/comparison/result.json`. O3 ordinary adapters now pass
integer enum arguments without JNI field access. The remaining gap is not
accepted; next inspect separate adapter/typed-entry calls and cache cost.

### Next experiment: joint adapter and typed-entry optimization

Clang currently compiles adapters separately, preventing LLVM from removing the
protected-entry carrier/call boundary. Test Clang bitcode plus compiler typed IR
in one optimization unit, retaining the isolated C runtime, protected landing
pads, trace metadata pass and baseline target. This affects producer native build
identity and shared backend composition, not admission or reclamation analysis.
Before adoption compare identical workload, inspect optimized calls, and verify
producer determinism, scalar containment, object/enum/root exceptional exits,
exact traces and packaging on each supported target. Ordinary executables and
existing fixture object linking must retain their current path.

Joint bitcode experiment did not improve the Mac workload: 96.13 ns/cycle;
forcing entry inlining measured 101.31. Rejected both production changes. Exact
patch retained at `experiments/optimization/joint-bitcode-experiment.patch`;
artifacts and comparisons retained under `optimization/joint-bitcode` and
`optimization/joint-inline`. No LLVM toolchain/dependency change adopted.

Next pre-change review: the existing InitializedTypeSpecializer selects looping
methods, overlooking non-looping exported entry methods. Experiment with adding
native export roots to its selection, reusing the existing immutable publication
proof, state-2 guard, original cold/failure fallback and clone fact propagation.
Consumers include bridge final reclamation/retention checks and normal executable
initialization. Verify cold success, failed and recursive initialization, enum
identity, exact exception traces, safe roots and unsafe hidden reclamation,
source/class/archive reconstruction. Existing loop selection for executables is
unchanged. Use focused initialized-type audit and bridge enum/final-proof tests.

Adding non-looping export roots to initialized-type selection measured 95.30
ns/cycle, with no meaningful improvement over primitive enum transport. Retained
experiment patch and artifact; reverted the production policy change.

Next cache experiment: avoid queue polling on live hits, and drain on misses
before any insertion. Keep weak referents, exact address identity, null/cleared
handling, delayed-queue removal by Entry identity, allocation/growth exception
atomicity and bounded records. No insertion can grow without a drain, and no
hit adds a record. Root caches retain the existing lookup policy. Verify the
existing cache adversarial, delayed-queue, allocation and actual pool GC tests.

Moving cache queue draining to misses measured 97.57 ns/cycle, also no reliable
improvement. Rejected and preserved the experiment patch/artifact. Production
remains the two measured transport improvements at `2be10c1c`. Further work now
separates native engine, JNI transitions and identity costs, and checks physical
x86 performance before final qualification. None of the three rejected
experiments is included in production.

Longer unchanged-workload comparison (10 million warmup, 50 million measured,
three interleaved forks): Mac native 45.62, Java21 61.49, bridge21 82.34 ns/cycle;
Estonia native 125.32, Java21 239.72, bridge21 192.22. Java22/23 and 64-operation
latency reports are preserved in each `enum-tokens/long-comparison` directory.
These development results establish a physical x86 gain but a remaining Mac gap.

IR and disassembly identify another specific optimization opportunity:
OrderBook.match has 362 typed operations, called by two small methods (16 and 6
operations). SelectiveInlining's 256-operation bound excludes it. Experiment
with a 512-operation library bound, preserving the executable policy, recursive
exclusion, direct-call restrictions and all checks/trace metadata. Consumers are
native shared images; proof IR is unchanged. Verify normal/exceptional native
behavior, cleanup and exact traces, existing large/recursive rejection policy
for executables, plus object/enum/root production tests before adopting a gain.

Maintainer direction: Linux is the numerical performance judge; beating Java on
macOS is optional. Preserve macOS correctness and report its numbers, but focus
optimization acceptance on Linux x86-64 and Linux ARM64.

The larger selective library-loop bound measured 77.79 ns/cycle on Mac (versus
82.34 for the two fixes). Raising the shared LLVM inline threshold to 10000 in
addition measured 78.88, with no added gain; reverted that threshold experiment.
Compare the selective-loop change against the committed baseline on Linux before
adoption. Native and Java project algorithms remain unchanged.

### Third checkpoint: medium library-loop inlining

Physical Estonia Java21: native 124.47, Java 240.28, bridge 178.84 ns/cycle.
Linux ARM64 matched comparison: native 45.60, Java 65.24, committed two-fix bridge
82.58, larger-loop bridge 78.16. All three JDKs and per-64-operation latency
reports are retained at `optimization/estonia-inline/comparison` and
`optimization/linux-arm64-inline/comparison`. Mac O3 disassembly confirms no
out-of-line OrderBook.match body/call remains. Normal JNI adapters still preserve
protected status handling. No engine source or benchmark workload changes.

Seven focused tests pass in `experiments/optimization/inline-focused-tests.log`:
legacy and new library selection, native checks/cleanup/exact traces, final
non-reclamation and root proof negatives, cold enums, actual OrderBook allocation
and weak recreation at O0/O3. Test/build strict compilation and license audit pass.

Longer Linux ARM64 controls do not rehabilitate the earlier cache/LLVM experiments:
inline plus cold-cache draining 79.98 ns, inline plus joint LLVM/forced entry
inlining 78.87 ns, versus approximately 78.16 for library-loop inlining alone.
Keep these changes out of production. One last isolated compiler copy tests
prioritizing export roots in initialized-type specialization; production source
is unchanged by that experiment. Its source/jar/artifact live under
`experiments/optimization/export-priority` and `optimization/linux-arm64-export-priority`.

### Matching JNI boundary controls

The maintainer asks to establish crossing costs directly. Independent handwritten
JNI controls use the same eight primitive call shapes as the OrderBook cycle,
with almost empty native bodies and unchanged argument counts. They also measure
one scalar crossing and a native-to-Java object callback separately. All controls
pass checked-JNI functional runs before unchecked timing; consumed checksums and
allocation observations accompany three fresh forks and seven trials per JDK.

Estonia Java21: scalar 7.248 ns, eight-call cycle 60.101 ns, callback 105.295 ns.
Linux ARM64 Java21: scalar 2.710 ns, eight-call cycle 22.260 ns, callback 54.517 ns.
Evidence: `optimization/estonia-boundary` and `optimization/boundary-linux-arm64`;
exact independent sources/runner are in `experiments/optimization/boundary`.
Callbacks are much more expensive than direct JNI transitions. The controls
include loop and argument work and are not an exact additive subtraction model.
All earlier artifacts/evidence remain preserved; no production instrumentation
or installation was needed.

### Final initialization-selection refinement under verification

The isolated export-priority compiler copy improves Linux ARM64 in all three
paired forks: medians 78.45 to 76.71 ns/cycle. Physical Estonia likewise measures
178.39 to 176.81 ns. Before adopting it, extend the existing initialized-type
structure test with a non-looping library export: require explicit state-2 guards,
unchanged cold/reentrant/failure fallback and mutable-field loads. Reuse existing
recursive/failed-initialization native tests and exact traces, final bridge
lifetime/root proof negatives, retention/delivery tests, and source/class/archive
producer parity. Only root selection/order changes; publication proofs, budgets,
clone provenance and final mandatory revalidation remain unchanged. Production
qualification must use the final resulting compiler, never these experiment jars.

The refinement passes all ten focused selections in
`experiments/optimization/export-priority-focused-tests.log`, including producer
source/class/archive parity, final lifetime/root negatives, exceptional retention
commits and exact traces. Strict compilation, licensing and diff checks pass.
Adopt as D223. No further experimental compiler policy is selected. Next freeze
the complete matched production candidate, run the remaining focused host/JDK,
loader, stack and packaging checks, and collect final matched measurements. The
JNI controls establish small absolute crossings; they do not authorize weakening
identity, skipping required work or silently replacing the API with batching.

### Assembly inventory regression found during candidate refresh

Candidate `234c6e7e` values pass all 18 assembly launches; roots fail before
registration with a conversion-helper signature mismatch. Host production includes
D220's private helpers, but the package manifest and assembler omit their inventory.
Preserve this failed candidate. Add a separate host-helper inventory, consume it
when rebuilding the combined loader, and extend the existing assembly test with a
permanent nested object and missing helper-descriptor rejection. This changes
packaging metadata only, not native code or lifetime proofs. Run that focused test,
license checks, and refresh the five-case candidate using the repaired producer.
The 14 remaining Mac fixtures and 13 proofs pass; Linux selections remain running.

The repaired focused assembly test passes, including host reproducibility, nested
permanent identity, checked-JNI execution, unchanged native payload bytes and all
malformed-input rejections. Evidence: `experiments/optimization/assembly-repair.log`
and `p6a/assembly/run-7189411520427621978`. Strict build and license audit pass.

### Final measurement preparation

Assembly repair committed as `42dd0d12`. Synchronize the earlier loop-only
optimizer descriptions with D223. The performance runner now defaults to 10M
warmup and 50M measured OrderBook operations in all three scenarios (and the
separately labelled batch control), instead of 1M/2M. Record these counts and the
eight-operation cycle explicitly; expose positive integer options for reproducing
older settings. Benchmark algorithms and microbenchmark methods are unchanged.
Parser/help, licensing and diff checks pass; final matched runs will exercise
the longer settings. Preserve every earlier short-run result.

### Repaired candidate and qualification checkpoint

`42dd0d12` repairs assembly helper inventories. `e3150db6` records longer
measurement counts and synchronized optimizer documentation. The immutable
`optimization/candidate-e3150db6` passes all five assemblies and their 90 launch
checks, with translated x86 assembly launches labelled accordingly. Compiler
content identity is `be9308458112c8a27091137e690267b9bcd7ef43be65d2c6e11f2d595991c5bd`.
Final OrderBook jar SHA-256 is
`12d272a34d41c2f5b4a93b3cc8c2ad31c4a3b65ce8b6befe7f7c0c0872fc4de3`.
`optimization/final-code` contains all three exact payloads, hashes, symbol lists
and disassembly. No separate OrderBook matching-loop symbol remains; inspected
warmed adapters call the protected typed entry and branch to outlined exception
conversion on failure, without the old JNI cache callback or enum field reads.

Mac completes the remaining 14 fixtures/13 proofs, 194 asserting consumer replays
on each of Java 22/23, the 120 loader cases, fixed-candidate checks (including
Java 24 refusal), stack checks and final measurement preparation. Linux ARM64
and Estonia each pass the 36 selected bridge/optimizer cases. The ARM64 test
process used a classes directory that was rebuilt for the assembly repair while
it ran. Its behavior observations are retained, but its generated artifact
identities are not final qualification. Rebuild only the 17 fixture-producing
selections using the immutable compiler jar, then replay those outputs on 22/23.
Mac fixtures finished before that rebuild; Estonia uses an independent compiler
build, unchanged during its 36-test run.

The frozen candidate transfer to Estonia verifies SHA-256
`1db74d7f5892ec5eea5660d5a0437227603ea1312b067ad9201c3f4811c92498`.
Remaining remote validation exceeds available disk headroom. The user already
authorized cleanup of newly generated temporary test/build files: completed JVM
extraction caches are inventoried before cleanup, while jars, source, commands,
logs, transferred archives and Docker images/containers remain. A space-bounded
replay runner copy retains every original assertion and verifies extracted hashes
before removing each new temporary extraction directory; its source/diff and
runner hash are recorded. A request to remove duplicate non-temporary remote
evidence only after a verified Mac backup is pending. Do not perform that removal
without the reply. Continue ARM64 qualification and prepare the backup meanwhile.

Final timings have not started. All experimental timings above remain development
observations, not final acceptance. Active session IDs, exact pending commands
and the temporary-cleanup inventory paths are in
`experiments/optimization/current-validation.json`.

### ARM64 collection completed; refreshed x86-64 work is space-blocked

The immutable-jar Linux rerun passes 17 fixtures and 196 asserting consumers on
each of Java22/23. Both ARM64 targets finish fixed candidate/loader/stack checks,
132 validated performance observations, 30 latency reports and 63 retaining-call
observations. Backup compression/transfer was explicitly paused, its output size
checked stable, and Linux then Mac timings ran sequentially before transfer
resumed. The exact pause interval is recorded in the archive's
`measurement-pause.json`. Results and limitations are in
[JAVA_BRIDGE_OPTIMIZED_PERFORMANCE.md](JAVA_BRIDGE_OPTIMIZED_PERFORMANCE.md).

Linux ARM64 Java21 cycle medians: native45.26ns, Java68.38ns, bridge76.97ns. The
bridge's 103.93M operations/s remains below Java's116.99M; the requested ARM64
speed target is not met. Warm cached-object return is4.01ns versus the original
61.72ns. Do not infer an unavoidable lower bound by subtracting JNI controls.
Further performance acceptance/implementation decisions remain open.

Estonia now passes the36selected cases, repaired assembly regression,196consumer
replays per Java22/23 and30minimal-JVM launches of the exact final candidate.
The next loader stage stops before execution at the explicit12GiB headroom guard
while roughly4GiB is free. Preserve its nonzero controller status as a space
precondition failure, not a compiler/JNI assertion failure. Only unfinished
loader/candidate/stack/preparation/performance work should run after space recovery.

The backup inventory contains13,508immutable files totalling38,316,334,620bytes.
It excludes generated extraction temporary directories, retains original artifact
bytes and hashes, and groups archive members to compress repeated support sources.
Earlier incomplete compression streams are retained separately. Verification must
stream every archive member against the inventory before any authorized duplicate
removal. The requested permission is still pending; no non-temporary remote
evidence has been removed. Temporary-cleanup inventories contain no crash reports.


### Remaining-gap experiments and disk-bounded qualification

Keep the frozen producer and candidate unchanged. The existing enum pass only
specializes constant SSA inputs, while bridge enum conversion joins produce a
dynamic argument. An isolated engine copy adds four BUY/SELL forwarding methods;
an experimental facade override routes the existing methods through them. The
project benchmark remains unchanged. This deliberately expanded scratch surface
is a mechanism probe, not a production API or qualified package. Its plan, exact
sources, commands, output and hashes are under `experiments/optimization/enum-case-probe`.
Checked JNI and the asserting allocation/identity consumer pass with zero warm
Java allocation. Three Linux ARM64 forks give native 46.67, Java 65.92, current
bridge 78.08 and experimental bridge 76.48 ns/cycle. This small improvement does
not close the gap; do not adopt an API expansion or claim performance acceptance.
Any production version would need typed compiler specialization without changing
the public surface, plus null, cold/failed/recursive initialization, mutable enum
fields, containment, safety-proof and source/class/archive regressions.

A second isolated compiler copy revisits joint adapter/entry optimization because
D223 changed the entry bodies after the previous rejected experiment. It applies
the archived patch to three current source copies and updates a copy of the frozen
compiler jar, leaving production source/build untouched. The existing pre-change
containment, trace, proof, determinism and packaging requirements still apply.
Its artifacts and comparison are under `experiments/optimization/joint-current`.

The Estonia backup now independently verifies all 13,508 files and 38,316,334,620
bytes. Archive SHA-256 is
`918fb88e0f0bff1b3b5ceedbbb1de542e703ef8093ca07e53bf1d1b8a87d9845`;
manifest SHA-256 is
`126afe5d06d0c6623fd1cd438afda40c8b19509f05ad3ae83ff6ed0559cd07d6`.
Remote originals remain pending the cleanup permission. The completed minimal
JVM checks' new extraction cache was inventoried and removed under the prior
explicit temporary-file authorization, reclaiming 747,873,552 bytes.

A scratch performance controller preserves every production runner assertion,
workload, fork and pairing check, adds a fresh scoped JVM temporary directory,
and inventories/removes that directory only after the preceding command's checks
return. This bounds disk growth without removing existing evidence. Preparation
passes; physical x86-64 measurements are running without competing task builds,
tests or bulk transfers. The original runner hash, controller hash, exact patch,
actual commands and per-cache hashes are recorded with the evidence. The loader
stage's larger retained artifact requirement remains blocked, not bypassed.


### Refreshed physical x86 collection and finite-enum follow-up

The bounded runners complete 132 performance observations and their summary,
30 latency reports, 63 retention observations, 90 fixed-candidate launches, and
six bounded stack cells plus adaptive child-failure diagnostics. Final e315
Java21 medians: native 123.99, Java 207.94, bridge 177.05 ns/eight-operation cycle;
64.52, 38.47 and 45.18 million operations/s. The bridge beats Java on all supported
JDKs, but the standalone gap remains. Updated tables and limits are in the
optimized performance report. All final remote evidence is copied and verified:
2,462 files, 1,533,814,764 bytes, archive SHA-256
`0f0547d4e6ee59b320be490ca6c63e941a47d64de89380ff6f7161c93ffed657`.
Remote originals remain unchanged. The 114-case loader matrix still requires
more retained-artifact disk space than is available; cleanup permission remains
pending. No safety/assertion failure is being bypassed.

Ten seeded, shuffled physical Estonia Java21 forks compare the unchanged project
workload through current and experimental bridges. Median cycle times: native
124.04, Java 248.24, current 180.19, joint adapter/entry 187.59, constant-enum
scratch entries 171.98 ns. All raw fork values are retained; JVM runs show material
variation. Both experimental builds pass checked JNI and the original asserting
identity/allocation consumer with zero warm Java bytes. Reject joint bitcode
again. The enum result warrants a compiler-only prototype, not a new public API.

Pre-change review is in `experiments/optimization/enum-tail-plan.md`: within
already-proved state-2 export contexts, duplicate only a closed, single-entry
continuation of a phi joining two exact enum constants, optionally null. Reuse
existing predecessor branches; add no runtime tests or bookkeeping. Preserve
signatures, export roots, source/exception metadata, cold/recursive/failed paths
and all final lifetime proofs. Unsupported CFGs remain unchanged. The isolated
compiler copy lives in `experiments/optimization/enum-tail`; production source
and the frozen producer are unchanged. Before adoption require paired CFG,
mutable-field, initialization, safety-negative, reconstruction and checked
native regressions plus an unchanged-API physical Linux comparison.


The continuation prototype produces through the unchanged final lifetime gates,
passes checked JNI and the original zero-warm-allocation consumer, and emits
231 duplicated blocks across eight protected entries. Existing constant-argument
specialization now sees BUY/SELL in the createLimit/createMarket callees. Linux
ARM64 still shows no gain: native 44.67, Java 63.78, reference bridge 79.18,
prototype 80.02 ns/cycle in the three-fork comparison. A ten-fork physical x86
comparison is running before disposition. This remains scratch-only.

A separate isolated generated-Java experiment changes only the permanent cache's
initial bucket count from 16 to 256, retaining the exact frozen native payload,
weak references, queue drainage and all root caches. Its pre-change plan is
`experiments/optimization/cache-capacity-plan.md`. Ten shuffled Estonia forks
invoke the same benchmark wrapper and inspect bucket chains only after timing.
Current median is 177.17 ns/cycle; larger cache is 175.33. Three current runs have
one collision, while all larger-cache runs have none, but the ranges overlap
and collision-free runs still vary. Only six of ten paired runs improve. Do not
infer that collisions explain the remaining gap or adopt the larger cold array
on this evidence. Checked JNI and zero warm allocation pass. Exact sources,
class/native hashes, all fork values and post-timing chain counts are preserved
under `experiments/optimization/cache-capacity`; no production cache change.


The ten-fork continuation comparison finishes: physical x86 current 180.99,
prototype 177.80 ns/cycle (nine of ten paired runs lower). Repeating on Linux
ARM64 resolves the earlier uncertainty: current 76.86, prototype 78.59 ns/cycle,
with eight of ten pairs worse. Do not enable this across all targets.

Pre-change scope refinement: the Java Bridge currently supports host-native
builds only, classifies the host as Linux amd64/x86_64 or either ARM64 target,
and audits final ELF machine identity against that host selection. Select the
continuation optimization only for Linux x86-64 during the actual typed native
transformation, before final lifetime proofs. Keep direct pass tests explicit
and leave ARM64 and ordinary executable IR unchanged. Existing host-neutral Java
API/generation identity remains based on original source/API/compiler identity;
target/build identity already includes the emitted LLVM and target. Do not infer
target selection from native object contents or bypass its audit.

Add a separate 2,048-operation total duplication budget and 256-operation
per-continuation cap; old initialized-group budgets remain intact. Preserve
original cold blocks, all export roots and callable provenance. Cover policy
selection, exact enum/null paths, nonconstant joins, outside entries, loop
backedges, SSA renaming, source/exception metadata, and budget refusals. Re-run
focused native initialization/artifact/trace/safety and bridge proof tests, then
refresh production artifacts as space permits. Preserve the old compiler/build
in `optimization/frozen-producer-e3150db6` before rebuilding. The remote loader
and additional retained artifacts remain subject to the unresolved disk-space
blocker; no remote evidence removal is authorized yet.

Production implementation review found a bounded-growth corner case: a recursive
export's fast body can be emitted in both the guarded root and its guardless
clone. Restrict this new duplication to guarded nonrecursive export roots,
excluding any function already selected for a callee clone. This keeps the
separate growth accounting exact and leaves the eight measured OrderBook entries
eligible. The new structural regression covers recursive export fallback as well
as enum/null SSA, external operands, invokes/landing pads, mutable and unknown
inputs, outside entries, loop backedges, source metadata and budget refusals.
The first structural pair and five focused Mac native/artifact/safety tests pass.
Rosetta x86 bridge proof/consumer tests are running against a fixed pre-review
build; retain that distinction and re-run affected checks after the final build.


### Continuation experiment disposition: not retained

The reviewed implementation passes both structural selections, all seven focused
Rosetta x86 checks, and the additional actual OrderBook class/archive O0/O3
producer comparison against paired Java, including pool recovery and checked
JNI. Physical Estonia checks also pass on Java21-23 with zero warm Java
allocation. Passing safety checks does not establish a performance improvement.

The repeat physical comparison does not reproduce the initial ten-fork gain:

| JDK | Forks | Current bridge ns/cycle | Continuation experiment ns/cycle |
| --- | ---: | ---: | ---: |
| 21 | 10 | 176.96 | 179.32 |
| 22 | 3 | 186.56 | 181.47 |
| 23 | 3 | 186.04 | 181.24 |

Java21 improves in only three of ten pairs, reversing the earlier nine-of-ten
result. The paired native median is 124.06 ns/cycle and Java21 is 248.23, with
material Java fork variation retained in the raw evidence. Extra Java22/23
observations favor the experiment but do not resolve its inconsistent Java21
result. Do not select only the favorable run or claim a universal regression.

The reviewed and initial prototype `.text` sections are byte-for-byte identical:
39,960 bytes, SHA-256
`bd61f4383ab5cee8c00070196ee1267cb06cb5d358bda92bfa23a64801940b07`.
Thus a changed machine-code body does not explain the reversed small result.
Three clock-included latency forks per supported JDK are also preserved, but
that supplement lacks an interleaved current-bridge latency arm and cannot by
itself establish this optimization's gain. No performance acceptance is inferred.

Preserve the complete reviewed source, regressions, proposed decision text,
compiler, jars, LLVM, disassembly, command lines and raw results under
`evidence/optimization/enum-continuation-production`. Despite its historical
folder name, this is now an **unadopted experiment**, not a replacement candidate.
Its jar SHA-256 is
`61bdf2bd2a7bc7bee4a6f4964ded35a3295499fbb7a528c8179acda166aa9d49`;
native payload SHA-256 is
`30236f3b3ba48608de33ce3f7b2c393af9a940578bd6856fbf6151215c6afdc4`.
The physical evidence archive SHA-256 is
`28f76d8715388bbfe0992510c5797b236c163364a148258727be90a133662817`.
All remote originals remain. Only the runner's newly generated temporary
extraction files were inventoried, hashed and cleaned after assertions passed.

Reversed only this experiment's uncommitted source/documentation hunks and
preserved its two newly created source files in the evidence directory. No D224
is adopted. The rebuilt compiler's 799 jar entries match the frozen e3150db6
producer byte-for-byte; final baseline checks are recorded beside that identity
comparison. D220-D223 and the fixed candidate remain the retained implementation.
The rejected joint-bitcode and larger-cache experiments likewise remain scratch.

The remaining physical loader qualification is still blocked by available disk
space. Existing non-temporary files, transferred artifacts and containers were
not deleted. Verified backups are available, but permission to remove duplicate
remote evidence is still pending. ARM64 performance remains behind Java; the
user's close-to-standalone target and final numerical acceptance remain open.


### Repeat direct enum-entry selection before private generation work

The previous turn made evidence-driven progress and retained e3150db6 after
rejecting continuation duplication. The disk-space blocker still applies to
physical loader qualification, but does not prevent a small repeat using the
already transferred constant-enum helper probe.

This mechanism differs from phi continuation duplication: generated Java facade
branches can let the JVM select a native entry with a fixed enum argument.
Ten newly shuffled physical Estonia forks measure 172.21 ns/cycle versus 179.21
for the current bridge, improving in all ten pairs. Native is 124.27 and Java
242.55 in this run. The earlier helper experiment measured 171.98 versus 180.19.
The original checked JNI/zero-warm-allocation consumer also passes again.
Exact existing jar/class hashes and all observations are in
`experiments/optimization/enum-entry-repeat`; the copied physical archive is
`cbc4864fb97383e0fb91caf38f0b531412239dafa6ba5508cae6cc580a6b673a`.

A ten-fork Linux ARM64 repeat measures native 45.97, Java 65.49, reference 77.43,
and helper probe 76.27 ns/cycle. Its raw report inherited the earlier script's
three-fork prose label; the executed source and 80 preserved records show ten
forks. `arm-scope-erratum.json` records this without rewriting measurements.
This remains mechanism evidence, not adoption or a performance acceptance.

The expanded scratch helper API cannot ship. Investigate private generated JNI
entries and typed protected-entry variants using the same source callable,
constant enum token mapping, generic fallback and final lifetime proofs. Preserve
public signatures, null/cold/failed initialization, weak identity, ownership,
exception containment, conservative unknowns and source/class/archive parity.
No production edit yet; record the precise consumer map and focused verification
selection before implementing the variant machinery. Both rejected continuation
sources and the fixed producer remain available for identity checks.


The nonconstant-enum control uses volatile enum fields with the same asserted
engine operations. Its first ten physical forks are mixed: current 183.28,
helper 185.58 ns/cycle. A second controlled comparison measures constant callers
at current 177.76, helper 171.85 and shared-result Java wrapper 181.52; nonconstant
callers measure 180.08, 174.20 and 177.14 respectively. The shared wrapper uses
exactly the existing helper native payload, so this isolates Java wrapper shape.
Keep all forks: the shared form varies between roughly 169 and 185 ns in the
constant case. No private-entry production implementation is adopted from these
observations. Dedicated JIT/GC diagnostic runs are next, separate from numerical
acceptance and ordinary flag-free measurements.

The pre-change review also identifies a required null-ordering guard: compare
against Java enum constants only after ruling out null, so null calls do not
newly initialize the enum projection. Fixed native conversion must retain the
existing selected ensure/load path; only the existing state-2 proof may fold its
load. The consumer map and paired verification plan are preserved in
`experiments/optimization/enum-entry-repeat/private-entry-plan.md`.

JIT diagnostics identify an actual first-loop-exit `unstable_if` trap at bytecode
7, followed by benchmark recompilation during measurement. No measured-phase GC
occurred. In these diagnostic forks, both approximately 189-190 ns slow results
also have an extra address-mismatch probe in the weak identity cache; other
forks have no such probe. This is correlation with concrete branch profiles,
not a claim that every variation has one cause. `jit-findings.json` retains the
profiles and runtime traps.

A separate warmup experiment performs ten one-million-operation invocations
instead of one ten-million-operation invocation, preserving total work and
assertions. All six diagnostic forks complete benchmark compilation before
measurement. Forty ordinary, flag-free forks then measure current 177.39,
original helper 175.50, shared-result helper 168.77 and Java 247.35 ns/cycle
(medians, ten forks each). Cache-related variability remains. These are new
experimental observations, not replacements for the frozen final report.
The copied settled evidence archive is
`bac1aa9eff3526eb0b65cb70644fff82ade39281290e67364af64e6b4f03bb80`.

Private-entry implementation is now under test, not adopted. It adds at most two
entries per eligible source method, with no Cartesian expansion: one two-value
enum, at least two other primitive arguments, a permanent instance receiver,
and no String/enum result. Original generic entries remain proof roots. Fixed
entries omit the enum formal from both JNI and typed ABIs and retain selected
native ensure/load behavior. Java null guards precede enum constant field reads.
All variants participate in additive synthesis, exception closure and final
non-reclamation checks. Private bindings share the existing result converter;
registration inventories deduplicate that exact converter declaration. C input
carriers are checked against the typed entry ABI, including byte normalization
for booleans.

Initial verification passes the existing paired OrderBook producer at O0/O3
and two new focused tests for generic-root preservation, exact fixed metadata,
forged metadata rejection, retained unsafe generic branches in every unfreed
mode, cold null without native enum allocation, both constants, failed native
initialization, target exceptions and private-only declarations. Follow-up
regressions, final payload inspection and actual-producer Linux measurements
remain required before adopting or committing this production change.

The full focused selection passes eleven Mac tests, plus both new tests on Linux
ARM64 and translated x86 (functional evidence only). Actual-producer paired
comparisons each retain 100 observations. ARM64 measures ordinary 77.92 to 77.32,
settled 78.25 to 75.86 and volatile-enum 78.84 to 77.54 ns/cycle; lower paired
results occur in 7/10, 10/10 and 9/10 forks. Physical x86 measures ordinary 179.10
to 182.50 (5/10 lower), settled 175.85 to 174.94 (7/10), and volatile 181.78 to 180.24
(7/10). These mixed x86 observations do not yet justify adoption.

Frozen prototype inputs and payload inspection are under
`evidence/optimization/private-enum-entry`. Physical jar SHA-256 is
`4204d78fdf4dde1294b4d47452ec7978bdb1d6a63ed4dc7d84256d25ed0f0969`,
payload `6cb136f1efa7c1c734e7e0fcad1b9f1c99f878d5c1d11b0db121f7086a4f9d19`;
ARM jar `21bf712e7b887970d1c1a9a401bf2d700f39670a4e1bc5b2a1cdf4ae1e42ccbd`.
Physical comparison archive is
`a37dd0b366eab425a16af3038305809a605133c78cd630489425e2e270904624`.
The actual payload retains all four protected fixed entries, native initializer
state guards and contained cold paths; matching is inlined in each entry.

Next isolate weak-cache collisions with both retained and private-entry payloads,
each at 16 and 256 initial buckets. Keep exact payloads, collect bucket occupancy
only after timing, retain every fork and compare settled/nonconstant callers.
This is a mechanism control; widening production cache capacity has not yet been
adopted. No benchmark or source workload has been changed in production.

The eighty-fork physical cache control measures settled medians of 176.39ns
(retained 16), 174.40 (retained 256), 175.59 (private 16), 167.98 (private 256). Collision
forks are 4/10, 0/10, 7/10, 0/10 respectively; private 256 spans 167.08-169.62ns.
The volatile-enum medians are 181.40, 179.41, 174.09, 174.28 ns respectively.
All observations remain in `cache-control-evidence`, archive SHA-256
`600e854b176c4aa806f1e8d574efa3c060d3f08a5e6afc38f510995005aa033f`.
This supports testing the combined private-entry/256-bucket permanent cache
implementation. Per-root caches remain 16; weak references, draining, entry
allocation and growth algorithms remain unchanged.

The wider-cache focused run passes identity/allocation/fault/retry, rooted facade
and private-entry cold/failure checks. The OrderBook allocation test correctly
fails its old hardcoded thirteenth-identity growth assumption, after both warm
zero-allocation assertions pass. The fixture now forces table growth outside
measurement according to actual capacity and retains its exact live-identity
count, bounded repeated recreation and no-later-growth assertions. The failed
run and focused retry are retained; dependent qualification waits for the retry.

The focused OrderBook allocation retry passes. A translated x86 component run
then exposes intermittent 184-byte allocation measurements in its old unmatched
warmup/measurement loops (two failures across the first four child processes).
The actual OrderBook warm allocation test remains zero. A scratch helper that
warms the exact checked hit loop and its exit passes five fresh runs with zero
bytes; the assertion and 500,000 measured hits remain unchanged. Three separate
Flight Recorder runs with TLABs disabled report zero main-thread allocations
inside the measured window, so they do not identify the intermittent 184-byte
allocation's source. Do not claim that source was proved.

Updated the component harness to warm that exact measured helper. Its full
identity/growth/collection/recreation/delayed-queue/allocation-failure assertions
pass on Mac, Linux ARM64 and translated x86. Physical Estonia then passes both
production and injected-failure components on Java 21, 22, 23 (six child processes).
All failed logs and diagnostic recordings remain under
`private-enum-wide/cache-allocation-diagnostic`. The wider candidate's x86 and
ARM native `.text` sections are byte-identical to the private-entry 16-bucket
prototype: the capacity change affects Java cache storage only. Actual candidate
throughput/latency runs are now in progress; adoption remains pending those
results. The frozen compiler's jar entries match the rebuilt compiler exactly
after the test-only warmup adjustment.


### Combined candidate, final inventory correction

The private-entry/256-bucket candidate completes 118 throughput observations
and 30 batch-latency observations on each Linux target. Physical Java21 settled
medians are 123.99 ns/cycle native, 247.36 Java, 174.59 retained bridge and
167.90 candidate. All ten paired settled and all ten original-protocol candidate
forks improve. ARM settled medians are 46.60, 65.23, 78.12 and 75.56 respectively;
nine of ten candidate pairs improve, but the candidate still trails Java.
These are observations of the frozen per-host jars, not numerical acceptance.

Java22 physical latency initially regresses: three-fork mean/p99 medians
1457/1530 ns become 1507/1551 ns per 64-operation batch. A predeclared ten-pair
repeat retains every fork: original warmup gives 1428.5/1457 versus 1417/1456;
a separate ten-pair 200,000-batch warmup gives 1422.5/1439 versus 1391/1403.
The original negative cell remains valid evidence of fork variability. The
repeat does not establish a universal latency improvement or identify its exact
cause. ARM Java23 throughput is effectively unchanged in the three-fork sample.
Raw repeat commands and observations are in `private-enum-wide/x86/latency-repeat`.

Assembly succeeds structurally, but actual Mac and ARM assembled-jar loading
fails before native execution with `duplicate Ironwood native signature`.
The new variants share one conversion helper; Java/native registrations already
deduplicate it, but `BridgePackageManifest` serialized it once per binding.
The loader correctly refuses the duplicate inventory. Correct only that exact
manifest conversion list, preserving duplicate-signature rejection. Extend the
existing assembly regression with a two-constant enum method returning the same
permanent facade, and exercise both variants plus null after assembly. Preserve
all failed combined jars and logs. Rebuild host artifacts with the corrected
producer, then reassemble and qualify the new identities; earlier timings remain
attached to their original per-host artifacts.


### D224 implementation checkpoint: 694ada30

Committed private fixed enum entries, the 256-bucket permanent cache, exact
shared conversion manifest inventory and focused regressions locally on
`java-bridge`. No push. The corrected frozen producer in `private-enum-final`
produces all three target jars and one assembled jar. Mac, Linux ARM64 and
physical x86 each pass all 18 Java21-23 class/module/executable checked/unflagged
consumer cases. Both Mac Java24 refusal launches stop before extraction. The
latest private-ABI/cold-null/failure fixture also passes Linux ARM64, including
boolean carriers and a source helper-name collision. Source hashes still match
that frozen compiler; licensing and diff checks pass.

Three-fork final assembled-jar measurements finish on all three targets. Physical
Java21 native/Java/bridge medians are 124.00/247.39/172.53 ns/cycle; Linux ARM64
45.94/64.98/76.37; Mac 45.65/67.20/72.24. All raw forks remain, including slower
bridge observations. The bridge is faster than Java on physical x86 across
21-23, still slower on ARM64, and still behind standalone native everywhere.
Full numbers, identities and limitations are in `JAVA_BRIDGE_D224_PERFORMANCE.md`.
Do not claim the all-Linux performance goal or numerical acceptance complete.

Estonia now has only about 659 MiB disk free but 15 GiB available RAM. A new
8 GiB tmpfs container is running the exact 114 loader cases with the corrected
frozen compiler. The only runner adjustment inventories and cleans new child
extraction files after all original assertions and payload comparisons. Built
fixtures remain in RAM until archived and verified on Mac. No installs, remote
source edits, pre-existing-file deletion, image/container deletion or publishing.


The first RAM attempt fails its first child before bridge code: Docker's default
`--mount type=tmpfs` is noexec, so HotSpot cannot map `inspector.so`. Exit 1,
not OOM; the failure and patched-runner identity remain on host/Mac. That
container exited and its new temporary RAM files were released. A second named
container explicitly permits execution only on its new scratch tmpfs. Its
controller also stays alive after test failure until the generated evidence is
archived and verified, preventing loss of future failed-run scratch evidence.
No production change or assertion relaxation is involved. Session 78270 runs
that attempt; a separate bounded-output SSH session waits for readiness and
streams its archive to Mac. Existing remote files and containers are preserved.


The second controller mistakenly reused the first controller's output directory
and exited before any tests; it did not overwrite that directory. Corrected the
scratch runner to use a new `loader-ram-control-exec2` directory and a third
named container. Its mount inventory and a separate `ctypes.CDLL` child confirm
that `inspector.so` maps successfully before the full cases run. These are
harness/environment retries, not production changes or passing qualification.

### Refreshed physical loader matrix and final experiment

The executable RAM retry passes all 114 original loader cases, O0/O3 times
Java21-23 times 19 scenarios, using the final frozen D224 producer. The original
assertions remain unchanged. An exact-segment archive deduplicates repeated raw
ZIP members without rewriting file contents. Local reconstruction verifies every
one of 2,321 files, 5,945,569,181 bytes and 857 independent identity entries;
all 114 result records have exit zero. Archive SHA-256 is
`24653ac21b3cf088ebd02a84d8cb4e7e4edc693c4cbf8fd344ecd72c30ab569b`.
The archive, independent inventory and verification record are retained under
`private-enum-final/x86`; the decoder is `private-enum-final/verify-packed-loaders.py`.
The obsolete slower stream was stopped and its
partial archive preserved. Only after verification was the controller permitted
to release its newly generated RAM scratch. Existing remote files, archives,
images and containers remain. The earlier disk-cleanup request is no longer
needed to finish this matrix.

An isolated joint adapter/typed-entry optimization was reconsidered after D224.
Its full source/native/Java exception traces match the baseline; private enum
structure/native O0/O3 regressions and checked OrderBook zero-allocation checks
pass. Native assembly removes the separate entry calls. Nevertheless, ten paired
ARM forks give current/prototype medians 74.6648/74.3933 ns/cycle, a paired median
change of -0.0946 ns and paired mean change of +0.7622 ns. Seven of ten improve,
but two prototype forks exceed 80 ns versus about 75 ns for the baseline.
Reject this inconsistent result; production remains commit `694ada30`. Archive
work was explicitly paused throughout timing. Keep all source, payloads,
commands and observations in `experiments/optimization/joint-after-fixed`.

The final report remains `JAVA_BRIDGE_D224_PERFORMANCE.md`. The three-scenario
physical Java21 throughput is 64.51M native, 32.34M Java and 46.37M bridge
operations/s. Linux ARM64 is 174.14M, 123.12M and 104.76M respectively. Numerical
review is pending; all-Linux superiority, native parity and release readiness
are not achieved. Further implementation needs a demonstrable gain under the
existing safety/API contracts; do not adopt rejected experiments or relabel
their measurements as production evidence.

### Private matching-context experiment: rejected

The next inspection found repeated incoming-order side/type/price loads inside
native matching. Scratch copies of both Ironwood and Java pass those immutable
operation values directly into the private `match` helper. Public API, operation
count, matching algorithm, pool ownership and compiler proofs remain unchanged.
The pre-change contract map and validation plan are in
`experiments/optimization/match-context/PLAN.md`. This is an engine experiment,
so native-only and Java-only baselines are measured for both source versions.

Five executions produce identical 771-line behavior output: old/new Java,
old/new bridge and new standalone native. Cases include both sides, multiple
levels, partial fills, market/limit orders, reduction, cancellation, capacity
exhaustion and null-side refusal. Checked-JNI consumers retain strict zero warm
allocation on ARM and physical x86. Two harness failures remain recorded: a
missing temporary directory correctly refused by the loader, and unavailable
`AssertionError` in the scratch native test, replaced by `RuntimeException`
without changing its assertions or expected exception catches. No production
failure or proof relaxation was involved.

Thirty-two shuffled throughput observations per Linux target retain ten bridge
pairs and three native/Java pairs. ARM old/new bridge medians are 82.8183/82.2798
ns/cycle; paired median/mean changes are -0.3291/-0.0277 ns, six of ten improve.
Physical x86 medians are 171.2936/169.5048; paired median/mean changes are
-0.0210/-0.3271 ns, five of ten improve, with large outliers in both directions.
ARM native medians improve 48.7750 to 45.6694, but x86 native remains effectively
unchanged at 125.0096/124.9327. Java anchors are 70.4886/72.0343 on ARM and
250.0353/250.4562 on x86. These are experiment-specific observations, not
replacements for the production report. The changed assembly removes the
identified repeated loads and branches, but no repeatable bridge gain is shown.
Reject the experiment; production engine and compiler remain unchanged.

All sources, patches, commands, input hashes and raw observations remain under
`experiments/optimization/match-context`. The physical evidence archive SHA-256
is `1e8fc71d9cbb66d7d1f85f035c04a5e3b87cf8ba397439ce5af70e06ad0f4a4a`;
27 handoff files and 22 locally matching measurement inputs were hash-verified.
Two pre-existing AppleDouble metadata files included in the remote inventory
were separately fetched and verified. No timing overlapped builds or transfers.
Remote extraction used new executable RAM scratch, with per-child inventory
before authorized temporary cleanup; remote artifacts and containers remain.
Numerical review and the outstanding performance goals remain unresolved.

### Current artifact and result audit

At `e39e70fe`, independently reconcile the final OrderBook compiler jar, all
frozen changed source hashes against the current checkout and `694ada30`, and
the assembled jar against each target's actual native payload. All match.
Re-read 54 launch stdout/stderr/exit records and all 126 throughput/latency
records, recompute throughput medians and batch mean/p99 medians, and verify
the published summary. All match; each latency batch contains 64 operations.
The two Java24 refusals and 114 physical loader result records remain correct.
`private-enum-final/current-artifact-audit.json` records this bounded audit;
it does not relabel historical fixtures or establish complete P6b readiness.
The initial audit parser omitted singular latency units such as `1.000 micro`;
its corrected singular/plural parser passes, with the initial script retained.

Correct the historical P6 evidence page's current-report link to D224. No
production code, payload, benchmark, supported Java version or test assertion
changes. The phase records and D209 experiment remain historical evidence with
their original compiler/payload identities. Current numerical acceptance is
still the maintainer's decision; Linux ARM64 superiority and near-standalone
performance remain unproved. No further evidence-backed implementation change
is presently identified under the unchanged API and safety contracts. Do not
substitute batching, new JVM flags, broader versions or weaker proofs to close
the goal without a material contract decision.
