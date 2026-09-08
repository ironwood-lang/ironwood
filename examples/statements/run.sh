#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/RemainingStatements'
set +e
./target/RemainingStatements
IRONWOOD_EXAMPLE_EXIT_STATUS=$?
set -e
printf 'exit status: %d\n' "$IRONWOOD_EXAMPLE_EXIT_STATUS"
if [[ $IRONWOOD_EXAMPLE_EXIT_STATUS -ne 42 ]]; then
    echo "error: expected exit status 42" >&2
    exit 1
fi
