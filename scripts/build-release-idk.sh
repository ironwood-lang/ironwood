#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

# The hosted release and local preflight share these build and archive checks.
set -euo pipefail
IRONWOOD_RELEASE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
IRONWOOD_RELEASE_VERSION=${1:?pass a version}
IRONWOOD_RELEASE_PLATFORM=${2:?pass a platform}
case "$(uname -sm)/$IRONWOOD_RELEASE_PLATFORM" in
    "Darwin arm64/macos-arm64"|"Linux aarch64/linux-arm64"|"Linux arm64/linux-arm64"|"Linux x86_64/linux-x86_64") ;;
    *) echo "error: release IDK must be built on its matching target" >&2; exit 1 ;;
esac
export IRONWOOD_IDK_TOOLCHAIN_HOME=${IRONWOOD_IDK_TOOLCHAIN_HOME:-${CONDA_PREFIX:?select the IDK toolchain}}
export JAVA_HOME="$IRONWOOD_IDK_TOOLCHAIN_HOME/lib/jvm"
export IRONWOOD_LLVM_HOME="$IRONWOOD_IDK_TOOLCHAIN_HOME"
export PATH="$JAVA_HOME/bin:$IRONWOOD_LLVM_HOME/bin:$PATH"
export IRONWOOD_GC_HOME=${IRONWOOD_GC_HOME-$IRONWOOD_IDK_TOOLCHAIN_HOME/ironwood-gc}
export IRONWOOD_TLS_HOME=${IRONWOOD_TLS_HOME:-$IRONWOOD_IDK_TOOLCHAIN_HOME/ironwood-tls}
if [[ -z "$IRONWOOD_GC_HOME" ]]; then
    echo "error: IRONWOOD_GC_HOME is empty; select a prepared SDK or a new preparation prefix" >&2
    exit 1
fi
cd "$IRONWOOD_RELEASE_ROOT"

# Compiler/native suites are separate local release-readiness work.
python3 -B scripts/test-jvm-options.py
./scripts/check-licenses.sh
./scripts/check-tracked-files.sh
./scripts/build.sh
./scripts/update-irondocs.sh --check

IRONWOOD_RELEASE_GC_OPTIONS=()
if [[ -e "$IRONWOOD_GC_HOME" || -L "$IRONWOOD_GC_HOME" ]]; then
    IRONWOOD_RELEASE_GC_OPTIONS=(--verify)
fi
python -B scripts/prepare-gc.py --llvm-home "$IRONWOOD_LLVM_HOME" --prefix "$IRONWOOD_GC_HOME" \
    --target "$IRONWOOD_RELEASE_PLATFORM" ${IRONWOOD_RELEASE_GC_OPTIONS[@]+"${IRONWOOD_RELEASE_GC_OPTIONS[@]}"}
IRONWOOD_RELEASE_TLS_OPTIONS=()
if [[ -e "$IRONWOOD_TLS_HOME" || -L "$IRONWOOD_TLS_HOME" ]]; then
    IRONWOOD_RELEASE_TLS_OPTIONS=(--verify)
fi
python -B scripts/prepare-tls.py --llvm-home "$IRONWOOD_LLVM_HOME" --prefix "$IRONWOOD_TLS_HOME" \
    ${IRONWOOD_RELEASE_TLS_OPTIONS[@]+"${IRONWOOD_RELEASE_TLS_OPTIONS[@]}"}
if [[ "$IRONWOOD_RELEASE_PLATFORM" == linux-* ]]; then
    export IRONWOOD_BRIDGE_SUPPORT_HOME=${IRONWOOD_BRIDGE_SUPPORT_HOME:-$IRONWOOD_IDK_TOOLCHAIN_HOME/ironwood-bridge-support}
    IRONWOOD_RELEASE_SUPPORT_OPTION=--setup
    if [[ -e "$IRONWOOD_BRIDGE_SUPPORT_HOME" || -L "$IRONWOOD_BRIDGE_SUPPORT_HOME" ]]; then
        IRONWOOD_RELEASE_SUPPORT_OPTION=--check
    fi
    python -B scripts/prepare-java-bridge-support.py "$IRONWOOD_RELEASE_SUPPORT_OPTION" \
        --target "$IRONWOOD_RELEASE_PLATFORM" --prefix "$IRONWOOD_BRIDGE_SUPPORT_HOME"
fi

./scripts/package-idk.sh "$IRONWOOD_RELEASE_VERSION"
IRONWOOD_RELEASE_DIST=${IRONWOOD_DIST_DIR:-$IRONWOOD_RELEASE_ROOT/dist}
IRONWOOD_RELEASE_NAME="ironwood-idk-$IRONWOOD_RELEASE_VERSION-$IRONWOOD_RELEASE_PLATFORM"
IRONWOOD_RELEASE_ARCHIVE="$IRONWOOD_RELEASE_DIST/$IRONWOOD_RELEASE_NAME.tar.gz"
# Leave room for relocation and verify the archive, independently of its staging tree.
rm -rf "$IRONWOOD_RELEASE_DIST/$IRONWOOD_RELEASE_NAME"
./scripts/test-idk.sh "$IRONWOOD_RELEASE_ARCHIVE"
./scripts/test-idk.sh "$IRONWOOD_RELEASE_ARCHIVE" --gc9
