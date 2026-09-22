#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

source ./toolchain.sh

rm -rf target/objects
mkdir -p target/objects

# C++ optimizes while compiling, so -O3 and the host CPU flag apply here. The
# engine lives in headers, so each program's hot path inlines without LTO.
for SOURCE in src/main/cpp/org/ironwood/orderbook/*.cpp; do
    COMMAND=("$CXX" "${CXX_COMPILE_FLAGS[@]}"
        -c "$SOURCE" -o "target/objects/$(basename "$SOURCE" .cpp).o")
    printf '+ %q ' "${COMMAND[@]}"
    printf '\n'
    "${COMMAND[@]}"
done
