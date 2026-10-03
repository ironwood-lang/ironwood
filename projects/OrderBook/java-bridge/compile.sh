#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$PROJECT_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
cd "$PROJECT_DIR"

# Compile only the existing engine closure, not the native benchmark drivers.
rm -rf target/iron-classes
COMMAND=(ironwoodc --source-path ../src/main/ironwood
    -d target/iron-classes ../src/main/ironwood/org/ironwood/orderbook/OrderBook.iron)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
