#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

SOURCES=(src/main/ironwood/org/ironwood/qualifiedanonymous/*.iron)
# Keep the conditional qualifier, chained calls, and inline constructor argument intact.
# Their unnamed holders/payload remain allocated until process exit. Only missing-free
# diagnostics are disabled for this syntax demonstration; memory-safety errors remain.
COMMAND=(ironwoodc --unfreed=off --source-path src/main/ironwood -d target/classes "${SOURCES[@]}")
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
