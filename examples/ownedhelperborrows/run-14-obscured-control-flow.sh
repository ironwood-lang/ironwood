#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: compile error because a branch merge obscures the borrow owner.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-invalid-scenario.sh" ObscuredControlFlow \
    "allocation has conflicting borrowed-helper ownership across control flow"
