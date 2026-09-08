#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Verifies scenario 12: publishable backlink rejects owner reclamation.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/compile-invalid-scenario.sh" PublishedBacklink \
    "allocation escapes from constructor 'org.ironwood.ownedhelperborrows.PublishingOwner'"
