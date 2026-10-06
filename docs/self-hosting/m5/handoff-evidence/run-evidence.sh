#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M5 artifacts and command-line helpers checkpoint run: a fresh git archive
# of the given commit, the strict build, warning-free compilation of every
# compiler test source, the 25 M5 tests (m5-tests.txt) with the consumers the
# pre-change review named (the Ironwood archive and IronDocs tests, SHA-256,
# borrow dispatch, the U5 traversals, the M4 filesystem, deletion, discovery
# and backend helpers, the port generics audit), the three M5 classifications
# with every earlier table regenerated unchanged, one compilation of every
# port source with the pilot adapters (no diagnostics), the diff and license
# audits, and linux-evidence.sh from the same archive on the estonia (Linux
# x86-64) and miami (Linux arm64) hosts. Logs land in
# workspace/m5/handoff-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
start=ea19508cd62e2fe1e7dcfe56d991453ff2795dda
ev=$root/workspace/m5/handoff-evidence
stage=$root/workspace/m5.noindex/handoff-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
export PATH=$JAVA_HOME/bin:$PATH
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS IRONWOOD_RUNTIME_HOME IRONWOOD_STDLIB_HOME IRONWOOD_VERSION TMPDIR
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
git -C "$root" archive "$commit" | tar -xf - -C "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
{ sw_vers; uname -m; "$JAVA_HOME/bin/java" -version 2>&1 | head -1; "$IRONWOOD_LLVM_HOME/bin/llvm-config" --version; } > "$ev/host.txt"
cd "$stage"
scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar > "$ev/build-identity.txt"
rm -rf compiler/build/test-classes && mkdir -p compiler/build/test-classes
find compiler/src/test/java -name '*.java' | sort > compiler/build/test-sources.txt
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp compiler/build/classes \
    -d compiler/build/test-classes @compiler/build/test-sources.txt > "$ev/javac.log" 2>&1; status javac $?
tests=()
while IFS= read -r name; do tests+=("$name"); done < docs/self-hosting/m5/handoff-evidence/m5-tests.txt
tests+=("Ironwood archives create list and reproduce exact bytes"
        "Ironwood archives reject malformed paths indexes and payloads"
        "Ironwood archives resolve class-path types lazily"
        "caller-owned library results survive source class archive and tree-shaking round trips"
        "IronDocs comments, CLI, links, and reproducible library documentation"
        "compiler SHA-256 matches Java digests across artifacts"
        "borrow dispatch uses exact overloads defaults and receiver flow"
        "borrow dispatch rejects retaining and unknown receiver flows"
        "U5 directory foundation enumerates entries and reads attributes"
        "U5 file tree traversal controls depth links and cleanup"
        "U5 file tree traversal enforces borrowed visitor callbacks"
        "M4.1 filesystem services unwind every allocation failure without leftovers"
        "M4.2 publication moves match Java 21 on one file system across artifacts"
        "compiler tree deletion matches the Java cleanup policies across artifacts"
        "compiler installation inputs match Java discovery across layouts and artifacts"
        "compiler backend helpers unwind every allocation failure"
        "compiler port generics spell reference bounds")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
: > "$ev/classification.log"
for phase in M5.1 M5.2-M5.3 M5.4; do
    python3 docs/self-hosting/m3/classify.py "$phase" --markdown "$ev/classification-$phase.md" \
        >> "$ev/classification.log" 2>&1; status "classification-$phase" $?
    cmp "$ev/classification-$phase.md" "docs/self-hosting/m5/CLASSIFICATION_$phase.md"
    status "classification-$phase-recorded" $?
done
for table in m3/CLASSIFICATION_M3.1 m3/CLASSIFICATION_M3.2 m3/CLASSIFICATION_M3.3 m4/CLASSIFICATION_M4.1-M4.2 \
        m4/CLASSIFICATION_M4.3; do
    phase=${table#*CLASSIFICATION_}
    python3 docs/self-hosting/m3/classify.py "$phase" --markdown "$ev/earlier.md" >> "$ev/classification.log" 2>&1
    cmp "$ev/earlier.md" "docs/self-hosting/$table.md"; status "classification-$phase-unchanged" $?
done
rm -f "$ev/earlier.md"
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
# Linux x86-64 on estonia and Linux arm64 on miami, from the same archive.
# The first ssh after the miami VM wakes can fail with "No route to host".
retry() { for attempt in 1 2 3 4 5 6; do "$@" && return 0; sleep 10; done; return 1; }
ship() {
    git -C "$root" archive "$commit" | ssh -o BatchMode=yes "$1" \
        "rm -rf $2 && mkdir -p $2/tree && tar -xf - -C $2/tree"
}
launch() {
    ssh -o BatchMode=yes "$1" "cd $2 && nohup tree/docs/self-hosting/m5/handoff-evidence/linux-evidence.sh $2 \
        > run.log 2>&1 < /dev/null &"
}
hosts=("estonia temp/java_bridge/m5-evidence linux-x86_64" "miami temp/ironwood-m5 linux-arm64")
for entry in "${hosts[@]}"; do
    set -- $entry
    retry ship "$1" "$2"; status "$3-ship" $?
    retry launch "$1" "$2"; status "$3-launch" $?
done
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
