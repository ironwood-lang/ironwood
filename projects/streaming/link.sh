#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
COMMAND=(ironwoodc --link -cp target/classes
    --main-class org.ironwood.streaming.Streaming -o target/streaming -O3)
printf '+ %q ' "${COMMAND[@]}" >&2
printf '\n' >&2
"${COMMAND[@]}"
