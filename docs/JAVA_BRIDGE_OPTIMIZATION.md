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
