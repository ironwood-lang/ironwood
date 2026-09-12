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
        "$PROJECT_DIR/../../compiler/build/ironwood-testing.ironjar" \
        "$PROJECT_DIR/../../lib/ironwood-testing.ironjar" \
        "${IRONWOOD_HOME:-}/lib/ironwood-testing.ironjar" \
        "${IRONWOOD_HOME:-}/compiler/build/ironwood-testing.ironjar"; do
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
    src/test/ironwood/org/ironwood/orderbook/BenchmarkTests.iron
    src/test/ironwood/org/ironwood/orderbook/LatencyReportChecks.iron)
printf '+ %q ' "${COMPILE[@]}"
printf '\n'
"${COMPILE[@]}"

LINK=(ironwoodc --link -cp "$IRONWOOD_TESTING_ARCHIVE:target/test-classes"
    --main-class org.ironwood.orderbook.BenchmarkTests -o target/benchmark-tests -O3)
printf '+ %q ' "${LINK[@]}"
printf '\n'
"${LINK[@]}"

output=$(./target/benchmark-tests)
printf '%s\n' "$output"
[[ "$output" == *'PASS: 4 passed, 0 skipped, 4 total' ]]

# Executable checks use small workloads; elapsed values are machine-dependent.
output=$(./throughput.sh 0 1)
[[ "$output" =~ ^[0-9]+$ && "$output" -gt 0 ]]
output=$(./latency.sh 2 5 10)
[[ "$output" == *'Cycles per batch: 10'* ]]
[[ "$output" == *'Operations per batch: 80'* ]]
[[ "$output" == *'Measured operations: 400'* ]]
[[ "$output" == *'Measurements: 5 | Warm-Up: 2 | Iterations: 7'* ]]
output=$(./latency.sh 0 1 1)
[[ "$output" == *'Measurements: 1 | Warm-Up: 0 | Iterations: 1'* ]]
for arguments in '-1 1 1' '0 0 1' '0 1 0' '2147483647 1 1' '0 2147483647 2147483647'; do
    read -r warmup measurements cycles <<< "$arguments"
    if ./latency.sh "$warmup" "$measurements" "$cycles" >target/invalid-latency.log 2>&1; then
        printf 'error: accepted invalid latency arguments: %s\n' "$arguments" >&2
        exit 1
    fi
done
printf 'PASS: throughput and latency command-line checks\n'

./java/compile.sh
./java/test.sh
ironwoodc --link -cp "$IRONWOOD_TESTING_ARCHIVE:target/test-classes" \
    --main-class org.ironwood.orderbook.LatencyReportChecks -o target/latency-report-checks -O3
./target/latency-report-checks > target/native-latency-reports.txt
java -cp java/target/classes:java/target/test-classes \
    org.ironwood.orderbook.BenchmarkTests reports > target/java-latency-reports.txt
diff -u target/native-latency-reports.txt target/java-latency-reports.txt
printf 'PASS: native and Java latency reports match\n'
