#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$EXAMPLE_DIR"

VALID_CLASSES=(
    FreshOwnerFree
    CompletedTraversal
    DeadIteratorLocal
    HelperReturnBorrow
    ObservingCall
    EncapsulatedBacklink
    StableControlFlow
    PrimitiveNestedHolder
    InsertedElementSurvives
    FreeInsertedElement
    ConstructorRollback
    ReusableIteratorReset
)

for scenario_name in "${VALID_CLASSES[@]}"; do
    # Match the scenario-specific policy documented in compile.sh during link analysis.
    COMMAND=(ironwoodc)
    if [[ "$scenario_name" == FreeInsertedElement ]]; then
        COMMAND+=(--unfreed=off)
    fi
    COMMAND+=(--link -cp target/classes \
        --main-class "org.ironwood.ownedhelperborrows.$scenario_name" \
        -o "target/$scenario_name" -O3)
    printf '+'
    printf ' %q' "${COMMAND[@]}"
    printf '\n'
    "${COMMAND[@]}"
done

printf 'linked %d valid scenarios; compile-error scenarios intentionally have no executable\n' \
    "${#VALID_CLASSES[@]}"
