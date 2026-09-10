# Release Ironwood

Use the source checkout to release a stable Ironwood version together with its
frozen standard-library API reference:

```sh
./scripts/release.sh 0.1.4
```

The command prepares the stable release and the next beta, then pushes both
commits on `main` and the stable release tag atomically. GitHub Actions builds,
packages, verifies, and publishes it. A successful local command means the tag
was pushed, not that the hosted release workflow has finished.

## Before running the command

- Complete final local verification using [Local platform tests](LOCAL_TESTING.md).
  Full suites are reserved for release readiness; fixes use selected tests.
- Merge the completed work into `main` and push it to `origin`.
- Start on a clean `main`, with no staged, unstaged, or untracked files.
  Ignored build output does not count as a dirty working tree.
- Choose any stable `MAJOR.MINOR.PATCH` version, with no leading `v` or
  prerelease suffix. It need not match the current development version.
- The requested tag must not exist locally or on `origin` for a new release.
- Use Git, Python 3, Java 21, and credentials that can push to the repository.
  `origin` must have one matching fetch and push URL.

For example, once all release work has been merged and pushed:

```sh
git switch main
git pull --ff-only origin main
git status --short
./scripts/release.sh 0.1.4 --dry-run
./scripts/release.sh 0.1.4
```

The script fetches `origin/main` and requires it to equal local `main` before
preparing a new release. It does not merge, rebase, stash, switch branches, or
push unrelated branches. A dry run checks the branch, working tree, source
version, version prose, and tag availability. It fetches remote refs but does
not edit files, create commits or tags, run packaging, or publish anything.

Version numbers may skip patch, minor, or major releases. For example, from
`0.2.6-beta` you can select `0.3.0` directly:

```sh
./scripts/release.sh 0.3.0 --status "Release Message Here"
```

This releases the current source as `0.3.0`, retires `docs/api/0.2.6-beta/`,
and starts development at `0.3.1-beta`. No intermediate release is required.

## Customize the IronDocs status

Use `--status` to replace **Stable release** in the API version table for this
release. For example, when releasing `0.1.5`:

```sh
./scripts/release.sh 0.1.5 --status "Pool improvements"
```

The message must be non-empty, single-line plain text. Quote messages containing
spaces. Markdown and HTML punctuation are displayed literally. Omitting the
option uses **Stable release**. Beta versions always display
**Development (updated in place)**.

The message is saved in that version's `snapshot.json` and survives future
IronDocs regeneration and releases. It changes the IronDocs table only, not
the GitHub release notes, release title, or Git tag message.

`--status` also works with `--dry-run` and `--prepare-only`. After preparation,
`--resume` preserves the saved message without needing the option again. If you
repeat `--status` with `--resume` or `--verify-tag`, it must match the saved
message; those commands cannot rewrite an existing release's status.

## What the release command does

1. Set `VERSION` to the requested stable version and update the source-tree
   version in `docs/LANGUAGE_SPECS.md`.
2. Generate `docs/api/0.1.4/` from those sources using the IronDocs generator.
   Remove the previous development reference, even when its version differs,
   and any prerelease of the requested version. Update `docs/api/README.md`.
   Preserve earlier stable references.
3. Verify the source fingerprint, snapshot files, and version index, then commit
   the stable version and its documentation together.
4. Create the annotated tag `v0.1.4` on that release commit.
5. Advance `VERSION` and the matching version prose to `0.1.5-beta`, generate
   `docs/api/0.1.5-beta/`, and commit that next development state on `main`.
   Preserve the stable `0.1.4` snapshot and its tagged commit.
6. Push `main` and the tag in one atomic Git operation. If either update is
   rejected, neither remote ref is updated by that push.

The version argument has no leading `v` and no prerelease suffix. The next
development version increments the patch number and appends `-beta`, for example
`0.1.4` becomes `0.1.5-beta`. The beta commit has no release tag and does not
trigger a GitHub release. Development advances when the atomic push succeeds;
it does not wait for the hosted build to finish. A later CI failure leaves the
stable tag intact and `main` ready for fixes in the next development version.

## Frozen IronDocs and the release tag

The version folder is a source snapshot that the generation tools refuse to
rewrite. It is not made read-only on GitHub: a contributor with write access can
still edit it in a later commit.

Release notes link to the reference **at the release tag**, for example
`v0.1.4/docs/api/0.1.4/` when browsing the repository. Editing that folder on
`main` does not change the files selected by `v0.1.4`. Preserving the release tag
preserves the published reference. The script never moves an existing tag and
does not configure GitHub rules or permissions that prohibit tag changes.

## GitHub's release checks

The tag push triggers `.github/workflows/release.yml`. Before creating a draft,
the workflow checks that the tag belongs to `origin/main`, matches `VERSION`,
and contains the matching frozen IronDocs. The release notes include a link to
that tagged API reference.

The jobs audit licenses and build the source on macOS ARM64, Linux ARM64, and
Linux x86-64. They compare regenerated IronDocs with the frozen snapshot using
`./scripts/update-irondocs.sh --check`, build each IDK, and smoke-test it. Fresh
runners download and smoke-test the exact uploaded archives before the final job
publishes the release. The full compiler/native suite is deliberately omitted
from GitHub; run it locally before releasing. The release command does not run
that suite or duplicate the cross-platform build locally. The packaging commands
and archive layout are retained.

Linux release environments pin the architecture-matched glibc 2.17 sysroot.
Packaging rejects a different sysroot version, and archive smoke tests inspect
the generated ELF programs for any GLIBC requirement newer than 2.17.

Both packaging scripts include `conf/jvm.options` and its shared launcher helper.
GitHub runs the focused launcher tests on each platform. Archive smoke tests
then require the commented default file, set a probe JVM property through it for
all three packaged tools, check invalid-option diagnostics and missing-file
defaults, and restore the file. This also runs on the downloaded draft archives,
so a missing or unused configuration file blocks publication.

Check the **Release Ironwood IDK** workflow in GitHub Actions for the result.
If a build or verification fails, the workflow does not publish the draft.

If fixing the failure changes repository source, prepare a new patch release.
For example, preserve the failed `v0.1.3` tag, continue development at `0.1.4-beta`,
then release `0.1.4` after validation. The script deliberately refuses to move
an existing tag or rewrite its frozen IronDocs. The failed attempt remains in
Git history even though its GitHub release was never published.

A GitHub job retry uses the original event's commit, so it cannot pick up fixes
subsequently pushed to `main`. Retrying is appropriate for transient runner or
network failures that do not require source changes. See GitHub's
[workflow retry behavior](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs).

## Prepare locally or resume an interrupted push

To inspect the exact release commit and tag before pushing:

```sh
./scripts/release.sh 0.1.4 --prepare-only
./scripts/release.sh 0.1.4 --resume
```

`--prepare-only` prepares both commits and the stable tag, leaving local `main`
at the next beta, then stops before pushing. Inspect `v0.1.4` for the stable
release and `main` for the next development state.

`--resume` validates the stable commit and its beta successor, including their
documentation and allowed file changes. It can finish preparation from a clean
stable commit, restore a missing local tag to the verified release commit, or
push an already prepared pair. It permits the prepared `main` to be ahead of
`origin/main`, but rejects divergent history, unexpected later commits, or an
existing tag pointing elsewhere.

If a push fails or its result is uncertain, the local commits and tag remain
available. Resolve the remote error and rerun with `--resume`. If both refs
already arrived, the command reports that instead of republishing. If another
change made `origin/main` diverge, inspect that change and prepare a fresh release
from synchronized `main`; the script does not force a push or rewrite a tag.

If preparation fails before pushing, inspect the local changes left for review.
The script does not reset or discard them. If beta generation failed after the
stable commit was tagged, resolve or restore the uncommitted preparation changes
to obtain a clean stable commit, then run `--resume`. No tag or commit has been
pushed at that point. Once a tag has reached GitHub,
`--resume` does not rerun the workflow; use GitHub Actions to retry transient CI
failures.

## Start the next development cycle

The release command starts this cycle automatically. After releasing `0.1.4`,
`main` and the local source compiler identify themselves as `0.1.5-beta`;
the tagged source and released binaries remain `0.1.4`. The source launcher
rebuilds as needed when the version changes.

The `0.1.5-beta` IronDocs folder already exists. After further standard-library
changes, commit the source and update its documentation normally:

```sh
./scripts/update-irondocs.sh --commitpush
```

This updates and commits the current development reference, then pushes `main`.
It preserves `docs/api/0.1.4/` and earlier stable versions.
See [IronDocs](IRONDOCS.md) for the documentation lifecycle and preview commands.

## Verify changes to release automation

Run `./scripts/test-release.sh` for release orchestration changes. It uses small
standalone local Git fixtures and a bare local remote to exercise source/API
consistency, explicit version jumps, both commit scopes, next-beta generation, tag checks,
clean/synchronized main requirements, atomic rejection, interrupted preparation,
resume, consecutive releases, and the CI verification command.
It does not push to GitHub or create an actual Ironwood release.

Use `./scripts/test-irondocs.sh` when changing documentation generation. Changes
to IDK packaging or native behavior still require their affected packaging,
smoke, or compiler checks according to `AGENTS.md`.
