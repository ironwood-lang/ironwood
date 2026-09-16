#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
set -x
ironwoodc --source-path src/main/ironwood -d target/classes --unfreed=error src/main/ironwood/org/ironwood/hostnetworking/HostNetworking.iron
