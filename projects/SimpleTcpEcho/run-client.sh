#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Keep stdout exclusively for the server's reply.
exec "$PROJECT_DIR/target/Client" "$@"
