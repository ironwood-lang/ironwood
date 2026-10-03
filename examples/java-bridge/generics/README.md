<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Read-only generic facades

`Box.iron` has two native factories, returning `Box<Quote>` and `Box<Trade>`.
The generated Java declaration preserves `Box<T>` and `T get()`. Its constructor
is inaccessible and it has no type-dependent inputs. Java uses ordinary calls;
the producer generates the loading and JNI code. Shared Quote/Trade values have
proved process lifetime. Each factory-created box is an owning root, explicitly
freed by the consumer. Reading through a wildcard preserves the actual value.

With a supported JDK 21 to 25 selected in `JAVA_HOME` and LLVM 23 on PATH, and the prepared Linux support
SDK when building on Linux, run from the repository root:

```sh
./examples/java-bridge/generics/compile.sh
./examples/java-bridge/generics/link.sh
./examples/java-bridge/generics/run.sh
```

Expected output: `generic values: 17 29`. Successful execution exits zero.
Java 22 to 25 can consume the same artifact; `run.sh` passes the native-access
grant that keeps Java 24 and 25 warning-free. Java 26+ is refused.
Public generic construction/mutation, generic facade parameters, standalone
generic methods, generic arrays and generic inheritance remain outside P7d1.
See the [bridge usage guide](../../../docs/JAVA_BRIDGE_USAGE.md).

For the performance regression control:

```sh
python3 examples/java-bridge/generics/benchmark.py --output examples/java-bridge/generics/target/measure
```

Use `--cpu 1` to pin Linux measurement children and `--quick` for functional
smoke only. Each output directory must be new. The runner compares the cached
`Box<Quote>.get()` with the equivalent nongeneric `Plain.get()`, including JNI,
root checks, facade identity lookup and a Java identity check. Both classes have
static factory storage, so both native getters include the same initialization
guard. It is a comparison
of two bridge paths, not a native-only versus Java application benchmark.

Three independent JVMs per path alternate execution order. Each runs five
warmup batches and seven measured batches of two million calls. Reports retain
batch-average latency, throughput, checksums, Java/native allocation counts,
first-call cost, commands, payload hashes, Java bytecode and native disassembly.
First-call cost includes class/facade initialization and is reported separately.
The full run requires zero Java and native allocations in measured batches.
No official OrderBook code or benchmark is changed.
