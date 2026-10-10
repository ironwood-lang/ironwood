<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge JDK 21-23 implementation and verification

Authorized scope: build and run the compiler and JNI bridge with JDK 21, 22 or
23; Java 21 language/API/class-file baseline; Java 24+ remained refused until
D245 admitted Java 24 and 25 on 2026-10-02.
Work stays on the existing `java-bridge` branch, with local commits only.

## Initial review

- Canonical root and both origin URLs verified. Initial working tree clean.
- Read AGENTS, contribution/licensing rules, bridge plan/usage and relevant
  version, pairing, packaging and performance decisions.
- Affected machinery: Java tool selection, build-time bridge tools, launchers,
  examples, assembly and host/IDK packaging. No ownership analysis, typed IR,
  JNI transport, native loading or warmed-call lowering changes planned.
- Preserve exact compiler/runtime generation, common assembly bytes, native
  build inputs (including JDK version/vendor/JNI hashes), content inventories,
  reproducibility and refusal of mismatches. Different producer tool versions
  are not assumed byte-identical. Matching host production remains required.
- Verification: build on all three pinned JDKs, source/class/archive producers,
  exact focused bridge tests and positive/negative pairing checks; nine JVM
  cells per target; arrays, views, generics, callbacks, exceptions, lifetimes,
  loading/packaging, basics and OrderBook; Java 21 class versions and Java 24
  refusal; shell selection regressions, licenses and diff checks.
- Available targets: macOS ARM64, running `ironwood-tests` Colima ARM64 Docker
  environment, physical x86-64 `ssh x86host` under `~/temp/java-bridge`.
  Existing evidence and installations will be preserved; no installs.

## Implementation and verification checkpoints

- Core implementation committed as `b85a6b46`: producer/assembly/compiler tools
  support JDK 21-23, with Java 21 compilation and explicit component diagnostics.
- Shared JDK selection, build/launcher/example/Maven/Gradle/package changes and
  documentation are implemented and verified below.
- macOS complete: all nine producer/consumer cells, eight artifact families per
  producer, ordinary and checked JNI, module loading, Java 21 class-file checks,
  source/class/archive workflows, companion equality and distribution copying.
  Four focused producer/tool/assembly/distribution regressions pass per JDK.
- Additional five exact safety/proof/OrderBook tests pass under JDK 23. Fourteen
  shell launcher tests pass. License audit and diff checks pass so far.
- Missing javac/Javadoc: six reduced-module JVM checks pass. Missing JNI header
  diagnostics pass on all three JDKs. Java 24 refuses three build operations and
  fifteen consumer launches with zero extraction files or native-access warnings.
- Linux ARM64 and physical x86-64 matrix runs completed in fresh source
  snapshots. Their input archive SHA-256 is
  `14f98b1a1711e209eaf3b8ccbfa2cf9707d39f1069d8fbfc8f3d323d3c2ff165`.
  Existing host files/evidence are untouched. The recorded original runner uses
  scripts/test.sh; the revised runner bounds its equivalent exact CompilerTests
  invocation to 1536 MiB and streams logs for future runs.
- Evidence root: `workspace/java-bridge/jdk-matrix`. Mac: `mac`; ARM64:
  `linux-arm64-work/evidence/jdk-matrix`; x86host:
  `~/temp/java-bridge/jdk-matrix-20260929/evidence/jdk-matrix`.
  Commands, selected environment, full JDK versions, results, bridge manifests
  and complete jar/compiler hashes are retained there.
- Offline Maven/Gradle: both producers on JDK 21/22/23 and all eighteen
  consumer workflow cells pass, using isolated copies of cached repositories.
- All nine compiler build/runtime combinations pass. First version-output
  harness assumed 0.5.4-beta rather than reading VERSION (0.5.5-beta); corrected
  harness evidence is preserved beside that failed assertion.
- Host packaging smoke passed until the final unchanged TLS stage required its
  SDK environment. That stage passed when rerun alone with the prepared SDK.
- Relocated IDK packaging exposed conda-pack's env-selected Python mismatch.
  scripts/package-idk.sh now invokes the bundled Python explicitly. The new
  isolated archive packages successfully. Relocation checks passed until the
  documented macOS 27 SDK/linker mismatch in an unchanged streaming example.
  Focused bridge checks with installed SDK 26.5 pass for bundled Azul 21.0.10+7
  and external Temurin 21/22/23, with twelve consumer cells and native AOT.
  Initial focused harness expected an obsolete ByteView output string; its
  corrected continuation passed without rebuilding already verified artifacts.
- Original ARM64 run was interrupted during expensive shared-filesystem archive
  writes. Partial evidence is preserved. A fresh container using VM-native
  storage and the bounded/streamed runner completed all three producers.
- macOS selected-JDK identity audit verifies version/vendor and JNI header
  hashes for all twenty-four host artifacts. Java 25 companion-producer refusal passes.
- Linux's historical producer parity fixture returns early on non-Mac hosts.
  The workflow runner independently produces source/class/archive artifacts on
  Linux; only active build-tool/assembly/distribution tests count there. The
  revised runner explicitly omits that Mac-only fixture on Linux.
- Commits so far: b85a6b46 (bridge tools), 56735168 (JDK selection/workflows),
  40f6e7c0 (packaging/bundled tools).
- All twenty-seven host producer/consumer cells pass. The ARM64 producer-23
  consumer-22 launch initially failed with an explicit extraction I/O error
  because the VM disk filled. All artifact hashes and failure evidence were
  copied out before removing only this task's container/temp fixtures. The
  failed and remaining cells pass in `arm-retry`, using unchanged JARs.
  The workflow runner now cleans each launch's private temporary directory.
- Final catalogs are local: `mac`, `arm-collected`, `x86-collected`. Linux
  archives include approximately 200 MiB of required source/notices each.
  Lossless long-window Zstandard transport reduced repeated evidence bytes;
  copied x86-64 and ARM64 artifacts match their original recorded SHA-256 hashes.
- [The durable identity catalog](JAVA_BRIDGE_JDK_IDENTITIES.json) records all seventy-two artifacts' exact jar,
  generation, API/program, compiler/runtime, native build/image and JDK/header
  identities. Selected producer JDK versions and JNI header hashes match.
- Documented callback shell commands built on JDK 23 and launched on all three
  consumer JVMs in `commands-demo`.
- A compiler built on JDK 23 assembles its paired host artifact while running on
  JDK 21, 22 or 23. Its exact compiler/runtime generation remains checked.
- All nine compiler build/runtime pairs also compile Ironwood successfully,
  beyond the version-output checks, in `producer-compiler-matrix`.
- Differing compiler-build generation is refused and existing output preserved
  in `mixed-jdk-rejection`; the task does not normalize producer differences.
- All twenty-four three-target assemblies pass, preserving every native resource
  and main distribution byte, with identical results after reversing input order.
  All twenty-seven combined producer/consumer cells pass on the three targets,
  including executable-JAR loading. The x86-64 transfer added macOS AppleDouble
  metadata; the replay helper now ignores these non-JAR sidecars. Actual jars
  match their original SHA-256. Failed helper evidence remains preserved.
- Local identity commit: 16bc5b8c. Final documentation and qualification are
  complete. No push or main integration is authorized.

## Final qualification, 2026-09-30

| Producer JDK | Java 21 consumer | Java 22 consumer | Java 23 consumer |
| --- | --- | --- | --- |
| 21 | Pass on all three targets | Pass on all three targets | Pass on all three targets |
| 22 | Pass on all three targets | Pass on all three targets | Pass on all three targets |
| 23 | Pass on all three targets | Pass on all three targets | Pass on all three targets |

The table passes independently for host and combined jars: 27 cells each.
Every cell compiles consumers with that JDK at `--release 21`, then runs ordinary
and checked JNI. Combined jars additionally pass module and executable-JAR
loading. Cases include primitives/Strings/exceptions, native objects and `free`,
basics callbacks, retained listeners, arrays, ByteViews, read-only and bounded
generics, and actual OrderBook correctness. All compiler, facade and companion
classes inspected have major version 65. No warmed-call native code changed.

- Exact producer/consumer pins: Eclipse Temurin HotSpot **21.0.12.1+1-LTS**,
  **22.0.2+9**, **23.0.2+7**; LLVM/Clang **23.1.0**. Full version output and
  selected JDK homes are in each host's `input.json` and `jdk-N.log`.
- macOS: Apple M5 ARM64, macOS 26.6.2 (25G83). Native payload deployment floor
  26.0; inspected image SDK 26.5. The manifest retains both reported SDK input
  and inspected-image metadata. LLVM `/opt/homebrew/opt/llvm@23`.
- Local Linux ARM64: matching ARM64 Colima virtualization, Docker context
  `colima-ironwood-tests`, image `ironwood-bridge-linux-arm64:05d5199baf46c922`,
  image ID `sha256:a03b0d3a764744079949a3adf5ecf6fe7ce82382ee35ca95360a38d453af8767`.
- Physical Linux x86-64: `x86host`, Intel Xeon E-2288G, kernel 4.15.0-188-generic,
  image `ironwood-bridge-linux-x86_64:1a18fe26577fb8c5`, image ID
  `sha256:dd4c1e82b2f999db86e75538f7d32adcaa364452f99a8f615eca693707c382a7`.
  Work/evidence remains under `~/temp/java-bridge`; nothing installed.
- Linux producers use `/opt/ironwood-toolchain`, JDK 21 in
  `/opt/ironwood-bridge-jdk/jdk-21.0.12.1+1`, and mounted 22/23 homes recorded
  in the identity catalog. Support manifest hashes: ARM64
  `69c7780bd9c9acc8b59d4248a4193b38b5fb075728950fb50ce2a39717ca9039`, x86-64
  `468c18db4b88ec54c95b39e7a0e039133ac2d51de2076b930d2889ca052e286e`.
- Thirty active exact producer/tools/assembly/distribution regressions pass,
  plus five focused safety/proof/OrderBook tests and fourteen launcher tests.
  Unsupported JDKs, missing components, mixed generations, incomplete artifacts
  and failure preservation pass their focused negatives. Java 24.0.2+12 refuses
  producer/companion/assembly and fifteen consumer launches before extraction;
  Java 25.0.4.1+1-LTS companion-producer refusal also passes.
- Offline Maven and Gradle producer/consumer workflows pass eighteen cells.
  Relocated IDK bridge checks add bundled Azul 21.0.10+7-LTS and twelve consumer
  cells. Host archive smoke and the isolated TLS continuation pass. All host and
  combined bridge distribution checks retain exact main artifact bytes.
- Final assembled evidence: `assembled`, `assembled-mac-21/22/23`,
  `assembled-arm-final`, `assembled-x86-final`. All 324 combined command records
  pass. `assembled-x86-transfer-sha256.txt` verifies all 27 transferred JARs.
  The durable identity catalog records 72 host jars, 24 combined jars and three
  exact ByteView companions, plus both result matrices and original runner hashes.
- Final diff/license checks, shell/Python syntax checks and exact documentation
  copy checks for both package scripts pass. The packaged progress log retains
  its matching identity catalog.

Representative commands, with JDK variables set to the recorded installed homes
and prepared native toolchain/support SDK selected:

```sh
python3 scripts/java-bridge/qualify-jdks.py --target macos-arm64 \
  --jdk "21=$JDK21" --jdk "22=$JDK22" --jdk "23=$JDK23" \
  --output workspace/java-bridge/jdk-matrix/new-run
JAVA_HOME="$JDK23" ./scripts/build.sh
JAVA_HOME="$JDK23" ./examples/java-bridge/basics/compile.sh
JAVA_HOME="$JDK23" ./examples/java-bridge/basics/link.sh
"$JDK21/bin/java" -Xcheck:jni \
  -cp examples/java-bridge/basics/target/ironwood-basics.jar:examples/java-bridge/basics/target/consumer-classes \
  org.ironwood.javabridge.basicsconsumer.Main
"$JDK23/bin/java" -jar "$COMPILER_JAR" --java-bridge-assemble \
  -o "$ASSEMBLED_JAR" "$MAC_HOST_JAR" "$ARM_HOST_JAR" "$X86_HOST_JAR"
"$JDK23/bin/java" -jar "$COMPILER_JAR" --java-bridge-distribution \
  --input "$ASSEMBLED_JAR" --group-id org.ironwood.jdkcheck \
  --artifact-id basics --version 0.0.0-local -d "$DISTRIBUTION_DIR"
python3 -B scripts/test-jvm-options.py
./scripts/check-licenses.sh
git diff --check
```

The basics JVM command also passed with JDK22 and JDK23. Every concrete compiler,
producer, test, assembler, packaging and consumer argument list is preserved as
`*.command.json` beside its output and exit records. Linux uses the same matrix
command with its native target and recorded mounted homes. Host source snapshots
preserve original input/runner hashes; runner refinements bound test heap, stream
logs and clean only per-launch temporary extraction files.

Historical validation limits from the first qualification (superseded by the
out-of-box repair below): generic IDK relocation smoke stopped at the macOS 27
SDK/linker mismatch in the unchanged streaming example. Focused bridge/native AOT
checks passed with the installed 26.5 SDK; no toolchain was installed or replaced.
Full Linux IDK archives were not repackaged in this task; native bridge production,
assembly, distribution and consumers passed on both Linux targets. Unfiltered
compiler suites, hosted builds, release/publishing and performance benchmarks were
not run. Safety/IR/runtime lowering and JNI valid-path code remain unchanged.

## SDK 27 end-user follow-up, 2026-09-30

A direct bridge-producer check confirms the distribution limitation. The isolated
relocated IDK, with JAVA_HOME unset (bundled JDK) and Xcode's SDK 27.0 explicitly
selected, fails to build the value example at native linking. The linker rejects
`arm64e.x1` in libSystem/libc++ text stubs; exit status is 1 and no artifact is
published. Command, environment and output are preserved in
`workspace/java-bridge/jdk-matrix/idk-sdk27-bridge-e69s9nma`.
This confirms a macOS IDK usability gap for end-users selecting SDK 27. The
previous SDK 26.5 bridge matrix remains valid, but selecting an older installed
SDK is a workaround rather than SDK 27 qualification. No compiler/toolchain
implementation changes had been made at that diagnostic checkpoint. The repair
below replaces the workaround with supported native-tool selection and packaging.

## Out-of-box IDK repair plan, 2026-09-30

- Keep the existing java-bridge branch and local-only commits; canonical root,
  origins and clean tree reverified. Preserve all previous evidence.
- macOS: select Apple's SDK and linker through the same developer environment;
  pass explicit SDK/linker flags while retaining LLVM 23 compilation. Record
  actual SDK and linker identities in native build inputs. Honor explicit SDK
  selection and reject missing components; do not fall back to SDK 26.5.
- Linux: validate and package the existing pinned bridge support closure, pins,
  required source/notices and checksum manifests. Default installed discovery
  must work without IRONWOOD_BRIDGE_SUPPORT_HOME. No runtime/ownership/IR changes.
- Consumers of changed machinery: ordinary native executables, shared bridge
  images, TLS links, package relocation, source/class/archive bridge production.
  Paired cases: SDK 27 and 26.5 succeed; invalid SDK/linker/support fail clearly;
  default and explicit JDK 21/22/23 work; Java 24+ and artifact mismatches reject.
- Focused checks: SDK selection regression, existing native target and bridge
  producer/tools/assembly/distribution tests, package/IDK smoke paths, relocated
  IDK bridge matrices on Mac ARM64, local Linux ARM64 and physical Linux x86-64.
  Revisit the selection if scope changes; no unfiltered suites or hosted jobs.

### Repair checkpoint

- Implemented `MacNativeTools`: one explicit Apple SDK/linker selection shared
  by adapter/runtime compilation, target discovery and final Clang linking;
  actual SDK settings/stubs and linker content/version enter native identity.
  LLVM 23 compilation/optimization, JNI and runtime/IR/safety code are unchanged.
- Linux IDK packaging now checks and copies the complete prepared support SDK,
  preserving source/notices/checksum manifests and adding the package inventory
  record. Source/host archives carry the pins/preparation helper. The existing
  release-only workflow prepares Linux support; no development jobs were added.
- Both Linux generic IDK archive smokes pass, including default bridge callbacks,
  ordinary O0/O3 builds, source/class/archive inputs, TLS/wget and glibc 2.17.
  macOS generic smoke passed the SDK 27 native builds and remaining pre-TLS
  stages; the failed TLS certificate fixtures were corrected to use a local
  OpenSSL config. Focused continuation passes all TLS/wget/static-closure checks.
- Initial installed-IDK matrix passes all 27 host producer/consumer cells with
  exact Temurin pins already recorded above. Bundled defaults add Azul
  21.0.10+7-LTS on Mac and OpenJDK 21.0.10-internal on Linux. Native tools are
  LLVM 23.1.0; PATH contains only IDK bin, /usr/bin and /bin. No overrides select
  LLVM/support/SDK. SDK 26.5 and CLT SDK 27.0 explicit checks also pass; invalid
  SDK overrides preserve existing artifacts. Java 24 producer/values/assembler
  and consumer refusal passes with the repaired compiler.
- Assembly correctly rejected the prototype Mac archive: AppleDouble files
  ending in .c/.h had entered its runtime inventory, unlike Linux. Archive
  writers now omit Apple metadata; fresh final archives and the Mac matrix are
  being checked before assembly. No identity check was relaxed. Initial failed
  harness/assembly logs remain preserved alongside successful retries.
- Focused SDK/native/producer/tools/assembly/distribution checks pass (six exact
  tests); ten support setup/check negative tests and the license audit pass.
  Evidence root: `workspace/java-bridge/idk-repair`; original matrix/evidence is
  unchanged. Host evidence remains under `~/temp/java-bridge/idk-repair-20260930`.
  No installation, publishing, push or unfiltered suite was performed.
- Pending: final portable archive inventories, refreshed Mac artifacts,
  three-target assembly/replay, host package smoke, durable artifact catalog,
  final diff checks and focused local commits.

### Matched archive checkpoint

- Three fresh IDKs have identical compiler content inventory
  `0857893f34baf96ad018e669ed48cfe13f373dda6613d21e9ac321c50e79a8bd`
  and runtime inventory
  `9327fdc20bbdf5bf310a77a071a68ee2d536a644c1987ec3fb692641693036e0`.
  All 27 fresh host producer/consumer cells pass, with all eight families per
  producer. Source snapshot SHA-256:
  `c19e6cd81f062d56aab908833638894815ab9a33f2e338bd80d8e32e8705f084`.
- Archive inventories pass: complete Linux support sources/notices/pins,
  matching compiler entries, all Java class files at version 65, and no
  AppleDouble/xattr entries. Added the six previously omitted Maven/Gradle
  project files to both archive writers and smoke requirements. Exact copy
  checks pass for host and all IDK archives; eighteen offline build-tool
  producer/consumer cells pass against the repaired Mac IDK with SDK 27.
- Mac host archive smoke passes native and pre-TLS stages. Xcode's older Python
  SSL peer reported NO_SHARED_CIPHER; focused continuation using the existing
  pinned bundled Python passes TLS/wget/static closure/pruning. This is a test
  peer dependency, not an Ironwood/JNI native failure. No tool was installed.
- Strict assembly also caught unmatched provenance text between early Linux
  and fresh Mac snapshots. Fresh Linux artifacts were regenerated from the
  same snapshot. Checks were retained unchanged; successful JDK 21 assemblies
  preserve native bytes, reverse-order jar hashes and distribution main bytes.
  Their three-JVM Mac/ARM replay passes; other assemblies/replays are underway.
- Local implementation commits: `fe4dff83` (Apple tool selection), `941dbca8`
  (Linux support/portable IDK workflows), `c840bf76` (shipped build-tool files).
  Remaining: finish combined replay/catalog, final diff/license checks and
  commit the verification record. All original and failed-attempt evidence is
  retained in `workspace/java-bridge/idk-repair` and the task's x86host directory.

### Completed repair qualification

The SDK 27 and Linux IDK gaps above are closed. The
[IDK identity catalog](JAVA_BRIDGE_IDK_IDENTITIES.json) records exact JDK pins,
three archive hashes, compiler/runtime fingerprints, 72 host artifacts, 24
three-target artifacts, companions and both complete compatibility matrices.

| Producer JDK | Java 21 | Java 22 | Java 23 |
| --- | --- | --- | --- |
| 21.0.12.1+1-LTS | PASS | PASS | PASS |
| 22.0.2+9 | PASS | PASS | PASS |
| 23.0.2+7 | PASS | PASS | PASS |

Every cell passes on Mac ARM64 with the default SDK 27.0, local Linux ARM64 and
physical Linux x86-64, for host jars and assembled jars. Eight families cover
primitives/Strings, native owners/reclamation, callbacks/listeners, arrays,
ByteViews, both generic subsets, exception transport and OrderBook. Consumers
compile at release 21 and run with ordinary and checked JNI; combined replay
also checks module-path and executable-jar loading. All 324 combined command
records pass. All 27 transferred combined jars retain their exact hashes.

All 24 assemblies preserve native resource bytes, reverse-order output hashes
and distribution main bytes. All generated/compiler class files checked are
version 65. SDK 26.5 and CLT SDK 27.0 explicit checks pass, including actual SDK
input/image identity equality. Missing/empty SDK selections preserve existing
artifacts; missing SDK/linker components and support corruption/missing components
reject. Java 24.0.2+12 producer/values/assembly and consumer refusal is retained.
Six exact compiler regressions, ten support negatives, eighteen offline
Maven/Gradle consumer cells, archive inventories, diff checks and license audits
pass. Compiler builds use the installed 21, 22 and 23 toolchains; JDK 22's final
source build is recorded as `compiler-jdk22-build.log`.

IDK native matrices use LLVM 23.1.0 and installed discovery without LLVM/support
overrides or developer/SDK overrides. Bundled-default checks additionally pass
with Azul 21.0.10+7-LTS on Mac and OpenJDK 21.0.10-internal on both Linux targets.
Mac uses Xcode's Apple linker `ld-27037.1`; exact linker and SDK settings/stub
hashes are in every Mac native identity. Both Linux IDK generic relocation smokes
pass. Mac IDK and host smokes pass their pre-TLS stages and focused corrected TLS
continuations with the bundled Python. Passing stages were not rerun unfiltered;
the original TLS fixture failures and all harness/identity rejection attempts
remain in the evidence. The source/host archive also contains the six exact
build-tool files. Candidate archives were refreshed only for these example
files after native qualification; compiler/runtime bytes remain unchanged.

Reproduction uses existing JDKs and an extracted IDK; no setup downloads occur:

```sh
python3 -B scripts/java-bridge/qualify-idk.py --idk "$IDK" \
  --jdk "21=$JDK21" --jdk "22=$JDK22" --jdk "23=$JDK23" \
  --output workspace/java-bridge/fresh-idk-run
```

Exact producer/consumer argv, selected environment, logs, exits and hashes are
preserved under `workspace/java-bridge/idk-repair/{mac-final-matrix,arm-final-collected,x86-final-collected}`.
Assembly commands are in `assembled-matched`; its runner checks reversed inputs,
all native bytes and all distribution main hashes. `combined-mac`, `combined-arm`
and `combined-x86` retain merged replay evidence and their original per-producer
records. Build-tool commands are in `build-tool-idk`. Archive/packaging commands
and inventory scripts remain beside their logs. x86host work remains exclusively
under `~/temp/java-bridge/idk-repair-20260930`; original host evidence is preserved.

Scope limits remain the three documented native targets, glibc 2.17 or newer,
and Java 21/22/23. Mac native production still requires installed Apple development
tools, without an SDK downgrade. Unfiltered suites, hosted runs, publishing,
pushes and new performance measurements were not run. No IR, ownership/runtime
or steady-state JNI lowering changed, and nothing was installed.

The final Linux archive refresh runs on Linux's case-sensitive filesystem.
Its full streaming comparison checks every original entry's filename, type,
link destination, mode and file SHA-256. Only the intended example README changes;
six build files and their four directories are added. Complete support manifests
verify again. An earlier Mac-based evidence refresh merged case-sensitive Linux
headers; those rejected candidates remain separate and are excluded from the
final catalog. Original native-qualified archives/artifacts were unaffected.
