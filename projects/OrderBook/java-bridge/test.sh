#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BRIDGE_JDK_ROOT=$(CDPATH= cd -- "$PROJECT_DIR/../../.." && pwd)
source "$BRIDGE_JDK_ROOT/scripts/jdk.sh"
ironwood_select_java "$BRIDGE_JDK_ROOT"
cd "$PROJECT_DIR"

# Run after compile.sh and link.sh. These are smoke checks, not timing evidence.
for type in OrderBook Order 'Order$Side' 'Order$Type' PriceLevel; do
    if [[ -e "target/consumer-classes/org/ironwood/orderbook/$type.class" ]]; then
        echo "error: Java engine class leaked into the bridge consumer: $type" >&2
        exit 1
    fi
done
expected=$'initial\n99\n100\n101\n80\nafter-market\n102\n30\n2\n120\nfinal\ntrue\ntrue\n4\n170\n2'
[[ "$(./run.sh 2>&1)" == "$expected" ]]
[[ "$(java -Xcheck:jni --enable-native-access=ALL-UNNAMED -cp target/orderbook.jar:target/consumer-classes org.ironwood.orderbook.Main 2>&1)" == "$expected" ]]
# The consumer override selects the registered JNI methods. The grant only keeps Java 24/25 from
# printing their restricted-method warning for the loader itself (JEP 472).
[[ "$(java -Xcheck:jni -Dironwood.bridge.calls=jni --enable-native-access=ALL-UNNAMED -cp target/orderbook.jar:target/consumer-classes org.ironwood.orderbook.Main 2>&1)" == "$expected" ]]
# Without either option the JVM links the critical calls and reports its own warning on stderr.
[[ "$(java -cp target/orderbook.jar:target/consumer-classes org.ironwood.orderbook.Main 2>/dev/null)" == "$expected" ]]
output=$(./throughput.sh 0 1)
[[ "$output" =~ ^[0-9]+$ && "$output" -gt 0 ]]
output=$(./latency.sh 2 5 10)
[[ "$output" == *'Operations per batch: 80'* ]]
[[ "$output" == *'Measured operations: 400'* ]]
[[ "$output" == *'Measurements: 5 | Warm-Up: 2 | Iterations: 7'* ]]
output=$(./latency.sh 0 1 1)
[[ "$output" == *'Measurements: 1 | Warm-Up: 0 | Iterations: 1'* ]]
for arguments in '-1 1' '0 0' '0 -1' '0 1 extra'; do
    read -r -a values <<< "$arguments"
    if ./throughput.sh "${values[@]}" >target/invalid-throughput.log 2>&1; then
        echo "error: accepted invalid throughput arguments: $arguments" >&2
        exit 1
    fi
done
for arguments in '-1 1 1' '0 0 1' '0 1 0' '2147483647 1 1' '0 2147483647 2147483647' '0 1 1 extra'; do
    read -r -a values <<< "$arguments"
    if ./latency.sh "${values[@]}" >target/invalid-latency.log 2>&1; then
        echo "error: accepted invalid latency arguments: $arguments" >&2
        exit 1
    fi
done
printf 'PASS: Java Bridge demo, throughput, latency and invalid-argument checks\n'
