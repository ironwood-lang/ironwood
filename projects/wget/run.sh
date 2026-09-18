#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# File paths belong to the caller; never redirect diagnostics into payload stdout.
{
    printf 'wget'
    for WGET_ARGUMENT in "$@"; do printf ' %q' "$WGET_ARGUMENT"; done
    printf '\n'
} >&2
exec "$PROJECT_DIR/target/wget" "$@"
