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

## Java version policy experiment

P2's D203 refusal smoke test and D209 product experiment use two additional
macOS ARM64 launchers. These are controls/probes, not supported consumer cells.
Prepare once, with network access, using `--java-version 24` and `25` separately:

```sh
python3 scripts/prepare-java-bridge.py --setup --java-version 24 --target macos-arm64 \
  --prefix workspace/java-bridge/jdks/temurin-24-macos-arm64 \
  --evidence workspace/java-bridge/evidence/p2/java24-jdk.json
python3 scripts/prepare-java-bridge.py --setup --java-version 25 --target macos-arm64 \
  --prefix workspace/java-bridge/jdks/temurin-25-macos-arm64 \
  --evidence workspace/java-bridge/evidence/p2/java25-jdk.json
```

Build the compiler with the pinned Java 21 JDK first. Then run offline:

```sh
python3 -B scripts/java-bridge/check-java-version-policy.py \
  --evidence workspace/java-bridge/evidence/p2/version-policy-NEW
```

The directory must be new. The runner checks pinned JDK identities, rejects JVM
option injection, and builds O0/O3 ordinary and separately paired experimental
jars. Only a private compiler copy's version admission/metadata changes. It
records default, checked-JNI and explicit-deny launches across class path,
module path and executable jars, including native-image signatures, exact bytes,
warnings, native-denial mapping evidence and continued functional calls.
No public producer bypass is installed. Java 21-23 remains the support baseline;
see [the D209 report](../../docs/JAVA_BRIDGE_JAVA25.md).

## P2 producer loader qualification

After building the current compiler with pinned Java 21 and provisioning the
three supported launchers, run:

```sh
python3 -B scripts/java-bridge/check-producer-loaders.py \
  --evidence workspace/java-bridge/evidence/p2/producer-loaders-NEW
```

This builds ordinary O0/O3 jars through `ironwoodc --java-bridge` and checks
package/class collisions, disjoint artifacts, mixed identity/signatures, loader
anchoring, mapped-image rebinding refusal and deployment failures. The test-only
inspector opens images with RTLD_NOLOAD; it cannot load the target itself.
A separately identified private compiler copy injects partial registration
failure and counts its cleanup. That fault image and intentionally inconsistent
jars are negative controls, never production payload passes. No shipped runtime
hook or consumer bypass is added. Every child runs with checked JNI; ordinary
flag-free launch-form checks are covered by the producer selector and D209 runner.

The default is 120 child cases across O0/O3 and Java 21/22/23. Use repeated
`--scenario` for focused follow-ups, for example `--scenario corrupt --scenario
existing`. Supply `--llvm-home` if LLVM 23 is not at `/opt/homebrew/opt/llvm`.
The new evidence directory records commands, diagnostics, faults, revisions,
exact jar/class/image identities and extracted signature checks. This is P2
value-producer validation, not object/lifetime or final P6 qualification.

The same runner supports `--target linux-arm64` or `--target linux-x86_64` with
an explicit `--execution-scope`, matching `--llvm-home`, and optional
`--java21-prefix /opt/ironwood-bridge-jdk` in prepared development images.
Linux defaults to 114 children: the six macOS deployment-floor cases are
explicitly not applicable, while ELF/glibc requirements are audited by the
producer. All other collision, rollback, anchor and deployment assertions are
preserved. The selected target's pinned 22/23 prefixes must exist under the JDK
root. The test-only inspector uses Linux JNI headers and `libdl` there.

## Matched host assembly

`check-assembly.py` uses prepared local images and pinned JDK checks to build the
same source or dedicated compiled engine on all three targets, assemble without native recompilation, verify
input-order reproducibility and launch the finished jar in class-path,
automatic-module and executable-jar forms. Each form runs plain and checked JNI.
Linux consumers run in the existing minimal JVM images. No tool is downloaded or
remote machine provisioned. Example from the checkout root:

```sh
python3 scripts/java-bridge/check-assembly.py \
  --source scripts/java-bridge/VersionProbe.iron --export versionprobe \
  --consumer scripts/java-bridge/VersionProbeConsumer.java \
  --main VersionProbeConsumer --expected version-probe-ok --artifact versionprobe.jar \
  --docker-context colima-ironwood-tests \
  --linux-arm-image ironwood-bridge-linux-arm64:05d5199baf46c922 \
  --linux-x86-image ironwood-bridge-linux-x86_64:1a18fe26577fb8c5 \
  --minimal-arm-image ironwood-bridge-minimal-linux-arm64:e3372c6417429107 \
  --minimal-x86-image ironwood-bridge-minimal-linux-x86_64:e6085cf6f80dcf4a \
  --evidence workspace/java-bridge/evidence/p6a/assembly-NEW
```

Use a new evidence directory. Evidence preserves commands, image identities,
compiler/runtime/program/target hashes, input hashes, output and exit statuses.
The local Linux ARM64 runs use ARM64 virtualization. Local Linux x86-64 runs are
explicitly Rosetta translated functional evidence, never hardware stack or timing
qualification. These Java 21 assembly checks do not replace P6b's final matrix.

For a precompiled engine, replace `--source` with `--class-path` pointing to its
dedicated directory or archive. The runner records every input file hash. Use
`--optimization O0` for the matched containment variant; O3 is the default.
`--java-option=-XX:-DoEscapeAnalysis` adds the explicit allocation diagnostic to
all consumer children. `--expected` can contain multiple output lines, omitting
only the final newline. These choices do not change the hardware-evidence scope.

### Local build-tool integration

`check-build-tools.py` exercises the runnable Maven/Gradle examples, installs into
two isolated local repositories, runs both direct and cross-tool consumers and
checks installed jar/source/Javadoc bytes against the producer outputs. Supply
already installed build tools; it downloads no build-tool distribution and
configures no remote publishing. Maven may resolve its ordinary build plugins.

```sh
python3 scripts/java-bridge/check-build-tools.py \
  --java-home workspace/java-bridge/jdks/temurin-21-macos-arm64/jdk-21.0.12.1+1/Contents/Home \
  --maven /absolute/path/to/mvn --gradle /absolute/path/to/gradle \
  --evidence workspace/java-bridge/evidence/p6a/build-tools-NEW
```

The runner invokes each example's `clean` task, records exact commands, logs,
exit statuses, tool/JDK versions and installed hashes, and checks four-line
consumer output. Optional `--paired-jar` uses an existing assembled **value
example** jar with that exact API, preserving its main bytes. This is a build-tool
integration check, not a substitute for the final native/JDK qualification.

## Fixed candidate checks

`check-candidate.py` checks the five assembled P6a cases without rebuilding them.
It rejects changed jars/manifests or mixed compiler/runtime identities, audits
the selected native payload and dependencies, saves disassembly, and runs all
three launch forms under pinned Java 21/22/23 with plain and checked JNI. On
macOS it also checks extracted signatures and repeats the Java 24 refusal on
class/module paths with empty extraction directories.

```sh
python3 scripts/java-bridge/check-candidate.py \
  --candidate workspace/java-bridge/evidence/p6a/candidate-2559e145 \
  --target macos-arm64 --execution-scope 'ARM64 hardware' \
  --llvm-home /opt/homebrew/opt/llvm \
  --evidence workspace/java-bridge/evidence/p6b/candidate-macos-NEW
```

Linux uses its matching target, explicit execution scope and LLVM home; the
prepared development images also need `--java21-prefix /opt/ironwood-bridge-jdk`.
The JDK root must contain the pinned target's 22/23 installations. `--audit-only`
performs packaging inspection without qualifying any JVM cell. Scope labels
are operator declarations, not physical-host attestations. The runner covers
fixed candidate loading and its consumers; full fault, lifetime, stack and
performance checks remain separate. Never count translated execution as a
hardware matrix pass. See `docs/JAVA_BRIDGE_P6_EVIDENCE.md` for fixed jar hashes.

## Supported-JDK fixture replay and stack checks

`replay-consumers.py` accepts repeated `--fixture` paths naming exact evidence
directories from passed focused generated tests, plus `--target`,
`--execution-scope`, `--java-major`, `--jdk-prefix` and a new `--evidence` path.
It keeps the original asserting consumers, JNI checks, heap/escape-analysis
options and fault environment. It substitutes only the pinned launcher and a
fresh temporary extraction directory. It checks recorded output/exit contracts
and jar-native hashes. Comparisons normalize only elapsed-time fields, absolute
Java allocation totals into deltas and process-private extraction paths.
Actual consumer assertions are unchanged. Unsupported Java 24 checks and
destructive stack probes are explicitly excluded from supported-JDK replay.

`check-stack.py` builds its public O0/O3 stack fixture through the fixed compiler,
requires matching candidate compiler/runtime identities, and checks all three
supported JDKs. For example:

```sh
python3 scripts/java-bridge/check-stack.py \
  --candidate workspace/java-bridge/evidence/p6a/candidate-2559e145 \
  --target macos-arm64 --execution-scope 'ARM64 hardware' \
  --llvm-home /opt/homebrew/opt/llvm \
  --evidence workspace/java-bridge/evidence/p6b/stack-macos-NEW
```

Linux uses the same target/JDK/LLVM options as fixed-candidate checks. Keep the
prepared support SDK available to the producer. Eight bounded depth pairs
assert native/Java live values, caught failures and continued calls on the
default JVM stack. Separate disposable JVMs probe doubling depths at 512k/1m,
disable core dumps and preserve crash logs. Those failures are diagnostics,
never a pass for stack-overflow recovery. The runner accepts no translated
execution scope. This augments rather than replaces D213's original P0-10
production-harness checks.

## Performance and physical-host handoff

`measure-performance.py` takes the fixed-candidate target, execution scope,
JDK/LLVM options, `--host-notes` and a new `--evidence` path. It prepares original
micro/JNI and OrderBook batch fixtures, records immutable input/payload hashes,
and collects isolated unchecked-JNI measurements. Use `--prepare-only` first
when preparation must finish before timing; then use `--measure-prepared` with
the same evidence directory. Do not change recorded inputs between stages.
`summarize-performance.py --evidence PATH` checks identities, checksums and
allocation expectations and writes observations without numerical thresholds.

`measure-orderbook-latency.py --performance PATH --candidate CANDIDATE
--llvm-home LLVM --evidence NEW_PATH` verifies those prepared inputs and collects
30 separate OrderBook latency reports. It preserves the project's workload
checks and reports clock overhead without subtracting it. See
[the measured results](../../docs/JAVA_BRIDGE_PERFORMANCE.md) for limitations.

`qualify-host.py` sequences the exact 17 fixture and 15 proof selections in
`qualification-tests.json`, candidate/loading/stack checks, supported-JDK
replays, performance and latency collection. It requires a clean exact revision
and explicit physical execution scope. `--plan-only` writes commands without
qualification. Minimal-JVM launches and physical-host attestation remain
separate operator steps. Follow [the complete x86-64 handoff](../../docs/JAVA_BRIDGE_X86_HANDOFF.md)
for package verification, prerequisites, commands and expected results.

`measure-retention.py` takes the fixed candidate, target/scope, pinned JDK options,
`--host-notes` and a new `--evidence` directory. It measures the existing roots
candidate with unchecked JNI: three forks per JDK, seven warmed observations,
Java allocation counters, and native-state/lifetime assertions outside timing.
It records the exact candidate and consumer bytes, plus JIT logs. This separates
retaining-call costs from checked-JNI correctness-fixture diagnostics. Use
`--repository` only when running an identical archived copy of the runner outside
its normal repository location.

For P5 nested callbacks, `check-stack.py --callbacks` builds the ordinary Java
listener interface and public producer JAR at O0/O3, then runs all three pinned
JVMs. No `--candidate` is needed for this in-development checkpoint; supplying one
still requires exact candidate compiler/runtime identities. For example:

```sh
python3 scripts/java-bridge/check-stack.py --callbacks \
  --target macos-arm64 --execution-scope 'ARM64 hardware' \
  --llvm-home /opt/homebrew/opt/llvm \
  --evidence workspace/java-bridge/evidence/p5/callback-stack-NEW
```

On Linux use its matching target, existing pinned JDK roots and LLVM home, and
`--execution-scope 'ARM64 virtualization'` or `'x86-64 physical hardware'` as
appropriate. `--java21-prefix` selects an existing pinned image JDK. Archived
validation checkouts without Git metadata can supply `--revision-file`; retain
the input archive's complete hash manifest alongside that identity. Nothing is
downloaded or installed. Outputs preserve paired native images, generated JAR
identities, JDK settings, disassembly, commands and child logs. Each default-stack
cell checks native depths 1/8/32/64 from Java depths 0/64, original deepest Java
throwable identity and continued use. Separate 512k/1m child probes start at 64
and double until failure. They distinguish JVM startup refusal, observed Java
stack overflow and native crash; they do not promise recovery or a general safe
depth. This qualifies synchronous primitive callbacks, not pending stateful paths.
