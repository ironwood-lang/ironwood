#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

rm -rf target/classes
mkdir -p target/classes

find src/main/java -type f -name '*.java' | sort > target/sources.txt
javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
    -d target/classes @target/sources.txt
