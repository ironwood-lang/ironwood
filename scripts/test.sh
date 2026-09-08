#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
IRONWOOD_BUILD_DIR="$IRONWOOD_PROJECT_ROOT/compiler/build"
IRONWOOD_TEST_CLASSES_DIR="$IRONWOOD_BUILD_DIR/test-classes"
IRONWOOD_TEST_SOURCES_FILE="$IRONWOOD_BUILD_DIR/test-sources.txt"

"$IRONWOOD_SCRIPT_DIR/check-licenses.sh"
"$IRONWOOD_SCRIPT_DIR/build.sh"

rm -rf "$IRONWOOD_TEST_CLASSES_DIR"
mkdir -p "$IRONWOOD_TEST_CLASSES_DIR"
cd "$IRONWOOD_PROJECT_ROOT"
if command -v rg >/dev/null 2>&1; then
    rg --files compiler/src/test/java -g '*.java' | sort > "$IRONWOOD_TEST_SOURCES_FILE"
else
    find compiler/src/test/java -type f -name '*.java' | sort > "$IRONWOOD_TEST_SOURCES_FILE"
fi
javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
    -cp "$IRONWOOD_BUILD_DIR/classes" \
    -d "$IRONWOOD_TEST_CLASSES_DIR" \
    "@$IRONWOOD_TEST_SOURCES_FILE"

java -ea -cp "$IRONWOOD_BUILD_DIR/classes:$IRONWOOD_TEST_CLASSES_DIR" ironwood.compiler.CompilerTests "$@"
