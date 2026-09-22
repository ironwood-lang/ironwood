#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

# Finds clang++ in the LLVM 23 installation that ironwoodc uses, searched in
# ironwoodc's order: IRONWOOD_LLVM_HOME, the IDK toolchain beside ironwoodc,
# Homebrew, and /usr/lib/llvm-23. Compiling with it gives the C++ and Ironwood
# code the same LLVM optimizer and code generator.
find_llvm_cxx() {
    local homes=()
    local home
    local compiler
    if [[ -n "${IRONWOOD_LLVM_HOME:-}" ]]; then
        homes+=("$IRONWOOD_LLVM_HOME")
    fi
    if command -v ironwoodc >/dev/null 2>&1; then
        homes+=("$(dirname -- "$(command -v ironwoodc)")/../toolchain")
    fi
    if command -v brew >/dev/null 2>&1; then
        homes+=("$(brew --prefix llvm@23 2>/dev/null || true)")
        homes+=("$(brew --prefix llvm 2>/dev/null || true)")
    fi
    homes+=(/usr/lib/llvm-23)
    for home in "${homes[@]}"; do
        [[ -n "$home" && -x "$home/bin/llvm-config" ]] || continue
        [[ "$("$home/bin/llvm-config" --version 2>/dev/null)" == 23.* ]] || continue
        for compiler in clang++ clang++-23; do
            if [[ -x "$home/bin/$compiler" ]]; then
                printf '%s\n' "$home/bin/$compiler"
                return 0
            fi
        done
    done
    echo 'error: clang++ from LLVM 23 not found; install LLVM 23 or set IRONWOOD_LLVM_HOME' >&2
    return 1
}

CXX=$(find_llvm_cxx)

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
