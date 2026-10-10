#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Linux part of the M5 checkpoint, run from a git archive of the recorded
# commit in BASE/tree on the x86host host (x86-64: JDK 21.0.1 and the IDK
# 0.3.1 LLVM 23 toolchain) or the armvm VM (arm64: Temurin 21 and the IDK
# 0.2.5 LLVM 23 toolchain): the 25 M5 tests (m5-tests.txt) and the walk
# consumers of the D276 Files.walkFileTree change (the U5 traversals, M4.1's
# allocation-failure sweep and tree deletion). Logs land in BASE/logs; the
# done marker ends the run.
set -u
base=${1:?usage: linux-evidence.sh BASE}
case "$base" in /*) ;; *) base=$HOME/$base ;; esac
tree=$base/tree
logs=$base/logs
case "$(uname -m)" in
    x86_64)
        export JAVA_HOME=/usr/java/jdk-21.0.1
        export IRONWOOD_LLVM_HOME=/home/developer/temp/test-ironwood/ironwood-idk-0.3.1-linux-x86_64/toolchain ;;
    aarch64)
        export JAVA_HOME=/usr/java/java21
        export IRONWOOD_LLVM_HOME=$HOME/temp/test-ironwood/ironwood-idk-0.2.5-linux-arm64/toolchain ;;
    *)
        echo "unsupported machine $(uname -m)" >&2; exit 2 ;;
esac
export PATH=$JAVA_HOME/bin:$PATH LC_ALL=C.UTF-8 LANG=C.UTF-8
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS IRONWOOD_HOME IRONWOOD_STDLIB_HOME IRONWOOD_RUNTIME_HOME TMPDIR
rm -rf "$logs" && mkdir -p "$logs"
status() { echo "$1 exit=$2" >> "$logs/status.txt"; }
{ uname -a; nproc; free -m | head -2; ldd --version | head -1; "$JAVA_HOME/bin/java" -version 2>&1;
  "$IRONWOOD_LLVM_HOME/bin/llvm-config" --version; df -h "$base" | tail -1; } > "$logs/host.txt" 2>&1
cd "$tree"
tests=()
while IFS= read -r name; do tests+=("$name"); done < docs/self-hosting/m5/handoff-evidence/m5-tests.txt
tests+=("U5 directory foundation enumerates entries and reads attributes"
        "U5 file tree traversal controls depth links and cleanup"
        "U5 file tree traversal enforces borrowed visitor callbacks"
        "M4.1 filesystem services unwind every allocation failure without leftovers"
        "compiler tree deletion matches the Java cleanup policies across artifacts")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
./scripts/test.sh "${args[@]}" > "$logs/tests.log" 2>&1; status linux-tests $?
touch "$logs/done"
cat "$logs/status.txt"
