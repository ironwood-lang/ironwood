#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

# Only pass a disposable extracted package. Restore its configuration on exit.
set -euo pipefail
IRONWOOD_OPTIONS_TEST_ROOT=${1:?pass an extracted package directory}
for IRONWOOD_REQUIRED_FILE in conf/jvm.options scripts/jvm-options.sh; do
    if [[ ! -f "$IRONWOOD_OPTIONS_TEST_ROOT/$IRONWOOD_REQUIRED_FILE" ]]; then
        echo "error: package is missing $IRONWOOD_REQUIRED_FILE" >&2
        exit 1
    fi
done
IRONWOOD_OPTIONS_TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ironwood-jvm-options-test.XXXXXX")
cp "$IRONWOOD_OPTIONS_TEST_ROOT/conf/jvm.options" "$IRONWOOD_OPTIONS_TEST_DIR/original"
trap 'cp "$IRONWOOD_OPTIONS_TEST_DIR/original" "$IRONWOOD_OPTIONS_TEST_ROOT/conf/jvm.options"; rm -rf "$IRONWOOD_OPTIONS_TEST_DIR"' EXIT

ironwood_options_test_launch() {
    local tool=$1 argument=--help
    if [[ "$tool" == ironwoodc || "$tool" == irondoc ]]; then
        argument=--version
    fi
    env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
        "$IRONWOOD_OPTIONS_TEST_ROOT/bin/$tool" "$argument"
}

# Defaults must not disable SVE or otherwise customize the JVM.
if grep -Ev '^[[:space:]]*(#.*)?$' "$IRONWOOD_OPTIONS_TEST_DIR/original"; then
    echo "error: packaged jvm.options must contain only comments and blank lines" >&2
    exit 1
fi
printf '%s\r\n' '# JVM configuration smoke test' '' '  -XshowSettings:properties  ' \
    '-Dironwood.jvm.options.probe=configured value with spaces # literal' \
    > "$IRONWOOD_OPTIONS_TEST_ROOT/conf/jvm.options"
for IRONWOOD_OPTIONS_TEST_TOOL in ironwoodc ironjar irondoc; do
    if ! IRONWOOD_OPTIONS_TEST_OUTPUT=$(ironwood_options_test_launch "$IRONWOOD_OPTIONS_TEST_TOOL" 2>&1); then
        printf '%s\n' "$IRONWOOD_OPTIONS_TEST_OUTPUT" >&2
        echo "error: $IRONWOOD_OPTIONS_TEST_TOOL failed with configured JVM options" >&2
        exit 1
    fi
    if ! grep -Fq 'ironwood.jvm.options.probe = configured value with spaces # literal' \
            <<< "$IRONWOOD_OPTIONS_TEST_OUTPUT"; then
        echo "error: $IRONWOOD_OPTIONS_TEST_TOOL did not apply packaged JVM options" >&2
        exit 1
    fi
done
printf '%s\n' '-XX:IronwoodInvalidOptionForTest' > "$IRONWOOD_OPTIONS_TEST_ROOT/conf/jvm.options"
for IRONWOOD_OPTIONS_TEST_TOOL in ironwoodc ironjar irondoc; do
    if ironwood_options_test_launch "$IRONWOOD_OPTIONS_TEST_TOOL" \
            > "$IRONWOOD_OPTIONS_TEST_DIR/invalid-output" 2>&1; then
        echo "error: $IRONWOOD_OPTIONS_TEST_TOOL ignored an invalid JVM option" >&2
        exit 1
    fi
    if ! grep -Fq 'Unrecognized VM option' "$IRONWOOD_OPTIONS_TEST_DIR/invalid-output"; then
        echo "error: $IRONWOOD_OPTIONS_TEST_TOOL lost the JVM option diagnostic" >&2
        exit 1
    fi
done
mv "$IRONWOOD_OPTIONS_TEST_ROOT/conf/jvm.options" "$IRONWOOD_OPTIONS_TEST_DIR/invalid-options"
for IRONWOOD_OPTIONS_TEST_TOOL in ironwoodc ironjar irondoc; do
    ironwood_options_test_launch "$IRONWOOD_OPTIONS_TEST_TOOL" >/dev/null
done
echo "JVM options smoke checks passed for all three packaged tools"
