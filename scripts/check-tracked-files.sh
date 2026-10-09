#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

# Rejects tracked files that belong outside the repository: compressed files
# under docs/ (evidence archives, data and logs) and any file over 5 MiB.
# Keep such files elsewhere and commit their manifests and hashes instead
# (see docs/self-hosting/README.md). Checks the index, so staged files count.

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
IRONWOOD_SIZE_LIMIT=$((5 * 1024 * 1024))
IRONWOOD_FAILURES=0

while IFS=' ' read -r -d '' IRONWOOD_SIZE IRONWOOD_FILE; do
    case "$IRONWOOD_FILE" in
        docs/*.gz|docs/*.tgz|docs/*.bz2|docs/*.xz|docs/*.zst|docs/*.zip|docs/*.tar)
            echo "error: compressed file under docs/: $IRONWOOD_FILE" >&2
            IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            ;;
    esac
    if [[ "$IRONWOOD_SIZE" =~ ^[0-9]+$ ]] && (( IRONWOOD_SIZE > IRONWOOD_SIZE_LIMIT )); then
        echo "error: tracked file is larger than 5 MiB: $IRONWOOD_FILE ($IRONWOOD_SIZE bytes)" >&2
        IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
    fi
done < <(git -C "$IRONWOOD_PROJECT_ROOT" ls-files -z --format='%(objectsize) %(path)')

if [[ $IRONWOOD_FAILURES -ne 0 ]]; then
    echo "tracked-file check failed with $IRONWOOD_FAILURES error(s)" >&2
    exit 1
fi

echo "tracked-file check passed"
