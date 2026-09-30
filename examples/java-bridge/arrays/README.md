<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Copied primitive arrays

`ArrayOps.iron` demonstrates read-only inputs, mutable inputs and fresh array
results. The producer supplies all JNI code. Inputs remain Java-owned; repeated
aliases share one native copy. Fresh results become ordinary Java arrays and
their temporary native storage is reclaimed. No callback or concurrent mutation
may observe a copied input during the call.

With a supported JDK 21, 22 or 23 in `JAVA_HOME`, LLVM 23 on `PATH`, and the Linux native
support SDK configured as in the [producer guide](../../../docs/JAVA_BRIDGE_USAGE.md):

```sh
python3 examples/java-bridge/arrays/benchmark.py --output examples/java-bridge/arrays/target/run
```

The runner compiles, links and runs native Ironwood, Java bytecode and the generated
Java Bridge against identical deterministic work. `--cpu 1` pins Linux child
processes to an available CPU. `--quick` is a functional smoke only. Each output
directory must be new; the runner preserves existing evidence.

The output includes exact commands, paired payload hashes, package metadata,
optimized native disassembly, raw CSV timings and a JSON summary. Checksums must
match and all temporary native storage must return to baseline. Warmed input
calls must allocate zero Java objects; fresh results necessarily allocate Java
arrays. Native allocation counters exclude the JNI staging `malloc`, which is
one additional temporary allocation per distinct nonempty input.

Reported latency is the median **batch-average nanoseconds per call**, including
the driver loop and input update. It is not a per-call percentile. Throughput is
the corresponding calls per second. Numerical acceptance remains a maintainer
decision; these conversion workloads are separate from official OrderBook results.
The [P7b evidence report](../../../docs/JAVA_BRIDGE_ARRAY_EVIDENCE.md) records
the qualified targets, measured results and exact payload identities.
