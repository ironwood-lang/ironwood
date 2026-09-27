<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Internal Java Bridge validation preparation

These are internal preparation and focused validation tools, not a public bridge
producer or a completed release qualification runner.
The ordinary IDK/toolchain pins remain separate. Setup alone may download JDKs;
offline preflight and future test execution must never download a missing tool.

On macOS ARM64, from the checkout root:

```sh
python3 scripts/prepare-java-bridge.py --setup --target macos-arm64 \
  --prefix workspace/java-bridge/jdks/temurin-21-macos-arm64 \
  --evidence workspace/java-bridge/evidence/p0a/macos-jdk.json
python3 scripts/prepare-java-bridge.py --check --target macos-arm64 \
  --prefix workspace/java-bridge/jdks/temurin-21-macos-arm64 \
  --evidence workspace/java-bridge/evidence/p0a/macos-jdk-offline.json
```

Select that installation's `Contents/Home` as `JAVA_HOME` and put its `bin`
first in `PATH` for bootstrap compiler checks. Preflight refuses injected JVM
options, mismatched vendors/versions/architectures, and stale installation records.
Setup refuses to replace any existing prefix; checks never fall back to PATH.

P1's D210 macOS experiment additionally needs the pinned Java 22 and 23 launchers.
Use `--java-version 22` or `--java-version 23` with `--setup` and `--check`, and
prefixes `workspace/java-bridge/jdks/temurin-22-macos-arm64` and
`workspace/java-bridge/jdks/temurin-23-macos-arm64`. The version-specific pin files
preserve Java 21 installation identities. The development Docker setup continues
to select Java 21; other launchers are installed explicitly when due.

After the focused compiler test
`Java Bridge production scalar libraries preserve ABI and warm-path allocation`
prints its evidence directory, run:

```sh
python3 scripts/java-bridge/check-macos-extraction.py \
  --scalar-evidence workspace/java-bridge/evidence/p1/scalar-entries/run-NUMBER \
  --evidence workspace/java-bridge/evidence/p1/macos-extraction-NEW
```

The output directory must be new. The runner copies the finished O0/O3 images,
verifies their ad-hoc signatures, and signs the producer copy only if necessary.
Its fixture jar performs private byte-copy extraction, SHA-256 verification and
atomic publication. All three unmodified pinned launchers run the extracted
payload in fresh ordinary and checked-JNI children. Final/extracted signatures,
launcher entitlements, dependency names, attributes, commands and exit statuses
are retained. No JDK is re-signed or downloaded, and no consumer-side signing or
native-access flags are used. This private fixture does not satisfy P2/P6's
separate generated-artifact repetitions.

For Linux, prepare the ordinary platform image with `scripts/test-platforms.sh
--setup` first, then pass its exact image tag to the bridge layer. For example:

```sh
python3 scripts/prepare-java-bridge.py --setup-image --target linux-arm64 \
  --base-image ironwood-tests-linux-arm64:1b272c5800841993 \
  --docker-context colima-ironwood-tests \
  --evidence workspace/java-bridge/evidence/p0a/linux-arm64-image.json
python3 scripts/prepare-java-bridge.py --setup-image --target linux-x86_64 \
  --base-image ironwood-tests-linux-x86_64:1b272c5800841993 \
  --docker-context colima-ironwood-tests \
  --evidence workspace/java-bridge/evidence/p0a/linux-x86_64-image.json
```

The bridge tag includes the base image identity, target, JDK pins, setup code and
Dockerfile. The JSON evidence records the resulting image inspection. Use the
returned image explicitly with `docker run --rm --platform linux/arm64` or
`linux/amd64`. It selects Temurin through `JAVA_HOME`/`PATH`, retaining the existing
LLVM/TLS SDK. Offline in-image preflight is:

```sh
python /opt/ironwood-bridge-preparation/scripts/prepare-java-bridge.py \
  --check --target linux-arm64 --prefix /opt/ironwood-bridge-jdk \
  --evidence /tmp/jdk-check.json
```

Use `linux-x86_64` for the x86-64 image. On a Linux hardware host, omit the Colima
Docker context and use a prepared local base image for that architecture. The
preparation layer has no Colima dependency. Preserve archive notices in the JDK;
these development images are not published runtime payloads.

Record physical CPU, host/guest OS, VM configuration, translator, image identity,
JDK and compiler/LLVM versions independently. Guest `uname` and successful JDK
preflight cannot establish matching hardware. Rosetta x86-64 results are translated
functional evidence only. ARM64 stack and P6 cells require matching hardware;
x86-64 hardware cases remain pending D213. No SSH or paid host is assumed.

Linux shared output now requires the separate
[pinned native support SDK](../../docs/JAVA_BRIDGE_NATIVE_SUPPORT.md). Prepare
it explicitly with `scripts/prepare-java-bridge-support.py --setup`, then pass
`IRONWOOD_BRIDGE_SUPPORT_HOME` to the producer. For the local ARM64 development
image, the focused payload build is:

```sh
docker --context colima-ironwood-tests run --rm --platform linux/arm64 \
  -v "$PWD:/workspace" -w /workspace \
  -e IRONWOOD_BRIDGE_SUPPORT_HOME=/workspace/workspace/java-bridge/support/linux-arm64 \
  ironwood-bridge-linux-arm64:05d5199baf46c922 \
  java -ea -cp compiler/build/classes:compiler/build/test-classes \
  ironwood.compiler.CompilerTests \
  --test 'Java Bridge production libraries contain cold initialization and allocation failures' \
  --test 'Java Bridge production libraries preserve disjoint native traces'
```

Use the two emitted evidence directories in the D202 runner:

```sh
python3 -B scripts/java-bridge/check-linux-dependencies.py \
  --target linux-arm64 --execution-scope 'ARM64 virtualization' \
  --development-image ironwood-bridge-linux-arm64:05d5199baf46c922 \
  --docker-context colima-ironwood-tests \
  --library-evidence workspace/java-bridge/evidence/p1/libraries/run-NUMBER \
  --traces-evidence workspace/java-bridge/evidence/p1/shared-traces/run-NUMBER \
  --evidence workspace/java-bridge/evidence/p1/minimal-NEW
```

The evidence directory must be new. The runner uses an already installed local
development image to build a scratch JVM runtime, records its exact identities,
inventories the external libraries, audits final ELF closure and exercises the
native payloads without development tools or extra system-runtime packages.
Preserve each payload directory including its adjacent support/source directory.
For local translated x86-64 use target `linux-x86_64`, platform `linux/amd64`,
image `ironwood-bridge-linux-x86_64:1a18fe26577fb8c5`, its prepared SDK and
execution scope `x86-64 Rosetta translation`. On authorized matching Linux
x86-64 hardware, omit the Colima context, use its prepared local image and
record `x86-64 physical hardware` with independent physical-host evidence.
P1 functional checks alone never close P6 hardware/performance qualification.

Preparation regressions:

```sh
python3 -B scripts/test-prepare-java-bridge.py
python3 -B scripts/test-prepare-java-bridge-support.py
```
