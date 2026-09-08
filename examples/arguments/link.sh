#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

printf '%s\n' '+ ironwoodc --link -cp target/classes --main-class org.ironwood.arguments.CommandLineArguments -o target/CommandLineArguments -O3'
ironwoodc --link -cp target/classes \
    --main-class org.ironwood.arguments.CommandLineArguments \
    -o target/CommandLineArguments -O3
