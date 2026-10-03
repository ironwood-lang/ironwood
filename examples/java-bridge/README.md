<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge examples

Start with [basics](basics/README.md): a small native Ironwood `Counter` and
`CounterListener` interface, implemented by a Java application. Two Java calls
into the counter produce two callbacks into Java. It includes compile, link,
run and test scripts, plus explicit native cleanup. The
[step-by-step guide](../../docs/JAVA_BRIDGE_EXAMPLE.md) shows the exact commands
those scripts run.

With the prerequisites below on `PATH`, run from this directory:

```sh
./basics/compile.sh
./basics/link.sh
./basics/run.sh
./basics/test.sh
```

## Values and exceptions

The `value` example builds an ordinary Java dependency containing a paired
host-target native image. A Java consumer calls primitive/String methods, catches
a declared IOException, and continues calling the same artifact. Generated
transport handles proved temporary String reclamation. The consumer runs on one
thread, as required by the bridge's confinement contract.

Use a JDK 21 to 25, LLVM 23 and the macOS SDK or the prepared Linux bridge support
SDK described in the producer guide. Put `ironwoodc`,
`javac` and `java` on PATH, or set `JAVA_HOME` to select the same JDK for both build steps.
Run from this directory:

```sh
./value/compile.sh
./value/link.sh
./value/run.sh
```

Compilation writes ordinary `.ironclass` files. Linking feeds them through the
public bridge producer, writes `value/target/ironwood-values.jar`, includes the
example's license texts, and compiles the Java consumer against that jar. Running
uses Java and those dependency/consumer classes only, without native tools or
manual loading. Every run script passes `--enable-native-access=ALL-UNNAMED`,
which keeps Java 24 and 25 from printing their native-access warning and which
Java 21-23 ignore. Its program output is:

```text
42
copied: bridge
caught: example failure
continued: 42
```

Every script prints its command; successful execution exits zero. The same built
consumer can run with supported Java 22 to 25. The payload's recorded minimum
macOS version comes from its actual linked image; Linux uses the pinned glibc 2.17
baseline. Pass the same native-access grant in your own launch commands on Java
24 and 25; see the
[producer guide](../../docs/JAVA_BRIDGE_USAGE.md#runtime-and-distribution-contracts).
Java 26+ and targets absent from the jar are refused. The producer also
supports proved object facades and explicit root `free()`; this value example
does not qualify them or final release readiness.

Bridge examples have their own nested compile/link/run workflows because they
produce Java dependencies and have a separate JVM/platform matrix. The ordinary
native `examples/test-all.sh` catalog does not include them. See the
[producer usage guide](../../docs/JAVA_BRIDGE_USAGE.md) and the
[validation runners](../../scripts/java-bridge/README.md).

The [Maven and Gradle workflows](build-tools/README.md) build the same value
example, install standard coordinates and IDE companions locally, and run the
consumer using normal dependency resolution.

The [retained listener example](listeners/README.md) exercises synchronous Java
callbacks from native Ironwood, reentrant listener replacement, exception identity
and explicit cleanup. Its separate benchmark compares native/native, Java/Java
and native/Java execution with identical event counts and checked results.

The [primitive array example](arrays/README.md) covers copied inputs, mutation and
fresh results. Its runner compares native Ironwood, Java and the generated bridge
with matching checksums, allocation checks and preserved disassembly.

The [bounded byte-view example](byteviews/README.md) passes reusable Java-owned
storage directly to native code and compares copied arrays with borrowed views.
Its generated artifacts use the shared `ironwood-bridge-values.jar` dependency.

The [read-only generic facade example](generics/README.md) preserves `Box<T>`
with distinct native factories and ordinary Java wildcard reads. Its runner
compares warmed generic and nongeneric reference getters and checks allocations.

The [bounded generic example](bounded-generics/README.md) constructs and mutates
`Holder<T extends Value>` through ordinary Java calls. Its matched setter runner
checks retention costs against a nongeneric facade, including allocation counts.

For a larger application, [OrderBook through the Java Bridge](../../projects/OrderBook/java-bridge/README.md)
has compile/link/run scripts and the existing throughput and batch-latency drivers
calling the generated native API.
