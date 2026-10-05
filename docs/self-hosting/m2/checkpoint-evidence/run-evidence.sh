#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# G1 checkpoint run: a fresh git archive of the given commit, the strict
# build, warning-free test compilation and the nine M2 focused tests, plus
# the checkout's diff and license audits.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
ev=$root/workspace/m2/g1-evidence
stage=$root/workspace/m2.noindex/g1-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
git -C "$root" archive "$commit" | tar -xf - -C "$stage"
cd "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar > "$ev/build-identity.txt"
rm -rf compiler/build/test-classes && mkdir -p compiler/build/test-classes
find compiler/src/test/java -name '*.java' | sort > compiler/build/test-sources.txt
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp compiler/build/classes \
    -d compiler/build/test-classes @compiler/build/test-sources.txt > "$ev/javac.log" 2>&1; status javac $?
tests=("frontend pilot variants match the Java syntax model"
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
cd "$root"
# Every M2 change since the M1 checkpoint commit.
git diff --check 97417cb4c6a09193f79eaeaa341fbe703aa93196 "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
