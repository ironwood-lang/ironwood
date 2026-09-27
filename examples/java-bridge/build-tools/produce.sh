#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY=$(CDPATH= cd -- "$EXAMPLE_DIR/../../.." && pwd)
if [[ $# != 1 ]]; then
    echo 'usage: produce.sh <build-directory>' >&2
    exit 2
fi
mkdir -p -- "$1"
OUTPUT=$(CDPATH= cd -- "$1" && pwd)
# The distribution command preserves previous output; use the build tool's clean task before rebuilding.
if [[ -e "$OUTPUT/distribution" ]]; then
    echo 'distribution already exists; run the producer clean task before rebuilding' >&2
    exit 1
fi
if [[ -n ${BRIDGE_PAIRED_JAR:-} ]]; then
    INPUT=$BRIDGE_PAIRED_JAR
else
    INPUT=$OUTPUT/ironwood-values.jar
    "${IRONWOODC:-ironwoodc}" --java-bridge --export org.ironwood.javabridge.value \
        --license "$REPOSITORY/LICENSE-MIT" --license "$REPOSITORY/LICENSE-APACHE" \
        -O3 -o "$INPUT" "$EXAMPLE_DIR/../value/src/main/ironwood/org/ironwood/javabridge/value/Values.iron"
fi
"${IRONWOODC:-ironwoodc}" --java-bridge-distribution --input "$INPUT" \
    --group-id org.ironwood.example --artifact-id ironwood-values --version 0.1.0-local \
    -d "$OUTPUT/distribution"
