#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

if [[ $# -eq 0 ]]; then
    set -- body data/sample.txt
fi

COMMAND=(./target/minigrep "$@")
printf '+ %q ' "${COMMAND[@]}"
printf '\n'
set +e
"${COMMAND[@]}"
STATUS=$?
set -e
printf 'exit status: %d\n' "$STATUS"
exit "$STATUS"
