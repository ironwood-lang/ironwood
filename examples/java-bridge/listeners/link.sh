#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$EXAMPLE_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
ironwood_require_jdk
DIST_ROOT=$(CDPATH= cd -- "$EXAMPLE_DIR/../../.." && pwd)
cd "$EXAMPLE_DIR"
set -x
ironwoodc --java-bridge --export org.ironwood.javabridge.listeners -cp target/classes \
    --license "$DIST_ROOT/LICENSE-MIT" --license "$DIST_ROOT/LICENSE-APACHE" \
    -O3 -o target/ironwood-listeners.jar
javac --release 21 -Xlint:all -Werror -cp target/ironwood-listeners.jar -d target/consumer-classes \
    src/main/java/org/ironwood/javabridge/listenerconsumer/*.java
