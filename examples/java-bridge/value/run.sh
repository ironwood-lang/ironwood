#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$EXAMPLE_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
cd "$EXAMPLE_DIR"
# The grant keeps Java 24 and 25 from printing their native-access warning; Java 21-23 ignore it.
COMMAND=(java --enable-native-access=ALL-UNNAMED -cp target/ironwood-values.jar:target/consumer-classes org.ironwood.javabridge.consumer.Main)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
