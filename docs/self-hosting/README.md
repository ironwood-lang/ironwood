<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Self-hosting preparation records

This directory holds the contracts, handoffs, manifests and reference
programs of the before-self-hosting milestones M0 to M6; the milestones are
described in [BEFORE_SELF_HOSTING_PLAN.md](../BEFORE_SELF_HOSTING_PLAN.md).

## Evidence kept outside the repository

The milestones also produced compressed evidence: frozen capture and
measurement archives (`*.tar.gz`), inventory and reconciliation data
(`*.json.gz`, `*.tsv.gz`) and run logs (`*.gz` under the evidence
directories). These 899 files, about 150 MiB, were removed from the
repository and its history on 2026-10-09 because every clone downloaded
them. The documents, manifests and scripts that name them are unchanged.
The manifests still record each file's name, size and SHA-256, so a
restored copy can be checked against them.

No test reads these files. The ownership pilot test compares its native
kernel results with the SHA-256 values that the M0 manifests record for the
J0 results. The qualification scripts under `scripts/self-hosting/` and
some `run-evidence.sh` scripts read the evidence, so they need the files
restored first.

Do not commit compressed files under `docs/`. `.gitignore` excludes them
and `scripts/check-tracked-files.sh` rejects them, along with any tracked
file over 5 MiB.

## Rewritten commit identifiers

Removing the files on 2026-10-09, and the local account name and the macOS
hostname on 2026-10-10, gave new identifiers to every commit from
2026-09-27 on: the 157 commits of this work and the Java Bridge commits
before them. The documents and manifests here still cite the identifiers
the commits had when they were written, and
[COMMIT_MAP.txt](COMMIT_MAP.txt) maps each of them to its current one.
Commit messages already cite the current identifiers.

## Neutral account and host names

On 2026-10-10 the local account name in recorded paths became `developer`,
and the private host names became `machost` (the macOS host), `x86host`
(the physical Linux x86-64 host) and `armvm` (the Linux arm64 VM), in 109
files under `docs/` and two READMEs elsewhere. The account name and the
macOS hostname were also removed from every earlier version in the
history. The SHA-256 values that manifests and identity snapshots record
for the edited files describe their earlier content. Scripts that read
paths from these records need them adjusted to the local machine.
