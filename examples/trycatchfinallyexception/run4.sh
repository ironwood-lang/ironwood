#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

COMMAND=(./target/Case4BodyThrowsFinallyThrows)
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
    'uncaught Ironwood exception: org.ironwood.trycatchfinallyexception.Case4BodyThrowsFinallyThrows$FailureA: body failure A' \
    $'\tat org.ironwood.trycatchfinallyexception.Case4BodyThrowsFinallyThrows.execute(Case4BodyThrowsFinallyThrows.iron:29)' \
    $'\tat org.ironwood.trycatchfinallyexception.Case4BodyThrowsFinallyThrows.main(Case4BodyThrowsFinallyThrows.iron:40)' \
    'secondary Ironwood exception: org.ironwood.trycatchfinallyexception.Case4BodyThrowsFinallyThrows$FailureB: finally failure B' \
    $'\tat org.ironwood.trycatchfinallyexception.Case4BodyThrowsFinallyThrows.execute(Case4BodyThrowsFinallyThrows.iron:32)' \
    $'\tat org.ironwood.trycatchfinallyexception.Case4BodyThrowsFinallyThrows.main(Case4BodyThrowsFinallyThrows.iron:40)')
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: case 4 primary/secondary exception report did not match" >&2
    exit 1
fi
