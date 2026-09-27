<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge producer preview

The experimental producer builds macOS ARM64 Java dependencies exposing primitive
and copied-String APIs, proved roots and borrowed views with bounded retention,
permanent concrete objects, enums and custom exception snapshots. Consumers call
generated Java classes and catch mapped Java exceptions using ordinary dependency
loading. General object inheritance, arrays, callbacks, optional TLS dependencies
and Linux publication remain rejected at their pending implementation boundaries.
This preview is not a release qualification.

## Build and run

The producer requires the Java 21 JDK compiler/Javadoc tools and JNI headers,
LLVM 23 and the macOS SDK. Build the checkout compiler using the repository's
normal instructions, with that JDK selected. A generated consumer requires only
Java 21, 22 or 23 and the jar on a compatible macOS ARM64 host. The native image
declares its real minimum macOS version; the build does not promise an older
deployment floor than the toolchain actually produces.

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

Multi-target assembly, producer Maven/Gradle conventions and final qualification
are scheduled in P6. The [implementation plan](JAVA_BRIDGE_PLAN.md) is authoritative;
the [progress log](JAVA_BRIDGE_PROGRESS.md) distinguishes completed checkpoints
from pending work and hardware evidence.
