#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: native program exits 42 after a published partial graph survives collection.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-valid-scenario.sh" ConstructorRollback
