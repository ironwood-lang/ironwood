#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <scenario-class> <expected-diagnostic>" >&2
    exit 2
fi

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

SCENARIO_NAME=$1
EXPECTED_MESSAGE=$2
SOURCE="src/invalid/ironwood/org/ironwood/ownedhelperborrows/$SCENARIO_NAME.iron"
OUTPUT_DIR="target/individual/$SCENARIO_NAME/classes"
DIAGNOSTIC_FILE="target/individual/$SCENARIO_NAME/diagnostic.log"
mkdir -p "$OUTPUT_DIR"
COMMAND=(ironwoodc --source-path src/invalid/ironwood -d "$OUTPUT_DIR" "$SOURCE")
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
if "${COMMAND[@]}" >"$DIAGNOSTIC_FILE" 2>&1; then
    cat "$DIAGNOSTIC_FILE" >&2
    printf 'error: %s unexpectedly compiled\n' "$SCENARIO_NAME" >&2
    exit 1
fi
if ! grep -Fq "$EXPECTED_MESSAGE" "$DIAGNOSTIC_FILE"; then
    cat "$DIAGNOSTIC_FILE" >&2
    printf 'error: %s did not report: %s\n' "$SCENARIO_NAME" "$EXPECTED_MESSAGE" >&2
    exit 1
fi
printf 'expected compile error: %s\n' "$EXPECTED_MESSAGE"
