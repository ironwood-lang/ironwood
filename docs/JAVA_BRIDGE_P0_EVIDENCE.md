<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge P0 feasibility checkpoint

P0a/P0b/P0c pass for continued implementation under D213. The only deferred
P0 qualification is the real Linux x86-64 hardware stack experiment. This is
not production Java Bridge completion or release readiness. P1-P4 must carry
these cases into their production artifacts; P6 must qualify the final candidate.

## Environment and evidence identities

The designated checkout is `/Users/soliveira/workspace-mba-m2/Ironwood`, on
local `java-bridge`. No branch was merged or pushed. P0 uses pinned Temurin
HotSpot 21.0.12.1+1 and LLVM 23.1.0, separately from the ordinary IDK JDK.
Preparation, immutable archive pins, offline checks and image identities are in
`workspace/java-bridge/evidence/p0a/` and `scripts/prepare-java-bridge.py`.

- Physical macOS ARM64: Apple M5, macOS 26.6.2 (25G83), Darwin 25.6.0.
- Local Linux ARM64: Colima `ironwood-tests`, 6 CPUs/8 GiB, Ubuntu 24.04.4,
  kernel 6.8.0-117-generic; same-architecture virtualization on that Mac.
- Linux x86-64 functional evidence: the prepared x86-64 image under Lima Rosetta.
  It proves neither hardware performance nor native stack limits.

`workspace/java-bridge/evidence/p0b/checkpoints.json` binds checkpoint revisions
to retained source/LLVM/C/Java inputs, payloads, commands, outputs and SHA-256
identities. Static disassembly inspects those target binaries, not translated
machine code. Each native case uses O0/O3 and Java `-Xcheck:jni`. Instrumented
runtime copies are identified separately and never replace unmodified-runtime
functional checks. Earlier superseded attempts remain recorded, not passed.

## Case audit

Evidence paths below are relative to `workspace/java-bridge/evidence/p0b/`.
The progress log records per-target directories and checkpoint commits.

| Case | Objective evidence and disposition |
| --- | --- |
| P0-1 | `BridgeEntryTests`, root/identity and actual-engine fixtures pass primitive static/instance values, cold/warm calls, caught native failures and continued calls. `scalar-entries/`, `root-payloads/`, `root-identity/`, `orderbook-failure/`. All three local targets pass. |
| P0-2 | `BridgeImageTraceTests` loads disjoint shared images, repeats bootstrap and verifies each exception's image/function/file/line without cross-image resolution. `shared-traces/`; all three targets pass. |
| P0-3 | `BridgeLoaderTests` exercises duplicate classes, split packages, mixed generations/signatures, class-path/first-use orders, disjoint success, loader GC/global anchoring, explicit retained-image OnLoad refusal and late registration rollback. `loaders/`; all three targets pass. |
| P0-4 | `BridgeEnumTests`/`BridgeEnumNativeTests` prove named conversion/initialization; fresh SELL receiver returns 1 and asymmetric argument returns 29. Null, failed/repeated initialization and Java-only initialization on another thread pass. `enums/`; all three targets pass. |
| P0-5 | Retention, construction, destruction and root-entry proof tests preserve conservative unknown effects, artifact parity and ordinary free diagnostics. `BridgeCommitTests` verifies actual success/failure deltas, aliased holders/slots, increments before decrements, no early commit failure, preparation/headroom refusals and post-return Java failures. Facade GC retains indexed counts; final eligible frees release root/global state. `root-payloads/`, `host-commit/`; all three targets pass. |
| P0-6 | `BridgeStringTests` repeats allocating conversions with budgets 0/1, initializer failures and snapshot fallback; partial JNI acquisition/copy cleanup is exact. Instrumented copies verify released emergency delivery and cleared implicit failure state. `string-copies/`; all three targets pass. |
| P0-7 | `BridgeIdentityTests` verifies held-facade identity, independent/borrowed instances, shared invalidation, weak-cache failure/recovery, facade GC, reservation/index-growth/global-reference failures and exact destruction. An isolated deterministic allocator forces same-address reuse with distinct new state and dead old facades. Inherited Java identity remains valid after free. Mixed/unbounded reclaimable origins stay refused. `root-identity/`; all three targets pass. |
| P0-8 | Actual engine non-reclamation and exact rollback proofs agree across source, class directory, individual class and archive. `BridgeOrderBookNativeTests` calibrates actual constructor failures and records allocations/deallocations, including existing exposed controls. `orderbook-failure/`; all three targets pass. |
| P0-9 | Static and instance scalar adapter/typed-entry O0/O3 disassembly is retained for each target with handwritten JNI comparisons. Optimized instance getters contain four typed instructions. The permanent path has no liveness check; the reclaimable path has only the required owner check and ABI work. Neither warmed path allocates, scans identity state, updates counters/TLS/traces, synchronizes or calls an avoidable helper. Error formatting is conditional on failure. `scalar-entries/`, final `root-identity/` and `orderbook-failure/`; pass. |
| P0-10 | `BridgeStackTests` passes depths 1/8/32/64 with Java depths 0/64 on default platform-thread stacks, deepest-frame failure and continued calls at O0/O3 on both ARM64 environments. Separate limit children characterize fatal overflow without claiming recovery. `stack/run-258330496932685331` and `stack/run-1268804225534442939`. Linux x86-64 hardware remains pending D213. |

## D208 cleanup result

The capacity-2 control book consumes 10 managed allocations; a successful
capacity-3 book consumes 12. Total budgets 13/17/21 fail at OrderBook source lines
48/54/59: after one Order is installed, partway through PriceLevel construction,
and during the last array allocation. No failed book facade is delivered.

The first two failures deallocate only the fresh failed book. The final failure
deallocates its fresh `tail` array followed by the book. Unpublished survivors
are 2/6/9 respectively; no pooled-element destruction is invented. Exact event
addresses are disjoint from the earlier control, whose getters, reduction and
cancellation remain allocation-free. These findings prove the required safety
exclusion, not leak-free failed construction.

## Carry-forward boundary

Production implementation reuses the resolved root/ABI model, bound final
construction/result facts, retention/non-reclamation/cleanup analyses, protected
typed entry lowering, bounded result/slot transport, shared linking and image
trace registration. Ordinary compilation and mandatory free proofs remain
unchanged. There is no public switch that bypasses a proof.

The fixed JNI/Java fixture bindings, diagnostic counters, fault injection and
single-purpose source adapters are test harnesses. P1 integrates final-link
roots and optimizer propagation. P2 replaces scalar bindings with the producer,
loader and jar generation. P3 extends the same analyses and generates object,
identity, retention and complete exception protocols. Unsupported conversions,
unproved effects/dispatch, mixed reclaimable origins, nested view ownership and
view retention without owner deltas remain rejected by the current internal
admission paths. They cannot silently become production capabilities.

## Deferred hardware command

On a verified physical Linux x86-64 host, optionally in same-architecture
virtualization, prepare the pinned x86-64 Temurin/LLVM environment using
`scripts/java-bridge/README.md`. Preserve host CPU, OS/glibc, VM resources,
archive/image identities and explicit absence of translation. Then run from the
matching checkout with that JDK's `JAVA_HOME`/`PATH`:

```sh
./scripts/test.sh --test 'Java Bridge bounded native stack envelope and isolated limits'
```

Expected: the single selected compiler test passes its O0/O3 bounded cases and
records separate limit-probe outcomes under `workspace/java-bridge/evidence/p0b/stack/`.
Retain its commands, logs, payload hashes, disassembly, JVM stack flags and
environment records. Deliberate limit-child crashes are diagnostic outcomes,
not recovered Java exceptions. Do not run this under Rosetta/QEMU as hardware
qualification. P6 supplies the final candidate runner and all required 21/22/23
hardware/JDK cells; this P0 command does not replace those final checks.
