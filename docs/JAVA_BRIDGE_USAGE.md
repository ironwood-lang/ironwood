<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge producer preview

The experimental producer builds host-target Java dependencies for macOS ARM64,
Linux ARM64 and Linux x86-64, exposing primitive,
copied-String and proved copied primitive-array APIs, roots and borrowed views with bounded retention,
permanent concrete objects, enums, custom exception snapshots and bounded
synchronous Java listeners. Consumers call
generated Java classes and catch mapped Java exceptions using ordinary dependency
loading. General object inheritance, object/multidimensional arrays, callback shapes outside the proved
subsets and optional TLS dependencies
remain rejected at their pending implementation boundaries.
This preview is not a release qualification.

## Build and run

The producer requires the Java 21 JDK compiler/Javadoc tools and JNI headers,
LLVM 23 and the matching macOS SDK or pinned Linux glibc 2.17 sysroot/private
runtime SDK. Build the checkout compiler using the repository's normal
instructions, with that JDK selected. A generated consumer requires only Java
21, 22 or 23 and the jar on a compatible host. The macOS image declares its real
minimum OS version; Linux images declare their glibc 2.17 baseline and include
their pinned compiler runtimes. Windows, musl and 32-bit hosts are unsupported.
One host build contains one target; combine matched host jars using the assembly
step below. The [distribution candidate and three-target evidence](JAVA_BRIDGE_P6_EVIDENCE.md)
are recorded, including physical Linux x86-64 execution under D213. Final
OrderBook numerical acceptance is recorded in D225. New P5 callback measurements
have their own [three-target qualification and measurement report](JAVA_BRIDGE_P5_EVIDENCE.md).
Rosetta observations remain separate functional/static evidence.

Linux producers first prepare the [pinned native support SDK](JAVA_BRIDGE_NATIVE_SUPPORT.md)
and set `IRONWOOD_BRIDGE_SUPPORT_HOME` to that target's prepared directory. Host
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
native toolchain and Java 21 producer on each matching build host. Copy the
completed jars back without modifying their contents. Assembly needs the matching
Ironwood compiler/runtime distribution and Java 21 JDK, but no native compilation:

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

Java 24+ is refused before extraction or native loading. There is no bypass flag.
[D209's separate Java 25 experiment](JAVA_BRIDGE_JAVA25.md) found working default-
policy calls with visible warnings; support remains Java 21-23 for this run.

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
