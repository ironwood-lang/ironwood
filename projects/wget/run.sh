#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# File paths belong to the caller; never redirect diagnostics into payload stdout.
exec "$PROJECT_DIR/target/wget" "$@"
