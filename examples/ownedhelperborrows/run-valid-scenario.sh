#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <scenario-class>" >&2
    exit 2
fi

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

SCENARIO_NAME=$1
COMMAND=("./target/$SCENARIO_NAME")
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
set +e
"${COMMAND[@]}"
PROGRAM_CODE=$?
set -e
printf 'exit status: %d\n' "$PROGRAM_CODE"
if [[ $PROGRAM_CODE -ne 42 ]]; then
    printf 'error: %s expected exit status 42\n' "$SCENARIO_NAME" >&2
    exit 1
fi
