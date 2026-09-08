#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
IRONWOOD_VERSION=${1:-${IRONWOOD_VERSION:-}}
IRONWOOD_IDK_TOOLCHAIN_HOME=${IRONWOOD_IDK_TOOLCHAIN_HOME:-${CONDA_PREFIX:-}}

if [[ -z "$IRONWOOD_VERSION" ]]; then
    echo "usage: package-idk.sh <version>" >&2
    echo "error: pass a version or set IRONWOOD_VERSION" >&2
    exit 1
fi
if [[ ! "$IRONWOOD_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
    echo "error: invalid IDK version '$IRONWOOD_VERSION'" >&2
    exit 1
fi
if [[ -z "$IRONWOOD_IDK_TOOLCHAIN_HOME" || ! -d "$IRONWOOD_IDK_TOOLCHAIN_HOME" ]]; then
    echo "error: set IRONWOOD_IDK_TOOLCHAIN_HOME to the prepared IDK toolchain environment" >&2
    exit 1
fi

IRONWOOD_OS=$(uname -s)
IRONWOOD_ARCH=$(uname -m)
case "$IRONWOOD_OS/$IRONWOOD_ARCH" in
    Darwin/arm64) IRONWOOD_PLATFORM=macos-arm64 ;;
    Linux/aarch64|Linux/arm64) IRONWOOD_PLATFORM=linux-arm64 ;;
    Linux/x86_64|Linux/amd64) IRONWOOD_PLATFORM=linux-x86_64 ;;
    *)
        echo "error: IDK packaging is not supported on $IRONWOOD_OS/$IRONWOOD_ARCH" >&2
        exit 1
        ;;
esac

IRONWOOD_REQUIRED_TOOLS=(clang llvm-as opt llc llvm-objcopy llvm-config conda-pack python)
for IRONWOOD_TOOL in "${IRONWOOD_REQUIRED_TOOLS[@]}"; do
    if [[ ! -x "$IRONWOOD_IDK_TOOLCHAIN_HOME/bin/$IRONWOOD_TOOL" ]]; then
        echo "error: IDK toolchain is missing bin/$IRONWOOD_TOOL" >&2
        exit 1
    fi
done
if [[ ! -x "$IRONWOOD_IDK_TOOLCHAIN_HOME/lib/jvm/bin/java" ]]; then
    echo "error: IDK toolchain is missing lib/jvm/bin/java" >&2
    exit 1
fi

IRONWOOD_LLVM_VERSION=$(
    "$IRONWOOD_IDK_TOOLCHAIN_HOME/bin/llvm-config" --version
)
if [[ "${IRONWOOD_LLVM_VERSION%%.*}" != 23 ]]; then
    echo "error: IDK packaging requires LLVM 23.x, found $IRONWOOD_LLVM_VERSION" >&2
    exit 1
fi

IRONWOOD_PACKAGE_NAME="ironwood-idk-$IRONWOOD_VERSION-$IRONWOOD_PLATFORM"
IRONWOOD_DIST_DIR="$IRONWOOD_PROJECT_ROOT/dist"
IRONWOOD_STAGE_DIR="$IRONWOOD_DIST_DIR/$IRONWOOD_PACKAGE_NAME"
IRONWOOD_ARCHIVE="$IRONWOOD_DIST_DIR/$IRONWOOD_PACKAGE_NAME.tar.gz"
IRONWOOD_TOOLCHAIN_ARCHIVE="$IRONWOOD_DIST_DIR/.idk-toolchain-$IRONWOOD_PLATFORM.tar.gz"

IRONWOOD_VERSION="$IRONWOOD_VERSION" "$IRONWOOD_SCRIPT_DIR/build.sh"

rm -rf "$IRONWOOD_STAGE_DIR"
rm -f "$IRONWOOD_ARCHIVE" "$IRONWOOD_TOOLCHAIN_ARCHIVE"
mkdir -p \
    "$IRONWOOD_STAGE_DIR/bin" \
    "$IRONWOOD_STAGE_DIR/lib" \
    "$IRONWOOD_STAGE_DIR/docs" \
    "$IRONWOOD_STAGE_DIR/examples" \
    "$IRONWOOD_STAGE_DIR/projects" \
    "$IRONWOOD_STAGE_DIR/runtime" \
    "$IRONWOOD_STAGE_DIR/stdlib" \
    "$IRONWOOD_STAGE_DIR/LICENSES" \
    "$IRONWOOD_STAGE_DIR/toolchain"

cp "$IRONWOOD_PROJECT_ROOT/bin/ironwoodc" "$IRONWOOD_STAGE_DIR/bin/ironwoodc"
cp "$IRONWOOD_PROJECT_ROOT/bin/ironjar" "$IRONWOOD_STAGE_DIR/bin/ironjar"
cp "$IRONWOOD_PROJECT_ROOT/compiler/build/ironwoodc.jar" "$IRONWOOD_STAGE_DIR/lib/ironwoodc.jar"
cp "$IRONWOOD_PROJECT_ROOT/compiler/build/ironwood-stdlib.ironjar" \
    "$IRONWOOD_STAGE_DIR/lib/ironwood-stdlib.ironjar"
cp "$IRONWOOD_PROJECT_ROOT/compiler/build/ironwood-testing.ironjar" \
    "$IRONWOOD_STAGE_DIR/lib/ironwood-testing.ironjar"
cp "$IRONWOOD_PROJECT_ROOT/docs/IDK.md" "$IRONWOOD_STAGE_DIR/README.md"
cp "$IRONWOOD_PROJECT_ROOT/README.md" "$IRONWOOD_STAGE_DIR/docs/PROJECT.md"
cp "$IRONWOOD_PROJECT_ROOT/LICENSE" "$IRONWOOD_STAGE_DIR/LICENSE"
cp "$IRONWOOD_PROJECT_ROOT/LICENSE-APACHE" "$IRONWOOD_STAGE_DIR/LICENSE-APACHE"
cp "$IRONWOOD_PROJECT_ROOT/LICENSE-MIT" "$IRONWOOD_STAGE_DIR/LICENSE-MIT"
cp "$IRONWOOD_PROJECT_ROOT/docs/LICENSE_MECHANICS" "$IRONWOOD_STAGE_DIR/LICENSE_MECHANICS"
cp "$IRONWOOD_PROJECT_ROOT/LICENSES/GPL-2.0-only.txt" \
    "$IRONWOOD_STAGE_DIR/LICENSES/GPL-2.0-only.txt"
cp "$IRONWOOD_PROJECT_ROOT/LICENSES/Classpath-exception-2.0.txt" \
    "$IRONWOOD_STAGE_DIR/LICENSES/Classpath-exception-2.0.txt"
cp "$IRONWOOD_PROJECT_ROOT/LICENSES/Unicode-15.0.txt" "$IRONWOOD_STAGE_DIR/LICENSES/Unicode-15.0.txt"
cp "$IRONWOOD_PROJECT_ROOT/docs/THIRD_PARTY_NOTICES.md" "$IRONWOOD_STAGE_DIR/THIRD_PARTY_NOTICES.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/COMPILER.md" "$IRONWOOD_STAGE_DIR/docs/COMPILER.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/DECISIONS.md" "$IRONWOOD_STAGE_DIR/docs/DECISIONS.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/LANGUAGE.md" "$IRONWOOD_STAGE_DIR/docs/LANGUAGE.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/MEMORY.md" "$IRONWOOD_STAGE_DIR/docs/MEMORY.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/ROADMAP.md" "$IRONWOOD_STAGE_DIR/docs/ROADMAP.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_ROADMAP.md" "$IRONWOOD_STAGE_DIR/docs/STDLIB_ROADMAP.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/LIBRARY_PORTS.md" "$IRONWOOD_STAGE_DIR/docs/LIBRARY_PORTS.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/OPENJDK_PORTING.md" "$IRONWOOD_STAGE_DIR/docs/OPENJDK_PORTING.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/SOURCE_PROVENANCE.md" "$IRONWOOD_STAGE_DIR/docs/SOURCE_PROVENANCE.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md" "$IRONWOOD_STAGE_DIR/docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_S0_SOURCE_REVIEW.md" "$IRONWOOD_STAGE_DIR/docs/STDLIB_S0_SOURCE_REVIEW.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_U1_SOURCE_REVIEW.md" "$IRONWOOD_STAGE_DIR/docs/STDLIB_U1_SOURCE_REVIEW.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_U2_SOURCE_REVIEW.md" "$IRONWOOD_STAGE_DIR/docs/STDLIB_U2_SOURCE_REVIEW.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_U3_SOURCE_REVIEW.md" "$IRONWOOD_STAGE_DIR/docs/STDLIB_U3_SOURCE_REVIEW.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/STDLIB_STRING_REVIEW.md" "$IRONWOOD_STAGE_DIR/docs/STDLIB_STRING_REVIEW.md"
mkdir -p "$IRONWOOD_STAGE_DIR/scripts"
cp "$IRONWOOD_PROJECT_ROOT/scripts/GenerateCaseData.java" "$IRONWOOD_STAGE_DIR/scripts/GenerateCaseData.java"
cp "$IRONWOOD_PROJECT_ROOT/docs/SYSTEM_OUTPUT_SOURCE_REVIEW.md" "$IRONWOOD_STAGE_DIR/docs/SYSTEM_OUTPUT_SOURCE_REVIEW.md"
cp "$IRONWOOD_PROJECT_ROOT/docs/TESTING.md" "$IRONWOOD_STAGE_DIR/docs/TESTING.md"
while IFS= read -r IRONWOOD_EXAMPLE_FILE; do
    IRONWOOD_EXAMPLE_RELATIVE=${IRONWOOD_EXAMPLE_FILE#"$IRONWOOD_PROJECT_ROOT/examples/"}
    mkdir -p "$IRONWOOD_STAGE_DIR/examples/$(dirname -- "$IRONWOOD_EXAMPLE_RELATIVE")"
    cp "$IRONWOOD_EXAMPLE_FILE" "$IRONWOOD_STAGE_DIR/examples/$IRONWOOD_EXAMPLE_RELATIVE"
done < <(find "$IRONWOOD_PROJECT_ROOT/examples" -type f \
    \( -name '*.iron' -o -name '*.sh' -o -name 'README.md' \) -print)
while IFS= read -r IRONWOOD_PROJECT_FILE; do
    IRONWOOD_PROJECT_RELATIVE=${IRONWOOD_PROJECT_FILE#"$IRONWOOD_PROJECT_ROOT/projects/"}
    mkdir -p "$IRONWOOD_STAGE_DIR/projects/$(dirname -- "$IRONWOOD_PROJECT_RELATIVE")"
    cp "$IRONWOOD_PROJECT_FILE" "$IRONWOOD_STAGE_DIR/projects/$IRONWOOD_PROJECT_RELATIVE"
done < <(find "$IRONWOOD_PROJECT_ROOT/projects" -type f \
    \( -name '*.iron' -o -name '*.sh' -o -name '*.txt' -o -name 'README.md' \) -print)
cp -R "$IRONWOOD_PROJECT_ROOT/runtime/include" "$IRONWOOD_STAGE_DIR/runtime/include"
cp -R "$IRONWOOD_PROJECT_ROOT/runtime/src" "$IRONWOOD_STAGE_DIR/runtime/src"
cp -R "$IRONWOOD_PROJECT_ROOT/stdlib/src" "$IRONWOOD_STAGE_DIR/stdlib/src"
cp "$IRONWOOD_PROJECT_ROOT/stdlib/README.md" "$IRONWOOD_STAGE_DIR/stdlib/README.md"
cp -R "$IRONWOOD_PROJECT_ROOT/compiler/build/stdlib" "$IRONWOOD_STAGE_DIR/lib/stdlib"
printf '%s\n' "$IRONWOOD_VERSION" > "$IRONWOOD_STAGE_DIR/VERSION"

"$IRONWOOD_IDK_TOOLCHAIN_HOME/bin/conda-pack" \
    -p "$IRONWOOD_IDK_TOOLCHAIN_HOME" \
    -o "$IRONWOOD_TOOLCHAIN_ARCHIVE" \
    --force
tar -xzf "$IRONWOOD_TOOLCHAIN_ARCHIVE" -C "$IRONWOOD_STAGE_DIR/toolchain"
rm -f "$IRONWOOD_TOOLCHAIN_ARCHIVE"

"$IRONWOOD_IDK_TOOLCHAIN_HOME/bin/python" - "$IRONWOOD_IDK_TOOLCHAIN_HOME" \
        > "$IRONWOOD_STAGE_DIR/THIRD-PARTY-PACKAGES.tsv" <<'PYTHON'
import glob
import json
import os
import sys

prefix = sys.argv[1]
print("package\tversion\tlicense\tsource")
for metadata_path in sorted(glob.glob(os.path.join(prefix, "conda-meta", "*.json"))):
    with open(metadata_path, encoding="utf-8") as metadata_file:
        metadata = json.load(metadata_file)
    print("\t".join((
        metadata.get("name", ""),
        metadata.get("version", ""),
        metadata.get("license", ""),
        metadata.get("channel", ""),
    )))
PYTHON

tar -C "$IRONWOOD_DIST_DIR" -czf "$IRONWOOD_ARCHIVE" "$IRONWOOD_PACKAGE_NAME"
echo "packaged $IRONWOOD_ARCHIVE"
