#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# Scenario 19 alone uses --unfreed=off in compile-valid-scenario.sh and link.sh.
# Its failure branch avoids touching an element whose destructor already ran;
# forcing cleanup there would invalidate the destruction check. All other scenarios
# keep the default warnings, and mandatory memory-safety errors remain enabled.
COMPILERS=(
    compile01.sh
    compile02.sh
    compile03.sh
    compile04.sh
    compile05.sh
    compile06.sh
    compile07.sh
    compile08.sh
    compile09.sh
    compile10.sh
    compile11.sh
    compile12.sh
    compile13.sh
    compile14.sh
    compile15.sh
    compile16.sh
    compile17.sh
    compile18.sh
    compile19.sh
    compile20.sh
    compile21.sh
)

for compiler in "${COMPILERS[@]}"; do
    printf '\n== %s ==\n' "$compiler"
    "$EXAMPLE_DIR/$compiler"
done

printf '\nPASS: compiled or compile-checked %d owned-helper-borrow scenarios\n' \
    "${#COMPILERS[@]}"
