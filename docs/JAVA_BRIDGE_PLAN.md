<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood Java Bridge implementation plan

Status: design and implementation plan, updated 2026-09-26. No bridge implementation
is authorized by this document. Repository observations were checked at
`767e21d`; proposed classes, commands, tests, and output formats below do not
exist yet. The maintainer confirmed **Java 21 and newer** as the consumer
baseline and requested a comparison of explicit and automatic reclamation
before selecting a lifetime policy. Subsequent discussion confirmed that the
bridge remains single-threaded by caller contract, without runtime enforcement
of thread misuse; see
[D188](DECISIONS.md#d188---java-bridge-confinement-is-a-caller-obligation) and
section 8.

This plan reviews [the user-facing sketch](JAVA_BRIDGE.md) and
[the earlier proposal](IRONWOOD_JAVA_BRIDGE.md). Where they disagree, use this
document as the current planning draft, not as a new language specification.
Accepted compiler semantics, including mandatory safe reclamation and D132/D133,
remain unchanged. D188 records the accepted bridge threading contract; section
14 identifies the remaining decisions needed before implementation.

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
confinement, JVM native-access permission, and supported host platforms are
real boundaries. They must be stated before promising a drop-in replacement.

### Consumer experience to deliver

The following is a proposed Java call-site fragment using the **actual current**
OrderBook API, not a runnable bridge example today. Lifetime cleanup is omitted
here because its policy is still under review, not because cleanup is automatic.

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
| Java 21 JNI class-path application | No bridge-specific launch flags under ordinary JVM policy. |
| Newer JDK native-access policy | A JVM launch option or executable manifest grants access; there is no interactive approval dialog. A dependency cannot grant access to itself. |
| Native cleanup | Pending explicit-versus-automatic decision; never silently described as Java GC behavior. |
| Unsupported signature or unsafe ownership | Producer build diagnostic, not generated methods that fail only when called. |
| Unsupported platform or restricted extraction | Clear load-time diagnostic; no guessed binary or runtime download. |

JDK 24 introduced native-access restrictions for JNI as well as FFM. Current
JDK documentation describes warning and deny policies, with stricter defaults
planned. Design and test against denial, rather than relying on a warning being
harmless forever. Applications can grant access to the bridge module or, on the
class path, `ALL-UNNAMED`. See the
[JDK migration guide](https://docs.oracle.com/en/java/javase/26/migrate/migrating-from-jdk-8-later-jdk-releases.html).

An executable application's manifest can declare
`Enable-Native-Access: ALL-UNNAMED` for `java -jar`; that attribute in an ordinary
dependency jar does not authorize its consumer. Optional application packaging
integration can generate the executable manifest or launch configuration. It
cannot silently change an already-running JVM. See the
[JAR specification](https://docs.oracle.com/en/java/javase/25/docs/specs/jar/jar.html).

### Concrete native-access example

The familiar JNI flow still applies: load a library, resolve its native methods,
then call them. On ordinary Java 21 deployments, `System.loadLibrary` needs no
new native-access grant. From JDK 24, loading and native-method binding fall
under the newer policy. For example, JDK 25 defaults to allowing the operation
with a warning when permission is absent; explicit denial makes it fail with
`IllegalCallerException`. Permission is a JVM deployment setting, not a login,
OS permission prompt, or approval for every call.

These are illustrative Java launch commands for a future packaged bridge,
using the macOS/Linux class-path separator:

```sh
# Explicitly grant native access to jars on the class path.
java --enable-native-access=ALL-UNNAMED \
    -cp app.jar:orderbook-bridge.jar com.acme.Main

# JDK 25: exercise strict policy while granting the needed access.
java --illegal-native-access=deny --enable-native-access=ALL-UNNAMED \
    -cp app.jar:orderbook-bridge.jar com.acme.Main
```

Removing the grant from the second command rejects restricted loading/binding.
For a named bridge module, grant its actual module name instead of
`ALL-UNNAMED`. This setting permits native access; it does not locate or load
the library. Ironwood still performs loading automatically. See the
[JDK 25 launcher specification](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html).

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
| Java-side loan bookkeeping is free under Ironwood's performance policy | It costs the application and requires review, even if outside the Ironwood runtime. |
| Callback argument wrappers can be reused and retargeted | Arbitrary Java code can retain them. Reuse can change a retained reference's meaning or expose dead storage. |
| No upcall implies eligibility for a critical FFM call | Extremely short execution on every path is also required. No blocking, unbounded loops, or lazy first-use work can be assumed away. |
| One per-thread status/scratch block is enough | Nested Java-to-native-to-Java-to-native calls need independent active frames. |
| Raw global memory segments are a default implementation technique | Do not expose unbounded native memory to Java. Start with generated accessors and bounded copies. |
| Thread-local exception fields imply safe thread migration | The runtime also has unsynchronized image-global trace, emergency, allocation-limit, and type-initialization state. Audit the whole world. |
| Every throwable can be owned by a Java exception and closed | Initialization failures, rethrown aliases, and emergency throwables can be retained or immortal. Prefer Java-owned exception snapshots. |
| An existing Ironwood `close()` can automatically be followed by free | Resource closure and memory reclamation are distinct. Publication, failure, aliases, and repeated closure must be proved compatible first. |
| Current OrderBook supplies listeners and time-in-force enums | The current project explicitly omits them. Use separate callback fixtures. |
| Native calls have a fixed published nanosecond cost | Remove unsourced rankings and end-to-end promises. Measure this implementation and workload. |

The old proposal's `close()` preference is now a candidate under renewed review.
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
| [`EscapeSummaryAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java), [`SymbolicReturnOriginAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/SymbolicReturnOriginAnalyzer.java) | Summaries distinguish retention, publication, fresh results, aliases, and dependent borrows. | Reuse facts, but add explicit foreign-root/foreign-callback obligations; unknown facts remain conservative. |
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

Recommended first transport: **generated JNI**, with Java source compiled using
`javac --release 21`. JNI is available on the required baseline, needs no preview
API, and lets consumers use normal JVM types. This is a recommendation for the
implementation gate, not a claim that JNI is inherently faster or slower than
FFM for this workload.

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
dependency. Evaluate this versus direct LLVM JNI emission in the initial spike
and record the choice before broader implementation.

Use private generated native methods and explicit registration, with bootstrap
failure reported as Java linkage errors. Export only required JNI bootstrap
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

Use `ironwoodc --java-bridge --export <package>` as the proposed public workflow,
matching the shorter existing user sketch. It performs a closed-world library
link and packages a jar; it does not require `--main-class` or a dummy main.
Keep lower-level shared-link options internal until an independent use case
needs a public contract.

Proposed producer recipe, not supported by the current compiler:

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
arguments form a union. Include public top-level and accessible nested types,
their public callable surface, and the minimal accessible signature closure.
Include inherited visible members and interface defaults in validation. Do not
export every implementation dependency merely because its code is reachable.
Reject inaccessible signature types with source locations and an explanation.

An exported package is an API commitment. An unsupported public member must fail
the bridge build with a useful diagnostic, rather than silently disappear or
become a runtime trap. A narrower producer package is an ordinary API boundary,
not a handwritten binding requirement. Optional per-type selection can be
considered later; bridge annotations are not needed for the first release.

| Surface | First supported treatment and boundary |
| --- | --- |
| Primitive methods, overloads, constructors, static methods | Generate the same Java signatures; preserve overload resolution, widening, access, ordered evaluation, and exceptional edges. |
| Final concrete objects | Primary object milestone. No Java subclass can override native behavior. |
| Native inheritance | Preserve assignability and dynamic dispatch in generated hierarchies when implemented. Do not make every facade final if exported inheritance requires otherwise. |
| Java subclassing of native classes | Initially reject export shapes requiring it; do not generate an apparently extensible class whose overrides native calls ignore. Interfaces are the planned callback boundary. |
| Enums | Generate real Java enums with named constant mapping, custom exported methods, and initialization behavior. Do not assume ordinal stability across builds. |
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
| `ironwood.*` library types | Project only supported signature closure, preserving their actual contracts. Do not fabricate a Java Collections Framework mapping for `ironwood.ds`. |

Specify `equals`, `hashCode`, `toString`, nulls, self returns, repeated borrowed
returns, casts, and `instanceof` before object support. Proposed identity rule:
one live Java facade per exposed native object within its world, so repeated
returns preserve `==`. That requires a canonicalization mechanism at object
conversion and careful cache lifetime; it is not free. Share its ownership
state across all views. Do not put identity lookups on primitive-only calls.

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
and whether a complete destruction capability is established. Preserve mixed
fresh/borrowed/null alternatives or reject them; do not choose the favorable
alternative. Revalidate after specialization changes callable identities.

### Entry and exception boundary

Bootstrap validates the paired build and registers image-local trace metadata
once. Each exported operation preserves lazy type initialization at the correct
active-use point, including constructor failure and stored initializer failure.
Do not eagerly initialize every exported class during library loading.

Every native entry contains Ironwood unwinding before it reaches JNI/JVM frames.
Prefer a native adapter stack frame with bounded result/error storage over the
old global segment and per-thread scratch default. A proposed JNI sequence is:

1. Validate the Java-side world/lifetime obligations selected at the design gate.
2. Marshal supported arguments with cleanup established before any failing step.
3. Invoke the typed entry with an invocation-local result/error frame.
4. Convert success, or materialize the Java throwable after native unwinding ends.
5. Release only temporary native/JNI resources whose ownership is established.

Audit runtime `exit`, `abort`, process-global facilities, environment handling,
stdio, sockets, and optional TLS dependencies. Ordinary source exceptions must
be catchable by Java; genuine fatal runtime failures remain process-fatal unless
a separately proved recovery mechanism exists. An installable handler cannot
make arbitrary fatal unwinding recoverable. Do not redirect Java streams or
change JVM signal handlers as an unannounced bridge side effect.

## 7. Reclamation: compare before selecting

This is the largest unresolved design choice. Arbitrary Java code cannot be
statically analyzed by Ironwood. A native pointer's stability does not prove its
liveness. Neither `AutoCloseable` nor `Cleaner` establishes safe reclamation by
itself. The compiler must prove the native part of the ownership contract, and
the host boundary must establish the remaining obligations.

### Candidate policies

| Policy | Java experience | Benefits | Costs and unresolved obligations |
| --- | --- | --- | --- |
| Explicit `AutoCloseable` | Try-with-resources or explicit `close()` for owners | Deterministic release on the calling thread; no GC-driven destruction schedule | Visible lifecycle; aliases, loans, and in-flight reentrant calls still need a lifetime design. Thread misuse is outside the contract. |
| Automatic cleanup | Ordinary objects; cleanup after Java reachability ends | Closest to Java usage | Cleaner registration/state, delayed reclamation, cross-runtime cycles, owner-thread scheduling, memory pressure, and nondeterministic release. |
| Explicit plus automatic fallback | Deterministic close with leak fallback | Familiar native-resource pattern | Both sets of machinery; cleanup races and exactly-once destruction still need proof. |
| Process-lifetime native objects | No reclamation operations | Smallest safe starting capability for bounded engines | Unbounded creation leaks native memory; not general-purpose Java object semantics or a final answer for long-lived services. |

Prototype and measure the first three; use the fourth only as a clearly named
development capability or an accepted application contract. Do not quietly
make process lifetime the released default. The maintainer has not selected
explicit or automatic cleanup.

Use the same small owned graph, child view, retained listener, and repeated
create/destroy workload for all candidates. Compare source ergonomics, bytes
allocated per object/call, steady-state crossing cost, peak native memory,
cleanup latency after an idle owner, and behavior under callback cycles and
failure. Explicit cleanup should prove deterministic release; automatic cleanup
must establish a credible bounded-memory story for long-lived services without
claiming prompt GC. Decide using those results and the intended workload, not
the assumption that a Cleaner makes native ownership disappear.

### Requirements common to any reclaimable facade

- A generated destruction entry is a capability, not a universal `free(long)`.
  Prove that the object is host-owned, its native aliases are accounted for, its
  destructor is valid, and no native static or unrelated live object can reach
  storage that would be freed. Freshness alone is insufficient.
- Prevent new native publication from invalidating a previously granted
  capability. Reject an unrepresentable retaining operation at producer build
  time, or represent its loan/transfer in the approved host protocol.
- All Java aliases, including child views and multiple interface/base views,
  share liveness authority. A strong reference to the owner prevents Java GC,
  but does not by itself prevent explicit owner closure.
- Reject or safely defer closure during a call or callback that still uses the
  owner. Closing the root from its own listener must never free a live frame.
- Retention can involve multiple borrowers, repeated insertion, replacement,
  exceptions, and partial success. A single boolean `onLoan` is insufficient.
- Separate native storage lifetime from pool checkout validity. A reused live
  pool object is not a freed object, but a stale logical order may already have
  different contents. Preserve the source pool contract and never silently
  promise immutable snapshots.
- A generated class cannot hide `close()` depending on how an instance was
  returned. Consider distinct owner/view projections, uniform close with
  precisely defined capabilities, or automatic-only facade lifetime. Compare
  API fidelity and diagnostics before choosing. Do not emit a method that
  unexpectedly fails for valid source uses solely due to hidden ownership.
- Existing Ironwood `close()` retains its resource contract. Reclamation must
  not be appended in `finally` without proof that both success and failure paths
  permit it. Diagnose unresolved name/semantic collisions before generating a jar.

For an explicit candidate, model owner state such as OPEN, CLOSING, and CLOSED,
shared with dependents. Establish when idempotence applies, what happens after
a failed source `close()`, and whether a loan rejects or postpones closure.
Methods must never dereference native storage after reclamation. Canonical
facades also need protection against address reuse when new objects are allocated.

For an automatic candidate, distinguish Java reachability from the **combined**
Java/native retention graph. JNI global references can keep listeners alive;
a listener can retain its owner facade and form a cycle invisible to simple
Cleaner logic. Define how those cycles are released or reject such retention
shapes. Keeping every facade strongly in a canonicalization map also prevents
automatic cleanup. Do not substitute weak references without resolving races.

Cleaner actions run on a cleanup thread and have no guaranteed execution at
process exit. They must not invoke a thread-confined world directly. An
owner-thread queue leaves memory unreclaimed when that thread becomes idle or
terminates; draining it at every native call adds steady-state work. A dedicated
world executor is outside the accepted scope: the bridge does not route calls
onto worker threads. Compare an owner-thread cleanup checkpoint against explicit
cleanup, making the extra work and idle-thread limitation visible.
See [Cleaner](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/Cleaner.html).

### Examples of the extra lifetime machinery

These examples describe proposed behavior, not current generated classes. They
assume compiler-proved reclaimable native storage, which the existing OrderBook
pool graph does not yet provide.

1. **An independent object.** A Java `NativeCounter` facade holds a native
   counter. When the facade becomes unreachable, the JVM can discover that its
   Java storage is dead, but cannot infer that its private integer handle owns
   an Ironwood allocation. Generated cleanup state must associate the facade
   with a destruction capability. Under D188 a Cleaner could enqueue that
   capability, but the owner thread must eventually perform destruction. The
   cleanup state must not itself keep the facade alive.
2. **A borrowed child.** Java obtains `child = owner.child()` and drops its
   direct reference to `owner`. The child still needs the parent's native graph.
   A generated strong child-to-owner link can preserve Java reachability.
   If explicit `owner.close()` is also available, that link alone cannot
   prevent premature native destruction; the lifetime contract must cover it.
3. **A listener cycle.** A native owner retains a Java listener through a JNI
   global reference, and the listener holds the owner's facade. The global
   reference keeps the entire Java chain reachable, so a Cleaner for the owner
   never runs. An explicit release operation, a different callback ownership
   design, or a restricted supported shape is needed. Java GC does not discover
   ownership edges hidden in an arbitrary native graph.

For contrast, an explicit candidate would generate `AutoCloseable` for an
eligible owner. A Java try-with-resources statement calls its destruction entry
on the same thread at block exit, without waiting for GC or queue draining.
That solves cleanup scheduling, not aliases, native publication, or reentrant
close. The combined candidate retains those checks and adds automatic fallback
with the same owner-thread limitations. Neither policy is selected yet.

Native memory pressure is another difference: a small Java facade may own a
large native graph. Do not assume that ordinary Java heap pressure will trigger
GC soon enough to bound native usage, or promise prompt reclamation after the
last Java reference disappears.

If reachability-triggered cleanup is adopted, establish reachability through
the entire native operation, including borrowed argument owners and exceptional
paths. Evaluate generated `Reference.reachabilityFence` use; it does not solve
explicit close races or native retention. See
[Reference](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/Reference.html).

### Safety/performance decision gate

Lifetime state checks, loan counts, canonicalization, cleanup
queues, and JNI reference operations cost time or storage. Moving them to Java
does not exempt them from [D132/D133](DECISIONS.md#d132---stack-traces-use-on-demand-native-decoding-without-runtime-bookkeeping)
or `AGENTS.md`. Before implementing them, review measured alternatives and
approve a narrowly specified **host-boundary** cost, or restrict the export
surface to cases proved safe without it. Do not weaken Ironwood's native free
proofs or introduce continuous instrumentation into ordinary native calls.
Thread checks, per-object/world locks, and executor dispatch to police
multithreaded misuse are excluded by D188, not pending additions at this gate.

Until that gate is resolved, implement only independently safe foundations;
do not ship arbitrary reclaimable object handles with a documented unsafe-close
obligation. The first release needs a coherent lifetime policy, not several
unexplained modes that consumers must wire together.

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

On native failure, snapshot the supported throwable data into a normal Java
exception: matching catch hierarchy, message, causes/secondary failures where
representable, and Ironwood source frames followed by the Java call site. Do
not force consumers to close exceptions. Custom exception getters require
copyable data or a proved lifetime projection; unsupported shapes are diagnosed.
Copy limits and cycles must preserve a useful bounded failure representation.

Determine native ownership independently: fresh translation temporaries can
be reclaimed when proved safe, but stored initialization failures, borrowed
throwables, and emergency occurrences must not be blindly destroyed. Test
repeated failure and out-of-memory translation without recursively allocating
another failing bridge exception. Snapshot translation alone does not promise
that every native thrown object becomes reclaimable.

After a JNI callback, detect a pending Java exception before making further
ordinary JNI calls. Preserve its identity in the active invocation frame,
clear the pending JNI state only as required for controlled translation, and
raise a typed native foreign-failure carrier if Ironwood must catch it. At the
outer boundary rethrow the original Java throwable when it propagates unchanged.
If native code catches, replaces, or retains it, define carrier ownership and
reference release on every path. Never longjmp across JVM or Ironwood frames.
See [JNI functions](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html).

## 9. Values and performance

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
Cleanup registration belongs to object creation, while automatic queue draining
would be extra work wherever it is scheduled. These costs are not the bare JNI
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

The proposed bridge jar contains generated `.class` files, artifact-private
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
step combines independently built payloads only when their Java API and bridge
schema agree. Distinguish a logical API/schema identity from each native build's
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
4. Load the exact absolute path, then validate bootstrap schema/API/build identity
   before binding user operations. This cannot sandbox malicious native payloads.
5. Initialize artifact-private support once, without eager source-class effects.
6. Keep the image alive while its objects, callbacks, or code addresses remain
   reachable. Object closure is not permission to unload the native world.

Classloader behavior is part of the release gate. JNI associates loaded native
libraries with classloaders and rejects loading the same library into multiple
loaders. Isolated loaders therefore need an intentional strategy, such as
separate private extracted images with verified runtime isolation, or a clearly
diagnosed restriction. Never accidentally share a process-global Java singleton
between unrelated bridge artifacts. See the
[JNI invocation specification](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/invocation.html).

Hide or uniquely scope runtime symbols and inspect exported symbols on every
target. Audit linked unwind/C++ support and optional host/TCP/TLS dependencies
for interposition, unresolved libraries, and per-image state. Two libraries
containing similarly named source types must not share native descriptors.
Validate world identity for object parameters where Java typing alone cannot
distinguish them, particularly shared interfaces and internal raw handles.

Test plain class path, module path, executable jars, supported nested/fat-jar
loaders, two artifacts, and isolated loaders. Preserve resources under shading;
either support class relocation with regenerated registration metadata or
diagnose/document it as unsupported. Test extraction failure, read-only/noexec
locations, paths with spaces, checksum mismatch, missing symbols, and native
access denial. Offer advanced location overrides only for real deployment
constraints; ordinary use must require none.

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

Before claiming reclaimable OrderBook support, either prove and implement an
explicit ownership/destructor change as separately reviewed producer work, or
use a dedicated bridge fixture with a provable graph. Preserve the current
benchmark's intentional lifetime and source equivalence. If the analyzer cannot
establish boundary safety for the existing project, report that exact gap and
do not grant project-name-based exemptions.

For acceptance, run the paired scenario, verify price-time ordering, fills,
reductions, cancellations, match count/volume, final maker ID, and full pool
recovery. Add retained-order and cross-book misuse cases according to the
chosen boundary contract. A separate small listener fixture exercises callbacks
without silently adding them to the benchmark engine.

## 12. Implementation phases and exit criteria

Each phase should be split into focused commits. Do not start a later safety-
dependent phase while its contract is unresolved. Test names below for new
bridge behavior are proposed, not existing commands.

| Phase | Work and concrete deliverable | Exit criteria |
| --- | --- | --- |
| P0: design gates and bounded experiments | Compare JNI adapter shapes, lifetime policies under D188, identity, and boundary cost. Write further accepted decisions after review. Experiments are a later authorized task. | Java 21 demonstrated; supported API matrix and lifetime costs accepted; no reliance on unsafe-close documentation. Multithreaded misuse remains outside the contract. |
| P1: multi-root native library foundation | Output kind, typed export roots, optimizer propagation, shared link flags, visibility, bootstrap, image-local traces. Use scalar static entries and a private host harness. | No main required; callable reachable only from Java retained; unreachable code pruned; first-use and failed initialization correct; unwinding contained at O0/O3. |
| P2: first plug-and-play jar | Deterministic export model, Java 21 source/classes, generated JNI, loader, manifest pairing, one-target jar. Static primitives and copied strings where cleanup is proved. | Plain Java consumer builds/runs from jar without native tools or manual loading; unsupported export rejected; bad platform/build/permission errors actionable. |
| P3: object and lifetime model | Constructors, identity, supported hierarchy/enums, owned/dependent contracts, selected cleanup policy, failure rollback, source `close()` collision handling. | In supported single-threaded use, shared aliases cannot reach freed memory; retaining operations obey proofs; wrong-world values rejected; repeated construction and cleanup verified. |
| P4: current OrderBook | Generated actual API including nested enums and pooled orders; paired Java workload and allocation measurements. | Consumer imports actual classes without glue; correctness matches; process-lifetime versus reclaimable support reported precisely; hot-call costs measured. |
| P5: callbacks and complete failure semantics | Typed foreign calls/proxies, conservative effects, retained listener lifecycle, nested invocation contexts, original Java exception propagation and native snapshots. | Listener works as a Java interface; reentrancy safe; retained arguments and callback-close tested; neither runtime unwinds across the foreign boundary. |
| P6: distribution readiness | Multi-target assembly, classloader/module integration, producer Maven/Gradle conventions, sources/Javadoc, license/source payloads, deployment diagnostics. | Clean consumer machines need only supported Java and dependency; selected target/JDK matrix passes locally; package content reproducible and reviewed. |
| P7: measured optimization and API expansion | Evaluate FFM, bounded zero-copy, batching, additional arrays/generics based on real workload needs. | Each extension has a compatibility/proof contract, focused tests, allocation evidence, and machine-code/benchmark justification. |

P2 is a usable scalar preview, not completion of the requested object feature.
The first object release requires P3/P4/P6; if it advertises the older promise
of Java listeners, P5 is also mandatory. An incomplete lifetime policy is a
release blocker, not a documentation caveat.

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
| Ownership | Independent fresh result accepted; result that also publishes an input stays conservative. Inline and helper versions agree. |
| Alias lifetime | Safe owner cleanup succeeds; cleanup with native publication/loan or live dependent use is rejected or prevented before dereference. |
| Pool behavior | Same-pool helper remains accepted; wrong-pool transfer and dangling native aliases remain rejected. |
| Host boundary | Legal single-threaded call succeeds; double closure, closed alias, wrong world, and callback-triggered close follow the selected lifetime contract. |
| Threading scope | Calls and callbacks execute on the calling thread; no injected thread checks, locks, or executor dispatch enforce confinement. No test promises safe rejection of multithreaded misuse. |
| Automatic candidate | Reachable dependent protects owner; cleanup cannot race an active call. Test callback cycles and abandoned owner threads without assuming GC timing. |
| Marshalling | Unicode/NUL/surrogates and numeric extremes round-trip; invalid lengths, retention, aliasing, and failure cleanup cannot leak temporary pointers. |
| Exceptions | Ordinary native failure becomes catchable Java exception; initializer/emergency/rethrown native objects are not incorrectly freed. |
| Callbacks | Ordinary and throwing listeners work; nested calls preserve outer state; unknown retention is never accepted as borrowing. |
| Isolation | Two independent artifacts work; mismatched image/type/build and symbol collisions fail safely or are excluded before publication. |
| Performance | Primitive steady state has measured allocations and crossing cost; no bridge bookkeeping appears in ordinary native-only call paths. |

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

Test Java 21 and selected newer releases, including explicit native-access
denial and granted access, on supported target hosts. Use local targeted
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

## 14. Decisions to review next

Confirmed requirement: Java 21 and newer. Confirmed process choice: compare
explicit and automatic lifetime management before selecting either.
Confirmed contract: D188, single-threaded access as a caller obligation without
runtime enforcement of multithreaded misuse. This is no longer an open gate.

The next design discussion should resolve these items in dependency order:

1. **Lifetime and boundary cost.** Select explicit, automatic, or combined cleanup;
   settle owner/view API shape and existing `close()` collisions. Approve the
   exact necessary host-boundary checks/state, or narrow the surface further.
2. **Cleanup scheduling under D188.** If automatic cleanup is selected, define
   how destruction runs on the calling thread, what happens when it becomes
   idle, and how much extra work is acceptable. Do not introduce a worker-thread
   execution model or multithreading support to solve cleanup implicitly.
3. **Identity and retention.** Approve canonical facade semantics and costs;
   specify retained listeners, native publication, pool views, and unrepresentable
   graph diagnostics. Automatic cleanup must cover cross-runtime cycles.
4. **Transport and packaging conventions.** Ratify generated JNI with isolated
   C adapters, the single-jar default, package export selection, Java 21 class
   output, and the proposed command spelling. FFM remains a measured extension.
5. **First release surface.** Set callback inclusion, supported OS/ABI/JDK pairs,
   array scope, classloader scenarios, and the OrderBook reclamation expectation.

Record accepted architectural choices in `DECISIONS.md`, explicitly superseding
any earlier decision only when necessary. Synchronize `MEMORY.md`, `COMPILER.md`,
`LANGUAGE.md`, `LANGUAGE_SPECS.md`, compatibility docs, `IDK.md`, and bridge user
documentation as implementation lands. Scope JNI wording to the generated Java
host adapter: Ironwood still has no JVM execution backend or Java-platform
compatibility requirement. Update roadmap/status tables only for implemented,
verified capabilities; this plan changes no supported feature status.

The next implementation task should be a bounded P0/P1 investigation after
the relevant decisions are accepted, not an attempt to implement the entire
bridge in one change.
