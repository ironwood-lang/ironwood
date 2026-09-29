#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
GENERIC_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
set -x
java -Xcheck:jni -cp "$GENERIC_EXAMPLE/target/generics.jar:$GENERIC_EXAMPLE/target/consumer" Consumer
