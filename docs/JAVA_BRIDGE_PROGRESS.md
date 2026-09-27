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

P0a preparation/shared model implemented and focused checks passed. P0b is next;
no bridge runtime capability or complete P0 case has passed yet.

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

The shared model now provides resolved callable identities, exact-width native
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

Evidence: `workspace/java-bridge/evidence/p0a/` contains archive URLs/hashes,
resolved JDK paths/full settings, and complete image inspections. Linux images:

- ARM64: `ironwood-bridge-linux-arm64:05d5199baf46c922`, manifest
  `sha256:a03b0d3a764744079949a3adf5ecf6fe7ce82382ee35ca95360a38d453af8767`.
- x86-64: `ironwood-bridge-linux-x86_64:1a18fe26577fb8c5`, manifest
  `sha256:94b22aef9c134f490463540c7e174a07b3c4bcf028133247078898cc530a47bd`.

Large generated evidence lives under ignored `workspace/java-bridge/`; concise
outcomes and identities go here. Never count absent/skipped evidence as a pass.

## Next steps and pending qualification

1. Implement P0b's reusable retention/non-reclamation proofs and internal
   analysis-only reporting; connect shared root identities and preserve all
   ordinary diagnostics across unfreed modes.
2. Build the proof-authorized typed-entry/shared-image fixtures and collect
   the required O0/O3 platform/adapter evidence.
3. Complete every required P0 ARM64 and translated/static x86-64 case before P1.
4. Carry evidence and shared modules through successive production phases.
5. Prepare final x86-64 hardware runner and evidence bundle at P6b. Hardware
   address/access is not supplied; do not assume SSH or paid infrastructure.

All implementation beyond preparation, ARM64 qualification, x86-64 hardware
qualification and final numerical review remain pending. Release readiness is
not established.
