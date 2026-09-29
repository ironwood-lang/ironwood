#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
BYTEVIEW_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
java -Xcheck:jni -cp "$BYTEVIEW_EXAMPLE/target/byteviews.jar:$BYTEVIEW_EXAMPLE/target/ironwood-bridge-values.jar:$BYTEVIEW_EXAMPLE/target/consumer" Consumer
