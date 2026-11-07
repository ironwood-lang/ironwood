#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: native exit 42; shared elements follow managed reachability.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-valid-scenario.sh" FreeInsertedElement
