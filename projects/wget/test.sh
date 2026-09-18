#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$PROJECT_DIR/../../scripts/test-networking-m6.py" "$@"
