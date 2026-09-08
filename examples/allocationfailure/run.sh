#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ IRONWOOD_ALLOCATION_LIMIT=0 ./target/CatchableAllocationFailure'
set +e
IRONWOOD_ALLOCATION_LIMIT=0 ./target/CatchableAllocationFailure
STATUS=$?
set -e
printf 'exit status: %d\n' "$STATUS"
if [[ $STATUS -ne 42 ]]; then
    echo "error: expected exit status 42" >&2
    exit 1
fi
