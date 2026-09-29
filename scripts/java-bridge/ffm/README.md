<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7e0 transport experiment

This private experiment reuses production protected-entry compilation and linking.
It does not enable FFM in producer artifacts. JNI and ordinary FFM call the same
16 native entries; no critical calls or upcalls are used. Cold failure handling
uses one shared experiment JNI helper, reporting the native failure type. This
is not production exception projection or a complete alternate bridge backend.

Prerequisites: prepared compiler/build/classes and test-classes from the recorded
revision, bootstrap Temurin 21.0.12.1+1 in JAVA_HOME, LLVM 23.1.0 on PATH, and
pinned Temurin 22.0.2+9 and 23.0.2+7 target installations. Linux also needs the
existing bridge support SDK via IRONWOOD_BRIDGE_SUPPORT_HOME. Nothing is
installed by this runner. Use a new output directory on every invocation.

From the checkout root:

```sh
python3 scripts/java-bridge/ffm/run.py \
  --target linux-x86_64 --jdks /jdks --cpu 1 \
  --output workspace/java-bridge/p7e0/evidence
```

Use `linux-arm64` or `macos-arm64` for those targets and omit `--cpu` where CPU 1
is not reserved. `--jdks` contains `temurin-22-TARGET/jdk-*` and
`temurin-23-TARGET/jdk-*` (macOS adds `Contents/Home`). The runner uses Java 21
for baseline sources and the builder. Optional FFM classes are compiled once
with `--release 22`, then the same bytes run on 22 and 23. Class-path and
module-path tests run with absent, correct and incorrect native-access grants.
Only the explicit experimental launch commands grant access.

Expected result: exit 0, `exit.txt` containing 0, successful functional/OOM and
frame-lifetime tests, expected native-access warnings/denials, and 84 benchmark
rows with matching checksums and zero warmed Java bytes/native managed
allocations. Stack probes intentionally reach native stack exhaustion in child
JVMs with core dumps disabled; their nonzero exits are diagnostic limits, not a
claim that arbitrary recursion is recoverable. Never run those cases in-process.

Three independent forks per transport/JDK each warm for eight 5-million-call
loops, then measure seven loops. Fork order alternates. `summary.json` reports
median/min/max amortized ns/call and reciprocal throughput, not sampled tail
latency. Allocation counts cover the current Java thread and Ironwood's managed
allocator. Native disassembly separately checks the successful add path has no
heap calls. Arena/handle setup, exception translation and JVM internals outside
that thread's Java allocation count are outside the measured interval.

The FFM frame is confined to the experiment's single invoking thread and reused.
It is not a production policy for concurrency or reentrancy. A future P7e1 must
resolve those costs and retain the default JNI deployment contract. Wrong-thread
and closed-segment checks confirm FFM rejects these two invalid frame lifetimes
before the side-effecting native entry executes. Do not invoke arbitrary
addresses, null pointers or undersized frames to test misuse.

Keep `*.command.json`, logs, `policy.json`, stack summaries/crash logs,
`samples.json`, `summary.json`, generated sources, LLVM, native disassembly,
`entries.tsv`, `hashes.json`, the input snapshot hash and compiler revision.
Keep native support licenses/source adjacent to Linux images. Record host/image
identity and CPU/governor alongside the evidence; translated execution must not
be labelled physical hardware evidence. See the [report](../../../docs/JAVA_BRIDGE_FFM_EXPERIMENT.md).
