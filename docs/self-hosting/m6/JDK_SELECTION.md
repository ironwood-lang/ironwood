<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M6.2: the native producer's JDK selection (decision needed)

Status: open for the maintainer's decision. M6.2 records the options and their
evidence and does not choose. This blocks M6.2's "selected JDK-tool process
path" and therefore M6.3 and the M6 checkpoint; every other M6.2 item is
delivered ([the M6.2 record](JAR.md)).

## What the Java producer does today

D239 selects the producer JDK for the Java bootstrap: `JAVA_HOME` when set
(an invalid selection fails without fallback), otherwise the IDK's bundled
JDK at `toolchain/lib/jvm`, otherwise the runtime of `java` on `PATH`
(`scripts/jdk.sh` reads its `java.home` and `java.specification.version`
from `java -XshowSettings:properties -version`). The running JVM is then
the producer JDK:

| Need | Java producer (BridgeBuildTools, BridgeProducer) |
| --- | --- |
| Supported range | `Runtime.version().feature()` in 21 to 25, else `requires JDK 21, 22, 23, 24 or 25; detected <version>` |
| Compiler and documentation tool | `ToolProvider.getSystemJavaCompiler()` and `getSystemDocumentationTool()`, in process, else `requires the selected JDK's javac component` or `Javadoc component` with `java.home` |
| JNI headers | `<java.home>/include/jni.h` and `include/darwin/jni_md.h` or `include/linux/jni_md.h`, regular and readable |
| Native build identity inputs | `jdk.version` = `java.runtime.version`, `jdk.vendor` = `java.vendor`, `jdk.jni.sha256`, `jdk.jni_md.sha256` |

A native producer has no running JVM, so each row needs a native answer.

## Decision-independent evidence (M6.2)

`M6.2 JDK tools run by absolute path match the in-process javac and javadoc`:
with the same JDK 21.0.1 home, `javac` and `javadoc` run by absolute path
through the port's `Command` and `ProcessRunner.runToFile`, with
BridgeBuildTools' exact flags (`--release 21 -encoding UTF-8 -proc:none
-Xlint:all -Werror --class-path "" --source-path "" -d ...` and the javadoc
flags), write byte-identical class files and documentation pages, and a
`-Werror` failure exits 1 with the in-process tool's diagnostics byte for
byte. Every option below ends in such an absolute JDK home, so this path holds
for all of them.

On every JDK 21 to 25 on this Mac (Oracle 21.0.1, 23.0.1, 25.0.1, 25.0.2 and
25.0.4.1; GraalVM 21, 23 and 25; Eclipse Temurin 21.0.12, 22, 23, 24 and 25;
IBM Semeru 23; Azul Zulu 23), the `release` file's `JAVA_RUNTIME_VERSION` and
`IMPLEMENTOR` equal the `java.runtime.version` and `java.vendor` that
`java -XshowSettings:properties -version` reports. The Linux x86-64 and arm64
Temurin 22 and 23 bundles' `release` files carry both keys too (not run here).
JDKs 17 to 20 here lack `JAVA_RUNTIME_VERSION`; they are outside the supported
range.

## Options

### Where the JDK comes from

| Option | Rule | Consequences |
| --- | --- | --- |
| A. `JAVA_HOME` only | The producer requires `JAVA_HOME`; unset or invalid fails | Simplest and explicit; IDK and build-tool launchers must set it; differs from D239's defaults |
| B. D239's order natively | `JAVA_HOME` (invalid fails, no fallback), else `<installation>/toolchain/lib/jvm` through D269's Installation, else `PATH` | Same user-facing behavior as the Java producer and `scripts/jdk.sh`; the `PATH` step needs a rule: resolve `java` and read its `java.home` (option R2 below), or resolve `javac` with M4.3's ExecutableSearch and `toRealPath`, then take its `bin` directory's parent |
| C. An explicit producer option with B as default | for example `--java-bridge-jdk <home>`, then B | Makes the JDK part of the producer command line; a new command-line option is a public surface change |

### How it is validated and recorded

| Option | Rule | Consequences |
| --- | --- | --- |
| R1. The `release` file | Read `<home>/release` with BridgeProperties-style parsing: `JAVA_VERSION`'s feature for the 21 to 25 gate, `JAVA_RUNTIME_VERSION` for `jdk.version`, `IMPLEMENTOR` for `jdk.vendor`; check `bin/javac`, `bin/javadoc` and the JNI headers | No extra process; equal to the Java producer's values on every JDK above; relies on distributor metadata whose keys are conventional rather than specified, and a quoted-value format the readers must unquote |
| R2. Ask the JDK | Run `<home>/bin/java -XshowSettings:properties -version` through `runToFile` and read `java.specification.version`, `java.runtime.version`, `java.vendor` and `java.home`; check the tools and headers | The exact values the Java producer records and the convention `scripts/jdk.sh` already uses; one extra process per producer run; the listing is human-readable output, so its parsing is a contract to freeze |
| R3. Change the identity inputs | Record something the native producer has directly, such as the `release` file's digest or `javac -version` | Changes the native build identity format, so it belongs to S7's producer-identity design rather than to M6.2 |

`javac -version` alone cannot supply `java.vendor` or the full runtime version
that the identity inputs hold today.

## What the decision unblocks

With an option for each table, M6.2 adds the selection adapter to the port
(beside D273's adapters), qualifies it with focused fixtures (unset, invalid
and out-of-range homes, missing tools and headers, each vendor's recorded
values), checks the M6.2 box, and M6 proceeds to M6.3's reconciliation and
the checkpoint. Nothing in this record changes the Java producer.
