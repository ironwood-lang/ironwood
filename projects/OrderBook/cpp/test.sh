#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Run after compile.sh and link.sh. Builds and runs the C++ tests, checks the
# command-line programs, and compares the latency reports with Java's, which
# needs a Java 21 JDK.

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

bash "$PROJECT_DIR/test-toolchain.sh"

source "$PROJECT_DIR/toolchain.sh"

CXX_PATH=$(find_llvm_cxx)
CXX=("$CXX_PATH" --driver-mode=g++)

# The IDK's clang is a conda build. Its default target, such as
# x86_64-conda-linux-gnu, uses a C-only sysroot and the conda linker, so
# retarget it to the same architecture and C library with a generic vendor and
# the system GCC's C++ standard library. Other clangs keep their defaults.
CXX_TRIPLE=$("$CXX_PATH" -print-target-triple)
if [[ "$CXX_TRIPLE" == *-conda-* ]]; then
    CXX+=(--target="${CXX_TRIPLE/-conda-/-unknown-}" --gcc-toolchain=/usr)
fi

# Stops early, with the compiler's output, when this compiler cannot build and
# run a C++ program on this machine.
check_cxx() {
    local probe
    probe=$(mktemp -d "${TMPDIR:-/tmp}/orderbook-cxx.XXXXXX")
    printf '#include <iostream>\nint main() { std::cout << "ok" << std::endl; }\n' > "$probe/probe.cpp"
    if "${CXX[@]}" -std=c++17 "$probe/probe.cpp" -o "$probe/probe" > "$probe/output" 2>&1 \
            && [[ "$("$probe/probe" 2>> "$probe/output")" == ok ]]; then
        rm -rf "$probe"
        return 0
    fi
    printf 'error: %s cannot build and run a C++ program here; install the system\n' "${CXX[*]}" >&2
    printf 'C++ standard library (for example the g++ package). Compiler output:\n' >&2
    cat "$probe/output" >&2
    rm -rf "$probe"
    return 1
}

check_cxx

# Tune for the build host, as the Ironwood and Native Image builds do. On arm64
# -march=native selects a generic core, while -mcpu=native selects the host
# core, matching the -mcpu=native that ironwoodc passes to LLVM.
case $(uname -m) in
    arm64|aarch64) CPU_FLAG=-mcpu=native ;;
    *) CPU_FLAG=-march=native ;;
esac

# Java rounds every floating-point operation separately, so the report code
# must not fuse multiplies and adds. The measured workload is integer-only.
# Signed addition, subtraction, and multiplication wrap as in Java and Ironwood.
COMPILE_FLAGS=(-std=c++17 -O3 "$CPU_FLAG" -ffp-contract=off -fwrapv
    -Wall -Wextra -Wpedantic -Werror -I src/main/cpp)

mkdir -p target/test-objects
COMPILE=("${CXX[@]}" "${COMPILE_FLAGS[@]}"
    -c src/test/cpp/org/ironwood/orderbook/BenchmarkTests.cpp
    -o target/test-objects/BenchmarkTests.o)
printf '+ %q ' "${COMPILE[@]}"
printf '\n'
"${COMPILE[@]}"

LINK=("${CXX[@]}"
    target/test-objects/BenchmarkTests.o target/objects/LatencyReport.o
    target/objects/JavaCompat.o
    -o target/benchmark-tests)
printf '+ %q ' "${LINK[@]}"
printf '\n'
"${LINK[@]}"

./target/benchmark-tests

# Executable checks use small workloads; elapsed values are machine-dependent.
output=$(./run.sh)
[[ "$output" == $'initial\n99\n100\n101\n80\nafter-market\n102\n30\n2\n120\nfinal\ntrue\ntrue\n4\n170\n2' ]]
output=$(./throughput.sh 0 1)
[[ "$output" =~ ^[0-9]+$ && "$output" -gt 0 ]]
output=$(./latency.sh 2 5 10)
[[ "$output" == *'Cycles per batch: 10'* ]]
[[ "$output" == *'Operations per batch: 80'* ]]
[[ "$output" == *'Measured operations: 400'* ]]
[[ "$output" == *'Measurements: 5 | Warm-Up: 2 | Iterations: 7'* ]]
output=$(./latency.sh 0 1 1)
[[ "$output" == *'Measurements: 1 | Warm-Up: 0 | Iterations: 1'* ]]
for arguments in '-1 1 1' '0 0 1' '0 1 0' '2147483647 1 1' '0 2147483647 2147483647'; do
    read -r warmup measurements cycles <<< "$arguments"
    if ./latency.sh "$warmup" "$measurements" "$cycles" >target/invalid-latency.log 2>&1; then
        printf 'error: accepted invalid latency arguments: %s\n' "$arguments" >&2
        exit 1
    fi
done
for arguments in '-1 1' '0 0' 'x 1' '2147483648 1'; do
    if ./throughput.sh $arguments >target/invalid-throughput.log 2>&1; then
        printf 'error: accepted invalid throughput arguments: %s\n' "$arguments" >&2
        exit 1
    fi
done
printf 'PASS: C++ demonstration, throughput and latency command-line checks\n'

../java/compile.sh
mkdir -p ../java/target/test-classes
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp ../java/target/classes \
    -d ../java/target/test-classes ../java/src/test/java/org/ironwood/orderbook/BenchmarkTests.java
./target/benchmark-tests reports > target/cpp-latency-reports.txt
java -cp ../java/target/classes:../java/target/test-classes \
    org.ironwood.orderbook.BenchmarkTests reports > target/java-latency-reports.txt
diff -u target/java-latency-reports.txt target/cpp-latency-reports.txt
printf 'PASS: C++ and Java latency reports match\n'
