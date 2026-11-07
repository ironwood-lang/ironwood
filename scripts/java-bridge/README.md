<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Current managed Java Bridge validation

Use the guarded scripts/test-gc6-* drivers from the repository root with explicit
SDK, LLVM, Java and output paths. They cover generated objects, anchors,
registrations, listeners, borrowed views, suspended reentry, carrier/JNI cleanup,
independent images and metadata rejection. The current API is schema 2 /
objects-gc-v1. See [usage](../../docs/JAVA_BRIDGE_USAGE.md).

measure-managed.py retains the micro, alternating shared-reference retention,
OrderBook pooling/batch/latency and listener workloads. Select one --case and
use --quick for bounded migration smoke. Each child has a 16 GiB process-group
RSS guard and 60-second bound. Output records identities and observations without
GC8 performance/capacity acceptance. Pure Java OrderBook baseline sources remain
in the project; BridgeBench scopes the generated result anchors explicitly.

Preparation tools and the current assembly/build-tool/version-policy/loader
checks remain available with their actual prerequisites. GC7 retires the old
P0-P7 projection-specific aggregation/replay/stack/FFM runners, preserving exact
source hashes and replacement mappings in the [ledger](../../docs/GC7_DISPOSITION.md).
Earlier reports and measured archives remain frozen historical evidence. Do not
run discarded self-hosting or old ownership projection tools against this model.

[GC9](../../docs/GC9_LINUX_X86_64_DISTRIBUTION.md) qualifies native/managed
distribution on macOS arm64, Linux arm64 and physical Linux x86-64 within exact
OS/SDK/toolchain bounds. arm64e remains unqualified. Translated execution does
not establish physical-hardware performance qualification.
[GC10 acceptance](../../docs/GC10_ACCEPTANCE.md) reconciles the final evidence.
All images retain the single-caller/external-serialization and unsupported
foreign-lifetime refusals.
