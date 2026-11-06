<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood Java Bridge

The implemented bridge is the GC6 managed model. Read the current
[overview](JAVA_BRIDGE.md) and [producer/API guide](JAVA_BRIDGE_USAGE.md).
Repository consumers use generated Roots, RootHandle.value()/close() and
ListenerRegistration, with source resource closure kept separate.

The former manual/permanent/owned projections and whole-program reclamation
proofs are retired by [GC7](GC7_IMPLEMENTATION.md). Historical bridge design,
measurement and qualification reports preserve their original provenance and
hashes. They do not expand the current platform, serialization or foreign-lifetime
contract. No self-hosting or GC8 work is authorized by this migration.
