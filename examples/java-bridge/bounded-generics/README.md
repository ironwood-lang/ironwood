<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Bounded generic construction and mutation

`Holder<T extends Value>` has a public constructor and setter. Value is an
exported final native class, so every legal Java type argument has the same
native representation. Java calls use the source API without handwritten JNI
or loading code. A holder retains its input root until replacement or `free()`;
destroy holders before their retained values. `echo` preserves input identity.

With the pinned Java 21 JDK and LLVM 23 on PATH, and the prepared support SDK
on Linux, run from the repository root:

```sh
./examples/java-bridge/bounded-generics/compile.sh
./examples/java-bridge/bounded-generics/link.sh
./examples/java-bridge/bounded-generics/run.sh
```

Expected output: `bounded values: 17 29`, exit zero. Java 21-23 use the same jar;
Java 24+ remains refused. Unrestricted mutable `Holder<T>`, generic methods,
generic arrays, listeners and inheritance remain rejected. Every variable must
have a single admitted final facade bound. See the
[usage contract](../../../docs/JAVA_BRIDGE_USAGE.md#bounded-generic-inputs).

For a matched retaining-setter comparison:

```sh
python3 examples/java-bridge/bounded-generics/benchmark.py --output examples/java-bridge/bounded-generics/target/measure
```

Use `--cpu 1` on Linux to pin timing children. Use `--quick` only for smoke.
The directory must be new. Generic and nongeneric setters alternate the same
two native roots, exercising existing retention commits on every call. Three
independent JVMs per path each run five warmup batches and seven measured batches
of two million calls. Full measurements require zero Java and native allocations.
The runner records batch-average latency, throughput, final-value checks,
payload hashes, commands, bytecode and native disassembly. This compares two
bridge paths; it does not measure native-only versus pure Java applications.
