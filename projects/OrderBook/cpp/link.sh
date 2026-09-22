#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Links the objects from compile.sh, where all optimization happens.

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

source "$PROJECT_DIR/toolchain.sh"

CXX_PATH=$(find_llvm_cxx)
CXX=("$CXX_PATH" --driver-mode=g++)

# The IDK's clang is a conda build. Its default target, such as
# x86_64-conda-linux-gnu, uses a C-only sysroot and the conda linker, so
# retarget it to the same architecture and C library with a generic vendor and
# the system GCC's C++ standard library. Other clangs keep their defaults.
CXX_TRIPLE=$("$CXX_PATH" -print-target-triple)
if [[ "$CXX_TRIPLE" == *-conda-* ]]; then
    CXX+=(--target="${CXX_TRIPLE/-conda-/-unknown-}" --gcc-toolchain=/usr)
fi

# Stops early, with the compiler's output, when this compiler cannot build and
# run a C++ program on this machine.
check_cxx() {
    local probe
    probe=$(mktemp -d "${TMPDIR:-/tmp}/orderbook-cxx.XXXXXX")
    printf '#include <iostream>\nint main() { std::cout << "ok" << std::endl; }\n' > "$probe/probe.cpp"
    if "${CXX[@]}" -std=c++17 "$probe/probe.cpp" -o "$probe/probe" > "$probe/output" 2>&1 \
            && [[ "$("$probe/probe" 2>> "$probe/output")" == ok ]]; then
        rm -rf "$probe"
        return 0
    fi
    printf 'error: %s cannot build and run a C++ program here; install the system\n' "${CXX[*]}" >&2
    printf 'C++ standard library (for example the g++ package). Compiler output:\n' >&2
    cat "$probe/output" >&2
    rm -rf "$probe"
    return 1
}

check_cxx

DEMO_COMMAND=("${CXX[@]}" target/objects/Main.o -o target/orderbook)
printf '+ %q ' "${DEMO_COMMAND[@]}"
printf '\n'
"${DEMO_COMMAND[@]}"

BENCH_COMMAND=("${CXX[@]}" target/objects/Bench.o target/objects/JavaCompat.o -o target/orderbook-bench)
printf '+ %q ' "${BENCH_COMMAND[@]}"
printf '\n'
"${BENCH_COMMAND[@]}"

LATENCY_COMMAND=("${CXX[@]}" target/objects/LatencyBench.o target/objects/LatencyReport.o
    target/objects/JavaCompat.o -o target/orderbook-latency)
printf '+ %q ' "${LATENCY_COMMAND[@]}"
printf '\n'
"${LATENCY_COMMAND[@]}"
