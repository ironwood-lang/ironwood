#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# D264 helper evidence: a fresh git archive of the given commit, the strict
# build, the four helper tests, four fresh runs of each Java 21 reference,
# the three native transcripts from classes and archive at -O3, the
# allocation-failure sweep, and the checkout's diff and license audits.
# Logs land in workspace/m3/helpers-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
base=616f4e56
ev=$root/workspace/m3/helpers-evidence
stage=$root/workspace/m3.noindex/helpers-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
git -C "$root" archive "$commit" | tar -xf - -C "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
cd "$stage"
scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar > "$ev/build-identity.txt"
tests=("compiler port helpers keep borrowed items and captured state alive"
       "compiler port generics spell reference bounds"
       "compiler port helpers match Java stacks values and callbacks across artifacts"
       "compiler port helpers unwind every allocation failure")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
./scripts/test.sh "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
port=compiler/src/main/ironwood/ironwood/compiler/port
helpers=(Action BooleanSource Condition Extremes IdentityMapper Lists Mapper Maps PairAction ScopeStack
         SnapshotList SnapshotMap SnapshotSet Source StringOrder)
sources=(); for helper in "${helpers[@]}"; do sources+=("$port/$helper.iron"); done
for pair in compiler_scope_stack:ScopeStackReference compiler_value_snapshots:ValueSnapshotsReference \
        compiler_callbacks:CallbacksReference; do
    fixture=${pair%%:*}; reference=${pair#*:}
    for run in 1 2 3 4; do
        "$JAVA_HOME/bin/java" "docs/self-hosting/m3/helpers-evidence/$reference.java" > "$ev/$fixture-java-$run.txt"
        status "$fixture-java-$run" $?
    done
    work=$ev/native/$fixture && mkdir -p "$work"
    bin/ironwoodc "${sources[@]}" "integration-tests/cases/$fixture.iron" --unfreed=warn -d "$work/classes" \
        > "$ev/$fixture-compile.log" 2>&1; status "$fixture-compile" $?
    bin/ironwoodc --link -cp "$work/classes" --main-class Main --unfreed=warn -O3 -o "$work/program" \
        > "$ev/$fixture-link.log" 2>&1; status "$fixture-link" $?
    "$work/program" > "$ev/$fixture-native.txt"; status "$fixture-run-42" $?
    cmp "$ev/$fixture-java-1.txt" "$ev/$fixture-native.txt" > /dev/null 2>&1; status "$fixture-equal" $?
done
work=$ev/native/failure && mkdir -p "$work"
bin/ironwoodc "${sources[@]}" integration-tests/cases/compiler_port_helpers_failure.iron --unfreed=warn \
    -d "$work/classes" > "$ev/failure-compile.log" 2>&1; status failure-compile $?
bin/ironwoodc --link -cp "$work/classes" --main-class Main --unfreed=warn -O3 -o "$work/program" \
    > "$ev/failure-link.log" 2>&1; status failure-link $?
: > "$ev/oom.log"
for ((limit = 0; limit < 400; limit++)); do
    IRONWOOD_ALLOCATION_LIMIT=$limit "$work/program"; code=$?
    echo "limit=$limit exit=$code" >> "$ev/oom.log"
    [ "$code" = 43 ] && break
done
"$work/program"; echo "limit=none exit=$?" >> "$ev/oom.log"
cd "$root"
git diff --check "$base" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
