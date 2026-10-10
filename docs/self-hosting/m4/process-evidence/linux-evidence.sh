#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Linux part of the M4.3 process and driver checkpoint, run on the x86host
# host from a git archive of the recorded commit in
# ~/temp/java_bridge/m4-evidence: the fifteen M4.3 tests with JDK 21 and the
# IDK 0.3.1 LLVM 23 toolchain. Logs land in ~/temp/java_bridge/m4-evidence-logs.
set -u
tree=~/temp/java_bridge/m4-evidence
logs=~/temp/java_bridge/m4-evidence-logs
export JAVA_HOME=/usr/java/jdk-21.0.1
export IRONWOOD_LLVM_HOME=/home/developer/temp/test-ironwood/ironwood-idk-0.3.1-linux-x86_64/toolchain
export PATH=$JAVA_HOME/bin:$PATH LC_ALL=C.UTF-8 LANG=C.UTF-8
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS IRONWOOD_HOME TMPDIR
rm -rf "$logs" && mkdir -p "$logs"
status() { echo "$1 exit=$2" >> "$logs/status.txt"; }
{ uname -a; ldd --version | head -1; "$JAVA_HOME/bin/java" -version 2>&1 | head -1;
  "$IRONWOOD_LLVM_HOME/bin/llvm-config" --version; } > "$logs/host.txt"
cd "$tree"
tests=()
while IFS= read -r name; do tests+=("$name"); done < docs/self-hosting/m4/process-evidence/m4.3-tests.txt
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
./scripts/test.sh "${args[@]}" > "$logs/tests.log" 2>&1; status linux-tests $?
cat "$logs/status.txt"
