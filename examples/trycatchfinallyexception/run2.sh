#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

COMMAND=(./target/Case2BodyThrowsFinallyCompletes)
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
    'uncaught Ironwood exception: org.ironwood.trycatchfinallyexception.Case2BodyThrowsFinallyCompletes$FailureA: body failure A' \
    $'\tat org.ironwood.trycatchfinallyexception.Case2BodyThrowsFinallyCompletes.execute(Case2BodyThrowsFinallyCompletes.iron:16)' \
    $'\tat org.ironwood.trycatchfinallyexception.Case2BodyThrowsFinallyCompletes.main(Case2BodyThrowsFinallyCompletes.iron:25)')
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: case 2 exception report did not match" >&2
    exit 1
fi
