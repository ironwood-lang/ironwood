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

Scalar-entry checkpoint: compiler-owned entry CFGs and a bounded result-frame
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

1. Implement P0b's reusable retention/non-reclamation proofs and internal
   analysis-only reporting; connect shared root identities and preserve all
   ordinary diagnostics across unfreed modes.
2. Build the proof-authorized typed-entry/shared-image fixtures and collect
   the required O0/O3 platform/adapter evidence.
3. Complete every required P0 ARM64 and translated/static x86-64 case before P1.
4. Carry evidence and shared modules through successive production phases.
5. Prepare final x86-64 hardware runner and evidence bundle at P6b. Hardware
   address/access is not supplied; do not assume SSH or paid infrastructure.

P0 gates beyond the compiler/scalar evidence above, all later implementation
phases, final ARM64 qualification, x86-64 hardware qualification and numerical
review remain pending. Release readiness is not established.
