#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Verifies scenario 4: iterator use after owner destruction is rejected.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/compile-invalid-scenario.sh" IteratorUseAfterFree \
    "cannot use 'iterator' after its allocation was freed"
