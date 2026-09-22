#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

WARMUP_MILLIONS=${1:-10}
MEASURED_MILLIONS=${2:-100}

exec ./target/orderbook-bench "$WARMUP_MILLIONS" "$MEASURED_MILLIONS"
