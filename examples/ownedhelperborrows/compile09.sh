#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Historical scenario: reject removed reclamation syntax with a migration diagnostic.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/compile-invalid-scenario.sh" UnknownRetainingCall \
    "source free has been removed"
