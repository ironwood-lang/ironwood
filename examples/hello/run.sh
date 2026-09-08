#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

COMMAND=(./target/HelloWorld)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
set +e
OUTPUT=$("${COMMAND[@]}")
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"
if [[ "$OUTPUT" != "Hello World!" ]]; then
    echo "error: expected output 'Hello World!'" >&2
    exit 1
fi
if [[ $STATUS -ne 0 ]]; then
    echo "error: expected exit status 0" >&2
    exit 1
fi
