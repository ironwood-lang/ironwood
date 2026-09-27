<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood Java Bridge implementation plan

Status: design and implementation plan, updated 2026-09-26. No bridge implementation
is authorized by this document. Repository observations were checked at
`767e21d`; planned classes, commands, tests, and output formats below do not
exist yet. The maintainer selected **Java 21-23** as the initial consumer
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
and limits first-release retention slots to root objects. Numerical performance
acceptance is deferred to the final release review.
Discussion also confirmed that the
bridge remains single-threaded by caller contract, without runtime enforcement
of thread misuse; see
[D188](DECISIONS.md#d188---java-bridge-confinement-is-a-caller-obligation) and
section 8.

This plan reviews [the user-facing sketch](JAVA_BRIDGE.md) and
[the earlier proposal](IRONWOOD_JAVA_BRIDGE.md). Where they disagree, use this
document as the implementation plan, not as a new language specification.
Accepted compiler semantics, including mandatory safe reclamation and D132/D133,
remain unchanged for ordinary native execution. D188-D196 record the accepted
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
| Java 24+ | Deferred; native-access authorization is outside the initial release scope. |
| Native cleanup | Explicit `free()` with compiler ownership proofs and shared Java lifetime state. No automatic fallback is implied. |
| Unsupported signature or unsafe ownership | Producer build diagnostic, not generated methods that fail only when called. |
| Unsupported platform or restricted extraction | Clear load-time diagnostic; no guessed binary or runtime download. |

The initial release uses generated JNI on Java 21, 22, and 23. Automatic native
loading is part of the bridge; consumers do not supply native-access flags.
Java 24+ support, permission handling, executable-manifest grants, and related
deployment tests are deferred and are not release blockers for this range.
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
| [`EscapeSummaryAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java), [`SymbolicReturnOriginAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/SymbolicReturnOriginAnalyzer.java) | Summaries distinguish retention, publication, fresh results, aliases, and dependent borrows. They do not provide complete slot writes/releases, destination-owner effects or slot-value propagation. General pool-array loads need not establish a known owner. | Reuse origin facts without rewriting unknown provenance. Add retention-slot write analysis (P0 proof prototype, P3 implementation), a closed-world non-reclamation proof, and foreign-root/foreign-callback obligations; preserve conservative native free analysis. |
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
| Native inheritance | Preserve assignability and dynamic dispatch in generated hierarchies when implemented. Do not make every facade final if exported inheritance requires otherwise. |
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

For concrete facades, inherited identity `equals(Object)` checks receiver
liveness when its root is reclaimable and compares facade identity; null and
unrelated Java objects compare unequal. Source overrides of `equals(Object)` are outside the first release
because arbitrary Java `Object` arguments are not supported; diagnose them at
producer build. Dispatch `hashCode()` and `toString()` through the native
implementation with applicable liveness checks and string conversion, including
supported source overrides. Java casts and `instanceof` use the generated type
hierarchy and need no native access, even for a freed wrapper. A Java `==` test
also remains ordinary Java reference comparison. Native access after free
throws; it does not change the wrapper's Java type or identity. Generated enums
retain Java enum identity and final `Object`-method behavior.

`java.lang.Object` methods, Java monitors, reflection, serialization, cloning,
framework proxies, and native object identity are not automatically equivalent.
Ordinary Java reflection may see the generated API; it must not see a promise
that native fields or arbitrary serialization are supported. Document enforced
boundaries and test Java-valid calls admitted by the generated declarations,
following [behavioral contract review](OPENJDK_PORTING.md#behavioral-contract-review).

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
non-reclaimable/immortal classification from section 7. Preserve mixed
fresh/borrowed/null alternatives or reject them; do not choose the favorable
alternative. Revalidate after specialization changes callable identities.
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
Prefer a native adapter stack frame with bounded result/error storage over the
old global segment and per-thread scratch default. The JNI sequence is:

1. Validate the Java-side world/lifetime obligations in sections 7 and 14.
2. Marshal supported arguments with cleanup established before any failing step.
3. Invoke the typed entry with an invocation-local result/error frame; perform
   native enum conversion and initialization inside its exception boundary.
4. Convert success, or materialize the Java throwable after native unwinding ends.
5. Release only temporary native/JNI resources whose ownership is established.

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
state, incoming retention count, or generated `free()`. The world stays loaded
while facades are reachable. Repeated returns of live pool storage preserve
Java wrapper identity; no generation check or snapshot semantics is added.
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
`IllegalStateException`. Generated documentation identifies ownership on every
constructor/result. This is a contract of the new generated method, not a trap
added to an existing source method. Types that can never be Java-owned need not
expose `free()`. D191 settles this API shape.

A repeated `free()` on an already freed owning facade is a no-op;
ordinary native accesses through it still throw. Use LIVE, FREEING, and FREED
states to prevent reentrant reuse. Do not mark a refusal as FREED, or restore
LIVE after destruction may have started. Generate a destruction capability only
when its complete path is proved nonthrowing and callback-free; section 14
defines refusal and fatal-failure behavior.

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
D196 adds a separate retention-slot write analysis, prototyped in P0 and
implemented in P3, to establish section 14.B's entry contracts and reject
unaccounted native aliases/publication before accepting an export.

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
Thread handoff, virtual-thread carrier migration, concurrent isolated worlds,
and asynchronous native callbacks are not promised by this plan. Loader
isolation tests are not an authorization to implement multithreading.

Calls execute synchronously on the Java calling thread. Same-thread callbacks
and reentrancy are supported only where the source contract permits them.
A Cleaner thread must not call into the world, even though the application
itself obeys confinement. Generated cleanup must obey the same contract.

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

P2 implements built-in checked/unchecked exception mappings, matching Java catch
hierarchies and generated `throws` declarations, messages, representable causes
and secondary failures, and Ironwood source-frame snapshots followed by the
Java call site. Its Java consumer tests must catch the expected built-in type
and inspect those values; a generic bridge error or native stderr report is
not an acceptable replacement. Until P3, custom exception exports are diagnosed
as unsupported instead of silently losing their type or data.

P3 generates supported custom Java exception classes and their catch hierarchy,
checked declarations and snapshot-compatible getters. Values remain accessible
after the native call and eligible native cleanup, without a native handle or
generated `free()` on the Java exception. Test custom checked and unchecked
types, superclass catches, getter values and producer rejection of unsupported
getter/data shapes. These exception hierarchies are part of the mandatory
snapshot projection, not the deferred general native-facade inheritance feature.

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
Materialize a valid native representation or add a typed, bounded borrowed
representation with its own proofs. Never reinterpret arbitrary heap storage
as an existing Ironwood `String`/array object.

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

1. Resolve the resource from the facade's defining loader and identify the host.
2. Select a compatible payload; report available targets when none matches.
3. Extract to a private, versioned location with safe filenames, restrictive
   permissions, atomic creation, and payload integrity checks. Handle concurrent
   JVMs and stale partial files. A digest checks consistency, not publisher trust.
4. Load the exact absolute path and validate bootstrap schema/API/build identity.
   Preflight all package markers and resolved generated classes before any
   native registration; only then bind the checked classes and publish the
   artifact as ready. This cannot sandbox malicious native payloads.
5. Initialize artifact-private support once, without eager source-class effects.
6. Keep the image alive while its objects, callbacks, or code addresses remain
   reachable. Object closure is not permission to unload the native world.

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
[JNI invocation specification](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/invocation.html).

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
native-access policy tests are deferred. Offer advanced location overrides only
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

## 12. Implementation phases and exit criteria

Each phase should be split into focused commits. Do not start a later safety-
dependent phase while its contract is unresolved. Test names below for new
bridge behavior are proposed, not existing commands.

| Phase | Work and concrete deliverable | Exit criteria |
| --- | --- | --- |
| P0: bounded validation experiments | Validate section 14's settled contracts, including the C JNI adapter, identity, a retention-slot write-analysis prototype, retention reconciliation, and D190's boundary checks. Experiments are a later authorized task. | Prototype distinguishes root-field set/clear and exceptional writes from forbidden slot-value transfers, borrowed-child slots and unknown destination owners; read-only classification requires complete effects. Required proof cases pass and structural costs are inspected. A failed safety or feasibility case blocks dependent work; no numerical performance threshold is required. Multithreaded misuse remains outside the contract. |
| P1: multi-root native library foundation | Output kind, typed export roots, optimizer propagation, shared link flags, visibility, bootstrap, image-local traces. Use scalar static entries and a private host harness. | No main required; callable reachable only from Java retained; unreachable code pruned; first-use and failed initialization correct; unwinding contained at O0/O3. |
| P2: first plug-and-play jar | Deterministic export model, exclusive packages, generation identity, Java 21 source/classes, generated JNI, complete registration preflight, loader, manifest pairing, one-target jar. Static primitives and copied strings where cleanup is proved; built-in exception mapping and trace snapshots. | Plain Java 21-23 consumer runs without native tools, flags or manual loading; signature types outside exports diagnosed; disjoint artifacts work; colliding packages/classes fail before any rebinding and preserve an already usable artifact; platform/build/extraction errors remain actionable. Java catches expected built-in checked/unchecked types with correct declarations, messages, representable causes/secondary failures and Ironwood frames; initializer/repeated failures and translation exhaustion are tested. |
| P3: object and lifetime model | Constructors, identity, supported hierarchy/enums with typed initialization-before-conversion, owner/dependent enforcement, explicit `free()`, failure rollback, generated signature validation; implement retention-slot write analysis and root-only persistent slot records, closed-world non-reclaimable classification and world-level identity caching; generate custom exception classes, hierarchy and snapshot getters. | Reclaimable aliases remain safe; root-slot writes/clears reconcile on success and failure, including helper writes to known argument roots; copied/moved slot values, child-held slots and unknown owner/effect cases fail export. Counts survive facade GC; cleanup verified. Cold enum receiver/argument calls and initializer failure pass before P4. P2 collision checks cover object facades as well; valid same-world arguments work, with no fabricated public cross-world case. Permanent pooled returns and receiver publication pass without fabricated ownership; reachable reclamation or unknown deallocation effects fail the permanent proof. Source/class/archive results agree. Custom checked/unchecked declarations, superclass catches and getter values pass Java consumer tests; unsupported projections fail producer build and snapshots remain valid after eligible native cleanup. |
| P4: current OrderBook | Apply P3's non-reclamation proof to the dedicated engine closure; generate its actual API including nested enums and pooled orders; run the paired workload and allocation checks. | `createLimit`, `cancel` and `reduceTo` export successfully under the proved permanent-storage contract; consumer imports actual classes without glue; correctness matches; no liveness bookkeeping is added to permanent scalar calls. Retention/cross-owner argument tests use the separate reclaimable fixture. Timing acceptance is deferred to P6. |
| P5: callbacks and Java exception propagation | Typed foreign calls/proxies, conservative effects, retained listener lifecycle, nested invocation contexts and callback-originated Java throwable propagation. Reuse P2/P3 native-to-Java translation. | Listener works as a Java interface; reentrancy, retained arguments and callback-triggered free tested; unchanged callback throwables preserve Java identity through nested calls, with carrier cleanup on catch/replace/retain paths; neither runtime unwinds across the boundary. |
| P6: distribution and final release readiness | Multi-target assembly, classloader/module integration, producer Maven/Gradle conventions, sources/Javadoc, license/source payloads, deployment diagnostics, final performance measurements. | Clean consumer machines need only supported Java and dependency; selected target/JDK matrix passes locally; package content reproducible and reviewed; final numerical performance acceptance recorded. |
| P7: measured optimization and API expansion | Evaluate FFM, bounded zero-copy, batching, additional arrays/generics based on real workload needs. | Each extension has a compatibility/proof contract, focused tests, allocation evidence, and machine-code/benchmark justification. |

P2 is a usable scalar preview, not completion of the requested object feature.
The first object release follows P0 -> P1 -> P2 -> P3 -> P4 -> P6. P5 callbacks
and P7 expansion are later capabilities, not prerequisites. P1 contains native
unwinding; P2 maps built-in exceptions and traces; P3 maps custom exceptions and
supported getters. All three are release prerequisites. Deferring P5 only
defers callback-originated Java failure propagation. Incomplete native-to-Java
translation or lifetime implementation is a release blocker,
not a documentation caveat. D191 settles section 14's contracts; implementation
remains a separate task.

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
| Initialization | Java's first call initializes once; recursive and failed initialization preserve existing behavior without a synthetic main. |
| Enum first use | In a fresh child JVM, `Side.SELL.index()` as the first native operation returns 1 without a prior book operation. A separate cold enum-argument fixture with asymmetric slots (for example BUY=11, SELL=29) selects SELL correctly; empty OrderBook counts of zero on both sides are not a discriminating test. Inspect typed IR and verify O0/O3 and source/class/archive paths. |
| Enum failure and confinement | A throwing enum initializer becomes a Java exception before target-body effects; repeated calls preserve native stored-failure semantics. Cover constant-specific bodies and null arguments. Preinitialize only the Java enum on another thread, then make the first native call on the designated thread: Java initialization performs no native work, and native initialization occurs on that caller. |
| Ownership | Independent fresh result accepted; result that also publishes an input stays conservative. Inline and helper versions agree. |
| Non-reclaimable exports | Unknown-origin pooled result and receiver publication accepted only with a complete permanent-storage proof; source/deferred free, destructor cleanup, temporary reclamation, deallocating pool release or generated destruction that can reach exposed storage defeats that proof. Exercise all dynamic targets and source/class/archive reconstruction. |
| Failed construction and dependencies | Proved unpublished constructor rollback remains allowed; an unaccounted escape or unknown deallocator cannot receive the non-reclaimable classification. A permanent holder without root slot state retaining a reclaimable object fails export; permanent self-storage does not excuse a dangling child. |
| Permanent identity | Repeated returns of the same live pooled allocation reuse the live Java facade; non-reclaimable facade exposes no generated free or liveness state. Enum results, including null and constant-specific bodies, use the existing immortal constant mapping. |
| Alias lifetime | Safe owner cleanup succeeds; cleanup with native publication/loan or live dependent use is rejected or prevented before dereference. |
| Pool behavior | Same-pool helper remains accepted; wrong-pool transfer and dangling native aliases remain rejected. |
| Host boundary | Legal same-world single-threaded call succeeds; repeated free, freed alias and callback-triggered free follow the selected lifetime contract. Cross-artifact facade collisions are rejected at bootstrap, not by a fictitious public wrong-world call. |
| Threading scope | Calls and callbacks execute on the calling thread; no injected thread checks, locks, or executor dispatch enforce confinement. No test promises safe rejection of multithreaded misuse. |
| Explicit free | Owner reclamation invalidates all dependent facades; borrowed free and active-callback free are refused under the accepted contract. Retention counters follow actual effects on normal and exceptional exits. |
| Retention-slot analysis (P0/P3) | Accept root-field set/replace/clear and proved read-only loads; cover aliases, helpers, all dispatch targets, writes to known argument roots and store-then-throw. Reject slot-to-slot copies/moves (including clearing the source afterward), hidden static/container publication, unresolved destination owners/effects and borrowed-child slots. Repeated stores of the same input into distinct slots count separately; refused free persists until the last release. Source/class/archive proof results agree. |
| Retention state lifetime (P3) | Root slot records survive root/child facade GC and wrapper recreation. Retaining a borrowed value protects its root; retaining on a borrowed child is rejected. Purely permanent graphs remain exempt, while permanent holders of reclaimable values fail export. |
| Marshalling | Unicode/NUL/surrogates and numeric extremes round-trip; invalid lengths, retention, aliasing, and failure cleanup cannot leak temporary pointers. |
| Built-in exceptions (P2) | Expected checked/unchecked Java catch type, `throws` declaration, message, representable causes/secondary failures and Ironwood source frames plus Java call site. Include initializer/repeated failure, bounded cycles and translation exhaustion; retained/emergency native throwables are not incorrectly freed. |
| Custom exceptions (P3) | Generated checked/unchecked types preserve superclass catches, declarations and snapshot getter values after native return/eligible cleanup. Unsupported getter/data shapes fail producer build. Repeat relevant P2 trace, failure and ownership cases with custom types. |
| Callbacks (P5) | Ordinary and throwing listeners work; nested calls preserve outer state and unchanged Java throwable identity; carrier cleanup covers catch/replace/retain paths. Unknown retention is never accepted as borrowing. Reuse the P2/P3 native-failure translator. |
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
native-access authorization tests are deferred. Use local targeted
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

Confirmed support range: Java 21-23 initially; Java 24+ is deferred. Keep facade
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
milestone assignment and D196's root-slot analysis, records these selected
contracts and resolves the earlier open alternatives. P0 validates this design.
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
- Preserve one live facade per native object. Use a world-owned root index and
  weak facade values in a per-root identity cache for reclaimable roots; use
  section 7's world-level cache for non-reclaimable objects. Both are accessed
  at object conversion rather than scalar calls. Keep reclaimable root state
  until explicit reclamation or world
  termination. Java collection of a wrapper never frees native storage; a later
  return can recreate a wrapper with the same root state. Discard the root's
  cache on free; old wrappers retain the dead state, and address reuse creates
  a distinct state. Cache maintenance must not call native code from a GC thread.
- Ownership is a property of the object, not the path by which an alias was
  returned. A self-return of an already Java-owned object reuses its owning
  facade and capability. Calling it a borrowed return does not revoke that
  existing capability. Borrowed children are distinct native objects and remain
  non-owning. Non-reclaimable results need no fabricated owner; they never gain
  a destruction capability. Reject projections requiring conflicting owners
  for one object.
- Repeated `free()` of a successfully freed owner is a no-op. Native access
  through any dead facade, or free of a borrowed, immortal, retained, or active
  object, throws `IllegalStateException`. Internal handle paths that admit a
  world mismatch reject it with `IllegalArgumentException` before native access;
  this is not an expressible first-release public argument scenario. A class or
  package collision instead fails bootstrap with `LinkageError`. Inherited
  identity equality compares unequal as specified in section 5. Preserve
  source-defined null behavior rather than imposing a blanket non-null rule.
- Refusals leave the root LIVE. Eligible destruction must be proved nonthrowing
  and callback-free, including initialization and destructor dependencies.
  Transition LIVE -> FREEING -> FREED; release outgoing retention counts only
  after native destruction succeeds. Reject a destruction capability lacking
  that proof. Do not invent a recoverable partial-destruction state or restore
  LIVE after native destruction starts. Fatal runtime failures remain fatal.
- Failure to construct or publish a new facade must either reclaim a proved
  unpublished allocation or preserve its already-established native owner.
  Preallocate required host state before native side effects wherever possible;
  failed Java allocation must not leave an untracked published native owner.

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

Prototype retention-slot write analysis in P0; implement and integrate it in P3.
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

Use this boundary protocol:

1. Validate receiver/argument liveness and world identity. Reserve all required
   host slots and bounded result storage before entering native code.
2. Run the native operation. Its generated entry catches native exceptions and
   records actual final references in the affected slots on both normal and
   exceptional exits, before throwable or result conversion. This inspects a
   fixed proved set of slots, not the heap or an arbitrary object graph.
3. Reconcile the stored old roots with the returned final roots, counting each
   retained slot separately. Complete all increments/decrements without Java
   allocation or user callbacks before returning or throwing into Java. If a
   method stores an order and then throws, that dependency still counts.
4. Constructor failure must prove rollback removes unpublished dependencies;
   otherwise reject the export. Freeing an owner releases its outgoing slots
   only after the nonthrowing native destruction has completed.

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
| API surface | Constructors, static/instance methods, primitives, copied strings with proved cleanup, concrete non-subclassable facades, enums/static nested types, owned roots, borrowed views and compiler-proved non-reclaimable results. P2 built-in exception/trace mappings and P3 copyable custom exception snapshots/getters are mandatory before release. |
| Deferred surface | Java callbacks/listeners (P5), arrays, general `CharSequence`/`Object` arguments (except inherited identity equality), source overrides of `equals(Object)`, exported reference generics, general native inheritance, Java subclassing, mutable public fields, and arbitrary object-graph conversion. Reject unsupported public signatures at producer build. Internal uses remain allowed when their boundary proofs hold. |
| Retention | Fixed fields on reclaimable roots with persistent host records only; P3 proves every write and rejects slot-value transfers or hidden publication. Borrowed-child retention slots and permanent holders of reclaimable targets are deferred. |
| OrderBook | Preserve the actual project's process-lifetime graph using P3's non-reclaimable classification. Demonstrate complete `free()`, retention and cross-owner argument behavior using a separate reclaimable owner/child fixture. Reclaimable production OrderBook is a separate producer change; it is not claimed by this release. |
| Platforms | macOS ARM64, Linux ARM64 and Linux x86-64. Reuse official IDK native baselines, including Linux glibc 2.17; record the macOS deployment target and required CPU features in the artifact. Effective support also requires a supported Java 21-23 JVM on that host. Do not advertise an older OS merely because the native payload can load there. |
| Loading | Ordinary class path, module path, and executable jars with standard dependency loading. Multiple bridge artifacts require disjoint generated packages. Validate generation identity on every resolved class and package marker before any native registration. One defining classloader per artifact per JVM; reject a second independent load before user-native initialization. Custom nested-jar loaders, relocated/shaded facades, isolated duplicate worlds, unloading and hot reload are deferred. |
| Release gate | Complete P0-P4 and P6. P5 and P7 are extensions. No callback signature is admitted before P5, so P3 does not depend on unfinished callback machinery. |

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

Use explicit checkpoints:

- Before P0: contracts A-C and structural criteria are settled in D191. Start
  the implementation branch when implementation is requested; this documentation
  task does not start it.
- After P0: review proof/prototype results and structural costs. Stop on a failed
  safety or feasibility case and propose a specific contract correction. Require
  paired accepted root-slot mutations and rejected transfers/child slots before P3.
- After each of P1-P4: review its exit criteria, focused regressions and remaining
  limitations before proceeding to dependent work. P3 must demonstrate partial
  mutation followed by an exception, aliases, address reuse, refused free, complete
  slot-write attribution and retention records surviving facade collection.
- After P6: review the entire release surface, target/JDK evidence and final
  numerical performance results. Passing P2 alone never closes the feature.
  Review P5/P7 separately when requested.

Record accepted architectural choices in `DECISIONS.md`, explicitly superseding
any earlier decision only when necessary. Synchronize `MEMORY.md`, `COMPILER.md`,
`LANGUAGE.md`, `LANGUAGE_SPECS.md`, compatibility docs, `IDK.md`, and bridge user
documentation as implementation lands. Scope JNI wording to the generated Java
host adapter: Ironwood still has no JVM execution backend or Java-platform
compatibility requirement. Update roadmap/status tables only for implemented,
verified capabilities; this plan changes no supported feature status.

The next implementation task starts with bounded P0 validation on the requested
new branch. The contracts are settled; only demonstrated design failures require
reconsideration. Proceed through the dependency checkpoints in focused changes.
