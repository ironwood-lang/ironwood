#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

COMMAND=(ironwoodc --link -cp target/classes
    --main-class org.ironwood.minigrep.Minigrep -o target/minigrep -O3)
printf '+ %q ' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
