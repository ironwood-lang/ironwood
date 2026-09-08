#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Compiles scenario 20: failed constructor rolls back installed helper.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/compile-valid-scenario.sh" ConstructorRollback
