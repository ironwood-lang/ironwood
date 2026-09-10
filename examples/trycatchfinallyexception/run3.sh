#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

COMMAND=(./target/Case3BodyCompletesFinallyThrows)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
set +e
OUTPUT=$("${COMMAND[@]}" 2>&1)
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"
if [[ $STATUS -ne 1 ]]; then
    echo "error: expected uncaught exception exit status 1" >&2
    exit 1
fi
EXPECTED=$(printf '%s\n' \
    'uncaught Ironwood exception: org.ironwood.trycatchfinallyexception.Case3BodyCompletesFinallyThrows$FailureB: finally failure B' \
    $'\tat org.ironwood.trycatchfinallyexception.Case3BodyCompletesFinallyThrows.execute(Case3BodyCompletesFinallyThrows.iron:22)' \
    $'\tat org.ironwood.trycatchfinallyexception.Case3BodyCompletesFinallyThrows.main(Case3BodyCompletesFinallyThrows.iron:30)')
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: case 3 exception report did not match" >&2
    exit 1
fi
