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

P0a/P0b/P0c and P1 pass for continued implementation under D213. The
[P0 evidence audit](JAVA_BRIDGE_P0_EVIDENCE.md) maps all ten cases to their proofs,
matched runtime/static evidence and production handoff. Real x86-64 hardware
stack qualification remains pending. P1's production multi-root native library,
D202 dependency and D210 signature gates pass. Next is P2's first generated
plug-and-play macOS jar, including D209. P2-P4/P6 and release readiness are not
complete.

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
