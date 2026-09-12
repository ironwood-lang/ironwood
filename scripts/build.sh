#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
IRONWOOD_BUILD_DIR="$IRONWOOD_PROJECT_ROOT/compiler/build"
IRONWOOD_CLASSES_DIR="$IRONWOOD_BUILD_DIR/classes"
IRONWOOD_SOURCES_FILE="$IRONWOOD_BUILD_DIR/main-sources.txt"
IRONWOOD_JAR="$IRONWOOD_BUILD_DIR/ironwoodc.jar"
IRONWOOD_STDLIB_CLASSES_DIR="$IRONWOOD_BUILD_DIR/stdlib"
IRONWOOD_STDLIB_ARCHIVE="$IRONWOOD_BUILD_DIR/ironwood-stdlib.ironjar"
IRONWOOD_STDLIB_SOURCES_FILE="$IRONWOOD_BUILD_DIR/stdlib-sources.txt"
IRONWOOD_TESTING_CLASSES_DIR="$IRONWOOD_BUILD_DIR/testing"
IRONWOOD_TESTING_ARCHIVE="$IRONWOOD_BUILD_DIR/ironwood-testing.ironjar"
IRONWOOD_TESTING_SOURCES_FILE="$IRONWOOD_BUILD_DIR/testing-sources.txt"
IRONWOOD_VERSION_FILE="$IRONWOOD_PROJECT_ROOT/VERSION"
IRONWOOD_VERSION=${IRONWOOD_VERSION:-}

if ! command -v javac >/dev/null 2>&1; then
    echo "error: javac is required (JDK 21 or newer)" >&2
    exit 1
fi
if [[ -z "$IRONWOOD_VERSION" ]]; then
    if [[ ! -f "$IRONWOOD_VERSION_FILE" ]]; then
        echo "error: missing compiler version file: $IRONWOOD_VERSION_FILE" >&2
        exit 1
    fi
    IRONWOOD_VERSION=$(tr -d '[:space:]' < "$IRONWOOD_VERSION_FILE")
fi
if [[ ! "$IRONWOOD_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
    echo "error: invalid compiler version '$IRONWOOD_VERSION'" >&2
    exit 1
fi

mkdir -p "$IRONWOOD_BUILD_DIR"
rm -rf "$IRONWOOD_CLASSES_DIR"
mkdir -p "$IRONWOOD_CLASSES_DIR"

cd "$IRONWOOD_PROJECT_ROOT"
if command -v rg >/dev/null 2>&1; then
    rg --files compiler/src/main/java -g '*.java' | sort > "$IRONWOOD_SOURCES_FILE"
else
    find compiler/src/main/java -type f -name '*.java' | sort > "$IRONWOOD_SOURCES_FILE"
fi
if [[ ! -s "$IRONWOOD_SOURCES_FILE" ]]; then
    echo "error: no compiler Java sources found" >&2
    exit 1
fi

javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
    -d "$IRONWOOD_CLASSES_DIR" "@$IRONWOOD_SOURCES_FILE"
printf '%s\n' "$IRONWOOD_VERSION" > "$IRONWOOD_CLASSES_DIR/ironwood/compiler/VERSION"
jar --create --file "$IRONWOOD_JAR" --main-class ironwood.compiler.Main -C "$IRONWOOD_CLASSES_DIR" .

rm -rf "$IRONWOOD_STDLIB_CLASSES_DIR"
mkdir -p "$IRONWOOD_STDLIB_CLASSES_DIR"
if command -v rg >/dev/null 2>&1; then
    rg --files stdlib/src/main/ironwood -g '*.iron' | sort > "$IRONWOOD_STDLIB_SOURCES_FILE"
else
    find stdlib/src/main/ironwood -type f -name '*.iron' | sort > "$IRONWOOD_STDLIB_SOURCES_FILE"
fi
IRONWOOD_STDLIB_SOURCES=()
while IFS= read -r IRONWOOD_STDLIB_SOURCE; do
    IRONWOOD_STDLIB_SOURCES+=("$IRONWOOD_STDLIB_SOURCE")
done < "$IRONWOOD_STDLIB_SOURCES_FILE"
if [[ ${#IRONWOOD_STDLIB_SOURCES[@]} -eq 0 ]]; then
    echo "error: no standard-library Ironwood sources found" >&2
    exit 1
fi
java -jar "$IRONWOOD_JAR" "${IRONWOOD_STDLIB_SOURCES[@]}" \
    -d "$IRONWOOD_STDLIB_CLASSES_DIR" \
    --source-path "$IRONWOOD_PROJECT_ROOT/stdlib/src/main/ironwood" \
    --unfreed=error
touch "$IRONWOOD_STDLIB_CLASSES_DIR/.built"
java -cp "$IRONWOOD_JAR" ironwood.compiler.IronJarMain \
    --create --file "$IRONWOOD_STDLIB_ARCHIVE" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSE" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSE-APACHE" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSE-MIT" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/LICENSE_MECHANICS" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSES/GPL-2.0-only.txt" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSES/Classpath-exception-2.0.txt" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSES/Unicode-15.0.txt" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/THIRD_PARTY_NOTICES.md" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/SOURCE_PROVENANCE.md" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_S0_SOURCE_REVIEW.md" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_U1_SOURCE_REVIEW.md" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_U2_SOURCE_REVIEW.md" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_U3_SOURCE_REVIEW.md" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_STRING_REVIEW.md" \
    "$IRONWOOD_STDLIB_CLASSES_DIR"

rm -rf "$IRONWOOD_TESTING_CLASSES_DIR"
mkdir -p "$IRONWOOD_TESTING_CLASSES_DIR"
if command -v rg >/dev/null 2>&1; then
    rg --files stdlib/src/testing/ironwood -g '*.iron' | sort > "$IRONWOOD_TESTING_SOURCES_FILE"
else
    find stdlib/src/testing/ironwood -type f -name '*.iron' \
        | sort > "$IRONWOOD_TESTING_SOURCES_FILE"
fi
IRONWOOD_TESTING_SOURCES=()
while IFS= read -r IRONWOOD_TESTING_SOURCE; do
    IRONWOOD_TESTING_SOURCES+=("$IRONWOOD_TESTING_SOURCE")
done < "$IRONWOOD_TESTING_SOURCES_FILE"
if [[ ${#IRONWOOD_TESTING_SOURCES[@]} -eq 0 ]]; then
    echo "error: no standard-library testing sources found" >&2
    exit 1
fi
java -jar "$IRONWOOD_JAR" "${IRONWOOD_TESTING_SOURCES[@]}" \
    -d "$IRONWOOD_TESTING_CLASSES_DIR" \
    --source-path "$IRONWOOD_PROJECT_ROOT/stdlib/src/testing/ironwood" \
    --unfreed=error
touch "$IRONWOOD_TESTING_CLASSES_DIR/.built"
java -cp "$IRONWOOD_JAR" ironwood.compiler.IronJarMain \
    --create --file "$IRONWOOD_TESTING_ARCHIVE" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSE" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSE-APACHE" \
    --license "$IRONWOOD_PROJECT_ROOT/LICENSE-MIT" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/LICENSE_MECHANICS" \
    --license "$IRONWOOD_PROJECT_ROOT/docs/SOURCE_PROVENANCE.md" \
    "$IRONWOOD_TESTING_CLASSES_DIR"
echo "built $IRONWOOD_JAR"
