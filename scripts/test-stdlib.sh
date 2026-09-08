#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_TEST_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_TEST_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_TEST_SCRIPT_DIR/.." && pwd)
IRONWOOD_TEST_COMPILER="$IRONWOOD_TEST_PROJECT_ROOT/compiler/build/ironwoodc.jar"
IRONWOOD_TEST_ROOT="$IRONWOOD_TEST_PROJECT_ROOT/stdlib/test"
IRONWOOD_TESTING_ARCHIVE="$IRONWOOD_TEST_PROJECT_ROOT/compiler/build/ironwood-testing.ironjar"
IRONWOOD_TEST_TARGET="$IRONWOOD_TEST_ROOT/target"
IRONWOOD_TEST_EXECUTABLE="$IRONWOOD_TEST_TARGET/standard-library-tests"
IRONWOOD_TEST_MESSAGE_ORDER_SOURCE="$IRONWOOD_TEST_ROOT/negative/ironwood/testing/MessageFirstAssertions.iron"
IRONWOOD_TEST_SKIP_BUILD=false

if [[ ${1:-} == "--skip-build" ]]; then
    IRONWOOD_TEST_SKIP_BUILD=true
    shift
fi
if [[ $# -ne 0 ]]; then
    echo "usage: scripts/test-stdlib.sh [--skip-build]" >&2
    exit 2
fi

if [[ "$IRONWOOD_TEST_SKIP_BUILD" == false ]]; then
    "$IRONWOOD_TEST_SCRIPT_DIR/build.sh"
fi

mkdir -p "$IRONWOOD_TEST_TARGET"

compile_suites() {
    local classes="$IRONWOOD_TEST_TARGET/classes"

    rm -rf "$classes"
    mkdir -p "$classes"
    java -jar "$IRONWOOD_TEST_COMPILER" "$IRONWOOD_TEST_ROOT/ironwood/testing/StandardLibraryTests.iron" \
        -d "$classes" --source-path "$IRONWOOD_TEST_ROOT" \
        -cp "$IRONWOOD_TESTING_ARCHIVE" >/dev/null
    java -jar "$IRONWOOD_TEST_COMPILER" --link \
        -cp "$IRONWOOD_TESTING_ARCHIVE:$classes" \
        --main-class ironwood.testing.StandardLibraryTests -o "$IRONWOOD_TEST_EXECUTABLE" -O3 >/dev/null
}

verify_message_order() {
    local classes="$IRONWOOD_TEST_TARGET/message-order-negative"
    local output
    local status

    rm -rf "$classes"
    mkdir -p "$classes"
    set +e
    output=$(java -jar "$IRONWOOD_TEST_COMPILER" "$IRONWOOD_TEST_MESSAGE_ORDER_SOURCE" \
        -d "$classes" -cp "$IRONWOOD_TESTING_ARCHIVE" 2>&1)
    status=$?
    set -e
    if [[ $status -ne 1 ]]; then
        echo "error: message-first testing calls exited $status, expected compilation failure" >&2
        printf '%s\n' "$output" >&2
        exit 1
    fi
    for method in assertTrue assertEquals assumeTrue; do
        if [[ "$output" != *"no applicable method '$method'"* ]]; then
            echo "error: missing message-first $method diagnostic" >&2
            printf '%s\n' "$output" >&2
            exit 1
        fi
    done
}

capture_suite_output() {
    local suite_name=$1

    set +e
    IRONWOOD_TEST_CAPTURED_OUTPUT=$("$IRONWOOD_TEST_EXECUTABLE" "$suite_name" 2>&1)
    IRONWOOD_TEST_CAPTURED_STATUS=$?
    set -e
}

printf 'RUN - Testing framework\n'
compile_suites
verify_message_order
capture_suite_output framework-pass
IRONWOOD_TEST_PASS_OUTPUT=$IRONWOOD_TEST_CAPTURED_OUTPUT
IRONWOOD_TEST_EXPECTED_PASS=$'RUN - assertionsPass\nok - assertionsPass\nRUN - beforeEachRuns\nok - beforeEachRuns\nRUN - exactExceptionCheckPasses\nok - exactExceptionCheckPasses\nRUN - assumptionSkip\nskip - assumptionSkip: optional capability is unavailable\nPASS: 3 passed, 1 skipped, 4 total'
if [[ $IRONWOOD_TEST_CAPTURED_STATUS -ne 0 || "$IRONWOOD_TEST_PASS_OUTPUT" != "$IRONWOOD_TEST_EXPECTED_PASS" ]]; then
    echo "error: passing framework output changed" >&2
    printf '%s\n' "$IRONWOOD_TEST_PASS_OUTPUT" >&2
    exit 1
fi
IRONWOOD_TEST_PASS_SUMMARY=${IRONWOOD_TEST_PASS_OUTPUT##*$'\n'}

printf 'RUN - Testing framework expected failures\n'
capture_suite_output framework-failure
IRONWOOD_TEST_FAILURE_OUTPUT=$IRONWOOD_TEST_CAPTURED_OUTPUT
IRONWOOD_TEST_FAILURE_STATUS=$IRONWOOD_TEST_CAPTURED_STATUS
IRONWOOD_TEST_EXPECTED_FAILURE=$'RUN - intentionalAssertionFailure\nnot ok - intentionalAssertionFailure: expected mismatch: expected <1> but was <2>\nRUN - intentionalCharacterFailure\nnot ok - intentionalCharacterFailure: character values differ: expected <a> but was <b>\nRUN - continuesAfterFailure\nok - continuesAfterFailure\nRUN - unexpectedThrowable\nnot ok - unexpectedThrowable: unexpected failure\nFAIL: 3 failed, 1 passed, 0 skipped, 4 total'
if [[ $IRONWOOD_TEST_FAILURE_STATUS -ne 1 ]]; then
    echo "error: failing framework fixture exited $IRONWOOD_TEST_FAILURE_STATUS, expected 1" >&2
    printf '%s\n' "$IRONWOOD_TEST_FAILURE_OUTPUT" >&2
    exit 1
fi
if [[ "$IRONWOOD_TEST_FAILURE_OUTPUT" != "$IRONWOOD_TEST_EXPECTED_FAILURE" ]]; then
    echo "error: failing framework output changed" >&2
    printf '%s\n' "$IRONWOOD_TEST_FAILURE_OUTPUT" >&2
    exit 1
fi

printf 'RUN - Pool behavior\n'
capture_suite_output pool
IRONWOOD_TEST_POOL_OUTPUT=$IRONWOOD_TEST_CAPTURED_OUTPUT
IRONWOOD_TEST_POOL_SUMMARY=${IRONWOOD_TEST_POOL_OUTPUT##*$'\n'}
if [[ $IRONWOOD_TEST_CAPTURED_STATUS -ne 0 || "$IRONWOOD_TEST_POOL_SUMMARY" != "PASS: 24 passed, 0 skipped, 24 total" ]]; then
    echo "error: migrated pool suite failed" >&2
    printf '%s\n' "$IRONWOOD_TEST_POOL_OUTPUT" >&2
    exit 1
fi

printf 'RUN - Pool destruction\n'
capture_suite_output pool-destruction
IRONWOOD_TEST_POOL_DESTRUCTION_OUTPUT=$IRONWOOD_TEST_CAPTURED_OUTPUT
IRONWOOD_TEST_POOL_DESTRUCTION_SUMMARY=${IRONWOOD_TEST_POOL_DESTRUCTION_OUTPUT##*$'\n'}
if [[ $IRONWOOD_TEST_CAPTURED_STATUS -ne 0 || "$IRONWOOD_TEST_POOL_DESTRUCTION_SUMMARY" != "PASS: 10 passed, 0 skipped, 10 total" ]]; then
    echo "error: pool destruction suite failed" >&2
    printf '%s\n' "$IRONWOOD_TEST_POOL_DESTRUCTION_OUTPUT" >&2
    exit 1
fi

printf 'RUN - Data-structure behavior\n'
capture_suite_output data-structures
IRONWOOD_TEST_DS_OUTPUT=$IRONWOOD_TEST_CAPTURED_OUTPUT
IRONWOOD_TEST_DS_SUMMARY=${IRONWOOD_TEST_DS_OUTPUT##*$'\n'}
if [[ $IRONWOOD_TEST_CAPTURED_STATUS -ne 0 || "$IRONWOOD_TEST_DS_SUMMARY" != "PASS: 46 passed, 0 skipped, 46 total" ]]; then
    echo "error: migrated data-structure suite failed" >&2
    printf '%s\n' "$IRONWOOD_TEST_DS_OUTPUT" >&2
    exit 1
fi

printf 'RUN - Data-structure destruction\n'
capture_suite_output data-structure-destruction
IRONWOOD_TEST_DS_DESTRUCTION_OUTPUT=$IRONWOOD_TEST_CAPTURED_OUTPUT
IRONWOOD_TEST_DS_DESTRUCTION_SUMMARY=${IRONWOOD_TEST_DS_DESTRUCTION_OUTPUT##*$'\n'}
if [[ $IRONWOOD_TEST_CAPTURED_STATUS -ne 0 || "$IRONWOOD_TEST_DS_DESTRUCTION_SUMMARY" != "PASS: 24 passed, 0 skipped, 24 total" ]]; then
    echo "error: data-structure destruction suite failed" >&2
    printf '%s\n' "$IRONWOOD_TEST_DS_DESTRUCTION_OUTPUT" >&2
    exit 1
fi

printf '\nStandard library test summary\n'
printf 'ok - Testing framework: %s\n' "${IRONWOOD_TEST_PASS_SUMMARY#PASS: }"
printf 'ok - Testing framework expected failures: intentional failure reporting verified\n'
printf 'ok - Pool behavior: %s\n' "${IRONWOOD_TEST_POOL_SUMMARY#PASS: }"
printf 'ok - Pool destruction: %s\n' "${IRONWOOD_TEST_POOL_DESTRUCTION_SUMMARY#PASS: }"
printf 'ok - Data-structure behavior: %s\n' "${IRONWOOD_TEST_DS_SUMMARY#PASS: }"
printf 'ok - Data-structure destruction: %s\n' "${IRONWOOD_TEST_DS_DESTRUCTION_SUMMARY#PASS: }"
printf 'TOTAL: 107 passed, 1 skipped, 108 total across 5 test suites\n'
printf 'PASS: all 6 standard-library suite checks passed\n'
