#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Run from either directory; preserve all samples and stop on a failed command.
set -euo pipefail
cd -- "$(dirname -- "$0")"

CPU=1
PAIRS=20
WARMUP=8
MEASURED=80
OS=$(uname -s)
case "$OS" in
    Linux)
        command -v taskset >/dev/null || { printf 'Linux requires taskset.\n' >&2; exit 1; }
        ;;
    Darwin) CPU=unpinned ;;
    *) printf 'Unsupported OS: %s\n' "$OS" >&2; exit 1 ;;
esac
mkdir -p ./target
LOG=$(mktemp ./target/native-image-comparison.XXXXXX)
printf 'Saving every result to %s\n' "$LOG"

scripts=(./throughput.sh ./java/throughput-native-image.sh)
names=(Ironwood NativeImage)
{
    printf 'OS=%s CPU=%s warmup=%s measured=%s pairs_per_pass=%s\n' "$OS" "$CPU" "$WARMUP" "$MEASURED" "$PAIRS"
    # Alternate each pair; pass 2 reverses the starting order.
    for pass in 1 2; do
        for ((pair=1; pair<=PAIRS; pair++)); do
            if (((pair + pass) % 2 == 0)); then order=(0 1); else order=(1 0); fi
            for variant in "${order[@]}"; do
                printf 'pass=%s pair=%s %s: ' "$pass" "$pair" "${names[variant]}"
                if [[ "$OS" == Linux ]]; then
                    taskset -c "$CPU" "${scripts[variant]}" "$WARMUP" "$MEASURED"
                else
                    "${scripts[variant]}" "$WARMUP" "$MEASURED"
                fi
            done
        done
    done
} 2>&1 | tee "$LOG"

printf 'Finished. Send this results file: %s\n' "$LOG"
