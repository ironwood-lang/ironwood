<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native Ironwood for Java

Ironwood's managed Java Bridge produces closed-world native dependencies callable
from an ordinary Java application. The current model is GC6 schema 2 /
objects-gc-v1 with exact layouts, foreign roots and suspended JVM reentry
segments. GC7 retires the former manual/permanent/owned projections and migrates
repository consumers. See [usage](JAVA_BRIDGE_USAGE.md), [GC6](GC6_IMPLEMENTATION.md)
and [GC7](GC7_IMPLEMENTATION.md).

Construct managed facades through generated Roots factories. Reference results
carry independent RootHandle anchors: value() reads the facade, close() releases
that anchor. ListenerRegistration has a separate lifetime. Source file/socket/TLS
close methods remain ordinary resource operations. Collection, anchor closure and
registration closure do not substitute for source resource closure.

The producer keeps primitive/String calls, supported copied arrays and borrowed
views, finite generic facades, enum conversion, mapped exceptions and synchronous
callbacks within their proved contracts. Unknown retaining/collection/native
lifetime effects remain conservative. Unsupported asynchronous lifetimes refuse.
The single-caller/external-serialization contract remains mandatory.

[GC9 qualification](GC9_LINUX_X86_64_DISTRIBUTION.md) covers macOS arm64,
Linux arm64 and physical Linux x86-64 native/managed distribution, including
a common three-target jar. Java 21 JNI and recorded Java 23 controls are the
qualified GC JVM scope; the launcher's Java 21-25 range does not expand it.
arm64e remains unqualified. [GC10](GC10_ACCEPTANCE.md) records final acceptance.
Earlier P0-P7 reports preserve their original claims and hashes as historical
pre-GC evidence; they do not broaden the current platform/lifetime gates.

Maintained runnable consumers are in [examples/java-bridge](../examples/java-bridge).
The focused scripts/test-gc6-* drivers cover production images, anchors,
registrations, callbacks, borrowed views, carriers, JNI completion and metadata.
The [migration ledger](GC7_DISPOSITION.md) maps retired qualification tools to
these controls. No GC8 capacity acceptance or self-hosting is implied.
