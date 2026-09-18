#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# exec lets Ctrl+C reach the native server directly.
exec "$PROJECT_DIR/target/Server" "$@"
