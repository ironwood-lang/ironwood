#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Links the objects from compile.sh, where all optimization happens.

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
