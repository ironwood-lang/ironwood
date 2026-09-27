<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge implementation progress

## Authority and continuation

The maintainer authorized implementation on 2026-09-26, following
[JAVA_BRIDGE_PLAN.md](JAVA_BRIDGE_PLAN.md). Work exclusively on local
`java-bridge`, created from `be61dee9bb74022900dc791ee15029850e694558`.
Canonical checkout and both origin URLs were verified; the initial tree was
clean. Do not merge, push, publish, create worktrees, or change `main`.
Continue P0a/P0b/P0c -> P1 -> P2 -> P3a/P3b/P3c/P3d -> P4 -> P6a -> ARM64 P6b.
P5/P7 remain deferred. A checkpoint is not a phase exit.

The maintainer selected Java 21-23 with the existing Java 24+ refusal for this
implementation run. This records D209's pre-P6 product decision: keep the bounded
support matrix while broader-version qualification remains outside this run.
Still execute the separately identified Java 25 experiment in P2 and report
findings and a recommendation for later review. Do not broaden support.
Numerical performance acceptance belongs to the maintainer. Real x86-64 hardware
qualification is pending under D213, not waived or passed.

## Current checkpoint

P0a/P0b/P0c, P1, P2 and the P3a compiler-admission gate pass for continued
implementation under D213. The
[P0 evidence audit](JAVA_BRIDGE_P0_EVIDENCE.md) maps all ten cases to their proofs,
matched runtime/static evidence and production handoff. Real x86-64 hardware
stack qualification remains pending. P1's production multi-root native library,
D202 dependency and D210 signature gates pass. The
[P2 audit](JAVA_BRIDGE_P2_EVIDENCE.md) maps the public value-producer, loader,
exception, distribution and D209 gates to matched evidence. The
[P3a audit](JAVA_BRIDGE_P3A_EVIDENCE.md) records the admitted compiler contracts
and remaining producer boundaries. Concrete signatures, protected root/String and permanent/
String composition pass focused checks. Exact enum input/result conversion and
mixed permanent object/enum/String proofs and protected entries also pass.
Mixed reclaimable object/enum/String entries now preserve proved retained slots,
destruction and rollback across native conversion failures.
Cleanup analysis separates descriptor bodies from implicit entry initialization.
Custom exception metadata/getter proofs and native extraction checks pass;
generated Java snapshots remain P3b work. Exact synthesis and recorded native
optimization now carry conservative source facts into final closure checks,
including structurally proved unpublished constructor rollback.
Final root validation now rechecks slot payloads, getter effects, root/view
storage and separate destruction/rollback, including actual specialized helpers.
Exported snapshot declarations seed final getter closure, including never-thrown
types. Mixed permanent object/root/String/enum proofs and matched native payloads
preserve root dependencies and reject reclamation through generated destruction.
Automatic concrete-object admission now selects and proves root, mixed and
permanent contracts, including the actual OrderBook's complete public surface.
P3b object generation identities, the generated weak permanent cache and concrete
permanent Java declarations pass focused component checks. Concrete permanent
JNI conversion and generated macOS jars pass initial O0/O3, host allocation/
delivery and object generation/loader collision checks. Generated enums and mixed
permanent object/enum jars pass cold conversion and initializer containment.
Enum host metadata/delivery failures also pass. Custom snapshots and public
integration remain.
Next implement P3b Java/native permanent facades, weak identity caching and
enum/custom snapshot projection, then P3c/P3d, P4 and P6. Those phases and release
readiness are not complete.

- Read repository instructions, contribution/license requirements, the complete
  implementation plan, D188-D213, and the shared-analysis regression lessons.
- macOS ARM64 host: macOS 26.6.2 (25G83), Darwin 25.6.0.
- Colima `ironwood-tests` is running ARM64 with 6 CPUs and 8 GiB RAM.
  Select Docker context `colima-ironwood-tests` explicitly; the ambient context
  targets a stopped VM. Existing ARM64 and x86-64 toolchain images are available.
- Ambient Java is Oracle 25.0.4.1, not a bridge test JDK. Provision exact Temurin
  archives separately and never fall back to ambient Java for bridge evidence.
- Verified official Temurin 21.0.12.1+1 archive SHA-256 values against release
  asset metadata and companion checksum files, then installed all three targets.
  macOS offline preflight passed. Linux image setup preflight passed on ARM64
  and Rosetta x86-64; this is setup evidence, not a bridge case pass.
- Physical CPU is Apple M5; local LLVM is 23.1.0. Colima guest is Ubuntu 24.04.4,
  kernel 6.8.0-117-generic. Active x86-64 handler is Lima Rosetta with OCF flags.

## Pre-change contracts and focused verification

P0a introduces internal resolved export roots, transport descriptors and immutable
proof outcomes without changing ordinary compilation or admitting public exports.
Unknown/rejected proof outcomes must remain distinct from accepted facts. Input
reconstruction must reproduce callable/type identities and source locations.
The later semantic analyses must run before final free validation, use complete
normal/exceptional closures and preserve conservative unknown effects. No observer
snapshots or fixture-specific answers may authorize native execution.

Consumers to preserve: ordinary ownership/free checks in every unfreed mode;
pool release/checkout and owned helpers; generic dispatch; constructor rollback;
source, class and archive reconstruction; native reachability and initialization.
Native-only output must gain no bridge state or D132/D133 overhead.

Initial focused checks will cover root resolution (overloads and reconstruction),
ABI type distinctions, immutable proof outcomes and fail-closed selection. When
shared analysis is connected, pair safe root set/clear/helper writes with unsafe
slot transfer, unknown owner, child slot and hidden publication cases; pair actual
OrderBook permanent-storage/rollback proofs with reachable exposed reclamation
and unknown deallocation effects. Preserve every P0 case and later revalidate
against production artifacts.

Next shared-analysis change: add an opt-in immutable construction-fact projection
from the final escape/owned-field analyses, without reading observer snapshots or
changing their answers. Use it to prove exclusions only for failed-construction
receiver/owned storage, with typed unwind-edge attribution. Do not exempt a
deallocation merely because it appears in a constructor. Compare enabled/disabled
ordinary diagnostics and IR in each unfreed mode; run the four existing safety
checks below plus accepted/rejected construction and artifact cases. Revalidate
facts after specialization; unknown or stale facts cannot exclude cleanup.

Existing shared-analysis selection, to run when those paths change:

```sh
./scripts/test.sh \
  --test 'pool release helper proofs preserve mandatory safety' \
  --test 'pool release helper proofs survive artifact reconstruction' \
  --test 'destructor and constructor effects are checked closed-world' \
  --test 'caller-owned library results survive source class archive and tree-shaking round trips'
```

Run `git diff --check` for every change and license checks for source/packaging.
Use exact focused tests, never unfiltered suites. Stack-limit, allocation-failure
and unwinding experiments run in disposable children. Record commands, revisions,
payload hashes, expected/actual results and host/translation identity together.

## Evidence and commits

`0cf6115`: pinned setup/offline preflight and independent Linux
image layer, with instructions in `scripts/java-bridge/README.md`. Seven focused
preparation regressions pass; the initial macOS canonical-path assertion was
corrected and only that failing test rerun. No compiler semantics changed.

`2973007`: the shared model provides resolved callable identities, exact-width native
value ABI descriptors, deterministic explicit root resolution/revalidation and
distinct proved/unknown/rejected contract outcomes. Arrays, generic and synthetic
exception values have no admitted ABI. Opaque reference transport does not grant
export, lifetime, conversion or destruction permission. The model is internal;
ordinary compilation does not invoke it. P0b must supply actual immutable proof
payloads through this interface, not treat successful root resolution as proof.

Three focused compiler tests passed using pinned macOS Temurin 21: roots/ABI,
source/class-directory/individual-class/archive reconstruction, and fail-closed
proof outcomes. Exact commands/results are in
`workspace/java-bridge/evidence/p0a/model-tests.log`. The test script also passed
the license audit and rebuilt compiler/stdlib. `git diff --check` passed.
Linux offline JDK/LLVM checks passed on both prepared images; full command,
output/status and translation labels are in the corresponding offline JSON files.

`ce604dc`, P0b first retention step: `BridgeRetentionAnalyzer` and immutable slot contracts
now attribute stores through helpers, phi alternatives, native dispatch targets,
initialization and exceptional exits. Three focused tests passed: accepted
set/clear/replace, observing loads and store-then-fail in every unfreed mode;
rejected copy/move/helper transfer, child/static/array writes, adversarial dispatch;
unknown recursive/missing effects and an unknown phi alternative; matching proofs
from source, class directory, individual class and archive inputs.

The initial positive failed on generated Throwable initialization. Source/runtime
inspection established null/immortal stores into fresh holders and private trace
capture/release/common effects; these have narrow classifications, while storing
an input/slot reference in fresh storage still fails. The positive rerun passed.
Extended fixtures initially omitted mandatory `@Override`; fixing that source
error made both failing checks pass. Logs: `workspace/java-bridge/evidence/p0b/`.
Each test invocation included a passing license audit. Whitespace checks passed.

Limits: these contracts prove reference-store attribution only, not ownership,
acyclicity, construction rollback or non-reclamation. Recursive summaries and
other runtime operations remain conservative. There is no production export
admission or native fixture yet. Do not count this step as P0-5 completion.

Next P0b step implements the independent non-reclamation closure check and shares
call/initialization target resolution with retention. Two new focused tests pass:
all explicit roots and dynamic targets are covered; unreachable frees do not
enter the closure; reachable candidate and array reclamation are rejected;
missing effects remain unknown; construction rollback is not silently excluded.
The retention reconstruction test also passes after sharing the resolver.
Evidence: `workspace/java-bridge/evidence/p0b/non-reclamation-tests.log`.
License and whitespace checks passed. This initial proof deliberately cannot
accept actual OrderBook construction until unpublished rollback exclusions are
derived from final compiler ownership/escape facts. P0-8 remains open.

`ff0eba7`, construction-fact projection checkpoint: the internal `analyzeForBridge` entry
keeps ordinary validation and projects immutable constructor facts from the final
escape and owned-field analyses only after successful validation. Facts bind to
the complete resulting IR; changed specialization effects remain unknown.
Six focused checks passed: enabled/disabled diagnostics and IR in every unfreed
mode, source/class/archive fact parity, and all four shared-analysis regressions
listed above. Stronger accepted/rejected assertions were added and the isolation
test alone reran successfully. Logs: `construction-facts-tests.log` and
`construction-isolation-rerun.log` under the P0b evidence directory. License
audits passed. This projection does not yet authorize rollback exclusions.

Actual OrderBook inspection reports a proved unpublished constructor receiver
and only `tail` among its currently proved owned storage fields. Generated
rollback frees that array container and the book, without pooled-element
destruction. Do not infer cleanup of other arrays or pooled Order/PriceLevel
allocations. Record surviving unpublished allocations separately in D208's
runtime calibration. Evidence: `orderbook-construction-ir.txt` and
`orderbook-initial-facts.txt` in the P0b directory.

`52ea0f7`, unpublished rollback attribution checkpoint: non-reclamation consumes those
facts for generated constructor entries and exact typed allocation/invoke/unwind
edges. It matches actual generated rollback bodies and records constructor,
caller, allocation span, owned fields and cleanup identity for each exclusion.
Destructor effects still enter the ordinary closure. Missing/stale facts and
ordinary reachable frees cannot use this exclusion.

The actual dedicated OrderBook roots (`OrderBook`, `createLimit`, `cancel`,
`reduceTo`) now prove non-reclamation of book/order/level storage and their array
containers, including unpublished nested Order/PriceLevel rollback. Paired
missing-fact, stale-fact, unknown-effect, dispatch and reachable-free cases pass.
The proofs also match source, class-directory, individual class files with their
explicit compiled dependencies, and archive reconstruction. The first individual
class test omitted those dependencies and correctly failed resolution; only that
test was fixed/rerun, successfully. Logs: `rollback-proofs-tests.log`,
`rollback-artifacts-tests.log`, `rollback-artifacts-rerun.log` in P0b evidence.
License and whitespace checks pass. This closes the initial actual-source
non-reclamation proof step, not P0-8: protected JNI fixtures, observed allocation
failure cleanup and runtime evidence are still required.

Evidence: `workspace/java-bridge/evidence/p0a/` contains archive URLs/hashes,
resolved JDK paths/full settings, and complete image inspections. Linux images:

- ARM64: `ironwood-bridge-linux-arm64:05d5199baf46c922`, manifest
  `sha256:a03b0d3a764744079949a3adf5ecf6fe7ce82382ee35ca95360a38d453af8767`.
- x86-64: `ironwood-bridge-linux-x86_64:1a18fe26577fb8c5`, manifest
  `sha256:94b22aef9c134f490463540c7e174a07b3c4bcf028133247078898cc530a47bd`.

Large generated evidence lives under ignored `workspace/java-bridge/`; concise
outcomes and identities go here. Never count absent/skipped evidence as a pass.

## Next steps and pending qualification

Native-fixture pre-change review: add a compiler-owned bounded result-store IR
operation and generated scalar entry CFGs, using resolved roots and retention
proofs. Every initialization/target invoke must unwind to a typed catch with
ordinary occurrence cleanup. Entry lowering rejects references, constructors,
unknown effects and unresolved roots until their contracts are implemented.
Consumers: LLVM instruction emission, CFG renaming, function visibility, trace
planning and native ABI. Preserve ordinary emitter output; no source language
syntax or runtime per-call bookkeeping is added. Check invalid models, boolean
normalization, protected CFGs, renamed operands, native O0/O3 results and unwind
containment. Follow with scalar machine-code inspection and platform fixtures.
Shared-image linking uses the same pinned assembly/optimization/code-generation
pipeline, with private runtime symbols. The macOS trace-registration change
resolves the image containing the source-site table at bootstrap, instead of
assuming the main executable. Run the existing optimized uncaught-trace test
alongside the new JNI tests; validate image-local traces separately before P0-2.

The real-initializer typed-entry regression found the retention DFS cannot prove
a class initializer calling its same-class static helper, because the helper's
initialization barrier forms a cycle. Correct the opt-in analysis with finite
monotone callable summaries, first resolving origins from bottom and then
propagating unknown for unresolved origins. Never suppress the initialization
edge. Paired recursive safe-store/identity, unsafe slot-copy, unknown-origin,
artifact-parity and real-initializer checks are required before this entry
checkpoint can pass. Ordinary source ownership analysis remains untouched.
`4ffd340`: the correction passes all four selected checks: safe recursive stores/identity,
rejected recursive slot-copy and unknown-origin effects, reconstructed artifact
parity, and the protected real-initializer entry. Evidence is in
`workspace/java-bridge/evidence/p0b/recursive-retention-tests.log`. The initializer
fixture first needed an exact owner filter to avoid selecting unrelated bundled
`read` methods; after correcting that fixture, its actual cyclic summary failure
was resolved by the analysis change. License and whitespace checks passed.

`4b7a5d6`, scalar-entry checkpoint: compiler-owned entry CFGs and a bounded result-frame
store lower to protected native calls. Shared linking retains the pinned LLVM
pipeline and isolates runtime symbols. Fixed private JNI bindings pass cold/warm
integer, boolean, long and double calls, native failure containment, post-catch
calls and `-Xcheck:jni` at O0/O3 on macOS ARM64, Linux ARM64 VM and translated
Rosetta Linux x86-64. The native loops report zero ordinary Ironwood allocations.
The optimized entry has four instructions on all targets, with no helper call,
allocation, TLS, trace maintenance or registry operation. A real initializer
retains its protected edge; only a completely resolved no-work initialization
closure omits the barrier. Handwritten JNI comparison timings are saved, with
no numerical acceptance asserted and no x86-64 hardware performance claim.

Final scalar artifact directories beneath `workspace/java-bridge/evidence/p0b/scalar-entries/`:

- macOS ARM64: `run-16615856797947639068`.
- Linux ARM64 VM: `run-4190351105982097498`.
- Linux x86-64 under Rosetta: `run-7789236896154082467`.

Each contains input LLVM, C/Java fixtures, commands, JNI output, payload SHA-256
and O0/O3 disassembly. Compiler/test logs include `scalar-entry-final-tests.log`
(native pass; initial typed fixture failure), `recursive-retention-tests.log`
(typed fixture corrected and passing), `scalar-linux-arm64-final.log` and
`scalar-linux-x86_64-rosetta.log`. The existing `uncaught stack traces are stable
at O3` test passed with the macOS image-registration change in
`scalar-entry-rerun.log`. This is not complete P0-1/P0-2/P0-9 evidence: instance
entries, image trace isolation and the other scheduled fixtures remain pending.

The scalar checkpoint identity manifest is
`workspace/java-bridge/evidence/p0b/scalar-entries/checkpoint.json`. Its target
payloads are individually hashed. The ARM64 LLVM inputs match; x86-64 differs
only in the sign bits of unused bundled Float/Double NaN constants produced by
host constant evaluation. The exercised scalar results and JNI assertions match.

Next native checkpoint: add bounded, allocation-free exception type/source-frame
extraction inside a separately protected typed region. Use invocation-local
transport storage and immutable image metadata, never source getters called
from C after the entry. Keep native catch/implicit-failure cleanup before the
extraction and on its fallback edge. Preserve the scalar fast path and inspect
its code/benchmark after the transport grows. Validate source-frame identity,
repeated bootstrap and two disjoint image mappings on all three P0 targets.
Full message/cause/custom-getter translation and allocation-failure injection
remain explicit later work; the private P0 snapshot is not a public API promise.

Snapshot/image checkpoint evidence: typed boundary tests and private JNI tests
now verify the exact native exception type plus callable/file/line. Two disjoint
images are loaded in one child JVM, bootstrap is repeated, and alternating
failures preserve each image's own metadata and subsequent successful calls.
O0/O3 and `-Xcheck:jni` pass on macOS ARM64, Linux ARM64 VM, and Rosetta x86-64.
Logs: `snapshot-entry-tests.log`, `shared-trace-tests.log`,
`snapshot-linux-arm64.log`, `snapshot-linux-x86_64-rosetta.log` under P0b.
Updated scalar disassembly still has the same four-instruction optimized typed
entry on all targets; zero ordinary Ironwood allocations and deterministic
checksum/timing comparisons pass. No hardware/performance acceptance is inferred
from Rosetta. Snapshot-fallback fault injection remains pending P0-6.

Latest scalar directories: `run-11245275352134111610` (macOS),
`run-10549336939587100811` (Linux ARM64), `run-1728810231708887692` (Rosetta),
beneath `scalar-entries/`. Paired-image directories beneath `shared-traces/`:
`run-13085979668786039832`, `run-5902144525845839322`,
`run-8255598595537168940`, respectively. Each preserves commands, generated
inputs, payload hashes and child output. License/whitespace checks pass;
packaging already recursively copies the runtime include directory containing
the new private transport header.

1. Implement P0b's reusable retention/non-reclamation proofs and internal
   analysis-only reporting; connect shared root identities and preserve all
   ordinary diagnostics across unfreed modes.
2. Build the proof-authorized typed-entry/shared-image fixtures and collect
   the required O0/O3 platform/adapter evidence.
3. Complete every required P0 ARM64 and translated/static x86-64 case before P1.
4. Carry evidence and shared modules through successive production phases.
5. Prepare final x86-64 hardware runner and evidence bundle at P6b. Hardware
   address/access is not supplied; do not assume SSH or paid infrastructure.

`d2751a9` commits the protected snapshots and image-isolation checkpoint above.

Next P0-10 experiment: reuse proved scalar entries for a non-tail recursive
fixture with scalar values live across the recursive call. Inspect actual O0/O3
target disassembly to ensure a call and retained frame remain. On ARM64 hardware
and its Linux VM, test native depths 1/8/32/64 at Java depths 0/64 on default
platform-thread stacks, deepest-frame failure translation and subsequent calls.
Capture JVM stack flags, payload identities, commands and child output. Probe
512 KiB and 1 MiB stack limits only in disposable JVMs; diagnostic crashes never
count as recovered exceptions or supported depths. No compiler/runtime change
or per-call stack check is planned. x86-64 hardware probes remain deferred D213.

P0-10 ARM64 fixture passes: all eight default-stack combinations return the
expected checksum, translate the deepest-frame exception and allow subsequent
calls, at O0/O3 on macOS ARM64 and Linux ARM64 VM. Retained recursion uses a
32-byte native frame at both optimization levels on both targets. Typed entry
frames are 64/32 bytes (O0/O3). The fixed diagnostic JNI adapter frames are
960/896 bytes on macOS and 912/848 bytes on Linux; these include bounded failure
formatting storage and are not a promised production entry footprint. Default
JVM ThreadStackSize flags are 2048 KiB macOS and 2040 KiB Linux.

At both O0/O3, isolated 512 KiB probes succeed through depth 8192 and fail at
16384; 1 MiB probes succeed through 16384 and fail at 32768. The unsuccessful
children exit 132 on macOS and 139 on Linux, without returning a Java failure.
Those outcomes only characterize this diagnostic fixture's fatal limit. They
do not enlarge the required/supported envelope or imply recovery. Core dumps
are disabled. Exact commands, exit statuses, payload hashes, disassembly, stack
flags and child logs are retained under `p0b/stack/run-258330496932685331`
(macOS) and `p0b/stack/run-1268804225534442939` (Linux). Focused test logs are
`p0b/stack-macos.log` and `p0b/stack-linux-arm64.log`; license/whitespace checks
pass. D213 x86-64 hardware evidence remains pending. P1 and P6 must repeat the
bounded cases against their own matched production payloads.

`0114519` commits the ARM64 stack fixture checkpoint above.

Next P0-6 checkpoint and pre-change review: project final semantic borrowed-input
facts without modifying escape summaries, bind them to the unchanged typed
program, and use them to authorize temporary copied String arguments. A proved
input must neither escape nor be invalidated, including helper/exceptional
paths. Ordinary source diagnostics and every unfreed mode remain authoritative.
Retaining, freeing, unknown-effect and reference-result shapes must fail closed;
source/class/archive reconstruction must agree. Add a compiler-owned UTF-16 copy
operation invoked under the entry handler with its non-null implicit allocation
failure context. Cleanup uses the exact temporary objects, including all partial
conversion prefixes, on success and failure. No JNI critical API is permitted.
Select focused bridge conversion proof/native checks, existing construction-fact
isolation/parity and borrowed-input regression checks. Re-run scalar optimized
code/allocation evidence only if its path changes. Test O0/O3 conversion budgets
0/1 and normal execution in child JVMs, multiple methods, initializer failure,
target failure, null/empty/embedded-NUL/surrogate inputs and post-failure calls.
Fault injection and runtime-state inspection must remain test-only, never add
production per-call bookkeeping. Full result/custom-snapshot support remains
later work, with unsupported capabilities rejected.

Copied-string checkpoint: final semantic borrowing facts authorize scalar-result
static entries only. Copies are protected typed operations with a real OOM
context; every acquisition prefix has exact normal/exceptional cleanup. Free
and rollback retention summaries now traverse their resolved destructor effects;
missing destructors remain unknown and retaining destructors are rejected.
Ordinary non-reclamation checks remain separate and pass their negative cases.
The first test run found a missing invoke-whitelist entry for the new operation;
that omission was corrected before any gate passed. Source/class/archive parity,
all unfreed modes, unsafe retention/free/result refusals, construction-fact
isolation, scalar entry checks and existing String ownership checks pass.

At O0/O3, macOS ARM64, Linux ARM64 and translated x86-64 pass `-Xcheck:jni`
tests for null/empty/embedded-NUL/unpaired-surrogate/supplementary inputs,
exactly one managed allocation per non-null copied input, zero temporary leaks,
target failure, repeated allocation budgets 0/1, and initializer failure at
budget 2 followed by failed-initializer reentry and successful scalar calls.
Separately instrumented test-runtime copies confirm cleared implicit-OOM and
emergency-delivery state, protected snapshot fallback, and cleanup after failure
of the first or second JNI acquisition. No instrumentation enters production.
Bridge UTF-8 byte-length overflow now raises protected OOM before allocation;
a lowered test-only byte limit exercises that branch without a multi-gigabyte
allocation. The existing UTF-16/StringBuilder native check passes after factoring
the shared byte-count calculation. An initially mistyped test selector ran no
tests; the exact registered selector was then run successfully.

Final production-runtime evidence under `p0b/string-copies/`:
`run-2095339560163925875` (macOS), `run-3046953674983055173` (Linux ARM64),
`run-15056279931441863850` (Rosetta). Each links its separate instrumented-child
directory through `fault-child.log`, and retains source, LLVM, adapter, commands,
payload hashes, child output and O0/O3 disassembly. Matching final logs are
`string-byte-limit-macos.log`, `string-byte-limit-linux-arm64.log` and
`string-byte-limit-linux-x86_64-rosetta.log`. Other focused proof/consumer logs:
`string-rerun.log`, `string-fault-tests.log`, `string-cleanup-proof-tests.log`.
The deterministic 50,000-call copy benchmark returns checksum 650189050000,
performs exactly 100,000 managed allocations and restores the live count.
Timings are diagnostic under `-Xcheck:jni`, with no numerical acceptance or
translated hardware-performance claim. Optimized copy code contains the required
copy/allocation/byte-count work, with no bridge registry, TLS or trace maintenance
on success. License/whitespace checks pass. No packaging behavior changed.

`41f5695` commits the copied-string checkpoint. Matched revision/input/payload
identities for the recent snapshot, stack and copy checkpoints are recorded in
`workspace/java-bridge/evidence/p0b/checkpoints.json`.

Next: P0-3 loader preflight, collision and permanent-anchor fixtures, then the
remaining enum/object/retention/identity/actual OrderBook JNI gates. String
results, complete built-in exception projection and public producer packaging
remain later-phase work; these capabilities are not admitted by this checkpoint.

P0-3 plan: use proof-authorized scalar entries with fixed private bindings, and
resolve all actual Java classes/package markers without initialization before
registration. Runtime annotations carry generation identity in artifact-private
support namespaces. Validate loader, identities and exact native descriptors;
never register a subset after a preflight mismatch. Pin the defining loader with
a JNI global reference before readiness, preserve an image-local bound flag,
and refuse another loader/retained-image OnLoad before source entry. Check both
class-path and first-use orders for duplicate-class and split-package artifacts,
mixed generation/signature fixtures, disjoint success, continued use of the first
artifact, anchor survival after dropping application references/GC, and direct
OnLoad refusal in an OS-retained-image test harness. Counters/inspection in these
fixed test adapters are test-only evidence, not production hot-path mechanisms.
Run O0/O3 under pinned Temurin with `-Xcheck:jni` on both ARM64 environments and
Rosetta; preserve commands and payload identities. Public generation/packaging
and its repeated production gates remain P2 work.

P0-3 fixed-binding evidence passes on macOS ARM64, Linux ARM64 and translated
x86-64 at O0/O3 with pinned Temurin 21 and `-Xcheck:jni`. Thirteen child-JVM
scenarios cover both class-path and first-use orders for duplicate-class and
split-package jars, disjoint success, mixed-generation and native-descriptor
refusal, same-loader bootstrap idempotence, explicit different-loader refusal,
permanent global-anchor survival through application-reference dropping/GC,
and direct OnLoad refusal for the bound OS-retained image. An additional
split-package automatic-module launch fails before the driver/native load.
Late registration failure is injected after one successful binding: only that
artifact's registration is removed, the pending Java exception is preserved,
no source entry occurs, and an already usable disjoint artifact remains usable.
The scalar source fixture has a real one-time initializer; subsequent calls
also verify it was not rerun. Inspection counters are private test-adapter code.

Matched loader evidence is under `p0b/loaders/run-9911535933252082068` (macOS),
`run-16940289498850660220` (Linux ARM64), and `run-15154980915522334808`
(Rosetta). Source, classes/jars, C/LLVM, payload hashes, commands and scenario
logs are retained; summary logs are `loader-registration-macos.log`,
`loader-linux-arm64.log` and `loader-linux-x86_64-rosetta.log`. Earlier macOS
iterations lacked the real initializer/late-failure case and are superseded by
these final directories. License/whitespace checks pass. This proves the private
P0 protocol, not P2's public generator, loader or packaged distribution.

Next P0 work is enum initialization/conversion, then remaining owning-root,
retention-commit, identity/reservation and actual OrderBook constructor JNI gates.

`0107ce2` commits the loader checkpoint. P0-4 pre-change review: the enum probe
in `workspace/java-bridge/enum-probe/` confirms the existing non-reclamation
analysis proves the test enum, while retention correctly needs an explicit rule
for compiler-owned singleton publication in its declaring class initializer.
Recognize only an exact final enum field's matching `IrEnumConstant` publication;
ordinary static stores remain rejected. Include enum conversion initializers as
analysis roots even when only an unrelated class's enum-argument method is
exported. Use named public static fields after protected initialization, never
private singleton operands in generated entries. Tokens are paired by constant
name, independent of native ordinal. Null argument conversion must not initialize
the enum. Preserve source receiver dispatch; admit only resolved final instance
entries initially, rejecting unsupported polymorphic entry shapes. Project final
method facts from validated semantics rather than infer finality from names.
Paired tests cover exact singleton publication versus ordinary retaining stores,
non-reclamation/unknown effects, missing or stale metadata, source/class/archive
parity, cold SELL receiver/argument values, failed initialization before target
effects, null conversion and Java-only enum initialization on another thread.
Retain existing retention and mandatory reclamation regressions. Native checks
remain O0/O3 on both ARM64 environments and translated x86-64; no registry,
thread check or runtime ownership bookkeeping is added to the entry path.

P0 gates beyond the compiler/scalar evidence above, all later implementation
phases, final ARM64 qualification, x86-64 hardware qualification and numerical
review remain pending. Release readiness is not established.

P0-4 named enum conversion checkpoint: `BridgeEnumInputs` binds complete named
token maps to resolved roots/final facts, proves non-reclamation and empty
retention over the expanded conversion-initializer closure, and rejects missing
metadata, duplicate/null tokens, unsupported dispatch and unknown effects.
Final instance dispatch comes from semantic facts. Protected typed lowering
initializes the native enum before loading its public field; null arguments skip
conversion initialization. Invalid private tokens cannot enter source code.
Only exact compiler-owned singleton publication is exempted from the retention
static-store rejection. Ordinary retaining static fields remain rejected.

Enum proof/entry CFG and named-field contracts agree for source, class directory,
individual class and archive reconstruction. An initial whole-program equality
assertion exposed unrelated dependency-order type/dispatch IDs; the corrected
assertion compares resolved bridge contracts/CFGs. An initial native test expected
Java's failed-initialization wrapper, contrary to D055; it now verifies the same
native NullPointerException on both attempts and exactly one initialization.
No production semantics changed to satisfy either test correction.

O0/O3 pinned Temurin 21 `-Xcheck:jni` child cases pass: cold SELL receiver=1,
asymmetric SELL argument=29, Java-only enum initialization on another thread,
null conversion without native enum initialization, initializer failure before
target effects, continued disjoint use, and invalid-token refusal. Final matched
evidence is `p0b/enums/run-2374232017661096662` (macOS ARM64),
`run-14683475575642049394` (Linux ARM64), and `run-6158468002395351722`
(Rosetta x86-64). Each contains inputs, LLVM, adapters, class files, payload
hashes, exact commands, logs and O0/O3 disassembly. Warm O3 paths have no helper,
TLS, registry, allocation or retention bookkeeping; ordinary initialization
state checks and named-field loads remain. Initializer/failure helpers are cold.

The deterministic million-iteration loop performs two JNI calls per iteration,
checksum 30000000 and zero Ironwood allocations. O3 bridge/baseline nanoseconds:
macOS 9733916/6969333; Linux ARM64 11624673/8442960; translated x86-64
35150805/27107964. These `-Xcheck:jni` measurements are diagnostics, not numerical
acceptance or x86-64 hardware performance. Logs: `enum-benchmark-*.log`.
Focused retention transfer/unknown, destructor/rollback and ordinary construction
safety/IR regressions pass (`enum-final-macos.log`); license and diff checks pass.
P0-5 owning-root/acyclic retention and nonthrowing commit, P0-7 identity/reservation,
and P0-8 actual OrderBook JNI constructor-failure work remain next.

`75b8f8f` commits the enum checkpoint. P0-5 pre-change review: extend the shared
proof model with a bounded constructor-origin input surface and aggregate
retention admission. Initially admit constructor-created roots and scalar-result
methods only; reference-result provenance remains a separate P0-7 extension.
Derive root input eligibility from the complete selected constructor/method
surface, exact closed-world dynamic types, bound unpublished-construction facts,
and source-closure non-reclamation (approved generated destruction is a separate
capability, not a permanent-storage classification). Reject missing constructors,
borrowed/reference-result exposure, unknown effects and source reclamation.
Aggregate attributed slot edges across all entries and require an acyclic type
graph, including self edges and repeated-call cycles. This conservative initial
admission must reject unsupported shapes, not invent root ownership from names.
Pair root set/clear/helper/exception/constructor cases with cycles, child slots,
missing construction, changed roots and source/class/archive parity. Preserve
ordinary safety diagnostics. Adapter snapshot/commit and destruction evidence
will follow only after these contracts pass; no runtime permissions follow from
store attribution alone.

P0-5 scope refinement: constructor fixtures need internal owned allocations as
well as retained input slots. Extend retention with an optional program-bound
projection of existing owned-field facts. Only stores of fresh/null values to
those proved private owned fields are internal storage updates; input/loaded/
unknown values remain subject to the ordinary rejection/slot rules. Without
matching final facts, no exemption applies. Pair allocating constructors and
failure paths with the existing borrowed/unknown-store negatives before lowering.

P0-5 root-origin admission checkpoint passes. `BridgeRootRetentionAnalyzer`
reuses final construction, source-closure non-reclamation and store-attribution
proofs. Its immutable contract binds the exact export surface and program,
lists constructor-origin root types and persistent root slot layouts, and
rejects any cycle in the union of possible type edges across calls. Missing
constructor origins, reference-result exposure, child slots and slot transfers
remain rejected. This limited surface is not general object-result support.
Bound private owned-field facts distinguish internal fresh/null storage updates
from independent retained roots; stale facts are rejected and absence of facts
does not grant the exemption. No runtime code consumes this contract yet.

Focused proofs pass under all unfreed modes, including constructor retention,
helper/argument-root writes, store-then-throw, self/repeated-call cycles, changed
root sets, allocating constructor failure paths, and source/class/archive
contract parity. The ordinary frontend already rejects the unsafe argument-free
and in-progress receiver-publication negative fixtures; tests preserve those
diagnostics rather than requiring the bridge analyzer to override them.
`root-retention-owned.log` records three passing focused tests; final expanded
proof/parity coverage is in `root-retention-final.log`. License/diff checks pass.
Next: prove destruction capabilities, emit bounded final-slot snapshots on both
exits, then exercise allocation-free increment-before-decrement JNI commits,
reservation failure and unpublished rollback. P0-5 runtime gates remain open.
The ignored `p0b/checkpoints.json` now also records matched loader/enum revisions,
inputs, payloads, command/log hashes and disassembly, retaining platform labels.

`311dfdd` commits root-origin/acyclic admission. Destruction pre-change review:
reuse `ClosedWorldEffectAnalyzer` on final bridge IR, exposing only a solved
nonthrowing/allocation-free query. Pair it with explicit complete call,
initialization and descriptor-cleanup closure checks and a conservative native
operation whitelist; existing effect summaries alone do not make missing or
unclassified operations safe. Prove that cleanup cannot introduce dependencies
or mutate other roots before granting a generated destruction capability.
Keep normal source diagnostics unchanged. Check empty cleanup, private primitive
array cleanup and scalar destructor effects against throwing/allocating/unknown
cleanup and retention-transfer negatives; preserve source/class/archive parity.

Destruction proof checkpoint passes: `BridgeDestructionAnalyzer` requires an
admitted constructor-origin root, complete descriptor/call/initializer cleanup,
solved nonthrowing/allocation-free effects and explicitly classified native
operations. Cleanup retention may clear its own slots but cannot add dependencies
or mutate another root. Unknown native operations and hidden static retention
are rejected. Empty descriptor cleanup, explicit private-array cleanup and
scalar destructor counters pass; source/class/archive contracts agree.
Ordinary throwing/allocating-destructor diagnostics agree in all unfreed modes.
`destruction-proofs.log` and `destruction-regressions.log` record the proof test
and two ordinary safety regressions; license/diff checks pass. This grants no
runtime free entry yet; that lowering and the adapter commit gate remain next.

`bd986e8` commits destruction proofs. Protected-root-entry pre-change review:
extend the private result frame with a bounded array of holder/final-value pairs,
using a compiler-only typed store. Record the fixed proved slots on success and
failure before exception extraction. Null holders must never be dereferenced;
constructor rollback must not snapshot the freed receiver, while writes to
existing argument roots still require snapshots. Allocation/init/constructor
calls stay protected. Require separate nonthrowing rollback closure evidence
and ordinary unpublished facts before emitting constructor cleanup; normal free
uses only the separately proved destruction capability. No object-result
origin beyond the bounded constructor surface is admitted. Check SSA renaming,
layout, both exit paths and snapshot-fallback preservation before JNI execution.

Protected root entry checkpoint: `BridgeEntryModule.rootObjects` derives all
permissions from bound root-retention, normal destruction and exact unpublished
rollback proofs. It emits protected allocation/initialization/construction and
scalar-result methods, plus separately proved native free entries. Bound semantic
facts reject abstract/enum allocation entries. Reference-result factories and
borrowed-result admission remain unsupported. The private frame's 288-byte prefix
is followed by bounded 16-byte holder/value records; typed stores, SSA renaming
and C layout assertions agree. Both exits finish records before snapshot
extraction. Null inputs and failed constructor receivers produce absent records;
existing argument-root stores remain observable after rollback. Code inspection
removed redundant pre-call record clearing; absent records are written on their
actual exit path. Ordinary builds do not acquire these operations.

Source/class/archive root/free entry lowering agrees. `root-payload-final-macos.log`
passes proof/parity, typed lowering and JNI payload tests. `root-entry-regressions.log`
passes scalar entry, copied String and destruction regressions; construction
eligibility and unchanged ordinary safety pass in `root-constructible-proofs.log`.
The final constructor eligibility gate reproduces byte-identical validated LLVM
on all targets (`admission-recheck.txt`, ignored `PayloadIdentity.java` runner).

O0/O3 pinned Temurin 21 `-Xcheck:jni` payload cases pass on all three local targets:
root creation, set/clear, aliased holders, store-then-throw, null argument failure,
explicit cleanup, and constructor failure after mutating an existing root.
An allocation limit of 5 admits the new receiver but rejects its private array;
rollback returns live allocations to the prior baseline, preserves the existing
root's changed slot and leaves existing storage usable. A separate instrumented
runtime makes OOM snapshot extraction fail: status 2 retains the completed payload
and clears emergency/implicit failure state. No fault hooks enter production.
Three ordinary NPE objects remain live in this private P0 failure transport and
are counted separately from root/storage cleanup, as in earlier fixtures.

Final payload directories under `p0b/root-payloads/`: macOS production
`run-9877347103907963450`, fault `run-12409441739396241786`; Linux ARM64 production
`run-2949113459849834807`, fault `run-3938310820413454285`; Rosetta production
`run-12489077190238976483`, fault `run-16262974051289123782`. Commands, inputs,
hashes, JVM logs and final O0/O3 disassembly are retained. O3 setter entry is five
instructions on ARM64 and x86-64, including result-frame stores and return, with
no helper/TLS/allocation/registry work. The 100000-iteration native setter/read
loop has checksum 2000000 and zero allocations; O3 diagnostic times are 216000 ns
(macOS), 117955 ns (Linux ARM64) and 241203 ns (translated x86-64). This measures
the typed transport inside a JNI call, not the future host count-commit path or
final numerical acceptance. License/diff checks pass.

Next required P0 work: allocation-free JNI increment-before-decrement commit with
pre-resolved metadata/references, exact deduplication for aliased holder slots,
headroom/preparation refusal before native effects, store-then-throw and post-return
Java error tests, and persistent root/index reservation/identity cases. The current
payload harness owns its test objects directly in C and is not Java facade/count
or root-index evidence. P0-5/P0-7 host protocols and actual OrderBook JNI failure
cases remain open; P1 and later phases have not started.

`8a311ca` commits protected root payloads; matched production/instrumented runs
are indexed in `p0b/checkpoints.json`. P0-5 host-commit pre-change review: the
fixed JNI fixture will consume these proved entries/slot layouts, pre-resolve
field IDs and old/input root references, reserve local capacity and constructor
global/index resources before mutation, and preflight worst-case incoming-count
headroom with input aliases. Deduplicate holder/field records, then commit every
increment before any decrement and finish records before Java error delivery.
Use Java root state with persistent slots and native strong global references;
the later P0-7 cases must validate full identity/reservation/reuse behavior rather
than infer it from the count fixture. Test multiple slots, aliased holders,
replacement/clear, constructor rollback affecting an existing holder, native
store-then-throw, exact lifetime-refusal types/counters, and Java failures after
return. Free is separately proved and drops outgoing counts only after native
destruction. Inspect the epilogue for allocation/Java calls/early exits and run
O0/O3 on all three local targets. No active-use guards or thread checks are added
to callback-free synchronous paths. A private bounded test index is not the
production dynamic index or the P0-7 qualification by itself.

P0-5 host commit checkpoint: the private adapter now consumes compiler-proved
slot layouts and possible input origins, deduplicates actual holder/field pairs,
prepares old/root references and field IDs, and preflights count headroom for
each possible aliased input. A constructor reserves its bounded index record and
strong global reference before native execution. Its result binds that reservation
before slot reconciliation; failed unpublished construction releases it after
committing mutations to existing argument roots. The commit applies every
increment before any decrement and completes persistent Java root slots before
Java exception/result delivery. Proved free completes destruction before dropping
outgoing dependencies, marking FREED and removing the index/global reference.

The focused test passes O0/O3 with pinned Temurin 21 and `-Xcheck:jni` on macOS
ARM64, Linux ARM64 and translated Linux x86-64. Cases cover multiple slots and
holders, input/holder aliases, replacement, constructor rollback, store-then-throw,
exact artifact-private lifetime refusal versus native IllegalStateException,
post-return StackOverflowError/facade-delivery OOM, and error-materialization OOM.
Count overflow refuses before native effects, including two holders sharing an
input; duplicate records for one holder consume headroom only once. Preparation
gate injection leaves native counters/storage/index/global counts unchanged.
These injected gates model preparation refusal in a fixed-capacity test index;
they do not yet qualify actual dynamic reservation allocation or weak-cache
behavior under P0-7. Repeated free is a no-op and final root/global counts are
zero. Four ordinary native throwable objects remain separately accounted for
in this private P0 transport.

A child JVM with a copied, instrumented runtime forces snapshot extraction to
raise after a store-then-throw. Status 2 reaches the same commit, retains the
target's count, refuses its free and later releases all root/index/global state.
Final evidence under `p0b/host-commit/`: macOS production
`run-18285488368628096885`, fault `run-14569484300148387775`; Linux ARM64 production
`run-13709414003946694773`, fault `run-3536031771795769165`; translated x86-64
production `run-11045319501062642728`, fault `run-1939834625521996901`.
Logs are `host-commit-final-{macos,linux-arm64,rosetta}.log`.

O0/O3 adapter/image disassembly is retained. O3 reconciliation calls only prepared
JNI SetLongField/SetObjectField on valid paths (table offsets 0x370/0x340); the
only other call aborts on a violated compiler result-origin invariant. There is
no recoverable early exit, allocation, reference creation, metadata lookup or
Java method call in commit. A private allocation-free observation between the
increment/decrement loops checks coverage before releasing old dependencies.
The 20000 setter/read JNI loop yields checksum 400000 and zero Ironwood
allocations. O3 times with checked JNI and private auditing are 62678500 ns
(macOS), 26063348 ns (Linux ARM64), 39112713 ns (translated x86-64). These are
diagnostics, not optimized production performance or numerical acceptance.
Strict Java/C compilation, license audit and diff checks pass. No production
runtime hooks or packaging behavior changed.

Next: P0-7 requires reusable result-origin contracts before any alias/fresh/view
fixture becomes executable, followed by actual reservation, authoritative index,
weak facade cache, collection and address-reuse tests. Actual OrderBook JNI
failure calibration and the remaining P0 performance/gate audit remain open.

`bcc015a` commits the host protocol checkpoint; six matched production/fault runs
are recorded in `p0b/checkpoints.json`. P0-7 result-origin pre-change review:
project the already selected final symbolic-return/escape summaries into bound,
immutable bridge contracts. Distinguish null-only, uniformly fresh, input alias
and uniquely owned dependent-view origins; reject mixed fresh/existing ownership,
unknown origins and fresh publication. Do not infer ownership from runtime
addresses or grant capabilities from fixture metadata. Keep ordinary analysis
unchanged and reference-result lowering disabled until root/destruction/admission
proofs consume these contracts. Pair nullable fresh/alias/helper/owned-field cases
with mixed, unknown, published and unproved-owner negatives; compare source,
individual class, directory and archive reconstruction and unchanged ordinary
safety diagnostics. This projection is not a complete registration capability.

Result-origin foundation checkpoint: final bridge facts now project uniform
fresh, input-alias, null-only and single-owner dependent-view alternatives from
the existing selected semantic summaries. Fresh publication, mixed fresh/existing
results, root/view mixtures and multiple dependent owners are rejected; unknown
origins, array-element provenance and merely encapsulated external fields remain
unproved. Alias/view nullability is conservative because inputs and owned fields
may be null even without an explicit null-return expression. The immutable
contracts remain bound to unchanged final IR; no executable admission was added.

`result-origin-final.log` passes positive/negative cases in all unfreed modes and
source/class-directory/individual-class/archive parity. `result-origin-proofs.log`
also verifies unchanged ordinary IR/diagnostics and disabled ordinary projection.
`result-origin-regressions.log` passes actual OrderBook construction and root
retention/acyclicity/artifact tests. Strict compilation, license and diff checks
pass. Next connect a bounded subset to admission/lowering: exact root aliases,
fresh-or-null roots with bounded initial slots and protected failure results;
dependent storage still needs owner-aware admission before a view entry can run.

`a335fae` commits result-origin projection. Bounded result-admission review:
require exact constructed root types and matching final origin facts for every
reference result. Admit null, root aliases and uniformly unpublished fresh roots;
fresh roots must have no persistent external-retention slots in this first
increment. Keep dependent views rejected until their input/root ownership model
is integrated. Include every admitted reference entry in the same non-reclamation,
retention and destruction closure, and clear failed reference results before
snapshot extraction. Test accepted nullable fresh/alias lowering, mixed/unknown
and fresh-with-retained-slots rejection, source/class/archive agreement, existing
root/rollback proofs, then JNI identity/reservation behavior with these entries.

Bounded root results and P0-7 identity checkpoint: admission now consumes final
origin contracts, includes result entries in ordinary closure proofs, and rejects
dependent views and fresh results needing unimplemented initial slot reporting.
Failed reference entries clear poisoned result frames before extraction. Three
focused proof/parity tests pass (`root-result-proofs.log`); the host commit and
snapshot-fallback regression passes (`root-result-commit-regression.log`).

The private identity adapter uses a dynamically allocated native address index,
preallocated records/global references and post-commit Java weak facade caching.
O0/O3 checked-JNI cases pass on all local targets: null/alias/fresh results,
preparation failures, unpublished failure and allocation-limit-zero children,
facade/cache-delivery OOM, wrapper collection, actual observed address reuse with
stale wrappers alive, and index growth with existing roots. Failed growth keeps
the old table intact; successful growth followed by global-reference refusal
preserves all previous bindings. Root records, globals and reservations finish
at zero; each published root is destroyed exactly once. World-owned table
capacity remains intentionally allocated. One native NPE snapshot remains
separately accounted for. No borrowed-child capability is admitted yet.

Final evidence in `p0b/root-identity/`: macOS `run-2792900980276599146`, Linux
ARM64 `run-4033680889064192052`, translated x86-64 `run-16005941940225145335`;
logs `root-identity-final-{macos,linux-arm64,rosetta}.log`. O0/O3 disassembly is
retained. O3 publication only probes reserved buckets, writes index data and
calls prepared JNI SetLongField; no allocation/growth/Java method call occurs.
The alias typed entry is five ARM64 instructions or six x86-64 instructions.
50000 warm alias calls plus the matched handwritten JNI baseline allocate zero
Java bytes and zero Ironwood objects. O3 bridge/baseline nanoseconds: macOS
14010625/11238792, Linux ARM64 6026955/6524954, translated x86-64
11407536/10196828. The handwritten single-input baseline bypasses the general
index lookup; production may apply that same proved alias optimization. These
checked-JNI diagnostics are not final numerical acceptance. License/diff checks
pass. P0-7 still needs dependent-child identity/liveness and combined retention
collection cases; actual OrderBook failure experiments and P0 gate audit remain.

`032975b` commits bounded root results and identity; final runs are matched in
`p0b/checkpoints.json`. Dependent-view pre-change review: allow a proved owned
child result only when its unique owner input is an exact constructed root.
Include child types in non-reclamation and dynamic-type proofs. Child facades
reuse that owner's indexed state and weak cache; never allocate another root
state or infer a free capability from the child address. Types that may be views
cannot hold persistent slots, and retaining them remains rejected until owner
delta reporting is implemented. Nested/ambiguous owners stay rejected. Pair
accepted getters, aliases and scalar child methods with unsafe deallocation,
child-held slots and unproved owners; verify artifact parity and owned/borrowed
instances of the same facade type, post-owner-free access, cache failure and GC.

P0-7 audit clarification: earlier reuse runs observed real allocator address
reuse, but the plan also requires a deterministic test allocator. Add an isolated
copied-runtime child that caches one deallocated Node buffer and returns it on
the next same-type allocation, preserving normal allocation-limit/counter/type
initialization code. Require reuse within two iterations and a test allocator
reuse counter. Keep unmodified-runtime runs alongside instrumented runs; no
production allocator change or hardware qualification is inferred.

O3 inspection found avoidable zero stores in the private identity adapter's
result frames. Remove this initialization: every supported call has a nonvoid
result written on success, failure metadata is read only after status 1, and
status 2 selects fallback without reading incomplete snapshot fields. Keep the
fresh-result poison test. Repeat normal, OOM and forced-reuse JNI cases with the
optimized adapter; no production lowering or ordinary runtime changes are needed.

Dependent-view/P0-7 checkpoint: exact owned-child results now share their proved
root state, including child aliases, weak-cache failure/recovery and facade GC.
Owned and borrowed instances of the same class retain distinct free capabilities;
owner free invalidates borrowed access before native entry. Nested owners,
child-held slots and retained views without owner deltas remain rejected. Final
semantic/artifact checks pass (`view-artifact-proofs.log`, `view-proofs.log`).
The retention adapter also verifies that collecting holder/target facades neither
destroys native storage nor loses incoming counts or indexed root states.

O0/O3 checked-JNI runs pass on all local targets, including copied-runtime forced
reuse children and allocation-limit-zero children. Final identity parents/children
in `p0b/root-identity/`: macOS `run-14119170707516663250` /
`run-7526578670530103954`; Linux ARM64 `run-5165487384848424965` /
`run-18026784617698710176`; translated x86-64 `run-17284793410705744607` /
`run-9382365107225570717`. Logs: `view-reuse-final-{macos,linux-arm64,rosetta}.log`.
Retention GC parents in `p0b/host-commit/`: macOS `run-4404491300723296190`,
Linux ARM64 `run-1664985495727200342`, translated x86-64
`run-14141316596416568277`; snapshot-fallback children also pass.

Final O0/O3 disassembly is retained. Optimized child scalar access has the owner
liveness read and typed call, with no index lookup, allocation, frame clearing
or trace/TLS work; private fixture entry counters remain diagnostic instrumentation.
Publication calls only prepared SetLongField after reserved index insertion.
50000 warm aliases allocate zero Java bytes and Ironwood objects. O3 bridge /
handwritten-baseline nanoseconds: macOS 13370542 / 11229500, Linux ARM64
5942719 / 6654336, translated x86-64 11242493 / 10113256. These checked-JNI
measurements are diagnostic, not numerical acceptance. Strict compilation,
license audit and diff checks pass. Next: actual OrderBook failure/cleanup
experiments under D208, followed by the remaining P0 gate audit before P1.

`83a48b3` commits the view/reuse checkpoint; matched payloads are indexed in
`p0b/checkpoints.json`. D208 pre-change review: reuse non-reclamation and exact
unpublished-construction proofs for a uniformly permanent entry surface. Extract
the existing complete nonthrowing/allocation-free cleanup check for both ordinary
root destruction and permanent-constructor rollback, preserving its conservative
unknown-operation and retention checks. Every admitted reference input/result
must pass the full export closure; no fabricated return owner or runtime liveness
state is needed. Keep arrays, copied Strings and mixed lifetime surfaces outside
this mode. Pair actual engine acceptance/artifact parity with reachable free,
unknown effects and unsafe rollback rejection, and rerun existing destruction
proofs. Then calibrate real constructor allocation failures in child JVMs and
compare recorded allocations/deallocations with typed rollback and surviving
control storage on all three local targets. No ordinary escape/free rule changes.

Uniform permanent-entry foundation checkpoint: the internal entry module now
uses D192's complete closure proof for every reference input/result, with no
fabricated return owner, root state or destruction entry. The existing cleanup
effect proof is shared with permanent constructor rollback. Actual OrderBook
source/class-directory/individual-class/archive proofs and protected entries
agree; existing destructor positives/negatives pass (`permanent-proof.log`).
`permanent-proof-final.log` passes new ordinary-IR/diagnostic parity across unfreed
modes, publication/unknown-return acceptance and reachable-free, unknown-effect,
unsafe-construction, unknown-cleanup and array-boundary refusals. Array refusal
occurs at the existing ABI resolver, and constructor publication is already an
ordinary source error; tests preserve these earlier boundaries. Strict compilation,
license and diff checks pass. D208 runtime evidence remains the next gate.

`4135a2e` commits uniform permanent admission and shared cleanup proofs. D208
runtime checkpoint passes at O0/O3 with checked JNI on macOS ARM64, Linux ARM64
and translated x86-64. A successful capacity-2 control allocates 10 objects;
capacity-3 construction allocates 12. Budgets 13/17/21 then fail at actual
OrderBook source lines 48/54/59: after one installed Order, after one installed
PriceLevel, and at the final `levelCount` array. Failure returns no facade and
clears the poisoned native result. Existing book/order identity remains live;
getters, `reduceTo` and `cancel` succeed afterward without allocation/free.

Copied-runtime fixed event records independently confirm allocation prefixes and
cleanup addresses. The first two failures free only the fresh failed book;
the last frees its `tail` array, then the book. Remaining unpublished allocations
are respectively 2/6/9, matching current typed ownership and rollback without
invented pooled-element cleanup. No earlier control address is deallocated.
Successful-construction and failure cases also pass with the unmodified runtime.
The first instrumentation build needed its own `<inttypes.h>` include; the
corrected case passes. This was a fixture build error, not a runtime discrepancy.

Final parents/recorded children under `p0b/orderbook-failure/`: macOS
`run-9177783921131450562` / `run-16517862379333993492`; Linux ARM64
`run-14642823622813682975` / `run-3875963929763793254`; translated x86-64
`run-12504237735353859368` / `run-876281866228937595`. Logs:
`orderbook-failure-final-{macos,linux-arm64,rosetta}.log`. Inputs, bound proofs,
exact commands/budgets, traces, payload hashes and O0/O3 disassembly are retained.
License and diff checks pass. Repeat these cases against production artifacts
in P3/P4. Next finish P0-9's scalar-instance/baseline audit and P0c gate review;
x86-64 hardware stack qualification remains explicitly pending under D213.

`2c46200` commits D208 runtime evidence. P0-9/P0c checkpoint: uninstrumented
reclaimable and permanent scalar getters now have matched handwritten JNI
comparisons. O3 inspection exposed an unconditional error-helper call in the
permanent fixture; the corrected failure-only branch passes repeated O0/O3
functional/allocation checks. Existing instrumented safety paths still retain
their exact entry counters. Permanent entry admission additionally refuses
unproved direct receiver dispatch, paired with a polymorphic negative; actual
engine and final semantic proof cases pass.

Final reclaimable scalar parents/forced-reuse children in `p0b/root-identity/`:
macOS `run-1119145758219451049` / `run-1525979789530368166`; Linux ARM64
`run-10567909826585342056` / `run-4766823948076777106`; translated x86-64
`run-715259248670169557` / `run-4189094568223038695`. Final permanent scalar and
D208 parents/event children in `p0b/orderbook-failure/`: macOS
`run-13612177958165854025` / `run-1490756183052752898`; Linux ARM64
`run-8009581471728125573` / `run-7059684182266436141`; translated x86-64
`run-13771682622297421647` / `run-15224662049860923162`. Logs:
`instance-scalar-*.log` and `instance-scalar-final-*.log`.

Every measured 50000-call loop reports zero Java bytes and Ironwood allocations.
O3 bridge/baseline nanoseconds for reclaimable getters: macOS 6231334/5373875,
Linux ARM64 3364542/2997458, translated x86-64 5669499/5665083. Permanent getters:
1229958/1053625, 638750/554959, 1134917/1085709 respectively. The handwritten
instance baselines call the same protected typed getters. Disassembly confirms
four-instruction typed getters, no permanent liveness work and no warmed-path
helper/registry/TLS/trace/allocation work beyond required JNI/ABI/owner checks.
These checked-JNI measurements are diagnostic, not final numerical acceptance.
Strict compilation, license and diff checks pass. P0 closes for implementation
with only D213's explicit x86-64 hardware stack deferral. Continue to P1.

`b8d7dc4` closes P0 for implementation; final matched payloads are indexed in
`p0b/checkpoints.json`. P1 pre-change review: add explicit immutable native export
roots to IR and preserve them through every reconstruction. Reuse P0 scalar
admission/typed entries, then the existing final-link optimization/pruning passes;
never assume foreign callers have initialized a class or supplied constant enum
arguments. Keep allocation-failure support and entry unwind regions reachable.
Separate shared/executable link kinds while preserving executable behavior and
runtime-cache flag separation. Paired checks: multiple Java-only roots retained,
unreachable functions pruned, overload/signature identity preserved, invalid/stale
roots refused, init/failed-init/OOM contained, ordinary optimizer/ownership tests
unchanged, and source/class/archive parity. Review shared pruning, initialized
and enum specialization, field forwarding/store elimination, selective inlining
and LLVM entry visibility together. Then run focused production native/stack
checks and D202/D210 dependency/signature experiments on their scheduled targets.

P1 foundation checkpoint: explicit IR export roots now survive all production
reconstructions and the shared native optimization/final-link passes. The scalar
library path reuses P0 admission/entries, retains cold initialization and implicit
OOM support, prunes unrelated main/method bodies, revalidates entry signatures and
does not attach stale semantic facts to optimized IR. Backend output kinds keep
executable behavior separate from shared linking; Linux shared links request NOW.
No public producer CLI or wider capability has been admitted.

Strict Java 21 compilation and focused library source/class/archive parity pass.
The initial parity comparison was too broad: reconstructed declaration ordering
changes internal descriptor IDs. Exact per-linkage typed functions and unordered
initialization contracts agree; all four representations pass the same root,
signature, pruning and protected-edge checks. Invalid fixture main syntax and
allocation-budget expectations were corrected before the passing run; exhausted
implicit NullPointerException allocation correctly produces OutOfMemoryError.
Existing initialized/enum specialization, field forwarding and unread-store
structure/safety tests pass, as does field forwarding's native/artifact test.

Production O0/O3 libraries pass cold/warm/failed initialization, repeated caught
failure, zero/one allocation budgets and continued calls on macOS ARM64, Linux
ARM64 and translated x86-64. Two disjoint production images preserve their own
native traces. ARM64 default-stack depth 1/8/32/64 with Java depth 0/64 passes;
separate diagnostic children fail at 16384 native frames with 512 KiB and 32768
with 1 MiB after last successes 8192/16384. These fatal probes do not establish
stack-overflow recovery. O3 recursive and protected entry frames are each 32
bytes on both ARM64 targets; private JNI frames are 896 bytes on macOS and 848
on Linux, including their inlined diagnostic formatter. The production generator
must outline uncommon translation work in P2. Physical x86-64 stack work remains
pending under D213.

Evidence under `workspace/java-bridge/evidence/p1/`: libraries macOS
`run-6087922724765937330`, Linux `run-2446589362154472326`, translated x86-64
`run-11635037218318175066`; shared-traces respectively
`run-11022967658993543791`, `run-12179034780387665768`,
`run-3428522627112429376`; stack macOS `run-7798338637632620928`, Linux
`run-2352802388115074423`. Logs `foundation-{macos,linux-arm64,rosetta}.log`.
Scalar production ABI/allocation fixtures at O0/O3 pass on all three targets;
O3 typed integer addition is four machine instructions without bookkeeping.
100000-call bridge/handwritten nanoseconds: macOS 3578209/1125625, Linux ARM64
1383224/834084, translated x86-64 2305514/2397052. These checked-JNI short-loop
measurements are diagnostic, not numerical acceptance. Scalar evidence directories
respectively `run-4355172479852457308`, `run-13469797911851367967`,
`run-12569653848973146841`; logs `scalar-{macos,linux-arm64,linux-x86_64}.log`.
License and diff checks pass. P1 remains open for the full D202 dependency/load
audit and D210 launcher/signature/extraction checks; proceed with those next.

`09da7f0` commits the P1 native foundation; `p1/checkpoints.json` binds eleven
passing payload/evidence directories to that revision. D210 now passes its P1
private-jar experiment on all six macOS cells: pinned Temurin 21.0.12.1+1,
22.0.2+9 and 23.0.2+7, each at O0/O3 with ordinary and checked-JNI launches.
Official Adoptium release metadata and companion SHA-256 files supplied the new
22/23 pins; original Java 21 pins/installations remain unchanged. The linker
already supplied valid final ad-hoc signatures. Private byte-copy extraction and
atomic publication preserve bytes, Identifier, CDHash and signature type. Scalar,
caught-native-exception and continued-call checks pass without launcher changes,
consumer signing or native-access flags. Full commands, signatures/entitlements,
macOS build, extraction attributes, six records and hashes are under
`p1/macos-extraction-01/`; the recorded source payload revision is `09da7f0`.
P2/P6 must repeat against their generated artifacts. Java 25 remains scheduled P2.

D202 dependency audit in progress: the actual Linux ARM64 payload has eager
binding, but the configured Clang driver injects a development-prefix RPATH and
links libstdc++.so.6/libgcc_s.so.1. The pinned JVM itself does not need those
libraries. A successful development-container load is insufficient evidence.
Next construct a minimal JVM-only runtime, demonstrate absence/load behavior,
and implement automatic dependency delivery with provenance, relative paths
and two-image isolation before closing P1. This gate has not passed.

`8e3d15c` commits the D210 runner/pins and passing private-jar experiment.
D202 continuation: scratch runtime images now contain only the pinned Temurin
JDK and its inventoried libc/libm/libdl/pthread/rt/loader closure. Both JVMs
run without libstdc++ or libgcc_s; the original production payload fails
System.load catchably for missing libstdc++. A private relative-path experiment
with the adjacent pinned libraries passes scalar/initialization/exception calls
on Linux ARM64 and translated x86-64. These preliminary copies are not final
producer evidence. Minimal-image setup corrected a missing JDK-internal ldd
search path and preserved executable mode on the copied ELF loader; no consumer
environment workaround is needed.

Active uncommitted work: prepare a hash-validated Linux support SDK from exact
Conda GCC 16.2.0 packages, with GCC source archive, recipe/patches, zlib build
source and complete GPLv3/Runtime Exception texts. Reviewed exact GCC
`libgcc/unwind-dw2.c` and `libstdc++-v3/libsupc++/eh_personality.cc` headers and
the packaged license classification. LLVM compiles Ironwood-owned IR; no GCC
IR/plugin combination is introduced. Package hashes and recipe revision are in
`packaging/java-bridge-support.properties`; prepared SDKs are ignored under
`workspace/java-bridge/support/{linux-arm64,linux-x86_64}`. Source download SHA
matches the immutable package recipe; the initial concurrent partial-archive
inspection was discarded and repeated after the verified download completed.

`BridgeNativeSupport` now validates the prepared SDK and glibc 2.17 sysroot,
disables Clang's injected development RPATH while explicitly preserving the
pinned compiler/sysroot selection, and stages complete support/source/notices
beside the library under a manifest-derived identity with a relative RPATH.
Ordinary executable and macOS paths are unchanged. Missing/changed inputs must
fail before linking. First Linux ARM64 production native/trace cases pass with
this solution; x86-64 and final staged-publication verification are in progress.
Next add fail-closed SDK/delivery regressions, durable minimal-runtime and missing
relocation-symbol runners, audit both dependency closures, run matching minimal
O0/O3 ordinary/checked/limited-allocation and disjoint-image checks, and document
provenance/distribution mechanics before committing or closing P1.

P1 exit checkpoint: D202 final production images pass O0/O3 in minimal JVM-only
scratch images on Linux ARM64 and translated x86-64. Each target has ordinary
and checked-JNI cold/warm/failure/continued-call cases with normal/zero/one
allocation budgets, repeated two-image trace checks, and catchable missing
required relocation failures with no native-entry marker. Actual DT_NEEDED,
relative RPATH, NOW flags, transitive library paths and version requirements are
recorded for twenty image/support files per target. No compiler runtime comes
from the minimal base; all libstdc++/libgcc_s resolutions use delivered files.
The native GLIBC requirements remain at or below 2.17. This is not a claim that
the current JVM experiment ran on a glibc 2.17 host.

Final Linux ARM64 library/trace evidence: `p1/libraries/run-4700994129389425173`
and `p1/shared-traces/run-5605896831174608380`. Translated x86-64:
`p1/libraries/run-4401888246413309914` and
`p1/shared-traces/run-7233831501793695101`. Minimal audits and all consumer
commands/statuses/hashes: `p1/minimal-final2-linux-arm64` and
`p1/minimal-final2-linux-x86_64`. The scratch image identities are respectively
`sha256:da9117595e95447d1cc27409f701b270518568644c92f7771fc1fbf489a961a2`
and `sha256:6884fa945432b5442bacaa0f5ebac0bf31e0001e4083b7bb693114343e9795a5`.
The first runner attempt used an unsupported raw image ID in Docker FROM; the
corrected runner creates a local digest-derived tag and does not fetch a base.
No runtime dependency failure was suppressed.

Six focused preparation integrity tests and the Java SDK/delivery test pass,
including wrong target, missing source/notices, changed pinned binary/source,
existing-delivery preservation and no partial staging directory. Strict Java 21
compilation, license and diff checks pass. Ordinary native/artifact behavior
passes on both Linux targets after the linking change. Final Linux ARM64 stack
evidence `p1/stack/run-3056622453807214571` retains the bounded envelope, 32-byte
O3 source/entry frames and the same isolated fatal limits. Final scalar paths
remain four instructions and allocation-free; 100000-call bridge/handwritten
nanoseconds are 1617728/1301274 on Linux ARM64 and 2322928/2389968 under Rosetta,
in `p1/scalar-entries/run-12117111796387709147` and
`run-15471165864710673832`. These short checked-JNI figures are diagnostic only.
Logs: `dependencies-final-*`, `minimal-final2-*` and `support-checks-*`.

P1 now closes with its earlier macOS D210 and native-root evidence. Physical
x86-64 stack/performance qualification remains pending D213. Continue P2;
keep P3/P4/P6 dependencies and Java 25 experiment/product-baseline requirements.

P2 pre-change review: exact-package discovery will seed the existing dependency
loader, preserving source/class/archive lookup and ordinary compilation. The
subsequent immutable API projection must retain resolved visibility, signatures,
throws, enclosing types and inherited members without granting lifetime proofs.
Only validated complete surfaces will reach the P0 root/entry model. Unsupported
public shapes remain producer errors. No new runtime checks or hot-path changes
are part of discovery. Focused discovery cases pair exact-package union and
nested declarations with missing/malformed packages, mismatched declarations,
corrupt artifacts and recursive-prefix exclusion. Compare source, class directory,
individual class and archive reconstruction; retain ordinary source-path tests.
API projection/validation will add paired static primitive/string and unsupported
reference/field/generic/inherited cases before generation is enabled. P2 remains
open until all generated-artifact, exception, loader, D210 and D209 gates pass.

P2 discovery checkpoint: exact-package union, nested/overloaded identity,
dependency closure and source/class-directory/individual-class/archive parity
pass `Java Bridge exact packages preserve source class and archive discovery`.
Missing/invalid packages, recursive-prefix exclusion, package mismatches,
corrupt artifacts and bundled-type shadowing are covered. The ordinary
`source path discovers and compiles referenced sources` test passes. The first
discovery assertion compared SourceFile object identity; it was corrected to
compare ordered paths, then the failing check passed. Strict Java 21 compilation
and applicable license/diff checks accompany the checkpoint. Public generation
remains disabled. Next project the final resolved public API and validate its
complete signature closure before adding Java/JNI generation.

`df4b679` commits exact-package discovery. P2's next checkpoint adds immutable
`BridgeApiFacts` from final resolved symbols, bound to their exact IR, plus
complete `BridgeExportSurface.scalarPreview` selection. This is API inventory
and validation, not JNI or exception-translation admission. Ordinary compilation
does not collect the inventory. Inherited methods/defaults/fields, constants,
overloads, throws and enclosing accessibility survive source/class/archive
reconstruction. Located errors reject missing export packages, inaccessible
signature types, marker collisions and unimplemented public capabilities.
P0's copied-input cleanup proof still rejects a String-retaining target even
when its signature is valid. String returns stay closed pending typed transport.

Focused API projection/selection checks and the existing library-root check pass.
Projection preserves ordinary IR/diagnostics in all unfreed modes and unsafe
use-after-free never produces API facts. The first inventory fixture violated
the one-public-top-level-type/file-name rule; correcting the fixture resolved
the failure. The initial inherited-error assertion named the accepted static
method instead of the rejected default instance method; corrected accordingly.
The four preselected shared-analysis regressions (pool safety and artifacts,
constructor/destructor effects, caller-owned result artifacts/tree shaking) pass.
Final positive/negative reconstruction assertions, strict Java 21 compilation,
license and diff checks pass. Continue production generation, loader identity,
typed value/error snapshots and P2's actual-jar validation. P2 is not complete.

`2447661` commits API projection and signature selection. Next establish D193's
separate logical API, complete source-program/producer generation, native build
and final payload identities. Generation must change for private implementation
or producer/runtime changes even when the API stays identical; source/class/
archive reconstruction and relocation must agree. Native build identity excludes
the final image digest to avoid self-reference. Its target/options/dependency
inputs may vary across payloads sharing a common generation. Canonical encoding
must preserve exact UTF-16 constants, including unpaired surrogates. Focused
identity tests will cover these distinctions and stale-surface rejection before
the generator or packager consumes them.

P2 identity checkpoint: `BridgeGeneration` separates API, complete source-program/
producer generation, native build and final byte hashes. `BridgeProducerInputs`
inventories actual compiler content/resources and runtime sources/headers, with
version consistency and required-file checks. Jar timestamps/order and host
paths do not alter content identity. The two focused identity/producer tests pass:
private helper changes, API changes, producer/runtime changes, source relocation,
class/archive reconstruction, target/options differences, stale surface refusal,
immutable metadata, incomplete inputs and exact unpaired-surrogate constants.
The initial surrogate fixture used unsupported string Unicode escape syntax;
the corrected existing character-escape plus constant-string concatenation form
passes without changing language semantics. Strict Java 21 compilation passes.
No actual jar/native manifest is produced yet; generation/loading/snapshots remain
the next P2 work. A production build must compare the captured input inventory
again before publishing a paired output, and record real native build inputs.

`5d6844e` commits the separate identities and producer inventories. Next generate
Java 21 facade/identity/package-marker sources and one authoritative private JNI
binding descriptor list from the validated surface. Preserve overloads, parameter
names, checked declarations, nested names and exact compile-time constants;
choose private entry names without colliding with producer members. This first
source-generation checkpoint uses a test-only support stub for javac/reflection
verification; it is not a loader or runnable bridge artifact. Public production
packaging stays unavailable until real support, native translation and gates exist.

P2 Java declaration checkpoint passes the focused
`Java Bridge generated Java preserves signatures constants and private JNI entries`
test. javac --release 21/-parameters/-Xlint:all/-Werror and reflection verify
overloads, all scalar widths, void/String inputs, checked declarations, nested
binary names, runtime identity metadata, private native descriptors, exact UTF-16
constants, primitive minima, negative zero, infinities and NaN. Metadata inspection
does not initialize facades; the test stub records one bootstrap per initialized
facade. A dedicated collision case reproduced field names `ironwood`/`java`
shadowing qualified expression names. An imported bootstrap method chosen outside
the API's method names, plus arithmetic nonfinite literals, fixes it. JNI names
also avoid a producer method named `$ironwood$native$0`. The test-only stub is not
shipped by the generator. Strict compiler compilation and license/diff checks
pass; next implement the real version guard, extraction/identity preflight and
native registration, then typed String-result and full failure snapshots. P2
remains open, with Java 25 and actual generated-jar experiments still pending.

`46b0184` commits Java declarations and private binding descriptors. Loader
implementation must preserve D191's canonical extraction identity across defining
loaders: never use a fresh image per loader. Use a private owner-checked canonical
cache, exclusive temporary files, byte verification and atomic publication;
stale partial files are never selected. Resource paths include generation identity
so disjoint jars cannot shadow one another's native resources. Check Java 21-23
before extraction and inspect every resolved class/marker/native descriptor
without initializing facades before registration. Source-only tests will verify
version predicates, complete preflight and extraction failures; actual native
binding, anchoring, collision, signing and launch-form evidence remain required.

P2 loader-source checkpoint passes
`Java Bridge generated loader validates metadata versions and canonical extraction`.
The generated Java compiles strictly for release 21. Private-method tests cover
the 21-23 predicate and OS minimum comparison; complete class/package/native
signature preflight; missing/mixed/stale metadata; simultaneous independent
classloader extraction to the same canonical path; exact bytes/modes; stale
partial exclusion; corrupt-existing-file preservation; and symlink/unsafe-mode
refusal. These tests never load their nonexecutable fixture bytes. The initial
mixed-generation fixture replaced namespace text as well as the annotation value;
it was corrected to mutate only the quoted metadata value, then passed.

Canonical cache paths include actual file owner identity and current JVM PID/start
identity, then generation/target, with one image filename independent of defining
loader or payload digest. A different build of the same generation in that JVM
cannot replace the first image; a later JVM gets its own cache. Atomic hard-link
publication preserves final signed bytes and cannot replace a competing image.
No process-global Java registry or steady-state call check is added. Actual
native binding/anchoring, real image extraction/signature checks and unsupported
runtime launches are still required. Strict compiler compilation, licenses and
diff checks pass. Continue with production native adapters and typed value/failure
transport; do not expose the public producer with incomplete translation.

P2 cleanup prerequisite review: `IrStringCopyInstruction` allocates fresh native
String storage but is absent from the closed-world local allocation effects.
The ordinary source experiment `CopyCleanup.iron` is already rejected, because
String initialization can independently allocate; it does not isolate the copy.
Add a typed-IR regression for direct, caught and helper copies, paired with a
nonallocating reference move, before fixing the missing effect. This changes
destructor/construction and bridge cleanup summaries only, with no runtime code
or ownership exemption. Run the focused copy-effect and existing closed-world
destructor tests plus bridge cleanup proof coverage; preserve conservative unknown
effects and all missing-free modes. Existing source/artifact reconstruction keeps
the same copy instruction, so no archive format change is needed.

The isolated regression failed before the fix with `String copy incorrectly
proved allocation-free: copy`. Adding the missing local effect makes direct,
caught and helper copies fail allocation-free proof while the reference move
remains accepted. The four selected tests above pass via `scripts/test.sh` under
Temurin 21, including all-mode source/class/archive copied-input checks. Evidence:
`workspace/java-bridge/experiments/string-copy-effects/verification.log`.
No runtime, instruction lowering, or artifact format changed. Continue P2 value
transport; this checkpoint does not establish String-result support.

`774a9f9` commits the copy-effect correction. P2 String-result pre-change review:
reuse final escape/result summaries for fresh versus input-alias ownership and
the P0 retention solver for exact literal/null origins through helpers. Project
return-only borrowing separately from ordinary borrowing; only a result consumer
that keeps input copies alive through conversion may use it. Keep stale facts,
publication, invalidation, unknown operations and mixed fresh/alias ownership
rejected. Recognize the inspected native String-copy intrinsic as non-retaining,
without changing ordinary escape/reclamation analysis. First add proof-only
contracts with paired literal/null/alias/fresh and stored/freed/unknown/mixed
cases, all missing-free modes and reconstructed source/class/archive parity.
Run existing retention, destruction and copied-input checks affected by this
shared projection. No production String-result signature is enabled until typed
transport, cleanup, native failure tests and allocation evidence follow.

The first result-proof run exposed a solver boundary: phi/conversion propagation
used only known origins even during the completing pass, so a null alternative
could mask an unclassified reference producer. Before relying on literal proof,
propagate complete origins through phi/conversion/helper arguments and explicitly
classify native String copy as fresh. Add null-plus-unknown and fresh-plus-null
regressions; run existing retention attribution/rejection/artifact checks since
the same conservative origin propagation governs root-slot stores. This is a
proof correction, not permission to infer unknown operations as fresh or borrowed.

String-result proof checkpoint passes: fresh-or-null copies, literal/null helper
returns and multi-input/loop aliases are classified distinctly in every unfreed
mode. Stored/freed inputs, published fresh results, mixed fresh/alias returns,
unknown calls and missing phi origins remain rejected. Positive and negative
source/class-directory/individual-class/archive results agree; stale facts fail.
Seven focused checks passed, followed by four affected retention/result checks
after adding local fixed-point completion, then the extended negative-artifact
result test. Logs are `result-proofs.log`, `result-proofs-final.log` and
`result-artifacts-final.log` under the String-copy experiment directory. Strict
Java 21 compilation, license audit and diff checks pass. No runtime behavior or
public String-result admission changed. Next implement typed result lifetime and
JNI copying, with exact live-allocation checks, failure cleanup and O3 inspection.

`8b06210` commits String-result proofs. Transport review: extend the existing P0
String entry lowering without enabling the public producer. On success, reclaim
all input copies except an exact returned input alias; keep that one or the
proved fresh result alive until JNI NewString finishes, including failure. A
proved result contract permits one nonthrowing raw String release afterward;
immortal/null results are never freed. Native String has only inline UTF-16 and
no destructor. Share its existing private C layout with adapters, preserving ABI
assertions and avoiding extra result-buffer allocations/helper calls. Keep all
raising conversion/initialization/target/snapshot work in typed handlers. Test
null/empty/NUL/surrogates, fresh/literal/aliased results, partial preparation and
JNI-delivery failure cleanup with exact live counts in isolated native children.
Inspect O3 String adapter code and record focused timing after native validation.

Typed String-result transport passes on macOS ARM64 at O0/O3 with Temurin 21
and `-Xcheck:jni`, using private test bindings and the production runtime. Final
evidence is `p2/string-results/run-13145395920723262166` under the evidence root.
Null/empty/NUL/unpaired-surrogate values, fresh/literal/multiple-input aliases,
simulated JNI delivery failure, target failure and allocation budgets 0/1/2 pass.
Both input copies are reclaimed on target failure; the fixture's newly allocated
null-throw exception remains native-owned, exactly one additional live object.
The original zero-extra-object assertion was corrected to account for that
existing ownership contract, not to waive a String leak. A throws-only String
fixture was refused by the existing no-result-origin rule; the containment case
now includes a null success branch. General throws-only result admission remains
to be proved before final producer coverage.

The 50,000-call alias loop allocates exactly 100,000 native input copies and no
extra native result storage, ending at its original live count. Three default-JVM
O3 diagnostic samples took 17,662,834 / 17,256,041 / 16,838,041 ns; these are not
numerical acceptance or cross-platform qualification. O3 disassembly shows the
typed alias entry's 64-byte frame, exact pointer-selected cleanup, and the JNI
adapter's 384-byte frame with inline String field reads, NewString and one proved
raw release. Failure formatting is outlined. No result-buffer allocation, TLS,
registry lookup or new safety bookkeeping is introduced. Existing copied-input
proof/native fault tests also pass after the shared lowering/header change.
Strict compilation, license and diff checks pass. P2 remains open: generated JNI
bindings, complete exception snapshots, real paired jars, launch/signature tests
and Java 25 evidence still follow; public String-result selection remains closed.

`e631dca` commits typed String results. Its final ignored evidence directory also
contains `checkpoint.json` with revision, source and payload hashes. Begin the
exception projection/translation checkpoint from D195/D197, keeping getters in
protected typed follow-up entries and Java construction after native return.
Review found built-in getMessage overrides return stored text; the earlier
working assumption that FileSystemException.getMessage allocates was incorrect.
DateTimeParseException.getParsedString does allocate a fresh String, and its
final semantic FRESH_ROOT proof is available. Borrowed message text must not use
the localized-message cleanup flag, which can describe a different override.
Resolve exact per-type dispatch getters and their fresh-versus-borrowed result
proofs, preserve builtin constructor data, and reject unknown/custom projections
until their scheduled implementation. Existing P0 return/retention facts remain
the authority; pending Java exceptions and retained/emergency native throwables
must survive cleanup. Add source/artifact and fresh/borrowed/unknown projection
tests before native snapshot generation, then bounded graph/cycle/fallback tests.

Projection tests found source/class reconstruction gives EOFException native type
ID 3 versus 2 while every getter, property, ownership fact and resolved accessor
identity agrees. These IDs are private per-program descriptor indices. Validate
each against its own program and compare semantic projections across containers;
never transplant the numeric IDs. Production native-build identity must include
actual typed/LLVM and adapter inputs so differently ordered native builds cannot
share a build identity merely because their logical generation matches. Also,
Java OutOfMemoryError has VirtualMachineError between it and Error; Ironwood
deliberately has no such JVM-specific class. Catch-parity checks validate every
mapped native superclass catch, not identical direct-superclass names.

The builtin exception projection and existing String-result proof selectors pass
under strict Temurin 21 compilation. Every catalog type resolves its exact
getters; getParsedString is fresh, built-in messages remain borrowed, covariant
IOException causes and inherited bytesTransferred survive projection. All
unfreed modes, stale-fact refusal, unknown/fresh borrowed-result negatives and
source/class/archive semantic parity pass. License and diff checks pass. Evidence:
`workspace/java-bridge/experiments/string-copy-effects/exception-projections-final.log`.
This remains projection evidence only. Next generate protected getter entries,
bounded snapshot graph transport, exact built-in Java factories and integration
with the generated facade loader; neither P2 nor exception transport is complete.

`414a744` commits the projection checkpoint. Protected-accessor implementation
will reuse ordinary typed calls/field loads plus the existing result frame.
Every raising getter and trace follow-up gets its own catch-all, ordinary
exception-caught cleanup, and bounded nonrecursive status: success, allocation
failure, or another extraction failure. Do not call snapshot extraction again
from a getter's catch. Keep user-entry hot paths unchanged; export these helpers
only with their complete projection and preserve them through native final-link
optimization. Field reads require the exact projected type and a live caught
throwable, as guaranteed by the internal translator, not a new public handle API.

Protected exception entries pass typed-boundary assertions and LLVM 23
assembly/verification after the production native optimization/pruning stages.
Evidence: `p2/exception-entries/run-6977835187231532363`. Runtime getter/graph
qualification remains pending. A preparatory DateTimeParseException throw probe
is currently rejected by P0 scalar retention: passing existing owned-field facts
removes fresh internal storage errors, but known-yet-unclassified String/render,
secondary-exception and array-copy effects remain unknown. Exact diagnostics are
in `workspace/java-bridge/experiments/string-copy-effects/exception-entry-probe.log`.
Do not bypass those proofs to run that dependent fixture. Extend the reusable
effect model with audited operation contracts and focused safe/unsafe cases;
other projection/Java-factory work can proceed independently. No unavailable
getter transport or exception type is admitted by the public producer.

The final protected-entry selector, stale-attachment refusal and existing scalar
typed-entry selector pass. Final LLVM evidence is
`p2/exception-entries/run-5517468276199912130`; the earlier directory predates the
added stale-attachment assertion. Trace follow-ups accept only caught-exception
IR values or exact live Throwable references; unrelated references remain
rejected. Strict compiler compilation, license and diff checks pass. This
checkpoint is structural/LLVM evidence, not execution of allocating getters.

`43ec8af` commits protected getter entries. The isolated JNI message-copy probe
passes on pinned Temurin 21/22/23 with `-Xcheck:jni` (three exit-zero children,
`workspace/java-bridge/experiments/throwable-message`, commands and hashes saved).
JNI can set Throwable's nonfinal detailMessage without consumer flags. This is
needed for final DirectoryIteratorException: its Java constructor otherwise
substitutes the translated cause's Java class-name prefix. Production must
validate/cache that field at bootstrap and copy only on the cold failure path;
this experiment does not qualify a generated adapter.

Exception constructor review found FileSystemException(null, null, null)
currently returns an empty native message instead of Java's null. Before Java
factory generation, verify every null/empty/nonempty constructor combination and
inherited single-file constructors against the pinned Java 21 behavior. Correct
the native library, preserving file/other/reason snapshots and existing ownership.
Focused O0/O3 differential tests, the existing exception projection selector,
license and diff checks cover this prerequisite. This is an independent original
compatibility fix, not an imported implementation or a bridge-only semantic waiver.

The differential fails on the original native all-null case, then passes all 45
constructor cases at O0/O3 after the one-line null-message correction. It covers
all 27 null/empty/nonempty triples and six single-file base/subclass constructors
for each file value. Getters and empty/null distinctions match Java 21. The full
builtin projection selector, strict build, licenses and diff checks pass. Final
evidence: `p2/filesystem-messages/run-12261293698494187117`; verification log:
`experiments/throwable-message/filesystem-final.log`. No bridge entry proof was
bypassed to test this ordinary native-library behavior.

`4722052` commits that compatibility prerequisite. Next generate constructor
factories from the immutable builtin projection, with generation annotations on
every helper class and inclusion in loader preflight. Preserve exact constructor
fields, nullable parsed text, required IOException causes and transfer counts;
the adapter separately copies DirectoryIteratorException's message using the
validated JNI field. Constructors run only after native getters return. Tests
compile actual generated Java, inspect preflight identities/private entry access,
exercise every builtin constructor and pinned 21/22/23 child JVMs, and reject
stale projection input. Graph traversal, native transport, bootstrap caching and
resource-exhaustion fallback remain separate required work before P2 admission.

Generated factories pass for all 43 builtin classes under each pinned macOS
Temurin 21/22/23 in fresh children, with `-Xcheck:jni`, exit zero and empty stderr.
Cases cover null/empty/UTF-16 messages, exact Java classes, required and later
causes, secondary attachment, path fields, null filesystem data, transfer count
and nullable parsed text. Complete helper identity preflight and stale projection
refusal pass. Final generated-source/class hashes, commands and outputs are in
`p2/exception-factories/run-11559137272800080012`. Existing Java declaration and
loader selectors pass after the new overload; strict compilation, license and
diff checks pass. This proves factory construction, not native graph transport or
the still-pending DirectoryIteratorException message-copy adapter.

`f4e4ee3` commits generated builtin constructors. Retention-analysis review now
covers fixed runtime text operations: copy, concatenation of already-converted
values, char-prefix/range and UTF-8 snapshots, default object identity text, and
Throwable description. Audited runtime bodies allocate independent inline UTF-16
storage and retain no source pointer; concrete producer overrides remain ordinary
resolved calls. Conditional owned-text release performs only raw String release,
not a producer destructor or publication. Classify these effects in the existing
P0 solver, without changing ordinary borrowing/free proofs or treating arbitrary
arraycopy and secondary associations as observing. Paired typed-IR publication
and unknown-effect tests, source/class/archive concatenation proof tests, existing
retention/cleanup/root-consumer checks and strict/license/diff checks are selected.

Seven fixed text operations and two owned-text release helpers now have audited
retention classifications. Every paired publication stays rejected; unclassified
reference arraycopy and secondary associations stay unknown. Five focused
retention/root-consumer selectors pass. Fresh concatenation and its publication
negative pass every unfreed mode and source/class/archive reconstruction. The
private JNI result fixture passes ten O0/O3 child cases, including a new budget-2
failure during concatenation after both copied inputs were prepared, continued
allocation-free calls, pending Java delivery failure and exact input-plus-result
allocation counts. Final evidence: `p2/string-results/run-7733651757081369907`;
logs: `experiments/throwable-message/text-retention-final.log` and `text-native.log`.
O3 inspection retains 384-byte JNI frames for both alias and concat adapters,
with inline String field access and raw result release. The diagnostic checked-JNI
50,000-call alias loop reports 43,283,750 ns and exactly 100,000 allocations;
this cold short-run timing is not numerical acceptance. No production runtime or
lowering changed. Strict compilation, license and diff checks pass.

The DateTime throw probe now narrows to IrSystemArrayCopyInstruction and
IrAddSecondaryExceptionInstruction with existing owned-field facts. It still
does not pass entry admission (`exception-entry-after-text.log`). Inspection
shows the secondary associations occur on cleanup-helper unwind paths even when
their exact runtime text-release helper cannot raise. Next prove complete
nonraising helper closure before excluding unreachable unwind edges, preserving
unknown native/call effects; do not whitelist associations. Then attribute
primitive arraycopy through erased helper arguments without admitting reference
array publication. Full exception graph/adapter and real P2 artifact gates remain
pending.

`42dcd46` commits audited text effects. Before the control-flow change: consumers
are retention summaries, String result origin classification and all P0 root-slot
users. Reuse final ClosedWorldEffectAnalyzer summaries with a separate complete
callee/native-operation check; unknown instructions, missing targets, allocation
or outward throwing must preserve unwind edges. Never discard an edge from a
method name or an incomplete summary. Follow explicit branch/throw/invoke edges,
including phi predecessors, without changing emitted code or ordinary free facts.
Pair a nonraising cleanup with allocating, explicitly throwing, unresolved and
unknown-native helpers, plus direct secondary-association refusal. Recheck source
DateTime diagnostics, text/String proof consumers and existing retention tests.

BridgeControlFlow now supplies that conservative analysis-only view. Complete
helper validation precedes unwind-edge removal; phi inputs are filtered by exact
remaining edges, including when both predecessor blocks stay reachable. Tests
keep allocation, explicit throw, missing target, unknown intrinsic and transitive
unknown helper edges; direct associations remain unknown. The DateTime source
probe loses only the impossible cleanup associations and remains UNKNOWN for
erased System.arraycopy (`exception-entry-after-flow.log`). Six initial selectors
and three final focused selectors pass, including String proof artifact parity,
retention artifact reconstruction and root-slot consumers. Final logs are
`experiments/throwable-message/control-flow.log` and `control-flow-final.log`.
Strict compilation, license and diff checks pass. No emitted instruction or
mandatory free proof changed. Next resolve primitive arraycopy attribution, then
revalidate entry production using the existing owned-field facts.

`bbb1293` commits conservative unwind reachability. Arraycopy review will carry
the exact primitive array type through existing origin substitution, rather than
declaring erased Object arguments non-retaining. Summaries retain conditional
copy effects until caller substitution; only equal known primitive element types
can discharge them. Reference arrays, mixed alternatives, unknown types and
publication remain unproved. Pair local/field/returned primitive arrays and helper
calls with reference/mixed/unknown copy negatives, source/artifact parity and
existing retention/root tests. Runtime inspection confirms memmove copies only
the checked matching element representation. Existing invalid-arraycopy paths
exit the process; this retention change neither changes that behavior nor claims
Java exception containment for those paths. No arrays become a supported Java
Bridge signature. The intended consumer is valid internal primitive storage work
in existing constructors such as StringBuilder and DateTimeParseException.

Primitive attribution passes in all unfreed modes and source/class/archive forms;
erased helper, field-return and recursive copies retain exact element type.
Reference/mixed/mismatched/unknown copies remain UNKNOWN and publication stays
REJECTED. The first test attempted to free an erased helper's destination, which
ordinary escape analysis rejects; the positive now returns that destination and
a separate negative preserves the mandatory rejection in every mode. No free
exemption was added. DateTime construction now proves empty retention with the
existing owned-field facts (`exception-entry-after-array.log`); typed entry
admission has not yet been changed to consume those facts. Primitive, text and
control-flow selectors plus five retention/String/root-consumer checks pass;
logs: `array-effects-final.log`, `array-effects.log`, `array-consumers.log` under
`experiments/throwable-message`. Strict compilation, licenses and diff checks pass.

`12f9c27` commits primitive copy attribution. Entry/String-result validators now
need to pass their already-validated final construction facts to retention
analysis, matching the existing object-proof consumer. Private owned storage may
contain fresh/null values only; copied inputs remain subject to ordinary borrowing
and publication checks. Recheck unsafe retained String inputs and stale facts.
Execute actual protected DateTime message/parsed-text/index/cause/secondary/trace
getters using production typed entries and the generated Java constructor factory
through a private JNI harness. O0/O3 normal, native budgets 0/1/2 and injected Java
delivery failure must preserve temporary cleanup, contain getter allocation
failure and permit subsequent scalar calls. This will qualify native getter
transport, not a production loader, complete graph translator or P2 jar.

The eight O0/O3 getter children pass on pinned macOS ARM64 Temurin 21 with
`-Xcheck:jni`: normal transport, native allocation budgets 0/1/2, and injected
Java delivery failure. Exact DateTimeParseException class, message, UTF-16 parsed
text (NUL and unpaired surrogate), index, native trace and subsequent scalar
calls survive; allocation counters confirm fresh getter storage is released on
Java delivery failure. Evidence is `p2/exception-getters/run-156589059705721325`
under the workspace evidence root, with payload hashes and disassembly.
The O3 message/cause getters are leaf loads; the allocating parsed-text getter
has a 32-byte protected frame. Private JNI fail/ping frames are 352/320 bytes;
the outlined cold translator is 432 bytes. No production adapter performance
claim follows from this fixture. Entry and String-result proof regression
selectors pass (`experiments/throwable-message/getter-admission-consumers.log`),
including unsafe input retention and artifact parity. Strict Java/C compilation,
licenses and diff checks pass. Next implement bounded production exception
snapshot assembly and generated JNI transport, preserving the separate ownership
proofs and rejecting reachable unmapped exceptions until their phase supports them.

`bc30260` commits the protected getter checkpoint. Next add generated Java graph
assembly over copied snapshots: 32 nodes, 32 secondary edges per node and 32
native frames per node, with at most 64 Java call-site frames. Representable
cycles/shared identity survive; self edges and capacity omissions use explicit
copy-limit markers. Construct ordinary nodes before IOException-cause wrappers,
then attach remaining causes/secondary edges and traces. Preserve pending Java
allocation failure without recursive fallback construction. Consumers are the
private generated factory and forthcoming JNI snapshot adapter; no public API
is admitted by this helper alone. Pair ordinary/shared/cyclic graphs, required
wrapper causes, limits, malformed indices and copied UTF-16 data in child JVMs;
retain all constructor/preflight tests. Native traversal, truncation production,
DirectoryIterator message patch and actual JNI bootstrap integration remain
separate required work.

Generated graph assembly now passes alongside all 43 builtin constructor checks
under pinned macOS ARM64 Java 21/22/23, using the same Java 21 class files.
Positive cases include shared references, ordinary and wrapper cause cycles,
secondary cycles/order/duplicates, self/copy-limit markers, exact UTF-16 text,
32-node/32-edge/32-native-frame boundaries and visible 64-frame Java truncation.
Malformed bounds/indices/null graph fields remain LinkageErrors. A separate
32-MiB child heap retains completed graphs until assembly throws Java OOM,
then releases them and successfully assembles another graph. No native recovery
claim follows from that Java-only experiment. Evidence:
`p2/exception-factories/run-9320599122325051555`, including commands, class/source
hashes, stdout/stderr/status for the supported launchers; focused test log is
`experiments/throwable-message/graph-assembly-final.log`. Strict compilation,
license and diff checks pass. D214 records concrete bounded-copy conventions.
Next implement the C snapshot traversal/Java transport using these generators.

`e6d3d33` commits bounded Java assembly. Native transport will use protected trace
metadata to select exact projected getters, a bounded pointer queue to preserve
graph identity, and immediate Java UTF-16 copies with proved fresh-result cleanup.
Each snapshot getter failure ends traversal without another snapshot attempt;
pending JNI exceptions survive permitted local-reference/storage cleanup. Cache
and validate factory/Throwable/trace metadata before user-native initialization,
including the nonfinal detailMessage field. Generated C remains an internal
component until real bootstrap/producer integration; private harness execution
will test its production fragment, allocation limits, native graph data and
continued calls. Keep cold traversal outlined and inspect its O3 frame. No
throwable reclamation grant follows from this traversal.

Generated C now transports protected DateTime snapshots through the Java graph
assembler, including actual native source frames before Java call sites. All 14
O0/O3 Java 21 child cases pass; generated transport normal and budgets 0/1/2 also
pass under Java 22/23 (16 further children) against the same images/classes.
UTF-8 metadata tests cover supplementary text, long temporary storage, NUL and
invalid/truncated/overlong/surrogate sequences. Projection mismatch is rejected.
Graph assembly reuses bounded staging arrays instead of allocating six trimmed
copies. Only DirectoryIteratorException receives direct detailMessage completion;
file/path exceptions retain their constructor-owned reason data. Evidence:
`p2/exception-getters/run-3007672829378581731`, with full source/class/payload
hashes and commands. O3 generated fail/ping frames are 320 bytes; the outlined
cold translator is 1424 bytes and UTF-8 helper 576 bytes. No normal-call work was
added. Constructor/Java graph tests also pass in
`p2/exception-factories/run-12587829077099063866`; combined log is
`experiments/throwable-message/generated-transport-final.log`. Strict Java/C,
licenses and diff checks pass. The initial O3 test incorrectly required the user
frame first; native constructor/inlining frames legitimately precede it, so the
test now requires the exact producer file/line before the Java call site.

Native multi-node graph validation remains blocked by a concrete entry-proof
limitation, not waived: constructing DirectoryIteratorException around a fresh
IOException is rejected for fresh-to-fresh cause-field stores. Exact probe and
failure: `experiments/throwable-message/GraphEntryProbe.java` and
`graph-entry-probe.log`. Next review whether complete store attribution can prove
these independent fresh graphs without retaining an entry input. Pair hidden
input retention, loaded references and unknown effects with the accepted case;
preserve separate ownership/free/result-origin proofs. Do not expose a producer
path or claim native graph validation until that proof and its cases pass.

`edeec20` commits generated native transport. Pre-change review for independent
fresh graphs: the fixed-point solver already retains every nested field store,
helper effect and unknown call; dropping a final FRESH-to-FRESH store cannot erase
an INPUT-to-FRESH or LOADED-to-FRESH store recorded separately. Extend only that
final classification, not summary propagation, static/array publication, input
destinations, free proofs or result ownership. Test direct/helper/recursive fresh
graphs and an actual builtin exception cause against nested constructor/helper
input capture, mixed origins, loaded fields, static/array publication and unknown
operations. Preserve ordinary unsafe-free refusal. Run source/class/archive
parity in all unfreed modes, existing retention negatives/artifacts, root-result
and String-result proof consumers before validating native multi-node traversal.

The narrow final classification now proves independent fresh stores. Fifteen
method contracts agree in source, class directory, individual class and archive
forms in every unfreed mode. Constructor/helper input capture, loaded/mixed data,
unknown operations and static/array publication stay rejected; replacing an
input holder's field with a fresh root remains unknown. Ordinary unsafe child
free remains an error. The actual fresh IOException/DirectoryIterator cause
entry now produces successfully. The initial mixed-origin fixture discarded an
allocation, correctly rejected by unfreed=error; it now allocates only on the
fresh alternative. Baseline failure and five passing focused selectors are in
`experiments/throwable-message/fresh-graphs-baseline.log` and
`fresh-graphs-final.log`. Existing retention/artifact, String-result and bounded
root-result proof consumers pass, as do strict compilation, licenses and diff
checks. Native multi-node traversal validation is next.

`8b82fd0` commits independent fresh graph attribution. The native fixture now
also throws DirectoryIteratorException/IOException, FileSystemException,
InvalidPathException, cyclic IOException causes and a 40-link cause chain through
ordinary proved source entries. Generated Java values preserve exact cause
identity, the native DirectoryIterator message prefix, file/path reason fields
and the explicit 32-native-node plus omission-marker boundary. Scalar calls
continue afterward. Fourteen Java 21 O0/O3 children and sixteen Java 22/23
generated-transport children pass against matching final payloads in
`p2/exception-getters/run-11417003624197639369`; full source/class/image hashes,
commands, status and disassembly are retained. Focused log:
`experiments/throwable-message/native-multi-graph.log`. Strict Java/C, license and
diff checks pass. Native secondary associations, reachable exception admission,
bootstrap and producer/jar work remain required P2 tasks.

`af26856` commits native cause/constructor snapshot validation. Next discover the
exception projection from actual compiler-owned closed-world reachability,
including selected typed entries, initializers and snapshot getters. Iterate
getter attachment/reachability to a fixed point so mapping does not omit an
exception made reachable by extraction itself. Reuse ClosedWorldPruner rather
than duplicating call semantics. Reachable custom Throwable types remain producer
rejections until P3; unreachable private exception code should not poison a
scalar preview. Verify exact program binding, builtin hierarchy/implicit OOM,
initializer/helper paths, reachable-custom negatives and source/class/archive
parity. This component will become the producer's mandatory admission path;
the public producer remains unavailable while P2 integration is incomplete.

Closed-world projection discovery passes source/class-directory/individual-class/
archive tests in all unfreed modes. Builtin hierarchy, cause classes and implicit
OOM are included; reachable custom exceptions through helpers/initializers are
rejected, while unreachable private exception methods are pruned. Stale entry
modules return an unproved binding outcome. The native getter/graph fixture now
uses this automatic projection and passes all fourteen O0/O3 Java 21 children
in `p2/exception-getters/run-1489173042421232129`. This is a new payload; previous
Java 22/23 results remain tied to their recorded older payloads. Combined focused
log: `experiments/throwable-message/exception-closure.log`. Strict compilation,
licenses and diff checks pass. Next resolve source secondary-association proof
and validate native secondary/truncation/failure paths, then integrate the real
producer bootstrap, adapters, jar and remaining P2 gates.

`fe969b4` commits exception closure discovery. The next exact refusal is a source
`try`/throwing `finally`, isolated in `experiments/throwable-message/SecondaryEntryProbe.java`
and `secondary-before.log`: the association is unknown because caught exception
origins are intentionally not tracked yet. Before changing shared analysis, add
outward-thrown origins to the existing fixed point and feed protected landing-pad
values from their actual invoke/throw predecessors. Keep exception handles opaque.
Known native allocation failures are independent implicit-OOM occurrences, not
ownership proofs; unknown native effects/missing callees remain unknown. Carry
secondary associations through helper substitution and discharge only independent
native origins, preserving all nested field stores and failures. Pair source
fresh finally/helper/recursive/catch cases with input, loaded, mixed and unknown
throwable cases, mandatory free refusal and source/artifact parity. Recheck
control-flow, retention/root/String consumers and native failure cleanup. Do not
grant a blanket exception-association exemption or a destructor ownership rule.

Caught/outward origin attribution now passes six independent source cases and
seven input/loaded/mixed/unknown/hidden-capture negatives, with source, class
directory, individual class and archive parity in all unfreed modes. Initializer
and recursive helper associations are included. Missing helpers, undefined SSA
values and opaque unwind handles do not acquire origins; freeing a caught alias
remains rejected. Two initial false positives were corrected without exemptions:
classified empty fixed-point results on impossible rollback edges differ from
absent producers, and raw null throws never yield a caught object (the frontend
explicitly creates NullPointerException). Unknown actual producers still propagate.
Three text/control-flow/secondary selectors and seven focused retention, fresh,
root and String proof consumers pass. Logs: `secondary-proofs-null.log` and
`secondary-consumers.log` under `experiments/throwable-message`. Native String
result and generated exception consumers also pass at O0/O3 in
`p2/string-results/run-902125703157102930` and
`p2/exception-getters/run-14634216238127348425` (`secondary-native-consumers.log`).
Strict compilation, licenses and diff checks pass. No emitted runtime code or
mandatory reclamation fact changed. Runtime association/wrapper host-calloc
exhaustion still has existing fatal-emergency paths; managed allocation-limit
checks do not qualify those separate system-heap exhaustion paths. Next exercise
actual native secondary graphs, truncation and repeated initializer snapshots.

`9c057c2` commits caught-origin retention. Native generated snapshots now preserve
ordinary secondary failures, 40-failure occurrence order with an explicit
32-secondary limit marker, native trace truncation and repeated stored initializer
failures. Managed budget 1 preserves the normal primary and implicit OOM secondary;
budget 2 preserves the ordinary secondary, and subsequent allocation failures and
scalar calls still work. The initial budget-0 expectation failed with exactly
`allocation failed while implicit OutOfMemoryError is active`, recorded in
`native-secondary.log` / `p2/exception-getters/run-3744606077095392090`. Review of
D070, D081 and MEMORY.md confirmed this is the accepted second-failure fatal
boundary, not a bridge recovery case. The test now preserves that isolated
exit-1 control and explicitly labels it as non-recoverable; no runtime contract
was changed or coverage removed.

Final source/class/payload evidence is
`p2/exception-getters/run-12243100898431218434`. On the same final O0/O3 images
and Java 21 class files, pinned Java 21/22/23 verify 54 recoverable transport
children and six documented fatal controls. Logs/status/expectations preserve
the distinction; payload/source/class hashes are recorded. Focused log:
`experiments/throwable-message/native-secondary-final.log`. Strict Java/C,
license and diff checks pass. These remain private JNI qualification fixtures,
not real producer jars. Next integrate generated scalar/String adapters and
identity-checked bootstrap with the loader and packaging pipeline, then execute
the remaining P2 distribution, collision, D209 and D210 gates.

### Generated value adapters: pre-change review

The next generator consumes the existing proved typed scalar/copied-String
entries and String result cleanup contracts. It must neither infer ownership in
C nor add hot-path world/thread/identity checks. Every acquired JNI UTF-16 buffer
must be released on normal, native-failure and partial-acquisition exits; owned
native results must be released after Java copying even with a pending Java
exception. Native exception work uses the protected snapshot entries. Bootstrap
remains separate and must initialize cached exception metadata before binding.
Focused verification will cover every scalar carrier, UTF-16/null String values,
fresh/alias/immortal results, conversion-before-target failures, injected JNI
acquisition/delivery failures, native allocation counts and O3 scalar disassembly.
Mismatched artifacts and unsupported reference/instance entry shapes must be
rejected by the generator. Public producer admission remains closed until the
complete P2 pipeline is integrated and qualified.

`BridgeValueNativeSources` now emits value adapters and exact callable/descriptor
bindings, using the existing typed conversion and result-lifetime contracts.
Every primitive carrier, null/embedded-NUL/unpaired-surrogate String, fresh/alias/
immortal result, native exception and conversion-budget case passes. Separate
fault images inject first/second JNI acquisition and Java result-allocation
failure, preserving pending exceptions and returning all acquired buffers and
owned results to baseline. Mismatched analyzed artifacts and closures attached
to other entries are rejected. No shared ownership proof or runtime changed.

Final evidence: `p2/value-adapters/run-9307225434529863560`, with matched source,
Java classes and payload hashes. Thirty child runs pass on pinned Java 21/22/23
at O0/O3 (production and separately identified fault variants). The primary
focused test ran through `scripts/test.sh`; final hardened generator and benchmark
revisions passed strict Java compilation and the same focused selector. O3 scalar
adapter instructions match the handwritten JNI equivalent after address
normalization; both have a 320-byte frame and call the four-instruction typed
integer-add entry. Warmed 200,000-call samples allocate zero native objects;
five alternating-order pairs are retained per launcher/optimization level.
These timings are local diagnostic evidence, not final P6 numerical acceptance.
Strict C compilation, license and diff checks pass. Next integrate production
bootstrap preflight, immutable build pairing, registration rollback and loader
anchoring, then package and exercise the real generated artifact.

`5311926d` commits generated value adapters. Bootstrap pre-change review: preserve
complete identity/signature preflight before registration, no Java facade or
native source initialization during that preflight, exact validated-Class
registration, original-loader idempotence, permanent loader anchoring and mapped
image rebinding refusal. All new checks are load-time work. Planned focused
tests pair successful binding with stale build/class/signature inputs, disjoint
and colliding artifacts, late registration failure and repeated/different-loader
bootstrap; P0 loader fixtures remain regression consumers. No public CLI or
unsupported target/version admission is added by this internal integration.

`BridgeBootstrapSources` now emits image-specific binding from generated Java
and value-adapter descriptors. Native generation/schema/API/build checks precede
complete per-class identity, defining-loader and private-native-signature checks,
using the payload's embedded expectations. The loader and native bootstrap share
an initialization-free Java reflection helper. Bootstrap caches exception metadata
and anchors the loader plus a private class-array copy before registration. It
rejects a different loader or class set and leaves original-loader repeats
idempotent. Registration failure cleans up completed and potentially partially
registered classes belonging to this validated artifact, preserving the original
Java exception; a bound/failed image cannot be rebound.

Generated-jar evidence: `p2/bootstrap/run-6549079300125602562`. Twenty-four child
runs pass on pinned Java 21/22/23 at O0/O3: ordinary values and checked exceptions,
stored lazy initializer failures, conversion OOM followed by scalar recovery,
permanent anchoring through GC, same/different-loader repeats, wrong pairing and
class-set controls, and separate injected partial-registration failures. Both
packaged and automatically extracted images pass strict macOS codesign checking
and retain identical SHA-256 bytes. Source/classes/jars/payload hashes are saved.
This is integration through actual generated jars, but their assembly remains a
test harness with fixture producer identities, not the finished producer command.
The fault jars are separately identified and never counted as production images.
Remaining P2 gates include production producer/atomic packaging, complete collision
and mapped-image tests through that path, launch-form coverage, Java 24 refusal,
D209 and required distribution notices/source. These are not P2 completion claims.

Focused logs `experiments/throwable-message/bootstrap.log` and
`bootstrap-regressions.log` record generated-jar integration, native-supplied
preflight mismatch controls and the existing P0 loader lifecycle regression
(`p0b/loaders/run-15398187124957013092`). Strict Java/C compilation, license audit
and diff checks pass. No runtime/ownership analysis or supported JVM range changed.

`a2441e66` commits generated bootstrap integration. Producer preparation exposed
an inaccurate fixture minimum-OS placeholder: `otool -l` reports macOS 26.0 in
the actual linked image, while the harness supplied 11.0 to the loader. This
never qualified older hosts, but must be corrected before production packaging.
`BridgeMacPayload` reads final thin baseline ARM64 Mach-O deployment/SDK metadata
and dependency names with bounded load-command parsing. The producer must use
those facts rather than infer a minimum from its host or a hard-coded placeholder.
Focused positive/negative format checks and the corrected generated-jar fixture
will validate this packaging prerequisite; no native target or minimum is lowered.

The payload metadata selector and corrected generated-jar fixture pass. Final
evidence `p2/bootstrap/run-15885657704090755465` supersedes the placeholder-bearing
jars for this checkpoint: all 24 Java 21/22/23 O0/O3 child cases and extracted
signature checks pass, source/class/payload hashes are recorded, and the parsed
deployment target agrees with saved `otool` output. Focused log:
`experiments/throwable-message/payload-metadata.log`. Malformed header/extent,
command alignment, architecture/subtype, platform, duplicate/missing target and
dependency-string cases are rejected; legacy version records are also tested.
Native code and supported targets are unchanged. The producer command, atomic
packager and remaining P2 gates are next.

`7e618601` commits final-image deployment metadata. Producer/packaging review:
reuse package discovery, analyzed API/retention/result contracts, automatic
exception closure and the existing native optimizer/linker. Reject unsupported
capabilities before publication. Stage a complete Java 21 jar beside its output
and atomically replace only after compilation, native linking, signature/target
inspection, inventory and archive validation succeed. Preserve previous output
on every failed stage. Package generated source/API documentation and required
library/runtime source and notices with identities tied to their actual bytes.
Public producer tests must cover source/class/archive equivalence, unsupported
API/free failures, ordinary Java launch forms, output preservation, manifest and
source/license contents. Keep Linux publication and broader API admission gated
until their scheduled phases, and retain the Java 21-23 consumer baseline.

Packaging foundations now inventory exact analyzed library source, runtime
C/headers and required notice/license texts, with immutable byte snapshots and
content identities. Source/class/individual-class/archive reconstruction yields
the same corresponding-source inventory. The test uses Instant parsing to include
the Classpath-covered calendar helper; its original Double-formatting fixture
correctly failed the coverage assertion because that implementation is independent.
Missing required notices fail; changed runtime source changes the inventory.
Application implementation source is not silently bundled as library source.

`BridgeJarArchive` verifies a complete staged jar before atomic publication,
preserves the Java manifest's leading position, fixes entry timestamps and
checks every entry's byte digest after reopening. Focused controls reject unsafe
entry names, missing manifests and symlink outputs without altering an earlier
jar; successful replacement is deterministic and leaves no staged archive.
Logs: `experiments/throwable-message/distribution-inputs-final.log` and
`jar-publication.log`. Strict Java compilation, license audit and diff checks pass.
These helpers are still internal; next compose the producer and its CLI, with
build-failure output-preservation checks through the complete pipeline.

`809e2933` commits archive/source inventory foundations. The next producer
composition uses a value-signature selector that includes String results while
retaining the earlier scalar selector for its existing consumers. Signature
selection grants no lifetime authority: `BridgeEntryModule.stringValues` and
the already qualified JNI result transport remain mandatory. Focused controls
pair fresh/alias/null/immortal return signatures with a published-input rejection
and general-object rejection; generated-jar checks exercise the complete String
result path before the public producer is enabled.

Value signature selection, earlier scalar rejection controls and generation
identity/reconstruction checks pass alongside the expanded generated-jar fixture.
Final jar evidence is `p2/bootstrap/run-12853630734007133747`, with all 24 Java
21/22/23 O0/O3 child cases passing on the same hashed jars, including String
alias/fresh/immortal/null transport and extracted signature checks. The small
recheck runner is saved at `experiments/bootstrap/recheck-supported-jdks.py`.
Focused log: `experiments/throwable-message/value-selection.log`. Mandatory
retention/result proofs are unchanged, and a stored input still blocks typed
entry construction. Strict Java/C compilation, licenses and diff checks pass.

`b51b24f3` commits String-result surface admission. The public experimental
producer now composes the proved module, built-in exception closure, Java/JNI
generators, validated bootstrap, final linker and atomic jar writer. It records
actual compiler/runtime, SDK/toolchain/header, native IR/adapter and distribution
inputs. Java 21 classes and complete generated source/Javadoc/legal contents
travel with the paired image and required corresponding library/runtime source.
Object/callback/TLS/Linux capabilities remain rejected. The automatic module
name uses the producing artifact basename, remaining stable across implementation
updates; independent modules need distinct producing names.

The primary focused selector `Java Bridge producer publishes paired jars with
source parity and failure preservation` passes through `scripts/test.sh`.
Evidence: `p2/producer/run-540270787486159252`; log:
`experiments/producer/producer-test.log`. Source O0, compiled-class O3 and archive
O3 jars share generation/API/program/module identities. Eighteen Java 21 child
runs cover all three ordinary launch forms plus allocation budgets 0/1/2.
Thirty-six additional Java 22/23 default and checked-JNI child launches pass
with no warnings, unchanged extracted image hashes and strict signature checks.
`experiments/producer/recheck-supported.py` records commands, stderr, statuses,
payload/class/source hashes and signature evidence for those same jars.

Unsupported object/retaining input/unsafe-free cases in every missing-free mode,
invalid options and an injected adapter compiler failure preserve the previous
jar. Staging cleanup succeeds. Strict compilation, license audit and diff checks
pass. Remaining P2 work includes complete producer-path loader collision/GC/
mapped-image controls, dependency-license handling, version refusal and the
scheduled D209 Java 25 experiment. This is a producer checkpoint, not P2 exit.

`8e9b0373` commits the public value producer checkpoint. P2 D203/D209 preparation
verified exact Temurin 24.0.2+12 and 25.0.4.1+1 macOS ARM64 archive checksums
against both official companion checksums and release-asset metadata, then
passed offline installation identity checks. The separate experimental compiler
copies the full production compiler and changes only admission policy plus its
metadata; native implementation and pairing checks remain unchanged, while the
actual changed compiler inventory produces a distinct generation/build identity.

The first version fixture's console-printing initializer was rejected by the
existing conservative String-result proof (unclassified PrintStream/stream
effects). Evidence `p2/version-policy-1/O0-ordinary/producer.stderr`. No proof
was relaxed. Use pure stored initializer state for functional checks and inspect
the deny child's actual image mappings to establish that its extracted payload
was never loaded. This fixes the probe without admitting unproved stream effects.

D203/D209 completed in `p2/version-policy-3`: all 36 O0/O3 children pass their
expected assertions. Six Java 21 ordinary launches pass warning-free; twelve
ordinary Java 24/25 launches refuse before extraction; twelve experimental
Java 25 default/checked-JNI launches pass values, strings, initialization and
exception/continued-call checks with policy warnings and no observed JNI misuse.
Six explicit-deny children verify the extracted image is not mapped. Full
commands/warnings/statuses, launcher/signature/attribute evidence and matched
compiler/class/jar/payload hashes are retained. The earlier `version-policy-2`
run exposed a harness assumption: repeated failed facade initialization produces
the JVM's NoClassDefFoundError, not the first UnsatisfiedLinkError. The harness
now asserts that actual behavior; no product code changed for this correction.

The report `docs/JAVA_BRIDGE_JAVA25.md` records results, exact payload identities,
limitations and a recommendation to consider warning-based Java 25 admission
later. The maintainer's authorized Java 21-23 decision is recorded in D209 and
the plan before P6; support is unchanged. Preparation's eight focused tests,
license audit and diff checks pass. Next complete remaining P2 producer loader
and distribution gates before P3a.

`272e8582` commits D203/D209 evidence and the retained product policy. The next
P2 loader qualification builds actual producer jars at O0/O3, covers duplicate
classes and package-only conflicts in both class-path/first-use orders, disjoint
artifacts, mixed classes/signatures, failed first use, explicit pairing repeats,
GC anchoring and a test-only retained-image JNI_OnLoad invocation. A separately
identified fault producer will inject partial registration failure and count
cleanup without adding a shipped hook. Require original bindings to remain
usable and collision failures to precede extraction; retain all artifact hashes.

Producer loader qualification passes 120 checked-JNI children on pinned macOS
ARM64 Java 21/22/23 at O0/O3. `p2/producer-loaders-1` records 78 package/class
collision, disjoint, mixed-class/signature, anchor/repeat and partial-registration
cases. Collisions fail before extracting the losing image and preserve the
winner. GC retains the successful loader; the test-only RTLD_NOLOAD inspector
invokes the mapped production JNI_OnLoad hook and observes JNI_ERR without
disturbing original calls. The separately inventoried fault compiler partially
registers the second API class, then verifies two successful cleanup calls and
permanent failed-image refusal, with the disjoint production image still usable.

`p2/producer-loaders-deployment-2` records 42 additional host/floor, missing/
corrupt resource, unsafe directory, corrupt existing file and native-build pairing
controls. Failed resource extraction removes temporary payloads; corrupt existing
bytes remain unchanged and unmapped. Build mismatch leaves the image unbound;
all cases preserve an already loaded disjoint artifact. The preceding deployment
run correctly rejected corrupt bytes, but its assertion expected "hash" instead
of the actual "digest" diagnostic; the test wording was corrected, not the loader.

The checked-in runner is `scripts/java-bridge/check-producer-loaders.py`, with
optional exact `--scenario` selection. Both evidence directories retain complete
commands/stdout/stderr/statuses, host/JDK records, modified fault-producer source,
actual compiler/class/jar/image hashes and extracted signature checks. Fault and
deliberately inconsistent artifacts remain separate negative controls. Strict
Java/C compilation, licenses and diff checks pass. Remaining P2 work: dependency
notice propagation/supplemental packaging, preview usage/demo and the gate audit.

`ea3181f4` commits producer-path loading qualification. Distribution follow-up
preserves notices from explicitly supplied archives that actually contributed
analyzed source, with archive/source consistency checks and byte identities.
Add repeatable `--license <file>` for application notices/source-availability
statements, matching the existing ironjar convention. Do not infer application
licensing or silently publish application implementation source. Revalidate
these inputs before publication, and test missing/changed inputs, same-named
notices, unused archives and preservation of earlier output.

Distribution follow-up passes both focused selectors through `scripts/test.sh`:
`Java Bridge preserves used archive notices and rejects changed source inventories`
and the expanded producer publication test. Log: `experiments/producer/producer-notices.log`.
Final producer evidence is `p2/producer/run-14323175281039715469`: source/class/
archive jars retain matching logical identities, exact application notice bytes,
archive notices only for the archive input, and all 18 existing Java 21 launch/
allocation cases. Missing notice files preserve the earlier jar. Direct inventory
controls cover nested declarations carried by an outer archive member, unused
archives, same-named notices, content/identity changes and changed analyzed source.
No application implementation source is silently repackaged. Strict compilation,
license audit and diff checks pass. This changes distribution inventory only;
native transport, loader semantics and accepted proofs are unchanged.

`da9e55a8` commits notice propagation. The public usage guide and nested
`examples/java-bridge/value` workflow now demonstrate ordinary Ironwood class
compilation, paired jar production, Java compilation and flag-free execution.
The example passes on pinned Java 21/22/23 with exact expected output, a caught
IOException and a successful subsequent call. Logs and jar identity are under
`experiments/producer/preview-example-*`. `jar --describe-module` confirms the
documented automatic-module inspection command. D215 records stable producing
basename/module naming and notice conventions. License and diff checks pass.
The native example catalog remains unchanged; bridge examples use their own
nested JVM workflows and are explicitly documented separately.

The P2 exit audit identified a final integration coverage step: run rich built-in
exception graphs, copied getter fields, bounded truncation and exhaustion through
public producer jars, supplementing the existing generated/private-adapter
extraction tests. Reuse their native source fixture, keep all faults in child
JVMs, and require continued scalar/exception use after recoverable failures.
P2 is not yet marked complete; P3a remains dependent on this audit.

`bf92e93c` commits preview documentation and the runnable value example. The final
rich-exception producer selector passes through `scripts/test.sh`; evidence is
`p2/producer-exceptions/run-17218665186162862845`. Sixteen Java 21 children plus
32 pinned Java 22/23 repetitions verify 42 recoverable cases and six documented
fatal implicit-OOM double-failure controls. Source O0 and archive O3 jars share
generation/API/program/module identities. Their built-in fields, cause cycles,
secondary order/limits, native/Java frame order, retained initialization errors,
native allocation fallback and Java heap-exhaustion recovery pass. Subsequent
scalar and exception calls succeed after recoverable failures. Matched jar/class/
source/extracted-image hashes and signatures are retained. Recheck runner:
`experiments/producer/recheck-exceptions.py`; primary log:
`experiments/producer/producer-exceptions.log`.

The notice-bearing producer artifacts also pass their remaining 36 Java 22/23
default/checked-JNI launch checks against unchanged signed bytes; log:
`experiments/producer/producer-notices-supported.log`. No native implementation
changed during these final integration checks. Strict compilation, license and
diff checks pass. `docs/JAVA_BRIDGE_P2_EVIDENCE.md` completes the gate audit:
P2 passes for implementation, with no deferred P2 safety/functional requirement.
Proceed to P3a; the task remains active through P4/P6a/ARM64 P6b. No P3/object,
P6 qualification, numerical acceptance or release-readiness claim is made.

### P3a pre-change review: concrete signatures and production proof composition

`a1bf791d` closes P2. The first P3a change extends only compiler-owned signature
selection to final concrete classes, public constructors/instance methods and
static nested classes. It preserves exact-package closure, resolved inherited
Object identity semantics and rejection of unsupported public members. Selection
is not an ownership grant and is not enabled in the public value producer.
Enums and custom throwable snapshots follow as separate P3a increments.

Consumers: `BridgeApiFacts` supplies final-program-bound resolved declarations;
`BridgeExportSurface` selects exact roots; P0 construction/result-origin,
non-reclamation, retention and destruction analyzers must authorize the complete
selected closure before production object lowering. `BridgeGeneration`, Java/JNI
generation and the public command still require the existing value surface.
No shared escape/ownership analysis or hot lowering changes in this increment.
Mandatory safety in every unfreed mode, unknown-effect conservatism and D132/D133
remain unchanged. Subsequent proof composition must distinguish fresh origin,
publication, stable permanent storage and actual destruction permission, and must
revalidate specialized/generated roots instead of inferring permission from shape.

Focused selection: new `Java Bridge concrete object signatures preserve closure
and reconstruction gates`, existing public surface/value selection and API
projection selectors. Safe cases include constructor/instance overloads, exact
self/child types, primitive/String methods and nested identities. Paired rejected
cases include non-final objects, non-static inner types, inheritance/defaults,
arrays/generics, mutable fields, missing/inaccessible signature types and source
`equals(Object)` overrides. Source, class-directory and archive forms must agree;
the public value gate must continue rejecting object APIs. Later proof changes
will add explicit P0 safe/unsafe retention, non-reclamation, destruction and pool
consumer checks before implementation. No native benchmark is needed for this
signature-only change. License and diff checks remain required.

Concrete signature selection passes its new selector after correcting the test
fixture's required `@Override` annotations. Every negative shape is valid ordinary
source before bridge rejection, so parser/semantic failures cannot mask an
admission defect. Source/class/archive roots and diagnostics agree. Existing API,
scalar-surface and String-value selectors also pass. Logs:
`experiments/p3a-signatures.log` and `experiments/p3a-signatures-recheck.log`.
Strict Java 21 compilation and the script's license audit pass; no native lowering
changed. The public producer remains value-only. Next compose production lifetime
contracts and expand the admitted enum/custom-exception closure; P3a is incomplete.

`d693f7b4` commits concrete signature selection. Next P3a proof increment: compose
copied String values with reclaimable-root surfaces. The existing root analyzer
incorrectly treats every String signature as a native facade, while the existing
String-result proof intentionally permits only static scalar/String entries.
Add a separately requested object-context String proof bound to the complete
root-retention contract. Keep scalar consumers unchanged. Known dependent String
results may be copied while their proved owner is live, but never reclaimed by
the result adapter; fresh/temporary-alias results retain their existing cleanup.
Copied String arguments must remain confined through normal and exceptional
paths. A String stored in a root or permanent slot is not an ownership transfer
of the temporary copy and remains rejected. Existing object entry lowering must
explicitly reject these signatures until its conversion protocol is implemented.

Affected consumers are bridge-only root/destruction and String-result admission,
the scalar JNI cleanup decision, and P0 entry builders. Ordinary escape facts,
pool ownership, rollback analysis and source reclamation diagnostics are reused
unchanged. Select the new object/String safe and unsafe proof/parity selector,
existing String input/result proof tests, root retention, dependent views and
destruction tests. Check stale program/root binding, owned-field getter versus
unknown/mixed result, temporary alias/fresh/literal results, copied-input capture,
and producer/lowering refusal while incomplete. Preserve all unfreed modes.
The scalar C cleanup output must stay identical for every existing result kind;
no valid-path runtime bookkeeping is added. Revisit this selection if lowering or
shared analysis changes beyond these proof consumers.

Scope correction before shared-analysis editing: the nullable owned-String
getter exposed an existing ordinary-source false positive. Detailed rejected-free
evidence identifies `absent ? null : label` as lacking a dependent-borrow contract.
`OwnedArrayFieldAnalyzer` recognizes only direct/cast/summarized field returns;
it also treats a separate `return null` as a non-borrowing alternative that erases
the method's borrowed-field metadata. Correct these equivalent nullable forms
without admitting fresh, unrelated or unknown non-null alternatives. Continue
checking conditions and both branches for publication/reentrant effects.

This changes shared owned-field facts consumed by escape summaries, ordinary
free checking, borrowed helpers, constructor rollback, pools and bridge result
origins. Add direct/conditional/early-null/nested-cast positive forms and paired
borrow-free, owner-free-before-use, publication, fresh/mismatched-field and
condition-publication negatives in every unfreed mode, with reconstruction parity.
Add the existing `owned reusable helpers remain dependent borrows across calls`,
`owned delegates distinguish observing and retaining extension paths`, and pool
release helper safety/artifact selectors to the earlier focused selection.
Native execution of the nullable helper fixture checks normal and absent values
and complete owner cleanup. This is a proof correction, not an exception to
mandatory ownership or a runtime tracking change.

A separate fixture expression `new String(label)` is rejected by the existing
constructor-argument confinement proof. That is not needed to test this bridge
conversion; the fresh-result case instead copies its String parameter while the
borrowed getter still returns the owned field. Its destructor remains present.
The constructor-argument boundary is not weakened in this change.

The same complete-closure test reaches `IrStringCopyInstruction`, currently
unknown to bridge non-reclamation. Its LLVM lowering calls only
`ironwood_string_copy`; inspection of `runtime/src/ironwood_runtime.c` confirms
fresh allocation and UTF-16 copying, with no source deallocation or user callback.
Classify this exact typed operation as non-reclaiming. Preserve unknown defaults
and all actual free/rollback handling. Include existing non-reclamation closure
and unknown/dynamic-deallocation selectors to protect the boundary.

Root/String proof composition and the nullable owned-field correction pass.
The new object/String selector covers owned and borrowed-view getters, nullable
results, fresh/temporary-alias/literal cleanup, copied-input publication, unknown
and mixed origins, stale program/root contracts, and positive/negative class/
archive parity. Its final log is `experiments/p3a-object-strings-final.log`.
`experiments/p3a-object-strings.log` records the separate nullable-owned-field
safe/unsafe/parity selector passing in all unfreed modes. Twelve existing/focused
regression selectors pass through `scripts/test.sh`, including ordinary helper,
delegate and pool release safety/reconstruction, bridge String/root/view/
destruction/non-reclamation contracts, and the new native O3 getter fixture with
exactly one child destruction. Log: `experiments/p3a-object-strings-regressions.log`.
Strict Java 21 compilation, license audit and diff checks pass.

No generated adapter admits object/String combinations yet. Existing scalar
cleanup emits the same release decision for each old result kind; borrowed
results explicitly carry no release permission. The next step is protected
combined root/String conversion lowering and proof consumption, followed by
remaining P3a enum/custom-exception and mixed-lifetime closure work. P3a remains
in progress; P3b/P3c/P3d, P4 and P6 remain outstanding.

`9fa66960` commits root/String proof composition and nullable dependent-return
correction. Next extend the existing `BridgeRootEntryLowering`, rather than add
a second object ABI. Its immutable root/retention/destruction facts plus every
String-result proof must pass before lowering. Input String copies precede target
initialization/allocation; every partial acquisition, initialization failure,
constructor rollback and target exception releases exactly the acquired temporary
copies. An input-alias result keeps only its returned copy through JNI delivery;
owned-field results never acquire release authority. Slot snapshots remain
complete before exception delivery, including store-then-throw. String is always
a copied value, never a constructed native root facade.

Affected consumers: P0 root/permanent builders share the typed lowering; P3's
future adapters consume its existing result frame and fixed slot payload. Preserve
the exact non-String entry IR, and retain the public producer's value-only gate.
Focused checks: object/String proof/parity tests now consume the combined module;
new typed/native root-String controls cover normal calls, null/UTF-16, result
ownership, partial copies, allocation exhaustion, constructor rollback, retention
on exception and continued scalar use. Run child processes at O0/O3, inspect O3
machine code and measure deterministic repeated calls. Re-run existing root entry,
root payload and permanent proof consumers only as needed by the changed lowering.
Keep evidence hashes paired with emitted source/IR/images. No runtime bookkeeping
or permission weakening is planned.

Protected root/String lowering passes the combined module proof/parity selector,
existing root-entry CFG and permanent proof consumers, plus 22 native child JVM
cases at O0/O3. Final native evidence: `p3a/root-strings/run-13186623901499725119`;
log `experiments/root-strings/native.log`. The children cover partial-copy and
root/owned-field allocation exhaustion, constructor rollback, stable borrowed
UTF-16 content, fresh and temporary-alias result cleanup, null inputs, retained
initialization failure, slot preservation on preparation failure, actual slot
clearing before exceptional exit, exact allocation/destruction counts and continued
scalar calls. Ordinary thrown NPE snapshots deliberately remain live in this
private transport; their three allocations are explicitly separated from temporary
or owned-storage leaks. This is shared-entry qualification, not public object-jar
or Java root-index/commit qualification.

O3 disassembly: `text` is four instructions, and `ping` is five, without helper,
TLS, registry or tracing calls. The two-String `lengths` path has its required two
copy/two release calls; exception bookkeeping is confined to failure paths.
One diagnostic native-loop sample measured 10,000 two-copy calls at 1,968,000 ns
(O0) / 1,677,000 ns (O3), with exactly 20,000 allocations and no retained temporary
storage. 100,000 borrowed getters took 641,000 / 168,000 ns with zero allocations.
These private-loop measurements include test checks, exclude public JNI/facade
transport and are not P6 numerical acceptance. Images, IR, native/Java source and
consumer classes have retained hashes and commands. The existing non-String root
fixture's complete emitted LLVM is byte-identical before/after in
`experiments/root-strings/{before,after}.ll`.

The first constructor negative-control selection also included unsupported array
signatures and therefore stopped at root resolution. Selecting the exact no-arg
String constructor now verifies its explicit copied-value refusal. Only that
failing selector was rerun; `experiments/root-strings/proofs-final.log` passes.
Other regression results are in `experiments/root-strings/regressions.log`.
Strict compilation, license and diff checks pass. Continue P3a with production
admission composition, complete enum/custom-snapshot and mixed-lifetime coverage,
and final specialized/generated-root proof revalidation before public adapters.

`e81a6a95` commits protected root/String lowering. Next P3a increment addresses
uniformly permanent objects with copied String parameters/results. A permanent
receiver may be published without lifetime counts, but this never permits a
temporary String copy to escape. The current retention summary records static/
array publication only as an unconditional failure, losing the published value's
origin. Preserve publication origins through helper substitution first, keeping
ordinary retention admission and diagnostics unchanged. Then add a separately
bound copied-input query using complete non-reclamation facts for permanent
types; do not disable the existing query or infer permanence from unknown origin.
Nested input capture, thrown/secondary exception capture and unknown effects
must still fail. Known owned-field String getters may borrow their proved permanent
owner during copying; fresh/alias cleanup remains separate.

Consumers: bridge retention, String conversion, exception getter extraction,
root-slot attribution, enum input proof, and P0 permanent/OrderBook admission.
Ordinary source ownership summaries are unchanged in this increment. Preserve
all previous root-slot rejection rules and source/class/archive parity. Focused
checks include attributed/rejected/dynamic/cleanup retention, recursive source/
artifact facts, fresh graph and exceptional-origin controls, and String-result
proofs. New permanent/String cases must pair receiver or permanent-input publication
with String publication through the same field/static/array/helper paths, plus
unknown native effects and stale complete-surface facts. Production adapters stay
gated; no runtime code or hot path changes are needed for the analysis increment.

Publication-origin groundwork passes all ten selected retention/String/enum
regressions through `scripts/test.sh`; log
`experiments/p3a-publication-origins.log`. Summaries now carry immutable origin
sets per static/array publication site, substitute them across helpers and join
them through recursion and initialization. A site is retained even before its
origins resolve, so the ordinary contract still rejects every previously
unsupported publication with the same diagnostic. This refactor grants no new
capability. Strict compilation, license audit and diff checks pass. Next implement
the separately bound permanent-object/copied-String query and its paired cases.

Production admission composition remains unresolved implementation work. Do not
silently convert a failed reclaimable-root proof into a permanent facade merely
to admit mixed fresh/existing results, child slots, cycles or unsupported effects.
D192 permanence needs complete closure evidence; D196/D202's required producer
rejections and eligible destruction capabilities must survive classification.
Uniform P0 permanent/root builders are foundations, not the final mixed-surface
policy. No new classification policy has been accepted or exposed here.

### P3a checkpoint: permanent objects with copied String values

Uniform permanent admission now excludes String from facade identities and proves
temporary confinement separately. Complete non-reclamation contracts bind the
retention query to the exact program/export roots; only that query marks proved
permanent storage as independent of copied inputs. Publication origins survive
helper erasure/substitution and static/array stores. Direct query tests reject
field/static/array/helper/nested-object/throwable capture independently of the
source borrowing gate. Fresh, input-alias, immortal and owned-field borrowed String
results keep distinct cleanup authority. Permanent entries reuse the protected
String lowering without free capabilities, root state or retention commits.

The new proof selector passes in all unfreed modes with source/class/archive
parity, stale-program/surface refusal, unknown-effect and mixed-result controls;
log `experiments/p3a-permanent-strings-recheck.log`. Existing root/String,
permanent and both actual OrderBook proof selectors pass in
`experiments/p3a-permanent-strings-final.log` (that run also records the initializer
case below as a failure before fixture isolation). Initial proof-only baseline
passed in `experiments/p3a-permanent-strings.log`.

Twelve isolated O0/O3 native children pass in
`p3a/permanent-strings/run-8686204557609217679`; log
`experiments/p3a-permanent-strings-fix.log`. They cover temporary-copy/root/owned
String allocation exhaustion, unpublished rollback, post-publication conversion
failure and continuation, unknown-origin identity, UTF-16 including NUL and
surrogates, fresh/alias release and borrowed storage preservation. Exactly two
published allocations intentionally remain until process exit. One thousand
getter/identity repetitions allocate nothing. O3 `text` and `unknown` each have
six instructions, and scalar `ping` five, with no helper/TLS/registry calls.
Source, IR, adapter, consumer and payload identities are retained with commands.
This is private shared-entry evidence, not public object facade qualification.

The added static array initializer uncovered an existing cleanup-query boundary:
`BridgeCleanupAnalyzer` supplies destructor/rollback roots to ordinary retention
analysis, which also adds their owning class's initialization even though cleanup
does not itself trigger that implicit initialization. The safe fixture was
rejected at the `<clinit>` publication site. Moving the array allocation into its
ordinary method isolates the permanent copied-input query and passes; this does
not resolve the initializer boundary or mark that original case accepted.

Next fix that boundary through an explicitly cleanup-only query, restricted to
destructor/constructor-rollback kinds. Preserve initialization reached by actual
typed calls inside cleanup, and keep implicit initialization for ordinary bridge
entry roots. Pair the original safe initializer with cleanup publication and
unknown/throwing/allocating helpers, and retain source/class/archive parity and
existing cleanup/root/permanent/OrderBook consumers. No runtime or source safety
rule change is needed. P3a remains in progress; producer object admission remains
disabled pending the complete closure and generated adapter gates.

All four selected ordinary retention regressions pass in
`experiments/p3a-permanent-strings-retention.log`: attributed/exceptional stores,
slot-transfer/unknown rejection, fresh graphs and caught secondary provenance.
Strict Java 21 compilation, license audit and diff checks pass for this increment.

`c0246220` commits permanent-object/copied-String composition. The subsequent
cleanup-only query now admits the original static array initializer safely.
It accepts only destructor/constructor-rollback roots, keeps final-fact binding,
and traverses actual typed initialization/call effects. Ordinary entry queries
still include their implicit initialization. The unchanged permanent/String
fixture now restores its static array allocation; all-mode source/class/archive
proofs pass in `experiments/p3a-cleanup-initialization-final.log`, along with
existing destructor/rollback retention and complete destruction checks.

The new paired selector passes in
`experiments/p3a-cleanup-initialization-controls-3.log`: independent owning-class
initialization is excluded only from descriptor-body effects; direct/helper
publication, actual called-initializer publication and unknown effects remain
unproved. An actually allocating cold initializer remains an ordinary source
error. Constructor rollback reclaims initialized owned fields without invoking
the source destructor, so uncalled destructor effects are not attributed to it.
Initial test attempts conflated these distinct paths and were corrected, with
no corresponding weakening of analysis. A misspelled selector initially stopped
the runner before tests; the corrected focused invocations are recorded. Existing
OrderBook rollback and root-entry lowering pass in
`experiments/p3a-cleanup-initialization-recheck.log`; that log also retains the
earlier fixture mismatch. Strict compilation, license and diff checks pass.

Next P3a increment inventories enum constants and synthesized callable roles from
final semantic facts, before extending signature selection or conversion. D194
requires named mappings and protected native initialization; source ordinals or
private constant addresses must not become the conversion ABI. Preserve exact
source/class/archive identities, constant-specific native types, declaration order
for Java enum behavior, and stale-program refusal. Existing API selection and
P0 enum proofs remain consumers; no public producer or new lifetime capability is
enabled by metadata alone. Then extend the same enum conversion foundations to
the admitted closure and final specialized/generated-root verification.

`e0864d5a` commits descriptor-body cleanup analysis. The next enum inventory
increment passes the new named-constant/synthesized-role selector plus existing
API projection and P0 named conversion proofs through `scripts/test.sh`; log
`experiments/p3a-enum-inventory.log`. Metadata preserves source declaration order,
names/spans, exact constant-specific native types and immutable reconstruction
across source/class/archive inputs. Empty nested enums remain distinguishable
from ordinary classes; source `values(int)` is not mistaken for synthesized
`values()`/`valueOf(String)`. Stale IR and metadata-only admission are rejected.
This is semantic inventory only; concrete/value selectors still reject enums
until conversion/dispatch/result proofs and adapters are implemented.

`1845f42a` commits enum inventory. Separate the P0 named-field mapping from its
scalar/final-method invocation restrictions so production enum inputs/results
can reuse one exact mapping contract. Bind names and resolved storage types to
final API/IR facts, keep arbitrary nonnegative unique producer tokens, and derive
default tokens by name rather than native ordinal. Empty enum metadata must not
be confused with a missing/ordinary type. P0 invocation/effect gates remain
unchanged while this mapping is extracted. Paired incomplete/duplicate/stale
mapping checks and source/class/archive parity accompany the existing enum
proof and O0/O3 native conversion checks. Virtual/abstract constant-specific
dispatch remains separate pending work; do not treat the base body as every
constant's implementation.

Named mapping extraction passes all three selected checks in
`experiments/p3a-enum-mapping.log`, including O0/O3 JNI conversion at
`p0b/enums/run-16114278363527581859`. `BridgeEnumConstants` validates complete
names, resolved field/storage types, unique nonnegative tokens and initializer
closure against final API/IR facts. P0 input admission consumes it and retains its
existing empty-instance, scalar/final-dispatch, effect and lifetime gates.
Default-token tests deliberately disagree with native ordinals, arbitrary tokens
remain bound by name, and empty metadata differs from a non-enum/missing type.
Missing/extra/duplicate/negative mappings, stale programs/type sets and ordinary
artifacts without bridge facts are rejected; source/class/archive mappings agree.
Strict compilation, license and diff checks pass. No lowering/hot path changed.

`364f1deb` commits named mapping extraction. Next record exact instance dispatch
slots in API facts and prove each enum constant's resolved implementation against
its compiler-owned dynamic type. Abstract enum declarations must resolve through
their constant bodies, not a fabricated base function. Keep this target inventory
separate from invocation/lifetime/cleanup permission; generated entries must later
validate that their named receiver selects the proved target. Preserve inherited
Java enum identity methods as Java behavior, including constants without a source
override when another constant overrides `toString`. Tests pair abstract/concrete
bodies and overloads with missing/stale mapping rejection and reconstruction;
P0 direct-final admission stays unchanged until protected dispatch integration.

Enum dispatch inventory passes `experiments/p3a-enum-dispatch-final.log`, with
source/class/archive parity, abstract declarations lacking base native functions,
distinct constant bodies, concrete base fallbacks, overload/static refusal and
stale/missing mapping controls. Existing API projection passes in
`experiments/p3a-enum-dispatch.log`. `BridgeEnumDispatch` binds final semantic
slots to the exact compiler-owned singleton dynamic types and resolved function
receivers/signatures. It distinguishes Java identity behavior from native source
overrides per constant. Initial classification by built-in owner name alone missed
the compiler-synthesized enum `toString`; the proof now also consumes the resolved
target's synthesized role, preserving a source override independently. No call
is emitted and no lifetime permission is granted by this inventory.

Next compose these exact target sets with enum-only permanent/value effect proofs
and protected named token conversion. Keep each actual resolved function as an
analysis root, including constant bodies behind an abstract declaration. Receiver
conversion must accept only the named constants selecting that target, use the
ordinary protected initialization and public field load, and preserve String
temporary/result cleanup. Java-only identity alternatives need no native entry.
Enum result conversion, combined object/enum surfaces and final generated-root
revalidation remain required before P3a can close or P3b admit these signatures.

`583bb954` commits exact enum dispatch inventory. `BridgeEnumInvocation` now
composes actual native target roots with named receiver restrictions, nullable
enum arguments and the reused permanent/String analyses. It adds all used enum
conversion initializers to the closure and proves every exposed enum/constant
receiver type non-reclaimable. Java-only alternatives produce no native entry.
Enum publication is accepted under these facts without accepting copied-String
capture; fresh/alias String result cleanup stays distinct. Enum/object results
remain explicitly rejected until protected result conversion is implemented.

The new selector passes through `scripts/test.sh` in
`experiments/p3a-enum-invocation-final.log`: abstract constant bodies, common base
bodies, nullable/empty enum arguments, enum publication with copied values,
unknown effects in just one constant body, static/thrown String capture,
changed token pairing/programs and all-mode unsafe-free controls. Positive and
negative source/class/archive reconstruction agree. The earlier proof-only pass
is `experiments/p3a-enum-invocation.log`. No entry lowering has changed yet.

Next extend the shared protected root/String lowering with a reusable named enum
conversion block builder extracted from P0. Preserve existing non-enum CFG/ABI
when no enum parameters exist. Copies acquire before native initialization;
enum initialization failure releases acquired copies before error extraction.
Invalid private tokens must skip source code and release temporaries. Receiver
field loads may convert to a constant body's native receiver type only under the
exact named dispatch proof. Check nullable/empty inputs, first-use asymmetric
values, abstract/overridden bodies, repeated initialization failure, partial
String-copy failure, invalid tokens and result cleanup in child JVMs at O0/O3;
inspect O3 code and allocation counts. No new wrapper unwind layer or source
semantics in JNI is required. Public producer gates remain closed.

`1853f7a0` commits enum invocation proofs. The shared protected lowering now
consumes them through `enumValues`; `BridgeEnumConversion` is extracted from
P0 and reused for named public-field loads, ordinary active use and exact
constant-body receiver types. Partial copies, initialization/target failures and
internal unmatched-token exits release acquired temporary values. These internal
controls do not establish a public security guarantee for private-entry bypass.

All 14 child JVM scenarios pass at O0/O3 in
`p3a/enum-values/run-13857726053452339548`; log
`experiments/p3a-enum-values-final.log` also passes the updated invocation proof/
reconstruction selector. Cases cover first-use asymmetric constructor fields,
abstract/overridden and shared bodies, nullable/empty inputs, malformed fixture
tokens, one/two-copy exhaustion, source fresh-result exhaustion, UTF-16 result
cleanup, repeated stored enum initialization failure and continued scalar calls.
The failed initializer's native exception remains held by ordinary stored-failure
semantics; repeated calls allocate only the temporary input copy and restore its
live-allocation baseline. Source/IR/adapter/consumer/payload hashes and commands
are retained. This remains private typed-entry evidence, not public enum jars.

O3 inspection removed one unnecessary synthetic-constant-class initialization
guard found in the pilot `run-2339064915954284171`; named receiver conversion
already performs D194 active use, and instance invocation itself adds no active
use. Actual source-body initialization remains intact. Final warmed scalar
entries have no helper/TLS/registry calls, only token selection, the required enum
initialization guard and source operations. A diagnostic sample of 100,000
scalar calls took 1,343,000 ns O0 / 379,000 ns O3 with zero allocations. 10,000
enum/String calls took 1,763,000 / 1,399,000 ns with exactly 10,000 temporary
allocations and no live remainder. These native-loop figures include test checks,
exclude public JNI/facade transport and are not P6 numerical acceptance.

Existing root-slot, root/String native and enum proof selectors pass in
`experiments/p3a-enum-lowering-regressions.log`; P0 enum native plus reconstructed
combined proofs pass in `experiments/p3a-enum-lowering-proofs.log`. Complete
emitted LLVM remains byte-identical for both prior fixtures: P0 enum SHA-256
`dff54a4dd67e3be801c4444df0de1da381a94c064fecee2f532f3ccea04ef1a7` between
`p0b/enums/run-16114278363527581859` and `run-16324909178755075010`; root/String
SHA-256 `3e0640858d5ac1d0f43fa9d3c6b41a0867a01c193a1f81e0c4be41a766db565f`
between `p3a/root-strings/run-13186623901499725119` and `run-7874903265833397213`.
Strict compilation, license and diff checks pass. Continue P3a with protected
enum results, complete surface composition and specialized/generated-root
revalidation; public object/enum/custom-exception adapters are still pending.

`98f89b40` commits the protected input lowering. Next extend invocation proofs
with exact declared enum result mappings, including result-only initialization
closures and non-reclamation facts. Shared consumers are the protected root/String
builder, permanent confinement query and source/class/archive reconstruction.
Null results map to the reserved null token without active use; non-null results
map through protected initialization and named public fields. Keep acquired
String copies live until conversion completes, then release them on every exit.
Pair nullable/constant/input-alias/saved enum results and empty enums with missing
mapping, ordinary-object results, unknown effects and String capture refusals.
Run the exact enum invocation selector, new child-process result cases at O0/O3,
and existing enum value controls. Inspect O3 and allocation counts; no public
producer gate or ordinary ownership analysis changes in this increment.

Protected enum results now pass the extended invocation selector in
`experiments/p3a-enum-results-proof-final.log`, including result-only otherwise
unreachable initializers, unknown initializer effects, named mapping absence,
ordinary-object refusal, String capture and source/class/archive parity. The
earlier `p3a-enum-results-native.log` records a passing proof selector and a
fixture rejection: publishing constructor `this` is forbidden by ordinary
construction safety. The fixture now publishes the completed FIRST constant in
a static initializer before a later failure. No safety rule was changed.

All 20 O0/O3 child cases pass in `p3a/enum-values/run-15344384577785071090`;
log `experiments/p3a-enum-results-native-recheck.log`. Added cases cover cold
asymmetric returns, empty/null results without initialization, receiver/input
aliases, saved permanent values, pre-entry String-copy exhaustion, and result
conversion after a stored enum initialization failure. The latter proves the
target returned a published singleton, conversion raised under protection, the
temporary was released and the original exception identity was preserved. Null
results still work after that failure. Artifact/source/adapter/consumer hashes,
child commands and disassembly are retained with the evidence.

O3 `self` has no warmed helper/TLS/registry call, and the optimizer removes the
redundant warmed result initialization check after input conversion. Public-field
comparisons implement the paired result mapping; no private storage address or
ordinal is substituted. The result-loop diagnostic measured 100,000 calls at
331,000 ns O0 / 109,000 ns O3, checksum 700,000, zero allocations. It includes
fixture checks and excludes public Java facade transport, so it is not P6 timing
acceptance. Stored-result String calls retain only the required copy/deallocation
helpers on successful paths; exception/initialization helpers stay uncommon.

Existing root/String and P0 enum native selectors pass in
`experiments/p3a-enum-results-regressions.log`. Their complete LLVM remains
byte-identical to the prior checkpoint: root/String
`run-4821498381215534937` has SHA-256
`3e0640858d5ac1d0f43fa9d3c6b41a0867a01c193a1f81e0c4be41a766db565f`;
P0 enum `run-16194697811332591368` has SHA-256
`dff54a4dd67e3be801c4444df0de1da381a94c064fecee2f532f3ccea04ef1a7`.
Strict compilation, license audit and diff checks pass. Continue the P3a surface
composition and final specialized/generated-root admission work; public enum,
object and custom-exception generation remains gated.

`1dbc1d39` commits enum result conversion. Next add an internal complete
concrete/enum/String signature selector, reusing exact per-constant dispatch and
named metadata. Keep static preview and concrete-only selectors unchanged.
Generated enum values/valueOf and identity behavior are Java projections, but
source overloads/overrides remain checked and native targets remain explicit.
Abstract enum methods must contribute actual constant bodies, never fabricated
base roots. Empty enums must still diagnose unsupported public member signatures.
Paired controls cover arrays, generics, interfaces, custom throws pending snapshots,
missing export packages, nested accessibility, source overloads, stale facts and
reconstructed inputs. This signature-only step grants no executable lifetime
permission and changes no hot lowering. Run its focused selector plus existing
concrete and enum inventory checks before composition with admission contracts.

The internal `objectValues` selector passes through `scripts/test.sh` in
`experiments/p3a-object-enum-surface.log`. Mixed concrete constructors/results,
enum/String signatures, nested/empty enums, abstract constant bodies and partial
`toString` overrides preserve their exact native root union. Java-only generated
enum members add no roots; source values/valueOf overloads remain selected.
Unsupported members on uninhabited enums, arrays/generics, inaccessible types,
interfaces, custom throws and runtime-initialized fields fail selection. Explicit
package unions complete foreign enum signatures; stale facts and unfinished
public generation remain refused. Positive/negative source/class/archive results
agree. Existing concrete selection, enum inventory and preview rejection selectors
pass in `experiments/p3a-object-enum-surface-regressions.log`. Strict compilation,
license audit and diff checks pass; no runtime or lowering changes.

`5f04eb13` commits mixed signature selection. Next extract immutable enum
conversion metadata from enum-only invocation permission. It must bind exact
entry roots, public mappings, receiver restrictions and conversion initializer
roots independently of lifetime analysis, so mixed permanent/reclaimable
consumers can reuse it without a bypass. Enum-only invocation must retain every
existing confinement/non-reclamation check. Pair missing receiver dispatch,
missing mapping/entry roots and stale metadata with complete mixed constructor,
argument and result inventories; preserve reconstructed metadata and existing
enum LLVM/native behavior. Check generated enum helper roles and valid
constant-only surfaces without native methods: signature selection can represent
an empty root inventory, while executable proof consumers retain their separate
requirements. Inspection confirmed that Ironwood valueCount/valueAt are native
traversal helpers preserving source static initialization and invalid-index
exceptions, even on enums without user methods. The initial inventory-count
assertion omitted these helpers; fix the fixture expectation, not their roots.

`BridgeEnumConversions` now binds exact mixed-entry parameter/result/receiver
metadata and used initialization roots. Complete-surface reconstruction, missing
mapping/dispatch/entry controls, stale metadata, constructor argument indices,
enum traversal helpers and constant-only empty root sets pass in
`experiments/p3a-enum-conversion-inventory-final.log`. Enum-only invocation proofs
and parity pass in `experiments/p3a-enum-conversion-inventory.log`; its mixed
fixture count failure is the helper-expectation correction recorded above.
Public producer admission remains unchanged.

Both native selectors pass in `experiments/p3a-enum-conversion-native.log`.
The 20-case enum value fixture at `p3a/enum-values/run-17334740257665802338`
emits byte-identical LLVM to `run-15344384577785071090`, SHA-256
`ce0401a61632882d0e7eb740cf67d9ece6d651d719fb34f0137c44311f40356c`.
P0 enum at `p0b/enums/run-14020154950826262654` remains byte-identical to
`run-16194697811332591368`, with its recorded `dff54a4d...` hash. Strict
compilation, license and diff checks pass. Next compose this inventory with
complete permanent-object proof/rollback closure and protected mixed lowering;
retain separate reclaimable admission rather than falling back to permanence.

`091c7511` commits shared conversion inventory. The next increment supplies that
bound inventory to the existing permanent analyzer, expanding its proof closure
with conversion initializers and proving every exposed object/enum receiver and
result together. Exact enum receiver alternatives may satisfy direct-dispatch
requirements; no unknown effect, reclaimable type or failed rollback gains an
exemption. String confinement and result ownership remain separate. Preserve the
old permanent path when no conversion inventory is supplied. Verify safe mixed
construction/publication/returns against copied String capture, unknown enum
initializer/body effects, reachable object deallocation, missing/stale conversion
facts and failed rollback, including source/class/archive and all unfreed modes.
Then exercise constructor/enum/String allocation and stored-initializer failures
through child-process typed entries, inspect O3 and verify allocation behavior.

Mixed permanent admission and lowering pass the all-mode proof/reconstruction
selector in `experiments/p3a-permanent-enums-proof-recheck.log`. Complete
conversion initializer closure, owned-String rollback, constant-specific targets,
publication and String results compose without reclaimable root state. Unknown
body/initializer effects, copied-String capture, reachable exposed-type free,
unknown child destruction during rollback, stale programs and changed entry sets
remain rejected. An attempted parameter free remains an ordinary safety error
with unchanged diagnostics in every mode. Fixture development corrected two
controls: free must target a known fresh allocation to reach bridge admission;
an uncalled permanent owner's source destructor is not its synthesized rollback,
so unknown cleanup is tested on an owned child that rollback actually destroys.

All 20 native children pass at O0/O3 in
`p3a/permanent-enums/run-642769849329076709`; log
`experiments/p3a-permanent-enums-native-final.log` also passes existing permanent
String confinement/native and complete lifetime/rollback selectors. Mixed cases
cover first-use enum arguments before object allocation, null arguments/results,
copied String/root/owned-String exhaustion, enum stored failure, source constructor
failure, unchanged publication identity and continued calls. On constructor
failure, exactly four managed allocations occur: copy, root, owned String and
exception; rollback releases the first three and the private fixture retains one
exception. Earlier count logs record the corrected expectation. A reused result
frame is explicitly cleared on invalid-token constructor exit after copy cleanup.

O3 enum result getters retain the necessary native initialization guard and
named-field comparison, with no warmed helper/TLS/registry calls. The borrowed
String getter and published-object getter each use six instructions without
calls. 100,000 enum-result plus identity pairs measured 2,092,000 ns O0 /
635,000 ns O3, checksum 100,000 and zero allocations. These fixture timings are
diagnostic, not public-adapter or P6 numerical acceptance. Successful published
Catalog and owned String intentionally remain allocated until process exit.

The original permanent/String fixture remains byte-identical between
`p3a/permanent-strings/run-8686204557609217679` and
`run-3275089042183438349`, LLVM SHA-256
`c13b7ae16eb77c7d38d4ab784c42d8b34b7155a548d17d46dc351f766f44b085`.
Strict compilation, license audit and diff checks pass. P3a still requires mixed
reclaimable admission, custom exception contracts and final specialized/generated
root revalidation before public P3 adapters can advance.

`28c70ef7` commits mixed permanent entries. Next extend reclaimable root
contracts with each exposed reference type's possible independent root owners.
Retaining a borrowed value must add all its possible owner types to the repeated-
call dependency graph, including its own root type when standalone construction
is also admitted. A holder that can be borrowed remains ineligible for slots.
Preserve child-slot, unknown-owner, slot-transfer, mixed fresh/alias and cycle
refusals. The slot payload continues to report actual stored references; the
later adapter must translate proved input/view ownership before committing deltas.
No public root adapter is enabled by these metadata facts alone. Select safe
distinct holder/owner cases against self and mutual cycles, multiple owners,
owned-or-borrowed views, unknown owner and child holder controls in every mode and
source/class/archive reconstruction. Rerun existing root/view and slot-lowering
checks; no hot-path instructions should change for existing fixtures.

Root contracts now expose immutable `rootOwnerTypes`. Retained view inputs add
all possible independent owners to the dependency graph, including independent
instances of a type that is also exposed as a view. Child holders remain rejected.
The old same-owner view-retention control still fails, now for its actual possible
self-cycle instead of a blanket unimplemented-view refusal.

`experiments/p3a-view-owner-retention.log` passes four selectors: existing root
retention, protected slot lowering, dependent views and the new owner-alternative
proofs. The final new selector including positive and negative reconstruction
passes `experiments/p3a-view-owner-retention-parity.log`. Cases cover multiple
owners, owned-or-borrowed references, helpers/store-then-throw attribution, missing
owner origins, self/mutual cycles, child-held slots and loaded-slot transfers in
every unfreed mode. Source/class/archive proof outcomes agree. Existing root entry
LLVM is byte-identical between `p0b/root-entries/run-6430317110163886476` and
`run-14234346142615864010`, SHA-256
`02f85693066c44d40e3e7b85bcbfc7beea1118e69ec9d77655644aa2e96f871a`.
Strict compilation, license and diff checks pass. Actual borrowed-owner adapter
delta reconciliation is still required in P3d and is not claimed by these proof
tests; public root generation remains gated. Continue with enum values in the
reclaimable proof closure, bounded fresh-result slots, exception contracts and
final generated/specialized-root revalidation.

`d1e7decd` commits retained-view owner alternatives. Next separate the enum-only
non-reclamation proof from its invocation wrapper so reclaimable surfaces can
reuse the same exact conversion/initializer closure. Keep copied-String-only
retention mode separate: it rejects every input store and cannot serve root
retention. A later root query may omit ownership deltas only for proved enum
values in exact enum-typed fields; it must still attribute reclaimable inputs,
reject permanent enum holders capturing roots and preserve unknown effects.
Do not silently ignore enum stores into an erased field that can also retain a
reclaimable root, since that would miss clearing a prior incoming dependency.
Such mixed slot representations need an explicit proved delta or a producer
refusal. First extract/bind enum lifetime evidence and preserve existing enum
proof/native results; then integrate the distinct retention projection and
reclaimable constructor/destruction closure with focused paired cases.

The shared `BridgeEnumLifetime` now binds non-reclamation evidence for declared
enums and actual constant-specific receivers across every selected mixed entry
and conversion initializer. It grants no ordinary object, String, rollback or
retention permission. Enum-only invocation uses this same production proof.
`experiments/p3a-enum-lifetime.log` passes both focused proof selectors, including
all unfreed modes, source/class/archive parity, stale/subset refusal and unknown
constructor, constant-body and initializer effects. Native enum cases pass in
`experiments/p3a-enum-lifetime-native.log`; `p3a/enum-values/run-4429071842456116773`
has byte-identical LLVM to `run-17334740257665802338`, SHA-256
`ce0401a61632882d0e7eb740cf67d9ece6d651d719fb34f0137c44311f40356c`.
Strict compilation, license and diff checks pass. Continue with a separate
enum-aware root retention query, preserving ordinary and copied-String modes.

`7ed62377` records that extraction. The next semantic change adds a distinct
enum-value mode with bound enum-only lifetime evidence. Exact enum fields may
store enum/null values without root deltas; erased fields remain conservative.
The same analysis must retain root slots, helper/exceptional attribution and
reject captured roots, loaded-slot transfers, unknown effects and cycles. Keep
ordinary source safety and existing analysis modes unchanged. Focused checks:
new mixed enum/root positive and negative contracts in all modes and artifact
forms; existing retention attribution/rejection/cleanup, copied String, root
and enum invocation selectors. Then compose protected lowering/destruction and
run native mixed allocation/failure/initialization cases with O3 inspection.

The extra enum String-field getter control exposed an unknown ordinary return
origin. Preserve that conservative source fact. Reuse the existing non-fresh
String-origin query only for a proved permanent receiver, no copied String
arguments and a borrowing receiver contract. It excludes fresh/unknown producers;
the immediate protected copy needs no ownership of the returned storage. Test
enum-only and mixed root paths, mixed fresh/loaded refusals and existing permanent
String capture/unknown-origin cases. No copied input may be cleaned before its
result is delivered, and no enum-held heap storage gains an immortal classification.

### P3a checkpoint: mixed reclaimable roots and enum values

The root contract carries bound enum lifetime evidence and a distinct complete
analysis-root set. Enum conversion initializers are analyzed without becoming
exported entries. The separate enum retention mode exempts exact enum fields and
permanent-only publication, preserving all reclaimable input slots and rejecting
enum-held roots, erased fields requiring unproved replacement deltas, transfers,
unknown effects and source reclamation. Destruction revalidates the mixed proof;
String getters retain their ownership-specific cleanup. Protected entries reuse
the existing enum conversion and normal/exceptional slot snapshot lowering.

`experiments/p3a-root-enums-regressions.log` passes six existing attribution,
rejection, cleanup, copied-String, root and enum selectors. The final mixed proof
and permanent-String checks pass `experiments/p3a-root-enums-string-recheck.log`,
covering every unfreed mode and positive/negative source/class/archive parity.
`experiments/p3a-root-enums-string-native-final.log` also passes the expanded
enum-only proof (including mixed borrowed/copied rejection) and 26 native O0/O3
children in `p3a/root-enums/run-4168621742780977004`, LLVM SHA-256
`f0d4dbf4abac98fd57065eab9f0b7d9f459fdc2c5d9f67a3ee31c76687acf07b`.
Those children cover cold/null enum conversion, stored initializer failure before
entry/mutation, copy/root/owned-String/item allocation failure, source constructor
rollback, old-versus-new slot values on failures, explicit clears and exact
destruction. The enum String getter copies borrowed storage without granting
heap immortality. The private transport retains source exception snapshots as
specified; allocation assertions distinguish them from leaked temporaries.

O3 scalar and borrowed-String getters use seven and four instructions respectively,
without helper/TLS/registry calls. Warmed enum result and caption paths preserve
the necessary initialization guard and named public field loads, with uncommon
initialization/failure helpers outlined. The final fixture's 100,000 enum-result
and scalar-getter pairs measured 469,000 ns O0 / 140,000 ns O3, checksum 100,000,
zero allocations. These are diagnostic fixture timings, not P6 acceptance.
The unchanged root/String fixture passed in
`p3a/root-strings/run-15780461605777222255`, byte-identical to
`run-4821498381215534937`, SHA-256
`3e0640858d5ac1d0f43fa9d3c6b41a0867a01c193a1f81e0c4be41a766db565f`.
Strict compilation, license and diff checks pass. Public object generation still
rejects these unfinished adapter capabilities. P3a remains open for complete
admission classification, custom exception contracts and final generated/
specialized-root proof validation; P3b/P3c/P3d and later phases remain pending.

`9ab8f5e3` commits mixed reclaimable entries. Next extend the existing exception
projection with bound custom hierarchy/API metadata and exact snapshot getter
targets, including inherited/overridden accessors. Keep built-in projection
behavior unchanged and public generation closed while custom transport is
incomplete. Copyable data is scalar/String; unsupported members, borrowed native
object/array data, generic/unknown dispatch and unproved String ownership must
reject. Throwing/allocating getters remain protected typed calls, not a reason to
move extraction into C. Test checked/unchecked and abstract catch hierarchies,
getter override/primitive/String ownership, stale facts and unsupported shapes
with source/class/archive parity. Assemble protected getter IR and rerun the
existing built-in projection/entry tests. Native custom snapshot fallback and
generated Java classes remain separate required P3 work.

Custom exception signature/hierarchy projection now passes
`experiments/p3a-custom-exception-final.log` alongside both existing built-in
projection and protected-entry selectors. The bound inventory includes abstract
catch declarations, inherited/overridden exact targets and covariant cause type
closure. Scalar and copied String properties are classified independently;
unsupported parameters, arrays/objects, mutable public fields, inaccessible
ancestors, generic members, receiver publication, mixed fresh/borrowed String
results and unknown String producers are rejected in every unfreed mode, with
source/class/archive parity. Existing built-in entry points still reject custom
types, and incomplete Java/native custom transports explicitly reject rather
than silently omit data.

Protected getters, including throwing/allocating getters and every scalar width,
assemble and pass LLVM verification in
`p3a/custom-exception-proofs/run-601496356476743978`. Built-in entry LLVM in
`p2/exception-entries/run-12100085460808443832` is byte-identical to the pre-change
projection rebuilt against the current compiler in `run-12218136158244161182`,
SHA-256 `ae23806663cfd921700d2608d1135fede23822d862f76a93c19f6cb2a8f16a48`.
The older P2 artifact differs only in the previously fixed nullable owned-String
rollback; it is not evidence of a regression in this projection change.
Strict compilation, license and diff checks pass. Next exercise custom getter
values, allocation failure and throwing extraction in child JVMs, then integrate
the full getter closure into final object admission. Generated custom Java
snapshots/hierarchies and bounded transport still belong to the required P3b work.

`30eaaac7` commits custom snapshot declarations/getter proofs. The follow-up
private native extraction harness passes eight O0/O3 child JVMs in
`experiments/p3a-custom-getters-native.log`, evidence
`p3a/custom-getters/run-7455283831047416553`. It verifies boolean, signed byte/
short/int/long, unpaired UTF-16 char, float and negative-zero double transport,
borrowed messages, fresh String copy/delivery/release, allocation failure before
the producer object and during repeated getter extraction, plus a throwing
getter followed by successful scalar/native calls. Source exception allocations
remain explicitly counted; no native exception reclamation is invented.
Strict test compilation, license and diff checks pass. This is protected getter
evidence, not generated custom Java exception or jar qualification.

`05ddf4bd` commits custom native getter evidence. Next make the non-reclamation
query inspect generated typed entries. Review of the four `IrBridge*` operations,
their LLVM lowering, `ironwood_bridge_copy_string` and
`ironwood_bridge_snapshot_failure` establishes that they allocate/copy or write
adapter-local frame data without reclaiming producer objects. Classify only that
effect; do not grant retention, ownership, nonthrowing or unrestricted callback
permission. Test actual mixed generated entries before/after optimization with
all destruction roots included: enum storage remains non-reclaimable, generated
root destruction and temporary String reclamation must fail their respective
permanent classifications. Inject reachable deallocation and unknown effects to
verify refusal. Keep ordinary retention conservative and source fact binding
strict. This is one final-proof prerequisite, not complete specialized ownership
or rollback revalidation.

`experiments/p3a-generated-reclamation-final.log` passes the new generated-root
query and both existing non-reclamation selectors. In every unfreed mode, all
four fixed bridge operations are inspected in real mixed entries before/after
native optimization. Enum storage is proved non-reclaimable with generated
destruction included; generated owner/item destruction and temporary String
cleanup reject their respective permanent classifications. Injected reachable
enum deallocation rejects, and an unclassified clock effect remains unknown.
Ordinary retention still rejects these frame publications, and unchanged source
facts do not match synthesized programs. No generated instructions changed.
Strict compilation, license and diff checks pass. Next establish a checked
synthesis extension for unchanged source facts and structurally prove generated
constructor rollback before trying to retain those facts across specialization.

`42a7745b` commits fixed transport non-reclamation effects. The next change
preserves source semantic facts only across an exact additive synthesis made by
the existing private entry/getter builders. It checks all original functions,
layouts, dispatch, initialization and constants, the complete generated function
inventory and export set; it gives generated functions no source borrow/result
facts. Generated constructor rollback additionally requires a bound source
constructor, its confined construction proof, an immediate protected allocation
and constructor call, and a failure prefix that has not published the receiver.
Consumers are bridge non-reclamation and rollback analysis; ordinary semantic
analyses and IR lowering remain unchanged. Pair permanent construction acceptance
with generated destruction, changed source/generated bodies, foreign modules,
unknown cleanup and stale optimization refusal. Verify source/class/archive and
all unfreed modes, the existing construction isolation/reconstruction tests and
generated closure test. Optimization still requires separate revalidation; this
extension must not accept arbitrary transformed IR.

The first getter-closure check conservatively stopped on secondary-exception
count/index reads. Their typed LLVM calls and runtime bodies only read the
existing metadata chain. Add those two exact non-reclaiming effects; retain
ordinary retention conservatism and all unknown-operation refusal. This expands
the focused selection to the existing non-reclamation closure/unknown tests.

`experiments/p3a-generated-construction-final.log` passes all six selected tests.
Exact generated entry/getter synthesis preserves the unchanged source facts and
proves permanent constructor rollback unpublished across all unfreed modes and
source/class-directory/individual-class/archive inputs. Source/generated edits,
foreign/subset modules, repeated synthesis and optimized IR fail binding.
Generated methods receive no source semantic facts; generated root destruction
and String temporary cleanup still reject permanence. The existing construction
isolation/reconstruction and non-reclamation safe/unknown checks pass. Strict
compilation, license and diff checks pass. No lowering or runtime code changed.
Next revalidate final optimization without allowing arbitrary fact rebinding,
then combine complete getter/entry closure with object admission.

`59d16d95` commits checked synthesis and generated rollback. Next record exact
input/output and clone origins while executing the existing native-link passes.
Only that privately constructed transformation result may carry source facts to
the final program. Initialization specialization narrows guarded effects; enum
specialization substitutes proved immortal identities; forwarding preserves
exact values; pruning removes unreachable entities; unread-store elimination
removes only unobserved primitive writes. None grants a new ownership fact.
Preserve conservative source facts for unchanged callable identities and cloned
borrowing/construction facts, but leave cloned result origins unknown because
enum substitution changes parameter provenance. Revalidate actual final roots,
generated rollback structure and cleanup against the transformed program.
Arbitrary IR edits, foreign transformations and unrecorded clones must fail.
Existing native compilation uses the same passes with unchanged output. Select
the generated construction/closure tests, initialization and enum specialization
structure/safety, plus focused forwarding/pruning checks when affected. Inspect
byte-identical transformed IR before claiming no code-generation change; no new
runtime operation is authorized by this metadata work.

`experiments/p3a-native-provenance-final.log` passes all six selected checks,
including actual initialized/enum and constructor clones, conservative unknown
result provenance, generated destruction rejection, final permanent rollback,
artifact parity and existing optimizer structure/mandatory-safety checks.
Source clock effects remain unknown after optimization. Foreign input and edited
outputs cannot use these bound facts. The unchanged forwarding/pruning passes
need no additional selectors. Strict compilation, license and diff checks pass.

The independently compiled `59d16d95` versions of the three changed optimizer
classes produce byte-identical LLVM to current code for the existing enum
fixture and generated permanent constructor/getter closure. Baseline sources,
helper and both outputs are in
`experiments/native-provenance-59d16d95`. SHA-256: enum LLVM
`1d441239ff86112ca1975d602e267488748a6ec39fc6b446f368440c96de42d1`;
constructor/getter LLVM
`6dc31273b93d25956abd39a88dcf35d0f98fad922157a310c1f105e5fc1fd302`.
No native instruction or runtime behavior changed. Next bind permanent lifetime
contracts to the complete final emitted closure, including all exception getters.

`0ba250e1` commits recorded native-link provenance. Next close the custom
exception/getter fixed point using the existing snapshot projection and protected
entry builder, while leaving the public P2 builtin-only producer boundary in
place. A final non-reclamation result must derive every candidate type from the
entry module's existing permanent/enum proofs, include every generated entry,
destruction, getter and trace root, execute recorded optimization and rerun the
actual lifetime query. It must not certify a caller-selected favorable subset.
Pair safe custom getters with a getter that reaches deallocation of a candidate
type and a getter that introduces another unsupported custom exception. Check
all unfreed modes, artifact parity, final binding and existing builtin closure
behavior. This result proves only storage lifetime; it cannot replace complete
final root retention/destruction or enable unfinished Java adapters.

`experiments/p3a-final-lifetime-final.log` passes four focused checks. Final
permanent and enum inventories include the complete custom/builtin getter fixed
point, all generated native roots and recorded optimization. A custom getter's
candidate-type deallocation defeats permanence; another exception reached only
through a getter is discovered, and its unsupported data rejects admission.
Positive and negative source/class-directory/individual-class/archive checks
agree. The individual-class case explicitly supplies the separate dependency
class directory; its initial missing dependency was a fixture setup failure.
Existing builtin-only discovery still rejects custom types. Mixed root/enum
final proof certifies only enums, and enum-only conversion inventories remain
complete. Strict compilation, license and diff checks pass. Next qualify the
final proved program through the existing permanent String native harness, then
continue final root retention/destruction and complete P3a admission.

`b3842cb1` commits complete final non-reclamation. The permanent String native
harness now obtains its actual emitted program from that immutable final result,
records its complete native/exception roots and LLVM identity, and retains the
existing twelve O0/O3 child checks for publication, borrowed/fresh/aliased String
delivery, failed allocation/rollback, exact temporary cleanup and recovery.
This is matched payload qualification, not a new public producer capability.

`experiments/p3a-final-lifetime-native.log` passes all twelve child checks against
`p3a/permanent-strings/run-7355536308636886570`. Final LLVM SHA-256 is
`e42bd8ac6ab1ab5f223d506405043d504fd97d5d257f95f2e939eb1b4a472896`;
the evidence records the seven API entries, four protected getters, trace root,
six builtin exception types, image hashes and O0/O3 disassembly. O3 `ping` is
five instructions; `text` and `unknown` are six instructions each, with no helper
calls, TLS, registry, allocation or lifetime bookkeeping. String-input `length`
retains its required copy/deallocation and outlined exception handling. No hot
lowering changed and no new timing acceptance is claimed. License/diff checks
pass. Continue final reclaimable-root retention/destruction checks, with getters
included in invocation effects and generated destruction kept as an explicit
separate capability rather than excluded from permanent proofs.

`39a0eef1` commits matched final permanent native evidence. Next validate the
reclaimable protocol against the final program: re-run source-entry slot
attribution and require exact agreement with the slots used by generated frame
lowering; require getter/initializer queries to introduce no unreported slots;
prove normal generated entries and getters cannot reclaim root/view storage;
and re-prove descriptor destruction/rollback as separate nonthrowing,
allocation-free capabilities. Only explicit generated destruction roots may be
outside the normal-invocation query, and permanent/enum proofs must continue to
include them. Final enum permanence can authorize enum-value attribution only
for the entry module's exact proved enum types and roots within its checked
closure. Preserve ordinary unknown effects and reject every mismatched final
program, field/slot inventory or cleanup. Select positive root/String/enum/view
fixtures plus hidden getter deallocation/publication, unknown effects, source/
class/archive parity and the existing final closure checks. No adapter is
enabled before its complete lifetime protocol exists.

The first combined getter query stopped on indexed secondary-exception reads.
Extend retention analysis for the already-audited runtime count/index readers:
the returned reference has loaded-slot provenance, never freshness or an input
transfer permission. Add read/count acceptance and loaded transfer/publication
refusals to the existing secondary-exception regression, including all modes
and reconstruction, and include that selector in final-root verification.

The combined root/String/view/enum/custom-getter cases pass in all modes. The
new secondary read/count effect proofs passed, but the existing test then tried
to admit their reference signatures through scalar-only lowering. Keep those
new operations in effect verification and explicitly assert scalar transport
refuses them; the old scalar cases retain their full checks. Add a retained-slot
helper called from a specialized loop to check payload agreement across clones.

The explicit clone fixture exposed a diagnostic-only difference: both the
original and initialized helper sites appear in the final proved slot summary.
`experiments/final-root-slots/result.txt` records identical ordered holder/field,
value-input and clearability data, with the added cloned site. Compare exactly
those payload fields after proving all actual stores; diagnostic callable names
are not frame fields. Keep source spans and actual final sites in the analyzer.
Require a real initialized helper clone in the regression, then repeat safe and
getter-deallocation refusal through class-directory/individual-class/archive
reconstruction with and without public enum conversions.

`experiments/p3a-final-roots-final.log` passes all five selected checks. Final
root/String/view and root/enum protocols preserve exact slot payloads across
real initialized helper clones. Normal entry/getter closure preserves root/view
storage; final descriptor destruction/rollback passes its separate checks.
Getter deallocation, hidden publication and unknown effects refuse admission in
every unfreed mode, with safe/deallocation class and archive parity. Secondary
count/index reads pass their effect checks while loaded transfer/publication and
scalar-only reference transport remain refused. Existing root-cycle, slot-transfer,
unknown-effect and final permanent-closure regressions pass. Strict compilation,
license and diff checks pass. Next match the existing root/String and root/enum
native harness payloads to these exact final proof results.

`6f4a075e` commits final root protocol validation. Both existing root/String and
root/enum native harnesses now emit their immutable final root result, assert
its exact entry/program binding and record cleanup roots plus payload identity.
Retain every existing O0/O3 allocation, initialization, exceptional-delta,
rollback, destruction and copied-value check, along with the enum fixture's
diagnostic zero-allocation loop and disassembly. This changes the exercised final
closure, not the generated lifetime protocol or public producer admission.

`experiments/p3a-final-root-native.log` passes both selectors and all 48 O0/O3
child checks. Matched root/enum evidence is
`p3a/root-enums/run-16781321434001245307`, LLVM SHA-256
`99b6bfe5f5f546d9244a14a03be78584cd462a00d861b15618bbb4bf42a0dfbc`;
matched root/String evidence is `p3a/root-strings/run-11486365812305310010`,
LLVM SHA-256 `d813cb8a5d695482b768f8e13f67729d4efbaa9f348383eccd7f3910bf3ae054`.
Their final-root inventories include protected exception getters, trace and all
destruction entries. All 29 existing API entries have unchanged O3 instruction
mnemonic sequences against the preceding fixture payloads. Diagnostic timings
are raw observations, not acceptance: 100,000 enum/read pairs take 2,015,000 ns
at O0 and 488,000 ns at O3; 10,000 String operations take 1,621,000/1,680,000 ns;
100,000 borrowed reads take 556,000/128,000 ns. Allocation-count assertions pass.
These short runs vary from prior observations and establish no numerical
performance conclusion. License and diff checks pass.

`e88e60f2` commits matched native root evidence. Next close internal object
signature selection for exported custom exception snapshots and declared throws.
Reuse the existing snapshot inventory; exception constructors never become
native object entries, and snapshot inheritance does not admit facade inheritance.
Require exact exported-package closure for catch parents and getter result/throws
types. Preserve copied-only getter shapes and ordinary object-parameter refusal.
Seed complete exception extraction with exported snapshot declarations, including
types absent from reachable throws, so final lifetime checks see their getters.
Public value production remains unchanged until P3 snapshot adapters exist.
Select safe abstract/checked/nested hierarchy and negative missing-package,
unsupported getter, object parameter and hidden getter-reclamation cases, with
source/class/archive parity and existing final closure regressions.

`experiments/p3a-snapshot-surface-final.log` passes all five focused selectors.
Exported checked/abstract/nested snapshots and declared throws pass internal
signature selection; custom constructors never enter native object roots.
Missing declared/getter/catch packages and undeclared reachable custom exceptions
outside the export set fail. Exception object parameters and unsupported getter
shapes stay rejected. Both final lifetime validators include never-thrown exported
getters: a hidden Item deallocation blocks root and permanent admission in every
mode and across source, class-directory, individual-class and archive inputs.
Existing enum, final-root, custom snapshot and final permanent regressions pass.
Two fixture setup errors were corrected (missing Override and incomplete
individual-class input inventory); compiler loading/ownership behavior was not
changed to accommodate them. Strict compilation, license and diff checks pass.

`3aa32ebc` commits complete exported snapshot declaration closure. Next extend
root admission to a mixed set of independently proved permanent object values,
reusing the enum/permanent origin treatment and P0 non-reclamation analyzer.
Bind candidates to the exact input program and complete entry/initializer roots.
Keep root types, root slots and destruction separate; permanent holders capturing
reclaimable inputs still fail, as do unknown effects, slot transfers and cycles.
Final permanent proof must include every generated root destruction and custom
getter, so freeing a permanent candidate through another root is not exempted.
This adds an internal proved capability, not a fallback from failed root analysis
or public producer admission. Verify mixed root/permanent/String/enum positives,
permanent-to-root retention and reachable destruction negatives, stale/subset
proof refusal, all unfreed modes and artifact parity. Existing enum/root proof
consumers remain in the focused selection. Ordinary source safety and native
lowering algorithms stay unchanged.

`experiments/p3a-mixed-lifetime-final.log` passes all six focused selectors.
Mixed root/permanent objects pass with and without enums in all unfreed modes;
permanent self-retention needs no root state while Holder keeps its exact Item
slot. Stale/subset proofs, permanent holders retaining Item, slot transfer,
unknown effects and generated destruction of a permanent candidate fail. Safe
and unsafe cases preserve source/class-directory/individual-class/archive parity.
Strict compilation, license and diff checks pass.

The matched native payload is `p3a/mixed-lifetime/run-12656671066873094957`,
LLVM SHA-256 `2a5bd7c4934dc6e0a3b05c08f7c23ce249f711773901c224bddf53edecc9e299`.
All 18 O0/O3 children pass for unlimited and 0-7 allocation budgets, including
unpublished permanent/root construction cleanup, cold enum use after OOM,
store-then-throw final slots and surviving permanent identity after root cleanup.
At O3, Catalog connect/remember/text, Item number and Holder catalog/text entries
are four instructions each, with no helper call or lifetime bookkeeping. The
100,000 catalog/text pair loop reports checksum 100,000 and zero new allocations:
1,439,000 ns at O0 and 590,000 ns at O3, diagnostic observations only. Existing
root/enum and root/String native regressions pass another 48 children, with LLVM
byte identities unchanged from their preceding final-proof payloads. Their new
evidence directories are `p3a/root-enums/run-8658849823953995553` and
`p3a/root-strings/run-6410579910893643269`. No Java facade admission is enabled.

`602b38bb` commits mixed lifetime composition. Next connect automatic object
admission to these contracts. Identify permanent candidates from explicit
unknown reference-result origins or receiver publication into static/array or
non-input storage, using the existing retention dataflow. Propagate permanent
ownership through proved dependent views. These are reasons to request a full
D192 proof, not lifetime permissions; unknown effects still fail that proof.
Do not classify explicit root arguments as permanent merely because a holder
captures them, or turn failed cycles, slot transfers or cleanup into permanent
fallbacks. Root, mixed and uniform permanent admission must all finish against
the exact selected API, exported snapshots and final generated closure. Retain
the public producer's incomplete-capability boundary until adapters exist.
Verify automatic selection for ordinary roots, mixed values and the actual
OrderBook closure, paired with the existing unsafe fixtures and reconstruction.

`experiments/p3a-object-admission-final2.log` passes all four focused selectors.
Automatic admission preserves ordinary root destruction, selects only Catalog
in the mixed fixture and follows a permanent owner's proved dependent view.
Root cycles, mixed fresh/alias returns, explicit reclaimable input capture,
slot transfers, source deallocation and unknown effects remain refused; the
unknown effect retains UNKNOWN status. Source/class/archive checks and exact
artifact binding pass. The complete current OrderBook selects permanent Order
and OrderBook storage with real origin/publication reasons, and final PriceLevel,
Order/PriceLevel array and primitive array non-reclamation proofs pass as well.
P0's actual rollback and reconstruction tests and the public value-producer
rejection boundary pass. The initial primary invocation used one mistyped test
name and ran no tests; the corrected exact selection passed. License and diff
checks pass.

`p3a/object-admission/run-14496284883336346754` records final OrderBook admission
reasons, exported roots, compiler-jar identity and LLVM payload identity. LLVM
assembly and verification pass. This is final compiler/LLVM proof evidence,
not execution, Java facade, workload or performance qualification.

`f5358b62` commits automatic concrete-object admission. Complete the same internal
admission route for enum-only and scalar/String APIs exporting custom snapshots.
An explicitly proved permanent-value module may have no object references; an
empty lifetime inventory grants no object or destruction permission. Preserve
all copied-String confinement, snapshot/getter and exact generated-root checks.
Verify enum constant-specific bodies, declared checked snapshots and rejected
String publication/unknown result ownership. Public generation remains gated
until Java/C adapters support the selected capability.

`experiments/p3a-value-admission-final.log` passes both focused selectors. Enum
constant-specific bodies and scalar APIs declaring custom checked snapshots pass
automatic admission and reconstruction; a snapshot-only value module has no
object lifetime state or destruction capability. String input publication and
unknown String-result ownership remain refused. Existing final custom getter
deallocation/unknown-effect cases pass. License and diff checks pass. Final
OrderBook LLVM is unchanged (`53a869fd7969d5409d340c3ffb3b49ba884084ce4c096a36fabde872ed9d3af6`);
the current matched compiler identity is recorded in
`p3a/object-admission/run-8186120906615387038/admission.txt`.

The P3a compiler-admission gate is recorded in `JAVA_BRIDGE_P3A_EVIDENCE.md`.
Proceed to P3b with these immutable contracts; every still-incomplete adapter or
unproved ownership shape remains rejected. This is not completion of P3 or
permission to claim object facades, Java lifetime state, OrderBook workload
acceptance or release readiness.

`1caa7927` records the P3a gate. P3b first extends target-independent generation
identity to consume the exact immutable object admission. Preserve the existing
P2 value identity and keep its generators unable to consume object identities.
Cover declared enum order, custom snapshot fields and generated lifetime roles,
including a root-to-permanent change that removes generated destruction. Source
paths, diagnostic reasons and target-specific payload bytes must not enter the
shared identity. This changes identity consumers, not ownership analysis or hot
native lowering. Focused checks pair exact admitted inputs and reconstructed
artifacts with stale proofs and incompatible value-generation consumers; also
rerun the existing generation-identity checks. Host adapters remain incomplete
and public object production stays rejected.

`experiments/p3b-object-generation-final.log` passes both the existing identity
test and the new object identity test. Exact final admission, stale-proof refusal,
root/permanent API distinction, enum order, custom getter/constant changes and
inherited InterruptedIOException fields pass. Source relocation and class/archive
reconstruction preserve identity. The first run exposed inherited native
Throwable trace methods and generic enum metadata in the raw API inventory;
identity now follows the admitted snapshot/enum projection without broadening
transport signatures. P2 generators reject object identities. Strict Java 21
compilation, license audit and diff checks pass; no native lowering changed.

`f5bf02f3` commits admitted object identities. Next generate the permanent
world's weak-value cache with primitive address keys and no boxed lookup keys.
Only object conversion uses it; scalar paths and native ownership proofs do not
change. A live hit must allocate zero Java bytes. A miss has one weak entry plus
the facade, with a bucket array only on growth. Queue cleanup and replacement
must remove the exact old entry, so delayed delivery cannot evict a recreated
facade. Verify generated Java 21 code, live identity, growth, observed collection,
repeated recreation and allocation counters in child JVMs. Run deterministic
entry/growth allocation-failure injection only in separately labeled test copies;
failure must preserve existing entries and allow retry. This component does not
qualify JNI conversion, jar integration, or P4 allocation acceptance yet.

`experiments/p3b-permanent-cache-final.log` passes the generated-cache selector.
The unmodified cache child records 500,000 hits with zero Java allocated bytes;
1,000 misses use 40,000 bytes, exactly the independently measured matching weak
entry layout (40 bytes per entry), with available bucket capacity. Eight observed
collection/recreation cycles, growth, duplicate live identity and delayed old
queue delivery pass. A separate injected child verifies entry/growth failure,
unchanged existing entries and successful retry. Stale admission and a purely
reclaimable surface cannot generate this permanent cache. All generated code
compiles strictly for Java 21; license and diff checks pass.

Matched component evidence: `p3b/permanent-cache/run-8969397217318706137`;
generated source SHA-256
`6c7adff2986c85dc1a1d2e5503f2511f98332211a4e4c93f20393ada95f998df`,
Temurin `21.0.12.1+1-LTS`, child `-Xmx64m -XX:-DoEscapeAnalysis`.
The measurements exclude facade creation, which the host adapter must perform,
and are not P4 workload or final performance acceptance. Next connect permanent
facade declarations, private handle/constructor metadata and JNI conversion to
this cache with complete bootstrap binding validation.

`e678e356` commits the permanent-cache component. Next generate concrete permanent
Java declarations from exact final admission, with private final address/type
metadata, private raw conversion constructors, and a separate private native
cache-registration helper after public constructor metadata initialization.
Inherited identity operations remain Java-only; source hash/text overrides retain
native dispatch. Reuse the existing source-entry binding record, and distinguish
host registration helpers from typed native source entries in loader/manifest
inventories. The value bootstrap must reject such helpers until the object
bootstrap validates them. Verify javac/reflection signatures, nested/source-name
collisions, immutable metadata and identity behavior with a test-only loader stub;
pair safe permanent generation with stale, reclaimable, enum and custom-snapshot
refusals. This checkpoint does not enable the public producer or qualify JNI.

`experiments/p3b-permanent-java-final.log` passes five focused selectors: permanent
declarations, existing Java value declarations, loader metadata/extraction,
generated native bootstrap and public producer artifact/failure preservation.
`experiments/p3b-permanent-java-manifest.log` adds and passes explicit distinct
host-helper manifest assertions. The declaration evidence is
`p3b/permanent-java/run-4330286631741303323`; paired C runtime hash extraction at
O3 agrees for zero, aligned, mixed-bit, signed-edge and all-one address bits.
Nested final facades, private immutable fields/raw constructors, overloads,
checked declarations, null constructor-overload resolution and source-name
collisions compile strictly. Equality, hash collection removal and asynchronous
text/equality use Java only; inherited text never invokes a native source hash
override. Native bindings remain private, with no generated permanent free.
Root/enum/custom-snapshot and stale-proof refusals pass. License and diff checks
pass. P2 bootstrap and producer evidence is under `p2/bootstrap/run-1421517417894327628`
and `p2/producer/run-947818486766011865`. Public object production remains gated.

`d0d9f0f1` commits permanent declarations and private host-binding metadata.
Next generate permanent JNI adapters and a complete object bootstrap around
the exact final admission program. Reuse the existing protected builtin snapshot
transport. Bootstrap preflights every class/helper and anchors facade classes
and the weak cache; resolve facade field/constructor IDs lazily after the world
is ready, avoiding facade initialization reentry during registration. Scalar
receiver calls pass the Java private final address directly and gain no cache
lookup, field access or lifetime state. Object arguments use validated class
field metadata; object results use the weak cache and private conversion
constructor. Public constructors register only after immutable fields are set.
All source-native initialization, allocation and raising work remains in proved
typed entries. Java/JNI failures must release acquired String buffers and owned
String results. Tests must use the final admitted LLVM payload, O0/O3 generated
jars, primitive/object/String/exception calls, weak recreation, cold class use,
allocation failures, native/Java counters and scalar disassembly. Keep roots,
enums/custom snapshots and public producer integration gated until complete.

`experiments/p3b-permanent-facades-final.log` passes permanent declarations,
generated permanent jars and the existing value bootstrap. Eight permanent-jar
children at O0/O3 pass normal identity/String/exception/cold-class/GC recreation
and native allocation budgets 0/1/2, including constructor/String rollback and
post-failure continuation. Tampered host-helper metadata is rejected. The first
fixture's `new String(label)` made native owned-field cleanup uncertain; the
compiler correctly refused it. The fixture now uses the already-proved copied
String input form while retaining borrowed owned-label coverage. No ownership
analysis or reclamation permission changed.

Matched evidence: `p3b/permanent-facades/run-17248281164719719299`;
LLVM `a69e16382dcb40942d585243723a6ece81a08a6f672c20f57d73ddd052eee546`,
adapter source `366188adcb7daf143d6dd5e2d4cdd20e5763ac21297e5157fa76da12dabf3098`.
Compiler/runtime identities, final roots and signed payload hashes are adjacent.
Both 100,000-call scalar and live-object loops report zero Java bytes and zero
native allocations. Raw scalar timings: O0 1,709,792 ns, O3 1,513,667 ns; object
hit timings: O0 17,235,583 ns, O3 17,952,292 ns. These are diagnostic component
measurements, not final numerical acceptance. O3 Box.number's typed entry is four
instructions; JNI uses a 320-byte frame, its typed call/status branch and outlined
failure path, without cache, JNI field access, TLS, allocation or lifetime state.
License, strict C/Java compilation, codesign and diff checks pass. Next add host
allocation/delivery and collision failures through these generated jars, then
complete enums/custom snapshots and producer integration. P3b remains open.

`201505a3` commits permanent JNI conversion and generated bootstrap. Next exercise
host failures in separately labeled injected copies of the generated jars:
private facade allocation, actual weak-entry insertion, fresh/borrowed String
delivery, second String-buffer acquisition, field-metadata failure after buffer
acquisition, and cache-class global-reference failure during bootstrap. Existing
permanent storage must survive delivery failure and reuse on retry; no target
effects may occur after failed preparation, acquired buffers must balance, owned
results must be released, and pre-binding global references must be disposed.
Use child JVMs at O0/O3 with JNI checking and exact injected-input/payload hashes.
Share only test jar assembly with the unmodified baseline and rerun that baseline
after the helper extraction. No production fault hook or proof relaxation.

`experiments/p3b-permanent-host-failures-final.log` passes the unmodified baseline
and injected host-failure selectors: eight baseline children and fourteen fault
children at O0/O3. Facade/cache failures preserve native child storage and retry
without native reallocation. Fresh-result failure releases the owned native
String; borrowed-result failure preserves its owner. Second-buffer and field-ID
failures balance acquisitions/releases, run no target code and allocate no native
objects. Failed bootstrap leaves zero native allocations and zero outstanding
tracked JNI global references. JNI checking emits no warnings. No production
code changed in this checkpoint; shared test assembly also records jar hashes.

Fault evidence: `p3b/permanent-host-failures/run-1231220470280440748`, final LLVM
`3a97750cb4356058ff07f3cc9a773a31917446c60a6830ec3fac8cb29070c6cb`.
The original and injected adapters, Java cache, compiler/runtime and payload/jar
identities are recorded separately. The unmodified baseline is
`p3b/permanent-facades/run-6864730718634381492`, with unchanged LLVM/adapter
identities from the previous checkpoint. License, strict compilation, codesign
and diff checks pass. Continue with object collision checks and enum/custom
snapshot projection, then public producer integration; P3b is not complete.

`fe918659` commits the host-failure checkpoint. The next focused selector uses
four independently proved permanent-object artifacts to repeat D193 collision
orders and disjoint success through generated jars at O0/O3. It checks existing
facade/native identity after refusal, no losing extraction, mixed generation,
the private constructor-registration signature, and loader anchoring after GC.
Source/LLVM/adapter/compiler/runtime and signed payload/jar identities remain
recorded per artifact. This adds test coverage only; the public producer remains
gated, and P3c/P3d will extend the combined safety checks later.

`experiments/p3b-permanent-loaders-1.log` passes the focused selector with 24
checked-JNI children at O0/O3. Both class-path and first-use orders reject
duplicate classes and package-only overlap before losing extraction. The already
usable facade preserves scalar results, self/recall identity and legal nullable
same-world arguments. Disjoint artifacts work. Mixed class identity and an
altered private constructor-registration carrier fail preflight before extraction
while the disjoint artifact continues. Repeated bootstrap remains idempotent;
GC preserves the anchored loader and a second independent loader is refused.

Evidence: `p3b/permanent-loaders/run-7952143850616138321`. Artifact A generation
`df3adc82f4ebb6bca9dbd498fd06593e79eb95c485c3f352b9f41713f6d55036`, final LLVM
`6d9d75ebc5a0ded4ff664ffbc04fdef17fa7beb400256924b505a829de9f7009`.
All four artifact proofs, sources, generated adapters/classes, signed images,
jar hashes and individual child logs are retained. Strict C/Java compilation,
codesign, license and diff checks pass. These are internal generated-jar tests;
public producer admission and the remaining P3b projections are still pending.

`4d7c0716` commits object loader coverage. Next integrate Java enum declarations
with the exact P0/P3a named-token and constant-dispatch proofs. Java enum metadata
and inherited identity remain Java-only; native calls use protected typed entries
for initialization-before-conversion. Cover declaration order differing from
tokens, constant-specific bodies, empty/nested enums, nullable inputs/results,
mixed permanent objects, initialization failure and String cleanup. Reuse the
existing final admission and keep incomplete native/public capabilities rejected.

The first Java enum compilation reproduces an empty-enum metadata bug: substituted
`Enum.compareTo(Empty)` has no exact native target, so the empty dispatch failed
to classify it as Java-only and generated an illegal override of Java's final
method. Correct only this resolved inherited signature in `BridgeEnumDispatch`.
Consumers are export selection, API identity and Java enum declarations; no
ownership or effect facts change. Verify empty/nonempty inherited methods,
constant-specific source overrides, stale metadata refusal and object identity
parity with the enum API, object generation and Java declaration selectors.

`experiments/p3b-enum-java-final.log` passes five focused selectors: enum inventory,
object generation/source-class-archive identity, permanent declarations, the
O0/O3 permanent native baseline and new generated enum declarations. Evidence
`p3b/enum-java/run-5483893865523202703` compiles the complete inventoried Java
class set without anonymous dispatch helpers. Declaration order, private final
name-paired tokens, empty/nested enums, values-copy behavior, EnumSet, inherited
identity and partial `toString` overrides pass. A test-only bootstrap stub proves
Java-only access performs no bootstrap and native methods select distinct exact
entries; native bodies are deliberately unbound in this component check.
Native enum generation explicitly refuses until conversion is implemented.
Permanent JNI baseline: `p3b/permanent-facades/run-14172721419244686214`.
Strict compilation, license and diff checks pass. Continue with paired native
enum conversion and child-JVM cold initialization/failure evidence.

`b05a21c9` commits Java enum declarations. JNI conversion now validates the exact
generated enum metadata against final admission and typed conversion mappings.
Bootstrap anchors the preflighted enum classes without initializing them. Lazy
field metadata reads immutable Java tokens; typed entries alone initialize native
enums, load named fields and encode result tokens. Java result conversion resolves
the paired singleton by name. Empty enums need no cache or concrete facade state.
Source constant-specific dispatch still binds only its proved target entries.

`experiments/p3b-enum-native-final.log` passes four focused selectors: Java enum
declarations, permanent JNI baseline, all fourteen permanent host-failure children
and sixteen enum children at O0/O3. Pure enums and mixed objects cover cold
receiver/argument conversion, nullable and empty values, exact constant overrides,
String copying, permanent object identity and enum constructor arguments/results.
Failed native enum initialization runs once and prevents target effects on repeat;
String allocation refusal also prevents effects and leaves no native allocations.
Real pinned Java 24 permits Java-only enum initialization on another thread and
refuses native use before any extraction. The initial test incorrectly tried to
simulate Java 24 using a system property; the production Runtime.version guard
correctly ignored that, and the test now uses the real pinned launcher.

Evidence: `p3b/enum-facades/run-15098101126293455454`; mixed final LLVM
`d84fab732f166cdab72b5207ede5993bcde7bd78942cd0ba5f5d1d3544289301`, adapters
`be0fda5e8999c8599153e680d75068dacda5dcf4c5ba2e6cd0ed14dc6ff0974a`.
Pure-enum LLVM `9343c546272d7af9ec3a0300f1ee2dd5b7b51799cb03978bc9cf99458ab61f87`.
Each artifact retains compiler/runtime, generation, payload and jar identities.
The 100,000 paired enum-scalar/object-enum operations allocate zero Java/native
objects, taking O0 22,344,375 ns and O3 23,897,541 ns in this diagnostic run; this
is not numerical acceptance. O3 inspection of `iw_permanent_11` shows the protected
typed call/status branch with outlined failure and no JNI/cache/lifetime work on
the successful receiver path. Its `ironwood_bridge_entry_15` keeps the required
native initialization-state check and public constant-field load, with no TLS,
allocation or runtime helper on the initialized valid path. Strict compilation,
codesign, license and diff checks pass. Next cover enum host metadata/delivery
failures, then custom snapshot projection and public integration. P3b stays open.

`5f5b4364` commits enum JNI conversion. The next test-only checkpoint injects
second enum-class global-reference failure, token-field lookup/read failures
after String acquisition, and result constant-field lookup/read failures after
source execution. `experiments/p3b-enum-host-failures-final.log` passes all ten
O0/O3 checked-JNI children. Preparation failures run no target code, allocate no
native objects and balance acquired String buffers. Delivery failures preserve
the completed target count, release copied native inputs, and permit correct
retry. Failed bootstrap releases all tracked JNI global references and runs no
native initialization or allocation. No production code changed.

Evidence: `p3b/enum-host-failures/run-13801104487880174287`, final LLVM
`52cc3133e2cd91c9bf8f74b3272e24983f2c016b574f32d1ca58af34c3269976`.
Original and injected adapters, compiler/runtime, source, signed payload and jar
identities are retained separately. Strict compilation, codesign, license and
diff checks pass. Continue with custom exception Java declarations, hierarchy,
copied getter data and protected native transport, preserving bounded graph and
fallback contracts. Public object production remains gated until complete.

`1338316e` commits enum host-failure coverage. Custom snapshot implementation now
starts with a deterministic scalar/String layout bound to the existing exact
projection. Inherited and overridden getters must select the same slot by name
and copied type; all primitive bits, including floating-point payloads, remain
lossless. Message and cause/secondary graph roles stay separate. No new native
getter invocation permission, ownership exemption or public producer capability
comes from layout metadata. Select custom proof, layout/parity and later Java
snapshot/transport tests; keep the built-in graph ABI unchanged unless a custom
projection needs its additional copied arrays. Covariant graph getters require
representation checks before exposure, never a delayed cast failure in a getter.

`experiments/p3b-custom-layout-final.log` passes the existing custom proof selector
(all unfreed modes, paired unsupported/effect cases and reconstruction) and the
new layout selector. The latter covers every primitive/String carrier, inherited
and overridden slot identity, abstract catch declarations without instance
extractors, indirect built-in catch ancestry, inherited transfer-count fields,
stale projection refusal and unchanged layout after body-only edits. Source,
class-directory and archive layout values are identical. Evidence:
`p3b/custom-layout/run-9507378632885102812`; companion proof evidence
`p3a/custom-exception-proofs/run-9966270557878104175`. Strict compilation, license
and diff checks pass. This metadata component grants no extra invocation or
lifetime permission and does not yet admit custom Java/native transport.

Next implement generated snapshot classes and their Java-only data/factory path,
then extend cold native extraction using the same layout and existing protected
getter entries. Preserve each getter's single captured value and owned-String
release, including values reused by built-in constructors. Keep the ordinary P2
factory ABI unchanged for projections without custom types. Test custom checked/
unchecked and abstract hierarchies, all primitive bits/UTF-16, ordinary/covariant
graph getters, constructor-specific built-in ancestors, bounded graph failures,
module access and extraction/host allocation failures before public integration.
