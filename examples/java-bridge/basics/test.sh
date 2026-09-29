#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

# Run after compile.sh and link.sh. Check both callbacks and the final native state.
expected=$'Java listener: 2\nJava listener: 5\nCounter total: 5'
actual=$(./run.sh)
if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: unexpected output:\n%s\n' "$actual" >&2
    exit 1
fi
actual=$(java -Xcheck:jni -cp target/ironwood-basics.jar:target/consumer-classes \
    org.ironwood.javabridge.basicsconsumer.Main)
if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: unexpected checked-JNI output:\n%s\n' "$actual" >&2
    exit 1
fi
printf 'PASS: Java calls, Ironwood callbacks and native counter state\n'
