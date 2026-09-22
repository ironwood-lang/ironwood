#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source "$PROJECT_DIR/toolchain.sh"
TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/orderbook-toolchain.XXXXXX")
trap '/bin/rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/bin" "$TEST_DIR/selected LLVM/bin" "$TEST_DIR/other LLVM/bin"
export ORDERBOOK_TOOLCHAIN_REPORT="$TEST_DIR/report"
export ORDERBOOK_TOOLCHAIN_STATUS=0
cat > "$TEST_DIR/bin/ironwoodc" <<'LAUNCHER'
#!/bin/sh
[ "$#" -eq 1 ] && [ "$1" = '-v' ] || exit 2
cat "$ORDERBOOK_TOOLCHAIN_REPORT"
exit "$ORDERBOOK_TOOLCHAIN_STATUS"
LAUNCHER
printf '#!/bin/sh\nexit 0\n' > "$TEST_DIR/selected LLVM/bin/clang"
chmod +x "$TEST_DIR/bin/ironwoodc" "$TEST_DIR/selected LLVM/bin/clang"
export PATH="$TEST_DIR/bin:$PATH"
export IRONWOOD_LLVM_HOME="$TEST_DIR/other LLVM"

write_selection() {
    printf 'ironwoodc test\nLLVM version: %s\nLLVM home: %s\nLLVM clang: %s/bin/clang\nClang version: Vendor clang version 23.1.0\n' \
        "$1" "$TEST_DIR/selected LLVM" "$TEST_DIR/selected LLVM" > "$ORDERBOOK_TOOLCHAIN_REPORT"
}

expect_failure() {
    if find_llvm_cxx > "$TEST_DIR/output" 2> "$TEST_DIR/error"; then
        echo "error: toolchain discovery unexpectedly accepted $1" >&2
        exit 1
    fi
    [[ ! -s "$TEST_DIR/output" ]]
}

# The launcher's choice wins over a conflicting environment; spaces survive.
write_selection 23.1.0
selected=$(find_llvm_cxx 2> "$TEST_DIR/details")
[[ "$selected" == "$TEST_DIR/selected LLVM/bin/clang" ]]
[[ "$(cat "$TEST_DIR/details")" == "$(cat "$ORDERBOOK_TOOLCHAIN_REPORT")" ]]

write_selection 22.1.0
expect_failure 'an unsupported LLVM major'
printf 'ironwoodc test\nLLVM not found: invalid configured directory\n' > "$ORDERBOOK_TOOLCHAIN_REPORT"
expect_failure 'unavailable LLVM'
printf 'ironwoodc test\n' > "$ORDERBOOK_TOOLCHAIN_REPORT"
expect_failure 'an older compiler without LLVM details'
write_selection 23.1.0
chmod -x "$TEST_DIR/selected LLVM/bin/clang"
expect_failure 'a missing selected executable'
chmod +x "$TEST_DIR/selected LLVM/bin/clang"
export ORDERBOOK_TOOLCHAIN_STATUS=1
expect_failure 'a failing compiler query'
export PATH="$TEST_DIR/empty-path"
expect_failure 'a missing ironwoodc'
# Restore utilities needed by the exit trap.
export PATH=/usr/bin:/bin
printf 'PASS: C++ toolchain selection follows ironwoodc and rejects invalid discovery\n'
