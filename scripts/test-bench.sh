#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail
BENCH_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$BENCH_ROOT"
if [[ ${1:-} == --skip-build ]]; then
    shift
else
    ./scripts/build.sh
fi
if [[ $# -ne 0 ]]; then
    echo 'usage: scripts/test-bench.sh [--skip-build]' >&2
    exit 2
fi
BENCH_TARGET="$BENCH_ROOT/stdlib/test/target/bench"
BENCH_CLASSES="$BENCH_TARGET/classes"
BENCH_TESTING="$BENCH_ROOT/compiler/build/ironwood-testing.ironjar"
BENCH_COMPILER="$BENCH_ROOT/compiler/build/ironwoodc.jar"
mkdir -p "$BENCH_CLASSES"
java -jar "$BENCH_COMPILER" --source-path stdlib/test -d "$BENCH_CLASSES" \
    -cp "$BENCH_TESTING" --unfreed=error \
    stdlib/test/ironwood/bench/BenchTests.iron \
    stdlib/test/ironwood/bench/NanoBenchTests.iron \
    integration-tests/cases/bench_native_checks.iron >/dev/null
for suite in BenchTests NanoBenchTests; do
    java -jar "$BENCH_COMPILER" --link -cp "$BENCH_TESTING:$BENCH_CLASSES" \
        --main-class "ironwood.bench.$suite" -o "$BENCH_TARGET/$suite" -O3 --unfreed=error >/dev/null
    if ! output=$("$BENCH_TARGET/$suite"); then
        printf '%s\n' "$output" >&2
        exit 1
    fi
    printf '%s\n' "$output"
    if [[ "$suite" == BenchTests ]]; then
        [[ "$output" == *'PASS: 18 passed, 0 skipped, 18 total' ]]
    else
        [[ "$output" == *'PASS: 6 passed, 0 skipped, 6 total' ]]
    fi
done
java -jar "$BENCH_COMPILER" --link -cp "$BENCH_CLASSES" \
    --main-class BenchNativeChecks -o "$BENCH_TARGET/native-checks" -O3 --unfreed=error >/dev/null
output=$("$BENCH_TARGET/native-checks" output)
expected=$'\n==BenchVerbose==> Iteration 1 => 999\n\n==BenchVerbose==> Iteration 2 => 10\nMeasurements: 2 | Warm-Up: 1 | Iterations: 3\nAvg Time: 10.500 nanos | Min Time: 10.000 nanos | Max Time: 11.000 nanos\n\nMeasurements: 2 | Avg Time: 10 nanos | Min Time: 10 nanos | Max Time: 11 nanos'
[[ "$output" == "$expected" ]] || { printf 'Unexpected benchmark output:\n%s\n' "$output" >&2; exit 1; }
# Fresh processes cover partial construction, histogram/list growth, and report
# allocations. Reclamation is checked inside the program after each failure.
for limit in {0..48}; do
    for mode in bench-failure nano-failure; do
        if ! IRONWOOD_ALLOCATION_LIMIT=$limit "$BENCH_TARGET/native-checks" "$mode"; then
            printf 'error: %s failed with allocation limit %d\n' "$mode" "$limit" >&2
            exit 1
        fi
    done
done
printf 'PASS: benchmark output, zero-allocation printing, and 98 allocation-failure checks\n'
