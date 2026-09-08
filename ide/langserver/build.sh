#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Builds the Ironwood language server jar.
#
# The jar contains only the server's own classes. It runs against the bootstrap
# compiler jar and the LSP4J jars supplied by whichever host launches it, which
# keeps the server free of vendored dependencies and lets the Eclipse plugin
# reuse the LSP4J the platform already ships.

set -euo pipefail

IRONWOOD_LS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_ROOT=$(CDPATH= cd -- "$IRONWOOD_LS_DIR/../.." && pwd)
IRONWOOD_BUILD_DIR="$IRONWOOD_LS_DIR/target"

IRONWOOD_ECLIPSE_HOME=${IRONWOOD_ECLIPSE_HOME:-/Applications/Eclipse-2026-03.app/Contents/Eclipse}
IRONWOOD_ECLIPSE_PLUGINS="$IRONWOOD_ECLIPSE_HOME/plugins"

IRONWOOD_JAVA_HOME=${IRONWOOD_JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home}
if [[ -x "$IRONWOOD_JAVA_HOME/bin/javac" ]]; then
    IRONWOOD_JAVAC="$IRONWOOD_JAVA_HOME/bin/javac"
    IRONWOOD_JAR_TOOL="$IRONWOOD_JAVA_HOME/bin/jar"
else
    IRONWOOD_JAVAC=javac
    IRONWOOD_JAR_TOOL=jar
fi

IRONWOOD_COMPILER_JAR="$IRONWOOD_ROOT/compiler/build/ironwoodc.jar"
if [[ ! -f "$IRONWOOD_COMPILER_JAR" ]]; then
    echo "  building the bootstrap compiler first"
    "$IRONWOOD_ROOT/scripts/build.sh"
fi

# LSP4J and Gson are taken from the Eclipse installation so that the server is
# compiled against exactly the versions it will run against inside Eclipse.
IRONWOOD_LSP4J_CLASSPATH=$(find "$IRONWOOD_ECLIPSE_PLUGINS" \
    \( -name 'org.eclipse.lsp4j_*.jar' \
    -o -name 'org.eclipse.lsp4j.jsonrpc_*.jar' \
    -o -name 'com.google.gson_*.jar' \) 2>/dev/null | tr '\n' ':')
if [[ -z "$IRONWOOD_LSP4J_CLASSPATH" ]]; then
    echo "error: no LSP4J jars found under $IRONWOOD_ECLIPSE_PLUGINS" >&2
    echo "set IRONWOOD_ECLIPSE_HOME to an Eclipse installation that includes LSP4E" >&2
    exit 1
fi

IRONWOOD_VERSION_LABEL=$(<"$IRONWOOD_ROOT/VERSION")
IRONWOOD_SERVER_JAR="$IRONWOOD_BUILD_DIR/ironwood-langserver.jar"

echo "building the Ironwood language server $IRONWOOD_VERSION_LABEL"

rm -rf "$IRONWOOD_BUILD_DIR"
mkdir -p "$IRONWOOD_BUILD_DIR/classes"

find "$IRONWOOD_LS_DIR/src/main/java" -name '*.java' > "$IRONWOOD_BUILD_DIR/sources.txt"
"$IRONWOOD_JAVAC" \
    --release 21 \
    -Xlint:all \
    -cp "$IRONWOOD_COMPILER_JAR:$IRONWOOD_LSP4J_CLASSPATH" \
    -d "$IRONWOOD_BUILD_DIR/classes" \
    @"$IRONWOOD_BUILD_DIR/sources.txt"

cat > "$IRONWOOD_BUILD_DIR/manifest.txt" <<EOF
Main-Class: ironwood.lsp.IronwoodLanguageServerMain
Implementation-Title: Ironwood Language Server
Implementation-Version: $IRONWOOD_VERSION_LABEL
EOF

"$IRONWOOD_JAR_TOOL" --create \
    --file "$IRONWOOD_SERVER_JAR" \
    --manifest "$IRONWOOD_BUILD_DIR/manifest.txt" \
    -C "$IRONWOOD_BUILD_DIR/classes" .

echo "built $IRONWOOD_SERVER_JAR"
