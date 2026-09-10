#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

# Linking repeats analysis; use the same documented syntax-demo policy as compile.sh.
COMMAND=(ironwoodc --unfreed=off --link -cp target/classes \
    --main-class org.ironwood.qualifiedanonymous.QualifiedAnonymous \
    -o target/QualifiedAnonymous -O3)
printf '+'
printf ' %q' "${COMMAND[@]}"
printf '\n'
"${COMMAND[@]}"
