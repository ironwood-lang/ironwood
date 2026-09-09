#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_ARCHIVE=${1:-}
if [[ -z "$IRONWOOD_ARCHIVE" || ! -f "$IRONWOOD_ARCHIVE" ]]; then
    echo "usage: test-idk.sh <ironwood-idk archive.tar.gz>" >&2
    exit 1
fi

IRONWOOD_TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ironwood-idk-test.XXXXXX")
trap 'rm -rf "$IRONWOOD_TEST_DIR"' EXIT
tar -xzf "$IRONWOOD_ARCHIVE" -C "$IRONWOOD_TEST_DIR"

IRONWOOD_ROOTS=("$IRONWOOD_TEST_DIR"/ironwood-idk-*)
if [[ ${#IRONWOOD_ROOTS[@]} -ne 1 || ! -d "${IRONWOOD_ROOTS[0]}" ]]; then
    echo "error: IDK archive must contain exactly one ironwood-idk-* directory" >&2
    exit 1
fi
IRONWOOD_IDK_ROOT=${IRONWOOD_ROOTS[0]}
IRONWOOD_MAIN_OUTPUT="$IRONWOOD_TEST_DIR/main"
IRONWOOD_CONTROL_FLOW_OUTPUT="$IRONWOOD_TEST_DIR/methods-and-control-flow"
IRONWOOD_OBJECTS_OUTPUT="$IRONWOOD_TEST_DIR/objects"
IRONWOOD_INHERITANCE_OUTPUT="$IRONWOOD_TEST_DIR/inheritance-and-interfaces"
IRONWOOD_CLASS_OUTPUT="$IRONWOOD_TEST_DIR/counter-classes"
IRONWOOD_CLASS_PROGRAM="$IRONWOOD_TEST_DIR/objects-from-classpath"

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
    bash "$IRONWOOD_SCRIPT_DIR/test-jvm-options-smoke.sh" "$IRONWOOD_IDK_ROOT"

IRONWOOD_REQUIRED_EXECUTABLES=(
    bin/ironwoodc
    bin/ironjar
    bin/irondoc
    toolchain/lib/jvm/bin/java
    toolchain/bin/clang
    toolchain/bin/llvm-as
    toolchain/bin/opt
    toolchain/bin/llc
    toolchain/bin/llvm-objcopy
    toolchain/bin/llvm-config
    toolchain/bin/python
)
for IRONWOOD_EXECUTABLE in "${IRONWOOD_REQUIRED_EXECUTABLES[@]}"; do
    if [[ ! -x "$IRONWOOD_IDK_ROOT/$IRONWOOD_EXECUTABLE" ]]; then
        echo "error: packaged IDK is missing executable $IRONWOOD_EXECUTABLE" >&2
        exit 1
    fi
done
if [[ ! -f "$IRONWOOD_IDK_ROOT/toolchain/bin/conda-unpack" ]]; then
    echo "error: packaged IDK is missing toolchain/bin/conda-unpack" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/lib/ironwoodc.jar" ]]; then
    echo "error: packaged IDK is missing lib/ironwoodc.jar" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/lib/ironwood-testing.ironjar" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/TESTING.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/testing/ironwood/ironwood/testing/Assertions.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/testing/ironwood/ironwood/testing/TestRunner.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/testing/ironwood/ironwood/testing/TestSuite.iron" ]]; then
    echo "error: packaged IDK is missing the standard-library testing module" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/VERSION" ]]; then
    echo "error: packaged IDK is missing VERSION" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/docs/MEMORY.md" ]]; then
    echo "error: packaged IDK is missing docs/MEMORY.md" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/docs/QUICK_START.md" ]]; then
    echo "error: packaged IDK is missing docs/QUICK_START.md" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/LICENSE" \
        || ! -f "$IRONWOOD_IDK_ROOT/LICENSE-APACHE" \
        || ! -f "$IRONWOOD_IDK_ROOT/LICENSE-MIT" \
        || ! -f "$IRONWOOD_IDK_ROOT/LICENSE_MECHANICS" \
        || ! -f "$IRONWOOD_IDK_ROOT/LICENSES/GPL-2.0-only.txt" \
        || ! -f "$IRONWOOD_IDK_ROOT/LICENSES/Classpath-exception-2.0.txt" \
        || ! -f "$IRONWOOD_IDK_ROOT/LICENSES/Unicode-15.0.txt" \
        || ! -f "$IRONWOOD_IDK_ROOT/runtime/src/ironwood_case.c" \
        || ! -f "$IRONWOOD_IDK_ROOT/runtime/src/ironwood_case_data.h" \
        || ! -f "$IRONWOOD_IDK_ROOT/runtime/include/ironwood_case.h" \
        || ! -f "$IRONWOOD_IDK_ROOT/scripts/GenerateCaseData.java" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/STDLIB_STRING_REVIEW.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/THIRD_PARTY_NOTICES.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/LIBRARY_PORTS.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/OPENJDK_PORTING.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/SOURCE_PROVENANCE.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/STDLIB_S0_SOURCE_REVIEW.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/STDLIB_U1_SOURCE_REVIEW.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/STDLIB_U2_SOURCE_REVIEW.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/STDLIB_U3_SOURCE_REVIEW.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/STDLIB_ROADMAP.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/docs/SYSTEM_OUTPUT_SOURCE_REVIEW.md" ]]; then
    echo "error: packaged IDK is missing license policy, provenance, or notices" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/runtime/src/ironwood_runtime.c" \
        || ! -f "$IRONWOOD_IDK_ROOT/runtime/include/ironwood_runtime.h" ]]; then
    echo "error: packaged IDK is missing the bootstrap native runtime" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/lang/String.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/lang/AutoCloseable.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/lang/CharSequence.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/lang/StringBuilder.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/lang/System.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/lang/ArrayIndexOutOfBoundsException.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/io/PrintStream.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/io/IOException.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/nio/file/Path.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/nio/file/Files.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/pool/ArraySizing.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/pool/ArrayObjectPool.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/pool/MultiArrayObjectPool.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/ironwood-stdlib.ironjar" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/lang/String.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/lang/AutoCloseable.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/lang/CharSequence.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/lang/StringBuilder.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/lang/System.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/lang/ArrayIndexOutOfBoundsException.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/io/PrintStream.ironclass" ]]; then
    echo "error: packaged IDK is missing the standard library" >&2
    exit 1
fi
if [[ ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/io/IOException.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/nio/file/Path.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/nio/file/Files.ironclass" \
        || ! -f "$IRONWOOD_IDK_ROOT/projects/README.md" \
        || ! -f "$IRONWOOD_IDK_ROOT/projects/minigrep/data/sample.txt" \
        || ! -f "$IRONWOOD_IDK_ROOT/projects/minigrep/src/main/ironwood/org/ironwood/minigrep/Minigrep.iron" \
        || ! -f "$IRONWOOD_IDK_ROOT/projects/minigrep/src/main/ironwood/org/ironwood/minigrep/Search.iron" ]]; then
    echo "error: packaged IDK is missing the U2 file API or minigrep project" >&2
    exit 1
fi
for IRONWOOD_POOL_CLASS in ObjectPool ObjectBuilder ArraySizing ArrayObjectPool \
        MultiArrayObjectPool 'MultiArrayObjectPool$ArrayHolder'; do
    IRONWOOD_POOL_SOURCE=${IRONWOOD_POOL_CLASS%%\$*}
    if [[ ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/pool/$IRONWOOD_POOL_SOURCE.iron" \
            || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/pool/$IRONWOOD_POOL_CLASS.ironclass" ]]; then
        echo "error: packaged IDK is missing pool class $IRONWOOD_POOL_CLASS" >&2
        exit 1
    fi
    if ! env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
            "$IRONWOOD_IDK_ROOT/bin/ironjar" --list \
            --file "$IRONWOOD_IDK_ROOT/lib/ironwood-stdlib.ironjar" \
            | grep -Fqx "ironwood/pool/$IRONWOOD_POOL_CLASS.ironclass"; then
        echo "error: packaged IDK archive does not contain $IRONWOOD_POOL_CLASS" >&2
        exit 1
    fi
done
for IRONWOOD_DS_CLASS in ArrayList LinkedList ArrayLinkedList IntArrayList LongArrayList \
        IntLinkedList LongLinkedList ByteMap CharMap IntMap LongMap HashMap IdentityHashMap LinkedHashMap \
        CharSequenceMap ByteBufferMap HashSet IdentityHashSet LinkedHashSet IntSet LongSet; do
    if [[ ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/ds/$IRONWOOD_DS_CLASS.iron" \
            || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/ds/$IRONWOOD_DS_CLASS.ironclass" ]]; then
        echo "error: packaged IDK is missing data-structure class $IRONWOOD_DS_CLASS" >&2
        exit 1
    fi
    if ! env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
            "$IRONWOOD_IDK_ROOT/bin/ironjar" --list \
            --file "$IRONWOOD_IDK_ROOT/lib/ironwood-stdlib.ironjar" \
            | grep -qx "ironwood/ds/$IRONWOOD_DS_CLASS.ironclass"; then
        echo "error: packaged IDK archive does not contain $IRONWOOD_DS_CLASS" >&2
        exit 1
    fi
done
for IRONWOOD_NIO_CLASS in ByteBuffer BufferUnderflowException BufferOverflowException; do
    if [[ ! -f "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/ironwood/nio/$IRONWOOD_NIO_CLASS.iron" \
            || ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/ironwood/nio/$IRONWOOD_NIO_CLASS.ironclass" ]]; then
        echo "error: packaged IDK is missing NIO class $IRONWOOD_NIO_CLASS" >&2
        exit 1
    fi
    if ! env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
            "$IRONWOOD_IDK_ROOT/bin/ironjar" --list \
            --file "$IRONWOOD_IDK_ROOT/lib/ironwood-stdlib.ironjar" \
            | grep -qx "ironwood/nio/$IRONWOOD_NIO_CLASS.ironclass"; then
        echo "error: packaged IDK archive does not contain $IRONWOOD_NIO_CLASS" >&2
        exit 1
    fi
done
IRONWOOD_STDLIB_ARCHIVE_ENTRIES=$(env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironjar" --list \
    --file "$IRONWOOD_IDK_ROOT/lib/ironwood-stdlib.ironjar")
IRONWOOD_TESTING_ARCHIVE_ENTRIES=$(env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironjar" --list \
    --file "$IRONWOOD_IDK_ROOT/lib/ironwood-testing.ironjar")
for IRONWOOD_TESTING_CLASS in Assertions Assumptions TestFailure TestRunner \
        TestSkipped TestSuite; do
    if ! grep -qx "ironwood/testing/$IRONWOOD_TESTING_CLASS.ironclass" \
            <<< "$IRONWOOD_TESTING_ARCHIVE_ENTRIES"; then
        echo "error: packaged IDK testing archive is missing $IRONWOOD_TESTING_CLASS" >&2
        exit 1
    fi
done
for IRONWOOD_LICENSE_ENTRY in META-INF/LICENSES/LICENSE \
        META-INF/LICENSES/LICENSE-APACHE \
        META-INF/LICENSES/LICENSE-MIT \
        META-INF/LICENSES/LICENSE_MECHANICS \
        META-INF/LICENSES/GPL-2.0-only.txt \
        META-INF/LICENSES/Classpath-exception-2.0.txt \
        META-INF/LICENSES/THIRD_PARTY_NOTICES.md \
        META-INF/LICENSES/SOURCE_PROVENANCE.md \
        META-INF/LICENSES/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md \
        META-INF/LICENSES/STDLIB_S0_SOURCE_REVIEW.md \
        META-INF/LICENSES/STDLIB_U1_SOURCE_REVIEW.md \
        META-INF/LICENSES/STDLIB_U2_SOURCE_REVIEW.md \
        META-INF/LICENSES/STDLIB_U3_SOURCE_REVIEW.md; do
    if ! grep -qx "$IRONWOOD_LICENSE_ENTRY" <<< "$IRONWOOD_STDLIB_ARCHIVE_ENTRIES"; then
        echo "error: packaged IDK standard-library archive is missing $IRONWOOD_LICENSE_ENTRY" >&2
        exit 1
    fi
done
for IRONWOOD_PROJECT_SCRIPT in compile.sh link.sh run.sh; do
    if [[ ! -x "$IRONWOOD_IDK_ROOT/projects/minigrep/$IRONWOOD_PROJECT_SCRIPT" ]]; then
        echo "error: packaged IDK project script is not executable: minigrep/$IRONWOOD_PROJECT_SCRIPT" >&2
        exit 1
    fi
done
while IFS= read -r IRONWOOD_STDLIB_SOURCE; do
    IRONWOOD_STDLIB_RELATIVE=${IRONWOOD_STDLIB_SOURCE#"$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood/"}
    IRONWOOD_STDLIB_CLASS=${IRONWOOD_STDLIB_RELATIVE%.iron}.ironclass
    if [[ ! -f "$IRONWOOD_IDK_ROOT/lib/stdlib/$IRONWOOD_STDLIB_CLASS" ]]; then
        echo "error: packaged IDK is missing loose class $IRONWOOD_STDLIB_CLASS" >&2
        exit 1
    fi
    if ! grep -qx "$IRONWOOD_STDLIB_CLASS" <<< "$IRONWOOD_STDLIB_ARCHIVE_ENTRIES"; then
        echo "error: packaged IDK standard-library archive is missing $IRONWOOD_STDLIB_CLASS" >&2
        exit 1
    fi
done < <(find "$IRONWOOD_IDK_ROOT/stdlib/src/main/ironwood" -type f -name '*.iron' | sort)
if ! env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
        "$IRONWOOD_IDK_ROOT/bin/ironjar" --list \
        --file "$IRONWOOD_IDK_ROOT/lib/ironwood-stdlib.ironjar" \
        | grep -qx 'ironwood/lang/System.ironclass'; then
    echo "error: packaged IDK standard-library archive does not contain System" >&2
    exit 1
fi
for IRONWOOD_EXAMPLE in basic hello controlflow objects inheritance exceptions checkedexceptions resources reclamation textreclamation echo foundations collections arguments stacktraces staticinitialization classicswitch enums multidimensionalarrays runtimefailures allocationfailure instanceofpatterns; do
    for IRONWOOD_EXAMPLE_SCRIPT in compile.sh link.sh run.sh; do
        if [[ ! -x "$IRONWOOD_IDK_ROOT/examples/$IRONWOOD_EXAMPLE/$IRONWOOD_EXAMPLE_SCRIPT" ]]; then
            echo "error: packaged IDK example script is not executable: $IRONWOOD_EXAMPLE/$IRONWOOD_EXAMPLE_SCRIPT" >&2
            exit 1
        fi
    done
done

for IRONWOOD_STREAM_SCRIPT in compile.sh link.sh; do
    env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
        PATH="$IRONWOOD_IDK_ROOT/bin:/usr/bin:/bin" \
        "$IRONWOOD_IDK_ROOT/projects/streaming/$IRONWOOD_STREAM_SCRIPT"
done
IRONWOOD_STREAM_OUTPUT=$(printf 'hello world\n' | env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin "$IRONWOOD_IDK_ROOT/projects/streaming/run.sh" wc)
if [[ "$IRONWOOD_STREAM_OUTPUT" != '12 12 2 1' ]]; then
    echo "error: packaged IDK streaming wc produced unexpected output" >&2
    exit 1
fi
printf '\000\377A' > "$IRONWOOD_TEST_DIR/stream-input.bin"
"$IRONWOOD_IDK_ROOT/projects/streaming/run.sh" cp \
    "$IRONWOOD_TEST_DIR/stream-input.bin" "$IRONWOOD_TEST_DIR/stream-copy.bin"
"$IRONWOOD_IDK_ROOT/projects/streaming/run.sh" cat \
    "$IRONWOOD_TEST_DIR/stream-copy.bin" > "$IRONWOOD_TEST_DIR/stream-cat.bin"
cmp "$IRONWOOD_TEST_DIR/stream-input.bin" "$IRONWOOD_TEST_DIR/stream-cat.bin"

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_IDK_ROOT/bin:/usr/bin:/bin" \
    "$IRONWOOD_IDK_ROOT/projects/minigrep/compile.sh"
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_IDK_ROOT/bin:/usr/bin:/bin" \
    "$IRONWOOD_IDK_ROOT/projects/minigrep/link.sh"
IRONWOOD_MINIGREP_OUTPUT=$(env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/projects/minigrep/run.sh")
if ! grep -Fqx 'A body stays strong through every season.' <<< "$IRONWOOD_MINIGREP_OUTPUT" \
        || ! grep -Fqx 'exit status: 0' <<< "$IRONWOOD_MINIGREP_OUTPUT"; then
    echo "error: packaged IDK minigrep produced unexpected output" >&2
    printf '%s\n' "$IRONWOOD_MINIGREP_OUTPUT" >&2
    exit 1
fi

IRONWOOD_EXPECTED_VERSION=$(tr -d '[:space:]' < "$IRONWOOD_IDK_ROOT/VERSION")
IRONWOOD_VERSION_OUTPUT=$(env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" -v)
if [[ "$IRONWOOD_VERSION_OUTPUT" != "ironwoodc $IRONWOOD_EXPECTED_VERSION" ]]; then
    echo "error: packaged compiler reported '$IRONWOOD_VERSION_OUTPUT', expected 'ironwoodc $IRONWOOD_EXPECTED_VERSION'" >&2
    exit 1
fi
IRONWOOD_IRONDOC_VERSION_OUTPUT=$(env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/irondoc" --version)
if [[ "$IRONWOOD_IRONDOC_VERSION_OUTPUT" != "irondoc $IRONWOOD_EXPECTED_VERSION" ]]; then
    echo "error: packaged IronDocs reported '$IRONWOOD_IRONDOC_VERSION_OUTPUT', expected 'irondoc $IRONWOOD_EXPECTED_VERSION'" >&2
    exit 1
fi

for IRONWOOD_EXAMPLE in basic hello controlflow objects inheritance exceptions checkedexceptions resources reclamation textreclamation echo foundations collections arguments stacktraces staticinitialization classicswitch enums multidimensionalarrays runtimefailures allocationfailure instanceofpatterns; do
    env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
        PATH="$IRONWOOD_IDK_ROOT/bin:/usr/bin:/bin" \
        "$IRONWOOD_IDK_ROOT/examples/$IRONWOOD_EXAMPLE/compile.sh"
    env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
        PATH="$IRONWOOD_IDK_ROOT/bin:/usr/bin:/bin" \
        "$IRONWOOD_IDK_ROOT/examples/$IRONWOOD_EXAMPLE/link.sh"
    env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
        PATH=/usr/bin:/bin \
        "$IRONWOOD_IDK_ROOT/examples/$IRONWOOD_EXAMPLE/run.sh"
done

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    "$IRONWOOD_IDK_ROOT/examples/basic/src/main/ironwood/org/ironwood/basic/Main.iron" \
    -d "$IRONWOOD_TEST_DIR/main-classes"

IRONWOOD_MAIN_ARCHIVE="$IRONWOOD_TEST_DIR/main.ironjar"
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironjar" \
    --create --file "$IRONWOOD_MAIN_ARCHIVE" "$IRONWOOD_TEST_DIR/main-classes"
if ! env -u JAVA_HOME -u IRONWOOD_LLVM_HOME PATH=/usr/bin:/bin \
        "$IRONWOOD_IDK_ROOT/bin/ironjar" --list --file "$IRONWOOD_MAIN_ARCHIVE" \
        | grep -qx 'org/ironwood/basic/Main.ironclass'; then
    echo "error: packaged IDK ironjar did not list the archived Main class" >&2
    exit 1
fi

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_MAIN_ARCHIVE" \
    --main-class org.ironwood.basic.Main \
    -o "$IRONWOOD_MAIN_OUTPUT"

set +e
"$IRONWOOD_MAIN_OUTPUT"
IRONWOOD_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_EXIT_STATUS -ne 42 ]]; then
    echo "error: packaged native example returned $IRONWOOD_EXIT_STATUS, expected 42" >&2
    exit 1
fi

IRONWOOD_MAIN_FROM_CLASS="$IRONWOOD_TEST_DIR/main-from-ironclass"
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_TEST_DIR/main-classes" \
    --main-class org.ironwood.basic.Main \
    -o "$IRONWOOD_MAIN_FROM_CLASS"
set +e
"$IRONWOOD_MAIN_FROM_CLASS"
IRONWOOD_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_EXIT_STATUS -ne 42 ]]; then
    echo "error: packaged IDK main linked from ironjar returned $IRONWOOD_EXIT_STATUS, expected 42" >&2
    exit 1
fi

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    "$IRONWOOD_IDK_ROOT/examples/controlflow/src/main/ironwood/org/ironwood/controlflow/MethodsAndControlFlow.iron" \
    -d "$IRONWOOD_TEST_DIR/control-flow-classes"
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_TEST_DIR/control-flow-classes" \
    --main-class org.ironwood.controlflow.MethodsAndControlFlow \
    -o "$IRONWOOD_CONTROL_FLOW_OUTPUT" \
    -O3

set +e
"$IRONWOOD_CONTROL_FLOW_OUTPUT"
IRONWOOD_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_EXIT_STATUS -ne 30 ]]; then
    echo "error: packaged optimized example returned $IRONWOOD_EXIT_STATUS, expected 30" >&2
    exit 1
fi

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    "$IRONWOOD_IDK_ROOT/examples/objects/src/main/ironwood/org/ironwood/objects/Objects.iron" \
    --source-path "$IRONWOOD_IDK_ROOT/examples/objects/src/main/ironwood" \
    -d "$IRONWOOD_TEST_DIR/object-classes"
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_TEST_DIR/object-classes" \
    --main-class org.ironwood.objects.Objects \
    -o "$IRONWOOD_OBJECTS_OUTPUT" \
    -O2

set +e
"$IRONWOOD_OBJECTS_OUTPUT"
IRONWOOD_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_EXIT_STATUS -ne 30 ]]; then
    echo "error: packaged object example returned $IRONWOOD_EXIT_STATUS, expected 30" >&2
    exit 1
fi

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    "$IRONWOOD_IDK_ROOT/examples/inheritance/src/main/ironwood/org/ironwood/inheritance/InheritanceAndInterfaces.iron" \
    --source-path "$IRONWOOD_IDK_ROOT/examples/inheritance/src/main/ironwood" \
    -d "$IRONWOOD_TEST_DIR/inheritance-classes"
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_TEST_DIR/inheritance-classes" \
    --main-class org.ironwood.inheritance.InheritanceAndInterfaces \
    -o "$IRONWOOD_INHERITANCE_OUTPUT" \
    -O1

set +e
"$IRONWOOD_INHERITANCE_OUTPUT"
IRONWOOD_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_EXIT_STATUS -ne 42 ]]; then
    echo "error: packaged inheritance/interface example returned $IRONWOOD_EXIT_STATUS, expected 42" >&2
    exit 1
fi

env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    "$IRONWOOD_IDK_ROOT/examples/objects/src/main/ironwood/org/ironwood/objects/Counter.iron" \
    -d "$IRONWOOD_CLASS_OUTPUT"
if [[ ! -f "$IRONWOOD_CLASS_OUTPUT/org/ironwood/objects/Counter.ironclass" ]]; then
    echo "error: packaged IDK did not create Counter.ironclass" >&2
    exit 1
fi
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    "$IRONWOOD_IDK_ROOT/examples/objects/src/main/ironwood/org/ironwood/objects/Objects.iron" \
    --source-path "$IRONWOOD_TEST_DIR/no-source-path" \
    -cp "$IRONWOOD_CLASS_OUTPUT" \
    -d "$IRONWOOD_TEST_DIR/classpath-application-classes"
env -u JAVA_HOME -u IRONWOOD_LLVM_HOME \
    PATH=/usr/bin:/bin \
    "$IRONWOOD_IDK_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_TEST_DIR/classpath-application-classes:$IRONWOOD_CLASS_OUTPUT" \
    --main-class org.ironwood.objects.Objects \
    -o "$IRONWOOD_CLASS_PROGRAM"
set +e
"$IRONWOOD_CLASS_PROGRAM"
IRONWOOD_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_EXIT_STATUS -ne 30 ]]; then
    echo "error: packaged IDK classpath program returned $IRONWOOD_EXIT_STATUS, expected 30" >&2
    exit 1
fi

echo "ok - IDK archive compiles uniform ironclass and native programs at O0 through O3"
