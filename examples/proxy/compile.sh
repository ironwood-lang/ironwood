#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"
COMMAND=(ironwoodc --source-path src/main/ironwood -d target/classes --unfreed=error
    src/main/ironwood/org/ironwood/proxy/ProxyTunnel.iron)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
