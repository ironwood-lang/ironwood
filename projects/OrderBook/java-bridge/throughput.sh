#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$PROJECT_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
cd "$PROJECT_DIR"

if [[ $# -gt 2 ]]; then
    echo 'usage: throughput.sh [warmup-millions] [measured-millions]' >&2
    exit 2
fi

WARMUP_MILLIONS=${1:-10}
MEASURED_MILLIONS=${2:-100}

exec java -cp target/orderbook.jar:target/consumer-classes org.ironwood.orderbook.Bench "$WARMUP_MILLIONS" "$MEASURED_MILLIONS"
