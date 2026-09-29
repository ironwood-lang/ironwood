#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
BYTEVIEW_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BYTEVIEW_ROOT=$(CDPATH= cd -- "$BYTEVIEW_EXAMPLE/../../.." && pwd)
"$BYTEVIEW_ROOT/bin/ironwoodc" --java-bridge --export bytebench --unfreed=off -O3 \
    --license "$BYTEVIEW_ROOT/LICENSE-MIT" --license "$BYTEVIEW_ROOT/LICENSE-APACHE" \
    -cp "$BYTEVIEW_EXAMPLE/target/classes" -o "$BYTEVIEW_EXAMPLE/target/byteviews.jar"
javac --release 21 -Xlint:all -Werror -cp "$BYTEVIEW_EXAMPLE/target/byteviews.jar:$BYTEVIEW_EXAMPLE/target/ironwood-bridge-values.jar" \
    -d "$BYTEVIEW_EXAMPLE/target/consumer" "$BYTEVIEW_EXAMPLE/Consumer.java"
