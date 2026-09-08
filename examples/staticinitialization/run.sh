#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ./target/StaticInitialization'
set +e
OUTPUT=$(./target/StaticInitialization)
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
printf 'exit status: %d\n' "$STATUS"

EXPECTED=$(printf '%s\n' \
    '1. superclass' \
    '2. default-method interface' \
    '3. field initializer' \
    '4. static block' \
    '5. main')
if [[ $STATUS -ne 42 ]]; then
    echo "error: expected exit status 42" >&2
    exit 1
fi
if [[ "$OUTPUT" != "$EXPECTED" ]]; then
    echo "error: static initialization order did not match" >&2
    exit 1
fi
