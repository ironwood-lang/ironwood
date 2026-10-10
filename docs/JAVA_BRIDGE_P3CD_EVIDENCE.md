<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge P3c/P3d implementation gate

This is a historical phase checkpoint. Subsequent [P6 evidence](JAVA_BRIDGE_P6_EVIDENCE.md)
and [x86host hardware results](JAVA_BRIDGE_X86_EVIDENCE.md) record completed
P0-P4/P6a implementation and D213 hardware work. P5/P7 remain deferred; final
numerical acceptance remains open.

P3c roots/views and P3d bounded retention are implemented through the public macOS
ARM64 producer. Together with [P3a](JAVA_BRIDGE_P3A_EVIDENCE.md) and
[P3b](JAVA_BRIDGE_P3B_EVIDENCE.md), their focused gates permit P4 work under D213.
This is not P4 acceptance, final numerical performance acceptance, complete P6b
qualification or release readiness. Real Linux x86-64 hardware remains pending.

The [implementation plan](JAVA_BRIDGE_PLAN.md) is authoritative. Root admission
reuses P0's analyses, exact final specialized contracts, typed slot payloads and
proved destruction/rollback entries. It adds no ownership exemption, callback
assumption or source-free permission. P5 callbacks and P7 extensions remain deferred.

## Generated protocol

Each reclaimable lifetime has a shared Java state and weak facade cache. Native
records, table capacity and global references are reserved before source entry.
The native index registers successful new roots before Java delivery and retains
their state independently of facade reachability. New allocations at reused
addresses receive fresh states. Java-only inherited identity uses immutable facade
metadata; source overrides retain native access preconditions.

Retention preparation resolves fixed fields, acquires local references, deduplicates
aliased holders/roots and checks maximum incoming-count headroom. Typed entries
report actual final slot contents on normal and exceptional exits. The adapter
commits increments before decrements and then writes slot records, using prepared
references and JNI field setters without allocation or Java helper calls. Scalar
entries without slot effects omit this work. Destruction prepares outgoing
references before FREEING, invokes the exact nonthrowing destructor, releases
dependencies, marks FREED and removes the index/global reference before returning.

Source/class/archive reconstruction preserves admission and generation identity.
The producer still rejects unknown effects, slot transfers, child-held slots,
cycles, mixed fresh/existing result ownership and unsupported projections in every
missing-free mode. Failed builds preserve previous output.

## Focused evidence

Paths are relative to ignored `workspace/java-bridge/evidence/`. Test logs are in
`workspace/java-bridge/experiments/`; each generated fixture retains compiler,
runtime, LLVM, adapter, signed native payload and jar identities. Instrumented
copies and test-only recovery helpers have separate recorded identities.

| Contract | Evidence |
| --- | --- |
| Shared state and cache | `p3c/root-state/run-7626428187225804051`: exact refusal identity, owner/borrowed eligibility, weak collection/growth and zero-allocation warmed checks. |
| Reservation/destruction component | `p3c/root-index/run-11126882712834111291`: O0/O3 global-state lifetime, exact destruction, constructor rollback, 257 simultaneous roots, 4,096 tombstone cycles and preparation/native allocation failures. |
| Generated facade ABI | `p3c/root-java/run-10322113580382270384`: private immutable facade metadata, complete helper inventory, final admission matching and mixed root/permanent declarations. |
| Root/view behavior | `p3c/root-facades/run-218130679318025282`: exact no-entry refusals, source exception controls, weak view recreation, explicit cleanup and Java/native zero-allocation scalar/alias loops. HashMap removal and logger-thread inherited identity survive free; source identity overrides refuse dead access. |
| Root delivery failures | `p3c/root-host-failures/run-5918818888745382355`: 32 O0/O3 children cover preflight, global/record/table reservation, cache/facade delivery, real 32 MiB heap exhaustion, real Java stack exhaustion and native budgets. Post-commit failure preserves authoritative registration. |
| Forced address reuse | `p3c/root-reuse/run-9117448296427245164`: separate deterministic runtime, 512 lifetimes/511 reuses at one address, fresh states, stale facade/private-handle refusal and exactly-once destruction. |
| Retention core | `p3d/root-retention/run-12561699327157582973`: multiple/aliased holders, near-limit incoming counts, unchanged borrowed slots, same-owner child changes, store-then-throw, constructor rollback that mutates another holder, weak child collection and outgoing releases. |
| Retention failures | `p3d/retention-failures/run-1888079866656191130`: 26 O0/O3 children cover partial reference acquisition, destruction preparation, reservation, Java translation/cache/post-return failures, real stack exhaustion, holder facade GC/recovery and native budgets 4/5/6. Counts, records and global-reference baselines remain exact. |
| Custom snapshots after mutation | `p3d/retention-snapshots/run-6802956992806954384`: checked hierarchy, copied getters after holder and owned getter-result cleanup, Java-thread reads, throwing-getter fallback and native getter-allocation exhaustion preserve committed retention. Throwable storage retains its independent native ownership. |
| Object loading isolation | `p3d/object-collisions/run-8682385543923302827`: 16 O0/O3 children cover duplicate classes and package-only overlap in both orders, disjoint worlds, altered destruction signatures, loader anchoring and late partial registration. Failed binding retains its permanent anchor and unregisters completed/partial classes without damaging a disjoint world. |
| Public mixed-lifetime producer | `p3d/producer-retention/run-18299519046693799851`: source O0 and class/archive O3 jars have equal API/program/generation/module identities, complete content/source/Javadoc/notices, all three launch forms and Java 21-23 consumers. Java 24 refuses before extraction. All-mode slot-transfer rejection preserves earlier output. |

The producer's existing permanent and root scenarios also pass in
`p3d-retention-producer-1.log`, with evidence
`p3d/producer-permanent/run-8686497713881984659` and
`p3d/producer-root/run-12195241963600488448`.
Selected P3a production/final/view-owner proofs and existing delivery failures pass
`p3d-retention-regressions.log`. P0's commit and snapshot-fallback regression passes
`p3d-retention-headroom.log`. No unfiltered suite was run.

## Findings and performance scope

An initially empty aliased-holder slot exposed an adapter origin-resolution bug.
The corrected adapter resolves its final value against the complete admitted
input set, while deduplicated preflight unions possible origins for headroom.
The before/after child logs are `p3d-retention-empty-alias-before.log` and
`p3d-retention-empty-alias-after.log`. No compiler proof was relaxed.

The source probe `p3d-snapshot-root-probe.log` rejects destruction of an exception
field after its ownership becomes uncertain through throw. This rejection remains;
snapshot tests do not claim that such throwable storage becomes reclaimable.

O3 ARM64 review of `p3d/root-retention/run-9570196688878376641` shows an 80-byte
commit frame and three bounded loops calling only SetLongField/SetObjectField
(JNI offsets 0x370/0x340). Increments precede decrements. The scalar adapter keeps
its 320-byte protected-call frame without retention preparation. The warmed
100,000-update diagnostic loop reports zero Java/native allocations and
177,157,750 ns under checked JNI. Its adapter identity is
`d56f477c968d2314f83ee4ab8baca55d186b4b53ec7efd279b0010f47627fea7`.
These are implementation checks, not final timing acceptance. P6 must qualify
matched final candidate payloads and refresh affected evidence after changes.
