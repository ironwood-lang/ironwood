#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

COMMAND=(./target/Case1BodyCompletesFinallyCompletes)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
set +e
OUTPUT=$("${COMMAND[@]}" 2>&1)
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"
if [[ $STATUS -ne 42 ]]; then
    echo "error: expected exit status 42" >&2
    exit 1
fi
EXPECTED='case 1: body completed; finally completed; execution continued'
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: case 1 output did not confirm normal completion" >&2
    exit 1
fi
