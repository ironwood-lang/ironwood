#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: compile error because polymorphic dispatch may retain the borrow.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-invalid-scenario.sh" UnknownRetainingCall \
    "cannot prove argument 1 of polymorphic method 'accept' does not escape"
