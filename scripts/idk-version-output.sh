#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

ironwood_idk_version_output_matches() {
    local output=$1
    local expected_version=$2
    local expected_llvm_version=$3
    local expected_clang_version=$4
    local idk_root=$5
    local line
    local lines=()

    while IFS= read -r line; do
        lines+=("$line")
    done <<< "$output"

    if [[ ${#lines[@]} -ne 5 \
            || "${lines[0]}" != "ironwoodc $expected_version" \
            || "${lines[1]}" != "LLVM version: $expected_llvm_version" \
            || "${lines[2]}" != "LLVM home: "* \
            || "${lines[3]}" != "LLVM clang: "* \
            || "${lines[4]}" != "Clang version: $expected_clang_version" ]]; then
        return 1
    fi

    [[ "${lines[2]#LLVM home: }" -ef "$idk_root/toolchain" \
            && "${lines[3]#LLVM clang: }" -ef "$idk_root/toolchain/bin/clang" ]]
}
