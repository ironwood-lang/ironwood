#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M4.3 process and driver checkpoint run: a fresh git archive of the given
# commit, the strict build, warning-free compilation of every compiler test
# source, the fifteen M4.3 tests (m4.3-tests.txt) with the consumers of the
# changed machinery (creation arrays, ownership summaries, IR consumers,
# toolchain and TLS discovery, the M4.1/M4.2 typed operations, IronDocs), the
# M4.3 classification with every earlier table regenerated unchanged, one
# compilation of every port source with both pilot adapters (no
# diagnostics), the diff and license audits, a serial discovery measurement
# once the one-minute load is below 3, and linux-evidence.sh on the estonia
# host from the same archive. Logs land in workspace/m4/process-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
start=bdf34a55a1981aa36297a2b6fcff720bc9cf159c
ev=$root/workspace/m4/process-evidence
stage=$root/workspace/m4.noindex/process-stage
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
while IFS= read -r name; do tests+=("$name"); done < docs/self-hosting/m4/process-evidence/m4.3-tests.txt
tests+=("creation-array cleanup proves distinct fresh elements"
        "creation-array competing second-pass diagnostics follow first store"
        "creation-array cleanup destroys elements natively"
        "flat interface snapshots preserve owned element borrows"
        "compiler backend helpers borrow inputs and own their results"
        "compiler installation inputs match Java discovery across layouts and artifacts"
        "compiler port generics spell reference bounds"
        "compiler tree deletion matches the Java cleanup policies across artifacts"
        "pool release helper proofs preserve mandatory safety"
        "borrow dispatch rejects retaining and unknown receiver flows"
        "util compatibility helpers run at O3"
        "generic list families run at O3"
        "caller-owned library results survive source class archive and tree-shaking round trips"
        "U2 path and whole-file operations use typed IR and audited ownership"
        "M4.1 filesystem services lower to typed IR and runtime boundaries"
        "M4.2 publication moves lower to typed IR and return the caller's target"
        "Clang version reporting preserves vendor identity and diagnoses query failures"
        "LLVM 23 toolchain is discovered"
        "invalid LLVM home is diagnosed"
        "TLS dependency selection follows pruned typed operations"
        "IronDocs comments, CLI, links, and reproducible library documentation")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
python3 docs/self-hosting/m3/classify.py M4.3 --markdown "$ev/classification.md" > "$ev/classification.log" 2>&1
status classification $?
cmp "$ev/classification.md" docs/self-hosting/m4/CLASSIFICATION_M4.3.md; status classification-recorded $?
for phase in M3.1 M3.2 M3.3; do
    python3 docs/self-hosting/m3/classify.py "$phase" --markdown "$ev/earlier.md" >> "$ev/classification.log" 2>&1
    cmp "$ev/earlier.md" "docs/self-hosting/m3/CLASSIFICATION_$phase.md"; status "classification-$phase-unchanged" $?
done
python3 docs/self-hosting/m3/classify.py M4.1-M4.2 --markdown "$ev/earlier.md" >> "$ev/classification.log" 2>&1
cmp "$ev/earlier.md" docs/self-hosting/m4/CLASSIFICATION_M4.1-M4.2.md; status classification-M4.1-M4.2-unchanged $?
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
# Discovery measurement: seven serial runs once the one-minute load is below 3.
measure=$ev/measure && mkdir -p "$measure/tmp"
port=compiler/src/main/ironwood/ironwood/compiler/port
"$JAVA_HOME/bin/java" -jar compiler/build/ironwoodc.jar --unfreed=warn -d "$measure/classes" "$port/Probes.iron" \
    "$port/ProbeRecord.iron" "$port/Command.iron" "$port/TreeDeletion.iron" \
    integration-tests/cases/compiler_discovery_probes.iron > /dev/null 2>&1
"$JAVA_HOME/bin/java" -jar compiler/build/ironwoodc.jar --link --unfreed=warn -O3 -cp "$measure/classes" \
    --main-class Main -o "$measure/probes" > /dev/null 2>&1
"$IRONWOOD_LLVM_HOME/bin/clang" -isysroot "$(/usr/bin/xcrun --show-sdk-path)" -O2 \
    integration-tests/runtime/process_helper.c -o "$measure/helper"
until [ "$(sysctl -n vm.loadavg | awk '{print ($2 < 3)}')" = 1 ]; do sleep 10; done
{ uptime; ps -A -o %cpu,etime,comm -r | head -6; } > "$ev/measure-host.txt"
for run in 1 2 3 4 5 6 7; do
    TMPDIR="$measure/tmp" "$measure/probes" "$IRONWOOD_LLVM_HOME" "$measure/helper" \
        >> "$ev/measure-stdout.txt" 2>> "$ev/measure-stderr.txt"
done
[ -z "$(ls -A "$measure/tmp")" ]; status measure-no-leftovers $?
rm -rf "$measure"
cd "$root"
git diff --check "$start" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
# Linux x86-64 on estonia, from the same archive.
remote=temp/java_bridge
git -C "$root" archive "$commit" | ssh -o BatchMode=yes estonia \
    "rm -rf $remote/m4-evidence && mkdir -p $remote/m4-evidence && tar -xf - -C $remote/m4-evidence"
status linux-ship $?
ssh -o BatchMode=yes estonia "cd $remote && nohup m4-evidence/docs/self-hosting/m4/process-evidence/linux-evidence.sh \
    > m4-evidence-run.log 2>&1 < /dev/null &"
until ssh -o BatchMode=yes estonia "grep -q '^linux-tests exit=' $remote/m4-evidence-logs/status.txt 2>/dev/null"; do
    sleep 15
done
for log in status.txt host.txt tests.log; do
    scp -q "estonia:$remote/m4-evidence-logs/$log" "$ev/linux-$log"
done
sed 's/^/linux: /' "$ev/linux-status.txt" >> "$ev/status.txt"
ssh -o BatchMode=yes estonia "rm -rf $remote/m4-evidence $remote/m4-evidence-logs"
cat "$ev/status.txt"
