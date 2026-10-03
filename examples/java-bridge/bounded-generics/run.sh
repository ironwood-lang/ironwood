#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
GENERIC_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$GENERIC_EXAMPLE/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
set -x
# The grant keeps Java 24 and 25 from printing their native-access warning; Java 21-23 ignore it.
java -Xcheck:jni --enable-native-access=ALL-UNNAMED -cp "$GENERIC_EXAMPLE/target/generics.jar:$GENERIC_EXAMPLE/target/consumer" Consumer
