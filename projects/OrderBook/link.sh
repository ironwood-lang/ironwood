#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

DEMO_COMMAND=(ironwoodc --link -cp target/classes
    --main-class org.ironwood.orderbook.Main -o target/orderbook -O3)
printf '+ %q ' "${DEMO_COMMAND[@]}"
printf '\n'
"${DEMO_COMMAND[@]}"

BENCH_COMMAND=(ironwoodc --link -cp target/classes
    --main-class org.ironwood.orderbook.Bench -o target/orderbook-bench -O3)
printf '+ %q ' "${BENCH_COMMAND[@]}"
printf '\n'
"${BENCH_COMMAND[@]}"
