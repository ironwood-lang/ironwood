#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Preserve the caller's directory so input/output names behave like ordinary CLI
# tools. Keep all script diagnostics off stdout to preserve binary pipelines.
exec "$PROJECT_DIR/target/streaming" "$@"
