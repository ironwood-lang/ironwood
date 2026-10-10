#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M4.1/M4.2 filesystem checkpoint run: a fresh git archive of the given
# commit, the strict build, warning-free compilation of every compiler test
# source, the twelve M4.1/M4.2 tests with the unchanged U2, U5, filesystem,
# path, IronDocs, round-trip and port-bound consumers, the M4.1-M4.2
# classification with the M3 tables regenerated unchanged, the function-body
# comparison against the pre-change compiler, one compilation of every port
# source with both pilot adapters (no diagnostics), the diff and license
# audits, and linux-evidence.sh on the x86host host from the same archive.
# Logs land in workspace/m4/filesystem-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
start=bdf34a55a1981aa36297a2b6fcff720bc9cf159c
prechange=e9024cceda30be77e980c37c6a79a44ba9b3c60b
ev=$root/workspace/m4/filesystem-evidence
stage=$root/workspace/m4.noindex/filesystem-stage
base=$root/workspace/m4.noindex/filesystem-base
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
export PATH=$JAVA_HOME/bin:$PATH
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS IRONWOOD_RUNTIME_HOME IRONWOOD_STDLIB_HOME IRONWOOD_VERSION TMPDIR
rm -rf "$ev" "$stage" "$base" && mkdir -p "$ev" "$stage" "$base"
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
tests=("M4.1 filesystem services lower to typed IR and runtime boundaries"
       "M4.1 filesystem services borrow inputs and own their results"
       "M4.1 filesystem services match Java 21 across artifacts and TMPDIR settings"
       "M4.1 filesystem services unwind every allocation failure without leftovers"
       "M4.1 native temporary creation survives collisions and injected failures"
       "compiler tree deletion matches the Java cleanup policies across artifacts"
       "M4.2 publication moves lower to typed IR and return the caller's target"
       "M4.2 publication moves match Java 21 on one file system across artifacts"
       "M4.2 publication moves keep their guarantees across file systems"
       "M4.2 exclusive publication admits exactly one competing process"
       "M4.2 native publication survives injected races and failures"
       "M4.2 staged publication unwinds every allocation failure without leftovers"
       "U2 path and whole-file operations use typed IR and audited ownership"
       "U2 paths and whole-file I/O run at O3"
       "U2 file and path allocation failures roll back at O3"
       "U5 directory foundation uses typed native operations"
       "U5 file tree traversal enforces borrowed visitor callbacks"
       "U5 directory foundation enumerates entries and reads attributes"
       "U5 file tree traversal controls depth links and cleanup"
       "filesystem mutation and random access helpers run at O3"
       "native filesystem scratch and resource cleanup survive injected failures"
       "path operations allocate only their owned result and match Java 21"
       "filesystem operations minimize managed scratch and preserve snapshots"
       "path transformations roll back both allocation boundaries"
       "Files.readAllLines rolls back partial results on OOM"
       "IO and NIO compatibility helpers run at O3"
       "caller-owned library results survive source class archive and tree-shaking round trips"
       "IronDocs comments, CLI, links, and reproducible library documentation"
       "compiler port generics spell reference bounds")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
python3 docs/self-hosting/m3/classify.py M4.1-M4.2 --markdown "$ev/classification.md" > "$ev/classification.log" 2>&1
status classification $?
cmp "$ev/classification.md" docs/self-hosting/m4/CLASSIFICATION_M4.1-M4.2.md; status classification-recorded $?
for phase in M3.1 M3.2 M3.3; do
    python3 docs/self-hosting/m3/classify.py "$phase" --markdown "$ev/m3-$phase.md" >> "$ev/classification.log" 2>&1
    cmp "$ev/m3-$phase.md" "docs/self-hosting/m3/CLASSIFICATION_$phase.md"; status "classification-$phase-unchanged" $?
    rm -f "$ev/m3-$phase.md"
done
# Programs that call no new member keep their function bodies (pre-change compiler versus this one).
git -C "$root" archive "$prechange" | tar -xf - -C "$base"
(cd "$base" && scripts/build.sh > "$ev/base-build.log" 2>&1); status base-build $?
for program in stdlib_file_mutations stdlib_u5_directory_foundation; do
    for side in base current; do
        jar=$([ $side = base ] && echo "$base/compiler/build/ironwoodc.jar" || echo compiler/build/ironwoodc.jar)
        "$JAVA_HOME/bin/java" -jar "$jar" -d "$ev/$side-$program" "integration-tests/cases/$program.iron" > /dev/null 2>&1
        "$JAVA_HOME/bin/java" -jar "$jar" --link -O3 -cp "$ev/$side-$program" --main-class Main \
            --emit-llvm "$ev/$side-$program.ll" -o "$ev/$side-$program-program" > /dev/null 2>&1
    done
    python3 docs/self-hosting/m4/compare_functions.py "$ev/base-$program.ll" "$ev/current-$program.ll" \
        >> "$ev/llvm-functions.log" 2>&1; status "llvm-functions-$program" $?
    rm -rf "$ev/base-$program" "$ev/current-$program" "$ev"/*-"$program".ll "$ev"/*-"$program"-program
done
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
# Linux x86-64 on x86host, from the same archive.
remote=temp/java_bridge
git -C "$root" archive "$commit" | ssh -o BatchMode=yes x86host \
    "rm -rf $remote/m4-evidence && mkdir -p $remote/m4-evidence && tar -xf - -C $remote/m4-evidence"
status linux-ship $?
ssh -o BatchMode=yes x86host "cd $remote && nohup m4-evidence/docs/self-hosting/m4/filesystem-evidence/linux-evidence.sh \
    > m4-evidence-run.log 2>&1 < /dev/null &"
until ssh -o BatchMode=yes x86host "grep -q '^no-newer-wrappers exit=' $remote/m4-evidence-logs/status.txt 2>/dev/null"; do
    sleep 15
done
for log in status.txt host.txt tests.log abi-build.log runtime-undefined.txt glibc-versions.txt; do
    scp -q "x86host:$remote/m4-evidence-logs/$log" "$ev/linux-$log"
done
sed 's/^/linux: /' "$ev/linux-status.txt" >> "$ev/status.txt"
ssh -o BatchMode=yes x86host "rm -rf $remote/m4-evidence $remote/m4-evidence-logs"
cat "$ev/status.txt"
