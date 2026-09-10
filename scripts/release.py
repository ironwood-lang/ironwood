#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Tag a stable source/API commit and advance main to the next beta in one atomic push."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

sys.dont_write_bytecode = True
import irondocs


STABLE = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
RELEASE_PATHS = ["VERSION", "docs/LANGUAGE_SPECS.md", "docs/api"]
git = irondocs.git


def next_development(version):
    major, minor, patch_version = map(int, version.split("."))
    return f"{major}.{minor}.{patch_version + 1}-beta"


def ref(root, name):
    result = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "--quiet", name],
                            stdout=subprocess.PIPE, text=True)
    if result.returncode not in (0, 1):
        raise ValueError(f"cannot resolve Git ref: {name}")
    return result.stdout.strip() if result.returncode == 0 else None


def require_clean(root):
    if git(root, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("release requires a clean working tree and index, including untracked files")


def require_main(root):
    if git(root, "branch", "--show-current") != "main":
        raise ValueError("release requires the main branch; merge the completed work into main first")


def is_ancestor(root, ancestor, descendant):
    result = subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", ancestor, descendant])
    if result.returncode not in (0, 1):
        raise ValueError("cannot verify release ancestry")
    return result.returncode == 0


def version_prose(root, current, target):
    text = (root / "docs/LANGUAGE_SPECS.md").read_text(encoding="utf-8")
    marker = f"`{current}` source tree."
    if text.count(marker) != 1:
        raise ValueError("docs/LANGUAGE_SPECS.md must identify the current VERSION source tree exactly once")
    return text.replace(marker, f"`{target}` source tree.")


def verify_files(root, version):
    """Fast release checks; CI also verifies regenerated pages with update-irondocs.sh --check."""
    if (root / "VERSION").read_text(encoding="utf-8").strip() != version:
        raise ValueError(f"source VERSION must match {version} exactly")
    version_prose(root, version, version)
    verify_snapshot(root, version)


def verify_snapshot(root, version):
    api = root / "docs/api"
    if (root / "docs").is_symlink() or api.is_symlink():
        raise ValueError("release documentation must not use symbolic-link directories")
    snapshots = irondocs.snapshots(api)
    if version not in snapshots:
        raise ValueError(f"missing IronDocs snapshot: docs/api/{version}")
    snapshot = snapshots[version]
    if any(name not in snapshot for name in ("README.md", "assets/irondocs.svg", "snapshot.json")):
        raise ValueError(f"incomplete IronDocs snapshot for {version}")
    metadata = json.loads(snapshot["snapshot.json"])
    if metadata.get("source_sha256") != irondocs.source_digest(root):
        raise ValueError("IronDocs source fingerprint does not match the source")
    if any(name.startswith(version + "-") for name in snapshots):
        raise ValueError(f"retire the {version} prerelease IronDocs before releasing")
    landing = (api / "README.md").read_text(encoding="utf-8")
    if irondocs.index_row(version, metadata) not in landing.splitlines():
        raise ValueError("IronDocs version index must match the version snapshot and status")


def verify_status(root, version, requested):
    if requested is not None:
        metadata = json.loads((root / f"docs/api/{version}/snapshot.json").read_text(encoding="utf-8"))
        if irondocs.status_text(version, metadata) != requested:
            raise ValueError("--status differs from the prepared release; omit it to keep the saved status")


def verify_tag(root, version, status=None):
    require_clean(root)
    head = git(root, "rev-parse", "HEAD")
    if ref(root, f"refs/tags/v{version}^{{commit}}") != head:
        raise ValueError(f"v{version} must point to the checked-out release commit")
    main = ref(root, "refs/remotes/origin/main")
    if not main or not is_ancestor(root, head, main):
        raise ValueError("release tag must belong to origin/main")
    verify_files(root, version)
    verify_status(root, version, status)
    print(f"Verified v{version}: matching source, main ancestry, and frozen IronDocs")


def remote_state(root, tag):
    # Inspect the same single remote that will receive the atomic push.
    fetch_url = git(root, "remote", "get-url", "origin")
    if git(root, "remote", "get-url", "--push", "--all", "origin").splitlines() != [fetch_url]:
        raise ValueError("origin must have one matching fetch and push URL")
    git(root, "fetch", "--no-tags", "origin", "+refs/heads/main:refs/remotes/origin/main")
    remote_tag = git(root, "ls-remote", "--refs", "origin", f"refs/tags/{tag}")
    return git(root, "rev-parse", "refs/remotes/origin/main"), remote_tag.split()[0] if remote_tag else None


def prepare(root, version, initial_head, status=None):
    current = (root / "VERSION").read_text(encoding="utf-8").strip()
    prose = version_prose(root, current, version)
    (root / "VERSION").write_text(version + "\n", encoding="utf-8")
    (root / "docs/LANGUAGE_SPECS.md").write_text(prose, encoding="utf-8")
    irondocs.update(root, argparse.Namespace(no_commit=True, check=False, release=True, status=status,
                                            retire_prerelease=current))
    verify_files(root, version)
    commit_preparation(root, initial_head, f"Release Ironwood {version}", RELEASE_PATHS)


def commit_preparation(root, initial_head, message, paths):
    require_main(root)
    if git(root, "rev-parse", "HEAD") != initial_head:
        raise ValueError("HEAD changed during release preparation; inspect the local changes")
    # Do not absorb edits made by another process while documentation was generated.
    if git(root, "status", "--porcelain", "--untracked-files=all", "--", ".",
           *(f":(exclude){path}" for path in paths)):
        raise ValueError("unrelated files changed during release preparation; inspect the local changes")
    git(root, "add", "-A", "--", *paths)
    git(root, "diff", "--cached", "--check")
    if git(root, "diff", "--cached", "--name-only"):
        git(root, "commit", "--only", "-m", message, "--", *paths)
    require_clean(root)


def development_paths(version):
    return ["VERSION", "docs/LANGUAGE_SPECS.md", "docs/api/README.md", f"docs/api/{next_development(version)}"]


def prepared_release(root, version):
    """Recognize the stable commit or its single, metadata-only beta successor."""
    head = git(root, "rev-parse", "HEAD")
    current = (root / "VERSION").read_text(encoding="utf-8").strip()
    development = next_development(version)
    if current == version:
        verify_files(root, version)
        release_commit = head
    elif current == development:
        parents = git(root, "rev-list", "--parents", "-n", "1", head).split()
        if len(parents) != 2:
            raise ValueError("prepared development commit must directly follow one release commit")
        release_commit = parents[1]
        if git(root, "show", f"{release_commit}:VERSION") != version:
            raise ValueError("prepared development commit must directly follow the requested release")
        if git(root, "diff", "--name-only", release_commit, head, "--", ".",
               *(f":(exclude){path}" for path in development_paths(version))):
            raise ValueError("unexpected changes after the release commit; refusing to publish")
        if git(root, "show", f"{release_commit}:docs/LANGUAGE_SPECS.md") != version_prose(root, development, version).strip():
            raise ValueError("development version prose differs from the prepared release")
        if f"]({version}/README.md)" not in git(root, "show", f"{release_commit}:docs/api/README.md"):
            raise ValueError("prepared release index must link to its stable snapshot")
        verify_files(root, development)
        verify_snapshot(root, version)
    else:
        raise ValueError(f"cannot resume {version} from development version {current}")
    tag_commit = ref(root, f"refs/tags/v{version}^{{commit}}")
    if tag_commit and tag_commit != release_commit:
        raise ValueError(f"local v{version} points to a different commit; refusing to move it")
    return release_commit


def prepare_development(root, version):
    if (root / "VERSION").read_text(encoding="utf-8").strip() == next_development(version):
        return
    release_commit = prepared_release(root, version)
    development = next_development(version)
    prose = version_prose(root, version, development)
    (root / "VERSION").write_text(development + "\n", encoding="utf-8")
    (root / "docs/LANGUAGE_SPECS.md").write_text(prose, encoding="utf-8")
    irondocs.update(root, argparse.Namespace(no_commit=True, check=False, release=False))
    verify_files(root, development)
    commit_preparation(root, release_commit, f"Start Ironwood {development} development", development_paths(version))
    prepared_release(root, version)


def push_release(root, version):
    tag = f"v{version}"
    require_main(root)
    require_clean(root)
    release_commit = prepared_release(root, version)
    if ref(root, f"refs/tags/{tag}^{{commit}}") != release_commit:
        raise ValueError("release tag does not match the prepared release; no push attempted")
    if (root / "VERSION").read_text(encoding="utf-8").strip() != next_development(version):
        raise ValueError("prepare the next development version before publishing")
    try:
        git(root, "-c", "remote.origin.mirror=false", "push", "--no-follow-tags", "--atomic", "origin",
            "refs/heads/main:refs/heads/main", f"refs/tags/{tag}:refs/tags/{tag}")
    except subprocess.CalledProcessError as error:
        raise ValueError(f"release push failed or its result is uncertain; local commits and tag are preserved. "
                         f"Resolve the remote issue, then run scripts/release.sh {version} --resume") from error
    print(f"Pushed main and {tag}. GitHub will test, package, verify, and publish the release.")
    print(f"Frozen API reference: docs/api/{version}/README.md at tag {tag}")
    print(f"main is ready for {next_development(version)} development, including its IronDocs.")
    print("Check the Release Ironwood IDK workflow in GitHub Actions for completion.")


def execute(root, args):
    version = args.version
    if not STABLE.fullmatch(version):
        raise ValueError("pass a stable version such as 0.1.3, without the v prefix or a prerelease suffix")
    status = getattr(args, "status", None)
    if status is not None:
        status = irondocs.validate_status(status)
    if Path(git(root, "rev-parse", "--show-toplevel")).resolve() != root:
        raise ValueError("release script must run from the Ironwood checkout")
    if args.verify_tag:
        verify_tag(root, version, status)
        return
    require_main(root)
    require_clean(root)
    current = (root / "VERSION").read_text(encoding="utf-8").strip()
    if not irondocs.VERSION.fullmatch(current):
        raise ValueError(f"invalid VERSION: {current!r}")
    version_prose(root, current, version)
    head = git(root, "rev-parse", "HEAD")
    tag = f"v{version}"
    remote_main, remote_tag = remote_state(root, tag)
    local_tag = ref(root, f"refs/tags/{tag}")
    if args.resume:
        release_commit = prepared_release(root, version)
        verify_status(root, version, status)
        if remote_tag:
            if remote_tag != local_tag or not is_ancestor(root, release_commit, remote_main):
                raise ValueError(f"remote {tag} differs from the prepared release; refusing to overwrite it")
            if head == remote_main and current == next_development(version):
                print(f"{tag} is already pushed; main is at {current}. Check GitHub Actions for the release result.")
                return
        if not is_ancestor(root, remote_main, head):
            raise ValueError("origin/main diverged from the prepared release; no push attempted")
    else:
        if head != remote_main:
            raise ValueError("main must be synchronized with origin/main before preparing a release")
        if local_tag or remote_tag:
            raise ValueError(f"{tag} already exists locally or on origin; use --resume only for a prepared release")
        if args.dry_run:
            print(f"Ready to release {version} from synchronized main at {head[:12]}.")
            if status is not None:
                print(f"IronDocs status: {status}")
            print(f"Will set VERSION, update version prose, freeze docs/api/{version}, and retire the previous prerelease reference.")
            print(f"Will tag that stable commit {tag}, then commit {next_development(version)} and its IronDocs on main.")
            print("Will atomically push both commits on main and the release tag. No files or release refs changed.")
            return
        try:
            prepare(root, version, head, status)
        except (OSError, ValueError, subprocess.CalledProcessError) as error:
            raise ValueError(f"release preparation failed: {error}. Local changes are left for review; "
                             "no release push was attempted") from error
    try:
        release_commit = prepared_release(root, version)
        if not local_tag:
            git(root, "tag", "-a", tag, release_commit, "-m", f"Ironwood {version}")
        prepare_development(root, version)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise ValueError(f"release preparation failed: {error}. Local changes are left for review; "
                         f"no release push was attempted. Once the checkout is clean, "
                         f"continue with scripts/release.sh {version} --resume") from error
    if args.prepare_only:
        print(f"Prepared {tag} with stable IronDocs and main at {next_development(version)}. Nothing pushed.")
        print(f"After review, run scripts/release.sh {version} --resume")
        return
    push_release(root, version)


def main():
    parser = argparse.ArgumentParser(description="Release Ironwood and start the next beta from clean main.")
    parser.add_argument("version", help="any stable MAJOR.MINOR.PATCH version, independent of the current VERSION")
    parser.add_argument("--status", metavar="TEXT", help="custom plain-text IronDocs status for this release (default: Stable release)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="check prerequisites and show the plan without preparing or pushing")
    mode.add_argument("--prepare-only", action="store_true", help="prepare the stable tag and next beta locally without pushing")
    mode.add_argument("--resume", action="store_true", help="finish or publish a prepared stable release and next beta")
    mode.add_argument("--verify-tag", action="store_true", help="CI check: verify this tag's source, main ancestry, and frozen API")
    args = parser.parse_args()
    try:
        execute(Path(__file__).resolve().parent.parent, args)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
