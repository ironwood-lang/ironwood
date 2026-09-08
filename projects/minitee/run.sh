#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Preserve the caller's working directory and stdout bytes, including NUL.
exec "$PROJECT_DIR/target/minitee" "$@"
