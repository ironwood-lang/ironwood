#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$PROJECT_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
cd "$PROJECT_DIR"

# Native access is granted explicitly so the JVM links the critical calls without a warning.
exec java --enable-native-access=ALL-UNNAMED -cp target/orderbook.jar:target/consumer-classes org.ironwood.orderbook.Main "$@"
