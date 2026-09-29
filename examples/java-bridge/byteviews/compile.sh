#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
BYTEVIEW_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BYTEVIEW_ROOT=$(CDPATH= cd -- "$BYTEVIEW_EXAMPLE/../../.." && pwd)
"$BYTEVIEW_ROOT/bin/ironwoodc" --unfreed=off -d "$BYTEVIEW_EXAMPLE/target/classes" "$BYTEVIEW_EXAMPLE/ByteOps.iron"
