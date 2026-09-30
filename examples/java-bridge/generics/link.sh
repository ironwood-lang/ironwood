#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
GENERIC_EXAMPLE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$GENERIC_EXAMPLE/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
ironwood_require_jdk
GENERIC_ROOT=$(CDPATH= cd -- "$GENERIC_EXAMPLE/../../.." && pwd)
set -x
"$GENERIC_ROOT/bin/ironwoodc" --java-bridge --export genericbench --unfreed=off -O3 \
    --license "$GENERIC_ROOT/LICENSE-MIT" --license "$GENERIC_ROOT/LICENSE-APACHE" \
    -cp "$GENERIC_EXAMPLE/target/classes" -o "$GENERIC_EXAMPLE/target/generics.jar"
javac --release 21 -Xlint:all -Werror -cp "$GENERIC_EXAMPLE/target/generics.jar" \
    -d "$GENERIC_EXAMPLE/target/consumer" "$GENERIC_EXAMPLE/Consumer.java"
