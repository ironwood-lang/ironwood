#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"
set -x
java -cp target/ironwood-listeners.jar:target/consumer-classes org.ironwood.javabridge.listenerconsumer.Main
