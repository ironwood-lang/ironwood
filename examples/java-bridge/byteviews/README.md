<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Borrowed byte views

`ByteOps.iron` compares copied byte arrays with Java-owned bounded storage.
Java allocates `ironwood.bridge.ByteView`, then calls generated methods with it.
The producer supplies the JNI transport. Views and overlapping slices share
immediate writes; the JVM owns their backing memory. Reuse storage across calls.
There is no close/free operation and no exposed address. Native source cannot
retain, return, construct or free a view, or invoke callbacks while borrowing it.

With a supported JDK 21, 22 or 23 in `JAVA_HOME`, LLVM 23 on `PATH`, and the Linux support SDK
configured as in the [producer guide](../../../docs/JAVA_BRIDGE_USAGE.md):

```sh
./examples/java-bridge/byteviews/compile.sh
./examples/java-bridge/byteviews/link.sh
./examples/java-bridge/byteviews/run.sh
```

The demo checks shared writes and prints `shared bytes: 4 5 6 7`.
For matched measurements:

```sh
python3 examples/java-bridge/byteviews/benchmark.py --output examples/java-bridge/byteviews/target/run
```

`--cpu 1` pins timing children to an available Linux CPU. `--quick` checks
functionality only. Each run requires a new output directory and retains exact
commands, payload hashes, native disassembly, samples and summary. Nothing is
published. Java consumers require the generated jar and its accompanying
`ironwood-bridge-values.jar` on the classpath or module path.

The five columns are native Ironwood using byte arrays, Java byte arrays, Java
views, JNI copied arrays and JNI borrowed views. Kernels perform identical byte
operations, including sequential overlapping writes. Every scenario's checksum
must agree. The native-only baseline uses arrays because view construction is a
Java-only API. No official OrderBook source or benchmark is changed.

Cold storage allocation and two slices are reported separately on stderr.
Warmed views must allocate zero Java/native objects and copy no payload.
Copied arrays allocate native copies and JNI staging buffers. Native allocation
counters measure Ironwood allocations; they exclude the staging `malloc`.
Latency is the median batch-average nanoseconds per call, including the driver
loop and input update, not a latency percentile. Throughput is the corresponding
calls per second. Small calls still pay JNI metadata-acquisition costs; numerical
performance acceptance belongs to the maintainer. See the
[three-target qualification and Linux measurements](../../../docs/JAVA_BRIDGE_BUFFER_EVIDENCE.md)
for the tested artifacts, useful zero-copy gains and remaining overlap gap.
