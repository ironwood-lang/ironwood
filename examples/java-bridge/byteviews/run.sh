#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
BYTEVIEW_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$BYTEVIEW_EXAMPLE/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
java -Xcheck:jni -cp "$BYTEVIEW_EXAMPLE/target/byteviews.jar:$BYTEVIEW_EXAMPLE/target/ironwood-bridge-values.jar:$BYTEVIEW_EXAMPLE/target/consumer" Consumer
