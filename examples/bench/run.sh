#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

# Small smoke workloads keep the example catalog quick. Run target executables
# directly with larger counts for measurement; elapsed timings are not asserted.
for program in SleepBenchmark MathBenchmark BubbleSortBenchmark IntMapBenchmark; do
    printf '+ ./target/%s 2 20 16\n' "$program"
    output=$("./target/$program" 2 20 16)
    printf '%s\nexit status: 0\n' "$output"
    [[ "$output" == *"Measurements: 20 | Warm-Up: 2 | Iterations: 22"* ]]
    case "$program" in
        MathBenchmark) [[ "$output" == *"Value computed: -1030000"* ]] ;;
        BubbleSortBenchmark) [[ "$output" == *"Value computed: 40260"* ]] ;;
        IntMapBenchmark) [[ "$output" == *"Values found: 22"* ]] ;;
    esac
done
printf '+ ./target/NanoBenchExample 20\n'
output=$(./target/NanoBenchExample 20)
printf '%s\nexit status: 0\n' "$output"
[[ "$output" == "Measurements: 20 | Avg Time: "* ]]
