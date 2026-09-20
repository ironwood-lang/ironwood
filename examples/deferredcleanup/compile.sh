#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

SOURCES=(src/main/ironwood/org/ironwood/deferredcleanup/*.iron)
COMMAND=(ironwoodc --unfreed=error --source-path src/main/ironwood -d target/classes "${SOURCES[@]}")
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
