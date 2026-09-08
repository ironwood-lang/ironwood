#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/ModernSwitch'
set +e
OUTPUT=$(./target/ModernSwitch)
IRONWOOD_EXAMPLE_EXIT_STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$IRONWOOD_EXAMPLE_EXIT_STATUS"
if [[ $IRONWOOD_EXAMPLE_EXIT_STATUS -ne 42 ]]; then
    echo "error: expected exit status 42" >&2
    exit 1
fi
if [[ "$OUTPUT" != 'modern switch: 42' ]]; then
    echo "error: unexpected modern-switch output" >&2
    exit 1
fi
