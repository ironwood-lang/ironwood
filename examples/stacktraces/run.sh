#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/StackTraces'
set +e
OUTPUT=$(./target/StackTraces 2>&1)
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"

EXPECTED=$(printf '%s\n' \
    'uncaught Ironwood exception: org.ironwood.stacktraces.PrimaryFailure: primary failed' \
    $'\tat org.ironwood.stacktraces.StackTraces.leaf(StackTraces.iron:33)' \
    $'\tat org.ironwood.stacktraces.StackTraces.middle(StackTraces.iron:38)' \
    $'\tat org.ironwood.stacktraces.StackTraces.run(StackTraces.iron:44)' \
    $'\tat org.ironwood.stacktraces.StackTraces.main(StackTraces.iron:52)' \
    'secondary Ironwood exception: org.ironwood.stacktraces.CleanupFailure: cleanup failed' \
    $'\tat org.ironwood.stacktraces.Cleanup.close(StackTraces.iron:25)' \
    $'\tat org.ironwood.stacktraces.StackTraces.run(StackTraces.iron:46)' \
    $'\tat org.ironwood.stacktraces.StackTraces.main(StackTraces.iron:52)')
if [[ $STATUS -ne 1 ]]; then
    echo "error: expected uncaught exception exit status 1" >&2
    exit 1
fi
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: stack trace did not match the expected source-level diagnostic" >&2
    exit 1
fi
