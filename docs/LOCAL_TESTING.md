# Local platform tests

Development uses individual tests or focused groups. Full compiler/native suites
are reserved for final release readiness or an explicit human request, as defined
in `AGENTS.md`. Ordinary pushes and pull requests do not trigger hosted builds.

## Run a selected test

On the Mac, list registered test names and run one by its exact displayed name:

```sh
./scripts/test.sh --list
./scripts/test.sh --test 'IronDocs pool example runs natively'
```

Repeat `--test` to select several tests. Unknown names fail; they never fall back
to the full suite. The script rebuilds the bootstrap compiler and test classes
before executing the selection, so total command time includes that preparation.

Java differential tests use the `java` and `javac` found on PATH. Compiling with
`--release 21` does not make a newer Java runtime use Java 21 library behavior.
The StringBuilder selection pins its version-sensitive insertion observations
to Java 21 and compares the remaining cases against the live runtime; see the
[StringBuilder review](STDLIB_STRINGBUILDER_REVIEW.md#verification-and-lessons).

For the `ironwood.bench` library, use
`./scripts/test.sh --test 'benchmark library reports and reclaims native results'`.
Its native `ironwood.testing` suites and allocation-failure probes are also
available through `./scripts/test-bench.sh`. The general standard-library runner
includes the two benchmark behavior suites.

For the OrderBook performance drivers, run `projects/OrderBook/compile.sh` and
`projects/OrderBook/link.sh`, then `projects/OrderBook/test.sh` with the repository
`bin/` on PATH. This runs four native `ironwood.testing` tests for workload
counts, pool reuse, allocation-free sample collection, and argument bounds,
plus six Java tests, small command-line checks for both implementations, and
byte-for-byte report comparisons. Run `projects/OrderBook/java/compile.sh` and
`projects/OrderBook/java/test.sh` for the Java checks independently.

Each test prints its start and result with millisecond timestamps and a counter:

```text
12:23:23.003 - 1/2 - RUN - IronDocs pool example runs natively
12:23:24.127 - 1/2 - ok - IronDocs pool example runs natively
```

The total is the number of tests selected for that run. `--list` still prints
only test names. IronDocs tests regenerate current source in temporary folders;
frozen release snapshots are compared against tagged source in release checks.

Native integration fixtures in `CompilerTests` run at `-O3` only. The compiler
continues to support every documented optimization level; the primary test suite
uses its single optimized configuration to keep ordinary verification practical.
The latest investigation of slow tests, including the scope of this integration
suite and the cases that inherently cross process or artifact boundaries, is in
[Test performance audit](TEST_PERFORMANCE.md).

Structural IR assertions must select the fixture's declared types, callable
owners, entry point, or dispatch entries before counting. A compilation also
contains bundled source from packages beyond `ironwood.lang`; excluding only
that package does not isolate application code. For tests using the default
package, compare the exact expected unqualified type names. Allocation tests
should check the relevant helper calls and actual allocation behavior, allowing
equivalent delegation. Pruning checks should pair an unused case with a used
case that verifies required metadata survives. See the
[eight-failure follow-up](TEST_PERFORMANCE.md#structural-test-follow-up-2026-09-06).

For related runtime scenarios, compile a selectable fixture once when the
closed-world context can be shared without weakening the assertion. Keep fresh
native processes for allocation budgets, initialization, and failure state.
Compilation is part of the coverage for source/class/archive and CLI tests;
those different input paths still need their own checks. Splitting a displayed
test name alone does not reduce the work.

## Launcher configuration checks

For launcher JVM configuration changes, run `python3 scripts/test-jvm-options.py`.
It exercises all three launchers with both bundled and system Java stand-ins,
including argument boundaries, literal text, comments, missing files, and errors.
The host-package and IDK smoke scripts additionally test real JVM configuration
from disposable extracted archives. These focused checks do not run the compiler
suite.

## Planned networking reachability checks

Milestones 1 and 2 implement blocking TCP and address/DNS APIs. Under accepted
[D156](DECISIONS.md#d156---separate-reachability-contract-tests-from-host-smoke-checks),
future `InetAddress.isReachable` contract and native fault-injection tests belong
in focused compiler/platform runs. Live ICMP/port-7 probes are separate opt-in
host smoke checks, outside default compiler, platform, package/IDK, and hosted
release pass/fail gates. Colima, container capabilities, routing/firewalls, and
the host configuration can affect results; Rosetta execution does not establish
network permissions or reachability. No runner privilege or network changes
are required by this policy.

Follow the plan's [reachability contract and verification](NETWORKING_MIGRATION_PLAN.md#reachability-contract-and-verification)
when that smoke check is implemented. Record the environment, observed result,
and unavailable or inconclusive coverage separately. A TCP refusal counts as
reachable, so `true` does not prove ICMP or a listening service. These limits do
not waive deterministic tests or excuse crashes, hangs, leaks, or contract
violations. No new smoke command or runner behavior is introduced by this plan.

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
SDK, never the mounted checkout's macOS SDK. No dependency is downloaded during
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


## Networking Milestone 1

`./scripts/test-stdlib.sh` includes native `ironwood.testing` suites for
networking Milestones 1, 2 and 3. They exercise public API behavior and ownership
using loopback sockets, local resolution and read-only interface queries. See
[the coverage summary](TESTING.md#run-the-repository-suites). They complement
the focused compiler and controlled native drivers below; live reachability
probes remain excluded from the default runner.

Use exact CompilerTests selections, never the unfiltered suite:

```console
./scripts/test.sh --test 'TCP facade preserves typed options and result ownership' --test 'TCP extensions preserve factory and constructor ownership effects' --test 'numeric TCP lifecycle and snapshots run at O3' --test 'injected and factory TCP delegation runs at O3'
```

The focused native driver is `python3 scripts/test-networking.py`. Its groups
are prepare, contracts, interop, metrics, operations, messages, failures,
inputs, benchmark and disassembly. Build once with `./scripts/build.sh`, then
run prepare and the affected groups. Prepare compiles only the named fixtures;
no group invokes the full compiler suite. The counting/fault interposer requires
macOS and Clang; interoperability uses Java 21 first in PATH (the contract group checks the runtime version), and
disassembly needs LLVM 23.
The driver records commands' output and report.json under ignored
`integration-tests/target/networking-m1/`. It uses loopback/ephemeral ports,
controlled faults and a 32-descriptor soft/hard limit for stress subprocesses.
Diagnostic counting and untraced fixed-workload benchmarks run separately.
See [the milestone evidence](STDLIB_N1_VERIFICATION.md) for exact expectations
and the additional focused ownership/initialization selections.


## Networking Milestone 2

Use Java 21 and LLVM 23. The new compiler selections cover typed resolver output,
copied-input and dependent-name ownership, copying constructor overloads, real
result reclamation and constructor/options/urgent behavior:

```console
./scripts/test.sh --test 'network resolver operations retain typed native results' --test 'network address results preserve copied inputs and dependent names' --test 'copying constructors preserve non-retaining effects through overloads' --test 'network address results reclaim owned graphs at O3' --test 'TCP constructors options and urgent data run at O3'
python3 scripts/test-networking-m2.py
```

Build before the driver. Select its `prepare`, `contracts`, `literals`, `resolver`,
`failures` and `allocations` groups as needed. Prepare also compiles and executes
the focused native long-hostname allocation test. The driver records Java 21
differentials, deterministic resolver/interposer output, generated LLVM and
allocation evidence under `integration-tests/target/networking-m2/`. It supports
macOS dyld and Linux LD_PRELOAD; constructor allocation injection additionally
uses the macOS-only M1 fixture. Resolver/result injection runs on all platforms.
Run platform jobs sequentially under the existing local platform workflow, with
isolated output/build directories. See [the M2 record](NETWORKING_M2_VERIFICATION.md)
for exact coverage and the retained M1 I/O, deadline and machine-code evidence.

## Networking Milestone 3

```console
./scripts/test.sh --test 'flat interface snapshots preserve owned element borrows' --test 'flat interface snapshot cleanup runs at O3' --test 'host networking preserves snapshot and scoped address ownership' --test 'host networking retains typed native operations' --test 'network enumerations enforce reference bounds' --test 'host networking deterministic native and public contracts'
```

The last selection runs `python3 scripts/test-networking-m3.py` with the
compiler's discovered LLVM home, so LLVM binaries need not be on PATH. Build
first when invoking the driver directly; set `IRONWOOD_LLVM_HOME` to the LLVM 23
installation unless its Clang and llvm-objdump tools are already on PATH.
The driver checks these tools before starting its fixtures and records their
paths in report.json. It covers deterministic native ICMP/TCP
contracts, synthetic OS interfaces, public argument differentials, source/class/
archive bounds and ownership, managed/native failure cleanup, native-call counts,
benchmarks and disassembly. It writes to `integration-tests/target/networking-m3/`.
Use the existing sequential platform orchestrator for focused Linux checks and
stop its VM when finished. Live reachability is never a pass/fail gate here.

The default `examples/hostnetworking` compile/link/run workflow reads host
metadata only. Its separate `probe.sh HOST TIMEOUT_MILLIS` is opt-in live smoke;
record environment/privileges and the observed result under D156. No test script
changes network privileges, firewalls or container capabilities.

## Networking Milestone 4

```console
./scripts/test.sh --test 'proxy configuration preserves copied ownership and API boundaries' --test 'proxy peeking retains typed native buffer operations' --test 'explicit proxy negotiation and cleanup contracts'
./scripts/test-stdlib.sh
```

The last compiler selection builds the actual proxy clients and runs
`python3 scripts/test-networking-m4.py` with discovered LLVM tools. It covers
local scripted SOCKS4/5 and HTTP CONNECT, copied credentials, target reporting,
fragmentation, malformed/truncated responses, no retry/direct fallback, shared
deadlines, allocation-failure cleanup, native faults, a Java differential,
archive linking, native-call benchmarks and O3 disassembly. No external service,
proxy environment discovery or live reachability probe is required.

For direct driver use, build first and set `IRONWOOD_LLVM_HOME` when needed.
`--case NAME` selects a wire scenario, `--skip-build` reuses prepared clients,
and `--evidence-only` selects native/allocation/benchmark/archive checks. Results
are written to `integration-tests/target/networking-m4/`. The platform runner
isolates Linux outputs as described above; preserve reports when running other
manual platform commands. The
[verification record](NETWORKING_M4_VERIFICATION.md) lists the focused Linux
selections and environment limits. Stop the local platform VM afterward.

The strict `examples/proxy` compile/link/run workflow starts local scripted
SOCKS5 and HTTP CONNECT peers and verifies both authenticated tunnels.

## Networking Milestone 5

Prepare the platform-local SDK using [TLS.md](TLS.md), then select:

```console
./scripts/test.sh --test 'TLS client preserves owned configuration and stream borrows' --test 'TLS dependency selection follows pruned typed operations' --test 'TLS local protocol policy and native cleanup contracts' --test 'TLS optional build and package dependency boundary'
./scripts/test.sh --test 'standard-library testing module reports deterministic native results'
```

For the Linux platform runner, `scripts/test-platforms.sh --setup` prepares both
SDKs in its cached images. Run `--setup` when image inputs change, then use
`--failed` to retry recorded failures or select the native TLS checks below:

```sh
./scripts/test-platforms.sh --setup
./scripts/test-platforms.sh --platform linux-arm64 --platform linux-x86_64 \
  --test 'standard-library testing module reports deterministic native results' \
  --test 'TLS local protocol policy and native cleanup contracts' \
  --test 'TLS optional build and package dependency boundary'
```

The two M5 drivers use local certificates, signed revocation URLs observed by
loopback listeners, authenticated proxy peers, injected native failures,
managed/native allocation sweeps, ticket/reconnection checks, O3 code inspection,
and timed/untimed record benchmarks. `scripts/test-networking-m5.py --case NAME`
selects a scenario; `managed-oom` and `native-evidence` select the two allocation
groups. `--skip-build` reuses prepared clients. The build driver checks source,
class and archive paths, pruned TLS without an SDK, diagnostics, static closure,
header/build cache invalidation and the platform baseline. LLVM tools come from
`IRONWOOD_LLVM_HOME` or the compiler's discovered toolchain, including objdump.
The build driver recreates its owned toolchain wrapper on every run, replacing
both stale and dangling symlinks without modifying the selected toolchain.

Outputs are under `integration-tests/target/networking-m5/` and
`networking-m5-build/`. The platform runner isolates these outputs as described
above; preserve reports when running other manual platform commands. The stdlib
runner includes M5 configuration/state/cleanup tests and requires a TLS SDK for
its final native link. It never prepares dependencies itself. Relocated host/IDK smoke
checks use `scripts/test-tls-package.py` and the shipped TLS example. No public
TLS host or live reachability probe is an acceptance gate.

### Networking M6 downloader

```sh
./scripts/test.sh \
  --test 'wget URL and response ownership contracts' \
  --test 'wget local HTTP HTTPS and streaming contracts'
```

`projects/wget/test.sh` runs the focused driver directly. Its optional
`--skip-build --group protocol|tls|files|allocation|cleanup` selects one group
after clients are built. Local peers verify framing, redirect limits, deadlines,
TLS identities, proxy credentials and binary output. Fixed reader/network
workloads measure managed/native allocations and native calls separately from
untraced timings. Cleanup tests exhaust managed allocation positions and inject
close failures. Output is in `integration-tests/target/networking-m6/`.
Relocated host/IDK smoke tests use the shipped compile/link/run scripts and both
class-directory and archive links, then audit TLS and wget dynamic dependencies
and Linux GLIBC versions. See [M6 evidence](NETWORKING_M6_VERIFICATION.md).
