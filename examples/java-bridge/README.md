<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge value preview

The `value` example builds an ordinary Java dependency containing a paired
host-target native image. A Java consumer calls primitive/String methods, catches
a declared IOException, and continues calling the same artifact. Generated
transport handles proved temporary String reclamation. The consumer runs on one
thread, as required by the bridge's confinement contract.

Use a Java 21 JDK, LLVM 23 and the macOS SDK or the prepared Linux bridge support
SDK described in the producer guide. Put `ironwoodc`,
`javac` and `java` on PATH, selecting the same Java 21 JDK for both build steps.
Run from this directory:

```sh
./value/compile.sh
./value/link.sh
./value/run.sh
```

Compilation writes ordinary `.ironclass` files. Linking feeds them through the
public bridge producer, writes `value/target/ironwood-values.jar`, includes the
example's license texts, and compiles the Java consumer against that jar. Running
uses Java and those dependency/consumer classes only, without native tools,
manual loading or native-access flags. Its program output is:

```text
42
copied: bridge
caught: example failure
continued: 42
```

Every script prints its command; successful execution exits zero. The same built
consumer can run with supported Java 22 or 23. The payload's recorded minimum
macOS version comes from its actual linked image; Linux uses the pinned glibc 2.17
baseline. Java 24+ and targets absent from the jar are refused. The producer also
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
