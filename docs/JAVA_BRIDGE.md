<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native Ironwood for Java

> **Implementation status, 2026-09-29:** The Java Bridge producer is implemented
> for Java 21-25 on macOS ARM64, Linux ARM64 and Linux x86-64. P5's bounded
> synchronous callbacks, including retained listeners, are implemented and
> qualified on all three targets. Callback numerical acceptance remains
> maintainer review; see the [measurements and evidence](JAVA_BRIDGE_P5_EVIDENCE.md).
> Compiler-proved automatic callback batching is implemented under D231.
> P7b copied primitive arrays are implemented and qualified on all three targets;
> see the [array evidence and measurements](JAVA_BRIDGE_ARRAY_EVIDENCE.md). P7c1 bounded
> byte views are implemented and qualified on all three targets; see the
> [buffer evidence and measurements](JAVA_BRIDGE_BUFFER_EVIDENCE.md).
> P7d1 read-only, factory-produced generic facades are implemented and qualified
> on all three targets, using the P7d0 foundations; see the
> [generic evidence](JAVA_BRIDGE_GENERIC_PROGRESS.md).
> P7d2 final-bounded construction/mutation is implemented and qualified on all
> three targets under D237; evidence is in the generic log. P7e0's isolated
> [JNI/FFM experiment](JAVA_BRIDGE_FFM_EXPERIMENT.md) is complete on all three
> targets. D238 accepts JNI only and closes P7e through its keep-JNI outcome;
> P7e1/P7e2 remain deliberately unimplemented. [P7f qualification](JAVA_BRIDGE_P7_QUALIFICATION.md)
> is complete on all three targets. New extension numerical acceptance remains
> maintainer review. D241 adds producer-selected
> [critical calls](JAVA_BRIDGE_USAGE.md#critical-calls) for proved object entries
> and D242 tunes portable x86-64 images; see the
> [2026-10-02 measurements](JAVA_BRIDGE_CRITICAL_CALLS.md).
> D245 admits Java 24 and 25 under the JDK's native-access policy, qualified on
> macOS ARM64 and Linux x86-64; Java 26 or later is refused. This is a producer
> preview, not an announcement of a published release. The
> [producer guide](JAVA_BRIDGE_USAGE.md) specifies the supported API and platform
> boundaries; the [implementation plan](JAVA_BRIDGE_PLAN.md) is authoritative
> over historical proposals. For checkpoints, see
> [implementation phases](JAVA_BRIDGE_PLAN.md#12-implementation-phases-and-exit-criteria).

Write performance-sensitive code in Ironwood, compile it to native code, and
call it from a regular Java application as if it were an ordinary Java
dependency. No handwritten bridge code, native declarations, or manual library
loading are required for supported APIs.

## Try the included programs

[JAVA_BRIDGE_EXAMPLE.md](JAVA_BRIDGE_EXAMPLE.md) is the shortest route: it walks
through the basics example command by command.

Select a JDK 21 to 25 for building, put the checkout's `bin` on `PATH`, and prepare
the [native prerequisites](JAVA_BRIDGE_USAGE.md#build-and-run). From the
repository root, start with the [basics example](../examples/java-bridge/basics/README.md):

```sh
export PATH="$PWD/bin:$PATH"
./examples/java-bridge/basics/compile.sh
./examples/java-bridge/basics/link.sh
./examples/java-bridge/basics/run.sh
./examples/java-bridge/basics/test.sh
```

Java implements an Ironwood listener interface, calls a native counter twice,
and receives two callbacks. The separate [value example](../examples/java-bridge/README.md#values-and-exceptions)
calls native primitive/String methods, catches an exception and continues.
For the real OrderBook API and its existing Java benchmark drivers:

```sh
./projects/OrderBook/java-bridge/compile.sh
./projects/OrderBook/java-bridge/link.sh
./projects/OrderBook/java-bridge/run.sh
./projects/OrderBook/java-bridge/throughput.sh 10 100
./projects/OrderBook/java-bridge/latency.sh 10000 50000 1000
```

See the [OrderBook bridge guide](../projects/OrderBook/java-bridge/README.md)
for prerequisites, arguments, output, smoke tests and comparison boundaries.

## Quick start

### 1. Set up the project

Use a JDK 21 to 25, pinned LLVM 23 and native platform prerequisites from the
[producer guide](JAVA_BRIDGE_USAGE.md#build-and-run), with `ironwoodc` on `PATH`.
Linux producers also need the pinned native support SDK described there.
Consumers use Java 21 to 25.

Keep Ironwood and Java source in their familiar source roots:

```text
pricing/
├── src/main/ironwood/com/acme/pricing/PriceEngine.iron
└── src/main/java/com/acme/app/Main.java
```

### 2. Write an ordinary Ironwood API

```java
package com.acme.pricing;

public final class PriceEngine {

    public long notional(long quantity, long price) {

        return quantity * price;
    }
}
```

There is no bridge-specific syntax. Exported packages define the Java-facing API.
The producer checks their complete public surface and rejects unsupported types,
members or unproved ownership instead of silently omitting them.

### 3. Build the bridge

```sh
ironwoodc --source-path src/main/ironwood -d target/classes \
    src/main/ironwood/com/acme/pricing/*.iron

ironwoodc --java-bridge \
    --export com.acme.pricing \
    -cp target/classes \
    -o target/pricing-bridge.jar \
    -O3
```

Repeat `--export` for every package that should be available to Java:

```sh
ironwoodc --java-bridge \
    --export com.acme.pricing \
    --export com.acme.risk \
    --export com.acme.orders \
    -cp target/classes \
    -o target/trading-bridge.jar \
    -O3
```

The public API from every exported package appears in the same Java jar.
Each export names an exact package, not its subpackages. Application types in
public signatures must also belong to explicitly exported packages; the producer
does not silently expand the exports.

The completed build looks like this:

```text
target/
├── classes/com/acme/pricing/PriceEngine.ironclass
└── pricing-bridge.jar
```

`pricing-bridge.jar` is a regular Java jar containing the generated Java API and
the native library built for the current platform, generated loading support,
metadata, sources, Javadoc and applicable licenses. A single host build contains
one target. Follow the [assembly instructions](JAVA_BRIDGE_USAGE.md#assemble-host-builds)
to combine matching builds for multiple platforms.

The Java application uses this jar directly. An `.ironjar` is not required.

### 4. Use it like Java

```java
package com.acme.app;

import com.acme.pricing.PriceEngine;

public class Main {

    public static void main(String[] args) {

        PriceEngine engine = new PriceEngine();
        try {
            long value = engine.notional(250L, 1995L);
            System.out.println(value);
        } finally {
            engine.free();
        }
    }
}
```

Reclaimable native roots expose a compiler-proved `free()` operation. It runs the
Ironwood destructor, if present, and reclaims the native memory. Java garbage
collection does not reclaim native objects. Generated classes do not implement
`AutoCloseable` for this purpose; use explicit `free()` with `finally` as above.
Permanent native objects have no generated `free()`. See the producer guide for
borrowed views, lifetime checks and caller threading obligations.

Compile and run the Java application against the generated jar:

```sh
mkdir -p target/app-classes

javac --release 21 -cp target/pricing-bridge.jar -d target/app-classes \
    src/main/java/com/acme/app/Main.java

java --enable-native-access=ALL-UNNAMED -cp target/pricing-bridge.jar:target/app-classes com.acme.app.Main
```

The program prints `498750`. The `--enable-native-access=ALL-UNNAMED` option keeps
Java 24 and 25 from printing their native-access warning; Java 21-23 ignore it.
Maven and Gradle projects use the same jar as a normal dependency.

The library loads automatically when first used. Application code does not
need `System.loadLibrary`, JNI wrappers, C headers, or platform-specific call
sites.

## Callbacks and application integration

Supported Ironwood listener interfaces become ordinary Java interfaces. Java
applications supply a lambda or implementation and register it through the
exported API. Generated adapters handle native-to-Java invocation and supported
exception transport. The separate [listener example](../examples/java-bridge/listeners/README.md)
demonstrates retained registration, reentry, failure identity and explicit cleanup
without changing the official OrderBook benchmark.

Bridge wiring is generated, but application setup and ownership remain explicit:
build and export the native API, add the generated jar as a dependency, register
listeners where needed and free reclaimable native roots. This is not a drop-in
replacement for arbitrary Java APIs. Unsupported callback shapes and other
pending capabilities are rejected by the producer.

Native compilation does not guarantee that every workload becomes faster. The
[current callback measurements](JAVA_BRIDGE_CALLBACK_OPTIMIZATION.md#current-linux-x86-64-production-measurements)
compare pure Ironwood, pure Java and the bridge using the same per-event work.
Automatic batching reduces the measured Linux x86-64 bridge time from about
103 ns/event to 2.52 ns/event, versus about 1.25 for pure Ironwood and Java.
The compiler batches only loops with proved equivalent behavior, preserving every
Java listener call. Other admitted loops keep ordinary JNI dispatch. These are
amortized event times, not callback arrival percentiles, and are separate from the
[OrderBook measurements](JAVA_BRIDGE_X86_EVIDENCE.md). The subsequent
[host investigation](JAVA_BRIDGE_HOST_PERFORMANCE.md) finds the JNI bridge slower
than Java in the maintainer's ordinary Linux environment. The earlier OrderBook
advantage depends on the validation container's security and CPU configuration.

Short native operations are dominated by the JNI transition itself. A producer
whose exported operations are all short can build with `--critical-calls=on`:
proved entries are then called without that transition, and JNI remains the
fallback. On the same ordinary host the OrderBook bridge then takes about 26%
less time than Java and about 48% more than standalone Ironwood. Read the
[contract](JAVA_BRIDGE_USAGE.md#critical-calls) before selecting it, and the
[measurements](JAVA_BRIDGE_CRITICAL_CALLS.md) for what the remaining gap is.

Use the [producer guide](JAVA_BRIDGE_USAGE.md) for current contracts and the
[implementation plan](JAVA_BRIDGE_PLAN.md) for phase status. The
[original design proposal](IRONWOOD_JAVA_BRIDGE.md) is historical context.
