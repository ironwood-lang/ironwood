# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Sourced by the build scripts on macOS and Linux. Set CXX to choose the
# compiler. Otherwise the scripts prefer the LLVM 23 clang++ that ironwoodc
# finds, so the C++ and Ironwood builds share an LLVM version, and fall back
# to clang++ or c++ on PATH.

is_llvm_23() {
    local version
    version=$("$1" -dumpversion 2>/dev/null) || return 1
    [[ "$version" == 23 || "$version" == 23.* ]]
}

find_cxx() {
    local home
    local formula
    local compiler
    local homes=()

    if [[ -n "${IRONWOOD_LLVM_HOME:-}" ]]; then
        homes+=("$IRONWOOD_LLVM_HOME")
    fi
    if command -v brew >/dev/null 2>&1; then
        for formula in llvm@23 llvm; do
            home=$(brew --prefix "$formula" 2>/dev/null || true)
            if [[ -n "$home" ]]; then
                homes+=("$home")
            fi
        done
    fi
    homes+=(/opt/homebrew/opt/llvm /usr/local/opt/llvm /usr/lib/llvm-23)

    for home in "${homes[@]}"; do
        if [[ -x "$home/bin/clang++" ]] && is_llvm_23 "$home/bin/clang++"; then
            printf '%s\n' "$home/bin/clang++"
            return
        fi
    done
    for compiler in clang++-23 clang++ c++; do
        if command -v "$compiler" >/dev/null 2>&1; then
            command -v "$compiler"
            return
        fi
    done
    echo 'error: no C++ compiler found; set CXX' >&2
    return 1
}

if [[ -z "${CXX:-}" ]]; then
    CXX=$(find_cxx)
fi

# Tune for the build host, as the Ironwood and Native Image builds do. On arm64
# -march=native selects a generic core, while -mcpu=native selects the host
# core, matching the -mcpu=native that ironwoodc passes to LLVM.
case $(uname -m) in
    arm64|aarch64) CPU_FLAG=-mcpu=native ;;
    *) CPU_FLAG=-march=native ;;
esac

# Java rounds every floating-point operation separately, so the report code
# must not fuse multiplies and adds. The measured workload is integer-only.
CXX_COMPILE_FLAGS=(-std=c++17 -O3 "$CPU_FLAG" -ffp-contract=off
    -Wall -Wextra -Wpedantic -Werror -I src/main/cpp)
CXX_LINK_FLAGS=(-O3 "$CPU_FLAG")
