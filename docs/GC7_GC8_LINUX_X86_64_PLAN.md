<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native Linux x86-64 GC7 and GC8 follow-up plan

2026-11-06, recorded before implementation or measurements. The maintainer
authorizes SSH work inside `~/temp/Ironwood-GC/` on `x86host`, adding physical
Linux x86-64 evidence to GC8 and checking GC7 there. Continue the local migration
branch from `04f09a36`; keep commits local. Do not begin GC9/GC10 or self-hosting.

## Inputs and preservation

Both canonical local and remote checkout roots/origin URLs were verified. The
remote checkout is an old GC1 checkpoint with two local script edits. Preserve
its files, Git state, archives and SDKs. Export a hash-recorded current source
snapshot into a new ignored subdirectory of that checkout. It is a source staging
directory, not a new branch or Git worktree. Build compiler/stdlib artifacts and
the current collector SDK from those sources, reusing only verified pinned
dependency archives, LLVM 23.1.0/sysroot inputs and native bridge support.
Record snapshot/overlay hashes explicitly; an inherited parent Git revision is
not the staged source identity. No old compiler/runtime binaries qualify current
behavior. No remote checkout update, push or origin/main integration is included.

The physical host reports an Intel Xeon E-2288G, eight cores/sixteen threads,
about 16 GiB RAM, no virtualization, and approximately 98 GiB free disk. Keep a
30 GiB free-space floor and a bounded staging/output budget. Do not delete or
rewrite existing unrelated evidence. Retain the 16 GiB summed process-group RSS
guard, nominal 50 ms sampling plus ps overhead and 60-second child deadlines.
Reduce workload size after a diagnosed timeout; never increase those limits.

## Contracts and focused implementation

Port qualification tooling only: accept the already supported Linux x86-64
target, recognize ELF caller symbols, select .so images, and collect Linux
process memory/native allocation/descriptor observations. Preserve the existing
macOS path. Linux RSS/peak/PSS and glibc allocator observations have explicit
units and scopes; PSS is not called an exact per-process charged-memory measure.
Observations remain cold and separate from primary timing. Add no production
write/allocation ledger, polling, automatic collection, finalizer or root bypass.

Preserve BDWGC 8.2.12/USE_MARK_BYTES=1, ordinary roots 2/owners 1 and managed
schema 2/objects-gc-v1, roots 3/owners 2. Keep exact layouts, nonmoving statepoints,
fail-closed verification, conservative unknown effects, explicit JNI/resource
cleanup, suspended roots, image isolation and native/pool provenance. D051 and
D132/D133 remain mandatory. Linux arm64 and arm64e gates stay closed.

The pre-change regression review applies to observer/tool consumers, not a new
shared compiler analysis. Pair supported ELF symbols with changed-instruction
controls and accepted disk/input identities with nearby refusals. If production
changes become necessary, first map their actual shared consumers and add paired
accepted/rejected focused regressions before implementation.

## GC7 verification selection

Build the current Java 21 bootstrap and test classes. Run exact selections for
removed forms/ordinary identifiers, shared graphs/results, partial publication
versus final initialization, copied/borrowed input confinement, collecting
snapshot getters versus publication, source/class/archive parity, same/wrong-pool
origins, structural retirement/bounded compilation and stale bridge metadata.
Retain focused GC2-GC6 layout/effect/owner and bridge refusal controls.

Run the GC7 shared-graph, retained file visitor and constructor-dispatch fixtures
through native compilation, including O0/O3 shared graphs. Run GC5 integrated,
pool, resource, OOM and no-automatic-collection controls. Use actual checked-JNI
GC6 objects/critical fallback, callbacks/batch fallback, carriers/suspended reentry
and private-image controls on the current source/SDK. This is a focused consumer
and contract selection, never an unfiltered compiler/native suite.

## GC8 qualification selection and acceptance

Use the same deterministic 4,096-node reference/index/spaced/rare kernels,
one allocation per 4,096 operations, call/pool defer/finally and maintained
eight-operation OrderBook cycle. Six independent processes per label, alternating
order, seven batches where supported, warmup, independent checksums and exact
allocation counts remain. Keep cold setup, explicit collection and later reuse
separate. Preserve A/A controls, process-level distributions/uncertainty and
timer-resolution evidence. Do not compare absolute timings across different CPUs
as a migration regression percentage.

Inspect actual linked O3 ELF callers/inlined loops, including ironwood_run,
ordinary/reference/pool/defer operations and bridge scalar/callback boundaries.
Preserve hashes and normalization rules without removing changed instructions.
Measure production critical calls, forced JNI and supported callbacks/collecting
reentry separately. Explain layout/locality and required boundary work separately
from ordinary collector maintenance; investigate meaningful differences.

Run three resident processes at each existing graph size: 4,096 nodes/scale 2
and 16,384/scale 8, with 14 replacements, intentional obsolete retention and
physical clearing. Verify exact graph allocation/survivor counts, fully touched
payload checksums, no implicit collection, complete Memory.collect durations,
heap/free storage reuse and native resource balances. For the native-only
reporter control, use 200 warmup phases plus three 100-phase blocks initially,
under the same deadline. Extend only to diagnose an unresolved stabilization
question. Record JVM/allocator warmup and method limitations explicitly.

Accept this extension only after focused GC7 contracts pass and GC8 has explained
hot paths, stable repeated graph/reuse behavior and native resource balances.
Keep the macOS record intact, add a separate Linux report/compact manifest, update
current status pointers and commit locally after diff/license/tracked-file checks.
Any unmet criterion remains an explicit limitation or blocker.

## Pre-change review extension: exact SDK build profiles

Python verification rebuilt/validated native-host configuration `97dad8a3...`,
but emission correctly refused it against the existing `27c2bc0d...` qualified
profile. The source/runtime inputs are identical; the glibc 2.17 installation
receipt digest differs (`f1dcba2c...` versus `82573c10...`). Preserve the existing
profile and add one named native-host configuration/receipt pair. Select only
an exact listed configuration with its own exact system-input digest. Unknown,
missing, mixed, stale source/payload and unsupported-target inputs still refuse.

Affected consumers are cold GcDependency discovery, ordinary native links,
managed image/producer identities and loader metadata. Runtime layouts/effects,
hot lowering, SDK recipe/engine payloads and all memory ABIs remain unchanged.
Focused pairs cover old/new profile acceptance, unknown/missing configuration,
missing/mismatched system-input binding and unqualified target refusal. Execute
actual native and bridge links plus existing stale native-input/metadata controls.
This admits an explicitly measured build profile, not arbitrary environment
configuration or a new supported target. Native qualification must pass before
the profile is reported as accepted.

## Timing-control refinement

Initial Linux defer/finally medians varied despite identical actual O3 callers.
The existing defer control uses constant managed argv[0] and one executable path;
the initial GC8 driver did not. Preserve the original observations, add explicit
executable selection to the guarded runner (same process group/RSS/deadline),
and hold argv[0] constant for native timing. Keep mode arguments in the same
allocation-size bucket. Rerun the primary timing window after this correction.
Use the established same-path/identical-binary control for call/pool with six
alternating rounds and longer 268,435,456-operation batches. This diagnoses
startup/layout and short-batch host variability without changing runtime or
lowering. Batches remain process samples, never operation-tail measurements.

The Java 23 metadata positive control loaded correctly but emitted its native
access warning because the current objects jar enables critical bindings. Pass
the standard `--enable-native-access=ALL-UNNAMED` launch option, as the production
critical-call consumers already do. Retain strict empty-stderr checking and all
nearby missing/stale metadata refusals; rerun that failed matrix in a fresh output
directory. This is a qualification launch correction, not a loader exemption.
