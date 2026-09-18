#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
for PROGRAM in Server Client; do
    COMMAND=(ironwoodc --link -cp target/classes
        --main-class "org.ironwood.simpletcpecho.$PROGRAM"
        -o "target/$PROGRAM" -O3 --unfreed=error)
    printf '+' >&2
    printf ' %q' "${COMMAND[@]}" >&2
    printf '\n' >&2
    "${COMMAND[@]}"
done
