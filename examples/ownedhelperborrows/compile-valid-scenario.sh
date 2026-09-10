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
SOURCE="src/valid/ironwood/org/ironwood/ownedhelperborrows/$SCENARIO_NAME.iron"
mkdir -p target/classes
# Scenario 19 deliberately skips cleanup on its already-destroyed failure branch.
# Keep this exception local to that scenario, including direct compile19.sh runs.
COMMAND=(ironwoodc)
if [[ "$SCENARIO_NAME" == FreeInsertedElement ]]; then
    COMMAND+=(--unfreed=off)
fi
COMMAND+=(--source-path src/valid/ironwood -d target/classes "$SOURCE")
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
