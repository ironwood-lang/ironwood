#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

if [[ $# -ne 0 ]]; then
    printf 'usage: %s\n' "$0" >&2
    exit 2
fi

EXAMPLES_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PASSED=0
FAILED=0
RESULTS=()

# Discover complete example workflows without maintaining another directory list.
for compile_script in "$EXAMPLES_DIR"/*/compile.sh; do
    [[ -f "$compile_script" ]] || continue
    example_dir=${compile_script%/compile.sh}
    example=${example_dir##*/}
    scripts=(compile.sh link.sh)
    case "$example" in
        ownedhelperborrows)
            # This catalog includes native checks and expected compiler rejections.
            scripts+=(run-all.sh)
            ;;
        trycatchfinallyexception)
            scripts+=(run1.sh run2.sh run3.sh run4.sh)
            ;;
        *)
            scripts+=(run.sh)
            ;;
    esac

    failures=''
    for script in "${scripts[@]}"; do
        printf '\nRUN - %s: %s\n' "$example" "$script"
        if "$example_dir/$script"; then
            continue
        else
            status=$?
        fi
        failures+="${failures:+, }$script (exit $status)"
        printf 'not ok - %s: %s (exit %d)\n' "$example" "$script" "$status"
        # Never link stale classes or run stale binaries after a prerequisite fails.
        # Independent numbered run scripts can still report their own failures.
        case "$script" in
            compile.sh|link.sh) break ;;
        esac
    done

    if [[ -z "$failures" ]]; then
        PASSED=$((PASSED + 1))
        RESULTS+=("ok - $example")
    else
        FAILED=$((FAILED + 1))
        RESULTS+=("not ok - $example: $failures")
    fi
done

TOTAL=$((PASSED + FAILED))
if [[ $TOTAL -eq 0 ]]; then
    printf 'FAIL: no examples with compile.sh found in %s\n' "$EXAMPLES_DIR" >&2
    exit 1
fi

printf '\nExample test summary\n'
printf '%s\n' "${RESULTS[@]}"
printf 'TOTAL: %d passed, %d failed, %d total\n' "$PASSED" "$FAILED" "$TOTAL"
if [[ $FAILED -ne 0 ]]; then
    printf 'FAIL: %d example(s) failed\n' "$FAILED"
    exit 1
fi
printf 'PASS: all %d examples passed\n' "$TOTAL"
