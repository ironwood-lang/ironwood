#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Set CXX to the clang++ of the LLVM 23 install that ironwoodc uses, so the C++
# and Ironwood code share one LLVM optimizer and code generator. The clang++ on
# PATH may bundle an older LLVM; it is only the default.

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

CXX=${CXX:-clang++}

# Tune for the build host, as the Ironwood and Native Image builds do. On arm64
# -march=native selects a generic core, while -mcpu=native selects the host
# core, matching the -mcpu=native that ironwoodc passes to LLVM.
case $(uname -m) in
    arm64|aarch64) CPU_FLAG=-mcpu=native ;;
    *) CPU_FLAG=-march=native ;;
esac

# Java rounds every floating-point operation separately, so the report code
# must not fuse multiplies and adds. The measured workload is integer-only.
COMPILE_FLAGS=(-std=c++17 -O3 "$CPU_FLAG" -ffp-contract=off
    -Wall -Wextra -Wpedantic -Werror -I src/main/cpp)

rm -rf target/objects
mkdir -p target/objects

# C++ optimizes while compiling, so -O3 and the host CPU flag apply here. The
# engine lives in headers, so each program's hot path inlines without LTO.
for SOURCE in src/main/cpp/org/ironwood/orderbook/*.cpp; do
    COMMAND=("$CXX" "${COMPILE_FLAGS[@]}"
        -c "$SOURCE" -o "target/objects/$(basename "$SOURCE" .cpp).o")
    printf '+ %q ' "${COMMAND[@]}"
    printf '\n'
    "${COMMAND[@]}"
done
