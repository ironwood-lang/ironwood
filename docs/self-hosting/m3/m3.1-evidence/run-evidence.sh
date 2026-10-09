#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M3.1 handoff run: a fresh git archive of the given commit, the strict
# build, warning-free compilation of every compiler test source, the eleven
# M3.1 tests, the eight ArrayList consumers of D263, the twelve M1/M2
# consumers of the changed Lists.iron, the M3.1 classification of the M0
# inventory, and the checkout's diff and license audits. Logs land in
# workspace/m3/m3.1-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
base=adfa0515194209c1cd4fb2766f3097ea79f51573
ev=$root/workspace/m3/m3.1-evidence
stage=$root/workspace/m3.noindex/m3.1-stage
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
rm -rf compiler/build/test-classes && mkdir -p compiler/build/test-classes
find compiler/src/test/java -name '*.java' | sort > compiler/build/test-sources.txt
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp compiler/build/classes \
    -d compiler/build/test-classes @compiler/build/test-sources.txt > "$ev/javac.log" 2>&1; status javac $?
tests=("list comparator sort keeps storage and exposes sorted items conservatively"
       "list comparator sort omits natural-order and array adapters"
       "list comparator sort matches Java List.sort across artifacts"
       "list comparator sort unwinds every allocation failure"
       "compiler port helpers keep borrowed items and captured state alive"
       "compiler port generics spell reference bounds"
       "compiler port helpers match Java stacks values and callbacks across artifacts"
       "compiler port helpers unwind every allocation failure"
       "IR model inventory matches the Java model"
       "IR model inventory fails closed on untreated variants"
       "IR model inventory matches the Java model across artifacts"
       "generic list families run at O3"
       "pool and data structures allocate nothing after warmup"
       "data structures retain inserted references for safe-free analysis"
       "data structure generic bounds reject primitives at the use site"
       "independent list copies preserve destination and nested payload loans"
       "independent list copy proofs survive source class and archive reconstruction"
       "caller-owned library results survive source class archive and tree-shaking round trips"
       "util compatibility helpers run at O3"
       "compiler value helpers keep ownership of results only"
       "compiler value helpers match Java escapes counts lists and records"
       "compiler value helpers leave no storage after allocation failure"
       "frontend pilot variants match the Java syntax model"
       "frontend pilot dispatch fails closed on untreated variants"
       "frontend pilot retires builders and rejects frees of observed state"
       "frontend pilot matches the Java frontend across artifacts"
       "frontend pilot unwinds every allocation failure cleanly"
       "ownership pilot retires versions and rejects frees of observed state"
       "ownership pilot counts callbacks and rejects frees of captured state"
       "ownership pilot matches the Java kernels across artifacts"
       "ownership pilot unwinds every allocation failure cleanly")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
python3 docs/self-hosting/m3/classify.py M3.1 --markdown "$ev/classification.md" > "$ev/classification.log" 2>&1
status classification $?
cd "$root"
git diff --check "$base" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
