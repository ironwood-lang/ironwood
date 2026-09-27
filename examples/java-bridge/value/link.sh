#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_ROOT=$(CDPATH= cd -- "$EXAMPLE_DIR/../../.." && pwd)
cd "$EXAMPLE_DIR"
COMMAND=(ironwoodc --java-bridge --export org.ironwood.javabridge.value -cp target/classes
    --license "$DIST_ROOT/LICENSE-MIT" --license "$DIST_ROOT/LICENSE-APACHE"
    -O3 -o target/ironwood-values.jar)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
COMMAND=(javac --release 21 -cp target/ironwood-values.jar -d target/consumer-classes
    src/main/java/org/ironwood/javabridge/consumer/Main.java)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
