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
First-time downloads and setup can take substantially longer than later runs.
The images are cached locally and their names change when the Dockerfile or
toolchain specification changes. There is no image upload or GitHub workflow.

macOS uses the checkout's existing Java and LLVM installation. For comparison
with the release environment, select the pinned release toolchain through
`JAVA_HOME`, `PATH`, and `IRONWOOD_LLVM_HOME` before invoking the script. These
local checks do not reproduce every detail of GitHub's macOS runner image.

The canonical checkout is mounted into the VM. Linux compiler build output is
kept under ignored `workspace/platform-tests/build/<platform>/` and mounted over
`compiler/build/` inside the container, preserving the Mac's compiler build.
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
