#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

SOURCES=(src/main/ironwood/org/ironwood/memorymanagement/TestMemoryManagement?.iron)
for SOURCE in "${SOURCES[@]}"; do
    COMMAND=(ironwoodc --source-path src/main/ironwood -d target/classes "$SOURCE")
    printf '+'
    printf ' %q' "${COMMAND[@]}"
    printf '\n'
    "${COMMAND[@]}"
done
