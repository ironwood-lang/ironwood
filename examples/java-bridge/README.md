<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Managed Java Bridge examples

These maintained programs use schema 2 / objects-gc-v1. Build with the current
matching BDWGC SDK and LLVM 23; generated consumers call native images from Java.
Use each directory's compile/link/run/test scripts. No option selects a manual
memory model.

The basics and listeners examples hold generated RootHandle anchors and separate
ListenerRegistration scopes. Generics and bounded-generics exercise rooted
results and shared fields. Byte views keep their synchronous borrowed-storage
confinement. Value/build-tool examples preserve ordinary dependency workflows.

[BridgeLifetime](support/BridgeLifetime.java) resolves image-specific generated
support types during example setup. It delegates to RootHandle.value()/close(),
Roots and Listeners. Source resource close stays separate from anchor and
registration closure. Critical loops retain their original workload and native
allocation expectations; collection is never inserted to repair old counts.

Generics benchmark scripts accept --quick. The current
[scripts/java-bridge runner](../../scripts/java-bridge/README.md) preserves micro,
retention, OrderBook and listener workloads with bounded children. Historical
reports retain their hashes and old scope; they do not qualify new platforms or
GC8 performance. See [current usage](../../docs/JAVA_BRIDGE_USAGE.md).
