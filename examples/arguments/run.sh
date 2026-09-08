#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/CommandLineArguments first "two words" "Ironwood 🌲"'
set +e
OUTPUT=$(./target/CommandLineArguments first "two words" "Ironwood 🌲")
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"
EXPECTED=$(printf 'first\ntwo words\nIronwood 🌲')
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: command-line arguments did not round-trip through String[]" >&2
    exit 1
fi
if [[ $STATUS -ne 3 ]]; then
    echo "error: expected exit status 3" >&2
    exit 1
fi
