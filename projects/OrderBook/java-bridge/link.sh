#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$PROJECT_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
ironwood_require_jdk
cd "$PROJECT_DIR"

REPO_ROOT=$(CDPATH= cd -- "$PROJECT_DIR/../../.." && pwd)
# Every order operation is short and memory-only, so its calls can skip the JVM
# thread-state transition. The registered JNI methods remain the fallback.
COMMAND=(ironwoodc --java-bridge --export org.ironwood.orderbook
    -cp target/iron-classes -O3 --critical-calls=on
    --license "$REPO_ROOT/LICENSE-MIT" --license "$REPO_ROOT/LICENSE-APACHE"
    -o target/orderbook.jar)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"

# Reuse the Java drivers verbatim, but resolve their engine types from the JAR.
# An empty source path prevents javac from silently compiling the Java engine.
rm -rf target/consumer-classes target/empty-source
mkdir -p target/consumer-classes target/empty-source
JAVA_SOURCES=../java/src/main/java/org/ironwood/orderbook
COMMAND=(javac --release 21 -encoding UTF-8 -Xlint:all -Werror
    -sourcepath target/empty-source -cp target/orderbook.jar -d target/consumer-classes
    "$JAVA_SOURCES/Main.java" "$JAVA_SOURCES/Bench.java"
    "$JAVA_SOURCES/LatencyBench.java" "$JAVA_SOURCES/LatencyReport.java")
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
