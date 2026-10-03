#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
BYTEVIEW_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$BYTEVIEW_EXAMPLE/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
ironwood_require_jdk
BYTEVIEW_ROOT=$(CDPATH= cd -- "$BYTEVIEW_EXAMPLE/../../.." && pwd)
"$BYTEVIEW_ROOT/bin/ironwoodc" --java-bridge --export bytebench --unfreed=off -O3 \
    --license "$BYTEVIEW_ROOT/LICENSE-MIT" --license "$BYTEVIEW_ROOT/LICENSE-APACHE" \
    -cp "$BYTEVIEW_EXAMPLE/target/classes" -o "$BYTEVIEW_EXAMPLE/target/byteviews.jar"
javac --release 21 -Xlint:all -Werror -cp "$BYTEVIEW_EXAMPLE/target/byteviews.jar:$BYTEVIEW_EXAMPLE/target/ironwood-bridge-values.jar" \
    -d "$BYTEVIEW_EXAMPLE/target/consumer" "$BYTEVIEW_EXAMPLE/Consumer.java"
