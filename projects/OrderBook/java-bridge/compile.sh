#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

# Compile only the existing engine closure, not the native benchmark drivers.
rm -rf target/iron-classes
COMMAND=(ironwoodc --unfreed=off --source-path ../src/main/ironwood
    -d target/iron-classes ../src/main/ironwood/org/ironwood/orderbook/OrderBook.iron)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
