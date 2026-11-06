<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC8 experiment plan and pre-change review

Recorded before measurements, 2026-11-06, from GC7 `9d2edc20` on the existing
local migration branch. No push, remote integration, hosted run, self-hosting or
GC9/GC10 work is authorized. Read with [the integration plan](BDWGC_INTEGRATION_PLAN.md).

## Contracts and implementation scope

Add original measurement fixtures, a bounded driver and a qualification-only
generated JNI observer. Use the normal Java 21 typed frontend, LLVM 23 O3,
verified current GC7 SDK and production runtime. Do not alter compiler analysis,
allocation policy or runtime hot paths. Preserve BDWGC 8.2.12/USE_MARK_BYTES=1,
ordinary roots 2/owners 1 and managed bridge schema 2/objects-gc-v1,
roots 3/owners 2. Preserve D051, D132/D133, fail-closed maps, explicit closure,
conservative effects and unsupported-target/foreign-lifetime refusals.

The observer reads existing adapter snapshots only at phase boundaries. JNI
acquisition counters are confined to generated qualification adapters. No source
per-write/allocation ledger or supported alternate memory model is introduced.
Affected consumers are these fixtures and existing focused GC2-GC7 controls.
If a production correction becomes necessary, revisit this review and select
paired accepted/rejected regressions for its actual shared consumers first.

## Critical execution experiments

* Reference mutation: 4,096 nodes, deterministic indexed visits, repeated
  reference assignment/read and primitive mutation. Retain the array and all
  nodes. Compare current indexed/reference controls and allocation layout
  perturbations separately; neither is an old allocator baseline.
* Rare allocation: replace one published node per 4,096 operations, compared
  with reuse using the same operation schedule. Exact successful allocation
  counts and checksums must agree with the declared schedule. Inspect the real
  callers/inlined loops, not just allocation helpers.
* Ordinary calls/general defer: existing deterministic call and actual
  ArrayObjectPool fixtures, paired with equivalent nested finally. Extend
  sampling beyond GC7's bounded two-round control, add identical-binary A/A.
* Application: maintained eight-operation OrderBook cycle, full pool restoration,
  match/volume/order checks and zero warmed allocations. Inspect ironwood_run
  and out-of-line measured callees.
* Foreign execution: supported generated scalar/critical calls separately from
  callback transitions, retained listeners and nested collecting reentry.
  JNI/anchor closure and collection-capable spills are independent observations.

Separate cold setup, warmup, warmed batches, explicit collection and reuse.
Use at least six independent processes per primary label, seven batches where
supported, alternating/reversed order or a recorded deterministic seed, and A/A
labels pointing to the same binary. Validate results outside measured regions.
Report process medians, range and deterministic process-level bootstrap intervals;
batches are not independent processes. Measure clock resolution. Batch averages
are throughput observations, never individual-operation tail latency.

Hash source, compiler jar, runtime inputs, SDK configuration/manifest/payloads,
LLVM tools, binaries and actual linked disassembly. Normalize only addresses,
relocation presentation and generated label identities; retain every instruction
and operand change. Attribute checks/bounds/resource operations separately from
GC work. Required transition capture and collection spills may occur only on
collection-capable routes. Investigate meaningful differences without a universal
percentage threshold. Historical C/LLVM/manual-heap evidence cannot establish a
matched current-runtime regression percentage.

## Resident phases and expected stabilization

Build normal Ironwood AST-like trees with shared symbol maps, cyclic type links,
reference-array worklists, varied fully touched byte payloads and temporary
scratch. Keep a defined current snapshot. Replace it repeatedly, retaining an
obsolete snapshot deliberately for a paired control; then clear its actual
physical slots and collect. Exercise pool and map cold reference cleanup,
statics, caught exceptions, trace refill, finally/defer and explicit file closure.
Consume payload contents before/after collections. No sparse virtual-span test.

Start with modest node counts, then scale to roughly tens/hundreds of MiB below
measured host headroom. Use bounded rounds and independent processes. Before
interpretation expect: retained control has an extra known graph, clearing drops
its live count, constant-shape post-collection counts repeat, sidecars settle,
heap growth reaches an allocator high-water plateau and subsequent replacements
consume reusable capacity. RSS need not fall. Require fully touched resident
payloads, exact survivor explanations and stable native descriptor/JNI/anchor
balances. A changing graph shape or intentionally retained slot must be accounted
for before diagnosing the collector. Do not build the Ironwood compiler workload.

Record phase allocations, cached live counts, expected graphs, sidecars,
collector administration, heap/free bytes and deferred-reclaim diagnostics;
process current RSS, exact OS peak RSS and macOS physical footprint when available.
Collector free bytes mean reusable capacity, not OS-returned pages. Native malloc
and collector metadata need separate accounting or an explicit observation limit.
Measure the complete source Memory.collect() call with System.nanoTime(), including
root preparation, eager engine work and post-collection enumeration. Instrumented
memory runs are separate from primary timing. No work may be deferred into later
allocation; collection counts must change only at requested sites.

## Bounds, verification and acceptance

Use available native hardware for primary evidence; record OS/CPU/memory,
translation/virtualization, headroom and power/frequency limitations. This is
bounded host qualification, not GC9 target/distribution acceptance.
Every child gets the existing 16 GiB summed process-group RSS guard, nominal
50 ms sampling plus ps overhead, and 60-second timeout. Shared pages can be
double counted, short peaks missed and detached groups excluded. Guard/timeout
termination fails an experiment. Keep raw logs/binaries/SDKs under ignored
workspace/gc8; commit compact records only. Keep generated disk use bounded.

Focused validation: new workload checksums/allocation/retention assertions,
observer integrity and distribution-statistic checks, O0/O3 graph behavior,
GC5 integrated/pool/resource/no-automatic-collection/OOM controls, current SDK
GC2 layout/exactness and GC6 supported callback/reentry/refusal controls.
Use exact registered selections or dedicated bounded harnesses, never an
unfiltered suite. Run diff, license and tracked-file checks before local commits.

Accept GC8 only when each accepted kernel has explained execution paths,
repeated resident phases stabilize/reuse storage and native resources balance.
Record limitations and unresolved regressions honestly in GC8_IMPLEMENTATION.md
and its compact manifest. GC9 receives target/distribution qualification only;
Linux arm64/arm64e gates and the self-hosting stop remain intact.

## Recorded refinements during execution

The generic map/list/pool observer candidate was conservatively refused because
generic specialization invalidates the original bridge snapshot facts. No proof
was bypassed or generalized. Use a fixed two-key symbol table, physical reference
worklist and one-slot scratch pool in the resident image. Retain the normal
stdlib map/pool cleanup and provenance controls separately. A combined historical
micro source with String-copy and callback exports also refused incomplete
callback classification. Use a narrow scalar recurrence beside the maintained
ResultProcessor callback workload instead; keep the refusal intact.

The first 14-phase observations showed slow free-block/native changes. Add a
1,044-phase diagnostic with and without resource/trace/export work, followed by
a native-only block control to separate Java reporting/JIT allocations. Free
block counts plateau, and dropping every graph restores free capacity. The first
4,200-phase native-block candidate exceeded the unchanged 60-second deadline.
It is an incomplete experiment, not accepted capacity evidence. Reduce the
bounded work to 200 warmup phases plus three blocks of 500, preserving the
16 GiB guard and all workload assertions. No production optimization is selected.
