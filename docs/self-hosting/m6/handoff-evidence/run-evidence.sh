#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M6 checkpoint run, which closes before-self-hosting preparation: a fresh git
# archive of the given commit, the strict build, warning-free compilation of
# every compiler test source, the 15 M6 tests (m6-tests.txt) with the
# consumers the pre-change review named (the five M5.4 tests of the shared
# identifier table, the M5.2 STORED writer, M4.2's moves, the two M4.3 tests
# of Command and ExecutableSearch, SHA-256 and the port generics audit), the
# M6.3 reconciliation, which regenerates every phase table unchanged, with its
# negative controls, one compilation of every port source with the pilot
# adapters (no diagnostics), the diff and license audits, and
# linux-evidence.sh from the same archive on the estonia (Linux x86-64) and
# miami (Linux arm64) hosts, which start first. The macOS selection test also
# inspects the JDK homes listed in workspace/m6/test-jdks.txt when it exists.
# The macOS part waits for a one-minute load below 3 so that no competing job
# stretches its process timeouts. Logs land in workspace/m6/handoff-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
start=eb3c14138152ab30d9d4d57dba27676a582fc254
ev=$root/workspace/m6/handoff-evidence
stage=$root/workspace/m6.noindex/handoff-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
export PATH=$JAVA_HOME/bin:$PATH PYTHONDONTWRITEBYTECODE=1
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS IRONWOOD_RUNTIME_HOME IRONWOOD_STDLIB_HOME IRONWOOD_VERSION TMPDIR
unset IRONWOOD_TEST_JDKS
[ -f "$root/workspace/m6/test-jdks.txt" ] && export IRONWOOD_TEST_JDKS=$(cat "$root/workspace/m6/test-jdks.txt")
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
git -C "$root" archive "$commit" | tar -xf - -C "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
{ sw_vers; uname -m; "$JAVA_HOME/bin/java" -version 2>&1 | head -1; "$IRONWOOD_LLVM_HOME/bin/llvm-config" --version;
  python3 --version; echo "IRONWOOD_TEST_JDKS=${IRONWOOD_TEST_JDKS:-}"; } > "$ev/host.txt"
# Linux x86-64 on estonia and Linux arm64 on miami, from the same archive.
# The first ssh after the miami VM wakes can fail with "No route to host".
retry() { for attempt in 1 2 3 4 5 6; do "$@" && return 0; sleep 10; done; return 1; }
ship() {
    git -C "$root" archive "$commit" | ssh -o BatchMode=yes "$1" \
        "rm -rf $2 && mkdir -p $2/tree && tar -xf - -C $2/tree"
}
# A single backgrounded command, so no remote subshell keeps the session open.
launch() {
    ssh -o BatchMode=yes "$1" "nohup $2/tree/docs/self-hosting/m6/handoff-evidence/linux-evidence.sh $2 \
        > $2/run.log 2>&1 < /dev/null &"
}
hosts=("estonia temp/java_bridge/m6-evidence linux-x86_64" "miami temp/ironwood-m6 linux-arm64")
for entry in "${hosts[@]}"; do
    set -- $entry
    retry ship "$1" "$2"; status "$3-ship" $?
    retry launch "$1" "$2"; status "$3-launch" $?
done
until [ "$(sysctl -n vm.loadavg | awk '{print ($2 < 3)}')" = 1 ]; do sleep 10; done
{ uptime; ps -A -o %cpu,etime,comm -r | head -6; } > "$ev/load.txt"
cd "$stage"
scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar > "$ev/build-identity.txt"
rm -rf compiler/build/test-classes && mkdir -p compiler/build/test-classes
find compiler/src/test/java -name '*.java' | sort > compiler/build/test-sources.txt
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp compiler/build/classes \
    -d compiler/build/test-classes @compiler/build/test-sources.txt > "$ev/javac.log" 2>&1; status javac $?
tests=()
while IFS= read -r name; do tests+=("$name"); done < docs/self-hosting/m6/handoff-evidence/m6-tests.txt
tests+=("M5.4 documentation scans match the Java documentation tools"
        "M5.4 Java identifier-part table regenerates from JDK 21"
        "M5.4 IronDoc source walks match Java's Files.walk selection"
        "M5.4 documentation helpers borrow inputs and own their results"
        "M5.4 documentation helpers unwind every allocation failure"
        "M5.2 STORED writer equals Java's bytes and opens in Java readers and JAR tools"
        "M4.2 publication moves match Java 21 on one file system across artifacts"
        "M4.3 port executable search matches the Java seed"
        "M4.3 driver adapters own arguments and lend probe outputs"
        "compiler SHA-256 matches Java digests across artifacts"
        "compiler port generics spell reference bounds")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
python3 docs/self-hosting/m6/reconcile.py > "$ev/reconcile.log" 2>&1; status reconcile $?
docs/self-hosting/m6/reconcile-controls.sh "$stage" "$root/workspace/m6.noindex/controls" > "$ev/reconcile-controls.log" 2>&1
status reconcile-controls $?
find compiler/src/main/ironwood -name '*.iron' | sort > "$ev/port-sources.txt"
adapters=(scripts/self-hosting/native/FrontendCapture.iron scripts/self-hosting/native/FrontendWire.iron
          scripts/self-hosting/native/FrontendCensus.iron scripts/self-hosting/native/FrontendLiterals.iron
          scripts/self-hosting/native/KernelCapture.iron)
"$JAVA_HOME/bin/java" -Xms256m -Xmx4096m -Xss8m -XX:+UseG1GC -jar compiler/build/ironwoodc.jar --unfreed=warn \
    -d "$ev/port-classes" $(cat "$ev/port-sources.txt") "${adapters[@]}" > "$ev/port-compile.log" 2>&1
status port-compile $?
grep -cE '^(error|warning)' "$ev/port-compile.log" > "$ev/port-diagnostics.txt"
[ "$(cat "$ev/port-diagnostics.txt")" = 0 ]; status port-diagnostics $?
rm -rf "$ev/port-classes"
cd "$root"
git diff --check "$start" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
# Collect the Linux runs.
for entry in "${hosts[@]}"; do
    set -- $entry
    # The remote script marks its end whatever its statuses; a dropped
    # connection only repeats the check.
    until ssh -o BatchMode=yes "$1" "test -e $2/logs/done" 2>/dev/null; do sleep 15; done
    for log in status.txt host.txt tests.log; do
        retry scp -q "$1:$2/logs/$log" "$ev/$3-$log" || echo "missing $3 $log" >> "$ev/fetch.log"
    done
    sed "s/^/$3: /" "$ev/$3-status.txt" >> "$ev/status.txt"
    retry ssh -o BatchMode=yes "$1" "rm -rf $2"; status "$3-cleanup" $?
done
cat "$ev/status.txt"
