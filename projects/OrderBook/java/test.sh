#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

mkdir -p target/test-classes
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp target/classes \
    -d target/test-classes src/test/java/org/ironwood/orderbook/BenchmarkTests.java
java -cp target/classes:target/test-classes org.ironwood.orderbook.BenchmarkTests

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
printf 'PASS: Java throughput and latency command-line checks\n'
