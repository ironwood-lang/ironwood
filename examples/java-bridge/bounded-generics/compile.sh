#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
GENERIC_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GENERIC_ROOT=$(CDPATH= cd -- "$GENERIC_EXAMPLE/../../.." && pwd)
set -x
"$GENERIC_ROOT/bin/ironwoodc" --unfreed=off -d "$GENERIC_EXAMPLE/target/classes" "$GENERIC_EXAMPLE/"*.iron
