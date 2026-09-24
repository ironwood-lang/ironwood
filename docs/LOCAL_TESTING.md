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

## Compare explanation changes against their base

For M1a and later checkpoints, run the off/off comparison harness with the full
SHA captured before the change. Use Java 21 and the pinned LLVM toolchain. For
example, when the change has one commit, its parent is the base:

```sh
BASE_REVISION=$(git rev-parse HEAD^)
python3 scripts/compare-explain-rejected-free.py \
  --base "$BASE_REVISION" \
  --fixture scripts/fixtures/explain-rejected-free/accepted.json \
  --fixture scripts/fixtures/explain-rejected-free/rejected.json
python3 scripts/test-compare-explain-rejected-free.py
```

Choose the actual pre-change SHA for a multi-commit checkpoint. The harness
exports and builds that revision independently, then builds the current checkout.
It verifies each jar's actual standard-library type paths before compiling,
compares expected rejection with accepted class/archive/LLVM/native outputs,
and preserves raw logs and `report.json` in the printed scratch directory. It
uses no explanation flag, including against a base that predates the option.
The small accepted fixture exits zero; MixedSlots has two rejected frees and
emits no artifacts. The Python test injects tool failures, primary changes,
and artifact differences; the integration run checks conflicting inherited
library home and working directory, plus a missing archive with class fallback.

For M1e's public parser, source/link transport, and output contract, run:

```sh
./scripts/test.sh --test 'explain-rejected-free CLI parses and transports the invocation option'
```

This uses `Main.run` with separate streams and checks both help aliases,
valued and misspelled options, duplicate bare flags, artifact absence on misuse
or rejection, source and native-link output, a class-path library rejection,
a rejected link from independently safe earlier-version class builds, and the
limited-analysis note after skipped refinement. It compares the
complete usage text and selected enabled/disabled output. Use the adjacent
off/off harness for selected class, archive, LLVM, and native parity against
the pre-change base; the CLI test compares LLVM with a fixed output path because
separate native links can contain nondeterministic linker bytes.

For the M1c nullable observer and local explanation readiness seam, run:

```sh
./scripts/test.sh --test 'explanation observer records completed and skipped refinement'
./scripts/test.sh --test 'explanation readiness gives local boundaries without changing primaries'
./scripts/test.sh --test 'explanation readiness and exclusions preserve eligible note boundaries'
./scripts/test.sh --test 'explanation eligibility covers deferred destructor loop and owned elements'
./scripts/test.sh --test 'explanation notes retain dependency source class and archive identities'
./scripts/test.sh --test 'explanation notes retain bundled Writer source and final readiness'
```

These check actual pipeline construction, analyzer instances and inner-round
callbacks, completed and skipped refinement, and local enabled-note boundaries.
Observed and null-observer runs keep identical primaries and accepted LLVM.
The local note test also compares disabled/enabled primaries across all
`--unfreed` modes, including `@SuppressUnfreed`, and checks the exact
limited-analysis note after a skip. The additional readiness selection covers
a later body error, skipped deferred/destructor/loop/owned-element checks,
parser/name/type errors, pending writes, use after free, and wrong-pool transfer.
The final selection checks deferred registration, destructor fields, both loop
diagnostics, and late owned-element validation, including excluded type guards.
The source-scope selections retain dependency source, class, archive, and
bundled Writer identities while checking final lowering and note placement.
The observer selection also checks selected proof projections and all four
user callable kinds. The dependency and bundled selections check the exact
limited-analysis boundary in those sources when refinement is skipped.
Detailed evidence remains pending.

For M1d's first nullable collector and snapshot-storage step, run:

```sh
./scripts/test.sh --test 'rejected-free evidence snapshots retain identity and enforce storage limits'
./scripts/test.sh --test 'explanation observer records completed and skipped refinement'
```

The first selection checks equal-proof/different-evidence snapshots, restore,
merge, missing-snapshot fallback, local snapshot limits, the invocation stop,
and charge retirement. The observer selection checks actual collector presence
only in enabled final lowering after completed refinement, allocation-origin
events, disabled shared-empty saves, and unchanged accepted LLVM. This step
does not yet emit source-origin detail in notes.

For M1d's local binding source and join step, run:

```sh
./scripts/test.sh --test 'rejected-free local bindings retain current source and invalidate reassignment'
./scripts/test.sh --test 'rejected-free evidence snapshots retain identity and enforce storage limits'
```

The first selection covers declaration, statement assignment, assignment
expression, reassignment away, and normal and exceptional joins with a common
or differing predecessor binding. It also checks the full section 5.1
rendered primary and two-note golden. Differing source sites give the
unsupported-detail boundary. The second selection checks binding snapshot
identity, replacement, join invalidation, and charge retirement.

For M1d's selected-reason identity and earlier-free path step, run:

```sh
./scripts/test.sh --test 'rejected free attributes only a current earlier reclamation path'
./scripts/test.sh --test 'rejected-free evidence snapshots retain identity and enforce storage limits'
./scripts/test.sh --test 'rejected free preserves escape and uncertainty reason selection'
```

The first selection checks a unique previous free, a returned predecessor,
two reaching branch frees, and a fresh replacement of the local. The storage
selection checks same-text reason replacement and event identity across
snapshots. Actual reason-source notes start in M2.

For M1d's forced pipeline storage limits, run:

```sh
./scripts/test.sh --test 'rejected-free evidence limits preserve pipeline safety and truthful fallback'
```

This selection applies package-private test limits through the real parser
and semantic pipeline. It forces function, snapshot, and invocation limits,
compares rejection primaries and accepted LLVM with option off, checks
unlocated fallback notes, and confirms that disabled analysis has no collector.

For deterministic selection among competing `free` blockers, run:

```sh
./scripts/test.sh --test 'safe free selects stable blockers across fresh compiler processes'
```

This checks mixed container/wrapper owners, reversed creation and retention
orders, and multiple array slots before and after ownership snapshot restoration.
It pairs rejections with accepted borrower cleanup under every unfreed mode and
compares complete diagnostics across eight fresh compiler JVMs. Only diagnostic
selection is changed: the earliest registered retaining allocation is selected,
or the lowest matching array index with allocation order breaking ties.

For the separate creation-array second-pass selection, run:

```sh
./scripts/test.sh --test 'creation-array competing second-pass diagnostics follow first store'
```

This pins the first recorded object's reason when two objects fail different
second-pass checks. It varies creation, recording, and invalid-use order,
preserves within-object and earlier-pass precedence, pairs an accepted control,
and repeats the competing cases in fresh compiler JVMs. The selected reason
changes only where the old `HashMap` iteration varied.

For selection between escape and uncertainty reasons, run:

```sh
./scripts/test.sh --test 'rejected free preserves escape and uncertainty reason selection'
```

This checks the latest accepted escape, escape/uncertainty ordering, and the
first accepted uncertainty. Removing the selected blocker exposes remaining
blockers; removing all of them accepts the controls. It tests current messages
and primary spans. M2a also checks first-note locations for direct instance and
static field stores, inexact and known array stores, a conditional reference,
and one incoming array store. Replacing a known slot with null accepts a free.
A conflicting join still yields an unlocated boundary.
Use `rejected-free evidence snapshots retain identity and enforce storage limits`
for the array-site snapshot and forced-cap accounting.

For M2a's accepted-update guards and borrowed-owner merges, run:

```sh
./scripts/test.sh --test 'rejected free keeps selected event sites across updates and restores'
./scripts/test.sh --test 'rejected free keeps borrowed owner uncertainty on its accepted merge path'
```

The first selection checks an accepted escape after uncertainty, ignored
uncertainty after escape, ignored uncertainty after earlier uncertainty,
ignored escape after freed and maybe-freed states, and distinct restored branch
sites. The second pairs two-owner and mixed borrowed/unborrowed rejections with
same-owner and nullable accepted controls. It checks that a catch predecessor
does not inherit the normal path's owner conflict. Unavailable owner or join
locations remain explicit boundaries until M2c and M3a.

For ownership facts merged at a branch join, run:

```sh
./scripts/test.sh --test 'rejected free distinguishes incoming branch facts without changing join reasons'
```

This checks two different field escapes, one escaping branch, a field versus a
retaining call, and equal escape reasons from different source stores. Controls
remove publication or exit the publishing branch before the join. It preserves
current primary diagnostics; path-labeled explanation notes are still planned.

For ownership analysis after earlier errors, run:

```sh
./scripts/test.sh --test 'safe free distinguishes earlier errors from refined dispatch'
```

This records the current conservative rejection after a missing `@Override`,
acceptance after adding the annotation, rejection of a genuinely retaining
implementation, and preserved dispatch refinement after an unrelated body error.
It is a baseline for the planned explanation gate, not a test of implemented
explanation notes. Removing secondary diagnostics remains a separate change.

For the diagnostic baseline of duplicated cleanup, run:

```sh
./scripts/test.sh --test 'rejected cleanup frees preserve per-exit diagnostic multiplicity'
```

This checks rejected `defer free` and `finally` cleanup across normal completion,
return, and exceptional unwinding, including one-exit failures and safe controls.
It preserves current primary messages, counts, and locations; exit notes remain
part of the planned `--explain-rejected-free` feature.

For the catch-analysis route with no recorded incoming exception edge, run:

```sh
./scripts/test.sh --test 'predecessor-free catch preserves direct and cleanup primaries'
```

This pins the static-publication rejection at the finally free and at a direct
free inside the catch, with nearby accepted controls. It checks the existing
primaries and artifact suppression; the catch qualifier is M3c work.

For both loop-back-edge primaries and nearby safe controls, run:

```sh
./scripts/test.sh --test 'loop back-edge rejections preserve both primary diagnostics'
```

This pins `LoopDemo`'s carried-local error at 6:9 and its reclamation error at
7:13, including exact messages, order, severity, and source. It accepts an
immediate break and a fresh allocation in each iteration. The fixture uses
`--unfreed=off`; loop explanation notes remain M3 work. The complete named
loop/destructor selections and expected outcomes are in
[the explanation plan](EXPLAIN_REJECTED_FREE.md#82-existing-regression-selections).

For deferred-call capture versus deferred-free binding, run:

```sh
./scripts/test.sh --test 'deferred calls capture values while deferred free binds locals'
```

This checks acceptance of freeing a replacement allocation after a deferred-call
capture, rejection of freeing the captured allocation through an alias,
pending-free binding-write rejection, and recognition of a pending deferred
free through another alias. It checks current diagnostics; explanation notes
remain planned. Fixtures use `--unfreed=off` to isolate mandatory safety checks.

For earlier-free and field-proof evidence boundaries, run:

```sh
./scripts/test.sh --test 'rejected free preserves branch reclamation and field proof boundaries'
```

This preserves the primary diagnostics for alternative branch frees, a freed
path that returns, unproved parameter-backed fields, and sibling-field sharing.
It includes destructor variants and accepted controls with the repeated free
removed or fresh unshared storage correctly detached. Related notes remain
planned; the test does not claim to verify explanation output.

For call-summary evidence baselines, run:

```sh
./scripts/test.sh --test 'rejected free preserves call chains cycles and final borrow refinement'
```

This checks a retaining call chain, retaining and non-retaining recursive cycles,
and a temporary constructor borrower accepted after refinement. An unrelated
missing `@Override` preserves today's two secondary cleanup rejections; fixing
it accepts, while actual helper publication remains rejected. These are current
diagnostic baselines, not tests of the planned witness chains or option.

For M2b's local call and missing-origin notes, run:

```sh
./scripts/test.sh --test 'rejected free call sites and missing identities use final local evidence'
```

This compares option-off primaries with option-on receiver, argument, and
constructor sites; it keeps multiline operand spans, selects the later
retaining call, and checks fresh and non-retaining controls. Parameter and
published-result failures name the missing origin without a callee chain.

For dependency reclamation diagnostics during compilation and linking, run:

```sh
./scripts/test.sh --test 'rejected free in dependencies preserves compile and link source locations'
```

This compiles a valid library independently, then rejects its free when compiled
with a retaining application override through source paths, class directories,
and archives. It verifies the reconstructed primary source paths and absent
failure outputs. A non-retaining override compiles, links, and runs in all three
artifact workflows. Failing links use valid application classes built against
an earlier compatible library implementation. Linking accepts class inputs only;
cross-file explanation notes remain planned. This test requires the native toolchain.
Its isolated `integration-tests/target/free-dependency-*` directory is deleted
after each run, including failed runs.

For the ownership contracts behind rejected-free explanations, run:

```sh
./scripts/test.sh --test 'rejected free respects helper pool wrapper and dispatch contracts'
```

This preserves iterator/pool dependent-borrow errors, wrapper lifetime and cleanup
ordering, and executable versus library dispatch outcomes. Controls cover pool
return, owner destruction, a close that leaves retention intact, and another live
wrapper. It checks existing primaries and acceptance; owner names, remedies, and
dispatch-target notes remain proposed behavior.

For a user override that prevents reclamation inside the bundled standard library:

```sh
./scripts/test.sh --test 'rejected free in bundled Writer follows retaining user overrides'
```

This checks the bundled `Writer` destructor rejection and its source location
under every `--unfreed` mode with no entry point, an empty main, and a main that
uses only `StringWriter`. A non-retaining override accepts in all three cases;
the StringWriter main without the retaining subclass also accepts. An entry
point alone does not remove conservative targets in uncalled library bodies.
Explanation notes and collector guards remain planned; missing-free source
filtering must not exclude the bundled rejection from that feature.

For compiler text compatibility with Eclipse markers, use Java 21 to run the
standalone parser verifier after building the compiler, without building Eclipse
or the language server:

```sh
mkdir -p ide/eclipse/target/parser-only/classes
javac --release 21 -d ide/eclipse/target/parser-only/classes \
  ide/eclipse/plugin/src/ironwood/ide/eclipse/CompilerOutputParser.java
java -cp ide/eclipse/target/parser-only/classes:compiler/build/classes \
  ide/eclipse/tools/VerifyCompilerOutput.java \
  "$PWD/bin/ironwoodc" "$PWD/ide/eclipse/target/parser-only/work"
```

It checks real current compiler errors, proposed note fixtures, and actual
`DiagnosticFormatter` note blocks under LF/CRLF,
including cross-file and unlocated notes after a located primary, and adjacent
unlocated errors without notes. Messages, locations, and diagnostic counts must
survive unchanged. The parser remains primary-only; this does not enable the
option in Eclipse.

For the focused shared diagnostic API and full-output renderer check, run:

```sh
./scripts/test.sh --test 'diagnostic formatting includes location and source' \
  --test 'structured diagnostic notes preserve primary and related blocks'
```

For native target selection and layout, run:

```sh
./scripts/test.sh --test 'native target layout agrees with configured Clang before optimization' --test 'mixed-width native layouts survive class and archive links'
```

These checks execute LLVM layout probes against configured Clang's C layout,
then exercise mixed-width heap/enum fields, inheritance, arrays and Strings from
class and archive inputs at O0/O3 with default/native CPU selection. They also
check target conflicts, macOS deployment selection and mandatory double-free
rejection. Run them on each supported native host when validating that host;
cross-target IR inspection does not establish native execution correctness.

For field aliasing, final-value observations and mandatory reclamation safety, run:

```sh
./scripts/test.sh --test 'field aliases preserve mandatory safety' --test 'field aliases and final observations survive optimized artifact links'
```

These checks cover same-object versus different-object aliases, inherited and
shadowed fields, primitive and reference generic storage, Object/array aliases,
rejected array covariance, constructor and recursive-static default observations,
enum fields, mutable contents through final references, and allocation lifetime
boundaries. Class/archive links execute at O3/native. Safe cleanup and nearby
live-alias/double-free cases are checked under every unfreed mode. These are
behavioral regressions retained after rejecting the Stage 2 alias experiment;
they do not require its removed metadata implementation. The Stage 2 report
records the historical pool, initialization and runtime checks as well.

For retained enum argument specialization, run:

```sh
./scripts/test.sh --test 'enum argument specialization preserves identities and bounded fallbacks' --test 'enum argument specialization preserves native behavior and artifacts'
```

These checks cover constant identities and dynamic fallbacks, mutable enum
contents, recursive exclusions, clone bounds, mandatory cleanup safety in all
unfreed modes, class/archive links and exact O0/O3 source traces.

For selective inlining and link controls in the working compiler, run:

```sh
./scripts/test.sh --test 'selective inlining bounds loop candidates and preserves fallbacks' --test 'selective inlining preserves native checks cleanup and traces' --test 'inlining link controls validate budgets and preserve enum specialization'
```

These checks cover eligible loops and structural exclusions, mandatory cleanup
safety, O0/O3 behavior and exact traces with 3B on/off. They capture the budget
passed to LLVM and execute 3A with 3B off at default, zero and 2000 budgets.
The Stage 3 report lists the additional affected-consumer checks.

For single-comparison array bounds and unread primitive-field stores, run:

```sh
./scripts/test.sh --test 'unsigned array bounds preserve extremes evaluation order and cleanup' --test 'unread primitive stores preserve live inherited and native fields' --test 'unread primitive stores preserve effects across source class and archive links' --test 'unread primitive stores preserve mandatory reclamation safety'
```

These checks cover empty arrays, extreme indexes, explicit operand capture,
null/bounds failures, cleanup, hidden/inherited live fields, generic primitive
and reference storage, native field addresses, runtime layouts, source/class/
archive reconstruction, and mandatory unsafe-free rejection. Native cases run
at O0 and O3. They remain performance-pass checks and do not replace the
separate frontend correctness selection below.

For Java-shaped simple assignment timing and proven destructor receivers, run:

```sh
./scripts/test.sh --test 'simple assignments preserve RHS order while compound updates validate first' --test 'proven destructor receivers preserve mandatory safety'
```

The native test covers field and array side effects, exception precedence and
`finally` cleanup through source, loose-class and archive paths at O0/O3. The
semantic test accepts direct and aliased `this` field reads while preserving
nullable-receiver, allocation, publication and use-after-free rejection in
every unfreed mode.

For exact field value forwarding (D178), run:

```sh
./scripts/test.sh --test 'field value forwarding requires exact receiver and effect proofs' --test 'field value forwarding preserves native behavior and artifacts' --test 'field value forwarding preserves mandatory reclamation safety'
```

These checks cover exact and possible-alias receivers, field-slot separation,
leaf getter/setter effects, reference copies, joins/loops, initialization/native/
reclamation barriers, floating-point exclusion, inherited/hidden/generic fields,
reuse, virtual effects, nulls and try/finally cleanup. Source/class/archive
native cases run at O0/O3; unsafe frees remain rejected in every unfreed mode.
The focused aliasing, initialized-state and unread-store groups above cover
adjacent consumers. This selection does not authorize a full suite.

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

## Pool helper ownership checks

For changes to shared call binding or pool escape summaries, use the three
`pool release helper` checks listed in the
[pool helper regression analysis](POOL_RELEASE_HELPER_REGRESSION.md#regression-coverage-and-prevention),
plus affected existing dispatch and ownership checks. The selection pairs safe
helper extraction with rejected publication and wrong-pool cases, tests all
`--unfreed` modes, and reconstructs proofs from source, classes, and archives.
This is a focused regression group, not authorization for a full compiler suite.

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
after clients are built. A normal run first compiles and links six O3 executables;
build steps and test groups report progress, with waiting notices for long
commands and a 180-second per-command limit. Ctrl+C stops the active command
and its children. `python3 scripts/test-networking-m6-driver.py` checks progress,
cancellation, timeout logs and LLVM discovery without compiling Ironwood.
Local peers verify framing, redirect limits, deadlines,
TLS identities, proxy credentials and binary output. Fixed reader/network
workloads measure managed/native allocations and native calls separately from
untraced timings. Cleanup tests exhaust managed allocation positions and inject
close failures. Output is in `integration-tests/target/networking-m6/`.
Relocated host/IDK smoke tests use the shipped compile/link/run scripts and both
class-directory and archive links, then audit TLS and wget dynamic dependencies
and Linux GLIBC versions. See [M6 evidence](NETWORKING_M6_VERIFICATION.md).

## Deferred cleanup, Milestone 1

Both Milestone 1 stages are implemented. Select the focused groups:

```sh
./scripts/test.sh \
  --test 'deferred calls preserve explicit-block syntax and diagnostics' \
  --test 'deferred calls enforce invocation and checked-exception contracts' \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred calls replay typed captures across independent exits' \
  --test 'deferred calls survive source class and archive reconstruction' \
  --test 'deferred calls preserve exit and failure order at O3' \
  --test 'deferred calls flush and close loopback sockets at O3' \
  --test 'deferred free enforces local syntax and pending binding writes' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'deferred free emits independent typed cleanup copies' \
  --test 'deferred free survives source class and archive reconstruction' \
  --test 'combined defer cleanup preserves exits failures and live counts at O3' \
  --test 'combined defer cleanup closes and reclaims loopback sockets at O3'
```

After building, run `python3 scripts/test-defer-calls.py --llvm-home PATH` with
LLVM 23 for the paired one-call workload, allocation checks and optimized
assembly comparison. Output is under `integration-tests/target/defer-call-cost/`.
Pass `--kind free` for the paired allocation/free workload; output is then under
`integration-tests/target/defer-free-cost/`. These narrow lowering checks do not
perform or establish full Milestone 2 acceptance.
See [Stage 1 evidence](DEFER_CALLS_VERIFICATION.md) and
[Stage 2 evidence](DEFER_FREE_VERIFICATION.md) for affected existing
regressions and historical stage boundaries.

## Deferred cleanup performance stage

After building, run the complete D168 Section 8 matrix on macOS ARM64 with LLVM 23:

```sh
python3 scripts/test-defer-performance.py --llvm-home PATH --rounds 12
```

Use repeated `--case` options for focused investigations. `--skip-build` checks
source and compiler/executable hashes before reusing built artifacts. Adding
`--control --case free --case failing-2 --rounds 16` runs same-path and
identical-binary timing controls. See [performance evidence](DEFER_PERFORMANCE_VERIFICATION.md)
for exact commands, workload equivalence, sizes, timings and remaining boundaries.
This driver performs no example/project adoption or unfiltered compiler suite.

## Deferred cleanup example stage

With the built repository compiler on `PATH`, run only the new example:

```sh
./examples/deferredcleanup/compile.sh
./examples/deferredcleanup/link.sh
./examples/deferredcleanup/run.sh
```

The strict `--unfreed=error` compile and O3 link exercise class reconstruction.
The run script checks all five output lines and native exit `42`. The program
checks return and failure cleanup, primary/secondary order, reclamation live
counts, and allocation-free pool reuse across fallthrough and `continue`.
See [the example guide](../examples/deferredcleanup/README.md) for its exception
lifetime accounting and stage boundaries. No full example runner is needed.

## Deferred cleanup project adoption

Run `./projects/SimpleTcpEcho/test.sh` with the built repository compiler on
`PATH` for strict compile/link, loopback behavior and the native reply allocation
probe. The deterministic failure selection is:

```sh
./scripts/test.sh --test 'deferred server cleanup preserves per-client and listener failures at O3'
```

Its expected native exit is `74`: four clients are reclaimed before later
accepts, then the designated accept failure reaches the outer handler after
listener cleanup. See [project evidence](DEFER_PROJECT_VERIFICATION.md) for the
affected existing selections, exact results and generated-code comparison.
The [final audit](DEFER_FINAL_VERIFICATION.md) consolidates the completed
milestones and documentation checks; it does not require repeating passing
behavior or performance checks for documentation-only changes.

## Deferred cleanup standard-library adoption

The [stdlib adoption verification](DEFER_STDLIB_VERIFICATION.md) records the
exact focused selections for library cleanup, allocation failures, ownership,
artifact reconstruction, networking, and paired compiled-code comparison.
`scripts/test-stdlib.sh` covers selected library families; it is not exhaustive
stdlib coverage. The additional collection rendering regression is:

```sh
./scripts/test.sh --test 'deferred standard-library collection rendering preserves text and live counts'
```
