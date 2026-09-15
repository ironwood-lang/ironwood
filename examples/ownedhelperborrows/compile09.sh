#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Verifies scenario 9: a retaining dispatch target blocks owner free.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/compile-invalid-scenario.sh" UnknownRetainingCall \
    "cannot free 'list': allocation escapes through argument 1 of method 'accept'"
