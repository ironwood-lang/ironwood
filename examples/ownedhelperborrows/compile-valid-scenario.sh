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
COMMAND=(ironwoodc --source-path src/valid/ironwood -d target/classes "$SOURCE")
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
