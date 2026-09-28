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

First checkpoint committed as `cf04f74c`.

### Second checkpoint: primitive enum argument transport

Generated callers map Java declaration ordinals to the compiler's name-assigned
native tokens and pass private primitive parameters. Null remains token -1;
protected typed entries still initialize and convert the enum. This removes JNI
field reads and exception queries without broadening the public API. Mixed
root/enum declarations retain their existing receiver and reservation indexing.

Cold enum/initializer containment and enum host-failure selections pass at O0/O3.
Preparation failure injection moved to the same transport preparation point,
preserving original assertions for entered count, acquired/released strings,
native allocation, live storage and successful recovery. An initial overly broad
fixture replacement failed C compilation; restricting it to the two intended
string-bearing entries fixed that failure. Logs: `enum-token-first.log` and
`enum-token-focused.log` under `experiments/optimization`. License audit passes.

Mac Java21 intermediate comparison: native 47.84, Java 73.09, old bridge 693.51,
new bridge 95.66 ns/eight-operation cycle. See
`optimization/enum-tokens/comparison/result.json`. O3 ordinary adapters now pass
integer enum arguments without JNI field access. The remaining gap is not
accepted; next inspect separate adapter/typed-entry calls and cache cost.

### Next experiment: joint adapter and typed-entry optimization

Clang currently compiles adapters separately, preventing LLVM from removing the
protected-entry carrier/call boundary. Test Clang bitcode plus compiler typed IR
in one optimization unit, retaining the isolated C runtime, protected landing
pads, trace metadata pass and baseline target. This affects producer native build
identity and shared backend composition, not admission or reclamation analysis.
Before adoption compare identical workload, inspect optimized calls, and verify
producer determinism, scalar containment, object/enum/root exceptional exits,
exact traces and packaging on each supported target. Ordinary executables and
existing fixture object linking must retain their current path.

Joint bitcode experiment did not improve the Mac workload: 96.13 ns/cycle;
forcing entry inlining measured 101.31. Rejected both production changes. Exact
patch retained at `experiments/optimization/joint-bitcode-experiment.patch`;
artifacts and comparisons retained under `optimization/joint-bitcode` and
`optimization/joint-inline`. No LLVM toolchain/dependency change adopted.

Next pre-change review: the existing InitializedTypeSpecializer selects looping
methods, overlooking non-looping exported entry methods. Experiment with adding
native export roots to its selection, reusing the existing immutable publication
proof, state-2 guard, original cold/failure fallback and clone fact propagation.
Consumers include bridge final reclamation/retention checks and normal executable
initialization. Verify cold success, failed and recursive initialization, enum
identity, exact exception traces, safe roots and unsafe hidden reclamation,
source/class/archive reconstruction. Existing loop selection for executables is
unchanged. Use focused initialized-type audit and bridge enum/final-proof tests.

Adding non-looping export roots to initialized-type selection measured 95.30
ns/cycle, with no meaningful improvement over primitive enum transport. Retained
experiment patch and artifact; reverted the production policy change.

Next cache experiment: avoid queue polling on live hits, and drain on misses
before any insertion. Keep weak referents, exact address identity, null/cleared
handling, delayed-queue removal by Entry identity, allocation/growth exception
atomicity and bounded records. No insertion can grow without a drain, and no
hit adds a record. Root caches retain the existing lookup policy. Verify the
existing cache adversarial, delayed-queue, allocation and actual pool GC tests.

Moving cache queue draining to misses measured 97.57 ns/cycle, also no reliable
improvement. Rejected and preserved the experiment patch/artifact. Production
remains the two measured transport improvements at `2be10c1c`. Further work now
separates native engine, JNI transitions and identity costs, and checks physical
x86 performance before final qualification. None of the three rejected
experiments is included in production.

Longer unchanged-workload comparison (10 million warmup, 50 million measured,
three interleaved forks): Mac native 45.62, Java21 61.49, bridge21 82.34 ns/cycle;
Estonia native 125.32, Java21 239.72, bridge21 192.22. Java22/23 and 64-operation
latency reports are preserved in each `enum-tokens/long-comparison` directory.
These development results establish a physical x86 gain but a remaining Mac gap.

IR and disassembly identify another specific optimization opportunity:
OrderBook.match has 362 typed operations, called by two small methods (16 and 6
operations). SelectiveInlining's 256-operation bound excludes it. Experiment
with a 512-operation library bound, preserving the executable policy, recursive
exclusion, direct-call restrictions and all checks/trace metadata. Consumers are
native shared images; proof IR is unchanged. Verify normal/exceptional native
behavior, cleanup and exact traces, existing large/recursive rejection policy
for executables, plus object/enum/root production tests before adopting a gain.

Maintainer direction: Linux is the numerical performance judge; beating Java on
macOS is optional. Preserve macOS correctness and report its numbers, but focus
optimization acceptance on Linux x86-64 and Linux ARM64.

The larger selective library-loop bound measured 77.79 ns/cycle on Mac (versus
82.34 for the two fixes). Raising the shared LLVM inline threshold to 10000 in
addition measured 78.88, with no added gain; reverted that threshold experiment.
Compare the selective-loop change against the committed baseline on Linux before
adoption. Native and Java project algorithms remain unchanged.

### Third checkpoint: medium library-loop inlining

Physical Estonia Java21: native 124.47, Java 240.28, bridge 178.84 ns/cycle.
Linux ARM64 matched comparison: native 45.60, Java 65.24, committed two-fix bridge
82.58, larger-loop bridge 78.16. All three JDKs and per-64-operation latency
reports are retained at `optimization/estonia-inline/comparison` and
`optimization/linux-arm64-inline/comparison`. Mac O3 disassembly confirms no
out-of-line OrderBook.match body/call remains. Normal JNI adapters still preserve
protected status handling. No engine source or benchmark workload changes.

Seven focused tests pass in `experiments/optimization/inline-focused-tests.log`:
legacy and new library selection, native checks/cleanup/exact traces, final
non-reclamation and root proof negatives, cold enums, actual OrderBook allocation
and weak recreation at O0/O3. Test/build strict compilation and license audit pass.

Longer Linux ARM64 controls do not rehabilitate the earlier cache/LLVM experiments:
inline plus cold-cache draining 79.98 ns, inline plus joint LLVM/forced entry
inlining 78.87 ns, versus approximately 78.16 for library-loop inlining alone.
Keep these changes out of production. One last isolated compiler copy tests
prioritizing export roots in initialized-type specialization; production source
is unchanged by that experiment. Its source/jar/artifact live under
`experiments/optimization/export-priority` and `optimization/linux-arm64-export-priority`.

### Matching JNI boundary controls

The maintainer asks to establish crossing costs directly. Independent handwritten
JNI controls use the same eight primitive call shapes as the OrderBook cycle,
with almost empty native bodies and unchanged argument counts. They also measure
one scalar crossing and a native-to-Java object callback separately. All controls
pass checked-JNI functional runs before unchecked timing; consumed checksums and
allocation observations accompany three fresh forks and seven trials per JDK.

Estonia Java21: scalar 7.248 ns, eight-call cycle 60.101 ns, callback 105.295 ns.
Linux ARM64 Java21: scalar 2.710 ns, eight-call cycle 22.260 ns, callback 54.517 ns.
Evidence: `optimization/estonia-boundary` and `optimization/boundary-linux-arm64`;
exact independent sources/runner are in `experiments/optimization/boundary`.
Callbacks are much more expensive than direct JNI transitions. The controls
include loop and argument work and are not an exact additive subtraction model.
All earlier artifacts/evidence remain preserved; no production instrumentation
or installation was needed.

### Final initialization-selection refinement under verification

The isolated export-priority compiler copy improves Linux ARM64 in all three
paired forks: medians 78.45 to 76.71 ns/cycle. Physical Estonia likewise measures
178.39 to 176.81 ns. Before adopting it, extend the existing initialized-type
structure test with a non-looping library export: require explicit state-2 guards,
unchanged cold/reentrant/failure fallback and mutable-field loads. Reuse existing
recursive/failed-initialization native tests and exact traces, final bridge
lifetime/root proof negatives, retention/delivery tests, and source/class/archive
producer parity. Only root selection/order changes; publication proofs, budgets,
clone provenance and final mandatory revalidation remain unchanged. Production
qualification must use the final resulting compiler, never these experiment jars.

The refinement passes all ten focused selections in
`experiments/optimization/export-priority-focused-tests.log`, including producer
source/class/archive parity, final lifetime/root negatives, exceptional retention
commits and exact traces. Strict compilation, licensing and diff checks pass.
Adopt as D223. No further experimental compiler policy is selected. Next freeze
the complete matched production candidate, run the remaining focused host/JDK,
loader, stack and packaging checks, and collect final matched measurements. The
JNI controls establish small absolute crossings; they do not authorize weakening
identity, skipping required work or silently replacing the API with batching.

### Assembly inventory regression found during candidate refresh

Candidate `234c6e7e` values pass all 18 assembly launches; roots fail before
registration with a conversion-helper signature mismatch. Host production includes
D220's private helpers, but the package manifest and assembler omit their inventory.
Preserve this failed candidate. Add a separate host-helper inventory, consume it
when rebuilding the combined loader, and extend the existing assembly test with a
permanent nested object and missing helper-descriptor rejection. This changes
packaging metadata only, not native code or lifetime proofs. Run that focused test,
license checks, and refresh the five-case candidate using the repaired producer.
The 14 remaining Mac fixtures and 13 proofs pass; Linux selections remain running.

The repaired focused assembly test passes, including host reproducibility, nested
permanent identity, checked-JNI execution, unchanged native payload bytes and all
malformed-input rejections. Evidence: `experiments/optimization/assembly-repair.log`
and `p6a/assembly/run-7189411520427621978`. Strict build and license audit pass.
