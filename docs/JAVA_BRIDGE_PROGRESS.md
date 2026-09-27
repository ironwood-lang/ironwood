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

P0a preparation/shared model implemented and focused checks passed. P0b is in
progress, with initial reusable retention-store attribution verified. No bridge
runtime capability or complete P0 case has passed yet.

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
