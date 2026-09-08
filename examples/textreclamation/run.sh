#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/TextReclamation "Ironwood 🌲"'
set +e
OUTPUT=$(./target/TextReclamation "Ironwood 🌲")
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"
if [[ "$OUTPUT" != '[ronwood 🌲]' ]]; then
    echo "error: text slice output did not match" >&2
    exit 1
fi
if [[ $STATUS -ne 42 ]]; then
    echo "error: expected exit status 42" >&2
    exit 1
fi
