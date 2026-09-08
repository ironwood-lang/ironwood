#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Verifies scenario 7: owner double-free is rejected.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/compile-invalid-scenario.sh" DoubleFreeOwner \
    "allocation was already freed"
