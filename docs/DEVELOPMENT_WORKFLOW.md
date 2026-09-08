# Tests, IronDocs, and releases

Run these commands from the repository root:

```sh
cd "${HOME}/workspace-mba-m2/Ironwood"
```

Use Java 21, LLVM 23, and the native build tools described in the
[build instructions](../README.md#building-ironwood-from-source). The orchestration
scripts also require Python 3. GitHub publishing requires Git credentials with
push access to `origin`.

**During development, run only individual tests or focused groups.** Reserve a
full suite for final release readiness or an explicit request. After failures,
fix the causes and rerun only the failing tests. Documentation-only changes need
consistency checks, not compiler tests. See [AGENTS.md](../AGENTS.md).

## Tests on the current machine

```sh
./scripts/test.sh --list
./scripts/test.sh --test 'IronDocs pool example runs natively'

# Select several tests by repeating --test.
./scripts/test.sh \
  --test 'IronDocs pool example runs natively' \
  --test 'pool ownership rejects dangling and conflicting aliases'
```

All arguments to `./scripts/test.sh`:

| Argument | Effect |
| --- | --- |
| No arguments | Run the full compiler/native suite on this machine. Release readiness only. |
| `--list` | List test names without executing tests. Can combine with `--test`. |
| `--test 'EXACT NAME'` | Run that test; repeat to select several. Names must match exactly. |

Unknown names fail instead of running the full suite. There are no substring,
numeric, `--help`, or `--failed` options for this script. It runs the license
audit and rebuilds the compiler and test classes even with `--list`, so elapsed
time includes preparation. `make test` is also a full-suite invocation.

Test output includes the time, selected-test counter, status, and name:

```text
12:23:23.003 - 1/2 - RUN - IronDocs pool example runs natively
12:23:24.127 - 1/2 - ok - IronDocs pool example runs natively
```

## Native standard-library testing suites

Run the testing-module self-tests, migrated standard-library suites, and
Ironwood-native destruction suites with:

```sh
./scripts/test-stdlib.sh
```

The focused compiler-harness entry is:

```sh
./scripts/test.sh --test 'standard-library testing module reports deterministic native results'
```

The first command rebuilds the compiler. The harness uses `--skip-build` only
after `scripts/test.sh` has already produced the current compiler JAR. See
[Testing Ironwood code](TESTING.md) for the compiler-owned `@Test` design,
manual client workflow, migration inventory, and destruction coverage.
The runner prints one compact progress line per suite, retains the individual
test output for failure diagnostics, and reports every suite plus aggregate
case totals together at the end.

## Local Linux VM and three-platform tests

The platform script requires an Apple Silicon Mac, Python 3, Colima, and the
Docker CLI. Install Rosetta to test Linux x86-64. macOS tests run directly on the
Mac; both Linux targets share one dedicated ARM64 VM named `ironwood-tests`.
Linux x86-64 uses Rosetta translation, with no automatic fallback to QEMU.

### Start and stop

```sh
# Start the VM and prepare both Linux toolchain images. No tests run.
./scripts/test-platforms.sh --setup

# Alternatively, prepare only the x86-64 image.
./scripts/test-platforms.sh --setup --platform linux-x86_64

# Stop the shared Linux VM when finished.
./scripts/test-platforms.sh --stop
```

Setup uses six virtual CPUs and 8 GiB of memory. Initial image and toolchain
downloads can take longer than later starts. It preserves the default Colima
profile and active Docker context. Images are cached and rebuilt when their
configuration changes; there are no CLI options to change CPU or memory sizes.

Shutdown is manual, including after failed or interrupted tests. `--stop` is
equivalent to `colima stop --profile ironwood-tests`; it retains images, disk
data, logs, and reports. Run `--setup` before the next Linux test session. To
switch an older QEMU environment to Rosetta, stop it, then run setup again.

### Select tests and platforms

```sh
# List supported platform names without starting tools or running tests.
./scripts/test-platforms.sh --list

# One test on all three platforms.
./scripts/test-platforms.sh --test 'IronDocs pool example runs natively'

# Only Linux x86-64, for example after a platform-specific fix.
./scripts/test-platforms.sh --platform linux-x86_64 \
  --test 'IronDocs pool example runs natively'

# Full verification only when ready to release.
./scripts/test-platforms.sh --full

# Full verification on just one platform, when that is all that remains.
./scripts/test-platforms.sh --full --platform linux-x86_64

# Retry recorded failures after fixing them.
./scripts/test-platforms.sh --failed
./scripts/test-platforms.sh --failed --platform linux-x86_64
```

All arguments to `./scripts/test-platforms.sh`:

| Argument | Effect |
| --- | --- |
| `--list` | Print all supported platform names. No VM, host toolchain, or reports required. |
| `--setup` | Start the dedicated VM and prepare selected Linux images. |
| `--stop` | Stop the entire shared VM, regardless of platform selection. |
| `--full` | Run the full compiler/native suite on selected platforms. |
| `--test 'EXACT NAME'` | Run one named test; repeat to select several. |
| `--failed` | Retry named failures in each selected platform's latest report. |
| `--platform PLATFORM` | Select a platform; repeat to select several. |
| `--dry-run` | Preview commands without executing tools or writing output. |
| `-h`, `--help` | Show usage. |

Choose exactly one mode: `--list`, `--setup`, `--stop`, `--full`, `--test`, or `--failed`.
No mode means a usage error, not a full run. Platform names are exactly
`macos-arm64`, `linux-arm64`, and `linux-x86_64`. The default is all three in
that order; explicit selections run in the requested order. For example:

```sh
./scripts/test-platforms.sh --full \
  --platform macos-arm64 --platform linux-arm64 --dry-run
```

`--list` always prints all supported names, regardless of platform selection,
and also works on Linux with Python 3.

Platforms run **sequentially** and continue after test failures. Missing VM,
image, or Rosetta prerequisites can stop the run before testing begins. Tests
do not automatically run setup. Ctrl+C stops the current test process and its
container and prevents remaining platforms from starting; the VM stays running.

Run one orchestrator at a time and avoid editing source during validation.
Linux uses the current checkout and isolated compiler output under
`workspace/platform-tests/build/<platform>/`. These commands do not trigger
GitHub Actions. Local environments reduce release surprises but do not reproduce
every detail of GitHub runners.

### Logs and retries

After testing, a **FINAL PLATFORM SUMMARY** table lists every selected platform,
its result, passed/failed test counts, and elapsed time (`HH:MM:SS`), followed by
the total test time. Interrupted, incomplete, unrun, and skipped platforms are
marked explicitly. The heading identifies a full suite, selected tests, or
retries. Setup and dry runs do not print test results.

Each platform writes to `workspace/platform-tests/`:

| File | Contents |
| --- | --- |
| `<platform>-YYYYMMDD-HHMMSS.log` | Timestamped run output. |
| `<platform>.json` | Latest selection, duration, completion status, exit code, passed count, log path, and failing names. |

`--failed` reruns **only recorded failing names**. It does not resume from a
test number or run tests that were never reached. A platform with a successful
report and no failures is skipped. Missing reports, or unsuccessful runs without
named failures, require resolving the problem and selecting tests explicitly.
Even `--failed --dry-run` needs reports to determine the selection.

Each retry replaces the latest JSON report; older timestamped logs remain.
A successful focused retry is not recorded as a new full-suite pass. More
environment details: [Local platform tests](LOCAL_TESTING.md).

## Other focused checks

Run these only when their area changes. The shell checks have no test-selection
options; the archive checks each require one path to an existing host-compatible
`.tar.gz` file.

| Command | Purpose |
| --- | --- |
| `./scripts/check-licenses.sh` | Source, license, provenance, and distribution audit. |
| `./scripts/test-irondocs.sh` | IronDocs generator and lifecycle checks, plus current generated-file consistency. |
| `./scripts/test-release.sh` | Release orchestration using temporary local Git repositories, without publishing to GitHub. |
| `python3 scripts/test-platform-workflow.py` | VM/test-runner orchestration checks using mocked commands, without starting a VM. |
| `./scripts/test-package.sh ARCHIVE.tar.gz` | Relocated host-package smoke test; requires system Java 21 and LLVM 23. |
| `./scripts/test-idk.sh ARCHIVE.tar.gz` | Self-contained IDK archive smoke test; macOS also needs Apple Command Line Tools. |

The Python workflow checker also accepts standard `unittest` arguments; use
`python3 scripts/test-platform-workflow.py --help` to see them. The shell wrappers
above do not forward test-selection arguments. To execute the documented pool
example, use the named compiler test shown earlier.

`test-platforms.sh --full` does not include these separate packaging checks.
See [Platform packages](../README.md#platform-packages) and [IDK](IDK.md) for
building the archives. For every repository change, run `git diff --check`.

## Generate and publish IronDocs

Edit documentation comments in `stdlib/src/main/ironwood/`, then commit the
source changes before committing generated documentation. The wrapper reads the
repository's `VERSION`, not an installed compiler's version. For example,
`VERSION` containing `0.1.5-beta` selects `docs/api/0.1.5-beta/`.

```sh
cat VERSION
./scripts/update-irondocs.sh              # Generate for local review.
./scripts/update-irondocs.sh --commit     # Generate and commit only.
./scripts/update-irondocs.sh --commitpush # Generate, commit, and push.
```

These are alternative workflows; normally run just the one you need. All options:

| Argument | Effect |
| --- | --- |
| No arguments, or `--no-commit` | Generate without committing or pushing. |
| `--commit` | Generate and commit only changes under `docs/api/`. No push. |
| `--commitpush` | Generate, commit documentation if changed, and push the current branch to `origin`. No tags. |
| `--check` | Regenerate in temporary storage and fail if checked-in output differs. No documentation edits, commit, or push. |
| `--release` | Prepare a stable snapshot using an already stable `VERSION`, retiring its prerelease documentation. |
| `-h`, `--help` | Show usage. |

`--commit`, `--commitpush`, `--no-commit`, and `--check` are mutually exclusive.
`--release` can accompany a mode, but normally let `release.sh` manage it.
It does not create a release tag or advance `VERSION` by itself. A stable
`VERSION` requires `--release` or `--check`; ordinary development updates refuse
to overwrite it. Generation and consistency checks may rebuild the compiler.

Each generation also checks the numeric inventory at the top of `STDLIB.md`
against the source tree and the newly generated package index. A source-file,
documented-type, package-total, or per-package mismatch fails before any
versioned API files are replaced. Update the inventory and its package purposes
as part of the same standard-library change.

Commit modes reject uncommitted changes to the source/version/generator inputs.
They leave unrelated changes outside `docs/api/` out of the documentation commit.
However, **pushing sends all pending commits on the current branch**, including
earlier source commits. If pushing fails, fix the Git error and rerun
`--commitpush`; the local documentation commit is retained.

On GitHub, select the pushed branch and open `docs/api/README.md`, then the
version link. Markdown renders directly; GitHub Pages is unnecessary. Development
updates replace the current beta reference and retire obsolete development
folders. Earlier stable snapshots are preserved, and the generator refuses to
overwrite changed stable documentation.

For direct `irondoc` arguments and comment syntax, see
[IronDocs](IRONDOCS.md#command-options).

## Release a stable version

Finish local release validation first. Merge and push the completed work, then
start from clean `main`, synchronized with `origin/main`. Staged, unstaged, and
untracked files must be absent; ignored build output is fine. The requested
version must match the base of `VERSION`, and its tag must not already exist.
`origin` must have one matching fetch and push URL.

Example: release `0.1.5` from `0.1.5-beta`. Substitute your actual version:

```sh
git switch main
git pull --ff-only origin main
git status --short
cat VERSION
./scripts/release.sh 0.1.5 --dry-run
./scripts/release.sh 0.1.5
```

All arguments to `./scripts/release.sh VERSION`:

| Argument | Effect |
| --- | --- |
| `VERSION` | Required stable `MAJOR.MINOR.PATCH`, with no leading `v` or prerelease suffix. |
| `--status "TEXT"` | Custom plain-text status for this stable release in the IronDocs version table. Defaults to **Stable release**. |
| No mode option | Prepare the release and next beta, then push both commits and the stable tag atomically. |
| `--dry-run` | Check prerequisites and preview. Fetches remote refs, but leaves working files, commits, and release tags unchanged. |
| `--prepare-only` | Prepare both commits and the stable tag locally, without pushing. |
| `--resume` | Validate and finish or push an already prepared release and next beta. |
| `--verify-tag` | CI check of a clean checkout at the release tag: matching source/API and ancestry in `origin/main`. No release creation. |
| `-h`, `--help` | Show usage. |

Mode options are mutually exclusive. The script does not switch branches,
merge, stash, force-push, run the compiler/native suite, or build local packages.
It may rebuild the compiler to generate IronDocs.

For a custom message, use `./scripts/release.sh 0.1.5 --status "Pool improvements"`.
The message must be non-empty and single-line; it is saved with the snapshot and
preserved by later IronDocs updates. Beta versions always display
**Development (updated in place)**. This option changes only the IronDocs table.
It can accompany a mode; `--resume` and `--verify-tag` keep the saved message and
reject a different one. See [Custom status](RELEASING.md#customize-the-irondocs-status).

For the example above, the normal release command:

1. Sets the source version to `0.1.5`, creates `docs/api/0.1.5/`, removes
   `docs/api/0.1.5-beta/`, and preserves earlier stable references.
2. Commits that stable source/documentation state and tags it `v0.1.5`.
3. Advances `main` to `0.1.6-beta`, generates its new API folder, and commits it.
4. Pushes `main` and `v0.1.5` atomically. The tag keeps the stable state; `main`
   already identifies the next beta.

Check **GitHub Actions > Release Ironwood IDK** after the push. Tag-triggered
jobs build all three platforms, audit licenses and IronDocs, and smoke-test the
packaged downloads before publishing. They skip the compiler/native suite.
Successful local pushing does not mean GitHub has finished publishing.

To review locally before publishing, or recover a failed/uncertain push:

```sh
./scripts/release.sh 0.1.5 --prepare-only
# Inspect the prepared commits and tag, then publish them.
./scripts/release.sh 0.1.5 --resume
```

Use `--resume` directly if preparation already completed; do not prepare again.
If preparation left uncommitted changes, inspect and resolve them first.
Resume rejects divergent or unexpected history and never moves an existing tag.
It does not rerun GitHub jobs after a successful push. Retry transient CI failures
in GitHub Actions; source fixes require a new patch release, preserving the old
tag. Further recovery details: [Releasing Ironwood](RELEASING.md).
