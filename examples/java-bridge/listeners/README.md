<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Retained Java listeners

`ResultProcessor` is native Ironwood code. Its generated `ResultListener` is an
ordinary Java interface, implemented by the application. Calls are synchronous
on the invoking thread. A registration stays alive until replacement, clearing
or registration closure. A suspended `process()` keeps its original registration
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
listeners-ok: registration, reentry, exception identity, anchors
```

The consumer tests reentry and replacement, independent processor-anchor and
registration lifetimes, Java exception identity through native code, continued
use after failure and explicit cleanup. No manual JNI or library loading is
needed. The paired JAR can also run on supported Java 22 and 23 on its recorded native target.

The separate benchmark compares the same stateful arithmetic and exactly one
listener event per result in three scenarios: native processor/native listener,
Java processor/Java listener, and native processor/Java listener. The native-only
driver reuses a static managed listener and clears the processor's binding after
the batches. Java consumers scope processor anchors and registrations separately.
Setup and registration are outside timing. It does not modify OrderBook.

The current bounded driver is scripts/java-bridge/measure-managed.py with
--case listeners; --quick selects a functional migration smoke. Supply the
required --sdk, --llvm-home, --java-home and fresh --output paths. Run --help
for current options. Native children keep their 16 GiB RSS guard and 60-second
deadline. This driver does not recreate the retired P5 multi-JDK measurement
matrix or establish new performance acceptance.

The [GC8 foreign measurements](../../../docs/GC8_IMPLEMENTATION.md#foreign-paths-and-actual-o3-execution)
separately qualify current critical/JNI, batched listeners and collecting reentry.
The original P5 and optimization reports below preserve their historical
artifacts, batching limits and numerical observations.

The [optimization report](../../../docs/JAVA_BRIDGE_CALLBACK_OPTIMIZATION.md) contains
the historical batching results. The [P5 evidence report](../../../docs/JAVA_BRIDGE_P5_EVIDENCE.md)
preserves the original JNI measurements and qualification with their identities.
