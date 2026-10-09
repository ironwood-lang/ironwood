#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Linux part of the M6 checkpoint, run from a git archive of the recorded
# commit in BASE/tree on the estonia host (x86-64: JDK 21.0.1 and the IDK
# 0.3.1 LLVM 23 toolchain) or the miami VM (arm64: Temurin 21 and the IDK
# 0.2.5 LLVM 23 toolchain): the 15 M6 tests (m6-tests.txt) and the five M5.4
# consumers of the shared identifier table. On estonia the JDK selection test
# also inspects the installed JDK homes listed below that exist. Logs land in
# BASE/logs; the done marker ends the run.
set -u
base=${1:?usage: linux-evidence.sh BASE}
case "$base" in /*) ;; *) base=$HOME/$base ;; esac
tree=$base/tree
logs=$base/logs
unset IRONWOOD_TEST_JDKS
case "$(uname -m)" in
    x86_64)
        export JAVA_HOME=/usr/java/jdk-21.0.1
        export IRONWOOD_LLVM_HOME=/home/developer/temp/test-ironwood/ironwood-idk-0.3.1-linux-x86_64/toolchain
        jdks=""
        for home in /usr/java/jdk-17.0.1 /usr/java/jdk-20.0.1 /usr/java/jdk-23.0.1 /usr/java/jdk-25.0.4.1 \
                /usr/java/graalvm-jdk-21.0.5+9.1 /usr/java/graalvm-jdk-25.0.4+7.1 /usr/java/jdk-21.0.6+6-temurin \
                /usr/java/jdk-23+37-temurin /usr/java/jdk-21.0.5+11-semeru /usr/java/jdk-23.0.1+11-semeru; do
            [ -x "$home/bin/java" ] && jdks=${jdks:+$jdks:}$home
        done
        export IRONWOOD_TEST_JDKS=$jdks ;;
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
  "$IRONWOOD_LLVM_HOME/bin/llvm-config" --version; df -h "$base" | tail -1; uptime;
  echo "IRONWOOD_TEST_JDKS=${IRONWOOD_TEST_JDKS:-}"; } > "$logs/host.txt" 2>&1
cd "$tree"
tests=()
while IFS= read -r name; do tests+=("$name"); done < docs/self-hosting/m6/handoff-evidence/m6-tests.txt
tests+=("M5.4 documentation scans match the Java documentation tools"
        "M5.4 Java identifier-part table regenerates from JDK 21"
        "M5.4 IronDoc source walks match Java's Files.walk selection"
        "M5.4 documentation helpers borrow inputs and own their results"
        "M5.4 documentation helpers unwind every allocation failure")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
./scripts/test.sh "${args[@]}" > "$logs/tests.log" 2>&1; status linux-tests $?
touch "$logs/done"
cat "$logs/status.txt"
