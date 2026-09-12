#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

SOURCES=(src/main/ironwood/org/ironwood/memorymanagement/TestMemoryManagement?.iron)
for SOURCE in "${SOURCES[@]}"; do
    CLASS_NAME=${SOURCE##*/}
    CLASS_NAME=${CLASS_NAME%.iron}
    COMMAND=(ironwoodc --link -cp target/classes \
        --main-class "org.ironwood.memorymanagement.$CLASS_NAME" \
        -o "target/$CLASS_NAME" -O3)
    printf '+'
    printf ' %q' "${COMMAND[@]}"
    printf '\n'
    "${COMMAND[@]}"
done
