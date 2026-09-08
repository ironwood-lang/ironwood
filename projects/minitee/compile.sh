#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
COMMAND=(ironwoodc --source-path src/main/ironwood -d target/classes
    src/main/ironwood/org/ironwood/minitee/Minitee.iron)
printf '+ %q ' "${COMMAND[@]}" >&2
printf '\n' >&2
"${COMMAND[@]}"
