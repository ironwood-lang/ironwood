#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail
IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
cd "$IRONWOOD_PROJECT_ROOT"

# Build only if the documentation launcher finds a missing or stale compiler.
./bin/irondoc --version >/dev/null
IRONWOOD_DOC_TEST_CLASSES=compiler/build/irondoc-test-classes
mkdir -p "$IRONWOOD_DOC_TEST_CLASSES"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
    -cp compiler/build/classes -d "$IRONWOOD_DOC_TEST_CLASSES" \
    compiler/src/test/java/ironwood/compiler/IronDocTests.java
java -ea -cp "compiler/build/classes:$IRONWOOD_DOC_TEST_CLASSES" ironwood.compiler.IronDocTests
python3 scripts/test-irondocs.py
./scripts/update-irondocs.sh --check
