<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Runnable Java Bridge examples

The maintained [example directory](../examples/java-bridge) contains primitive
values, anchored objects, finite generics, borrowed byte views and synchronous
listeners. Use each example's compile/link/run/test scripts. The generated
artifacts require the current matching GC6 SDK inputs.

The [basics consumer](../examples/java-bridge/basics/src/main/java/org/ironwood/javabridge/basicsconsumer/Main.java)
constructs a Counter through Roots, holds an independent anchor and opens a
ListenerRegistration before installing its listener. It prints listener values
2 and 5, then a total of 5. Registration closure releases JNI ownership; anchor
closure releases its foreign root. Neither action substitutes for a source
resource close operation.

The example [BridgeLifetime utility](../examples/java-bridge/support/BridgeLifetime.java)
resolves the image-specific generated support package at setup. Generic getter
results use their generated RootHandle.value() API directly. The listener example
also checks nested JVM reentry and replacement of retained listeners.

See [current usage and gates](JAVA_BRIDGE_USAGE.md), [GC6](GC6_IMPLEMENTATION.md)
and the [GC7 migration ledger](GC7_DISPOSITION.md). Pre-GC bridge reports remain
historical evidence, including their old source snippets and measurement hashes.
