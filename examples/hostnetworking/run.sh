#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
set -x
./target/HostNetworking
set +x
printf 'exit status: 0\n'
