#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
BYTEVIEW_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$BYTEVIEW_EXAMPLE/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
BYTEVIEW_ROOT=$(CDPATH= cd -- "$BYTEVIEW_EXAMPLE/../../.." && pwd)
"$BYTEVIEW_ROOT/bin/ironwoodc" --unfreed=off -d "$BYTEVIEW_EXAMPLE/target/classes" "$BYTEVIEW_EXAMPLE/ByteOps.iron"
