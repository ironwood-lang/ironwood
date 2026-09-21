#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

./compile.sh

rm -rf target/native-image
mkdir -p target/native-image

NATIVE_IMAGE_OPTIONS=(--no-fallback -O3 -march=native --gc=epsilon
    -H:+UnlockExperimentalVMOptions -H:-MLProfileInference
    -H:-UnlockExperimentalVMOptions
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

# These programs allocate bounded state before their measured loops. Epsilon
# avoids collection work while -O3 and -march=native optimize for this host.
build_image org.ironwood.orderbook.Main target/native-image/orderbook
build_image org.ironwood.orderbook.Bench target/native-image/orderbook-bench
build_image org.ironwood.orderbook.LatencyBench target/native-image/orderbook-latency
