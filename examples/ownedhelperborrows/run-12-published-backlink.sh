#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Expected: compile error because the helper can publish its owner backlink.
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$EXAMPLE_DIR/run-invalid-scenario.sh" PublishedBacklink \
    "allocation escapes from constructor 'org.ironwood.ownedhelperborrows.PublishingOwner'"
