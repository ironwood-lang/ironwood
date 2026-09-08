#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Compiles and runs the native test executable. The suite class is its own main
# class, so there is no separate test runner to install or configure.

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

# The testing archive lives beside the compiler in an extracted IDK and under
# the build directory in a source checkout.
IRONWOOD_TESTING_ARCHIVE=""
for CANDIDATE in \
        "${IRONWOOD_HOME:-}/lib/ironwood-testing.ironjar" \
        "${IRONWOOD_HOME:-}/compiler/build/ironwood-testing.ironjar" \
        "$PROJECT_DIR/../../compiler/build/ironwood-testing.ironjar" \
        "$PROJECT_DIR/../../lib/ironwood-testing.ironjar"; do
    if [[ -f "$CANDIDATE" ]]; then
        IRONWOOD_TESTING_ARCHIVE=$(CDPATH= cd -- "$(dirname -- "$CANDIDATE")" && pwd)/$(basename "$CANDIDATE")
        break
    fi
done

if [[ -z "$IRONWOOD_TESTING_ARCHIVE" ]]; then
    echo "error: ironwood-testing.ironjar not found; set IRONWOOD_HOME" >&2
    exit 1
fi

COMPILE=(ironwoodc -cp "$IRONWOOD_TESTING_ARCHIVE"
    --source-path "src/main/ironwood:src/test/ironwood"
    -d target/test-classes
    src/test/ironwood/org/ironwood/helloeclipse/GreetingTests.iron)
printf '+ %q ' "${COMPILE[@]}"
printf '\n'
"${COMPILE[@]}"

LINK=(ironwoodc --link -cp "$IRONWOOD_TESTING_ARCHIVE:target/test-classes"
    --main-class org.ironwood.helloeclipse.GreetingTests -o target/greeting-tests -O3)
printf '+ %q ' "${LINK[@]}"
printf '\n'
"${LINK[@]}"

printf '%s\n' '+ ./target/greeting-tests'
./target/greeting-tests
