#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/DeferredCleanup'
set +e
OUTPUT=$(./target/DeferredCleanup 2>&1)
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"

EXPECTED='normal: body -> close
body failure: primary preserved, resource closed
close failure: primary preserved, resource closed
body + close failure: body primary, close secondary, resource closed
pool: 3 checkouts, no new allocations'
if [[ $STATUS -ne 42 ]]; then
    echo "error: expected exit status 42" >&2
    exit 1
fi
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: deferred cleanup output did not match" >&2
    exit 1
fi
