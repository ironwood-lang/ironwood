<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge producer

The producer builds host-target Java dependencies for macOS ARM64,
Linux ARM64 and Linux x86-64, exposing primitive,
copied-String and proved copied primitive-array APIs, Java-owned bounded ByteView
storage, roots and borrowed object views with bounded retention,
permanent concrete objects, enums, custom exception snapshots and bounded
synchronous Java listeners. Consumers call
generated Java classes and catch mapped Java exceptions using ordinary dependency
loading. General object inheritance, object/multidimensional arrays, callback shapes outside the proved
subsets and optional TLS dependencies are not supported; the producer rejects them at compile
time, and the [implementation plan](JAVA_BRIDGE_PLAN.md#5-export-discovery-and-java-api-fidelity)
records them as deferred work (D246).
P7d1 admits read-only, factory-produced final reference-generic facades under
D236. P7d2 adds construction/mutation and inputs when every variable has one
exported final facade bound under D237. Generic methods remain rejected.
See [read-only generics](#read-only-generic-facades), [bounded inputs](#bounded-generic-inputs) and
the [generic progress/evidence log](JAVA_BRIDGE_GENERIC_PROGRESS.md).

## Build and run

The producer supports JDK 21, 22, 23, 24 and 25, with compiler/Javadoc tools and JNI headers,
LLVM 23 and the matching macOS SDK or pinned Linux glibc 2.17 sysroot/private
runtime SDK. Build the checkout compiler using the repository's normal
instructions, with that JDK selected. A generated consumer requires only Java
21 to 25 and the jar on a compatible host; on Java 24 and 25 the JDK's
native-access policy applies, as described under
[runtime and distribution contracts](#runtime-and-distribution-contracts). The macOS image declares its real
minimum OS version; Linux images declare their glibc 2.17 baseline and include
their pinned compiler runtimes. Windows, musl and 32-bit hosts are unsupported.
One host build contains one target; combine matched host jars using the assembly
step below. The [distribution candidate and three-target evidence](JAVA_BRIDGE_P6_EVIDENCE.md)
are recorded, including physical Linux x86-64 execution under D213. Final
OrderBook numerical acceptance is recorded in D225. The P5 callback measurements, accepted
in D246, have their own [three-target qualification and measurement report](JAVA_BRIDGE_P5_EVIDENCE.md).
Rosetta observations remain separate functional/static evidence.
JNI is the default transport under D238. D241 adds producer-selected
[critical calls](#critical-calls) for proved object entries, with JNI kept as
the registered fallback.
The [combined P7 qualification record](JAVA_BRIDGE_P7_QUALIFICATION.md) tracks
the current array, ByteView, generic and listener artifacts together.

Select the JDK with `JAVA_HOME=/absolute/jdk/home`. Build scripts and launchers
use that JDK's Java, javac and jar, even if PATH names another JDK. Without
JAVA_HOME, an IDK uses its bundled JDK; a source/host installation resolves Java
from PATH and uses that runtime's home. Invalid explicit selections fail without
fallback. IDK launchers retain their bundled native toolchain when Java is
overridden. Direct `java -jar ironwoodc.jar` uses the explicitly invoked runtime's
compiler/Javadoc modules and JNI headers, independently of PATH or JAVA_HOME.
Missing components have build diagnostics. Maven and Gradle producer examples
pass their running JVM's `java.home` into the producer as JAVA_HOME.

An IDK includes LLVM and, on Linux, the complete pinned bridge support SDK.
Unpack it and use its launchers without native-tool environment overrides.
On macOS, install Apple's Command Line Tools or Xcode; the compiler selects the
SDK and Apple linker through `xcrun` and passes their paths explicitly to LLVM's
Clang driver. SDK 26.5 and 27.0 are qualified. `DEVELOPER_DIR` and `SDKROOT` may
explicitly select installed Apple tools; invalid selections fail without fallback.
Native artifact identity includes the actual SDK stub/settings hashes and linker
version/hash. Java consumers do not need these build tools.

All compiler/facade/consumer builds retain `--release 21`; this does not add
Java 22/23 source syntax or APIs to Ironwood. A host artifact produced with any
supported JDK runs on each supported JVM for that native target. Javadoc output,
compiler classes and JNI header bytes can differ between JDK versions. Native
build identity records the selected JDK version/vendor and exact header hashes.
Assembly still requires identical compiler/runtime generations and common
classes, sources, Javadoc, licenses and shared ByteView dependency bytes. Use one
identified compiler build and matching producer JDKs across the target hosts;
changing the assembly JDK alone does not relax those checks. Reproducibility is
checked for identical inputs/toolchains, not asserted across different JDKs.
See the [JDK compatibility verification record](JAVA_BRIDGE_JDK_PROGRESS.md).

For example, select JDK 23 to build and produce the callback example, then run
its Java 21-compatible consumer on all three JVMs. Set JDK21/JDK22/JDK23 to the
installed JDK home directories. From an extracted IDK, run:

```sh
export JAVA_HOME="$JDK23"
export PATH="$PWD/bin:$JAVA_HOME/bin:$PATH"
./examples/java-bridge/basics/compile.sh
./examples/java-bridge/basics/link.sh
for jdk in "$JDK21" "$JDK22" "$JDK23"; do
  "$jdk/bin/java" -Xcheck:jni \
    -cp examples/java-bridge/basics/target/ironwood-basics.jar:examples/java-bridge/basics/target/consumer-classes \
    org.ironwood.javabridge.basicsconsumer.Main
done
```

Each JVM prints `Java listener: 2`, `Java listener: 5`, `Counter total: 5` and
exits zero. For a source checkout, first run `JAVA_HOME="$JDK23" ./scripts/build.sh`
with LLVM 23 selected and the native support described below prepared on Linux.

Linux source/host producers first prepare the [pinned native support SDK](JAVA_BRIDGE_NATIVE_SUPPORT.md)
and set `IRONWOOD_BRIDGE_SUPPORT_HOME` to that target's prepared directory.
Linux IDKs already include and verify this SDK at
`toolchain/ironwood-bridge-support`; no setup or override is needed. Host
packaging audits final ELF architecture, eager binding, relative dependency paths
and required glibc symbol versions. The jar carries complete support source,
recipes and licenses. Loading extracts and checks only the two required private
runtime libraries plus the bridge image, without consumer loader-path options.

The complete [value example](../examples/java-bridge/README.md) separates ordinary
Ironwood compilation, bridge linking and Java execution:

```sh
export PATH="$PWD/bin:$PATH"
./examples/java-bridge/value/compile.sh
./examples/java-bridge/value/link.sh
./examples/java-bridge/value/run.sh
```

The link step uses the public command:

```sh
ironwoodc --java-bridge --export org.ironwood.javabridge.value \
  -cp examples/java-bridge/value/target/classes -O3 \
  --license LICENSE-MIT --license LICENSE-APACHE \
  -o examples/java-bridge/value/target/ironwood-values.jar
```

Source inputs may instead be supplied as `.iron` files or discovered through
`--source-path`. Compiled class directories and `.ironjar` dependencies use `-cp`.
Repeat `--export` for each exact package; it does not recursively export child
packages. The producer checks the complete public surface of every selected
package and its signature closure. It never silently omits an unsupported public
member to make a jar build. Missing or unsafe ownership/retention proofs fail
production even with `--unfreed=off`; that option controls only missing-free
diagnostics. Existing output is replaced atomically after the staged artifact
passes compilation, signature, inventory and archive checks.

Class path, automatic module path and ordinary executable jars with manifest
Class-Path dependencies are supported launch forms. The generated jar's
Automatic-Module-Name is stable for its producing basename; inspect it with
`jar --describe-module --file <artifact.jar>`. Give independent modules distinct
producing basenames. Renaming a finished jar does not rename its declared module
or change its generation. Shading/relocation, nested-jar custom loaders, duplicate
native worlds, unloading and hot reload are outside the supported boundary.

## Assemble host builds

Build every host jar from the same compiler/runtime, complete source or compiled
input closure, producer basename, exports and distribution inputs. Use the pinned
native toolchain and the same producer JDK version/vendor on each matching build host. Copy the
completed jars back without modifying their contents. Assembly needs the matching
Ironwood compiler/runtime distribution and a JDK 21 to 25, but no native compilation:

```sh
ironwoodc --java-bridge-assemble -o dist/engine.jar \
  builds/macos-arm64/engine.jar \
  builds/linux-arm64/engine.jar \
  builds/linux-x86_64/engine.jar
```

The assembler requires equal complete generations, APIs, declaration inventories
and common Java/source/Javadoc/license bytes. It checks each native build digest,
target constraints, payload hashes and private dependency/source delivery. It
rejects duplicates, incomplete inventories and mismatches before replacing an
existing output. Native payloads remain byte-identical, including macOS signatures.
Only the artifact-private loader is regenerated for the combined target inventory.
The manifest records each target's build and dependency metadata separately.
Input order does not affect output bytes. A one-target assembly is also supported;
assembly does not manufacture an absent target or qualify untested hardware.

Repeated shared-image builds with the same recorded inputs use canonical trace
metadata ordering before linking. macOS additionally uses a stable install name;
temporary staging paths do not become install names. Assembly and companion packaging preserve the
resulting native bytes; neither step signs or repairs an already produced image.

## Actual OrderBook engine

Build a dedicated engine directory, selecting only its entry source and resolved
dependencies. The project package also contains benchmark/demo entry classes;
compiling the entire source directory would include them in the selected export.

```sh
bin/ironwoodc --unfreed=off \
  --source-path projects/OrderBook/src/main/ironwood \
  -d projects/OrderBook/target/bridge-classes \
  projects/OrderBook/src/main/ironwood/org/ironwood/orderbook/OrderBook.iron
bin/ironwoodc --java-bridge --export org.ironwood.orderbook --unfreed=off \
  -cp projects/OrderBook/target/bridge-classes -O3 \
  -o projects/OrderBook/target/orderbook.jar
```

Use a fresh dedicated output directory. Java consumers import the actual
`org.ironwood.orderbook.OrderBook`, `Order` and nested `Order.Side`/`Order.Type`.
The compiler proves the pool graph's permanent native lifetime. There is no
generated `free()`; collecting a facade does not reclaim pool storage. Active
order handles are valid only while resting. A fully matched `createLimit` result
may already be reset and returned to the pool. Strongly held wrappers may be used
for identity comparisons across reuse, without treating a released order as an
active business handle. The [P4 audit](JAVA_BRIDGE_P4_EVIDENCE.md) records paired
behavior, capacity errors and separate hit/miss allocation results.

## Critical calls

A JNI call changes the Java thread's state on entry and on return. That
transition costs several nanoseconds, which dominates short native operations.
The producer can instead call proved entries without the transition:

```sh
ironwoodc --java-bridge --export org.ironwood.orderbook --unfreed=off \
  -cp projects/OrderBook/target/bridge-classes -O3 --critical-calls=on \
  -o projects/OrderBook/target/orderbook.jar
```

The default is `--critical-calls=off`, which generates exactly the JNI-only
artifact. The option changes generated declarations, so it selects its own
generation; build every host jar of an assembly with the same setting.

**What the producer selects.** A binding gets a critical adapter only when:

- it is a static or instance method of an object projection, not a constructor;
- every parameter is a primitive or an enum, and the result is void, a
  primitive or a permanent object facade;
- it needs no root state, root reservation or retention slot;
- its complete native closure, including type initializers and cleanup, has
  only resolved native calls and memory-only operations: computation, field and
  array access, allocation and exception unwinding. A Java callback, an
  unresolved call, console, file, stream, socket, TLS, clock, environment or
  process operation, or an unaudited String operation keeps JNI.

Everything else keeps JNI, including String, array, ByteView, enum and root
results, methods of enum constants, and the value and callback projections.
The producer reports how many bindings were selected. Selection never admits
an export and never replaces an ownership, retention or exception proof.

**What the producer asserts.** A critical call cannot be interrupted for a JVM
safepoint. Garbage collection and every other safepoint operation, for all Java
threads, wait until it returns. The compiler proves that a selected entry cannot
call Java or block in a runtime service. It does not bound running time: loops,
allocation, type initialization and raising an exception are admitted. Select
critical calls only when every exported operation is short. Deep native
recursion can exhaust the Java thread's stack without recovery, as it can
under JNI.

**What the consumer sees.** The public API, exceptions, native traces, facade
identity and lifetime checks are unchanged. Each selected method keeps its
registered JNI method and uses it whenever the handle is unavailable. Java
21-25 are the supported versions and no `--enable-preview` option is
needed. On Java 21 the handles use the preview foreign-function API through
reflection; on Java 22 to 25 they use the final API.

Linking a handle is a restricted operation in the JDK, and on Java 24 and 25 so
is the loader's `System.load` (JEP 472). One grant covers both, because they are
made from the same module. Launch policy decides what happens:

| Launch | Java 21-23 | Java 24-25 |
| --- | --- | --- |
| `--enable-native-access=ALL-UNNAMED` (class path), `--enable-native-access=<artifact module>` (module path) or `Enable-Native-Access: ALL-UNNAMED` in an executable jar's manifest | Critical calls, no warning | Critical calls, no warning |
| No native-access option | Critical calls; the JVM prints its restricted-method warning once | Critical calls; the JVM prints its restricted-method warning once, at `System.load` |
| Native access enabled only for other modules | JNI, no warning | Critical calls with the same warning (the default `--illegal-native-access=warn` policy applies) |
| `-Dironwood.bridge.calls=jni` | JNI, no warning | JNI; the warning still appears unless native access is granted |
| `--illegal-native-access=deny` | Not an option on these releases | No native use at all: loading fails cleanly (see the contracts section) |

The artifact never grants itself access or hides a refusal. A failing critical
call parks its protected-entry status for the calling thread, and a JNI helper
then delivers the same translated exception. A successful call reads no shared
state unless its result is `long` or `double`, which have no spare value to
report failure; those read one pending-failure counter.

See the [measurements and design record](JAVA_BRIDGE_CRITICAL_CALLS.md) and
D241.

## Read-only generic facades

A final top-level native `Box<T>` can expose `T get()` and factories returning
`Box<Quote>` and `Box<Trade>`, where Quote and Trade are exported final,
nongeneric native facade classes. Its constructors must already be inaccessible
to Java and no public method may accept a value containing its class variables.
Public generic methods and constructors are not hidden to make production pass.
The [complete example](../examples/java-bridge/generics/README.md) builds the jar
and uses it from ordinary Java, without handwritten transport code.

The producer discovers the complete set of native allocations and concrete
factory signatures. Every argument must be an exported final nongeneric class;
primitive, unresolved, wildcard and nested-generic applications are rejected.
Class bounds can name Object, an admitted final facade or another variable of
the same declaration; other bounds are refused. Generic inheritance, nested
generic facade declarations, generic arrays and generic listeners remain
unsupported. Even fixed `Box<Quote>` parameters are refused in P7d1: an unchecked
Java cast cannot be trusted as evidence about a native input. P7d2 separately
addresses bounded construction and inputs.

Java keeps the ordinary `Box<T>` declaration, exact factory return signatures,
reflection metadata and erased descriptors. Legal raw/wildcard reads preserve
the actual returned facade. An unchecked cast from `Box<Trade>` to `Box<Quote>`
does not reinterpret native storage: an Object/wildcard read still obtains a
Trade, and assignment to Quote fails with Java's `ClassCastException`.
Reference applications share their existing native layout and implementation.
On an identity-cache miss, the actual value's native type ID selects only a
proved final alternative. Warmed reads use the existing address/cache path.

The usual ownership contracts still apply. A factory-created owning box can
have `free()`; a borrowed box shares its exact root's validity and cannot free
itself. A permanently published generic family has no `free()`. Type-variable
results with unknown ownership require complete non-reclamation proofs for all
possible concrete values. They do not acquire automatic reclamation or weaker
alias rules. Nulls, native failures and allocation failures use existing
contained conversion and rollback protocols. Source, individual-class,
class-directory and archive production preserve the same API and proof facts.

## Bounded generic inputs

A final top-level `Holder<T extends Value>` may expose public constructors and
type-dependent inputs when Value is an exported final nongeneric native facade.
Every class variable must have exactly one such class bound. Object, nonfinal,
variable-dependent or additional intersection bounds cannot admit mutable APIs.
Unrestricted mutable `Holder<T>` is rejected by the producer, including its
otherwise Java-valid constructors and setters; no member is silently hidden.

Java constructs `new Holder<Value>(value)` and calls `set(value)` normally.
Raw and wildcard views obey ordinary Java bounds and casts. The generated
public declaration preserves T; private static JNI parameters erase to Value.
Inputs such as `Holder<T>` or `Holder<Value>` are also admitted for these
final-bounded families. Every legal argument uses shared native reference
storage, with no runtime specialization, generic tag or extra per-call check.
The [runnable example](../examples/java-bridge/bounded-generics/README.md)
demonstrates construction, replacement, input identity and explicit cleanup.

Existing ownership proofs still decide admission. A retaining setter keeps an
independent input root, or the exact owner of a borrowed input, alive until
replacement or holder destruction. The generated adapter commits actual slot
changes before reporting a native exception. Failed construction rolls back
unpublished storage. Getters with unknown result ownership still require the
complete permanent-value proof; a generic getter cannot manufacture a borrowed
result or make arbitrary retained storage reclaimable. Unsupported transfers
between loaded retaining slots, cyclic retention and unknown effects remain
rejected. Generic methods, arrays, inheritance, listeners and primitive generic
projections remain outside this boundary. Java 21-25 is the support baseline.

## Runtime and distribution contracts

Use each artifact on one thread, or externally serialize all access and transfer
complete reachable state safely. The bridge adds no runtime thread-confinement
checks. Java inputs become temporary native String copies; proved result storage
is copied back and reclaimed by generated entries. A Java String is not a native
object facade. Native exception snapshots are bounded as specified in D214;
they preserve supported messages, fields, relationships and source frames.

Copied primitive arrays support all eight primitive kinds, one dimension, on
otherwise admitted static and instance methods. Source/class/archive inputs use
the same final borrowing, retention, result-origin and non-reclamation proofs.
The complete closure must exclude callbacks, reentry, array retention/publication
and input reclamation. Unknown effects reject the export. The current effect
boundary also rejects `System.arraycopy`: its native failure path is not yet a
proved contained bridge operation. Constructors with array parameters, object/multidimensional
arrays, varargs, listener array signatures, retained/shared array results and
arrays borrowed from native fields remain unsupported.

Java owns the input arrays. Null, length, element bits and same-invocation alias
identity are preserved. Repeated Java arguments share one native conversion;
equal-but-distinct arrays stay distinct. Read-only closures perform no copy-back.
Mutable inputs are copied back once per identity in first-parameter order, before
returning or throwing. All later copies are attempted after a copy failure.
The native failure stays primary, or the first copy-back failure if native code
succeeded. Indexed suppressed diagnostics describe failed copies. If diagnostics
cannot be attached, stderr explicitly reports their unavailability while the
original failure stays primary. A copy-back failure may leave partial writes;
it never becomes a successful return. Acquisition failure before execution
leaves inputs unchanged. Callers must prevent concurrent mutation for the entire
call; the bridge does not add thread-confinement checks.

An input-alias result returns the original Java array. A proved fresh result
becomes a new Java array; its native storage is reclaimed after copying, including
Java allocation/copy failure or abandonment after failed input copy-back. The
temporary input storage is also reclaimed on every exit. Array handling adds no
per-call state to scalar-only methods. JNI uses noncritical regions; copying
allocates temporary native storage and fresh results allocate Java arrays. See
the [array example and measurement runner](../examples/java-bridge/arrays/README.md),
[qualification and measurements](JAVA_BRIDGE_ARRAY_EVIDENCE.md) and
[implementation checkpoints](JAVA_BRIDGE_ARRAY_PROGRESS.md).

Bounded `ironwood.bridge.ByteView` inputs use JVM-owned reusable storage without
payload copying. Java creates views with `allocate(int)`, `slice(int, int)` and
`asReadOnly()`; both Java and native source expose `length()`, `isReadOnly()`,
`get(int)` and `put(int, byte)`. Null is admitted, but operations on null throw.
Writes are immediate, including writes preceding an exception. Overlapping
slices share their bytes while retaining distinct view identities. Read-only
writes throw `UnsupportedOperationException` before checking the index.

Views are synchronous borrowed method inputs. Native source cannot construct,
retain, return, store, free, upcast or perform runtime type operations on them;
constructor parameters, callbacks and unknown effects are rejected. Java has no
close/free or raw-buffer/address accessor. Keep storage confined during calls.
The JVM reclaims backing storage only after its views and active JNI references
cease to retain it. Reuse views rather than allocating per call.

The producer emits the companion `ironwood-bridge-values.jar`. Supply it beside
the paired artifact on the Java classpath or module path; it defines automatic
module `ironwood.bridge.values`. Its exact ABI, version and bytes are checked
before native initialization. Independent artifacts share this class, without
embedding competing public classes. Distribution emits the companion and its
source/licenses plus the ordinary dependency
`org.ironwood:ironwood-bridge-values:<IDK version>`. Non-view artifacts keep their
existing single-jar delivery. `ironwoodc --java-bridge-values -o <path>` builds the
host-only companion independently; it refuses to overwrite different bytes.

See the [accepted contract](JAVA_BRIDGE_BUFFER_DESIGN.md),
[runnable example and comparison](../examples/java-bridge/byteviews/README.md)
and [three-target qualification and Linux measurements](JAVA_BRIDGE_BUFFER_EVIDENCE.md).
Warmed views allocate no Java/native objects and copy no payload. The report
records useful read/update improvements over copied arrays and the remaining
overlapping-write gap; D246 accepts these recorded results.

Permanent-object admission proves that exposed native storage cannot be reclaimed
within the complete linked world. These facades have no generated `free()` or
liveness state. Repeated conversion reuses a still-live Java facade through a
weak identity cache; Java collection does not reclaim the native object. Inherited
identity methods run entirely in Java, while source overrides invoke native code.
Java enum constants retain declaration order and Java identity; native calls
convert by paired names after required native initialization.

Reclaimable roots expose a proved `free()` operation. Root and borrowed facades
share lifetime state; native access through either fails after owner destruction.
Calling `free()` on an already freed owner is a no-op, while a borrowed facade's
`free()` refuses. Types that can only represent borrowed views have no destruction
method. Generated constructor/result documentation identifies these capabilities.
Inherited identity methods remain usable after free, including hash collection
removal and safely published logging; source overrides require a live owner.
Java collection does not destroy native roots. The native index retains each
state until explicit destruction, including when Java facade delivery fails.
Proved fixed fields on independent roots may retain other roots or borrowed
views. A retained owner refuses `free()` until all native dependencies are cleared
or their holders are destroyed. Counts follow actual final slot contents on both
normal and exceptional returns, including store-then-throw. Facade collection does
not release a dependency. The producer rejects slot transfers, child-held slots,
cycles, unknown effects and unbounded retention; missing-free options cannot
bypass these proofs. Scalar calls with no slot mutation do no retention bookkeeping.

Synchronous listeners use generated top-level nongeneric Java interfaces. Methods
accept primitives or proved final-owner facades and return primitives/void.
Native roots with primitive/listener fields can retain registrations in proved
fixed slots. Replacement and clearing preserve the suspended invocation's listener
until it finishes; global references are released when their last registered or
active use ends. Reentrant calls are supported on the same thread. Active owners
refuse `free()`, including through callback argument aliases. Java may retain a
stable owner facade after callback return; native access fails after explicit free.
Copied String inputs remain live across allocating, nested or throwing callbacks
and are cleaned on every exit. See the [runnable listener example](../examples/java-bridge/listeners/README.md).

The producer automatically batches eligible retained-listener loops under D231.
Its current proof accepts a single counted loop starting at zero, advancing by
one to an invariant `int` input bound, with one captured-listener `void` call
carrying one to four `long` values per iteration. Intervening computation must
be pure integral arithmetic with no possible failure. Native writes, listener
reloads, extra calls, callback results, division, conditional callbacks and
unknown effects select ordinary JNI. Mandatory ownership and exception proofs
run before this optional optimization; batching grants no safety exemption.

For proved loops with at least two events, native code computes up to 1,024 events
into private reusable storage and Java invokes the listener once for each event,
in order. The first callback can therefore wait for a chunk's computation.
Throwing stops further delivery and uses the existing exception path. Nested
invocations have separate storage, preserving the suspended listener and values.
Buffers allocate lazily outside the warmed path; failed optional allocation or
unavailable direct-buffer access falls back to ordinary JNI. Successful owner
free drops cached buffer references for Java reclamation. No application wiring,
public buffer API or native GC is introduced. See the
[measurements and qualification](JAVA_BRIDGE_CALLBACK_OPTIMIZATION.md).

Unchanged Java callback exceptions preserve their original identity. Native
additions use the wrapper contract in D228; retained carriers follow the existing
native exception lifetime in D227. Built-in Throwable static slots can retain
failures; native cause and secondary-failure additions use bounded snapshot
translation. Each entry reclaims its newly created carriers only when the complete
closure proves they cannot escape. Retaining or unknown uses keep their carriers
and Java references for the process lifetime, even after a slot is overwritten.
Public callback admission remains bounded by its complete native-state proof.
It rejects arbitrary owner graphs/publication,
callbacks combined with independent-root retention mutation, reference callback
results, other reference callback arguments, asynchronous/native-created-thread
callbacks, interface inheritance/default/static methods and subclass proxies.
Missing-free options never bypass mandatory rejection.

Custom exception snapshots preserve their checked/unchecked catch hierarchy and
supported primitive/String getters. Their non-public constructors consume copied
data, and their getters require no native call or explicit cleanup. Such copied
snapshots may be inspected independently of the artifact's invocation thread.
Unsupported members and unrepresentable Java catch bases fail producer validation.
Bounded omitted edges that cannot satisfy a covariant cause return type yield the
documented LinkageError fallback before exposure, as specified in D217.

The loader validates the complete generated class/package set before binding.
Overlapping artifact packages are rejected, including split ownership without a
duplicated public class. A successful artifact permanently anchors its defining
classloader. The same paired native image cannot be loaded into a second loader.
Native payloads are extracted privately with content and permission checks;
broken or unsafe existing files are refused rather than overwritten or repaired.
The digest checks pairing consistency, not publisher trust.

Extraction is a per-user cache under `java.io.tmpdir`, in
`ironwood-java-bridge-<owner hash>/<generation>/<target>/<payload digest>/`
(D243). The first JVM that uses a build extracts it; later JVMs check owner,
mode and SHA-256 of every file again and load the same path without writing.
Each distinct file is stored once in the sibling `blobs` directory and
hard-linked into the builds that need it, so the private Linux `libstdc++.so.6`
and `libgcc_s.so.1` are not copied per build. A launch therefore adds nothing,
and a new build adds only its own image. The loader never deletes cache
entries. The whole `ironwood-java-bridge-*` directory can be removed whenever no
JVM is starting a bridge artifact; the next launch extracts again. Because
entries now outlive the JVM, a refused file stays refused until it is removed:
delete the path named by the error, or the whole directory. Loaders generated
before D243 used one `jvm-<pid>-<hash>` directory per launch; those directories
are never reused and can be deleted.

Java 21 to 25 are admitted (D245); Java 26 or later and anything below 21 are
refused before extraction or native loading, and there is no bypass flag. Java 24
and 25 treat the loader's `System.load` as a restricted method (JEP 472):

- With no option, the JVM prints its own warning once per module and then loads
  normally. The warning names `java.lang.System::load`, the artifact's `Support`
  class and its jar, and suggests `--enable-native-access=ALL-UNNAMED`. The bridge
  does not print or suppress it.
- Silence it with the grant that matches the launch form:
  `--enable-native-access=ALL-UNNAMED` for a class-path launch,
  `--enable-native-access=<Automatic-Module-Name>` (the jar manifest's value, also
  recorded as `java.module` in `bridge.properties`) for a module-path launch, or
  the `Enable-Native-Access: ALL-UNNAMED` manifest attribute of an executable jar
  started with `java -jar`. The same grant covers D241's critical-call handles.
- `--illegal-native-access=deny` makes `System.load` throw
  `IllegalCallerException` inside the facade's class initializer, before native
  bootstrap. The first use fails with `ExceptionInInitializerError` carrying that
  cause, later uses with `NoClassDefFoundError`; no partially bound world exists
  and the extracted image is never mapped. Explicit denial is a deployment-policy
  decision, not a bridge or JNI failure.
- `-Dironwood.bridge.calls=jni` and `-Xcheck:jni` behave as on Java 21-23.

[D209's Java 25 experiment](JAVA_BRIDGE_JAVA25.md) established this behavior
before D245 admitted both releases; that report records the qualification runs.

The jar contains Java classes, generated Java sources/Javadoc, its native image,
the versioned pairing/content manifest, required Ironwood/runtime/library notices
and exact corresponding standard-library/runtime source. It also retains license
metadata from actually used `.ironjar` inputs. Repeat `--license <file>` to add
application notices and source-availability statements; same-named files remain
separate by content. Missing or changed inputs fail before publication.
Application implementation source is not automatically exposed. Distributors
remain responsible for additional source and notices required by their own
dependencies. See [license mechanics](LICENSE_MECHANICS).

## Maven coordinates and IDE companions

Package a host or assembled jar without changing its bytes:

```sh
ironwoodc --java-bridge-distribution --input paired.jar \
  --group-id com.example --artifact-id engine --version 1.0.0 \
  -d target/engine-distribution
```

The output directory must be absent. It receives `engine-1.0.0.jar`,
`engine-1.0.0-sources.jar`, `engine-1.0.0-javadoc.jar`, `engine-1.0.0.pom` and a
generation/API/coordinate/hash inventory. Companion jars contain the exact
generated Java sources or Javadoc and applicable notices from the paired jar.
Covered runtime/library implementation source and native dependencies remain
inside the main jar. Packaging verifies canonical pairing metadata and complete
content hashes before publishing the directory; it neither compiles native code
nor uploads anything. The source compiler generation need not be installed for
this copying operation. Digests establish consistency, not publisher trust.

Use standard dependency coordinates and classifiers in Maven or Gradle. The
[runnable build-tool examples](../examples/java-bridge/build-tools/README.md)
invoke the producer and install only into local repositories. Producers still
need matching host toolchains; consumers need supported Java and the dependency.
Keep producing basenames stable under D215 even though repository filenames
include the version. Changing a POM version does not change native generation,
expand platform support, or qualify the artifact. The recorded D225 acceptance
applies to the measured OrderBook candidate, not unmeasured extensions.

The [implementation plan](JAVA_BRIDGE_PLAN.md) is authoritative;
the [progress log](JAVA_BRIDGE_PROGRESS.md) distinguishes completed checkpoints
from pending work and hardware evidence.
