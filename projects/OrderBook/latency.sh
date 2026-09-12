#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

if [[ $# -gt 3 ]]; then
    echo 'usage: latency.sh [warmup-batches] [measured-batches] [cycles-per-batch]' >&2
    exit 2
fi

WARMUP_BATCHES=${1:-10000}
MEASURED_BATCHES=${2:-50000}
CYCLES_PER_BATCH=${3:-1000}

exec ./target/orderbook-latency "$WARMUP_BATCHES" "$MEASURED_BATCHES" "$CYCLES_PER_BATCH"
