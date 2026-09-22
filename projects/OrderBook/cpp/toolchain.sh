#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

# Sourced by compile.sh, link.sh, and test.sh. Ask the launcher so IDK overrides,
# environment validation, and fallback discovery have exactly one authority.
find_llvm_cxx() {
    local details line llvm_home='' llvm_version='' compiler=''
    if ! command -v ironwoodc >/dev/null 2>&1; then
        echo 'error: ironwoodc is required to select the comparison toolchain; add its bin directory to PATH' >&2
        return 1
    fi
    if ! details=$(ironwoodc -v); then
        printf '%s\n' "$details" >&2
        echo 'error: ironwoodc -v failed while selecting the LLVM toolchain' >&2
        return 1
    fi
    printf '%s\n' "$details" >&2
    while IFS= read -r line; do
        case "$line" in
            'LLVM version: '*) llvm_version=${line#'LLVM version: '} ;;
            'LLVM home: '*) llvm_home=${line#'LLVM home: '} ;;
            'LLVM clang: '*) compiler=${line#'LLVM clang: '} ;;
        esac
    done <<< "$details"
    if [[ "$llvm_version" != 23.* || -z "$llvm_home" \
            || "$compiler" != "$llvm_home/bin/clang" || ! -x "$compiler" ]]; then
        echo 'error: ironwoodc -v did not report a usable LLVM 23 toolchain; update ironwoodc or fix its LLVM configuration' >&2
        return 1
    fi
    # Use the exact Clang executable selected by Ironwood, in C++ driver mode.
    printf '%s\n' "$compiler"
}
