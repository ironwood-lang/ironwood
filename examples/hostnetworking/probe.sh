#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
if [[ $# -ne 2 ]]; then
    printf 'usage: %s HOST TIMEOUT_MILLIS\n' "$0" >&2
    exit 2
fi
cd "$(dirname "$0")"
printf 'Opt-in live smoke: OS=%s architecture=%s uid=%s interface=default ttl=default timeout=%s ms\n' \
    "$(uname -s)" "$(uname -m)" "$(id -u)" "$2"
set -x
./target/HostNetworking --probe "$1" "$2"
