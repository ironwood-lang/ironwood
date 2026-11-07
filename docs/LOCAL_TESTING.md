<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Focused local testing

Run exact tests or focused subsystem drivers. Never run the unfiltered compiler
suite, --full platform runs, or reconstruct them through all groups without an
explicit release/full-suite request. Ordinary development is local. Use Java 21
and the pinned LLVM 23 toolchain. Compiler/native children use a 16 GiB
process-group RSS guard and bounded timeouts. Keep raw outputs under ignored
target/ or workspace/ paths.

## Compiler selections

List names without executing tests, then pass one or more exact names:

```sh
./scripts/test.sh --list
./scripts/test.sh --test 'GC7 removed forms retain migration errors and ordinary identifiers' \
  --test 'GC7 shared consumers preserve source class and archive parity'
```

The launcher rebuilds production/test classes. Unknown names fail instead of
falling back to a suite. Choose paired accepted/rejected cases for changed shared
analysis, including initialization, known wrong pools, native acquisition and
foreign confinement. General defer selections preserve syntax, invocation,
typed capture, exception ordering and source/class/archive behavior:

```sh
./scripts/test.sh --test 'deferred calls preserve explicit-block syntax and diagnostics' \
  --test 'deferred calls enforce invocation and checked-exception contracts' \
  --test 'deferred calls replay typed captures across independent exits'
```

The [GC7 disposition ledger](GC7_DISPOSITION.md) maps retired free-proof test
families to current replacements. They are retired selectors, not a testing
backlog or an alternate mode. Diagnostic primary/related spans remain tested.

## GC native selections

All drivers below require explicit --sdk, --llvm-home, --java-home and a new
--output directory. They guard each child at 16 GiB and 60 seconds. Select only
cases/levels relevant to the change; do not run all drivers as a substitute for
an unfiltered suite.

- test-gc-integrated.py: --case integrated, resources, pools, no_collect or oom,
  and explicit --level O0/O3. Covers GC5 exact roots and phase collection.
- test-gc7-consumers.py: --case core, pools, library or examples. Migrated shared
  graphs, partial construction, assignment failure order, deferred captures,
  explicit close, pooling and deliberate phase reclamation.
- test-gc-inputs.py: source/class/archive parity and stale SDK-input rejection.
- test-gc-layouts.py, test-gc-roots.py and test-gc-lifetimes.py: focused GC2-GC4
  descriptors, statepoints, runtime owners and failure controls.
- test-gc6-objects.py, test-gc6-callbacks.py, test-gc6-segments.py,
  test-gc6-carriers.py, test-gc6-images.py and test-gc6-metadata.py: current GC6
  anchors, registrations, borrowed views, JVM reentry, explicit JNI completion,
  isolated images and fail-closed metadata.
- test-gc-performance.py: bounded deterministic call/pool O3 controls and
  normalized assembly. This does not accept GC8 capacity/performance criteria.

GC SDK input hashes must match the checkout. After a verified input changes,
prepare a fresh SDK and qualify its identity instead of copying old payloads.
See [GC7 verification](GC7_IMPLEMENTATION.md) for exact executed selections and
remaining platform gates. The [GC9 checkpoint](GC9_IMPLEMENTATION.md) adds Linux
arm64 on matching hardware; arm64e remains unqualified. Keep the bridge hardware
evidence rules below when interpreting translated x86-64 results.

`test-package.sh ARCHIVE --gc9 OUTPUT` and `test-idk.sh ARCHIVE --gc9 OUTPUT`
select focused GC distribution checks without invoking compiler suites. The
default package smoke selections remain intact. The GC9 driver clears development
overrides, checks relocation and SDK payloads, links class/archive inputs and
loads real managed bridges. On Linux it also executes through the pinned glibc
2.17 loader/libraries on the current kernel, which is not an older-OS claim.
The Mach-O root driver adds `--case readonly-map`, `writable-map`,
`missing-boundaries` and `bad-boundaries`: actual store protection,
dyld/runtime refusal and stripped/malformed exact
native-boundary metadata. Native children retain the 60-second/16 GiB bounds;
archive orchestration needs its separately bounded packaging stage.

## Maintained consumer checks

Java bridge examples use generated Roots, RootHandle.value()/close(), and
ListenerRegistration. The example BridgeLifetime setup utility resolves the
image-specific generated support package. Source close methods remain separate
from both anchor and registration closure. Use each example's compile/link/test
scripts. Generics benchmarks support --quick. The current
scripts/java-bridge/measure-managed.py runner accepts one workload --case and
--quick for a bounded migration smoke; it preserves micro, shared-retention,
OrderBook pooling/batch/latency and listener workloads without accepting GC8.

For standard-library development choose individual generated suite mains.
PoolRetentionTests and CollectionRetentionTests include explicit phase checks;
ordinary library operations and critical loops never collect to repair counts.
The all-suites standard-library script is not a routine focused selection.

Networking drivers test controlled loopback/native-fault fixtures. Choose the
specific M1-M6 driver or exact CompilerTests selector required by the changed
protocol. File/socket/TLS cleanup and pool release remain explicit. Do not use
managed alias rejection as a native-resource test.

IDE grammar generation and Eclipse/LSP checks use the normal ide generators and
verification scripts. Ordinary free/destructor identifiers receive normal method
highlighting, symbols and completion; removed source/options produce migration
errors. Run generated-content consistency, diff, license and tracked-file checks
for affected content before committing.

## Prepare Linux locally with Rosetta

Use an Apple Silicon Mac with Python 3, Colima, Docker, and Rosetta installed:

```sh
./scripts/test-platforms.sh --setup
```

This starts a dedicated `ironwood-tests` Colima profile with six virtual CPUs and
8 GiB of memory. It uses Apple's virtualization framework for Linux ARM64 and
Rosetta for Linux x86-64. The existing default profile and active Docker context
are not changed. Setup and x86-64 test runs verify that Rosetta is active and
reject a competing QEMU registration before running tests.

Rosetta opens, in every translated process, the executable it translates and
then its runtime (`/mnt/lima-rosetta/rosetta` under Colima), both without
close-on-exec, so an x86-64 child inherits its parent's pair and adds its own.
The M4.3 process tests list a child's descriptors through the controlled helper
`integration-tests/runtime/process_helper.c`, whose `fds` mode leaves out a
descriptor naming that runtime and the one just before it; native hosts have no
such descriptors.

To switch an existing QEMU test environment to Rosetta, stop it first. Cached
images and compiler output are retained. Then prepare only the x86-64 image:

```sh
./scripts/test-platforms.sh --stop
./scripts/test-platforms.sh --setup --platform linux-x86_64
```

Linux test containers explicitly use `LANG=C.UTF-8` and `LC_ALL=C.UTF-8` so Java
can pass Unicode filenames, process arguments, and environment values correctly.
This setting is applied when starting a container; existing cached images work.

Setup downloads Ubuntu 24.04 images and creates both Linux toolchain images from
`packaging/idk-environment.yml`, including the release's pinned Java and LLVM.
It also runs `scripts/prepare-tls.py` inside each image to build the pinned
platform-specific SDK at `/opt/ironwood-tls`. Linux tests explicitly select that
SDK, never the mounted checkout's macOS SDK. Setup then runs
`scripts/prepare-java-bridge-support.py` with each image's Python to prepare the
[pinned Java Bridge native support SDK](JAVA_BRIDGE_NATIVE_SUPPORT.md) at
`workspace/java-bridge/support/<platform>/`, or verifies an existing directory
against the current pins; Linux test containers select it through
`IRONWOOD_BRIDGE_SUPPORT_HOME`, and a missing SDK stops a Linux run before any
test starts. Delete a stale support directory before rerunning `--setup`.
No dependency is downloaded during
compiler or test execution; rerun `--setup` when preparation inputs change.
First-time downloads and setup can take substantially longer than later runs.
The images are cached locally and their names change when the Dockerfile or
toolchain specification, TLS recipe, pins or license inputs change. There is no
image upload or GitHub workflow.

macOS uses the checkout's existing Java and LLVM installation. For comparison
with the release environment, select the pinned release toolchain through
`JAVA_HOME`, `PATH`, and `IRONWOOD_LLVM_HOME` before invoking the script. These
local checks do not reproduce every detail of GitHub's macOS runner image.

The canonical checkout is mounted into the VM. Linux compiler build output is
kept under ignored `workspace/platform-tests/build/<platform>/` and mounted over
`compiler/build/` inside the container, preserving the Mac's compiler build.
Native integration and stdlib test outputs similarly use
`workspace/platform-tests/integration-target/<platform>/` and
`workspace/platform-tests/stdlib-target/<platform>/`, mounted over
`integration-tests/target/` and `stdlib/test/target/`. These prevent stale binaries
and toolchain links from crossing architectures. Other example/project targets
remain shared, so platform runs are sequential.
No Git checkout or worktree is created. Run one orchestrator at a time and avoid
editing source during validation.

### Java Bridge validation hosts

The [Java Bridge P0 prerequisites](JAVA_BRIDGE_PLAN.md#p0-host-and-jdk-prerequisites-d205)
specify separate pinned Temurin preparation for these Linux images and macOS;
an arbitrary system JDK does not satisfy that matrix. Use the implemented
[preparation and qualification runners](../scripts/java-bridge/README.md).
Under D205/D208, Rosetta may supply P0-1 through P0-8 functional evidence when
labeled translated; P0-8 also includes compiler-only proofs. P0-9 inspects actual
target binaries and needs no hardware execution.
P0-10 stack probes and all nine P6 runtime cells require matching hardware;
Linux x86-64 therefore needs a physical x86-64 host, optionally through
same-architecture virtualization. Colima Linux ARM64 uses matching ARM64 hardware.
Under D213, run the ARM64 stack checks in P0 and defer real x86-64 hardware
execution to the end of P6b. Missing x86-64 hardware does not block implementation;
record those results as pending. Keep the permitted Rosetta functional/static
checks earlier; P1-P4 may also use labeled Rosetta functional evidence, including
Linux dependency/load checks. Prepare a focused manual/SSH runner and evidence
bundle for the final hardware handoff. All x86-64 hardware cases must pass before
P6b or release is complete; a known failure is not excused by this deferral.
These are bridge-specific evidence rules, separate from the networking smoke
policy above. No paid hardware, hosted development jobs or full suites are added.

P5 callback changes use the focused producer and native transport checks below.
The [P5 log](JAVA_BRIDGE_P5_PROGRESS.md) records additional proof controls,
failure injection, pinned-JDK replay and stack/performance evidence. Select only
the checks affected by a change; this is not permission for an unfiltered suite.

```sh
./scripts/test.sh \
  --test 'Java Bridge native carriers preserve catch replacement retained identity and allocation containment' \
  --test 'Java Bridge paired owner callbacks preserve slots aliases foreign guards and exception identity' \
  --test 'Java Bridge producer packages retained owner listeners with source class archive parity' \
  --test 'Java Bridge producer packages proved synchronous listeners with artifact parity'
```

P7b array changes use the six exact proof/producer/fault tests listed in the
[array evidence report](JAVA_BRIDGE_ARRAY_EVIDENCE.md#reproduction-and-evidence).
That report also records the pinned-JDK replays, matching payload identities,
allocation checks and standalone conversion benchmark. Fault-injected images
must remain separate from production performance payloads.

D241 critical-call changes use the three focused checks below. They cover closure
selection, exact declaration and adapter inventories, and a consumer that runs
every result carrier, failure delivery and each JNI fallback route. Run them with
each supported JDK selected through `JAVA_HOME` when the generated Java or the
linking code changes. The [critical-call report](JAVA_BRIDGE_CRITICAL_CALLS.md)
records the additional existing selections that were run with them.

```sh
./scripts/test.sh \
  --test 'Java Bridge critical calls admit only memory-only closures' \
  --test 'Java Bridge critical calls keep JNI declarations and exact adapter inventories' \
  --test 'Java Bridge critical calls preserve values failures and JNI fallback'
```

D245 version-policy changes use the checks below. The first two probe the
producer and loader predicates on any host. The others launch pinned Temurin
22-25 consumers from `workspace/java-bridge/jdks/`, so they need macOS ARM64 with
those launchers installed; on Java 24/25 they assert the exact JEP 472 warning
of a default launch, silence under the class-path, module-path and manifest
grants, and clean `--illegal-native-access=deny` failures. Run the three
critical-call checks above and the object producer check once more with
`JAVA_HOME` set to a Java 24 and a Java 25 JDK, so the runner's own JDK exercises
the producer and the consumer grants on those releases.

```sh
./scripts/test.sh \
  --test 'Java Bridge JDK tools preserve Java 21 APIs and reject unsupported versions' \
  --test 'Java Bridge generated loader validates metadata versions and canonical extraction' \
  --test 'Java Bridge object producer preserves final proofs packaged parity and pending capability refusal' \
  --test 'Java Bridge root producer preserves packaged input parity and consumer lifetime' \
  --test 'Java Bridge retaining producer preserves mixed lifetime parity and complete slot commits' \
  --test 'Java Bridge generated enum jars preserve cold conversion and initializer containment'
```

D243 loader extraction changes use the two checks below. The first exercises the
generated `Support` source with fixture bytes on any host: cache path identity,
write-free reuse, coexisting builds, the shared runtime file and every refusal.
The second builds real images and launches consumer JVMs against populated
caches, so it needs the native toolchain and a Java 21-25 `JAVA_HOME`.

```sh
./scripts/test.sh \
  --test 'Java Bridge generated loader validates metadata versions and canonical extraction' \
  --test 'Java Bridge permanent facades reject colliding generations before extraction'
```

D244 portable x86-64 tuning changes use the checks below. The first asserts the
triple selection on every host and, on an x86-64 host, disassembles a portable
`-O3` executable to check merged SSE2 stores without any instruction-set
extension. The other two link every target machine at `-O0` and `-O3` and keep
the raw `--emit-llvm` module target-neutral. Run them on both an x86-64 and an
ARM64 host when the tuning or `TargetMachine` changes, and rerun the OrderBook
throughput and latency benchmarks on the Linux x86-64 host.

```sh
./scripts/test.sh \
  --test 'portable x86-64 tuning merges adjacent stores with baseline SSE2' \
  --test 'native target layout agrees with configured Clang before optimization' \
  --test 'mixed-width native layouts survive class and archive links'
```

## Test platforms and retry failures

```sh
# List supported platform names.
./scripts/test-platforms.sh --list

# Run one test on all three platforms.
./scripts/test-platforms.sh --test 'IronDocs pool example runs natively'

# Select just one platform for a fix.
./scripts/test-platforms.sh --platform linux-arm64 --test 'IronDocs pool example runs natively'

# Only when ready for final release verification.
./scripts/test-platforms.sh --full

# Run the complete x86-64 suite only, for example after switching to Rosetta.
./scripts/test-platforms.sh --full --platform linux-x86_64

# After fixing failures, rerun only the named failures in the latest reports.
./scripts/test-platforms.sh --failed
```

`--list` prints all supported platform names without starting tools or running
tests. It requires only Python 3 and works without the VM or prior test reports,
even on a non-Mac host. It cannot be combined with another mode.

`--platform` accepts `macos-arm64`, `linux-arm64`, and `linux-x86_64`, and may be
repeated. Add `--dry-run` to preview commands without running tools or writing
output. With no mode, the script shows usage and runs nothing.

Platforms run sequentially to avoid contention and collisions in other ignored
example/project output. Each gets a timestamped log and a latest JSON report in
`workspace/platform-tests/`, including duration, selection, status, and failing
test names. A focused retry is recorded as focused verification, not as a new
full-suite pass. A failure before individual tests requires fixing setup and
selecting the intended tests again; `--failed` never substitutes a full-suite run.

After testing, the final output is a **FINAL PLATFORM SUMMARY** table with each
selected platform's result, passed/failed counts, and elapsed time (`HH:MM:SS`),
plus total test time. It identifies full, selected, or retried tests and labels
interrupted, incomplete, unrun, and skipped platforms explicitly. The total sums
platform runtimes and excludes VM setup and preflight checks. Setup and dry runs
do not print test results.

Reports are created when a platform starts and updated as failures occur. Ctrl+C,
terminal hangup, and termination requests stop the current test process and its
Linux container, preserve recorded failures, and stop further platforms from
starting. The report marks the run incomplete. `--failed` retries its recorded
failures only; tests that were never reached remain unverified. The VM stays
available until `--stop`.

Full testing still takes time on each platform. x86-64 code uses Rosetta
translation. Measure the actual workload rather than estimating it from the
Mac's suite duration. Switching the x86-64 translator alone does not require
rerunning macOS ARM64 or Linux ARM64 tests. Successful earlier tests are not
automatically rerun during a focused retry, nor are they
treated as proof about arbitrary later source changes.

This command covers `CompilerTests`, including its native integration tests. IDK
packaging and archive smoke tests remain the separate commands described in
[IDK](IDK.md) and [Releasing Ironwood](RELEASING.md). Run the compiler/native suite
locally before release. GitHub skips that suite and retains release builds,
license and IronDocs checks, and packaged-archive smoke tests on native machines.

The script prints a shutdown reminder after Linux testing. Stop the dedicated
VM when finished:

```sh
./scripts/test-platforms.sh --stop
```

This runs `colima stop --profile ironwood-tests`. It stops the shared Linux test
VM while preserving cached toolchain images and test results. Run `--setup`
again before the next Linux test session. Shutdown is manual so the VM remains
available for focused retries after a failure.

References: [Lima architecture emulation](https://lima-vm.io/docs/config/multi-arch/)
and [Colima configuration](https://colima.run/docs/configuration/).
