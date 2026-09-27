<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Internal Java Bridge validation preparation

These are P0 preparation tools, not a public bridge producer or a completed
qualification runner. No bridge runtime case is implemented by this directory yet.
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

Preparation regressions:

```sh
python3 scripts/test-prepare-java-bridge.py
```
