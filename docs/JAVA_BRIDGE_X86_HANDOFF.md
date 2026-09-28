<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge Linux x86-64 hardware handoff

The maintainer subsequently authorized SSH execution on Estonia. The selected
hardware checks and measurement collection are complete; see the [hardware
report](JAVA_BRIDGE_X86_EVIDENCE.md). Numerical acceptance remains pending.
This document retains the reproducible focused handoff for later authorized
runs. Preparing a package alone is not a hardware pass; Rosetta remains
functional/static evidence. No paid infrastructure was provisioned.

## Package and prerequisites

The local package directory is `workspace/java-bridge/handoff`. Its
`manifest.json` identifies the exact qualification revision, frozen production
compiler/runtime identities, candidate jar/native hashes, Docker image IDs and
SHA-256 of each delivered archive. It contains a local Git bundle for
`java-bridge`, candidate/SDK/JDK-cache payloads, and saved development and minimal
JVM images. Nothing was pushed or published. The original manifest/revision
`6f608190` is preserved. `qualification-update.json` binds test correction
`d533f1a6` used by the actual continuation. `qualification-tools.json` and
`qualification-tools-fef82f3e.bundle` add that fix and the retained-call runner
for a fresh complete run. Verify both manifests before using the supplement;
production payload/image identities still come from the original manifest.

Use a genuine x86-64 CPU, or an x86-64 VM on an x86-64 CPU as allowed by D205.
Record the physical CPU and hypervisor/container relationship yourself; a
container's reported architecture alone cannot prove absence of translation.
Required host tools are Git, Docker, Python 3 and tar, with sufficient local
disk for images, complete corresponding runtime source, fault jars and logs.
The delivered development image supplies Python 3.14, the pinned LLVM 23.1.0
toolchain/glibc 2.17 sysroot and Temurin 21.0.12.1+1. Temurin 22.0.2+9 and
23.0.2+7 are installed from the delivered SHA-256 cache. Do not substitute newer
JDK patches, LLVM, runtime libraries or a different compiler revision.

Verify every top-level archive against `manifest.json` before loading it.
The package was prepared locally; transport verification is not publisher
authentication. For example, from the transferred package directory:

```sh
python3 - <<'PY'
import hashlib, json
from pathlib import Path
for manifest_name in ('manifest.json', 'qualification-tools.json'):
    manifest = json.loads(Path(manifest_name).read_text())
    for name, expected in manifest['files'].items():
        digest = hashlib.sha256()
        with Path(name).open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise SystemExit('SHA-256 mismatch: ' + name)
print('Archive hashes match the handoff manifest')
PY
```

Create a separate ordinary checkout on that host. These are manual setup
commands, not permission to change the current Mac checkout or create worktrees.
Replace `/absolute/path/to/handoff` with the transferred directory:

```sh
export BRIDGE_HANDOFF=/absolute/path/to/handoff
git clone --origin bundle --branch java-bridge "$BRIDGE_HANDOFF/ironwood.bundle" ironwood-bridge-validation
cd ironwood-bridge-validation
git remote add origin https://github.com/ironwood-lang/ironwood.git
export BRIDGE_REVISION=$(python3 -c 'import json,os; print(json.load(open(os.environ["BRIDGE_HANDOFF"]+"/manifest.json"))["qualification_revision"])')
test "$(git rev-parse HEAD)" = "$BRIDGE_REVISION"
git remote -v
git bundle verify "$BRIDGE_HANDOFF/qualification-tools-fef82f3e.bundle"
git fetch "$BRIDGE_HANDOFF/qualification-tools-fef82f3e.bundle" java-bridge
git merge --ff-only FETCH_HEAD
export BRIDGE_REVISION=$(python3 -c 'import json,os; print(json.load(open(os.environ["BRIDGE_HANDOFF"]+"/qualification-tools.json"))["tooling_revision"])')
test "$(git rev-parse HEAD)" = "$BRIDGE_REVISION"
tar -xf "$BRIDGE_HANDOFF/payloads.tar" -C .
docker load -i "$BRIDGE_HANDOFF/development-image.tar"
docker load -i "$BRIDGE_HANDOFF/minimal-jvm-image.tar"
docker image inspect ironwood-bridge-linux-x86_64:1a18fe26577fb8c5
docker image inspect ironwood-bridge-minimal-linux-x86_64:e6085cf6f80dcf4a
```

Compare the inspected image identities with the manifest. Containerd image
stores report the OCI index digest; classic Docker stores may report the config
digest instead. The manifest records both, and every saved OCI blob was checked
against its digest. Require the matching recorded digest for the store's identity
kind, Linux/amd64 and the expected tag. Preserve the complete SDK source/licenses;
never deliver only its shared libraries. Prepare the two
archived consumer JDKs using the checked-in pins and delivered cache:

```sh
for version in 22 23; do
  docker run --platform linux/amd64 -v "$PWD:/work" -w /work \
    ironwood-bridge-linux-x86_64:1a18fe26577fb8c5 \
    python scripts/prepare-java-bridge.py --setup --java-version "$version" \
    --target linux-x86_64 --prefix "/work/workspace/java-bridge/jdks/temurin-$version-linux-x86_64" \
    --cache /work/workspace/java-bridge/downloads \
    --evidence "/work/workspace/java-bridge/evidence/hardware-jdk-$version.json"
done
```

Setup refuses existing prefixes. For previously prepared prefixes, use `--check`
instead of deleting them. A missing or mismatched cached archive is a blocker;
do not silently substitute an installation.

## Focused execution

Record host `uname -a`, `lscpu`, virtualization details, available memory and
competing work. On Estonia, record `/proc/cmdline` and use Docker
`--cpuset-cpus 1-4,9-12` for its authorized isolated cores. Use a reviewed CPU
selection on any other host. Retain the current governor and record it. Preserve
containers and prior evidence; use a new evidence directory for each run.
Stop unrelated benchmarks and builds. Keep default JVM stack
settings for bounded cases. The runner supplies stack sizes only to isolated
limit probes, disables core dumps and preserves their error logs.

Run from the clean checkout. Replace the host-notes text with actual CPU, VM
resources and contention information; keep the explicit physical-hardware scope:

```sh
docker run --platform linux/amd64 -v "$PWD:/work" -w /work \
  -e IRONWOOD_BRIDGE_SUPPORT_HOME=/work/workspace/java-bridge/support/linux-x86_64 \
  ironwood-bridge-linux-x86_64:1a18fe26577fb8c5 \
  python scripts/java-bridge/qualify-host.py \
  --candidate workspace/java-bridge/evidence/p6a/candidate-2559e145 \
  --revision "$BRIDGE_REVISION" --target linux-x86_64 \
  --execution-scope 'x86-64 physical hardware' \
  --java21-prefix /opt/ironwood-bridge-jdk \
  --java-home /opt/ironwood-bridge-jdk/jdk-21.0.12.1+1 \
  --jdk-root /work/workspace/java-bridge/jdks --llvm-home /opt/ironwood-toolchain \
  --host-notes 'REPLACE with actual CPU, physical/VM relationship, resources and contention' \
  --evidence workspace/java-bridge/evidence/p6b/x86-hardware-1
```

`--plan-only` with a different new evidence path writes exact commands without
executing qualification. The checked-in `qualification-tests.json` selects 17
fixture behaviors and 15 compiler proof/guard tests, never an unfiltered suite.
Every stage stops on failure and retains its command/log/exit. Rerun only failing
tests or affected stages into new evidence paths; do not erase failures.

Expected stage results are:

| Stage | Required outcome |
| --- | --- |
| Candidate | 90 exact plain/checked class/module/executable launches across Java 21/22/23, matching sealed payload hashes and ELF dependencies |
| Loader | 114 O0/O3/JDK scenarios; six macOS-only floor cases explicitly not applicable |
| Fixtures | 17 selected tests pass, including original P0-10 production stack envelope/limits, lifetime failures, identity/GC, enums, custom/builtin exceptions and OrderBook allocation |
| Proofs | 15 selected admission, parity, safety and version-guard tests pass |
| Replay | 196 unchanged generated consumer cases on each of Java 22 and 23, retaining all fault assertions and expected exits |
| Generated stack | Six bounded O0/O3/JDK cells pass; separate 512k/1m limit observations preserve first unsuccessful depths and crash logs |
| Performance | 132 observations, exact input identities/checksums and zero warm scalar/instance/cache-hit Java allocation; numerical acceptance is still manual |
| OrderBook latency | 30 verified reports with clock controls and preserved workload checks; three forks per mode/JDK, numerical acceptance still manual |
| Retaining calls | 63 unchecked observations against the frozen roots candidate, zero warm Java allocation, verified state/lifetime behavior and exact input hashes |

Limit-child crashes are diagnostics, not successful recovery. Unexpected crashes,
JNI warnings, proof failures, changed identities or incorrect counters block the
affected qualification. The emergency secondary-allocation fatal control has
its exact expected exit 1; it is not mislabeled as normal recovery.

## Minimal JVM consumers and collection

On the same physical host, repeat the final scalar/exception payload in the
minimal JVM image, which has no compiler or extra native runtime installation:

```sh
export BRIDGE_CANDIDATE="$PWD/workspace/java-bridge/evidence/p6a/candidate-2559e145"
for level in O0 O3; do
  docker run --platform linux/amd64 \
    -v "$BRIDGE_CANDIDATE/version-$level/combined:/payload:ro" \
    ironwood-bridge-minimal-linux-x86_64:e6085cf6f80dcf4a \
    -Xcheck:jni -cp /payload/version-probe.jar:/payload/consumer-classes VersionProbeConsumer
done
docker run --platform linux/amd64 \
  -v "$BRIDGE_CANDIDATE/orderbook-O3/combined:/payload:ro" \
  ironwood-bridge-minimal-linux-x86_64:e6085cf6f80dcf4a \
  -Xcheck:jni -XX:-DoEscapeAnalysis -cp /payload/orderbook.jar:/payload/consumer-classes OrderBookConsumer
```

Each scalar run must print only `version-probe-ok`. OrderBook must print
`orderbook-paired-ok 30000 2500000 10300000000 59998` and
`orderbook-production-warm-bytes=0`. Capture these commands, stdout/stderr and
zero exits together with image IDs and physical-host evidence.

Collect the new P6b evidence directory **and** the exact fixture directories
listed by its `fixtures.json`; include nested forced-reuse evidence, original
stack logs, prepared JDK records, source/manifest hashes, allocation/timing data,
disassembly and minimal-consumer output. Preserve failures too. Archive those
paths without changing payloads, then compute the archive's SHA-256. A result
requires review of matching hardware, all expected outcomes and code inspection,
not just a summary file. Hardware findings requiring production changes require
rebuilding affected candidates and refreshing affected ARM64 evidence.

Estonia now supplies these hardware checks for the frozen candidate. P6b and
release readiness remain incomplete until the maintainer's final numerical
performance review is accepted. This handoff does
not authorize publishing a release or broadening Java-version support.
