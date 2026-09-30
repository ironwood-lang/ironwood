#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
GENERIC_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$GENERIC_EXAMPLE/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
GENERIC_ROOT=$(CDPATH= cd -- "$GENERIC_EXAMPLE/../../.." && pwd)
set -x
"$GENERIC_ROOT/bin/ironwoodc" --unfreed=off -d "$GENERIC_EXAMPLE/target/classes" "$GENERIC_EXAMPLE/"*.iron
