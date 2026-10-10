<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# OrderBook through the Java Bridge

The ordinary Java demonstration and benchmark drivers call the native Ironwood
OrderBook through its generated Java API. No handwritten JNI or loader is used.
The engine sources and official benchmark definitions are unchanged.

## Build and run

From the repository root, select a **JDK 21 to 25** on `PATH` and in `JAVA_HOME`,
with `ironwoodc` on `PATH` and the pinned LLVM 23 toolchain available. On Linux,
set `IRONWOOD_BRIDGE_SUPPORT_HOME` to the prepared target SDK. See the
[producer prerequisites](../../../docs/JAVA_BRIDGE_USAGE.md#build-and-run).

```sh
export PATH="$PWD/bin:$PATH"
./projects/OrderBook/java-bridge/compile.sh
./projects/OrderBook/java-bridge/link.sh
./projects/OrderBook/java-bridge/run.sh
./projects/OrderBook/java-bridge/throughput.sh 10 100
./projects/OrderBook/java-bridge/latency.sh 10000 50000 1000
```

The scripts locate their own directory, so they also work from elsewhere.
Build stages print their commands. `run.sh` prints the same demonstration
snapshots as the other versions, ending with `true`, `true`, `4`, `170`, `2`.
A built consumer needs only Java 21 to 25 on the matching target; Java 26 or
later is refused. The scripts pass `--enable-native-access=ALL-UNNAMED`, which
Java 24 and 25 need to stay warning-free. No manual library loading is needed.

- `compile.sh` compiles only the existing Ironwood engine closure into
  `target/iron-classes`.
- `link.sh` builds `target/orderbook.jar` with `-O3 --critical-calls=on`, then
  compiles the existing Java `Main`, `Bench`, `LatencyBench` and `LatencyReport`
  into `target/consumer-classes`. An empty Java source path prevents implicit
  compilation of the Java engine. `OrderBook`, `Order` and their enums come
  exclusively from the generated JAR.
- `run.sh`, `throughput.sh` and `latency.sh` start those JVM drivers with that
  JAR and `--enable-native-access=ALL-UNNAMED`. All generated build files stay
  under this folder's ignored `target/` directory; program results go to
  standard output.

Every order operation is short and memory-only, so the build selects
[critical calls](../../../docs/JAVA_BRIDGE_USAGE.md#critical-calls): the producer
proves 25 of the 35 native bindings eligible and Java reaches them without a
JVM thread-state transition. Their registered JNI methods remain the fallback.
Without the native-access option the same JAR still uses critical calls and the
JVM prints its restricted-method warning once. Add `-Dironwood.bridge.calls=jni`
to a `java` command to measure the JNI transport with the same JAR.

The bridge producer uses its supported target CPU baseline; it does not accept
`-march=native`. Other OrderBook native scripts currently use host CPU tuning.
Record this difference when comparing measurements. Build the bridge on each
target being tested; this workflow produces one host-target JAR.

## Throughput and latency

Throughput arguments are **warmup operations and measured operations in millions**,
with defaults `10 100`. Output is one integer, the measured elapsed nanoseconds,
matching the other versions. Million operations/second is
`measuredMillions * 1e9 / elapsedNanoseconds`.

`official-throughput.sh [runs] [warmup-millions] [measured-millions]` defaults
to `31 8 80`, matching the other variants. It launches a fresh JVM for each
sample, prints per-run times to stderr and the median elapsed nanoseconds to
stdout. The run count must be a positive odd number. After building, use
`./projects/OrderBook/java-bridge/official-throughput.sh 31 8 80` from the
repository root.

For comparisons, record the JVM, CPU affinity and process security state as well
as compiler flags. The [x86host host investigation](../../../docs/JAVA_BRIDGE_HOST_PERFORMANCE.md)
shows that Docker's speculative-store-bypass mitigation changes the relative
OrderBook performance. Its earlier container speedup does not hold on the
ordinary host; compare all three variants in the same deployment environment.
The [critical-call measurements](../../../docs/JAVA_BRIDGE_CRITICAL_CALLS.md)
record the current ordinary-host comparison and what the remaining gap to the
standalone executable consists of.

Latency arguments are **warmup batches, measured batches, cycles per batch**,
with defaults `10000 50000 1000`. Each cycle has eight order operations. The
report includes the clock check, measured counts and batch-latency distribution.
A batch percentile is not a per-operation percentile. These Java loops call the
native engine through its ordinary API for each operation; they do not substitute
the separate coarse native-batch experiment from the qualification tools.

For a quick functional check after building:

```sh
./projects/OrderBook/java-bridge/test.sh
```

This checks the exact demonstration, checked JNI, positive throughput/latency
runs, invalid counts/overflow, extra arguments, and absence of Java engine
classes in the consumer output. Small smoke counts are not performance evidence.
For comparisons, use the same counts and JDK, run one process at a time, repeat
and alternate implementations. See the [shared workload](../README.md#shared-workload)
and [historical bridge measurements](../../../docs/JAVA_BRIDGE_X86_EVIDENCE.md).

The native book and pooled orders have process lifetime, as in the existing
Ironwood benchmark. They expose no generated `free()`; each script starts a
fresh JVM. Native pool reuse does not imply every Java facade lookup allocates
nothing: facade cache misses/recreation have their own allocation behavior.
