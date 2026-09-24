#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source "$IRONWOOD_SCRIPT_DIR/idk-version-output.sh"

IRONWOOD_TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/ironwood-idk-version.XXXXXX")
trap 'rm -r -- "$IRONWOOD_TEST_ROOT"' EXIT
IRONWOOD_IDK_ROOT="$IRONWOOD_TEST_ROOT/relocated IDK"
mkdir -p "$IRONWOOD_IDK_ROOT/toolchain/bin" "$IRONWOOD_TEST_ROOT/other/toolchain/bin"
: > "$IRONWOOD_IDK_ROOT/toolchain/bin/clang"
: > "$IRONWOOD_TEST_ROOT/other/toolchain/bin/clang"
ln -s "$IRONWOOD_IDK_ROOT" "$IRONWOOD_TEST_ROOT/alias IDK"

version_output() {
    local clang_home=${5:-$3}
    printf 'ironwoodc %s\nLLVM version: %s\nLLVM home: %s\nLLVM clang: %s/bin/clang\nClang version: %s' \
        "$1" "$2" "$3" "$clang_home" "$4"
}

expect_match() {
    if ! ironwood_idk_version_output_matches "$1" 0.5.3 23.1.0 \
            'clang version 23.1.0' "$IRONWOOD_IDK_ROOT"; then
        echo "error: equivalent packaged version output was rejected" >&2
        exit 1
    fi
}

expect_mismatch() {
    if ironwood_idk_version_output_matches "$1" 0.5.3 23.1.0 \
            'clang version 23.1.0' "$IRONWOOD_IDK_ROOT"; then
        echo "error: incorrect packaged version output was accepted" >&2
        exit 1
    fi
}

expect_match "$(version_output 0.5.3 23.1.0 "$IRONWOOD_IDK_ROOT/toolchain" \
    'clang version 23.1.0')"
expect_match "$(version_output 0.5.3 23.1.0 "$IRONWOOD_TEST_ROOT/alias IDK/toolchain" \
    'clang version 23.1.0')"
expect_mismatch "$(version_output 0.5.4 23.1.0 "$IRONWOOD_IDK_ROOT/toolchain" \
    'clang version 23.1.0')"
expect_mismatch "$(version_output 0.5.3 22.1.0 "$IRONWOOD_IDK_ROOT/toolchain" \
    'clang version 23.1.0')"
expect_mismatch "$(version_output 0.5.3 23.1.0 "$IRONWOOD_IDK_ROOT/toolchain" \
    'clang version 22.1.0')"
expect_mismatch "$(version_output 0.5.3 23.1.0 "$IRONWOOD_TEST_ROOT/other/toolchain" \
    'clang version 23.1.0')"
expect_mismatch "$(version_output 0.5.3 23.1.0 "$IRONWOOD_IDK_ROOT/toolchain" \
    'clang version 23.1.0' "$IRONWOOD_TEST_ROOT/other/toolchain")"
expect_mismatch "$(version_output 0.5.3 23.1.0 "$IRONWOOD_IDK_ROOT/toolchain" \
    'clang version 23.1.0')"$'\nextra line'

echo "packaged IDK version output checks passed"
