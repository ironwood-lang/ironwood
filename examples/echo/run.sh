#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/Echo "Ironwood 🌲" 3'
OUTPUT=$(./target/Echo "Ironwood 🌲" 3)
printf '%s\n' "$OUTPUT"
if [[ "$OUTPUT" != 'text=Ironwood 🌲 count=3 enabled=true' ]]; then
    echo "error: echo output did not match" >&2
    exit 1
fi

printf '%s\n' '+ ./target/Echo'
set +e
ERROR_OUTPUT=$(./target/Echo 2>&1)
ERROR_STATUS=$?
set -e
printf '%s\n' "$ERROR_OUTPUT"
printf 'exit status: %d\n' "$ERROR_STATUS"
if [[ "$ERROR_OUTPUT" != 'usage: Echo <text> <count>' || $ERROR_STATUS -ne 64 ]]; then
    echo "error: echo usage behavior did not match" >&2
    exit 1
fi
