<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7f combined JNI qualification

Use the canonical `java-bridge` sources or a verified frozen validation snapshot,
prepared compiler JAR/classes/test-classes, Temurin 21.0.12.1+1 in JAVA_HOME,
LLVM 23.1.0 on PATH, pinned target JDKs 22.0.2+9 and 23.0.2+7, and the existing
Linux native support SDK. No stage installs dependencies or publishes artifacts.
Use a new output directory for each stage. All commands run from the checkout
root. x86host work and outputs must stay under `~/temp/java-bridge`.

```sh
python3 scripts/java-bridge/p7/qualify.py produce \
  --target linux-x86_64 --output evidence/host
python3 scripts/java-bridge/p7/qualify.py tests \
  --target linux-x86_64 --jdks /jdks --output evidence/tests
python3 scripts/java-bridge/p7/qualify.py measure \
  --target linux-x86_64 --host evidence/host --cpu 1 --output evidence/measurements
```

`tests.json` is the explicit 18-test selection. The tests cover proofs,
source/class/archive producers, lifetime/failure cases and packaging negatives.
The runner preserves each generated child's assertions and environment settings
when replaying it on Java 22/23. Retry only failing tests using one or more
`--test 'EXACT NAME'` arguments and a new evidence directory. Do not run an
unfiltered compiler suite. Existing test fixtures may clean their own generated
scratch files; they must not modify earlier evidence or host files.

`produce` creates five independent native libraries from the existing array,
byte-view, generic and retained-listener examples, plus the shared ByteView
Java dependency. The ordinary Java consumer uses them together, including
callback reentry and failure cleanup. No example implementation is changed.
`measure` reuses the four existing array/view/generic benchmark runners; record
listener three-scenario measurements with `scripts/java-bridge/measure-listeners.py`
separately using the same pinned compiler and sources, with
`--bridge-jar evidence/host/listeners.jar`. Both paths reuse the qualified host
JARs; assembly verifies their native images are unchanged in the combined JARs.
This stage does not
measure tail latency or modify official OrderBook benchmarks.

After all three `produce` stages, copy their outputs without changing their
bytes to one host and assemble/package them there:

```sh
python3 scripts/java-bridge/p7/qualify.py assemble \
  --host evidence/macos-arm64/host --host evidence/linux-arm64/host \
  --host evidence/linux-x86_64/host --output evidence/combined
```

Assembly must preserve every native image, match common identities, and produce
identical bytes when input order is reversed. Distribution creates local main,
sources and Javadoc JARs, POMs, notices and inventories. Copy that exact combined
directory to each target, then run:

```sh
python3 scripts/java-bridge/p7/qualify.py launch \
  --target linux-x86_64 --jdks /jdks --candidate evidence/combined \
  --output evidence/launch
```

Use the corresponding target name and prepared JDK parent for ARM64/Mac. Each
launch stage requires 18 clean successful launches: Java 21/22/23, class path,
module path and executable JAR, each with and without `-Xcheck:jni`. No
native-access flags are used on those releases. Verify Java 24/25 admission
separately on the Mac with the pinned launchers (D245). Minimal-JVM container
smoke launches and signature/native dependency inspections supplement this
prepared-toolchain matrix.

On the Mac, audit every combined native image and check the pinned Java 24/25
launchers:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/java-bridge/p7/audit.py \
  --candidate evidence/combined --java24 /path/to/temurin-24/Contents/Home \
  --java25 /path/to/temurin-25/Contents/Home --output evidence/payload-audit
```

The audit records signatures, ELF dependencies/baselines, optimized disassembly,
Java 21 class-file versions and, per supplied launcher, ten isolated admission
launches under `--enable-native-access=ALL-UNNAMED` (plain and checked JNI,
which must extract the image and run without the JEP 472 warning) plus five
`--illegal-native-access=deny` launches, which must fail in the facade
initializer before any native use.

Each stage records commands, output, exits, compiler/runner/consumer identities
and an output hash catalog. Exit 0 with `exit.txt` containing 0 means that stage
passed, not that P7f or numerical review is complete. Keep partial failure logs
and corrected-run evidence. The [durable report](../../../docs/JAVA_BRIDGE_P7_QUALIFICATION.md)
tracks the complete selection, payload identities, pending checks and outcome.
