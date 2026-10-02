<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Retained Java listeners

`ResultProcessor` is native Ironwood code. Its generated `ResultListener` is an
ordinary Java interface, implemented by the application. Calls are synchronous
on the invoking thread. A registration stays alive until replacement, clearing
or processor `free()`. A suspended `process()` keeps its original registration
even if the listener replaces it during a reentrant call.

Use the same JDK 21 to 25 and LLVM 23 setup as the [value example](../README.md).
From this directory:

```sh
./compile.sh
./link.sh
./run.sh
```

Expected output:

```text
listeners-ok: retained registration, reentry, exception identity, cleanup
```

The consumer tests reentry and replacement, refusal to free a suspended owner,
the identity of a Java exception passing through native code, continued use after
failure and explicit cleanup. No manual JNI or library loading is needed. The
paired JAR can also run on supported Java 22 and 23 on its recorded native target.

The separate benchmark compares the same stateful arithmetic and exactly one
listener event per result in three scenarios: native processor/native listener,
Java processor/Java listener, and native processor/Java listener. The native-only
driver uses one static process-lifetime listener and reclaims its processor.
Setup and registration are outside timing. It does not modify OrderBook.

From the repository root, on Linux x86-64 with the existing pinned JDK directories:

```sh
python3 scripts/java-bridge/measure-listeners.py \
  --target linux-x86_64 --execution-scope 'x86-64 physical hardware' \
  --llvm-home /opt/ironwood-toolchain \
  --java21-prefix /opt/ironwood-bridge-jdk \
  --host-notes 'CPU affinity and host configuration recorded by the operator' \
  --evidence workspace/java-bridge/evidence/p5/listeners-NEW
```

Set `IRONWOOD_BRIDGE_SUPPORT_HOME` to the already prepared Linux support directory
and `--jdk-root` if Java 22/23 pins are outside the default workspace location.
For ARM64 use the matching target and execution scope. Nothing is installed.

The runner preserves sources' identities, paired artifacts, commands, raw samples,
allocation counters and disassembly. It independently checks every checksum and
event count. Defaults are three process forks, five warmups and seven measured
batches of one million events for each scenario and supported JVM. Latency is
elapsed batch time divided by event count, not individual-event tail latency.
Native/Java still invokes the Java listener for every event. The producer now
proves this example eligible for automatic batching: native code computes chunks
and crosses JNI once per chunk, then Java delivers each listener call in order.
No handwritten batching is required. The first callback waits for its chunk's
computation; these measurements do not describe individual callback arrival
latency. Other loops use ordinary JNI when equivalence is unproved. Numerical
acceptance remains review.

The [optimization report](../../../docs/JAVA_BRIDGE_CALLBACK_OPTIMIZATION.md) contains
the current batching results. The [P5 evidence report](../../../docs/JAVA_BRIDGE_P5_EVIDENCE.md)
preserves the original JNI measurements and qualification with their identities.
