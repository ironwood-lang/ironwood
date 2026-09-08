#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNNERS=(
    run-01-fresh-owner-free.sh
    run-02-completed-traversal.sh
    run-03-dead-iterator-local.sh
    run-04-iterator-use-after-free.sh
    run-05-free-borrowed-iterator.sh
    run-06-helper-return-borrow.sh
    run-07-double-free-owner.sh
    run-08-escaped-iterator.sh
    run-09-unknown-retaining-call.sh
    run-10-observing-call.sh
    run-11-encapsulated-backlink.sh
    run-12-published-backlink.sh
    run-13-stable-control-flow.sh
    run-14-obscured-control-flow.sh
    run-15-primitive-nested-holder.sh
    run-16-escaped-nested-holder.sh
    run-17-live-owner-alias.sh
    run-18-inserted-element-survives.sh
    run-19-free-inserted-element.sh
    run-20-constructor-rollback.sh
    run-21-reusable-iterator-reset.sh
)

for runner in "${RUNNERS[@]}"; do
    printf '\n== %s ==\n' "$runner"
    "$EXAMPLE_DIR/$runner"
done

printf '\nPASS: %d owned-helper-borrow scenarios\n' "${#RUNNERS[@]}"
