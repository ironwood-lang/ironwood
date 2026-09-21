#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

./compile.sh

rm -rf target/native-image
mkdir -p target/native-image

NATIVE_IMAGE_OPTIONS=(--no-fallback -O3 -march=native --gc=epsilon
    -cp target/classes)

run_command() {
    printf '+ %q ' "$@"
    printf '\n'
    "$@"
}

build_image() {
    local main_class=$1
    local output=$2
    run_command native-image "${NATIVE_IMAGE_OPTIONS[@]}" \
        -o "$output" "$main_class"
}

build_profiled_image() {
    local main_class=$1
    local output=$2
    shift 2
    local instrumented="$output-instrumented"
    local profile="$output.iprof"
    local profile_option="-XX:ProfilesDumpFile=$profile"
    local -a training_command=("$instrumented" "$profile_option" "$@")

    run_command native-image "${NATIVE_IMAGE_OPTIONS[@]}" --pgo-instrument \
        -o "$instrumented" "$main_class"
    printf '+ %q ' "${training_command[@]}"
    printf '\n'
    "${training_command[@]}" >/dev/null
    run_command native-image "${NATIVE_IMAGE_OPTIONS[@]}" "--pgo=$profile" \
        -o "$output" "$main_class"

    rm -f -- "$instrumented" "$profile"
}

# These programs allocate bounded state before their measured loops. Epsilon
# avoids collection work while -O3 and -march=native optimize for this host.
build_image org.ironwood.orderbook.Main target/native-image/orderbook

# Train each benchmark separately so its final executable receives profiles for
# both the shared workload and its own driver. Training output is discarded.
build_profiled_image org.ironwood.orderbook.Bench \
    target/native-image/orderbook-bench 1 10
build_profiled_image org.ironwood.orderbook.LatencyBench \
    target/native-image/orderbook-latency 100 1000 1000
