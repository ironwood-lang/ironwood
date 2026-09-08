#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: compile error because the reusable holder borrow escapes globally.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-invalid-scenario.sh" EscapedNestedHolder \
    "EscapedNestedHolder.saved"
