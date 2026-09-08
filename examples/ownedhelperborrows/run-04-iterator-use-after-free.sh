#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: compile error for iterator use after owner destruction.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-invalid-scenario.sh" IteratorUseAfterFree \
    "cannot use 'iterator' after its allocation was freed"
