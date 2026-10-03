#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$EXAMPLE_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
cd "$EXAMPLE_DIR"
set -x
ironwoodc --source-path src/main/ironwood -d target/classes --unfreed=error \
    src/main/ironwood/org/ironwood/javabridge/listeners/*.iron
