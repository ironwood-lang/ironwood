#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

MAIN_CLASSES=(
    org.ironwood.trycatchfinallyexception.Case1BodyCompletesFinallyCompletes
    org.ironwood.trycatchfinallyexception.Case2BodyThrowsFinallyCompletes
    org.ironwood.trycatchfinallyexception.Case3BodyCompletesFinallyThrows
    org.ironwood.trycatchfinallyexception.Case4BodyThrowsFinallyThrows
)
EXECUTABLES=(
    Case1BodyCompletesFinallyCompletes
    Case2BodyThrowsFinallyCompletes
    Case3BodyCompletesFinallyThrows
    Case4BodyThrowsFinallyThrows
)

for INDEX in "${!MAIN_CLASSES[@]}"; do
    COMMAND=(ironwoodc --link -cp target/classes \
        --main-class "${MAIN_CLASSES[$INDEX]}" \
        -o "target/${EXECUTABLES[$INDEX]}" -O3)
    printf '+'
    printf ' %q' "${COMMAND[@]}"
    printf '\n'
    "${COMMAND[@]}"
done
