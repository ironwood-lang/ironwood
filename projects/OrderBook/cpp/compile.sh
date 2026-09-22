#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

# Finds clang++ in the LLVM 23 installation that ironwoodc uses, searched in
# ironwoodc's order: IRONWOOD_LLVM_HOME, the IDK toolchain beside ironwoodc,
# Homebrew, the usual install directories, and llvm-config on PATH. Compiling
# with it gives the C++ and Ironwood code the same LLVM optimizer and code
# generator.
find_llvm_cxx() {
    local directories=()
    local directory
    local config
    local compiler
    if [[ -n "${IRONWOOD_LLVM_HOME:-}" ]]; then
        directories+=("$IRONWOOD_LLVM_HOME/bin")
    fi
    if command -v ironwoodc >/dev/null 2>&1; then
        directories+=("$(dirname -- "$(command -v ironwoodc)")/../toolchain/bin")
    fi
    if command -v brew >/dev/null 2>&1; then
        directories+=("$(brew --prefix llvm@23 2>/dev/null || true)/bin")
        directories+=("$(brew --prefix llvm 2>/dev/null || true)/bin")
    fi
    directories+=(/opt/homebrew/opt/llvm/bin /usr/local/opt/llvm/bin /usr/lib/llvm-23/bin)
    for config in llvm-config-23 llvm-config; do
        if command -v "$config" >/dev/null 2>&1; then
            directories+=("$("$config" --bindir 2>/dev/null || true)")
        fi
    done

    for directory in "${directories[@]}"; do
        [[ -x "$directory/llvm-config" ]] || continue
        [[ "$("$directory/llvm-config" --version 2>/dev/null)" == 23.* ]] || continue
        for compiler in clang++ clang++-23; do
            if [[ -x "$directory/$compiler" ]]; then
                printf '%s\n' "$directory/$compiler"
                return 0
            fi
        done
    done
    echo 'error: clang++ from LLVM 23 not found; install LLVM 23 or set IRONWOOD_LLVM_HOME' >&2
    return 1
}

CXX_PATH=$(find_llvm_cxx)
CXX=("$CXX_PATH")

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

# Tune for the build host, as the Ironwood and Native Image builds do. On arm64
# -march=native selects a generic core, while -mcpu=native selects the host
# core, matching the -mcpu=native that ironwoodc passes to LLVM.
case $(uname -m) in
    arm64|aarch64) CPU_FLAG=-mcpu=native ;;
    *) CPU_FLAG=-march=native ;;
esac

# Java rounds every floating-point operation separately, so the report code
# must not fuse multiplies and adds. The measured workload is integer-only.
# Signed addition, subtraction, and multiplication wrap as in Java and Ironwood.
COMPILE_FLAGS=(-std=c++17 -O3 "$CPU_FLAG" -ffp-contract=off -fwrapv
    -Wall -Wextra -Wpedantic -Werror -I src/main/cpp)

rm -rf target/objects
mkdir -p target/objects

# C++ optimizes while compiling, so -O3 and the host CPU flag apply here. The
# engine is visible in headers; the compiler chooses inlining without LTO.
for SOURCE in src/main/cpp/org/ironwood/orderbook/*.cpp; do
    COMMAND=("${CXX[@]}" "${COMPILE_FLAGS[@]}"
        -c "$SOURCE" -o "target/objects/$(basename "$SOURCE" .cpp).o")
    printf '+ %q ' "${COMMAND[@]}"
    printf '\n'
    "${COMMAND[@]}"
done
