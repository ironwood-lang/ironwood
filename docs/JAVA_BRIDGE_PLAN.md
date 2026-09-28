<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood Java Bridge implementation plan

Status: P0-P4/P6 implementation accepted under D225; P5 authorized and in progress.
Work followed the maintainer's separate 2026-09-26 authorization. See
[the durable progress log](JAVA_BRIDGE_PROGRESS.md) for current checkpoints and evidence. This document alone does not authorize implementation.
Repository observations were originally checked at `767e21d`. P0 through P4
and the P6a distribution candidate are implemented; the [producer guide](JAVA_BRIDGE_USAGE.md),
[OrderBook audit](JAVA_BRIDGE_P4_EVIDENCE.md) and [P6 evidence](JAVA_BRIDGE_P6_EVIDENCE.md)
describe the current result. ARM64 and [physical x86-64 checks](JAVA_BRIDGE_X86_EVIDENCE.md)
and performance measurements are recorded. The maintainer accepted the measured
implementation under D225 and authorized P5 on 2026-09-28; see the
[P5 progress log](JAVA_BRIDGE_P5_PROGRESS.md). P7 remains deferred. Release work
belongs to the maintainer, outside this implementation task.
The release gates below still apply. The maintainer selected **Java 21-23** as the initial consumer
support range, deferring Java 24+ and its native-access authorization work.
This replaces the initial Java 21+ target. The maintainer selected explicit
`free()` for native reclamation (D189) and compiler ownership proofs plus shared
Java lifetime state (D190), including the stated boundary costs. The maintainer
delegated settlement of the remaining contracts, recorded in D191 and section
14. D192 adds compiler-proved non-reclaimable exports for process-lifetime
graphs. D193 requires exclusive generated packages and validates class identity
before native registration. D194 requires native enum initialization during
typed entry conversion. D195 assigns native-to-Java exception mapping to P2/P3,
before the release gate. D196 schedules retention-slot write analysis in P0/P3
and limits first-release retention slots to root objects. D197 contains all
potentially raising native conversion work inside typed entries. D198 makes
inherited facade identity methods Java-only and valid after native reclamation.
D199 defines the P0 proof checklist, P4 allocation criteria and P6 JVM matrix.
D200 requires adapter-side bookkeeping completion and preallocated root registration.
D201 adds dependency/stack experiments, explicit OOM-state cleanup and permanent
loader binding for the first release.
D202 requires eager Linux symbol binding, defines the private-entry trust boundary
and rejects mixed reclaimable result ownership in the first release.
D203 sets the current Java-only version guard refusing Java 24+ before loading.
D204 places the authoritative root index in native memory, separate from Java's
weak facade caches, so adapter-side registration cannot depend on Java allocation.
D205 defines host/JDK preparation and separates translated functional evidence
from hardware-dependent stack and release gates.
D206 specifies noncritical JNI string access and matched buffer cleanup.
D207 distinguishes bridge lifetime refusals with an artifact-private exception subtype.
D208 makes actual OrderBook constructor rollback a P0-8 proof/runtime experiment.
D209 reopens D203 as a product choice: test Java 25 in P2 and decide before P6.
D210 adds macOS signature-preservation and pinned-launcher load experiments in P1.
D211 makes P0-5/P0-8 reusable compiler analysis foundations carried forward into P3.
D212 divides P0/P3/P6 into dependency checkpoints and defines shared-model handoffs.
D213 moves Linux x86-64 hardware qualification to the end of P6b; implementation
may proceed with that evidence explicitly pending.
The recorded numerical results were accepted under D225, with further tuning
deferred. New P5 paths still require their own correctness and performance evidence.
Discussion also confirmed that the
bridge remains single-threaded by caller contract, without runtime enforcement
of thread misuse; see
[D188](DECISIONS.md#d188---java-bridge-confinement-is-a-caller-obligation) and
section 8.

This plan reviews [the user-facing sketch](JAVA_BRIDGE.md) and
[the earlier proposal](IRONWOOD_JAVA_BRIDGE.md). Where they disagree, use this
document as the implementation plan, not as a new language specification.
Accepted compiler semantics, including mandatory safe reclamation and D132/D133,
remain unchanged for ordinary native execution. D188-D213 record the accepted
threading, explicit `free()`, host-boundary enforcement, and first-release
contracts. Section 14 consolidates the settled implementation choices.

## 1. Product goal and the meaning of transparency

An Ironwood library producer builds one ordinary Java dependency. Its consumer
adds that dependency, imports its classes, constructs objects, and calls methods.
Ironwood supplies the generated Java facade, native adapters, native binaries,
loader, and dependency metadata. Consumers write no JNI declarations, foreign
function descriptors, pointer manipulation, platform selection, registration,
or `System.load` calls. Producers write ordinary Ironwood APIs and select their
export packages once; they do not maintain parallel Java declarations.

The implementation artifact is a **native shared library**, loaded into the
JVM process. An existing executable with `main` is not imported as a class and
is not launched as a subprocess. The same Ironwood sources can separately be
linked as an executable. Ironwood implementation code stays native; generated
Java adapter bytecode does not introduce a JVM backend for Ironwood.

Target an ordinary Java source experience for the supported export surface,
not unconditional equivalence to every Java class. Native lifetime, thread
confinement and supported Java versions and host platforms are
real boundaries. They must be stated before promising a drop-in replacement.

### Consumer experience to deliver

The following is a proposed Java call-site fragment using the **actual current**
OrderBook API, not a runnable bridge example today. Cleanup is omitted because
this project's graph does not yet have proved reclamation support, not because
cleanup is automatic. Choosing `free()` does not create that proof.

```java
import org.ironwood.orderbook.Order;
import org.ironwood.orderbook.Order.Side;
import org.ironwood.orderbook.OrderBook;

OrderBook book = new OrderBook(128, 32);
Order bid = book.createLimit(1001L, Side.BUY, 200L, 15044L);
long bestPrice = book.getBestPrice(Side.BUY);
bid.reduceTo(100L);
bid.cancel();
```

The consumer should need only the generated jar on the class path. Published
artifacts should work as ordinary Maven or Gradle dependencies without a
consumer-side Ironwood plugin. Producer build integration is a separate concern.
No Ironwood compiler, LLVM installation, C compiler, or network download should
be necessary on the consumer's machine.

### What can and cannot be configuration-free

| Concern | Intended behavior |
| --- | --- |
| Binding code and API declarations | Entirely generated. |
| Finding/loading the native binary | Automatic from the jar, on first use. |
| Matching native and Java builds | Automatic validation before user code executes. |
| OS/CPU selection | Automatic among packaged, compatible targets. |
| Java 21-23 JNI class-path application | No bridge-specific launch flags under ordinary JVM policy. |
| Java 24+ | D203's current baseline refuses before native loading. D209 schedules a Java 25 warning-versus-refusal experiment in P2 and a decision before P6; support is not yet expanded. |
| Native cleanup | Explicit `free()` with compiler ownership proofs and shared Java lifetime state. No automatic fallback is implied. |
| Unsupported signature or unsafe ownership | Producer build diagnostic, not generated methods that fail only when called. |
| Unsupported platform or restricted extraction | Clear load-time diagnostic; no guessed binary or runtime download. |

The initial release uses generated JNI on Java 21, 22, and 23. Automatic native
loading is part of the bridge; consumers do not supply native-access flags.
Java 24+ support, permission handling, executable-manifest grants, and related
deployment support tests are deferred, except for D209's bounded Java 25 product
experiment. The D203 refusal test remains required under the current baseline;
the D209 decision must update the version policy and its tests before P6 if changed.
Compiling facade classes with `--release 21` does not imply support for every
later Java version. FFM also remains a separate future option.

## 2. Review of the older documents

Preserve package-based exports, generated Java source and classes, automatic
loading, paired build verification, closed-world optimization, typed native
adapters, and exception containment. Revise the following assumptions.

| Earlier assumption | Review and required correction |
| --- | --- |
| FFM first, JNI only as a late compatibility feature | Java 21 is required. Start with generated JNI; evaluate final FFM later without preview APIs in the Java 21 artifact. |
| Shared libraries require essentially a linker flag | PIC exists, but export discovery, optimizer roots, initialization, image-specific trace metadata, visibility, and runtime dependencies also need work. |
| All binding facts are already in `IrProgram` | Escape/return analyses are package-private semantic machinery. `SemanticResult` currently exposes only program and diagnostics. A validated projection and additional boundary proofs are needed. |
| Clearing one facade's handle makes use-after-close impossible | It does not invalidate another facade, a child view, a retained native alias, or a callback in progress. |
| A fresh result implies that arbitrary Java `close()` is safe | Freshness at return does not establish safety after later calls publish or retain it. |
| The same Java type can have `close()` only on owned instances | Java method sets are class-level. Choose a representable ownership API before generating it. |
| Strong Java references protect native lifetime | They protect Java reachability, not explicit close, native free, pool reuse, or classloader unload. |
| Java-side loan bookkeeping is free under Ironwood's performance policy | It costs the application. D190 explicitly accepts the specified boundary checks and bookkeeping; further costs need review. |
| Callback argument wrappers can be reused and retargeted | Arbitrary Java code can retain them. Reuse can change a retained reference's meaning or expose dead storage. |
| No upcall implies eligibility for a critical FFM call | Extremely short execution on every path is also required. No blocking, unbounded loops, or lazy first-use work can be assumed away. |
| One per-thread status/scratch block is enough | Nested Java-to-native-to-Java-to-native calls need independent active frames. |
| Raw global memory segments are a default implementation technique | Do not expose unbounded native memory to Java. Start with generated accessors and bounded copies. |
| Thread-local exception fields imply safe thread migration | The runtime also has unsynchronized image-global trace, emergency, allocation-limit, and type-initialization state. Audit the whole world. |
| Every throwable can be owned by a Java exception and closed | Initialization failures, rethrown aliases, and emergency throwables can be retained or immortal. Prefer Java-owned exception snapshots. |
| An existing Ironwood `close()` can automatically be followed by free | Resource closure and memory reclamation are distinct. Publication, failure, aliases, and repeated closure must be proved compatible first. |
| Current OrderBook supplies listeners and time-in-force enums | The current project explicitly omits them. Use separate callback fixtures. |
| Native calls have a fixed published nanosecond cost | Remove unsourced rankings and end-to-end promises. Measure this implementation and workload. |

The old proposal's `close()` preference is replaced by explicit `free()` in D189.
Do not implement its other open choices as if previously accepted.

## 3. Current implementation and change map

Read the linked code before each implementation phase; this table is a starting
map, not permission to bypass the owning compiler phase.

| Existing code | Observed behavior | Planned change |
| --- | --- | --- |
| [`Main`](../compiler/src/main/java/ironwood/compiler/Main.java), [`SourceSetLoader`](../compiler/src/main/java/ironwood/compiler/SourceSetLoader.java) | Link mode requires `--main-class`; loading starts from named types or explicit inputs. | Introduce an explicit bridge output mode and enumerate exported package types from class directories/archives. Diagnose missing packages and duplicate definitions. |
| [`IronClass`](../compiler/src/main/java/ironwood/compiler/IronClass.java), [`IronJar`](../compiler/src/main/java/ironwood/compiler/IronJar.java) | Compiled artifacts contain source and type indexes for reconstruction. | Recompute bridge facts from reconstructed source; do not trust stale serialized ownership claims. Keep source/class/archive parity. |
| [`CompilerPipeline`](../compiler/src/main/java/ironwood/compiler/CompilerPipeline.java) | `analyze` accepts no main but returns before the executable optimization/emission path. | Share an explicit final-link pipeline across executable and bridge roots. |
| [`SemanticAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/SemanticAnalyzer.java), [`SemanticResult`](../compiler/src/main/java/ironwood/compiler/semantic/SemanticResult.java) | Final ownership/effect validation precedes program construction; generic specialization follows. | Produce immutable export contracts from final proofs and preserve their callable identities through specialization. |
| [`EscapeSummaryAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java), [`SymbolicReturnOriginAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/SymbolicReturnOriginAnalyzer.java) | Summaries distinguish retention, publication, fresh results, aliases, and dependent borrows. They do not provide complete slot writes/releases, destination-owner effects or slot-value propagation. General pool-array loads need not establish a known owner. | Reuse origin facts without rewriting unknown provenance. Add retention-slot write analysis (reusable P0 foundation, extended/integrated in P3 under D211), a closed-world non-reclamation proof, and foreign-root/foreign-callback obligations; preserve conservative native free analysis. |
| [`IrProgram`](../compiler/src/main/java/ironwood/compiler/ir/IrProgram.java), [`ClosedWorldPruner`](../compiler/src/main/java/ironwood/compiler/ClosedWorldPruner.java) | One optional entry point; pruning currently returns the entire program unchanged when it is absent. | Model an export root set; preserve only its reachable native implementation and required support. |
| [`InitializedTypeSpecializer`](../compiler/src/main/java/ironwood/compiler/InitializedTypeSpecializer.java), [`EnumArgumentSpecializer`](../compiler/src/main/java/ironwood/compiler/EnumArgumentSpecializer.java), [`FieldValueForwarder`](../compiler/src/main/java/ironwood/compiler/FieldValueForwarder.java), [`UnreadFieldStoreEliminator`](../compiler/src/main/java/ironwood/compiler/UnreadFieldStoreEliminator.java) | Transforms reconstruct `IrProgram`; some skip programs without an entry point. | Carry roots/contracts through every reconstruction. Do not assume a foreign entry has its owner's initialization or enum argument facts established. |
| [`LlvmEmitter`](../compiler/src/main/java/ironwood/compiler/backend/LlvmEmitter.java) | Internal source functions, dynamic destroy helper, catch-all native main, inline initialization barriers. | Lower typed bridge entries and bootstrap, with exact ABI signatures and contained exceptions. |
| [`NativeBackend`](../compiler/src/main/java/ironwood/compiler/backend/NativeBackend.java) | PIC program/runtime objects; executable link; runtime object caching; optional TLS dependencies. | Shared output kind, platform export controls, compatible dependency linkage, and cache separation for bridge flags. |
| [`OptimizedTraceMetadata`](../compiler/src/main/java/ironwood/compiler/backend/OptimizedTraceMetadata.java), [`runtime`](../runtime/src/ironwood_runtime.c) | Trace tables finalized after optimization and registered by native main. macOS runtime reads `_mh_execute_header`. | Register the correct library image and table/section bounds once, including multiple independent images. |
| [`IronDocs model`](../compiler/src/main/java/ironwood/compiler/doc/DocModel.java) | Structured source documentation infrastructure exists. | Generate Java documentation from supported declaration metadata, translating links and ownership descriptions. |

In particular, macOS `_mh_execute_header` refers to the executable image. Merely
moving the existing registration call into `JNI_OnLoad` would not make it read
the bridge dylib's probes. Prefer explicit image-local section bounds or an
equivalent image-specific bootstrap contract, with Linux section retention
tested alongside it.

## 4. Architecture and transport

The first transport is **generated JNI**, with Java source compiled using
`javac --release 21`. JNI is available on the required baseline, needs no preview
API, and lets consumers use normal JVM types. This is not a claim that JNI is
inherently faster or slower than FFM for this workload.

```text
Java application
  -> generated Java facade and private loader
  -> generated JNI adapter
  -> compiler-owned native bridge entries
  -> ordinary typed Ironwood program and reachable runtime
```

Keep transport separate from API projection and ownership proof:

1. `BridgeExportModel` (proposed name) describes Java names, source signatures,
   ABI signatures, exceptions, ownership/effects, and source spans.
2. A semantic bridge validator rejects unsupported or unprovable surfaces.
3. A typed bridge lowering stage creates constructors, invocation adapters,
   failure containment, and only those destruction capabilities proved safe.
4. Java and JNI generators consume that same model. Neither independently
   guesses method effects or object layout.
5. A packager emits the facade jar, platform manifest, and native payload.

Generate a small C JNI adapter using the producer JDK's `jni.h`, compiled by the
pinned Clang. It converts JNI values, invokes typed-generated C-ABI entries,
and translates results. This avoids hard-coded `JNIEnv` table offsets in LLVM.
It does **not** translate Ironwood source to C or place frontend semantics in C.
Keep it isolated from the mandatory runtime; executable builds gain no JNI
dependency. D191 selects this adapter shape; the initial spike validates it
rather than reopening transport selection without evidence of a problem.

**D197:** C performs JNI transport, not raising Ironwood conversions. For strings
it passes raw UTF-16 buffers and lengths to typed entries; it must not call
`ironwood_string_from_chars` or another potentially raising runtime helper
directly. Object handles and primitive values retain the defined ABI. C/JNI
buffer acquisition failures use checked status or pending Java exceptions,
with cleanup; they never invoke Ironwood unwinding from an unprotected C frame.

Use private generated native methods and explicit registration, with bootstrap
failure reported as Java linkage errors. Before any `RegisterNatives`, validate
the complete set of resolved classes and package ownership against the expected
artifact identity, as specified in section 10. A facade's own world/liveness
checks cannot detect that another artifact has rebound its native methods.
Keep bootstrap classes and bootstrap native symbols artifact-specific; a common
bootstrap name must not bypass this validation. Export only required JNI bootstrap
symbols and any deliberately supported bridge bootstrap symbols; bridge-internal
C-ABI entries can remain hidden when linked into the same library. FFM, if later
selected, can export its own allowlist. Avoid a general public FFI or public
native pointers.

Keep a transport-neutral ABI based on exact-width primitives, opaque object
references, bounded buffer descriptors, and explicit result/error storage.
Document boolean normalization, signed byte/short conversion, unsigned UTF-16
`char`, float/double representation, alignment, calling convention, and null.
Initially support the existing 64-bit targets, without making a `long` address
a public Java API. JNI pointer conversion must use the target's pointer-width
type, not assume that C `long` matches Java `long`.

FFM is a later benchmarked option for JDKs with its final API. Keep every FFM
class out of Java 21 linkage, using a separate implementation artifact or a
tested multi-release jar. Do not silently change safety/lifetime semantics when
choosing a transport. Runtime-generated facades, a Class-File API dependency,
GraalVM Native Image, a Java fallback implementation, and subprocess transport
are separate future projects, not prerequisites.

## 5. Export discovery and Java API fidelity

Use `ironwoodc --java-bridge --export <package>` as the selected public workflow,
matching the shorter existing user sketch. It performs a closed-world library
link and packages a jar; it does not require `--main-class` or a dummy main.
Keep lower-level shared-link options internal until an independent use case
needs a public contract.

Planned producer recipe, not supported by the current compiler:

```sh
ironwoodc --source-path src/main/ironwood -d target/ironwood-classes \
    src/main/ironwood/com/acme/pricing/PriceEngine.iron
ironwoodc --java-bridge --export com.acme.pricing \
    -cp target/ironwood-classes -o target/pricing-bridge.jar -O3
```

The second command generates and compiles facade sources, links the host native
payload, and packages the jar atomically. Keep failed builds from replacing a
previous valid output. Producer plugins run this same pipeline before Java
compilation and add the output automatically. Standard dependency resolution
is enough for a consumer of a published jar.

Select exact packages, not implicit recursive prefixes. Multiple `--export`
arguments form a union. Include public top-level and accessible nested types
and their public callable surface. Validate the signature closure, including
inherited visible members and interface defaults, without silently generating
source-type facades outside the selected packages. If a signature requires one,
diagnose the member, required type and missing export package at producer build.
The producer must include that package in this artifact or change its public
surface. Reject inaccessible signature types with source locations. Native-only
implementation dependencies need no Java facade merely because they are reachable.

**D193: exclusive package ownership.** Each generated source API package belongs
to one bridge artifact in the supported application. Sharing its generated
types between independently linked native worlds is unsupported, even when
their Java declarations match. Multiple bridge artifacts must have disjoint
generated packages. Ordinary mapped JVM types such as `String` and built-in
exceptions are reused, not generated and not owned by an artifact. Private
bridge support uses an artifact-specific reserved namespace; it is the only
generated-package exception to the source API's explicit `--export` set.
Reserve a package marker binary name in each owned API package and diagnose a
source declaration colliding with it. This also detects two artifacts owning
the same package while exposing different public class names.

An exported package is an API commitment. An unsupported public member must fail
the bridge build with a useful diagnostic, rather than silently disappear or
become a runtime trap. A narrower producer package is an ordinary API boundary,
not a handwritten binding requirement. Optional per-type selection can be
considered later; producer-authored bridge annotations are not needed. Generated
identity annotations are private binding metadata, as specified in section 10.

| Surface | First supported treatment and boundary |
| --- | --- |
| Primitive methods, overloads, constructors, static methods | Generate the same Java signatures; preserve overload resolution, widening, access, ordered evaluation, and exceptional edges. |
| Final concrete objects | Primary object milestone. No Java subclass can override native behavior. |
| General native inheritance | Deferred beyond the first release. A future projection must preserve assignability and dynamic dispatch; mandatory custom exception snapshot hierarchies are a separate P3 feature. |
| Java subclassing of native classes | Initially reject export shapes requiring it; do not generate an apparently extensible class whose overrides native calls ignore. Interfaces are the planned callback boundary. |
| Enums | Generate real Java enums with named constant mapping and custom exported methods. Convert non-null receivers/arguments inside the typed entry by ensuring the native declaring enum is initialized and loading its public static constant field (D194). Never map directly to private constant storage or assume ordinal stability across builds. |
| Nested types | Preserve Java source and binary names; non-static inner construction needs outer-lifetime proof and is a later capability. |
| `String` | Java `String` facade value, with exact UTF-16 content copied across the boundary. No public native string handle. |
| General `CharSequence` | Not equivalent to `String`. Arbitrary Java implementations can execute code or mutate; admit only after a compatible callback/copy contract is defined. |
| Primitive arrays | Stage after primitives/strings. Define aliasing, mutation, copy-back on failure, and retention; copying is not automatically Java-equivalent. |
| Object arrays and mutable object graphs | Later. Identity, cycles, retention, and array-store/covariance behavior need a complete contract. |
| Interfaces implemented in Java | Dedicated callback milestone; conservative unknown effects until proved otherwise. |
| Checked/unchecked exceptions | Preserve checked signatures and catch hierarchy; map built-in equivalents to Java types and user types to generated Java exceptions. |
| Reference generics | Require a finite closed-world instantiation policy. Do not admit arbitrary Java `T` merely because Java erases its signature. |
| Primitive generic specializations | Cannot be Java `Box<int>`. A future explicitly named projection requires a naming decision and is not transparent substitution. |
| Public fields | Compile-time constants may be copied with matching initialization rules. Mutable fields cannot be transparently intercepted by a facade; initially diagnose rather than replace field syntax with getters. |
| `ironwood.*` library types | A generated facade requires its package in the explicit export set and exclusive ownership by this artifact. Otherwise diagnose the public signature. Native-only use remains allowed. Do not fabricate a Java Collections Framework mapping for `ironwood.ds`. |

Use one live Java facade per exposed native object within its world, so self
returns and repeated borrowed returns preserve `==`. This requires a
canonicalization mechanism at object
conversion and careful cache lifetime; it is not free. Share a reclaimable
root's ownership state across all views; section 7 defines the separate cache
for non-reclaimable storage. Do not put identity lookups on primitive-only calls.

**D198: inherited identity methods are Java-only.** For concrete facades whose
resolved native implementation is the inherited `Object` method:

- `equals(Object)` is exactly Java reference comparison (`this == other`), with
  no liveness/world check. A freed facade still equals itself; null, unrelated
  values and distinct facades compare unequal in either operand order.
- `hashCode()` computes the paired runtime's address-based identity hash in Java
  from immutable address bits. Match `identity_hash` in `ironwood_runtime.c`,
  including unsigned shifts, 64-bit wraparound and the final 32-bit result.
  No native dereference, JNI call or identity-cache lookup is needed.
- `toString()` formats the immutable native type name, `@`, and that identity
  hash as lowercase hexadecimal without leading zero padding, matching the
  paired runtime. Use the identity calculation directly, never virtual
  `hashCode()` dispatch that could enter a source override. Java string
  construction is allowed; native conversion and allocation are unnecessary.

Capture address bits and the resolved native type-name metadata in final Java
fields when creating the facade, before publication. Never derive the name by
dereferencing the native header after free. Preserve these values when native
access is invalidated; the address is private identity data, not permission to
access storage. Address reuse may produce equal hashes/text for distinct old
and new facades, but cannot make them equal. These inherited operations ignore
mutable root state and remain available after owner free, including on borrowed
views. Safely published facades may use them from another Java thread without
entering the native world; this does not make native calls or `free()` thread-safe.

Select this projection per method from resolved source semantics, not merely its
name. Source overrides of `equals(Object)` remain outside the first release
because arbitrary Java `Object` arguments are unsupported; diagnose them at
producer build. Supported source overrides of `hashCode()` and `toString()`
still dispatch natively with applicable liveness checks, conversion and D188
confinement. They may fail after free and are not safe for asynchronous logging.
Java casts, `instanceof` and `==` need no native access, even for freed wrappers.
Generated enums keep their Java enum identity behavior and existing method
projection; this concrete-facade rule does not replace enum behavior.

`java.lang.Object` methods, Java monitors, reflection, serialization, cloning,
framework proxies, and native object identity are not automatically equivalent.
Ordinary Java reflection may see the generated API; it must not see a promise
that native fields or arbitrary serialization are supported. Document enforced
boundaries and test Java-valid calls admitted by the generated declarations,
following [behavioral contract review](OPENJDK_PORTING.md#behavioral-contract-review).

**D202: private entries are not a security boundary.** The safety contract covers
calls through the generated public facade, including ordinary reflective calls
to those public methods. Bypassing it with privileged reflection, method handles,
`Unsafe`, instrumentation or JNI to invoke private native entries or forge/mutate
handles and lifetime state is outside the contract. Generated native methods and
raw handles remain private; do not promise safe rejection of such bypasses or
duplicate every facade check in native code to defend against hostile JVM code.
This exclusion does not excuse a safety failure through supported public calls.

## 6. Native roots, initialization, and host-safe execution

### Roots and analysis order

Build export roots before reachability or specialization can discard them.
Roots include exported constructors/methods, reachable dispatch targets,
required class initialization, callback proxy targets, approved destruction
entries, conversion helpers, exception translation, and bootstrap metadata.
An exported callable may be the first call, may be called repeatedly, and may
be invoked in any order allowed by its source contract.

Feed foreign publication/retention into semantic analysis **before final free
validation**. Adding trampolines after accepting ordinary ownership proofs
would miss new escapes. Callback proxy synthesis must precede the analyses that
depend on dispatch/effects, with fixed-point recomputation where needed.
Export wrappers must not manufacture a borrowing exemption.

Expose immutable proof results, not the live analyzers or diagnostic observer
snapshots. Record at least result alternatives, owner origins, retention versus
publication, invalidation/reclamation effects, callback reachability, throwing,
whether a complete destruction capability is established, and the separate
non-reclaimable/immortal classification from section 7. Preserve all result-origin
alternatives in analysis; never choose only the favorable one. D202 rejects an
export whose reclaimable result can be freshly Java-owned on one path and an
existing borrowed/alias reference on another, including through helpers or
dispatch targets. Source-location diagnostics must identify the conflicting
origins. A null alternative does not itself mix ownership: fresh-or-null and
proved borrowed-or-null remain admissible under the existing lifetime proofs.
Results uniformly classified as immortal/non-reclaimable by D192 remain exempt
from reclaimable ownership accounting, even with multiple allocation origins.
Do not infer ownership from a non-null pointer, Java type or a cache miss. A
future mixed-ownership extension needs an explicit compiler-produced result tag
and corresponding registration/lifetime proof; no such tag is in the first-release
ABI. Revalidate these rules after specialization changes callable identities.
Add immutable retention-slot contracts from section 14.B's new analysis before
final free validation: affected root origins, field identities, possible values
and complete normal/exceptional mutation effects. Existing escape summaries
alone cannot establish these contracts or classify an entry as read-only.

### Entry and exception boundary

Bootstrap validates the paired build and registers image-local trace metadata
once. Each exported operation preserves lazy type initialization at the correct
active-use point, including constructor failure and stored initializer failure.
Do not eagerly initialize every exported class during library loading.

**D194: enum conversion is an active native use.** Generated Java enum constants
contain only Java-side identity/mapping data, never a prebound native address.
Their Java static initializer must not load the native library, enter bootstrap,
or initialize an Ironwood enum. Java-only enum access can therefore occur on a
different Java thread without entering the native world; actual bridge calls
still obey D188's single-calling-thread contract.

For each non-null Java enum receiver or argument, pass a generated constant
token through JNI. Its mapping is established by constant name in the paired
artifact; a token is not an assumed native ordinal or a native pointer. Inside
the compiler-owned typed entry's exception boundary, on the calling thread:

1. Resolve the token to the declared native enum and its named constant field.
2. Perform the ordinary typed initialization check for that declaring enum,
   including constant-specific constructor/body initialization.
3. Load the source-visible public static constant field through typed IR and
   use that reference for the receiver/argument before executing the target body.

Bridge lowering must not substitute the private `IrEnumConstant` storage address
for this field load. Immortal storage is a lifetime fact, not an initialization proof: its
source fields start zeroed and its public constant field starts null. See
[native enum lowering](COMPILER.md) and [enum storage](MEMORY.md). Null enum
arguments remain null without initializing the enum merely for conversion;
normal source null behavior still applies. D055's recursive initialization and
stored-failure behavior remain unchanged, including field visibility on reentry.
If initialization throws, contain and translate it as any other native failure;
do not call the target body, retry construction, or fall back to raw storage.

Include these initialization checks and static field loads in bridge roots,
analysis and specialization. Java enum initialization, bootstrap success and
the token's identity do not prove native initialization. An optimizer may remove
a redundant barrier or fold the field load only from established native
initialization and publication facts. The C
adapter marshals the token; it does not implement source initialization semantics.

Every native entry contains Ironwood unwinding before it reaches JNI/JVM frames.
The protected region covers argument materialization, allocation, type/enum
initialization, the target body, native result conversion, exception-snapshot
extraction and any potentially raising cleanup. This applies to bootstrap or
follow-up conversion entries as well, not just the exported source method.
Supply the ordinary valid catchable allocation-failure context before any
fallible native allocation; a null failure context can take a fatal path even
with an enclosing handler. Do not disable allocation-limit coverage by treating
bridge conversion as pre-entry allocation.
Prefer a native adapter stack frame with bounded result/error storage over the
old global segment and per-thread scratch default. The JNI sequence is:

1. Validate the Java-side world/lifetime obligations in sections 7 and 14.
2. Acquire raw UTF-16 with `GetStringChars`, or copy it into adapter-owned storage
   using `GetStringRegion` (D206). Prepare invocation-local result/error storage. Do not hold
   any JNI critical region across the typed entry or adapter commit.
   Check pending Java exceptions and buffer-allocation status before native entry;
   reserve all root-registration and reconciliation state required by section
   14.A/B, and establish cleanup before any failing step. No Ironwood object is
   built in C.
3. Invoke the typed entry. Inside its catch-all, materialize native arguments,
   initialize types/enums, call the target, and extract native results or a bounded
   error snapshot. On argument conversion failure, skip the target body. Complete
   native cleanup and retention reporting on both success and failure under protection.
4. After native unwinding ends, the C adapter completes root registration and
   retention reconciliation before materializing Java results/throwables or
   returning control to Java (D200). C must not call native getters or other
   raising helpers here; any additional native work requires its own protected
   typed entry.
5. Release owned JNI buffers and native transport storage using proved nonthrowing
   release operations on every exit, including partial preparation failure.
   Match each successful `GetStringChars` with `ReleaseStringChars`, regardless
   of `isCopy`; free adapter-owned region-copy storage instead of passing it to
   that release API. Preserve pending Java exceptions during permitted cleanup.

Exception extraction runs in a separately protected region within the typed
entry, since a catch handler's own work is not protected by the catch it handles.
Apply ordinary catch/occurrence cleanup before extraction; do not allocate while
an implicit out-of-memory occurrence remains active. If extraction itself fails,
return a preallocated bounded failure status with no further native allocation,
getter calls or recursive snapshot attempts. The C adapter maps allocation
failure to Java `OutOfMemoryError`; if JNI reports its own allocation failure,
preserve that pending exception. Retained/emergency throwable storage is not
freed merely to complete translation. Inspect generated IR/unwind edges to prove
no potentially raising call is left outside these protected regions.

**D201: complete native catch cleanup, including OOM state.** Taking an unwind
wrapper with `ironwood_exception_take` does not clear `active_implicit_failure`.
The typed entry must also perform the ordinary `ironwood_exception_caught`
operation on the caught throwable before snapshot work can allocate or control
can leave the entry. Preserve handling of a primary exception carrying OOM as a
secondary failure; do not simply zero the runtime global or destroy the throwable.
Apply the same cleanup to failures caught in snapshot extraction and its fallback.
P0-6 checks repeated failures in different exported methods of the same world,
including initializer and snapshot failures, followed by an allocation-free call.
Test-only state inspection must show the implicit failure cleared and emergency
unwind wrapper released; no production per-call state scan is added.

Audit runtime `exit`, `abort`, process-global facilities, environment handling,
stdio, sockets, and optional TLS dependencies. Ordinary source exceptions must
be catchable by Java; genuine fatal runtime failures remain process-fatal unless
a separately proved recovery mechanism exists. An installable handler cannot
make arbitrary fatal unwinding recoverable. Do not redirect Java streams or
change JVM signal handlers as an unannounced bridge side effect.

## 7. Explicit `free()` and accepted ownership enforcement

**Accepted, D189:** Java requests native reclamation through generated `free()`.
Existing source `close()` methods retain their resource semantics. No generated
`close()` alias or `AutoCloseable` contract is added for memory reclamation.
Callers can use Java `try/finally`. Choosing `free()` does not authorize arbitrary
native destruction or automatic fallback. D190 separately accepts the ownership
enforcement and bounded host-side costs below.

**Accepted, D190:** combine compiler ownership checks with shared Java lifetime
state. The Java-side liveness checks, retention-change bookkeeping, and
callback active-use guards described below are approved design costs.
Ironwood cannot statically analyze arbitrary Java callers.
Compiler proofs cover the native graph; shared Java lifetime state covers the
facades through which Java accesses that graph. No Java garbage collector,
background destruction, thread checks, locks, or executor routing are required.

### 1. Classify ownership at the producer build

Classify each exposed result as Java-owned, borrowed from a known owner,
immortal, or compiler-proved non-reclaimable for the loaded world's lifetime.
Preserve native ownership: exporting a reference is not a transfer.
Only generate a destruction capability when the compiler proves the owner's
native graph can be reclaimed under a complete boundary contract. For values
that may be reclaimed, reject unknown ownership, uncontrolled publication, and
unaccounted native aliases at bridge build time. Do not make a fresh-return fact
a universal free permission, or equate unknown origin with permanent storage.

**D192: non-reclaimable exports.** Add a closed-world proof that no reachable
operation can reclaim any successfully exposed instance of the candidate type.
An unknown-origin result or escaping receiver is admissible without a known
owner only when every possible dynamic type has this proof (or is immortal).
Use resolved native types, not a Java declaration or an annotation promising
process lifetime. Keep existing escape/return summaries conservative; this is
an independent lifetime fact used by bridge admission, not a borrowing exemption.

The proof covers the complete export-root closure, repeated calls in any
permitted order, initialization, dynamic dispatch, exception paths and generated
adapters. Inspect source/deferred free, destructor and ancestor cleanup,
constructor rollback, temporary reclamation, pool eviction/destruction and
runtime deallocation effects. No generated destruction capability may target
these exposed instances. Unknown deallocation effects fail the proof. A mere
absence of a source destructor or public `free()` is insufficient.

Constructor rollback and other cleanup may still reclaim allocations proved
unpublished and disjoint from every exposed instance, including on failure.
Require an explicit no-escape proof for that exclusion; otherwise reject the
classification. This preserves normal failed-construction cleanup instead of
requiring that no allocation of the type can ever be deallocated. Pool return
that only resets and reuses an allocation does not reclaim its storage. Inspect
its actual effects; a pool release that deallocates storage invalidates the proof.

Expose the fact in immutable export contracts before bridge admissibility is
finalized. Recompute it from source/class/archive reconstruction and revalidate
against the final specialized roots and all synthesized cleanup paths before
emission. It cannot suppress an existing unsafe-free diagnostic or remove
source cleanup to make the proof pass. Enum-typed results use the existing
compiler-owned immortal constant category, including constant-specific bodies;
this does not make enum-held heap objects or arrays immortal.

Non-reclaimable facades use a world-level weak-value identity cache keyed by
native object identity, with immutable world identity and no per-object liveness
state, incoming retention count, or generated `free()`. D201 anchors the world
for the JVM lifetime, regardless of facade reachability. Repeated returns of live
pool storage preserve Java wrapper identity; no generation check or snapshot
semantics is added.
This does not guarantee that a released order remains logically usable.

Publication and cycles solely among proved permanent objects require no
reclamation bookkeeping. This exemption concerns only their own storage:
retaining a reclaimable object from a permanent holder still requires the
ordinary proved retention protocol. D196 rejects such retention in the first
release because these permanent facades have no persistent root slot state.
Native field/array safety and all source ownership checks remain mandatory. No global
scan, registry lookup, or lifetime check is added to primitive-only calls.

For the first version, borrowed native storage must remain allocated until its
known owner is freed. If ordinary native methods can free or replace that
storage earlier, reject the borrowed export unless its invalidation is fully
represented. Do not add instrumentation to every native free to repair an
unproved contract. Pool reuse of live storage keeps its existing caller contract;
it does not gain snapshot semantics or per-operation generation tracking.

### 2. Share lifetime state between facades

Use one lifetime state per reclaimable native ownership root. The owner's
facade and borrowed views refer directly to it. Multiple Java aliases naturally
refer to the same wrapper; independently produced wrappers for the same native
object must share the same state through the identity mechanism in section 5.
A borrowed view never owns an independent destruction capability.

Before native access through a reclaimable root, the facade checks that the
receiver's owner and relevant reclaimable native-object arguments are live.
Immortal/non-reclaimable values need no liveness check. An owner `free()`
invalidates the shared state and runs only its approved destruction entry.
A retained Java child wrapper can remain on the Java heap, but using it throws
before native dereference.
There is no scan of Java aliases or child wrappers and no need to wait for GC.
New native allocations must receive new lifetime state even if addresses repeat.

Selected representation: retain the same Java class where it can represent both owned
and borrowed instances. Its generated `free()` is an ownership operation with
an explicit precondition: it rejects borrowed/immortal instances with
the artifact-private `BridgeLifetimeException` subtype of `IllegalStateException`
(D207). Generated documentation identifies ownership on every
constructor/result. This is a contract of the new generated method, not a trap
added to an existing source method. Types that can never be Java-owned need not
expose `free()`. D191 settles this API shape.

A repeated `free()` on an already freed owning facade is a no-op;
ordinary native accesses through it still throw. Use LIVE, FREEING, and FREED
states to prevent reentrant reuse. Do not mark a refusal as FREED, or restore
LIVE after destruction may have started. Generate a destruction capability only
when its complete path is proved nonthrowing and callback-free; section 14
defines refusal and fatal-failure behavior.

**D207: distinguish bridge refusals from producer failures.** Generate a final
`BridgeLifetimeException extends java.lang.IllegalStateException` in the
artifact-unique support namespace. Keep it internal to that artifact; generated
support helpers construct it for dead receiver/argument access and refusal to
free borrowed, immortal, retained or active objects. Consumers may continue to
catch `IllegalStateException`; no new public exception API or checked declaration
is required. Producer exceptions retain their normal translation and must never
be remapped to this subtype. Repeated owner `free()` remains a no-op, and types
without a destruction capability do not gain a `free()` method.

Lifetime tests must check the exact generated exception class through a test-only
support-package helper, not just its superclass, name or message, and verify that
the refused operation's native-entry/destruction counters did not advance. Pair
this with a valid call that reaches a producer-thrown `IllegalStateException`:
it must not have the bridge refusal class. Exception construction remains on the
failure path; failure to allocate the refusal throwable must still prevent native
entry and cannot be reported as a successfully verified lifetime refusal.

### 3. Account for retention between independent owners

If `book.add(order)` retains a Java-owned order, the order cannot be freed until
the book releases it. For supported, compiler-proved retention shapes, generate
explicit dependency edges and a count of incoming native dependencies on the
retained object's ownership root. `order.free()` refuses while that count is
nonzero. Removing/replacing the edge or freeing the retaining owner releases
its count only when native code no longer needs the dependency.

Counts describe native retention, not the number of Java references. Track
multiple holders and repeated retention accurately; a boolean is insufficient.
A retained borrowed child protects its underlying ownership root. The retaining
slot itself must be a field on a reclaimable root, recorded in that root's
persistent host state, never in a weakly cached child facade. Borrowed children
may be retained values but cannot own retention slots in the first release.
Do not look up a global registry on every scalar call.

Start with provable, bounded retained fields and acyclic ownership dependencies.
General containers, cycles, unknown Java callback retention, and unobservable
internal edge changes are not automatically supported. Reject an export when
its exact retention/release protocol cannot be established. Success, exceptions,
partial mutation, constructor rollback, and callback reentrancy must all update
state consistently; a method's name or successful return is not evidence that
an edge was released. Do not guess event deltas from current escape summaries.
D196 adds a separate retention-slot write analysis, started as reusable compiler
code in P0 and completed/integrated in P3 (D211), to establish section 14.B's
entry contracts and reject unaccounted native aliases/publication before export.
D200 requires those counts and slot records to be committed inside the adapter
before Java can resume, including on failure. A Java `finally` block is not a
safe substitute for that completion boundary.

### 4. Prevent destruction of an active native invocation

Same-thread callbacks can attempt `free()` while a native frame still needs the
object. Use an active-use guard for callback-reachable paths, covering the
receiver and all dependent native arguments needed by suspended frames.
`free()` refuses such destruction. Do not instrument every native-only call;
callback-free synchronous paths do not need a concurrent-free guard under D188.
Nested invocations must restore active-use state on normal and exceptional exit.
Unknown reachability cannot be treated as callback-free.

### Accepted outcomes and performance constraints

| Case | Accepted outcome |
| --- | --- |
| Java calls `free()` on an owned independent object | Native destructor and reclamation run on the calling thread. |
| Java calls `free()` on a borrowed order | Reject without destroying it. |
| Java keeps a child view after its owner is freed | Its next native access throws before dereferencing freed storage. |
| Java frees an order retained by another native owner | Reject until the retaining owner releases that dependency. |
| Two Java aliases refer to the same freed owner | Both observe the same dead lifetime state. |
| Java compares, hashes or logs a freed facade using inherited Object methods | Java-only identity behavior remains valid; no native access or liveness check. Source overrides retain their native-access preconditions. |
| A callback tries to free an owner active on the native stack | Reject; preserve the suspended invocation. |
| Producer exports a shape without its required lifetime/retention proof | Compilation fails with a source-located diagnostic. |

The accepted design adds a Java-side liveness check on native access, stored lifetime
state, bookkeeping when supported retention relationships change, and active-use
bookkeeping on callback-capable paths. Identity conversion may also cost a
lookup or initial allocation. D190 approves the stated liveness, retention, and
callback costs at the Java/native boundary. It does not authorize registries on
every scalar call, general native instrumentation, or other unbounded overhead.
Thread misuse stays outside the contract under D188; no thread-enforcement cost
is added. D132/D133 continue to govern ordinary Ironwood execution.

Measure and minimize the approved boundary work; it need not be approved again
merely because it has a cost. Material additional mechanisms or regressions
still require review. Never weaken native reclamation proofs to meet a target.
Verify aliases, dependent views, retention, exceptional rollback, and reentrant
free as paired accepted/rejected cases before advertising reclaimable objects.

## 8. Threading, callbacks, and exceptions

### Threading

**Accepted caller contract, D188:** Ironwood and the Java Bridge are
single-threaded. The application must confine access to one calling thread;
multithreaded access is unsupported misuse and can produce unpredictable results
or crash the JVM. The bridge does not detect, serialize, or repair this misuse.
Do not add owner-thread checks, per-object/world locks, or executor dispatch
solely to enforce the contract. Ordinary Ironwood reclamation proofs remain
mandatory for supported single-threaded execution.

Confinement applies to the loaded native world, including objects, statics,
type initialization, runtime state, and destruction. Two different Java facade
instances backed by the same image are not thereby independent thread-safe
engines. Java application threads that never enter that world are unaffected.
D198's inherited facade `equals`/`hashCode`/`toString` use only immutable Java
identity data and may run on other threads after safe publication, including
asynchronous logging after free. This exception does not cover source overrides
or other methods that enter native code, and adds no thread checks or locks.
Thread handoff, virtual-thread carrier migration, concurrent isolated worlds,
and asynchronous native callbacks are not promised by this plan. Loader
isolation tests are not an authorization to implement multithreading.

Calls execute synchronously on the Java calling thread. Same-thread callbacks
and reentrancy are supported only where the source contract permits them.
A Cleaner thread must not call into the world, even though the application
itself obeys confinement. Generated cleanup must obey the same contract.

### Native stack contract and experiment

**D201:** A synchronous bridge call uses the caller's remaining thread stack.
Native stack exhaustion is potentially process-fatal, not a promised Java
`StackOverflowError`. Callers must leave enough stack for native frames, adapters,
initialization and exception delivery. Do not enter from near-exhausted Java
recursion; producer APIs with input-dependent recursion must document their
bounded workload and stack needs. There is no universal safe recursion count:
frame sizes, optimization, JVM stack size and existing Java depth all matter.
No worker thread, stack switching, signal recovery or hot-path stack check is
added by this plan. Unbounded recursion is outside the supported stack contract.

P0-10 defines a reference test envelope on the three targets at O0/O3.
Run ARM64 cases in P0; D213 schedules real x86-64 execution at the end of P6b:
use a non-tail-recursive Ironwood fixture with live frame data, verify from
disassembly that recursion was not optimized away, and exercise native depths
1, 8, 32 and 64 from Java depths 0 and 64 on an ordinary platform thread with
the JVM's default stack. Each bounded case must return its expected checksum
and translate a deliberate deepest-frame exception, then permit another call.
These are fixture acceptance depths, not a 64-call guarantee for arbitrary code.
Record actual native frame sizes and JVM stack settings; reference tests and
OrderBook must pass without requiring consumer `-Xss` configuration.

In separate disposable child JVMs, repeat with `-Xss512k` and `-Xss1m` and
increase depth to characterize the limit. Record rejected JVM stack settings
and any crash boundary as diagnostic results, never as recovered Java failures
or supported depths. Never deliberately exhaust the agent/application JVM.
P1 verifies the production entry's stack footprint; P6 reruns the bounded cases
on all nine JVM/target cells and publishes the measured envelope and caller
obligation. P5 must extend the experiment for nested callback stack use before
claiming that capability. A failed bounded reference case blocks its milestone;
do not silently solve it with required application flags.

### Callbacks

Generate ordinary Java interfaces and hidden native proxy types. A proxy's
foreign call must exist in typed IR and be visible to escape, ownership,
dispatch, destructor-effect, and reachability analyses. Unknown Java behavior
may retain arguments, throw, allocate, and reenter. Do not infer non-retention
from an interface signature. Reject unsafe exports rather than marking foreign
methods as trusted borrowers.

Start with synchronous callbacks on the calling thread and primitive/value
arguments. Add retained listeners and native-object arguments only after the
lifetime model handles them. JNI local references are call-scoped; retained
listeners need explicit global-reference ownership and cleanup. `JNIEnv*`
belongs to its thread and must not be cached as a transferable global. See the
[JNI design specification](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/design.html).

The private P5 transport binds proxy creation/cleanup to final P0/P3 proofs and
owns a global reference until all registered slots and suspended invocation uses
end. Preparing registration performs identity conversion, including listeners
held only by suspended calls; invoking a registered listener performs no identity
lookup. Preparation failure preserves the old slot. Callback-free listener-field
mutators reuse P0 attribution and protected final snapshots on success/failure.
Removed slot tokens remain owned until all active invocations of their holder
end, including tokens installed during Java reentry. Retirement links are prepared
with the slots before mutation; no allocation or Java call occurs during commit.
These components are not public admission: complete holder lifetime, owner guards
and reconciliation remain required before accepting retained listener signatures.

The private owner invocation proof now binds constructor-only final P0 storage
proofs to exact final classes with primitive/listener fields and empty initial
listener slots. Complete callback closures may read listeners and read/write
primitive fields; listener mutation, independent-root graphs, publication,
ordinary allocation/free and hidden static state reject. Borrowed owner receivers
and arguments become explicit guard obligations on protected entries. Generated
guards cover each proved owner using stable evaluated root-state locals. This
component does not yet compose the public holder adapters or slot reconciliation.

Primitive callback bodies normalize boolean and integral arguments/results to
I64 in typed IR, preserving signed byte/short/int and unsigned char semantics.
Float and double use F32/F64 carriers. Boolean normalization uses ordinary typed
control flow, which LLVM can eliminate. Generated JNI bodies use exact primitive
method descriptors and typed `jvalue` arrays with `Call<Type>MethodA`, checking
pending exceptions before further ordinary JNI work. Reference callback values
remain rejected until their conversion and ownership proofs are implemented.
Canonical typed body matching preserves hidden receiver confinement without
weakening the unknown effects of explicit foreign arguments.

The first synchronous invocation proof accepts static primitive-result entries
with borrowed listener and copied String inputs and a complete native closure. It rejects ordinary
heap/static access, non-exception allocation, reclamation, exception-graph edits
and unknown operations. Cold native fault construction must retain matching P0
constructor-confinement facts. All dispatch alternatives and initialization edges
are inspected. Within this bounded closure there are no native facade owners for
Java reentry to invalidate. Context specialization and protected entries reuse
the existing compiler foundations. The producer now composes this proof with
P0/P3 proxy construction/destruction and invocation-owned carrier cleanup, and
emits ordinary Java interfaces with load-time cached callback method IDs. JNI
local references keep borrowed Java listeners alive through the outer call and
nested calls. Temporary proxies are destroyed on every normal, exceptional and
partial-preparation exit; carrier cleanup follows outward exception translation.
Within one invocation, equal Java listener inputs of the same declared interface
share one proxy and one destruction. JNI identity checks occur during preparation,
never during callback dispatch. Cross-interface or erased listener identity tests
and dynamic listener interface queries remain rejected until their representation
is proved; a single nominal proxy cannot model Java multi-interface membership.
The generated interfaces participate in class-loader validation and generation/API
identity. The current public subset requires top-level nongeneric interfaces with
abstract primitive methods, and static callback-bearing primitive/void exports.
It rejects interface fields/inheritance/default/static methods, stateful holders,
retained listeners, native-object parameters and reference callback values until
their separate admission paths are integrated. Copied String inputs reuse D206's
noncritical JNI buffers and P0/P3 typed copy/cleanup lowering. The bounded proof
admits immutable String length/character reads, with complete helper dispatch and
borrowed-input facts; it does not authorize publishing or freeing those copies.

P5 string-bearing callback tests must retain the outer argument through a Java
callback that allocates, reenters with another string and either returns or
throws. Verify unchanged UTF-16 contents, independent nested buffers and complete
cleanup with `-Xcheck:jni`; D206 permits no critical JNI region around the callback.

Prefer an explicit invocation context passed only through callback-reachable
native paths, with native-only specializations kept unchanged, over a TLS
lookup at every native operation. This requires typed call/dispatch plumbing
and must be included in the performance spike. Do not store a stack context
in a listener that survives the call.

Nested invocations need distinct frames for pending Java throwables, temporary
buffers, and native failures. Never overwrite a suspended outer call's frame.
Default object arguments use stable facades or explicit value snapshots. Reused
callback flyweights that retarget a Java reference are excluded. Preserving a
borrowed facade beyond the callback requires its owner to remain valid; copying
instead must use a separately specified value API, not silently change identity.

Asynchronous callbacks from native-created threads are outside the accepted
scope. Subclass proxies and arbitrary escaping callback graphs remain deferred.
Foreign calls in destructors remain subject to existing closed-world effect
restrictions.

### Exceptions in both directions

**D195: native-to-Java translation is release work in P2/P3.** P1 establishes
native unwinding containment and image-local trace support. P2 must turn native
failures into usable Java exceptions; containment alone does not satisfy its
exit criteria. P5 adds callback-originated Java exception propagation using the
already implemented native-to-Java translator.

On native failure, snapshot the supported throwable data into a normal Java
exception: matching catch hierarchy, message, causes/secondary failures where
representable, and Ironwood source frames followed by the Java call site. Do
not force consumers to close exceptions. First-release custom exception getters
require copyable snapshot data; unsupported shapes are diagnosed.
Copy limits and cycles must preserve a useful bounded failure representation.
Native getters, message/cause traversal and trace extraction obey D197's typed
exception boundary, including its separately protected snapshot stage. Java
exception construction happens only after this native work has returned safely.

P2 implements built-in checked/unchecked exception mappings, matching Java catch
hierarchies and generated `throws` declarations, messages, representable causes
and secondary failures, and Ironwood source-frame snapshots followed by the
Java call site. Its Java consumer tests must catch the expected built-in type
and inspect those values; a generic bridge error or native stderr report is
not an acceptable replacement. Until P3, custom exception exports are diagnosed
as unsupported instead of silently losing their type or data.

D207's lifetime-refusal subtype belongs to facade checks, not this producer
exception mapping. A native producer's `IllegalStateException`, including
OrderBook capacity exhaustion, retains the ordinary Java exception mapping.

P3 generates supported custom Java exception classes and their catch hierarchy,
checked declarations and snapshot-compatible getters. Values remain accessible
after the native call and eligible native cleanup, without a native handle or
generated `free()` on the Java exception. Test custom checked and unchecked
types, superclass catches, getter values and producer rejection of unsupported
getter/data shapes. These exception hierarchies are part of the mandatory
snapshot projection, not the deferred general native-facade inheritance feature.
Reject a hierarchy that would require extending a final mapped Java exception,
including direct or indirect DirectoryIteratorException descendants (D216).
Do not flatten its catch hierarchy or substitute another type.

Determine native ownership independently: fresh translation temporaries can
be reclaimed when proved safe, but stored initialization failures, borrowed
throwables, and emergency occurrences must not be blindly destroyed. Test
repeated failure and out-of-memory translation without recursively allocating
another failing bridge exception. Snapshot translation alone does not promise
that every native thrown object becomes reclaimable. P2 verifies these failure
paths for built-ins; P3 extends them to custom snapshots. Both phases include
bounded cause/cycle handling and translation resource-exhaustion fallback
without recursive exception allocation or unsafe native reclamation.

**P5 only:** After a JNI callback, detect a pending Java exception before making further
ordinary JNI calls. Preserve its identity in the active invocation frame,
clear the pending JNI state only as required for controlled translation, and
raise a typed native foreign-failure carrier if Ironwood must catch it. At the
outer boundary rethrow the original Java throwable when it propagates unchanged.
If native code catches, replaces, or retains it, define carrier ownership and
reference release on every path. Never longjmp across JVM or Ironwood frames.
See [JNI functions](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html).

D227 preserves existing native exception lifetimes: retained callback carriers
and their owned Java global references remain live for the process lifetime.
Unknown retention is retained. Reclaim only compiler-owned carriers proved not
to escape, after all native aliases, unwind and cleanup uses have ended. A native
catch or replacement alone is not sufficient proof. Ordinary source `free` of
caught/thrown objects remains rejected; reclaimable retained exceptions are a
separate ownership extension outside P5.

Callback carriers inside a translated native wrapper's cause or secondary-failure
graph preserve the original Java throwable as an existing graph node. Do not
reconstruct its Java class or overwrite its existing Java cause, suppression or
stack trace. Under D228, a carrier with a native-added cause or secondary failure
becomes a `RuntimeException` wrapper whose cause is the unchanged Java original.
Native secondary snapshots are suppressed on that wrapper. A native-added cause
appears as a labeled `Ironwood native cause` suppressed wrapper, with the native
snapshot as its cause. This distinguishes the two kinds of addition and supports
Java originals with disabled suppression. Retained rethrows create fresh wrappers
without accumulating changes on the original. The same representation applies to
modified carriers embedded in another native graph. Native snapshot nodes retain
the ordinary bounded translation rules. This transport rule grants no native
reclamation permission.

## 9. Values and performance

**Accepted performance goal, D190:** a scalar JNI call can be inexpensive.
Keep primitive-only bridge methods close to the cost of a plain JNI call,
while preserving the accepted ownership checks. This is a design and benchmark
target, not a measured performance claim or a promise of zero overhead.

Separate three costs: the JVM's JNI transition, required value conversion, and
any additional generated bridge work. JNI scalar crossings can be inexpensive;
this plan does not assume a large transition penalty or a fixed nanosecond cost.
However, a native transition is not equivalent to a direct native instruction
or an inlined Java getter. Its importance depends on the work done per call.

For `long getMatchCount()`, the target path is an already-bound JNI call using
an existing object handle, a native scalar result, and only the approved entry
and lifetime obligations. There should be no per-call loading, symbol lookup,
string conversion, wrapper allocation, thread check, or lock. Loading and method
registration are first-use costs, not repeated call costs.

By contrast, passing a Java string can require content access and a valid native
copy; returning a string requires a Java result; returning a native object may
require a facade or identity lookup; a callback crosses the boundary again.
Lifetime-state creation belongs to object creation; explicit `free()` requires
no automatic cleanup queue. These costs are not the bare JNI
transition. Keep that distinction explicit in measurements and API discussions.
The [JNI design specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html)
describes primitive argument transport, managed-object references, and array
copying/pinning separately; it does not guarantee a universal crossing time.

Start with copied strings and primitive scalars. JNI modified UTF-8 is not
ordinary UTF-8; use length-based UTF-16 conversion to preserve NUL and unpaired
surrogates. A Java character buffer is not an Ironwood object with a descriptor.
Pass raw buffer/length descriptors from C and materialize a valid native
representation inside the typed catch-all, or add a typed, bounded borrowed
representation with its own proofs. Never reinterpret arbitrary heap storage
as an existing Ironwood `String`/array object.

**D206: noncritical JNI string access.** Use `GetStringLength` and
`GetStringChars` (the default), or `GetStringRegion` into checked, adapter-owned
storage. Handle null separately; lengths count UTF-16 code units, not bytes or
a terminating NUL. Acquisition/copy failure skips the typed entry and releases
already-acquired buffers. For `GetStringChars`, keep the source `jstring` reference
valid until its matching release. No raw pointer or descriptor may escape the call.
These raw buffers may remain live through target execution and cleanup. Do not
use `GetStringCritical` on this path, or hold a `GetPrimitiveArrayCritical` region
across native initialization, target execution, adapter reconciliation, result/error
materialization or Java callbacks. Arrays remain deferred. The
[JNI critical-access restrictions](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#getstringcritical-releasestringcritical)
make critical access unsuitable for this general call boundary, even before P5.

For copied input strings, a non-retention proof permits temporary cleanup.
Retention requires a native-owned copy with an explicit owner, or a producer
diagnostic. Returned strings are copied to Java, then native storage is
reclaimed only according to its actual ownership. Do not globally intern
arbitrary input to avoid solving cleanup.

For arrays, specify mutation visibility, repeated aliases in one call, overlapping
views, retention, and copy-back on both normal and exceptional exit before
admitting the signature. Otherwise expose a distinctly named copy/batch API in
the producer library or keep the signature unsupported. Copying cannot emulate
arbitrary shared mutable identity.

Measure primitive-only calls without per-call Java/native heap allocation after
warmup. Allocate for actual retained state or required returned values; report
those separately. An object-returning call may need canonicalization and a
first wrapper allocation. A string-returning call ordinarily allocates its Java
result. Do not claim all crossings are allocation-free.

Later optimizations, each behind proof and evidence:

- Batch work through an ordinary coarse-grained producer method first; introduce
  generated batching only if it preserves ordering, results, and exceptions.
- Reuse bounded marshalling storage only with nested-call lifetime handling.
- Evaluate FFM and critical leaf calls. Critical eligibility requires extremely
  short duration on all paths and no Java callbacks; non-retention alone is
  insufficient. Keep it off for allocation, blocking, unbounded work, callbacks,
  and first-use initialization. See
  [Linker.Option](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html).
- Defer direct memory views and field-load inlining until bounded lifetime,
  type initialization, layout versioning, and Java identity semantics are
  established. Do not use an unbounded global segment as a shortcut.

Benchmark bare JNI crossing, scalar instance calls, strings, object returns,
callbacks, exceptions, and complete OrderBook cycles separately. Compare native
standalone, Java implementation, Java-to-native per operation, and a coarse
native batch. Inspect `-O3` native machine code and relevant Java allocation/JIT
behavior. Record cold load time separately from warmed throughput and latency.
Publish results for measured target/JDK pairs only, with no assumed speedup.

## 10. Packaging and automatic loading

Move packaging into the first usable milestone; a loose native library plus
handwritten Java loader is only a private spike.

The bridge jar will contain generated `.class` files, artifact-private
support classes, a versioned bridge manifest, native binaries under
`META-INF/ironwood/native/<target>/`, and applicable notices/source availability.
Also publish generated Java sources and Javadoc for IDE use. Standard Maven
coordinates and a POM suffice for consumers. Avoid a new mandatory global
bridge service or manually installed runtime.

Target identity includes OS, architecture, ABI/libc baseline, pointer width,
endianness, minimum OS, and required CPU features. Start with macOS ARM64,
Linux ARM64, and Linux x86-64, matching existing native coverage. Do not imply
Windows, Android, musl, 32-bit hosts, or every JVM vendor is covered. Build on
appropriate hosts with the pinned toolchain; packaging multiple binaries does
not make the compiler a cross-toolchain distributor.

A host build can produce one-target development jars. A publishing assembly
step combines independently built payloads only when their Java API, bridge
schema and common artifact-generation identity agree. Use one generation
manifest for the platform builds of the same artifact. Its identity must cover
the producing artifact and complete program generation, not merely public API
signatures or an individual shared type's source. Distinct artifact generations
must not reuse an identity just because their public APIs match. Distinguish
this identity and a logical API/schema identity from each native build's
identity and byte digest. A macOS binary and Linux binary need not have the same
binary hash. Record compiler/runtime versions, transport, exported signature
descriptors, native build identity, platform constraints, and dependency/license
inventory. Define the build identity without a self-referential embedded digest.

Version Java API compatibility separately from this private native ABI. Replacing
an implementation means replacing the paired jar; do not support swapping an
arbitrary same-named shared library beneath old facades. Verify Java binary
compatibility for updates that claim it, including overloads, descriptors,
hierarchies, checked signatures, enum names, and generated support isolation.

Loader sequence:

1. Check `Runtime.version().feature()` in Java; admit only 21, 22 and 23.
   Refuse Java 24+ before resource extraction, `System.load` or native bootstrap.
2. Resolve the resource from the facade's defining loader and identify the host.
3. Select a compatible payload; report available targets when none matches.
4. Extract to a private, versioned location with safe filenames, restrictive
   permissions, atomic creation, and payload integrity checks. Handle concurrent
   JVMs and stale partial files. A digest checks consistency, not publisher trust.
5. Load the exact absolute path and validate bootstrap schema/API/build identity.
   Preflight all package markers and resolved generated classes before any
   native registration; only then bind the checked classes and publish the
   artifact as ready. This cannot sandbox malicious native payloads.
6. Initialize artifact-private support once, without eager source-class effects.
7. Keep the successfully bound defining loader and image alive for the JVM's
   lifetime as specified below. Freeing every object does not release that binding.

**D203: current baseline, subject to D209's product review.** Unsupported JVM
versions fail at first native use. The version guard
throws `UnsatisfiedLinkError` identifying the artifact, detected full runtime
version and supported range (Java 21-23). There is no consumer bypass or native-access flag
workaround. Keep this check in one-time loading, not warmed method calls. Pure
Java enum initialization and type inspection need not load the bridge or fail
this check. Java versions below 21 normally reject the facade class-file version
before this guard can run; no custom diagnostic is promised there.

D203 is stricter than the documented default native-access policy of
[Java 24](https://docs.oracle.com/en/java/javase/24/docs/specs/man/java.html) and
[Java 25](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html), which
warn rather than deny by default. It is a product choice, not an unavoidable JVM
requirement. D209's separate experimental artifact may admit exactly Java 25 to
evaluate that choice; the ordinary artifact retains this guard pending review.

### Class identity and registration preflight

Every generated facade, enum, exception and support class carries immutable
artifact-generation identity metadata in a runtime-visible generated annotation.
Its annotation type lives in the artifact-private support namespace. Each owned
API package contains the reserved package marker with that same identity.
The native payload has the
expected identities, binary names and registration descriptors from the same
generation manifest. Validate the actual resolved `Class` objects and their
defining loader, not just a same-named class-path resource or Java constants
inlined into another class. JVM-provided mapped types are outside this check.

Resolve the complete class and package-marker set through the artifact's
defining loader. Require the expected loader and identity for every item;
verify declared native signatures against the registration descriptors as well.
Missing metadata or any mismatch fails bootstrap with a `LinkageError` naming
the package/type and expected versus observed artifact. Perform this complete
preflight before the first `RegisterNatives` call, including classes whose
methods will only be used later. A package marker catches split ownership even
without a duplicated public class; per-class checks additionally catch mixed
or stale classes inside an otherwise correctly marked package.

Resolve classes without initialization and inspect their declared identity
annotations without invoking facade methods or reading static fields. Preflight
must not run Ironwood initialization or recursively enter native registration;
preserve the existing active-use contract after successful binding. JNI
static-field lookup can initialize a Java class, so a generated constant field
is not the metadata access mechanism.
See [JNI static-field lookup](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#getstaticfieldid)
and [native registration](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#registernatives).

Register methods only on the validated class objects. Do not look them up
again by name and accept a different class. Bootstrap is idempotent for the
same paired artifact; never rebind another artifact's classes. A collision must
perform no registration or unregistration and leave an already loaded artifact
usable. If registration itself fails after successful preflight, keep the new
artifact unavailable and restrict any cleanup to its own registrations. No
partially bound facade may invoke native operations. All these checks are
load-time work; scalar calls acquire no package registry or identity lookup.

Classloader behavior is part of the release gate. JNI associates loaded native
libraries with classloaders and rejects loading the same library into multiple
loaders. D191 selects one defining loader per artifact per JVM; diagnose a
second independent load before user-native initialization. Extract identical
payloads to the same canonical artifact location, not a new image per loader.
Isolated duplicate worlds and hot reload are deferred. Never accidentally share a process-global Java singleton
between unrelated bridge artifacts. See the
[JNI invocation specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/invocation.html).

**D201: pin the first successful binding.** A library-local flag only survives
while its image remains mapped, so it cannot alone enforce once-per-JVM binding
after full unloading. Before user-native initialization, successful bootstrap
retains a strong JNI global reference to its defining classloader for the rest
of the JVM lifetime. Allocate this anchor before publishing readiness; failure
leaves the artifact unusable and executes no source initialization. It is an
intentional per-artifact lifetime cost, not a reference released on `free()` or GC.

Also retain an image-local bound flag: `JNI_OnLoad` refuses a new load of an
already bound mapped image, and explicit bootstrap refuses a different loader
before registration or native initialization. Repeated bootstrap by the original
loader stays idempotent. Never reset the binding in `JNI_OnUnload`, run Ironwood
destructors from it, or treat it as native-world reinitialization. The anchor
prevents supported loader collection/unloading; the flag rejects stale-image
rebinding if the loader hook is nevertheless reached. No same-process reload is
promised, even when no facades remain. P0/P2 verify the anchor and repeat-load
refusal after dropping application references and requesting GC; a test-only
retained-OS-image harness verifies the bound-flag rejection independently.

### Native dependency experiment

P1 must inspect the actual shared payload, not infer its dependencies from the
JVM or the existing executable audit. `NativeBackend` currently uses the C++
driver and LLVM emission references `__gxx_personality_v0`; `test-idk.sh` checks
GLIBC versions but does not establish the bridge's full dependency closure.
Do not assume a JVM's internally linked C++ runtime exports what this image needs.

**D202: eager ELF binding.** Link Linux bridge payloads with `-Wl,-z,now` and
verify `BIND_NOW`/`NOW` in the final ELF dynamic flags. This requests symbol
resolution at load time, as specified by the [linker option](https://sourceware.org/binutils/docs/ld/Options.html).
P1 adds a fixture with a required function symbol missing from its load-time
dependency: `System.load` must report a catchable `UnsatisfiedLinkError` before
any source entry runs, and the child JVM must continue. Use a non-weak unresolved
relocation, not a deferred `dlsym` lookup; eager binding does not validate every
possible later manual symbol lookup. Repeat on both Linux targets and inspect
the packaged payload in P6. This resolves symbols eagerly, not Ironwood class
initialization, and requires no application flags. Keep it scoped to Linux bridge
links; do not change ordinary executable or macOS linking as part of this rule.

For both Linux targets, record `llvm-readelf --dynamic` (`DT_NEEDED`, RPATH/RUNPATH)
and `--version-info`, including GLIBC, GLIBCXX, CXXABI and GCC symbol requirements.
Resolve and inventory transitive dependencies, especially `libstdc++.so.6` and
`libgcc_s.so.1`. In a disposable minimal runtime environment with the chosen JVM
but no extra compiler-runtime packages, run a scalar call and a thrown/caught
native exception at O0/O3. Record which libraries the base JVM image actually
supplies; absence testing must not be inferred from a full development machine.

If additional runtime libraries are needed, P1 must select and demonstrate an
automatic packaging solution: legally redistributable private dependencies with
relative loader paths, or suitable static support with unwind/isolation evidence.
Do not require users to install system C++ packages or set library-path variables.
Prove exception delivery and two-artifact isolation after changing the link;
do not simply remove the C++ driver or statically link an unwinder without tests.
An unresolved dependency or unreviewed redistribution obligation blocks P1.
P6 repeats this audit/load test on the final Linux payloads and includes required
notices/source; macOS gets the analogous dylib dependency/install-name audit
plus D210's signature and extraction checks below.

**D210: macOS signing and extracted-library loading, starting in P1.** Use the
P1 private JNI harness and representative ARM64 dylib at O0/O3. A small fixture
jar must carry and extract the payload to a private location using the proposed
byte-copy/atomic-publication convention; this does not wait for P2's production
generator or loader. Provision the three supported macOS Temurin launchers in
P1: 21.0.12.1+1, 22.0.2+9 and 23.0.2+7, with D205's recorded hashes and paths.

- Finalize link, stripping and install-name/dependency edits before signing and
  computing the packaged payload digest. Record whether the linker supplied a
  valid final ad-hoc signature or the producer needed an explicit signing step.
  Do not assume the existing executable link path proves dylib signing behavior.
- Inspect the final dylib and extracted copy with `codesign --display --verbose=4`
  and verify both with `codesign --verify --strict --verbose=2`. Record signature
  type, identifier/CDHash, architecture, SHA-256 and command statuses. Require
  byte-identical payloads and the same valid ad-hoc signature after extraction.
  Verify byte identity and each recorded signature type for privately packaged
  dylib dependencies too, if any. See Apple's
  [code-signing verification guidance](https://developer.apple.com/library/archive/technotes/tn2206/_index.html).
- In a fresh subprocess for each of the three unmodified launchers and each
  optimization level, load the extracted absolute path, run a scalar call and
  contain a native exception; then make a successful call and exit normally.
  Run with ordinary launch settings and repeat with `-Xcheck:jni`. Record macOS
  build, launcher path/version/signature/entitlements, extraction path/attributes,
  stderr and exit status. Signature verification alone does not establish load
  compatibility with a launcher. Do not re-sign the JDK, change its entitlements,
  strip quarantine attributes or require consumer-side signing to make it pass.

A missing/invalid signature, changed payload, load rejection or crash blocks P1
until a producer-side solution is demonstrated and documented. This experiment
does not establish Developer ID/notarization or arbitrary custom-launcher support.
P2 repeats extraction/loading through the generated jar/loader; D209's Java 25
probe records the same signature/load evidence for its experimental artifact.
P6 repeats the audit and load cases on the final distributed macOS payload under
every selected supported launcher. No production signing or provisioning occurs
during this documentation update.

Hide or uniquely scope runtime symbols and inspect exported symbols on every
target. Audit linked unwind/C++ support and optional host/TCP/TLS dependencies
for interposition, unresolved libraries, and per-image state. Two libraries
containing similarly named native-only source types must not share native
descriptors. Shared generated parameter types and Java-implemented interfaces
are outside the first release. Disjoint generated packages, one loader/world
per artifact and registration preflight prevent cross-world public arguments.
Keep world identity for internal handle validation; do not invent a public
wrong-world call that the supported Java types cannot express. P5 interfaces
or any future shared-type design need their own world-validation contract.

Test plain class path, module path, executable jars with standard dependency
loading, two different artifacts, and refusal of duplicate defining loaders.
Use disjoint packages for the successful two-artifact case. Add class-path
negative cases with a duplicated `Money` class and with different public types
in one shared package. Exercise both class-path orders and both first-use orders;
trigger each bootstrap through an artifact-specific class, assert failure before
any conflicting registration, and verify an already usable artifact still works.
Also reject a mixed-generation facade despite a matching package marker. On the
module path, require the shared-package application to fail resolution/layer
creation before native entry; no bridge-specific diagnostic is promised when
the JVM rejects the [module configuration](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/module/Configuration.html) first.
Custom nested/fat-jar loaders and relocated/shaded facades are unsupported in
the first release. Test extraction failure, read-only/noexec
locations, paths with spaces, checksum mismatch, and missing symbols. Java 24+
native-access support tests are deferred except for D209's P2 Java 25 experiment.
Unsupported-version refusal remains required under the current P2/P6 baseline.
Offer advanced location overrides only
for real deployment constraints; ordinary use must require none.

Generated payloads carry obligations from reachable standard-library and native
dependencies. Follow [LICENSE_MECHANICS](LICENSE_MECHANICS), including source
availability and notices for covered portions. The source facade's license
does not determine the native binary's complete distribution obligations.

## 11. The OrderBook acceptance track

Use [the current project](../projects/OrderBook/README.md) as evidence, not the
larger listener-based API imagined in the older proposal.

- It exposes `OrderBook(int, int)`, `createLimit(long, Side, long, long)`,
  `createMarket`, primitive getters, and pooled `Order.reduceTo`/`cancel`.
- `createLimit` can return an order already returned to its pool after a complete
  match. The source contract only permits active order handles while resting.
- The pool graph has intentional process lifetime and no complete destructor
  contract today. A bridge cannot synthesize deep cleanup by following arbitrary
  fields and claim it is equivalent to an accepted `free`.
- Its paired Java engine is useful for differential behavior and benchmarks.
  It does not establish callback, cleanup, or arbitrary Java-API compatibility.

Build a dedicated producer class directory from the engine's `OrderBook`,
`Order`, and `PriceLevel` sources and their dependencies. The package also
contains demonstration and benchmark entry classes; do not accidentally make
them library exports by packaging the entire benchmark output directory.
This selects the producer artifact without changing engine packages or writing
a second API by hand.

First reproduce primitive book observations through a generated jar. Then
support stable native identity for returned pool storage, with the same logical
checkout obligations as the source API. Do not retarget a callback wrapper to
a different storage object. Native memory safety and stale pooled-item business
misuse are distinct contracts; avoid adding generation registries solely to
police the latter without a separate requirement and cost review.

P3 must deliver section 7's non-reclamation proof before P4. In the dedicated
engine closure, classify successfully exposed `OrderBook` and pooled `Order`
storage as non-reclaimable; prove the lifetime of the internal `PriceLevel` and
array storage as well. `createLimit` then needs no fabricated owner origin, and
the receiver publication performed by `cancel`/`reduceTo` does not by itself
reject the export. Their pool operations reset and reinsert objects without
deallocating storage. Enum results use the immortal category. This is a required
analysis deliverable, not an existing compiler capability or a project exemption.

Before claiming reclaimable OrderBook support, either prove and implement an
explicit ownership/destructor change as separately reviewed producer work, or
use a dedicated bridge fixture with a provable graph. Preserve the current
benchmark's intentional lifetime and source equivalence. If the analyzer cannot
establish boundary safety for the existing project, report that exact gap and
do not grant project-name-based exemptions.

For acceptance, run the paired scenario, verify price-time ordering, fills,
reductions, cancellations, match count/volume, final maker ID, and full pool
recovery. No public method of the actual API takes an `Order`; put retained-order
and cross-owner argument misuse cases in the separate reclaimable fixture with
explicit retaining/releasing methods. Do not call package-private engine methods
from the Java acceptance test or invent public methods for this purpose.
A separate small listener fixture exercises callbacks
without silently adding them to the benchmark engine.

### P4 allocation acceptance

Use the same bounded, preallocated OrderBook workload as the native comparison.
Measure native allocation-count deltas separately from Java thread-allocated
bytes and facade/cache allocation counts. Perform class initialization, pool and
cache sizing, warmup, logging and measurement setup outside the measured loop.
Do not change the weak-value identity-cache policy merely to satisfy a benchmark.

| Case | Required result |
| --- | --- |
| Warm scalar access | Repeated primitive getters and operations on already-held facades add zero native allocations and zero Java allocated bytes in the measured loop. No identity lookup or liveness bookkeeping is added to permanent scalar calls. |
| Warm object returns with live facades | Exercise every returned pool identity during warmup and keep its facade strongly reachable through the measured loop. Subsequent cache hits preserve `==`, allocate zero Java bytes (including lookup keys/cache bookkeeping) and zero native objects. Retain wrappers for identity only; do not use released orders as active business handles. |
| First conversion or return after facade GC | A cache miss may allocate exactly one facade and the cache's documented per-entry support objects. Native storage is reused without allocation. Once that facade is strongly held, further returns are allocation-free hits. This case is not advertised as zero Java allocation. |
| Repeated facade collection/recreation | Observe cleared weak references or reference-queue delivery before testing recreation; a GC request alone is not evidence. Cache records must be removed/replaced, not accumulated per incarnation: after maintenance, entries do not exceed the distinct native identities exercised. Failure to observe collection within a bounded test run is inconclusive and cannot pass this case. |

Record raw before/after counters and the exact cache-miss support-object budget
from the implementation. Test instrumentation stays out of production scalar
paths; verify zero-allocation hits with Java byte counters as well as facade
counts so temporary lookup allocations cannot hide. Exclude only harness work,
not bridge work, from the measured region. An unavailable counter or unexplained
positive delta is not a pass. Timing acceptance remains in P6.

## 12. Implementation phases and exit criteria

Each phase should be split into focused commits. Do not start a later safety-
dependent phase while its contract is unresolved. Test names below for new
bridge behavior are proposed, not existing commands.

**D212: retain phase IDs, clarify execution order.** The first-release sequence
is **P0 -> P1 -> P2 -> P3 -> P4 -> P6**. The table follows that order; P5 and P7
remain separately authorized extensions. Use the submilestones below to size
implementation tasks and review progress without renumbering accepted decisions
or weakening a phase's exit criteria. A submilestone is not a separate release.

| Phase | Work and concrete deliverable | Exit criteria |
| --- | --- | --- |
| P0: validation and compiler foundations | Prepare D205 host access, pinned Temurin images and focused hardware invocation; establish the shared root/contract model and build D211's reusable analysis modules and the focused platform/adapter experiments in the P0 validation checklist below: JNI/typed entry, image traces, registration, enum initialization, retention, allocation failure, identity, non-reclamation proofs and the bounded stack envelope. Implementation and experiments are a later authorized task. | D205 preparation and D211 reusable modules/contracts/paired regressions are delivered; P0-1 through P0-10 meet their implementation gates with the specified O0/O3 evidence, except that D213 records x86-64 hardware stack checks as pending P6b. ARM64 hardware checks, x86-64 functional/static checks and all compiler proofs remain required. Other missing or inconclusive cases block completion. No numerical timing threshold; multithreaded native misuse remains outside the contract. |
| P1: multi-root native library foundation | Integrate the P0 root/entry model into the production output kind, multi-root optimizer propagation, shared linking, visibility, bootstrap and image-local traces. Use scalar static entries and a private host harness. | No main required; callable reachable only from Java retained; unreachable code pruned; first-use and failed initialization correct; catch-all covers all potentially raising native entry work with a valid allocation-failure context. Private harness verifies allocation-limit failures return status at O0/O3; inspect unwind edges and production stack footprint. Both Linux payloads pass the DT_NEEDED/version-closure audit and minimal-JVM load/throw experiment without extra system-runtime installation; dependency packaging is resolved. Final ELF flags show eager binding and a missing required relocation symbol yields catchable load failure before source execution. D210 macOS signature-preservation and extracted-load checks pass at O0/O3 under all three pinned supported Temurin launchers. |
| P2: first plug-and-play jar | Extend the shared model with deterministic package/signature discovery, exclusive packages and generation identity; emit Java 21 source/classes, generated JNI, complete registration preflight, loader with JVM version guard, manifest pairing and a macOS ARM64 preview jar with required notices/source availability. Static primitives and copied strings where cleanup is proved; built-in exception mapping and trace snapshots. | Plain Java 21-23 consumer runs without native tools, flags or manual loading; signature types outside exports diagnosed; disjoint artifacts work; colliding packages/classes fail before any rebinding and preserve an already usable artifact; platform/build/extraction errors remain actionable. D203 version-predicate checks and the Java 24 refusal smoke test pass before extraction/native loading. Permanent loader anchoring and mapped-image rebinding refusal pass the GC/reload fixture. Java catches expected built-in checked/unchecked types with correct declarations, messages, representable causes/secondary failures and Ironwood frames; initializer/repeated failures and translation exhaustion are tested. Under `IRONWOOD_ALLOCATION_LIMIT`, string argument conversion raises Java `OutOfMemoryError` before target effects; result/snapshot failures return safely and the child JVM continues. D206 API inspection and normal/exceptional/partial-acquisition string-buffer cleanup checks pass. D210 signature/extraction/load checks repeat through the generated macOS jar/loader. D209 Java 25 default-policy comparison and recommendation are recorded, including any failures. |
| P3: object and lifetime model | Constructors, identity, Java-only inherited Object methods with immutable facade metadata, concrete facades/static nested types/enums with typed initialization-before-conversion, owner/dependent enforcement, explicit `free()`, failure rollback, mandatory native root-index capacity/global-reference preallocation, nonthrowing adapter commit, generated signature validation; extend/integrate D211's P0 retention-slot and non-reclamation analyses, implement root-only persistent slot records and world-level identity caching; generate custom exception classes, hierarchy and snapshot getters. | D211's reused analyses cover the admitted production surface, preserve P0 regressions and cannot be disabled to bypass export proof. Mixed fresh/existing reclaimable result origins fail producer build; nullable single-ownership and uniformly permanent results retain their supported behavior. D207 lifetime-refusal type/counter checks and producer-error controls pass. Reclaimable aliases remain safe; root-slot writes/clears reconcile on success and failure, including helper writes to known argument roots; copied/moved slot values, child-held slots and unknown owner/effect cases fail export. Counts survive facade GC; cleanup verified. Reservation failure prevents native execution; count/slot commits finish before any Java error delivery, including after store-then-throw. Post-return StackOverflowError/facade-allocation failure cannot expose undercounts or unregistered roots. D204 weak-cache insertion failure and collection preserve the indexed state on re-exposure; eligible destruction occurs once, and index/global-reference cleanup passes. Inherited equality/hash/text stay stable after free, hash-collection removal works, and asynchronous logging performs no native entry; source overrides keep liveness/confinement requirements. Cold enum receiver/argument calls and initializer failure pass before P4. P2 collision checks cover object facades as well; valid same-world arguments work, with no fabricated public cross-world case. Permanent pooled returns and receiver publication pass without fabricated ownership; reachable reclamation or unknown deallocation effects fail the permanent proof. Source/class/archive results agree. Custom checked/unchecked declarations, superclass catches and getter values pass Java consumer tests; unsupported projections fail producer build and snapshots remain valid after eligible native cleanup. Throwing/allocating custom getters stay inside protected snapshot extraction and exercise its bounded fallback. |
| P4: current OrderBook | Apply P3's non-reclamation proof to the dedicated engine closure; generate its actual API including nested enums and pooled orders; run the paired workload and section 11's P4 allocation acceptance cases. | `createLimit`, `cancel` and `reduceTo` export successfully under the proved permanent-storage contract; consumer imports actual classes without glue; correctness matches; D207 capacity-exhaustion controls remain producer exceptions, not bridge refusals; warmed scalar/cache-hit loops have zero native and Java allocations with strongly held facades, and weak-cache recreation meets the separate miss/collection criteria. No liveness bookkeeping is added to permanent scalar calls. Retention/cross-owner argument tests use the separate reclaimable fixture. Timing acceptance is deferred to P6. |
| P6: distribution and final release readiness | Multi-target assembly, final classloader/module qualification, producer Maven/Gradle conventions, sources/Javadoc, license/source payloads, deployment diagnostics, final performance measurements. | Before starting, D209's product decision and any revised guard/tests/matrix are recorded. Clean consumer machines need only supported Java and dependency; all nine pinned Temurin/target cells below pass their focused checks on matching hardware under D205, including diagnostic and flag-free launches; the separate Java 24 refusal test passes; package content reproducible and reviewed; D210 macOS signature/load checks pass on final payloads; final numerical performance acceptance recorded. |
| P5: authorized callbacks and Java exception propagation, in progress | Typed foreign calls/proxies, conservative effects, retained listener lifecycle, nested invocation contexts and callback-originated Java throwable propagation. Reuse P2/P3 native-to-Java translation. | Listener works as a Java interface; reentrancy, retained arguments and callback-triggered free tested; unchanged callback throwables preserve Java identity through nested calls, with carrier cleanup on catch/replace/retain paths; neither runtime unwinds across the boundary. D206 string-bearing callbacks allocate, reenter and throw without critical-region violations or leaked outer/nested buffers. |
| P7: deferred measured optimization and API expansion | Evaluate FFM, bounded zero-copy, batching, additional arrays/generics based on real workload needs. | Each extension has a compatibility/proof contract, focused tests, allocation evidence, and machine-code/benchmark justification. |

P2 is a usable scalar preview, not completion of the requested object feature.
The initial object implementation followed P0 -> P1 -> P2 -> P3 -> P4 -> P6.
D226 now authorizes P5; P7 requires separate implementation authorization. The
maintainer intends to evaluate both before considering a candidate. P1 contains native
unwinding; P2 maps built-in exceptions and traces; P3 maps custom exceptions and
supported getters. P5 adds callback-originated Java failure propagation.
Incomplete native-to-Java
translation or lifetime implementation is a release blocker,
not a documentation caveat. D191 settles section 14's contracts; implementation
remains a separate task.

### Execution checkpoints and handoffs (D212)

The detailed phase exits and P0 case list remain authoritative. These checkpoints
assign their work in dependency order; they add no new experiment or supported
API. P0-1 through P0-10 identify evidence cases, not an implementation sequence.

| Checkpoint | Deliverable and completion evidence |
| --- | --- |
| P0a: preparation and shared contracts | Record D205 host/JDK access and establish the minimal internal export-root/contract model: resolved callable/type identities, source spans, ABI descriptors and immutable proof results. Explicit fixture roots feed this model without a public export-discovery command. Its focused tests distinguish unknown/rejected facts from accepted contracts and preserve reconstructed-input identities. |
| P0b: compiler proofs and native fixtures | Implement D211's reusable retention/non-reclamation analyses and P0's limited typed-entry/shared-image harness using that model. Produce real P0-5/P0-8 proofs, including actual OrderBook rollback; use the harness for containment, initialization, registration, identity and commit experiments. Proof results authorize fixture capabilities; handwritten bindings never replace proofs. Keep analysis-only reporting separate from the executable harness. |
| P0c: feasibility gate | Review all ten P0 cases under D213: complete compiler proofs, functional/static checks and ARM64 hardware stack probes; record x86-64 hardware probes as pending final qualification. Record which compiler, typed-entry and runtime mechanisms carry forward and which fixed test bindings will be replaced. Only D213's x86-64 hardware evidence may remain pending when P0 closes for implementation. |
| P3a: production object admission | Extend the P0 modules to the complete admitted closure and revalidate specialized/generated roots. Wire immutable ownership, non-reclamation, retention-slot and destruction contracts into export validation. Paired safe/unsafe cases and source/class/archive parity pass before dependent adapters can admit those shapes. |
| P3b: permanent objects and value projections | Generate proved non-reclaimable concrete facades, static nested types, weak identity caching and Java-only inherited identity methods; implement enum initialization/conversion and custom exception snapshots/hierarchies/getters. Cold enum, repeated identity, custom catch/getter and snapshot-failure tests pass through generated jars. General native-facade inheritance remains deferred. |
| P3c: reclaimable roots and views | Implement bounded root preallocation, native index/global references, shared Java state, owner/borrowed identity, complete adapter commit and proved nonthrowing free/rollback. Pass refusal, address-reuse, post-free identity, reservation/cache-delivery failure and exact destruction tests. Independent-root retention exports remain rejected until P3d; no partial lifetime protocol is exposed. |
| P3d: retention and combined safety gate | Enable proved root-only slots and integrate actual normal/exceptional deltas with P3c's commit. Pass store-then-throw, aliased holders, dependency release, facade-GC and interruption cases; reject slot transfers, child slots, unknown effects and cycles. Run the combined P3 acceptance cases, including object collision checks and custom exceptions after mutation. All P3 exits must pass before P4 acceptance. |
| P6a: distribution candidate | After P4 and D209's recorded product decision, assemble matching multi-target payloads; complete producer Maven/Gradle integration, source/Javadoc/notices, deployment documentation and reproducibility checks. Verify identity mismatch rejection during assembly and inspect each final signed/dependency-complete payload. Record the candidate inputs and hashes for qualification. |
| P6b: release qualification | Run the selected supported hardware/JDK matrix against that candidate, including final loading, exceptions, lifetime, OrderBook allocation and stack cases, plus required version-refusal tests. Schedule D213's x86-64 hardware handoff last, including deferred P0-10 checks; review numerical performance after all targets have evidence. A changed candidate requires the affected evidence to be refreshed; no unsupported or skipped cell becomes a pass. |

P0 introduces the shared model because its analyses and P1's roots already need
it. P1 integrates the validated typed entries, image bootstrap and root model
into the production final-link/optimizer pipeline. P2 adds deterministic package
discovery, full signature validation, artifact identity and Java/JNI generation
to that same model. Do not defer the model's first definition to P2, or maintain
different ownership/ABI interpretations in the prototype and generators.
P0 need not deliver general package discovery or the complete production linker.

P1 completes the production native foundation on all three targets and its
dependency/signature experiments before P2 packaging. P2's first generated jar
targets macOS ARM64, matching D210 and D209's required launcher evidence; its
Java 21/22/23 consumers cover class path, module path and ordinary executable-jar
launches. Include required notices/source availability and usage instructions
with that preview. P6 expands assembly and verifies these existing loading
contracts across the final target/JDK matrix; it must not be the first module-path
or classloader implementation. Linux Temurin 22/23 provisioning remains in P6;
P2's one-target preview is not a claim of full release-matrix coverage.

OrderBook fixture preparation and an early generated-API smoke test can begin
after P3b, but neither substitutes for P3c/P3d or closes P4. Reclaimable ownership
needs its separate fixture even though OrderBook's graph is permanent. P4 remains
the complete workload/correctness/allocation gate after all of P3.

Carry case IDs and expected assertions from P0 into production regression tests.
P1 reruns the native/root/initialization/containment cases its implementation
changes; P2 tests the actual generator/loader and built-in conversions; P3 tests
the generated object protocols; P4 adds the real workload. Record the responsible
test and artifact at each handoff. Prototype results cannot stand in for production
results. Use focused checks for changed paths, then P6's specified final matrix;
this is not authorization for full-suite runs after each checkpoint.

### P0 validation checklist

**D199/D211:** P0 combines bounded platform/adapter experiments with the first
implementation of the P3 compiler analyses. P0-5 and P0-8 are real compiler work,
not throwaway prototypes or the full export generator/packager. Fixed JNI test
bindings are acceptable; handwritten analysis answers or fabricated ownership
facts are not. P1-P3 carry the validated code forward and repeat the cases against
production-generated artifacts. P0's proof checkpoints still precede P1.

#### Reusable analysis foundation (D211)

- Implement retention-slot write analysis and closed-world non-reclamation analysis
  in focused compiler semantic modules, using resolved typed calls, existing
  escape/ownership facts and explicit bridge roots. Return immutable contracts
  and source-based diagnostics through a shared compiler-facing interface that
  P3 will consume. Preserve unknown/rejected results; no fixture-specific safe
  answers or disconnected second analysis implementation is allowed.
- Expose this work through an internal analysis-only compiler/test option,
  disabled for ordinary builds. It selects the additional analysis and reports
  for P0 fixtures; it does not emit public bridge artifacts. The option cannot
  bypass ordinary reclamation checks, convert unknown effects to safe facts or
  make unproved exports executable. By P3, bridge builds invoke the required
  analyses automatically; disabling them must reject export, never skip a proof.
  This is not a Java consumer option or a new public CLI compatibility promise.
- P0 delivers the reusable modules, contract interface and paired safe/unsafe
  regressions for the exact P0-5/P0-8 cases, including actual OrderBook rollback
  and source/class/archive proof parity. P0 passes only when those cases have
  their existing evidence; compiling a skeleton or reporting unknown for a
  required positive case is insufficient. Unsupported cases outside the current
  scope stay conservative. Record remaining coverage/integration work for P3.
- P3 extends the same modules to the full admitted export surface, connects the
  production export model/diagnostics, revalidates specialized generated roots
  and uses the contracts in adapters. Retain the P0 regressions and add production
  tests; do not schedule a rewrite of an equivalent prototype. Keep temporary
  JNI harnesses separate from these durable compiler modules.
- Before changing shared analysis, apply section 13's pre-change review. Compare
  focused ordinary-program diagnostics/lowering with the option off and on,
  excluding only the additional analysis reports. Existing safe/unsafe-free
  results must agree in every unfreed mode; no runtime bookkeeping is added to
  native-only code. Use the focused regression selection, not a full suite.

Estimate P0 platform experiments and compiler foundations separately. The fixture
set bounds the initial analysis deliverable, not its implementation effort. Missing
proofs remain P0 blockers; do not hide unfinished analysis in P3 while declaring
the architecture validated. This schedules future implementation, not work to
execute during the current documentation review.

#### Final x86-64 hardware qualification (D213)

Develop on the ARM64 Mac and Linux ARM64 VM. Keep the permitted x86-64 Rosetta
functional checks and target-binary disassembly in their original phases;
they can catch portability defects early without access to a Linux hardware host.
P1-P4 may likewise use labeled Rosetta evidence for bounded x86-64 functional
checks, including P1's Linux dependency/load tests. This allowance establishes
neither native stack limits nor release allocation/performance results.

Move real Linux x86-64 hardware execution to the final P6b handoff: P0-10's
O0/O3 stack envelope and limit probes, plus the three pinned x86-64 release cells
and their final dependency, loading, safety, OrderBook, allocation and timing
checks. Prepare these tests during implementation. Record each deferred item as
`pending x86-64 hardware`, with its case, configuration and runnable command.
P0 can close for implementation with this explicit qualification outstanding;
P1-P4, P6a and ARM64 P6b work may proceed. All compiler proofs and other phase
checks still apply. A known x86-64 failure is not an unavailable-hardware waiver:
diagnose it and block affected work until corrected.

This accepts the risk of discovering hardware-specific defects late; it does
not remove x86-64 support requirements or label translated tests hardware passes.
P6b and the release remain incomplete until the hardware cases pass. If they
require code changes, rebuild affected payloads and refresh affected evidence
on other targets as well. Final performance review follows the hardware results.

Before the handoff, provide a focused runner and short instructions for the
maintainer's Linux x86-64 host: prerequisites, pinned JDK/image setup, exact
revision and payload hashes, commands and expected results. Capture environment,
logs, statuses, allocation/timing data and disassembly in an evidence bundle.
The maintainer may run it manually, or authorize execution through an available
SSH connection. Both use the same cases and evidence rules; a shell connection
alone does not establish the host architecture or pass a test. No SSH access,
credentials, remote installation or paid infrastructure is assumed by this plan.

#### P0 host and JDK prerequisites (D205)

Complete and record this setup checklist before collecting P0 gate evidence.
This is preparation for the ten cases, not an eleventh experiment:

1. Identify the macOS ARM64 host and Linux ARM64 host/VM for P0. Record the
   intended Linux x86-64 hardware host for final P6b validation if known; access
   to it is not a prerequisite for implementation under D213. Linux ARM64 in
   Colima on Apple Silicon is acceptable same-architecture virtualization.
   Linux x86-64 under Rosetta is
   translated execution and cannot substitute for the hardware-required rows
   below. A Linux VM/container on a physical x86-64 host is acceptable if its CPU
   instructions execute without architecture translation. Missing ARM64 hardware
   evidence still blocks P0.
   Missing x86-64 hardware access is recorded for P6b and does not block P0-P4
   or P6a; it blocks final release qualification.
2. Add a bridge-specific preparation layer to the Linux platform images that
   installs the pinned **Temurin HotSpot 21.0.12.1+1** for each image's architecture,
   alongside the existing LLVM/toolchain. Provision the matching macOS JDK too.
   The current [Dockerfile](../scripts/platform-tests/Dockerfile) selects the
   conda OpenJDK from [the IDK environment](../packaging/idk-environment.yml);
   it does not provision this Temurin matrix. Keep the ordinary IDK pins separate.
   Pin official archive URLs and SHA-256 values in preparation inputs, include
   those inputs in the bridge image cache key and perform downloads only during
   setup. The same Linux bridge images must be usable on matching hardware;
   provide a focused invocation independent of the current Colima/Rosetta-only
   runner. These image/harness additions are scheduled P0 work, not existing tools.
3. Select the exact JDK explicitly for facade compilation and consumer subprocesses.
   Fail preflight if vendor, full version or architecture differs; never fall back
   to conda OpenJDK or ambient PATH. Archive resolved java/javac paths, version
   output, JDK archive hashes, image identity, OS/kernel/glibc, physical host CPU,
   guest/container architecture and VM/translator details. Guest `uname` alone
   does not prove execution on matching hardware. Record compiler/LLVM versions
   separately. No bridge test may download its missing JDK during execution.

| Evidence | Permitted execution and gate meaning |
| --- | --- |
| P0-1 through P0-8 runtime assertions (D208 extends P0-8) | Rosetta Linux x86-64 may satisfy these bounded functional P0 assertions at O0/O3 with the pinned x86-64 Temurin. Label results translated; they establish neither native hardware performance nor stack limits. P6 repeats the applicable runtime checks on matching hardware. Translation failures or unexplained differences remain failures/inconclusive, not waivers. |
| Compiler-only assertions, including the proof portions of P0-5/P0-7/P0-8 | Any prepared host may run them. Preserve both lowering pipelines where applicable; no target execution claim follows. |
| P0-9 static disassembly | Cross-built or emulation-built target artifacts may satisfy structural checks on any inspection host. Inspect the actual O0/O3 target adapter/typed-entry machine code and handwritten JNI baseline, record target triples and binary hashes, and match them to the functional fixtures. Do not inspect Rosetta's translated code or infer timings or runtime stack behavior. |
| P0-10 stack envelope and limit probes | Require matching hardware on all three targets: ARM64 macOS, ARM64 Linux (including Colima virtualization), and Linux on physical x86-64 hardware, optionally through same-architecture virtualization. Execute the ARM64 cases in P0 and the x86-64 hardware cases at the end of P6b under D213. Rosetta/QEMU translated stack runs are diagnostic only and cannot pass this case. |
| P6 runtime, stack, allocation and timing evidence | All nine JVM/target cells require matching hardware with the same virtualization allowance; no translated run passes a release cell. Record VM resources and host contention for timing review. The Java 24 refusal test runs on macOS ARM64 as already specified. |

Run each runtime case below on macOS ARM64, Linux ARM64 and Linux x86-64 using
the pinned Temurin 21 build in the P6 matrix, with native `-O0` and `-O3` and
Java `-Xcheck:jni`. Each runtime case ultimately has six required configurations;
D213 defers P0-10's two x86-64 hardware configurations to final P6b qualification.
Compiler-only proof cases need one host, with both optimization pipelines
checked where lowering is involved. These are focused local/maintainer-controlled
host runs, not hosted full-suite jobs. Apply the D205 evidence rules above;
apart from D213's explicit x86-64 hardware deferral, an unavailable required
host/JDK or skipped case leaves P0 incomplete. No
cross-target execution result is inferred from linking. This plan neither
provisions paid hardware nor adds hosted development jobs.

| Case | Objective pass condition |
| --- | --- |
| P0-1: scalar call and exception containment | Primitive static/instance fixtures return their specified values on cold and warm calls. A deliberately thrown native exception is caught by Java; a post-catch marker and subsequent successful scalar call occur. Process exits zero and `-Xcheck:jni` reports no JNI misuse. |
| P0-2: shared-image traces | Load `.so` payloads on both Linux targets and a `.dylib` on macOS. A known throw site produces the expected Ironwood function/file/line from that image. Repeated bootstrap adds no duplicate registration, and two disjoint artifacts report their own trace metadata without cross-image resolution. |
| P0-3: loader and artifact refusal | A second independent defining loader fails with a linkage error before user-native initialization. D193's duplicate-class, shared-package and mixed-generation fixtures fail before conflicting `RegisterNatives`; try both class-path and first-use orders. An already usable artifact still returns its original result. Disjoint artifacts succeed; shared-package modules fail before native entry. After dropping application loader references and requesting GC, verify the JNI global anchor remains and a new loader is refused with no native reinitialization. Independently exercise JNI_OnLoad refusal for a bound OS-retained image using a test harness. |
| P0-4: enum first use | In fresh JVMs, first native call `Side.SELL.index()` returns 1; the separate asymmetric enum-argument fixture selects SELL=29 rather than BUY=11. Initialization failure reaches Java before target effects. Java-only enum initialization on another thread performs no native work. |
| P0-5: retention and construction | D211's reusable retention analysis accepts root-slot set/clear and attributes helper writes to known roots. Store-then-throw still blocks target free until clear. Slot-to-slot copies/moves, child-owned slots and unknown destination owners fail analysis. Constructor failure releases unpublished allocations/dependencies; unproved rollback fails analysis. Include known argument-owner writes and exceptional paths. Validate the allocation-free adapter commit with increments before decrements and no early exit; Java failure immediately after return cannot allow free of a retained target. |
| P0-6: repeated conversion allocation failure | With `IRONWOOD_ALLOCATION_LIMIT=0`, attempt allocating string-argument conversion twice in the same JVM. Both attempts yield catchable Java `OutOfMemoryError`, neither executes the target body, and an allocation-free native call then succeeds. A small nonzero budget also exercises partial conversion cleanup. Repeat through different exported methods, failed initialization and snapshot fallback; test-only inspection confirms active_implicit_failure is cleared and emergency delivery released. No process exit, emergency-state exhaustion or leaked temporary is accepted. |
| P0-7: identity and address reuse | Repeated returns while a facade is held preserve `==`; borrowed views share root invalidation. Force address reuse with a test allocator: the new allocation has distinct live state and facade; the old facade stays dead for native access and unequal to the new one. D198's inherited Java identity methods still work after free. Fail each new-root registration reservation before native execution; unbounded registration shapes and mixed fresh/borrowed reclaimable results fail analysis. Fail native index capacity/record reservation and JNI global-reference creation before target execution. After successful creation, inject Java weak-cache insertion failure; a surviving root remains indexed, and re-exposure recovers the same root state. Verify one destruction after eligible free, cleanup of reserved/indexed global references and distinct state on address reuse; safely rolled-back unpublished roots leave no entry. |
| P0-8: OrderBook proof | D211's reusable non-reclamation analysis over the actual dedicated engine closure reports non-reclaimable exposed book/order storage and internal level/array lifetime evidence, with no fabricated return owner; `createLimit`, `cancel` and `reduceTo` pass boundary classification. D208's actual constructor-failure cases below prove unpublished rollback and verify observed cleanup at O0/O3; existing exposed storage survives. A separate negative fixture with reachable reclamation or unknown deallocation effects fails that classification. Record roots, dynamic targets and reasons. |
| P0-9: scalar disassembly | Save O0/O3 adapter and typed-entry disassembly for each target; D205 permits static inspection on any host. The optimized warmed scalar path has no bridge-added allocation, identity lookup, TLS/trace maintenance, synchronization, thread check or avoidable helper call; only the required JNI/ABI work and accepted reclaimable-liveness check remain. Permanent scalar calls omit that liveness check. Compare with the equivalent handwritten JNI path; no numerical timing threshold is imposed. |
| P0-10: bounded stack envelope | On D205 matching hardware, run section 8's non-tail recursive fixture at native depths 1/8/32/64 and Java depths 0/64 on default-stack platform threads. Checksums, deepest-frame exception translation and a subsequent call succeed at O0/O3 on all three targets without -Xss configuration. Record frame/stack sizes; run separate child-JVM limit probes and distinguish unsupported overflow from the required bounded cases. |

**D208: P0-8 includes actual constructor failure, before P1.** Do not defer this
experiment to P4 or infer its result from the generic P0-5 fixture. Use the actual
OrderBook sources and dedicated engine closure, including reconstructed
class-directory/archive inputs for the proof:

1. Inspect typed rollback IR, owned-field/element facts and specialized cleanup
   reachability. Attribute every rollback deallocation to its allocation origin
   and prove failed-construction storage is unpublished and disjoint from exposed
   books/orders. Do not infer safety merely from a private field or constructor
   location. Unknown publication/deallocation effects block the classification.
2. In isolated prototype JNI subprocesses, calibrate `IRONWOOD_ALLOCATION_LIMIT`
   to fail after at least one Order has been installed in `orderPool`, partway
   through PriceLevel creation, and during a later constructor array allocation.
   Record exact budgets and reached allocation sites. Verify contained failure,
   no delivered facade for the failed book, and actual allocation/destruction
   records matching the generated rollback paths. With a previously exposed
   successful book/order as a control, failure of a second construction must not
   reclaim that earlier storage; allocation-free access afterward must work.
3. Retain the successful-construction non-reclaimable proof and negative fixtures
   with reachable exposed-storage reclamation or unknown effects. Report surviving
   unpublished allocations separately; D192 acceptance does not prove leak-free
   failed construction. An unproved exclusion or unexplained runtime cleanup
   blocks P0-8. Run the runtime portion at O0/O3 on the three P0 targets under
   D205's functional-evidence rules, and repeat through P3/P4 production artifacts.

Pooled-element destruction must be verified rather than assumed:
`buildConstructorRollbackFunctions` emits
array-element destruction only for fields recognized by `OwnedArrayElementAnalyzer`,
whose current selection uses destructor loops. The actual OrderBook has no such
destructor. Freeing a proved owned array container alone does not free its elements.
The experiment must establish the current cleanup behavior and the summaries
needed to justify each D192 exclusion; it must not invent reclamation paths.

Every P0 runtime lifetime-refusal assertion uses D207's exact exception-class
and native-operation counter checks; a producer exception cannot pass it.

Archive commands, fixture revisions, expected/actual values and diagnostics,
compiler/JDK versions, host/VM/translator and target/OS details, native artifact
hashes, trace evidence and disassembly per case. Any wrong value, missing refusal,
escaped unwind, JNI diagnostic or unexplained structural cost fails its case.
P0 closes for implementation only with all evidence due under D213; the deferred
x86-64 hardware results stay pending and cannot be reported as passed. All ten
rows need complete evidence before release; numerical performance remains deferred.

### P2 Java 25 product experiment and pre-P6 decision (D209)

**Implementation checkpoint:** [The D209 report](JAVA_BRIDGE_JAVA25.md) records
the completed macOS ARM64 value experiment. The maintainer retains Java 21-23
and Java 24+ refusal for this run to keep its qualification scope bounded.
This settles the required pre-P6 product decision without expanding the matrix.
The experiment procedure below remains the reproducible acceptance contract.

Run this when the P2 jar and consumer exist, not during the documentation pass
and not for the first time at P6. Java 25 is an
[LTS release](https://adoptium.net/support/); excluding it warrants measured
consumer evidence. Use Temurin HotSpot
[25.0.4.1+1](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1)
on macOS ARM64 hardware, provisioned before the experiment with D205's archive,
checksum and environment records. This is a product probe, not a supported
matrix cell or evidence for every Java 24+ release.

1. Run the ordinary P2 artifact as a control: D203 must refuse before extraction
   or native loading. Build a separately identified experimental artifact that
   admits exactly Java 25, using the same JNI/native implementation and ordinary
   artifact pairing/identity checks. Only the admission policy differs; no public
   bypass switch or shipped support promise is introduced.
2. Run the P2 scalar, copied-string, initialization and exception consumer cases
   with default JVM flags on class path, module path and executable-jar launches.
   Supply no native-access flags or manifest grants, and exclude inherited JVM
   option injection from the test environment. Capture complete commands, stderr
   warnings, exit statuses, values and caught exceptions. Verify a repeated call
   still works; warnings cannot substitute for successful results. Repeat with
   `-Xcheck:jni` separately to distinguish access-policy warnings from JNI misuse.
   Also run an isolated `--illegal-native-access=deny` control without grants:
   record the JVM failure and verify no producer target executes. Do not suppress
   warnings or silently grant access to make the default-flags experiment pass.
3. P2 exits with a reproducible comparison and recommendation: does the probe
   work without consumer wiring, and is its visible warning acceptable for the
   transparency goal? Failures and missing evidence must be reported, not treated
   as successful compatibility. The maintainer settles the product policy before
   P6 starts; P3/P4 can continue independently while that decision is pending.

If refusal is retained, record the reason and keep D203's tests. If documented
warning-based operation is chosen, explicitly supersede D203 for the selected
versions and update support claims, guard/refusal tests, deployment docs and the
pinned P6 hardware/JDK matrix before P6. A passing P2 scalar probe alone cannot
establish object/lifetime, OrderBook or multi-target compatibility: the selected
new version must pass those production release cases. Do not infer admission of
Java 24 or later versions from Java 25 success. Until this decision, Java 21-23
and the existing nine P6 cells remain the baseline; the decision itself is a
prerequisite to starting P6, not a requirement that the Java 25 probe succeed.

### P6 target/JDK matrix

Provision all pinned JDKs below using P0's explicit selection and checksum/cache
rules before P6. Add Temurin 22/23 for both Linux image architectures and reverify
the macOS 21/22/23 installations provisioned in P1 under D210,
plus the macOS Java 24 refusal-test build already required in P2. Reconfirm
access to D205's matching hardware hosts before scheduling the nine cells.
The existing translated x86-64
runner can provide rehearsal results only for release qualification. Under D213,
missing x86-64 hardware does not block P6a or ARM64 P6b work; it blocks completion
of P6b and release. It does not authorize a new hosted or paid test environment.

The following is the current baseline, to be revised explicitly before P6 if
D209 selects broader support. The mandatory reference distribution is
**Eclipse Temurin HotSpot**.
Use the following exact consumer builds, verified against official releases:
[21.0.12.1+1](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1),
[22.0.2+9](https://github.com/adoptium/temurin22-binaries/releases/tag/jdk-22.0.2%2B9)
and [23.0.2+7](https://github.com/adoptium/temurin23-binaries/releases/tag/jdk-23.0.2%2B7).
All three releases provide binaries for each target below. Java 22/23 are
archived compatibility baselines, not a claim of ongoing vendor maintenance;
see the [Temurin support table](https://adoptium.net/support/).

| Native target | Temurin 21.0.12.1+1 | Temurin 22.0.2+9 | Temurin 23.0.2+7 |
| --- | --- | --- | --- |
| macOS ARM64 (`aarch64_mac`) | Required | Required | Required |
| Linux ARM64 (`aarch64_linux`, glibc) | Required | Required | Required |
| Linux x86-64 (`x64_linux`, glibc) | Required | Required | Required |

Compile Java facades with the pinned Temurin 21 JDK and `javac --release 21`.
Record each downloaded JDK's URL, SHA-256 and full `java -version` output,
alongside the exact OS/glibc, CPU, compiler and payload versions. A patch upgrade
requires an explicit matrix update and rerunning its affected cells; do not
silently use a moving `latest` alias. Other vendors/JVMs are not mandatory cells
or claimed verified by these runs; adding them requires named builds and results.
Keep Java 24+ outside the supported range. Separately require a negative smoke
test on Temurin HotSpot 24.0.2+12 on macOS ARM64, recording the same download,
checksum and runtime metadata. On both class path and module path, first native
use must report the D203 version error before extraction or native bootstrap;
assert zero payload extraction/load attempts and no bridge-caused native-access
warning. This refusal test is not a tenth supported matrix cell. P2 establishes
it and P6 repeats it against the final jar. Test the Java version predicate with
21/22/23 accepted and 24/25/higher rejected, without a public override switch.

Every cell must run the focused first-release bridge consumer cases in section
13 using the final O3 payload and `-Xcheck:jni`, including exceptions, conversions,
lifetimes, identity, loading and OrderBook correctness/allocation acceptance.
Repeat scalar/exception/conversion containment with an O0 payload. Also run a
flag-free consumer smoke test on class path, module path and an ordinary
executable jar; `-Xcheck:jni` is a test diagnostic, not application configuration.
Both payload variants derive from the same producer inputs. P5 callback cases
remain excluded. Run compiler-only negative/proof tests separately rather than
turning each cell into an unfiltered compiler suite.

Each cell passes only with expected results/diagnostics and exit statuses
(including negative startup cases), zero JNI misuse reports, no crashes and
recorded evidence for supported cases. Deliberate stack-limit crash probes are
separate disposable diagnostics, never successful recovery tests. Missing binaries,
hosts or checks block that cell; no implied pass or unrecorded substitution.
Translated execution cannot satisfy a cell. Use compatible
hosts under section 14.C's effective OS/JVM baseline, recording tested OS versions
without expanding the native IDK's advertised support. These focused runs use
local/maintainer-controlled hosts, not new hosted development builds. Collect P6
timings separately without `-Xcheck:jni`; all nine cells and the maintainer's
final numerical performance review, plus the Java 24 refusal test, are required
to close P6.

## 13. Pre-change contracts and verification plan

This section records the review required by
[the regression lessons](POOL_RELEASE_HELPER_REGRESSION.md#lessons-for-future-changes).

Preserve mandatory free safety in all unfreed modes, fixed ownership, conservative
unknown effects, pool checkout/release contracts, source/class/archive parity,
lazy initialization, precise exception containment, and D132/D133. Consumers of
changed analysis include `ironwood.pool`, `ironwood.ds`, owned reusable helpers,
borrowed stream/visitor APIs, destructor validation, generic dispatch, and
constructor rollback. Bridge facts must not alter unrelated native programs.

| Area | Required positive/negative or equivalent pair |
| --- | --- |
| Root preservation | Method called only by Java survives; unreachable non-export does not. Check overload and inherited dispatch targets. |
| Artifacts | Same API and proof results from source, class-directory, individual class, and archive reconstruction. |
| Runtime version (P2/P6) | Under the current D203 baseline, Java 21-23 loads normally; Java 24+ fails the Java-only guard with artifact, full runtime version and supported range before extraction/loading. Run the pinned Java 24 negative smoke test and version-predicate checks in section 12. Pure Java enum initialization remains independent of native loading. |
| Java 25 product probe (P2) | D209 compares the ordinary refusal with the separate Java 25 admission artifact under default flags, with captured warnings and functional results across the three launch forms. Separate JNI-diagnostic and deny controls distinguish JVM policy from misuse. Report inconclusive/failing results honestly; settle the product decision before P6. |
| Initialization | Java's first call initializes once; recursive and failed initialization preserve existing behavior without a synthetic main. |
| Enum first use | In a fresh child JVM, `Side.SELL.index()` as the first native operation returns 1 without a prior book operation. A separate cold enum-argument fixture with asymmetric slots (for example BUY=11, SELL=29) selects SELL correctly; empty OrderBook counts of zero on both sides are not a discriminating test. Inspect typed IR and verify O0/O3 and source/class/archive paths. |
| Enum failure and confinement | A throwing enum initializer becomes a Java exception before target-body effects; repeated calls preserve native stored-failure semantics. Cover constant-specific bodies and null arguments. Preinitialize only the Java enum on another thread, then make the first native call on the designated thread: Java initialization performs no native work, and native initialization occurs on that caller. |
| Ownership | Independent fresh result accepted; result that also publishes an input stays conservative. Inline, helper and dispatch versions agree. Reject a reclaimable return that chooses between new storage and a receiver/argument/dependency alias, with source/class/archive diagnostic parity; accept nullable single-ownership and D192 uniformly permanent controls. |
| Non-reclaimable exports | Unknown-origin pooled result and receiver publication accepted only with a complete permanent-storage proof; source/deferred free, destructor cleanup, temporary reclamation, deallocating pool release or generated destruction that can reach exposed storage defeats that proof. Exercise all dynamic targets and source/class/archive reconstruction. |
| Failed construction and dependencies | Proved unpublished constructor rollback remains allowed; an unaccounted escape or unknown deallocator cannot receive the non-reclaimable classification. A permanent holder without root slot state retaining a reclaimable object fails export; permanent self-storage does not excuse a dangling child. |
| Permanent identity | Repeated returns of the same live pooled allocation reuse the live Java facade; non-reclaimable facade exposes no generated free or liveness state. Enum results, including null and constant-specific bodies, use the existing immortal constant mapping. |
| Alias lifetime | Safe owner cleanup succeeds; cleanup with native publication/loan or live dependent use is rejected or prevented before dereference. |
| Pool behavior | Same-pool helper remains accepted; wrong-pool transfer and dangling native aliases remain rejected. |
| Facade Object methods (P3) | Before/after owner free, verify reflexive/symmetric identity equality, null/unrelated values, stable hash/text, borrowed views and HashSet/HashMap lookup/removal. Compare Java hash/text with live native inherited results, including hash high bits and a source hashCode override with inherited toString. Verify address-reuse collisions never imply equality. Safely publish a facade to a logger thread and verify inherited methods never enter native code, bootstrap or mutable root state; source overrides still fail native access after free. |
| Public facade boundary (P2/P3) | Generated native entries/handles remain private. Ordinary reflection invoking supported public methods retains liveness and ownership checks. Privileged bypass is outside the contract; no test promises safe failure for forged handles or direct private-entry invocation. |
| Host boundary | Legal same-world single-threaded call succeeds; repeated free, freed alias and callback-triggered free follow the selected lifetime contract. Cross-artifact facade collisions are rejected at bootstrap, not by a fictitious public wrong-world call. |
| Threading scope | Native calls and callbacks execute on the calling thread; no injected thread checks, locks, or executor dispatch enforce confinement. No test promises safe rejection of multithreaded misuse. |
| Explicit free | Owner reclamation invalidates all dependent facades; borrowed free and active-callback free are refused under the accepted contract. Retention counters follow actual effects on normal and exceptional exits. |
| Lifetime refusal identity (P0/P3, extended in P4/P5) | D207 refusal assertions require the exact artifact-private exception class and unchanged counters for the refused native operation/destruction. Cover dead receivers/arguments and borrowed/retained free in the reclaimable fixture; P5 adds active-callback free. A producer-thrown IllegalStateException control must fail the refusal assertion; P4 adds actual OrderBook capacity exhaustion as a producer-error control, without inventing OrderBook free support. Repeated successful owner free remains a no-op. |
| Retention-slot analysis (P0/P3) | Accept root-field set/replace/clear and proved read-only loads; cover aliases, helpers, all dispatch targets, writes to known argument roots and store-then-throw. Reject slot-to-slot copies/moves (including clearing the source afterward), hidden static/container publication, unresolved destination owners/effects and borrowed-child slots. Repeated stores of the same input into distinct slots count separately; refused free persists until the last release. Source/class/archive proof results agree. |
| Bookkeeping completion (P0/P3) | Inspect the adapter epilogue: all success/error paths commit without allocation, Java calls or early exits. Inject StackOverflowError immediately after adapter return and failure during Java facade/error materialization; replacing a retained target, multiple slots, aliased holders, store-then-throw and owner destruction leave exact counts/records. Before releasing the last actual dependency, free must refuse. Check increment-headroom refusal occurs before native effects. |
| Registration reservation (P0/P3) | Inject failure at each reservation allocation, native index capacity preparation and JNI global-reference creation/local-capacity preparation: the native-call counter remains unchanged. Accept separate bounded fresh-or-null and proved alias/borrowed-or-null cases; reject mixed fresh/existing reclaimable origins; reject result shapes needing unbounded or post-call registration allocation. Failed facade delivery or Java weak-cache insertion cannot lose root identity/state; later re-exposure must reuse the native index record and its Java root state. Count index records, global references and native destructions across null/alias results, failed preparation, safe unpublished rollback, wrapper collection, eligible free and forced address reuse. No duplicate owner state, double destruction or leaked reservation is accepted. Inspect commit to exclude table growth, reference allocation and Java collection calls. |
| Retention state lifetime (P3) | Root slot records survive root/child facade GC and wrapper recreation. Retaining a borrowed value protects its root; retaining on a borrowed child is rejected. Purely permanent graphs remain exempt, while permanent holders of reclaimable values fail export. |
| Marshalling | Unicode/NUL/surrogates and numeric extremes round-trip; invalid lengths, retention, aliasing, and failure cleanup cannot leak temporary pointers. |
| String buffer APIs (P2, extended in P5) | Inspect generated adapters for D206 noncritical access. Pair acquisitions/releases on success, target failure and partial multi-string preparation failure; acquisition failure must skip native execution. Cover null, empty, embedded NUL and unpaired surrogates. P5 adds allocating/reentrant/throwing callbacks while the outer string remains live, with independent nested buffers and zero critical-region JNI warnings under `-Xcheck:jni`. |
| Conversion allocation failure (P1/P2, extended in P3) | P1 uses its private native harness; P2/P3 compare successful conversions with fresh child JVMs using `IRONWOOD_ALLOCATION_LIMIT=0` and small nonzero budgets chosen for each fixture. Fail before target entry, after partial argument construction, during type initialization, result conversion and snapshot/getter extraction. Assert Java catches the expected failure, prints a post-catch marker and exits normally; a later allocation-free native call succeeds. Check cleanup, repeated failure and O0/O3, including IR inspection that every raising operation has a handler. Bridge allocations use a non-null failure context and participate in the limit. |
| Built-in exceptions (P2) | Expected checked/unchecked Java catch type, `throws` declaration, message, representable causes/secondary failures and Ironwood source frames plus Java call site. Include initializer/repeated failure, bounded cycles and translation exhaustion; retained/emergency native throwables are not incorrectly freed. |
| Custom exceptions (P3) | Generated checked/unchecked types preserve superclass catches, declarations and snapshot getter values after native return/eligible cleanup. Unsupported getter/data shapes fail producer build. Repeat relevant P2 trace, failure and ownership cases with custom types. |
| Callbacks (P5) | Ordinary and throwing listeners work; nested calls preserve outer state and unchanged Java throwable identity; carrier cleanup covers catch/replace/retain paths. Unknown retention is never accepted as borrowing. Reuse the P2/P3 native-failure translator. |
| Native dependencies (P1/P6) | Audit direct/transitive ELF dependencies and symbol versions on both Linux targets; a minimal-JVM environment runs scalar and unwind tests without extra runtime packages or loader-path configuration. Private/static support, if needed, passes two-artifact isolation and distribution review. Verify BIND_NOW/NOW on final Linux payloads and catch System.load failure for a missing required symbol before any source call; inspect final macOS install names as well. |
| macOS signatures and extraction (P1/P2/P6) | D210 verifies the final/extracted dylib's ad-hoc signature and byte identity, then loads and calls it at O0/O3 under all three pinned supported Temurin launchers on macOS ARM64. P1 uses a fixture jar, P2 the generated loader and P6 the final distribution. Require successful scalar/exception/continued-call checks without changing launcher signing or consumer configuration. Record signing failures separately from dependency/load failures. |
| Stack envelope (P0/P1/P6) | Section 8's bounded cases pass on default stacks; record generated frame sizes and repeat across the P6 matrix. Run destructive limit discovery only in disposable children and publish the actual tested envelope. |
| Loader lifetime (P0/P2/P6) | Inspect the permanent JNI loader anchor, drop application references and exercise GC pressure; a control unanchored loader can collect, but the bound loader remains and a replacement is refused before native initialization. An OS-retained-image harness exercises the bound guard; the original world is not reset. |
| Isolation | Two artifacts with disjoint generated packages work; duplicate classes or package ownership fail before registration. Check both class-path/first-use orders, mixed-generation classes and continued operation of an already usable artifact; shared-package modules fail before native entry. |
| Performance | Compare primitive-only methods with a plain JNI baseline and measure the accepted checks separately; aim for close crossing cost without weakening safety. No bridge bookkeeping appears in ordinary native-only call paths. |

When modifying shared phases, select relevant existing checks by their exact
registered names, for example:

```sh
./scripts/test.sh \
    --test 'pool release helper proofs preserve mandatory safety' \
    --test 'pool release helper proofs survive artifact reconstruction' \
    --test 'destructor and constructor effects are checked closed-world' \
    --test 'caller-owned library results survive source class archive and tree-shaking round trips'
```

For root/initialization/trace work, select:

```sh
./scripts/test.sh \
    --test 'static initialization lowers through typed IR and private LLVM state' \
    --test 'static initialization survives source-path class-path and archive round trips' \
    --test 'stack traces retain source identity in typed IR and LLVM' \
    --test 'stack traces survive source class file and archive round trips'
```

Recheck names and relevance before implementation. Add focused bridge tests for
each phase, including parser/CLI, semantic diagnostics, typed IR, emitted ABI,
native link, javac compilation, and child-JVM execution as applicable. Use
`-Xcheck:jni` in JNI-focused tests. Keep crash/exit/unwind and failure-injection
tests in subprocesses so a native bug cannot terminate the entire test harness.
Do not replace existing process-isolated native tests wholesale with JNI calls.

Test Java 21, 22, and 23 on supported target hosts. Java 24+ compatibility and
native-access authorization support tests are deferred except for D209's P2
experiment; D203 refusal tests remain mandatory under the current baseline.
Settle D209 before P6 and update the matrix/tests explicitly if support changes.
Use local targeted
verification; hosted three-platform builds remain release-only. Run the current
OrderBook deterministic workload and inspect O3 machine code when changing hot
lowering. Use small Java differential tests only for shared behavior, never to
justify native free or excluded APIs.

Run `git diff --check` for every change. Source, header, provenance, and payload
changes require `scripts/check-licenses.sh`; packaging behavior changes require
the relevant [IDK](IDK.md) and README smoke paths. No unfiltered compiler suite
is authorized by this plan. After focused checks pass, broaden only for a new
change, failure, or unresolved risk.

This planning work changes documentation only. Its verification is link/path,
test-name, policy, and consistency checking. Proposed bridge snippets cannot be
compiled today and are explicitly labeled as such. No compiler or benchmark
results are claimed by this document.

## 14. Settled implementation contracts

Confirmed support range: Java 21-23 initially; Java 24+ is refused before native
loading under the current D203 baseline. D209 reopens that product choice through
a P2 Java 25 experiment and a required decision before P6. Keep facade
bytecode compatible with Java 21. Confirmed reclamation API: explicit `free()`
(D189); no generated `close()` alias or automatic fallback is implied.
Confirmed contract: D188, single-threaded access as a caller obligation without
runtime enforcement of multithreaded misuse. This is no longer an open gate.
Confirmed design: D190, compiler ownership checks plus shared Java lifetime
state, retention accounting, and callback active-use guards. Their stated
boundary costs are accepted. Primitive-only calls should stay close to plain
JNI cost; validate that goal with measurements.

The maintainer delegated these choices for settlement before implementation.
D191, amended by D192's non-reclaimable classification, D193's exclusive
packages/registration preflight, D194's enum initialization, D195's exception
milestone assignment, D196's root-slot analysis, D197's conversion exception
boundary, D198's Java-only inherited identity methods, D199's milestone criteria,
D200's bookkeeping completion, D201's runtime-risk contracts, D202's
first-release boundaries, D203's JVM version guard, D204's native root index,
D205's host prerequisites, D206's string-buffer contract, D207's refusal
exception subtype, D208's rollback experiment, D209's JVM product review,
D210's macOS signature/load experiment, D211's reusable analysis foundation,
D212's execution checkpoints and D213's final x86-64 hardware qualification,
records these selected contracts and resolves the earlier open alternatives.
P0 validates this design.
A failed proof or experiment warrants a specific correction; it does not permit
silently weakening safety or expanding
the release surface. Routine implementation choices within these contracts do
not need renewed design approval.

### A. Facades, identity, and destruction

- Use the same generated Java class for owned and borrowed instances. Generate
  `free()` when the class can represent an eligible owner. A private capability
  identifies whether that particular native object is the reclaimable root.
  A borrowed child cannot obtain that capability.
- Keep one shared state per reclaimable root, referenced directly by its facades:
  world identity, LIVE/FREEING/FREED status, incoming retention count, and fixed
  outgoing dependency slots for fields on the root itself, not its children.
  P5 adds active-use counts where needed. Use plain
  fields under the single-threaded contract, without synchronization.
- Preserve one live facade per native object. **D204: the authoritative,
  world-owned root index resides in native memory.** Each live reclaimable root
  has an index record mapping its native identity to a strong JNI global reference
  to its preallocated Java root-state object. Reserve native records/capacity and
  create those global references before the typed call. Index insertion in the
  adapter only binds identities into reserved native storage; it cannot resize,
  allocate references or invoke Java collections. Root/slot/count state remains
  in Java and uses the pre-resolved JNI field accesses in section 14.B.
  Only the weak facade cache resides in Java: per-root for reclaimable objects,
  or section 7's world-level cache for non-reclaimable objects. Fill it after
  adapter commit/return. A cache miss or failed insertion never authorizes a new
  root state or destruction capability; recover the existing state from the native
  index first. These lookups belong to object conversion, not scalar calls.
  Keep indexed root state until explicit reclamation or JVM termination, even
  if facade delivery fails or all wrappers are collected. After successful free,
  the adapter marks the shared state FREED, removes the index record and releases
  its global reference before returning to Java. Old wrappers retain that dead
  state; address reuse receives a distinct record/state. A stale Java cache entry
  cannot override the authoritative state. Cache cleanup must not call native
  code from a GC thread or reclaim native storage.
- Ownership is a property of the object, not the path by which an alias was
  returned. A self-return of an already Java-owned object reuses its owning
  facade and capability. Calling it a borrowed return does not revoke that
  existing capability. Borrowed children are distinct native objects and remain
  non-owning. Non-reclaimable results need no fabricated owner; they never gain
  a destruction capability. Reject projections requiring conflicting owners
  for one object.
- Repeated `free()` of a successfully freed owner is a no-op. Native access
  through any dead facade, or free of a borrowed, immortal, retained, or active
  object, throws the artifact-private `BridgeLifetimeException` subclass of
  `IllegalStateException` (D207). Internal handle paths that admit a
  world mismatch reject it with `IllegalArgumentException` before native access;
  this is not an expressible first-release public argument scenario. A class or
  package collision instead fails bootstrap with `LinkageError`. Inherited
  identity methods use only immutable Java data and remain valid after free,
  as specified in section 5; distinct facades compare unequal. Preserve
  source-defined null behavior rather than imposing a blanket non-null rule.
- Refusals leave the root LIVE. Eligible destruction must be proved nonthrowing
  and callback-free, including initialization and destructor dependencies.
  Transition LIVE -> FREEING -> FREED; release outgoing retention counts only
  after native destruction succeeds. Reject a destruction capability lacking
  that proof. Do not invent a recoverable partial-destruction state or restore
  LIVE after native destruction starts. Fatal runtime failures remain fatal.
- Failure to construct or publish a new facade must either reclaim a proved
  unpublished allocation or preserve its already-established native owner.
  **D200: preallocation is mandatory.** Before the native call, allocate/reserve
  all registration state for every possible new reclaimable root: Java lifetime
  state and fixed outgoing slots, native root-index records/capacity, strong JNI
  global references to that state and bounded commit/result records. Java weak
  facade-cache insertion is later delivery work, not root registration. Cover all
  possible admitted result alternatives and dynamic types, not just the common
  successful case. D202's mixed fresh/borrowed rejection happens before this reservation;
  preallocating enough records does not make that result shape admissible.
  If the compiler cannot bound or pre-reserve that state, reject the export;
  if reservation fails at runtime, do not invoke native code.
  Bind returned native identities into these reserved records in the adapter
  without allocation or Java helper calls. Existing-root aliases reuse their
  established state; null/alias/failure paths discard unused reservations only
  after proving no new root requires them; release their reserved global references
  and native records on every unused/failed preparation path. A successfully created
  root must be registered and strongly retained independently of facade delivery
  before any later Java allocation can fail. D198's final facade identity fields can still
  be set by a later Java constructor; facade allocation failure must leave the
  registered root intact or run a proved safe unpublished rollback. It must
  never leave untracked native storage or revoke a published dependency.

### B. Exact retention reconciliation

**D196: root-only slots and a new compile-time analysis.** For retention affecting
reclaimable storage, admit only a fixed set of reference fields on a reclaimable
root object itself. Its persistent Java root state stores one dependency record
per field and survives facade collection. No retention slots live on borrowed
children, in arrays/containers, or in weakly cached facades. Thus
`book.level(0).add(order)` is rejected if it retains `order` in a level field,
even when the level's owning book is known. A child method may update a field
on the root only if the analysis proves that exact root and field.
Purely non-reclaimable/immortal graphs keep section 7's exemption; first-release
permanent holders cannot retain reclaimable targets without root slot state.

Build the reusable retention-slot analysis foundation in P0; extend and integrate
the same implementation in P3 under D211.
Use semantic dataflow and interprocedural summaries over the complete export
closure, including helpers, constructors, dispatch targets, initializers and
exceptional cleanup. Do not infer writes/releases from may-escape summaries.
For every exported entry, prove all of the following:

- Attribute every store, overwrite or clear of a tracked field to a known root
  available at that entry (receiver/argument root or its newly constructed root).
  Include writes to other owners through aliases and callees, on every normal
  and exceptional path. Unknown effects or unresolvable owners reject the export.
- Track values loaded from slots through locals and calls. Reject copying or
  moving them into any other retaining storage, including another tracked slot,
  another owner's field, static storage or a container. Clearing the source
  later does not make such a transfer supported. Transient, proved non-retaining
  use is allowed; returned references still need the existing result/owner proof.
  This prevents hidden publication and unaccounted aliases required to be
  rejected by section 7, rather than assuming a slot load is harmless.
- Produce immutable per-entry contracts listing destination roots/fields and
  possible value origins for all paths. Recompute from source/class/archive
  inputs and revalidate after specialization. Only a proof of no slot mutation
  or hidden retention permits omission of the reconciliation payload.

The first release admits null and known input roots as new slot values; an
unchanged slot may preserve its already-tracked dependency. This is not permission
to copy that dependency into a different slot. Multiple slots may independently
retain the same known input, with each counted. Reject
unbounded containers, hidden publication, unrepresentable origins, and cycles
between independent roots. Prove acyclicity for every admitted argument
combination; a Java caller's convention is not a proof. Internal object cycles
within a root remain subject to that root's existing destruction proof.

**D200: complete the bookkeeping in the C adapter.** Returning from the typed
entry to C is not a return to Java. The adapter must finish all root/slot/count
updates before returning, throwing a Java exception or calling any Java helper.
Increment-first ordering in Java alone is insufficient: interruption before the
first increment could leave a newly stored reference uncounted.

Use this boundary protocol:

1. Validate receiver/argument liveness and world identity. Reserve section 14.A's
   registration state, host slots and bounded result/commit storage before native
   execution. Resolve field IDs, root references and required JNI local-reference
   capacity before mutation. Prove arithmetic cannot wrap: preflight headroom for
   the maximum possible incoming increments, including aliased inputs, and refuse
   the call before mutation if it is unavailable.
2. Run the native operation. Its generated entry catches native exceptions and
   records actual final references in the affected slots on both normal and
   exceptional exits, before throwable or result conversion. This inspects a
   fixed proved set of slots, not the heap or an arbitrary object graph. Preserve
   these records and identities of roots requiring registration in the bounded
   result/error frame even if later native conversion or snapshot extraction fails.
3. In the adapter, insert any new roots into the reserved native index using
   already-created global references, then reconcile old versus final
   slot roots, counting each distinct retained slot once even if receiver/argument
   aliases name the same holder. Apply all required incoming increments before
   any decrements, then finish slot records. Unchanged dependencies need no delta.
   Execute the commit using reserved native index writes, pre-resolved JNI field
   accesses and bounded arithmetic, without allocation, Java method calls,
   callbacks, native raising helpers or early exits. Prove this epilogue has no
   recoverable failure point; resolving
   metadata, allocating references or invoking Java collections here is forbidden.
   Every typed-entry success/failure path reaches it. If a method stores an order
   and then throws, the dependency is committed before Java sees that exception.
   Never expose a partially committed state for a later Java call to repair.
4. Constructor failure must prove rollback removes unpublished dependencies;
   otherwise reject the export. Freeing an owner releases its outgoing slots
   only after the nonthrowing native destruction has completed; the adapter also
   completes its slot releases, FREED transition, native index removal and release
   of the index's global reference before returning to Java. Existing wrappers
   keep their ordinary Java references to the dead state. Safe unpublished rollback
   releases its registration resources as well; facade-cache failure alone never
   rolls back a root that remains published or retained.

P0 must validate the nonthrowing commit epilogue in its target/JVM configurations;
P3 enforces it in generated code and P6 repeats coverage across its JVM matrix.
If a required update cannot meet this contract, block that export/implementation
path rather than moving completion into Java.
A Java error during later facade/throwable materialization or immediately after
adapter return cannot undo committed registration or counts. Fatal JVM/process
failure and unsupported concurrent native access are outside this guarantee;
no thread checks, locks or per-scalar-call transaction tracking are introduced.

This protocol is sufficient only when Java cannot reenter during the operation
and native code cannot independently reclaim or invisibly publish those roots.
Enforce those conditions at the producer build. Scalar methods proved read-only
by the new analysis carry no retention payload or slot reconciliation. Ordinary
native builds gain no such bookkeeping. Measure the bounded extra work on
retaining operations.

For P5, guard callback-reachable receivers and arguments identified by complete
effect proofs; reject free while a suspended native frame needs them. Initially
reject exports combining callbacks with independent-root retention changes.
Extending those exports requires a protocol that reconciles changes before
every Java reentry, including exceptional paths; return-only reconciliation
would be unsafe. This is an explicit later capability, not a hidden P3 promise.

### C. First release boundary

| Topic | Selected first-release contract |
| --- | --- |
| Transport and producer command | Generated C JNI adapters, `javac --release 21`, single-jar default, exact-package `--export` with exclusive ownership, and the command in section 5. Signature closure cannot silently add facade packages. |
| API surface | Constructors, static/instance methods, primitives, copied strings with proved cleanup, concrete non-subclassable facades, enums/static nested types, owned roots, borrowed views and compiler-proved non-reclaimable results. Inherited concrete-facade Object methods are Java-only and survive free (D198); supported source overrides retain native preconditions. P2 built-in exception/trace mappings and P3 copyable custom exception snapshots/getters are mandatory before release. |
| Deferred surface | Stateful/retained callbacks and reference callback values (remaining P5 work), arrays, general `CharSequence`/`Object` arguments (except inherited identity equality), source overrides of `equals(Object)`, exported reference generics, general native inheritance, Java subclassing, mutable public fields, mixed fresh/borrowed reclaimable results, and arbitrary object-graph conversion. Reject unsupported public signatures at producer build. Internal uses remain allowed when their boundary proofs hold. |
| Retention | Fixed fields on reclaimable roots with persistent host records only; the authoritative native root index has reserved capacity and JNI global references to Java root state before execution. The adapter commits registration/count/slot updates before Java resumes; only weak facade caching follows in Java. P3 proves every write and rejects slot-value transfers or hidden publication. Borrowed-child retention slots and permanent holders of reclaimable targets are deferred. |
| OrderBook | Preserve the actual project's process-lifetime graph using P3's non-reclaimable classification. Demonstrate complete `free()`, retention and cross-owner argument behavior using a separate reclaimable owner/child fixture. Reclaimable production OrderBook is a separate producer change; it is not claimed by this release. |
| Platforms | macOS ARM64, Linux ARM64 and Linux x86-64. Reuse official IDK native baselines, including Linux glibc 2.17; record the macOS deployment target and required CPU features in the artifact. Effective support also requires a supported Java 21-23 JVM on that host. Do not advertise an older OS merely because the native payload can load there. |
| Loading | Current baseline: refuse Java 24+ before native extraction/loading (D203), subject to the D209 product decision before P6. Ordinary class path, module path, and executable jars with standard dependency loading. Multiple bridge artifacts require disjoint generated packages. Validate generation identity on every resolved class and package marker before any native registration. One defining classloader per artifact is permanently anchored for the JVM lifetime, with a bound-image load guard; reject a second independent load before user-native initialization. Custom nested-jar loaders, relocated/shaded facades, isolated duplicate worlds, unloading and hot reload are deferred. |
| JVM verification | Eclipse Temurin HotSpot 21.0.12.1+1, 22.0.2+9 and 23.0.2+7 on each of the three targets, as specified in section 12. Other vendors are not claimed verified. |
| Release gate | Record the D209 product decision and any explicitly revised version tests/matrix before P6. Under the current baseline, complete P0-P4 and P6, including all ten P0 cases (with D213's deferred x86-64 hardware evidence completed in P6b), P4 allocation cases, all nine P6 JVM/target cells on D205 matching hardware and the separate Java 24 refusal test. P5 and P7 are extensions. No callback signature is admitted before P5, so P3 does not depend on unfinished callback machinery. |

The classloader restriction requires a reliable duplicate-load failure path;
do not bypass JVM library ownership by silently extracting a new image for
each loader. P0 must validate the chosen loading identity before P2 relies on
it. Platform minimums must be encoded from the build and tested against the
selected JVM distribution; verification of those facts is implementation work,
not a new decision to support an unspecified platform.

### D. Performance criteria and review checkpoints

Fix structural criteria now: on warmed successful scalar paths without retention
effects, the bridge adds no Java or native heap allocation, identity lookup,
loading, synchronization or thread checks. Include only required entry work and
accepted liveness checks. This does not prohibit allocations in the producer's
method body. Native-only output must acquire no bridge bookkeeping. Retaining
calls, failure paths and object/string conversion have separate measurements.

At P6, compare an equivalent handwritten JNI baseline with actual generated
output, using the same native operation, compiler flags, target and JVM. Include
scalar static and instance calls, warmed repeated forks, consumed results,
absolute time, relative overhead and measurement variability; keep loading
outside the timed region. Inspect native machine code and Java allocation/JIT
behavior. Measure strings, object returns, exceptions and OrderBook separately.

The maintainer explicitly permits postponing numerical thresholds and acceptance
to the very end. They are part of P6's final release review, not a prerequisite
for P0-P4. Record measured results and the final accept/optimize decision without
inventing a universal cutoff or claiming an unmeasured speedup. Structural
performance constraints and repository-required focused benchmarks for changes
to existing hot lowering still apply throughout implementation. Earlier timing
measurements may inform work but are not an added milestone gate.

D225 records the maintainer's 2026-09-28 acceptance of the measured D224
implementation with its documented Linux ARM64 and standalone-native gaps.
Further optimization is deferred. This supplies the numerical review for that
candidate; every other P6b qualification requirement remains, and publication
still requires separate authorization.

Use explicit checkpoints:

- Before P0: contracts A-C and structural criteria are settled in D191. Start
  the implementation branch when implementation is requested; this documentation
  task does not start it.
- After P0: review evidence for every P0 checklist row and its required
  configurations, including structural costs. D213 permits only the recorded
  x86-64 hardware deferral; other missing/inconclusive evidence blocks completion.
  A failed safety or feasibility case needs a specific correction.
- After each of P1-P4: review its exit criteria, focused regressions and remaining
  limitations before proceeding to dependent work. P3 must demonstrate partial
  mutation followed by an exception, aliases, address reuse, refused free, complete
  slot-write attribution and retention records surviving facade collection.
- Within P0/P3/P6: use section 12's D212 checkpoints to review concrete
  deliverables and handoffs. Do not count a completed subset as the phase exit;
  P6b qualifies the distribution candidate completed in P6a.
- After P6: review all nine pinned JVM/target cells, the release surface and final
  numerical performance results. Passing P2 alone never closes the feature.
  Review P5/P7 separately when requested.

Record accepted architectural choices in `DECISIONS.md`, explicitly superseding
any earlier decision only when necessary. Synchronize `MEMORY.md`, `COMPILER.md`,
`LANGUAGE.md`, `LANGUAGE_SPECS.md`, compatibility docs, `IDK.md`, and bridge user
documentation as implementation lands. Scope JNI wording to the generated Java
host adapter: Ironwood still has no JVM execution backend or Java-platform
compatibility requirement. Update roadmap/status tables only for implemented,
verified capabilities; this plan changes no supported feature status.

The next implementation task starts with P0a preparation/contracts, followed by
P0b compiler foundations and P0c validation on the requested new branch. The
contracts are settled; demonstrated design failures and D209's scheduled product
decision may require revision. Proceed through the dependency checkpoints in
focused changes; this review does not start implementation.
