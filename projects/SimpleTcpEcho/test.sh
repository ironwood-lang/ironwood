#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$PROJECT_DIR/compile.sh"
"$PROJECT_DIR/link.sh"
exec python3 "$PROJECT_DIR/src/test/python/test_echo.py"
