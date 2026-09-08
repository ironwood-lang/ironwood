#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ironwoodc --source-path src/main/ironwood -d target/classes src/main/ironwood/org/ironwood/arguments/CommandLineArguments.iron'
ironwoodc --source-path src/main/ironwood -d target/classes \
    src/main/ironwood/org/ironwood/arguments/CommandLineArguments.iron
