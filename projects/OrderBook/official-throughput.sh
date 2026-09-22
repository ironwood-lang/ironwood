#!/usr/bin/env bash
set -euo pipefail

RUNS=${1:-31}
WARMUP=${2:-8}
MEASURED=${3:-80}

if [[ ! "$RUNS" =~ ^[1-9][0-9]*$ ]] || (( RUNS % 2 == 0 )); then
    echo "error: run count must be a positive odd number" >&2
    exit 1
fi

TIMES=()
for ((RUN = 1; RUN <= RUNS; RUN++)); do
    TIME=$(./throughput.sh "$WARMUP" "$MEASURED")
    [[ "$TIME" =~ ^[1-9][0-9]*$ ]] || exit 1
    TIMES+=("$TIME")
    printf 'run %d/%d: %s ns\n' "$RUN" "$RUNS" "$TIME" >&2
done

MEDIAN_LINE=$((RUNS / 2 + 1))
printf '%s\n' "${TIMES[@]}" | sort -n | sed -n "${MEDIAN_LINE}p"

