#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
IRONWOOD_ARCHIVE=${1:-}
if [[ -z "$IRONWOOD_ARCHIVE" || ! -f "$IRONWOOD_ARCHIVE" ]]; then
    echo "usage: test-package.sh <ironwood host archive.tar.gz>" >&2
    exit 1
fi

IRONWOOD_SYSTEM_JAVA=$(command -v java || true)
if [[ -z "$IRONWOOD_SYSTEM_JAVA" || ! -x "$IRONWOOD_SYSTEM_JAVA" ]]; then
    echo "error: a system Java 21 runtime is required to test the host package" >&2
    exit 1
fi
IRONWOOD_JAVA_BIN=$(CDPATH= cd -- "$(dirname -- "$IRONWOOD_SYSTEM_JAVA")" && pwd)

IRONWOOD_SYSTEM_LLVM_HOME=${IRONWOOD_LLVM_HOME:-}
if [[ -z "$IRONWOOD_SYSTEM_LLVM_HOME" ]]; then
    if command -v llvm-config-23 >/dev/null 2>&1; then
        IRONWOOD_SYSTEM_LLVM_HOME=$(llvm-config-23 --prefix)
    elif command -v llvm-config >/dev/null 2>&1; then
        IRONWOOD_SYSTEM_LLVM_HOME=$(llvm-config --prefix)
    elif command -v brew >/dev/null 2>&1 \
            && IRONWOOD_SYSTEM_LLVM_HOME=$(brew --prefix llvm@23 2>/dev/null); then
        :
    elif command -v brew >/dev/null 2>&1 \
            && IRONWOOD_SYSTEM_LLVM_HOME=$(brew --prefix llvm 2>/dev/null); then
        :
    else
        for IRONWOOD_LLVM_CANDIDATE in \
                /opt/homebrew/opt/llvm@23 \
                /opt/homebrew/opt/llvm \
                /usr/local/opt/llvm@23 \
                /usr/local/opt/llvm \
                /usr/lib/llvm-23; do
            if [[ -x "$IRONWOOD_LLVM_CANDIDATE/bin/llvm-config" ]]; then
                IRONWOOD_SYSTEM_LLVM_HOME=$IRONWOOD_LLVM_CANDIDATE
                break
            fi
        done
        if [[ -z "$IRONWOOD_SYSTEM_LLVM_HOME" ]]; then
            echo "error: LLVM 23 is required; set IRONWOOD_LLVM_HOME or install llvm-config" >&2
            exit 1
        fi
    fi
fi
IRONWOOD_SYSTEM_LLVM_HOME=$(CDPATH= cd -- "$IRONWOOD_SYSTEM_LLVM_HOME" && pwd)

IRONWOOD_REQUIRED_LLVM_TOOLS=(clang llvm-as opt llc llvm-objcopy llvm-config)
for IRONWOOD_TOOL in "${IRONWOOD_REQUIRED_LLVM_TOOLS[@]}"; do
    if [[ ! -x "$IRONWOOD_SYSTEM_LLVM_HOME/bin/$IRONWOOD_TOOL" ]]; then
        echo "error: LLVM home is missing executable bin/$IRONWOOD_TOOL: $IRONWOOD_SYSTEM_LLVM_HOME" >&2
        exit 1
    fi
done
IRONWOOD_LLVM_VERSION=$("$IRONWOOD_SYSTEM_LLVM_HOME/bin/llvm-config" --version)
if [[ "${IRONWOOD_LLVM_VERSION%%.*}" != 23 ]]; then
    echo "error: host package smoke test requires LLVM 23.x, found $IRONWOOD_LLVM_VERSION" >&2
    exit 1
fi

IRONWOOD_TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ironwood-package-test.XXXXXX")
trap 'rm -rf "$IRONWOOD_TEST_DIR"' EXIT
IRONWOOD_EXTRACT_DIR="$IRONWOOD_TEST_DIR/extracted"
IRONWOOD_RELOCATE_DIR="$IRONWOOD_TEST_DIR/relocated distribution"
mkdir -p "$IRONWOOD_EXTRACT_DIR" "$IRONWOOD_RELOCATE_DIR"
tar -xzf "$IRONWOOD_ARCHIVE" -C "$IRONWOOD_EXTRACT_DIR"

IRONWOOD_ROOTS=("$IRONWOOD_EXTRACT_DIR"/ironwood-*)
if [[ ${#IRONWOOD_ROOTS[@]} -ne 1 || ! -d "${IRONWOOD_ROOTS[0]}" ]]; then
    echo "error: host archive must contain exactly one ironwood-* directory" >&2
    exit 1
fi
mv "${IRONWOOD_ROOTS[0]}" "$IRONWOOD_RELOCATE_DIR/ironwood relocated"
IRONWOOD_PACKAGE_ROOT="$IRONWOOD_RELOCATE_DIR/ironwood relocated"

IRONWOOD_REQUIRED_FILES=(
    bin/ironwoodc
    bin/ironjar
    lib/ironwoodc.jar
    lib/ironwood-stdlib.ironjar
    lib/ironwood-testing.ironjar
    VERSION
    LICENSE
    LICENSE-APACHE
    LICENSE-MIT
    LICENSE_MECHANICS
    LICENSES/GPL-2.0-only.txt
    LICENSES/Classpath-exception-2.0.txt
    LICENSES/Unicode-15.0.txt
    runtime/src/ironwood_case.c
    runtime/src/ironwood_case_data.h
    runtime/include/ironwood_case.h
    scripts/GenerateCaseData.java
    docs/STDLIB_STRING_REVIEW.md
    THIRD_PARTY_NOTICES.md
    docs/LIBRARY_PORTS.md
    docs/MEMORY.md
    docs/OPENJDK_PORTING.md
    docs/SOURCE_PROVENANCE.md
    docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md
    docs/STDLIB_S0_SOURCE_REVIEW.md
    docs/STDLIB_U1_SOURCE_REVIEW.md
    docs/STDLIB_U2_SOURCE_REVIEW.md
    docs/STDLIB_U3_SOURCE_REVIEW.md
    docs/STDLIB_ROADMAP.md
    docs/SYSTEM_OUTPUT_SOURCE_REVIEW.md
    docs/TESTING.md
    projects/README.md
    projects/streaming/compile.sh
    projects/streaming/link.sh
    projects/streaming/run.sh
    projects/streaming/src/main/ironwood/org/ironwood/streaming/Streaming.iron
    stdlib/src/main/ironwood/ironwood/io/InputStream.iron
    stdlib/src/main/ironwood/ironwood/io/BufferedReader.iron
    stdlib/src/testing/ironwood/ironwood/testing/Assertions.iron
    stdlib/src/testing/ironwood/ironwood/testing/TestRunner.iron
    stdlib/src/testing/ironwood/ironwood/testing/TestSuite.iron
    projects/minigrep/compile.sh
    projects/minigrep/link.sh
    projects/minigrep/run.sh
    projects/minigrep/data/sample.txt
    projects/minigrep/src/main/ironwood/org/ironwood/minigrep/Minigrep.iron
    projects/minigrep/src/main/ironwood/org/ironwood/minigrep/Search.iron
    examples/basic/compile.sh
    examples/basic/link.sh
    examples/basic/run.sh
    examples/basic/src/main/ironwood/org/ironwood/basic/Main.iron
    examples/hello/compile.sh
    examples/hello/link.sh
    examples/hello/run.sh
    examples/hello/src/main/ironwood/org/ironwood/hello/HelloWorld.iron
    examples/controlflow/compile.sh
    examples/controlflow/link.sh
    examples/controlflow/run.sh
    examples/controlflow/src/main/ironwood/org/ironwood/controlflow/MethodsAndControlFlow.iron
    examples/objects/compile.sh
    examples/objects/link.sh
    examples/objects/run.sh
    examples/objects/src/main/ironwood/org/ironwood/objects/Objects.iron
    examples/objects/src/main/ironwood/org/ironwood/objects/Counter.iron
    examples/inheritance/compile.sh
    examples/inheritance/link.sh
    examples/inheritance/run.sh
    examples/inheritance/src/main/ironwood/org/ironwood/inheritance/InheritanceAndInterfaces.iron
    examples/inheritance/src/main/ironwood/org/ironwood/inheritance/Value.iron
    examples/exceptions/compile.sh
    examples/exceptions/link.sh
    examples/exceptions/run.sh
    examples/exceptions/src/main/ironwood/org/ironwood/exceptions/Exceptions.iron
    examples/exceptions/src/main/ironwood/org/ironwood/exceptions/Failure.iron
    examples/exceptions/src/main/ironwood/org/ironwood/exceptions/SpecificFailure.iron
    examples/exceptions/src/main/ironwood/org/ironwood/exceptions/Tracker.iron
    examples/checkedexceptions/compile.sh
    examples/checkedexceptions/link.sh
    examples/checkedexceptions/run.sh
    examples/checkedexceptions/src/main/ironwood/org/ironwood/checkedexceptions/CheckedExceptions.iron
    examples/resources/compile.sh
    examples/resources/link.sh
    examples/resources/run.sh
    examples/resources/src/main/ironwood/org/ironwood/resources/DeterministicResources.iron
    examples/reclamation/compile.sh
    examples/reclamation/link.sh
    examples/reclamation/run.sh
    examples/reclamation/src/main/ironwood/org/ironwood/reclamation/ExplicitReclamation.iron
    examples/reclamation/src/main/ironwood/org/ironwood/reclamation/Cell.iron
    examples/textreclamation/compile.sh
    examples/textreclamation/link.sh
    examples/textreclamation/run.sh
    examples/textreclamation/src/main/ironwood/org/ironwood/textreclamation/TextReclamation.iron
    examples/echo/compile.sh
    examples/echo/link.sh
    examples/echo/run.sh
    examples/echo/src/main/ironwood/org/ironwood/echo/Echo.iron
    examples/foundations/compile.sh
    examples/foundations/link.sh
    examples/foundations/run.sh
    examples/foundations/src/main/ironwood/org/ironwood/foundations/ArraysStringsAndIo.iron
    examples/collections/compile.sh
    examples/collections/link.sh
    examples/collections/run.sh
    examples/collections/src/main/ironwood/org/ironwood/collections/ReusableCollections.iron
    examples/arguments/compile.sh
    examples/arguments/link.sh
    examples/arguments/run.sh
    examples/arguments/src/main/ironwood/org/ironwood/arguments/CommandLineArguments.iron
    examples/stacktraces/compile.sh
    examples/stacktraces/link.sh
    examples/stacktraces/run.sh
    examples/stacktraces/src/main/ironwood/org/ironwood/stacktraces/StackTraces.iron
    examples/staticinitialization/compile.sh
    examples/staticinitialization/link.sh
    examples/staticinitialization/run.sh
    examples/staticinitialization/src/main/ironwood/org/ironwood/staticinitialization/StaticInitialization.iron
    examples/classicswitch/compile.sh
    examples/classicswitch/link.sh
    examples/classicswitch/run.sh
    examples/classicswitch/src/main/ironwood/org/ironwood/classicswitch/ClassicSwitch.iron
    examples/enums/compile.sh
    examples/enums/link.sh
    examples/enums/run.sh
    examples/enums/src/main/ironwood/org/ironwood/enums/Enums.iron
    examples/multidimensionalarrays/compile.sh
    examples/multidimensionalarrays/link.sh
    examples/multidimensionalarrays/run.sh
    examples/multidimensionalarrays/src/main/ironwood/org/ironwood/multidimensionalarrays/MultidimensionalArrays.iron
    examples/stringconcatenation/compile.sh
    examples/stringconcatenation/link.sh
    examples/stringconcatenation/run.sh
    examples/stringconcatenation/src/main/ironwood/org/ironwood/stringconcatenation/StringConcatenation.iron
    examples/primitivegenerics/compile.sh
    examples/primitivegenerics/link.sh
    examples/primitivegenerics/run.sh
    examples/primitivegenerics/src/main/ironwood/org/ironwood/primitivegenerics/PrimitiveGenerics.iron
    examples/overridedirective/compile.sh
    examples/overridedirective/link.sh
    examples/overridedirective/run.sh
    examples/overridedirective/src/main/ironwood/org/ironwood/overridedirective/OverrideDirective.iron
    examples/textblocks/compile.sh
    examples/textblocks/link.sh
    examples/textblocks/run.sh
    examples/textblocks/src/main/ironwood/org/ironwood/textblocks/TextBlocks.iron
    examples/runtimefailures/compile.sh
    examples/runtimefailures/link.sh
    examples/runtimefailures/run.sh
    examples/runtimefailures/src/main/ironwood/org/ironwood/runtimefailures/CatchableRuntimeFailures.iron
    examples/allocationfailure/compile.sh
    examples/allocationfailure/link.sh
    examples/allocationfailure/run.sh
    examples/allocationfailure/src/main/ironwood/org/ironwood/allocationfailure/CatchableAllocationFailure.iron
    examples/instanceofpatterns/compile.sh
    examples/instanceofpatterns/link.sh
    examples/instanceofpatterns/run.sh
    examples/instanceofpatterns/src/main/ironwood/org/ironwood/instanceofpatterns/InstanceOfPatterns.iron
    examples/statements/compile.sh
    examples/statements/link.sh
    examples/statements/run.sh
    examples/statements/src/main/ironwood/org/ironwood/statements/RemainingStatements.iron
    examples/multicatch/compile.sh
    examples/multicatch/link.sh
    examples/multicatch/run.sh
    examples/multicatch/src/main/ironwood/org/ironwood/multicatch/MultiCatchAndPreciseRethrow.iron
    examples/arrayinitializers/compile.sh
    examples/arrayinitializers/link.sh
    examples/arrayinitializers/run.sh
    examples/arrayinitializers/src/main/ironwood/org/ironwood/arrayinitializers/ArrayInitializers.iron
    examples/binaryliterals/compile.sh
    examples/binaryliterals/link.sh
    examples/binaryliterals/run.sh
    examples/binaryliterals/src/main/ironwood/org/ironwood/binaryliterals/BinaryIntegerLiterals.iron
    examples/staticimports/compile.sh
    examples/staticimports/link.sh
    examples/staticimports/run.sh
    examples/staticimports/src/main/ironwood/org/ironwood/staticimports/Constants.iron
    examples/staticimports/src/main/ironwood/org/ironwood/staticimports/Operations.iron
    examples/staticimports/src/main/ironwood/org/ironwood/staticimports/Other.iron
    examples/staticimports/src/main/ironwood/org/ironwood/staticimports/StaticImports.iron
    examples/modernswitch/compile.sh
    examples/modernswitch/link.sh
    examples/modernswitch/run.sh
    examples/modernswitch/src/main/ironwood/org/ironwood/modernswitch/ModernSwitch.iron
    stdlib/src/main/ironwood/ironwood/lang/ArrayIndexOutOfBoundsException.iron
    lib/stdlib/ironwood/lang/ArrayIndexOutOfBoundsException.ironclass
    stdlib/src/main/ironwood/ironwood/lang/OutOfMemoryError.iron
    lib/stdlib/ironwood/lang/OutOfMemoryError.ironclass
    runtime/include/ironwood_runtime.h
    runtime/src/ironwood_runtime.c
    stdlib/src/main/ironwood/ironwood/lang/String.iron
    stdlib/src/main/ironwood/ironwood/lang/AutoCloseable.iron
    stdlib/src/main/ironwood/ironwood/lang/StringBuilder.iron
    stdlib/src/main/ironwood/ironwood/lang/System.iron
    stdlib/src/main/ironwood/ironwood/io/PrintStream.iron
    stdlib/src/main/ironwood/ironwood/io/IOException.iron
    stdlib/src/main/ironwood/ironwood/nio/file/Path.iron
    stdlib/src/main/ironwood/ironwood/nio/file/Files.iron
    stdlib/src/main/ironwood/ironwood/pool/ArraySizing.iron
    stdlib/src/main/ironwood/ironwood/pool/ArrayObjectPool.iron
    stdlib/src/main/ironwood/ironwood/pool/MultiArrayObjectPool.iron
    stdlib/src/main/ironwood/ironwood/ds/ArrayList.iron
    stdlib/src/main/ironwood/ironwood/ds/LinkedList.iron
    stdlib/src/main/ironwood/ironwood/ds/ArrayLinkedList.iron
    stdlib/src/main/ironwood/ironwood/ds/IntArrayList.iron
    stdlib/src/main/ironwood/ironwood/ds/LongArrayList.iron
    stdlib/src/main/ironwood/ironwood/ds/IntLinkedList.iron
    stdlib/src/main/ironwood/ironwood/ds/LongLinkedList.iron
    stdlib/src/main/ironwood/ironwood/ds/ByteMap.iron
    stdlib/src/main/ironwood/ironwood/ds/CharMap.iron
    stdlib/src/main/ironwood/ironwood/ds/IntMap.iron
    stdlib/src/main/ironwood/ironwood/ds/LongMap.iron
    stdlib/src/main/ironwood/ironwood/ds/HashMap.iron
    stdlib/src/main/ironwood/ironwood/ds/IdentityHashMap.iron
    stdlib/src/main/ironwood/ironwood/ds/LinkedHashMap.iron
    stdlib/src/main/ironwood/ironwood/ds/CharSequenceMap.iron
    stdlib/src/main/ironwood/ironwood/ds/HashSet.iron
    stdlib/src/main/ironwood/ironwood/ds/IdentityHashSet.iron
    stdlib/src/main/ironwood/ironwood/ds/LinkedHashSet.iron
    stdlib/src/main/ironwood/ironwood/ds/IntSet.iron
    stdlib/src/main/ironwood/ironwood/ds/LongSet.iron
    stdlib/src/main/ironwood/ironwood/ds/ByteBufferMap.iron
    stdlib/src/main/ironwood/ironwood/nio/ByteBuffer.iron
    stdlib/src/main/ironwood/ironwood/nio/BufferUnderflowException.iron
    stdlib/src/main/ironwood/ironwood/nio/BufferOverflowException.iron
    lib/stdlib/ironwood/lang/AutoCloseable.ironclass
    lib/stdlib/ironwood/lang/String.ironclass
    lib/stdlib/ironwood/lang/CharSequence.ironclass
    lib/stdlib/ironwood/lang/StringBuilder.ironclass
    lib/stdlib/ironwood/lang/System.ironclass
    lib/stdlib/ironwood/io/PrintStream.ironclass
    lib/stdlib/ironwood/io/IOException.ironclass
    lib/stdlib/ironwood/nio/file/Path.ironclass
    lib/stdlib/ironwood/nio/file/Files.ironclass
    lib/stdlib/ironwood/pool/ArraySizing.ironclass
    lib/stdlib/ironwood/pool/ArrayObjectPool.ironclass
    lib/stdlib/ironwood/pool/MultiArrayObjectPool.ironclass
    'lib/stdlib/ironwood/pool/MultiArrayObjectPool$ArrayHolder.ironclass'
    lib/stdlib/ironwood/ds/ArrayList.ironclass
    lib/stdlib/ironwood/ds/LinkedList.ironclass
    lib/stdlib/ironwood/ds/ArrayLinkedList.ironclass
    lib/stdlib/ironwood/ds/IntArrayList.ironclass
    lib/stdlib/ironwood/ds/LongArrayList.ironclass
    lib/stdlib/ironwood/ds/IntLinkedList.ironclass
    lib/stdlib/ironwood/ds/LongLinkedList.ironclass
    lib/stdlib/ironwood/ds/ByteMap.ironclass
    lib/stdlib/ironwood/ds/CharMap.ironclass
    lib/stdlib/ironwood/ds/IntMap.ironclass
    lib/stdlib/ironwood/ds/LongMap.ironclass
    lib/stdlib/ironwood/ds/HashMap.ironclass
    lib/stdlib/ironwood/ds/IdentityHashMap.ironclass
    lib/stdlib/ironwood/ds/LinkedHashMap.ironclass
    lib/stdlib/ironwood/ds/CharSequenceMap.ironclass
    lib/stdlib/ironwood/ds/HashSet.ironclass
    lib/stdlib/ironwood/ds/IdentityHashSet.ironclass
    lib/stdlib/ironwood/ds/LinkedHashSet.ironclass
    lib/stdlib/ironwood/ds/IntSet.ironclass
    lib/stdlib/ironwood/ds/LongSet.ironclass
    lib/stdlib/ironwood/ds/ByteBufferMap.ironclass
    lib/stdlib/ironwood/nio/ByteBuffer.ironclass
    lib/stdlib/ironwood/nio/BufferUnderflowException.ironclass
    lib/stdlib/ironwood/nio/BufferOverflowException.ironclass
)
for IRONWOOD_FILE in "${IRONWOOD_REQUIRED_FILES[@]}"; do
    if [[ ! -f "$IRONWOOD_PACKAGE_ROOT/$IRONWOOD_FILE" ]]; then
        echo "error: host package is missing $IRONWOOD_FILE" >&2
        exit 1
    fi
done
for IRONWOOD_PROJECT_SCRIPT in compile.sh link.sh run.sh; do
    if [[ ! -x "$IRONWOOD_PACKAGE_ROOT/projects/minigrep/$IRONWOOD_PROJECT_SCRIPT" ]]; then
        echo "error: packaged project script is not executable: minigrep/$IRONWOOD_PROJECT_SCRIPT" >&2
        exit 1
    fi
done
for IRONWOOD_TOOL in ironwoodc ironjar; do
    if [[ ! -x "$IRONWOOD_PACKAGE_ROOT/bin/$IRONWOOD_TOOL" ]]; then
        echo "error: host package tool is not executable: $IRONWOOD_TOOL" >&2
        exit 1
    fi
done
for IRONWOOD_EXAMPLE in basic hello controlflow objects inheritance exceptions checkedexceptions resources reclamation textreclamation echo foundations collections arguments stacktraces staticinitialization classicswitch enums multidimensionalarrays stringconcatenation primitivegenerics overridedirective textblocks runtimefailures allocationfailure instanceofpatterns statements multicatch arrayinitializers binaryliterals staticimports modernswitch; do
    for IRONWOOD_EXAMPLE_SCRIPT in compile.sh link.sh run.sh; do
        if [[ ! -x "$IRONWOOD_PACKAGE_ROOT/examples/$IRONWOOD_EXAMPLE/$IRONWOOD_EXAMPLE_SCRIPT" ]]; then
            echo "error: packaged example script is not executable: $IRONWOOD_EXAMPLE/$IRONWOOD_EXAMPLE_SCRIPT" >&2
            exit 1
        fi
    done
done

IRONWOOD_SYSTEM_PATH="$IRONWOOD_JAVA_BIN:/usr/bin:/bin"
if ! env -u JAVA_HOME PATH="$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" --list \
        --file "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-stdlib.ironjar" \
        | grep -qx 'ironwood/lang/System.ironclass'; then
    echo "error: packaged standard-library archive does not contain System" >&2
    exit 1
fi
IRONWOOD_TESTING_ARCHIVE_ENTRIES=$(env -u JAVA_HOME PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" --list \
    --file "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-testing.ironjar")
for IRONWOOD_TESTING_CLASS in Assertions Assumptions TestFailure TestRunner \
        TestSkipped TestSuite; do
    if ! grep -qx "ironwood/testing/$IRONWOOD_TESTING_CLASS.ironclass" \
            <<< "$IRONWOOD_TESTING_ARCHIVE_ENTRIES"; then
        echo "error: packaged testing archive is missing $IRONWOOD_TESTING_CLASS" >&2
        exit 1
    fi
done
IRONWOOD_PACKAGED_TEST_CLASSES="$IRONWOOD_TEST_DIR/packaged-testing-classes"
IRONWOOD_PACKAGED_TEST_SOURCE="$IRONWOOD_PROJECT_ROOT/stdlib/test/ironwood/testing/FrameworkPassingTests.iron"
if env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
        PATH="$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
        "$IRONWOOD_PACKAGED_TEST_SOURCE" \
        --source-path "$IRONWOOD_PROJECT_ROOT/stdlib/test" \
        -d "$IRONWOOD_PACKAGED_TEST_CLASSES" >/dev/null 2>&1; then
    echo "error: packaged compiler found ironwood.testing without the optional archive" >&2
    exit 1
fi
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    "$IRONWOOD_PACKAGED_TEST_SOURCE" \
    --source-path "$IRONWOOD_PROJECT_ROOT/stdlib/test" \
    -cp "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-testing.ironjar" \
    -d "$IRONWOOD_PACKAGED_TEST_CLASSES" >/dev/null
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" --link \
    -cp "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-testing.ironjar:$IRONWOOD_PACKAGED_TEST_CLASSES" \
    --main-class ironwood.testing.FrameworkPassingTests \
    -o "$IRONWOOD_TEST_DIR/packaged-testing" -O3 \
    --llvm-home "$IRONWOOD_SYSTEM_LLVM_HOME" >/dev/null
IRONWOOD_PACKAGED_TEST_OUTPUT=$("$IRONWOOD_TEST_DIR/packaged-testing")
if [[ ${IRONWOOD_PACKAGED_TEST_OUTPUT##*$'\n'} \
        != "PASS: 3 passed, 1 skipped, 4 total" ]]; then
    echo "error: packaged testing module produced unexpected output" >&2
    printf '%s\n' "$IRONWOOD_PACKAGED_TEST_OUTPUT" >&2
    exit 1
fi
for IRONWOOD_POOL_CLASS in ObjectPool ObjectBuilder ArraySizing ArrayObjectPool \
        MultiArrayObjectPool 'MultiArrayObjectPool$ArrayHolder'; do
    IRONWOOD_POOL_SOURCE=${IRONWOOD_POOL_CLASS%%\$*}
    if [[ ! -f "$IRONWOOD_PACKAGE_ROOT/stdlib/src/main/ironwood/ironwood/pool/$IRONWOOD_POOL_SOURCE.iron" \
            || ! -f "$IRONWOOD_PACKAGE_ROOT/lib/stdlib/ironwood/pool/$IRONWOOD_POOL_CLASS.ironclass" ]]; then
        echo "error: packaged standard library is missing pool type $IRONWOOD_POOL_CLASS" >&2
        exit 1
    fi
    if ! env -u JAVA_HOME PATH="$IRONWOOD_SYSTEM_PATH" \
            "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" --list \
            --file "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-stdlib.ironjar" \
            | grep -Fqx "ironwood/pool/$IRONWOOD_POOL_CLASS.ironclass"; then
        echo "error: packaged standard-library archive does not contain $IRONWOOD_POOL_CLASS" >&2
        exit 1
    fi
done
for IRONWOOD_DS_CLASS in ArrayList LinkedList ArrayLinkedList IntArrayList LongArrayList \
        IntLinkedList LongLinkedList ByteMap CharMap IntMap LongMap HashMap IdentityHashMap LinkedHashMap \
        CharSequenceMap ByteBufferMap HashSet IdentityHashSet LinkedHashSet IntSet LongSet; do
    if ! env -u JAVA_HOME PATH="$IRONWOOD_SYSTEM_PATH" \
            "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" --list \
            --file "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-stdlib.ironjar" \
            | grep -qx "ironwood/ds/$IRONWOOD_DS_CLASS.ironclass"; then
        echo "error: packaged standard-library archive does not contain $IRONWOOD_DS_CLASS" >&2
        exit 1
    fi
done
for IRONWOOD_NIO_CLASS in ByteBuffer BufferUnderflowException BufferOverflowException; do
    if ! env -u JAVA_HOME PATH="$IRONWOOD_SYSTEM_PATH" \
            "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" --list \
            --file "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-stdlib.ironjar" \
            | grep -qx "ironwood/nio/$IRONWOOD_NIO_CLASS.ironclass"; then
        echo "error: packaged standard-library archive does not contain $IRONWOOD_NIO_CLASS" >&2
        exit 1
    fi
done
IRONWOOD_STDLIB_ARCHIVE_ENTRIES=$(env -u JAVA_HOME PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" --list \
    --file "$IRONWOOD_PACKAGE_ROOT/lib/ironwood-stdlib.ironjar")
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
        echo "error: packaged standard-library archive is missing $IRONWOOD_LICENSE_ENTRY" >&2
        exit 1
    fi
done
while IFS= read -r IRONWOOD_STDLIB_SOURCE; do
    IRONWOOD_STDLIB_RELATIVE=${IRONWOOD_STDLIB_SOURCE#"$IRONWOOD_PACKAGE_ROOT/stdlib/src/main/ironwood/"}
    IRONWOOD_STDLIB_CLASS=${IRONWOOD_STDLIB_RELATIVE%.iron}.ironclass
    if [[ ! -f "$IRONWOOD_PACKAGE_ROOT/lib/stdlib/$IRONWOOD_STDLIB_CLASS" ]]; then
        echo "error: packaged standard library is missing loose class $IRONWOOD_STDLIB_CLASS" >&2
        exit 1
    fi
    if ! grep -qx "$IRONWOOD_STDLIB_CLASS" <<< "$IRONWOOD_STDLIB_ARCHIVE_ENTRIES"; then
        echo "error: packaged standard-library archive is missing $IRONWOOD_STDLIB_CLASS" >&2
        exit 1
    fi
done < <(find "$IRONWOOD_PACKAGE_ROOT/stdlib/src/main/ironwood" -type f -name '*.iron' | sort)
IRONWOOD_EXPECTED_VERSION=$(tr -d '[:space:]' < "$IRONWOOD_PACKAGE_ROOT/VERSION")
IRONWOOD_VERSION_OUTPUT=$(env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" --version)
if [[ "$IRONWOOD_VERSION_OUTPUT" != "ironwoodc $IRONWOOD_EXPECTED_VERSION" ]]; then
    echo "error: packaged compiler reported '$IRONWOOD_VERSION_OUTPUT', expected 'ironwoodc $IRONWOOD_EXPECTED_VERSION'" >&2
    exit 1
fi

compile_and_expect() {
    local source_name=$1
    local main_class=$2
    local optimization_level=$3
    local expected_exit=$4
    local output_name=$5
    local output_path="$IRONWOOD_TEST_DIR/$output_name"
    local class_output="$IRONWOOD_TEST_DIR/classes-$output_name"
    local example_name=${source_name%%/*}

    env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
        PATH="$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
        "$IRONWOOD_PACKAGE_ROOT/examples/$source_name" \
        -d "$class_output" \
        --source-path "$IRONWOOD_PACKAGE_ROOT/examples/$example_name/src/main/ironwood"

    env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
        PATH="$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
        --link \
        -cp "$class_output" \
        --main-class "$main_class" \
        -o "$output_path" \
        "$optimization_level" \
        --llvm-home "$IRONWOOD_SYSTEM_LLVM_HOME"

    set +e
    "$output_path"
    local actual_exit=$?
    set -e
    if [[ $actual_exit -ne $expected_exit ]]; then
        echo "error: packaged $source_name returned $actual_exit, expected $expected_exit" >&2
        exit 1
    fi
}

compile_and_expect basic/src/main/ironwood/org/ironwood/basic/Main.iron \
    org.ironwood.basic.Main -O0 42 main
compile_and_expect inheritance/src/main/ironwood/org/ironwood/inheritance/InheritanceAndInterfaces.iron \
    org.ironwood.inheritance.InheritanceAndInterfaces -O1 42 inheritance-and-interfaces
compile_and_expect objects/src/main/ironwood/org/ironwood/objects/Objects.iron \
    org.ironwood.objects.Objects -O2 30 objects
compile_and_expect controlflow/src/main/ironwood/org/ironwood/controlflow/MethodsAndControlFlow.iron \
    org.ironwood.controlflow.MethodsAndControlFlow -O3 30 methods-and-control-flow
compile_and_expect exceptions/src/main/ironwood/org/ironwood/exceptions/Exceptions.iron \
    org.ironwood.exceptions.Exceptions -O3 42 exceptions
compile_and_expect checkedexceptions/src/main/ironwood/org/ironwood/checkedexceptions/CheckedExceptions.iron \
    org.ironwood.checkedexceptions.CheckedExceptions -O3 42 checked-exceptions
compile_and_expect reclamation/src/main/ironwood/org/ironwood/reclamation/ExplicitReclamation.iron \
    org.ironwood.reclamation.ExplicitReclamation -O3 42 explicit-reclamation
compile_and_expect foundations/src/main/ironwood/org/ironwood/foundations/ArraysStringsAndIo.iron \
    org.ironwood.foundations.ArraysStringsAndIo -O3 42 arrays-strings-io
compile_and_expect collections/src/main/ironwood/org/ironwood/collections/ReusableCollections.iron \
    org.ironwood.collections.ReusableCollections -O3 42 reusable-collections
compile_and_expect statements/src/main/ironwood/org/ironwood/statements/RemainingStatements.iron \
    org.ironwood.statements.RemainingStatements -O3 42 remaining-statements
compile_and_expect multicatch/src/main/ironwood/org/ironwood/multicatch/MultiCatchAndPreciseRethrow.iron \
    org.ironwood.multicatch.MultiCatchAndPreciseRethrow -O3 42 multi-catch
compile_and_expect arrayinitializers/src/main/ironwood/org/ironwood/arrayinitializers/ArrayInitializers.iron \
    org.ironwood.arrayinitializers.ArrayInitializers -O3 42 array-initializers
compile_and_expect binaryliterals/src/main/ironwood/org/ironwood/binaryliterals/BinaryIntegerLiterals.iron \
    org.ironwood.binaryliterals.BinaryIntegerLiterals -O3 42 binary-literals
compile_and_expect staticimports/src/main/ironwood/org/ironwood/staticimports/StaticImports.iron \
    org.ironwood.staticimports.StaticImports -O3 42 static-imports
compile_and_expect modernswitch/src/main/ironwood/org/ironwood/modernswitch/ModernSwitch.iron \
    org.ironwood.modernswitch.ModernSwitch -O3 42 modern-switch

IRONWOOD_MAIN_CLASS_PATH="$IRONWOOD_TEST_DIR/main-class-output"
IRONWOOD_MAIN_ARCHIVE="$IRONWOOD_TEST_DIR/main.ironjar"
IRONWOOD_MAIN_FROM_CLASS="$IRONWOOD_TEST_DIR/main-from-ironclass"
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    "$IRONWOOD_PACKAGE_ROOT/examples/basic/src/main/ironwood/org/ironwood/basic/Main.iron" \
    -d "$IRONWOOD_MAIN_CLASS_PATH"
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" \
    --create --file "$IRONWOOD_MAIN_ARCHIVE" "$IRONWOOD_MAIN_CLASS_PATH"
if ! env -u JAVA_HOME PATH="$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/bin/ironjar" --list --file "$IRONWOOD_MAIN_ARCHIVE" \
        | grep -qx 'org/ironwood/basic/Main.ironclass'; then
    echo "error: packaged ironjar did not list the archived Main class" >&2
    exit 1
fi
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_MAIN_ARCHIVE" \
    --main-class org.ironwood.basic.Main \
    -o "$IRONWOOD_MAIN_FROM_CLASS" \
    --llvm-home "$IRONWOOD_SYSTEM_LLVM_HOME"
set +e
"$IRONWOOD_MAIN_FROM_CLASS"
IRONWOOD_MAIN_CLASS_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_MAIN_CLASS_EXIT_STATUS -ne 42 ]]; then
    echo "error: packaged main linked from ironjar returned $IRONWOOD_MAIN_CLASS_EXIT_STATUS, expected 42" >&2
    exit 1
fi

IRONWOOD_CLASS_PATH="$IRONWOOD_TEST_DIR/counter-classes"
IRONWOOD_CLASS_PROGRAM="$IRONWOOD_TEST_DIR/objects-from-classpath"
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    "$IRONWOOD_PACKAGE_ROOT/examples/objects/src/main/ironwood/org/ironwood/objects/Counter.iron" \
    -d "$IRONWOOD_CLASS_PATH"
if [[ ! -f "$IRONWOOD_CLASS_PATH/org/ironwood/objects/Counter.ironclass" ]]; then
    echo "error: packaged compiler did not create Counter.ironclass" >&2
    exit 1
fi
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    "$IRONWOOD_PACKAGE_ROOT/examples/objects/src/main/ironwood/org/ironwood/objects/Objects.iron" \
    --source-path "$IRONWOOD_TEST_DIR/no-source-path" \
    -cp "$IRONWOOD_CLASS_PATH" \
    -d "$IRONWOOD_TEST_DIR/application-classes"
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_TEST_DIR/application-classes:$IRONWOOD_CLASS_PATH" \
    --main-class org.ironwood.objects.Objects \
    -o "$IRONWOOD_CLASS_PROGRAM" \
    --llvm-home "$IRONWOOD_SYSTEM_LLVM_HOME"
set +e
"$IRONWOOD_CLASS_PROGRAM"
IRONWOOD_CLASS_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_CLASS_EXIT_STATUS -ne 30 ]]; then
    echo "error: packaged classpath program returned $IRONWOOD_CLASS_EXIT_STATUS, expected 30" >&2
    exit 1
fi

for IRONWOOD_EXAMPLE in basic hello controlflow objects inheritance exceptions checkedexceptions resources reclamation textreclamation echo foundations collections arguments stacktraces staticinitialization classicswitch enums multidimensionalarrays stringconcatenation primitivegenerics overridedirective textblocks runtimefailures allocationfailure instanceofpatterns statements multicatch arrayinitializers binaryliterals staticimports modernswitch; do
    env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME \
        IRONWOOD_LLVM_HOME="$IRONWOOD_SYSTEM_LLVM_HOME" \
        PATH="$IRONWOOD_PACKAGE_ROOT/bin:$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/examples/$IRONWOOD_EXAMPLE/compile.sh"
    env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME \
        IRONWOOD_LLVM_HOME="$IRONWOOD_SYSTEM_LLVM_HOME" \
        PATH="$IRONWOOD_PACKAGE_ROOT/bin:$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/examples/$IRONWOOD_EXAMPLE/link.sh"
    env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
        PATH="$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/examples/$IRONWOOD_EXAMPLE/run.sh"
done

env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME \
    IRONWOOD_LLVM_HOME="$IRONWOOD_SYSTEM_LLVM_HOME" \
    PATH="$IRONWOOD_PACKAGE_ROOT/bin:$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/projects/minigrep/compile.sh"
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME \
    IRONWOOD_LLVM_HOME="$IRONWOOD_SYSTEM_LLVM_HOME" \
    PATH="$IRONWOOD_PACKAGE_ROOT/bin:$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/projects/minigrep/link.sh"
IRONWOOD_MINIGREP_OUTPUT=$(env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/projects/minigrep/run.sh")
if ! grep -Fqx 'A body stays strong through every season.' <<< "$IRONWOOD_MINIGREP_OUTPUT" \
        || ! grep -Fqx 'exit status: 0' <<< "$IRONWOOD_MINIGREP_OUTPUT"; then
    echo "error: packaged minigrep produced unexpected output" >&2
    printf '%s\n' "$IRONWOOD_MINIGREP_OUTPUT" >&2
    exit 1
fi

for IRONWOOD_STREAM_SCRIPT in compile.sh link.sh; do
    env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME \
        IRONWOOD_LLVM_HOME="$IRONWOOD_SYSTEM_LLVM_HOME" \
        PATH="$IRONWOOD_PACKAGE_ROOT/bin:$IRONWOOD_SYSTEM_PATH" \
        "$IRONWOOD_PACKAGE_ROOT/projects/streaming/$IRONWOOD_STREAM_SCRIPT"
done
IRONWOOD_STREAM_OUTPUT=$(printf 'hello world\n' | env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" "$IRONWOOD_PACKAGE_ROOT/projects/streaming/run.sh" wc)
if [[ "$IRONWOOD_STREAM_OUTPUT" != '12 12 2 1' ]]; then
    echo "error: packaged streaming wc produced unexpected output" >&2
    exit 1
fi
printf '\000\377A' > "$IRONWOOD_TEST_DIR/stream-input.bin"
"$IRONWOOD_PACKAGE_ROOT/projects/streaming/run.sh" cp \
    "$IRONWOOD_TEST_DIR/stream-input.bin" "$IRONWOOD_TEST_DIR/stream-copy.bin"
"$IRONWOOD_PACKAGE_ROOT/projects/streaming/run.sh" cat \
    "$IRONWOOD_TEST_DIR/stream-copy.bin" > "$IRONWOOD_TEST_DIR/stream-cat.bin"
cmp "$IRONWOOD_TEST_DIR/stream-input.bin" "$IRONWOOD_TEST_DIR/stream-cat.bin"

IRONWOOD_DEFAULT_SOURCE_ROOT="$IRONWOOD_TEST_DIR/default output source/src/main/ironwood"
IRONWOOD_DEFAULT_ENTRY="$IRONWOOD_DEFAULT_SOURCE_ROOT/org/ironwood/inheritance/InheritanceAndInterfaces.iron"
IRONWOOD_DEFAULT_PROGRAM="$IRONWOOD_TEST_DIR/default-inheritance-and-interfaces"
mkdir -p "$IRONWOOD_DEFAULT_SOURCE_ROOT"
cp -R "$IRONWOOD_PACKAGE_ROOT/examples/inheritance/src/main/ironwood/org" \
    "$IRONWOOD_DEFAULT_SOURCE_ROOT/org"
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    "$IRONWOOD_DEFAULT_ENTRY" \
    --source-path "$IRONWOOD_DEFAULT_SOURCE_ROOT"
if [[ -e "${IRONWOOD_DEFAULT_ENTRY%.iron}" ]]; then
    echo "error: ordinary compilation unexpectedly created a native executable" >&2
    exit 1
fi
if [[ ! -f "${IRONWOOD_DEFAULT_ENTRY%.iron}.ironclass" ]]; then
    echo "error: packaged compiler did not create Main ironclass beside the entry source" >&2
    exit 1
fi
env -u JAVA_HOME -u IRONWOOD_RUNTIME_HOME -u IRONWOOD_LLVM_HOME \
    PATH="$IRONWOOD_SYSTEM_PATH" \
    "$IRONWOOD_PACKAGE_ROOT/bin/ironwoodc" \
    --link \
    -cp "$IRONWOOD_DEFAULT_SOURCE_ROOT" \
    --main-class org.ironwood.inheritance.InheritanceAndInterfaces \
    -o "$IRONWOOD_DEFAULT_PROGRAM" \
    --llvm-home "$IRONWOOD_SYSTEM_LLVM_HOME"
if [[ ! -x "$IRONWOOD_DEFAULT_PROGRAM" ]]; then
    echo "error: packaged linker did not create the requested native output" >&2
    exit 1
fi
set +e
"$IRONWOOD_DEFAULT_PROGRAM"
IRONWOOD_DEFAULT_EXIT_STATUS=$?
set -e
if [[ $IRONWOOD_DEFAULT_EXIT_STATUS -ne 42 ]]; then
    echo "error: packaged default output returned $IRONWOOD_DEFAULT_EXIT_STATUS, expected 42" >&2
    exit 1
fi

echo "ok - relocated host package compiles uniform ironclass and native programs at O0 through O3"
