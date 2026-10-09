#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# D265 inventory evidence: a fresh git archive of the given commit, the strict
# build, the three inventory tests, an independent regeneration compared with
# the checked-in IrModel.iron, the native transcript from classes, and the
# checkout's diff and license audits. Logs land in workspace/m3/variants-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
base=5a31d624
ev=$root/workspace/m3/variants-evidence
stage=$root/workspace/m3.noindex/variants-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
git -C "$root" archive "$commit" | tar -xf - -C "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
cd "$stage"
scripts/build.sh > "$ev/build.log" 2>&1; status build $?
tests=("IR model inventory matches the Java model"
       "IR model inventory fails closed on untreated variants"
       "IR model inventory matches the Java model across artifacts")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
./scripts/test.sh "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
target=compiler/src/main/ironwood/ironwood/compiler/port/IrModel.iron
cp "$target" "$ev/IrModel.checked-in.iron"
mkdir -p "$ev/gen"
"$JAVA_HOME/bin/javac" -d "$ev/gen" -cp compiler/build/classes compiler/src/test/java/ironwood/compiler/IrModelInventory.java \
    > "$ev/generator-compile.log" 2>&1; status generator-compile $?
"$JAVA_HOME/bin/java" -cp "$ev/gen:compiler/build/classes" ironwood.compiler.IrModelInventory > "$ev/generator.log" 2>&1
status generator $?
cmp "$ev/IrModel.checked-in.iron" "$target" > "$ev/regeneration-compare.log" 2>&1; status regeneration-equal $?
work=$ev/native && mkdir -p "$work"
bin/ironwoodc "$target" integration-tests/cases/compiler_ir_model.iron --unfreed=warn -d "$work/classes" \
    > "$ev/fixture-compile.log" 2>&1; status fixture-compile $?
bin/ironwoodc --link -cp "$work/classes" --main-class Main --unfreed=warn -O3 -o "$work/program" \
    > "$ev/fixture-link.log" 2>&1; status fixture-link $?
"$work/program" > "$ev/native.txt"; status fixture-run-42 $?
cd "$root"
git diff --check "$base" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
