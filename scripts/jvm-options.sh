#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

# Sourced by the launchers after resolving their own installation and Java.
ironwood_run_java() {
    local options_file="$IRONWOOD_ROOT/conf/jvm.options"
    local line line_number=0
    local -a options=()

    if [[ -e "$options_file" || -L "$options_file" ]]; then
        if [[ ! -f "$options_file" || ! -r "$options_file" ]]; then
            echo "error: JVM options must be a readable file: $options_file" >&2
            return 1
        fi
        while IFS= read -r line || [[ -n "$line" ]]; do
            line_number=$((line_number + 1))
            # Trim surrounding whitespace, including CRLF line endings.
            line="${line#"${line%%[![:space:]]*}"}"
            line="${line%"${line##*[![:space:]]}"}"
            case "$line" in
                ''|\#*) continue ;;
                -*) options+=("$line") ;;
                *)
                    echo "error: $options_file:$line_number: expected one JVM option starting with '-'" >&2
                    return 1
                    ;;
            esac
        done < "$options_file"
    fi

    # The guarded expansion also supports empty arrays with nounset on Bash 3.2.
    exec "$IRONWOOD_JAVA" ${options[@]+"${options[@]}"} "$@"
}
