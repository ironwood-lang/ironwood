#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Run after compile.sh and link.sh. Builds and runs the C++ tests, checks the
# command-line programs, and compares the latency reports with Java's, which
# needs a Java 21 JDK.

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

# Finds clang++ in the LLVM 23 installation that ironwoodc uses, searched in
# ironwoodc's order: IRONWOOD_LLVM_HOME, the IDK toolchain beside ironwoodc,
# Homebrew, and /usr/lib/llvm-23. Compiling with it gives the C++ and Ironwood
# code the same LLVM optimizer and code generator.
find_llvm_cxx() {
    local homes=()
    local home
    local compiler
    if [[ -n "${IRONWOOD_LLVM_HOME:-}" ]]; then
        homes+=("$IRONWOOD_LLVM_HOME")
    fi
    if command -v ironwoodc >/dev/null 2>&1; then
        homes+=("$(dirname -- "$(command -v ironwoodc)")/../toolchain")
    fi
    if command -v brew >/dev/null 2>&1; then
        homes+=("$(brew --prefix llvm@23 2>/dev/null || true)")
        homes+=("$(brew --prefix llvm 2>/dev/null || true)")
    fi
    homes+=(/usr/lib/llvm-23)
    for home in "${homes[@]}"; do
        [[ -n "$home" && -x "$home/bin/llvm-config" ]] || continue
        [[ "$("$home/bin/llvm-config" --version 2>/dev/null)" == 23.* ]] || continue
        for compiler in clang++ clang++-23; do
            if [[ -x "$home/bin/$compiler" ]]; then
                printf '%s\n' "$home/bin/$compiler"
                return 0
            fi
        done
    done
    echo 'error: clang++ from LLVM 23 not found; install LLVM 23 or set IRONWOOD_LLVM_HOME' >&2
    return 1
}

CXX=$(find_llvm_cxx)

# Tune for the build host, as the Ironwood and Native Image builds do. On arm64
# -march=native selects a generic core, while -mcpu=native selects the host
# core, matching the -mcpu=native that ironwoodc passes to LLVM.
case $(uname -m) in
    arm64|aarch64) CPU_FLAG=-mcpu=native ;;
    *) CPU_FLAG=-march=native ;;
esac

# Java rounds every floating-point operation separately, so the report code
# must not fuse multiplies and adds. The measured workload is integer-only.
COMPILE_FLAGS=(-std=c++17 -O3 "$CPU_FLAG" -ffp-contract=off
    -Wall -Wextra -Wpedantic -Werror -I src/main/cpp)

mkdir -p target/test-objects
COMPILE=("$CXX" "${COMPILE_FLAGS[@]}"
    -c src/test/cpp/org/ironwood/orderbook/BenchmarkTests.cpp
    -o target/test-objects/BenchmarkTests.o)
printf '+ %q ' "${COMPILE[@]}"
printf '\n'
"${COMPILE[@]}"

LINK=("$CXX"
    target/test-objects/BenchmarkTests.o target/objects/LatencyReport.o
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
