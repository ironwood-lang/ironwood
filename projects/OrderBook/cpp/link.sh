#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Use the same CXX as compile.sh. Optimization happened while compiling.

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

CXX=${CXX:-clang++}

DEMO_COMMAND=("$CXX" target/objects/Main.o -o target/orderbook)
printf '+ %q ' "${DEMO_COMMAND[@]}"
printf '\n'
"${DEMO_COMMAND[@]}"

BENCH_COMMAND=("$CXX" target/objects/Bench.o -o target/orderbook-bench)
printf '+ %q ' "${BENCH_COMMAND[@]}"
printf '\n'
"${BENCH_COMMAND[@]}"

LATENCY_COMMAND=("$CXX" target/objects/LatencyBench.o target/objects/LatencyReport.o -o target/orderbook-latency)
printf '+ %q ' "${LATENCY_COMMAND[@]}"
printf '\n'
"${LATENCY_COMMAND[@]}"
