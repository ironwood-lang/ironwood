#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Verifies scenario 17: live ordinary alias blocks owner free.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/compile-invalid-scenario.sh" LiveOwnerAlias \
    "allocation may still be observed through local 'alias'"
