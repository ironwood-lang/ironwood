#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: native program exits 42; the element survives collection after its list is dropped.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-valid-scenario.sh" InsertedElementSurvives
