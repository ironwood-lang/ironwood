#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
set -x
ironwoodc --link -cp target/classes --main-class org.ironwood.hostnetworking.HostNetworking -o target/HostNetworking -O3 --unfreed=error
