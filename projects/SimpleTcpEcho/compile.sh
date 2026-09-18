#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
SOURCES=(src/main/ironwood/org/ironwood/simpletcpecho/*.iron)
COMMAND=(ironwoodc --source-path src/main/ironwood -d target/classes
    --unfreed=error "${SOURCES[@]}")
printf '+' >&2
printf ' %q' "${COMMAND[@]}" >&2
printf '\n' >&2
"${COMMAND[@]}"
