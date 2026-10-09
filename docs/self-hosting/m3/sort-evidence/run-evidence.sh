#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# D263 list sort evidence: a fresh git archive of the given commit, the strict
# build, the four sort tests and eight existing ArrayList consumers, four
# fresh Java 21 reference runs, the native transcript, the allocation-failure
# sweep, O3 assembly of the merge loop, the timing series, LLVM function
# identity against the pre-change base and focused IronDocs, plus the
# checkout's diff and license audits. Logs land in workspace/m3/sort-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
base=adfa0515194209c1cd4fb2766f3097ea79f51573
ev=$root/workspace/m3/sort-evidence
stage=$root/workspace/m3.noindex/sort-stage
basestage=$root/workspace/m3.noindex/sort-base
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
llvm=$IRONWOOD_LLVM_HOME/bin
rm -rf "$ev" "$stage" "$basestage" && mkdir -p "$ev" "$stage" "$basestage"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
git -C "$root" archive "$commit" | tar -xf - -C "$stage"
git -C "$root" archive "$base" | tar -xf - -C "$basestage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
cd "$stage"
scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar > "$ev/build-identity.txt"
tests=("list comparator sort keeps storage and exposes sorted items conservatively"
       "list comparator sort omits natural-order and array adapters"
       "list comparator sort matches Java List.sort across artifacts"
       "list comparator sort unwinds every allocation failure"
       "generic list families run at O3"
       "pool and data structures allocate nothing after warmup"
       "data structures retain inserted references for safe-free analysis"
       "data structure generic bounds reject primitives at the use site"
       "independent list copies preserve destination and nested payload loans"
       "independent list copy proofs survive source class and archive reconstruction"
       "caller-owned library results survive source class archive and tree-shaking round trips"
       "util compatibility helpers run at O3")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
./scripts/test.sh "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
for run in 1 2 3 4; do
    "$JAVA_HOME/bin/java" docs/self-hosting/m3/sort-evidence/ListSortReference.java > "$ev/java-$run.txt"
    status "java-reference-$run" $?
done
work=$ev/native && mkdir -p "$work"
bin/ironwoodc --unfreed=warn -d "$work/classes" integration-tests/cases/ds_array_list_sort.iron > "$ev/fixture-compile.log" 2>&1; status fixture-compile $?
bin/ironwoodc --link -cp "$work/classes" --main-class Main --unfreed=warn -O3 -o "$work/sort" > "$ev/fixture-link.log" 2>&1; status fixture-link $?
"$work/sort" > "$ev/native.txt"; status fixture-run-42 $?
cmp "$ev/java-1.txt" "$ev/native.txt" > "$ev/transcript-compare.log" 2>&1; status transcript-equal $?
bin/ironwoodc --unfreed=warn -d "$work/fclasses" integration-tests/cases/ds_array_list_sort_failure.iron > "$ev/failure-compile.log" 2>&1; status failure-compile $?
bin/ironwoodc --link -cp "$work/fclasses" --main-class Main --unfreed=warn -O3 -o "$work/failure" > "$ev/failure-link.log" 2>&1; status failure-link $?
: > "$ev/oom.log"
for ((limit = 0; limit <= 12; limit++)); do
    IRONWOOD_ALLOCATION_LIMIT=$limit "$work/failure"; echo "limit=$limit exit=$?" >> "$ev/oom.log"
done
"$work/failure"; echo "limit=none exit=$?" >> "$ev/oom.log"
# O3 merge loop: one implementation (devirtualized) and two (guarded dispatch).
for shape in single poly; do
    dir=$work/o3-$shape && mkdir -p "$dir"
    cp "$root/docs/self-hosting/m3/sort-evidence/O3Sort.iron" "$dir/Main.iron"
    if [ "$shape" = poly ]; then sed -i '' 's|^// POLY ||' "$dir/Main.iron"; fi
    bin/ironwoodc --unfreed=warn -d "$dir/classes" "$dir/Main.iron" > "$ev/o3-$shape-compile.log" 2>&1
    bin/ironwoodc --link --unfreed=warn -O3 -cp "$dir/classes" --main-class Main --emit-llvm "$dir/program.ll" -o "$dir/program" > "$ev/o3-$shape-link.log" 2>&1; status "o3-$shape-link" $?
    "$llvm/opt" -S '-passes=default<O3>' -inline-threshold=1000 -enable-partial-inlining "$dir/program.ll" -o "$dir/program.opt.ll"
    "$llvm/llc" -O=3 --relocation-model=pic -filetype=asm "$dir/program.opt.ll" -o "$dir/program.s"
    awk '/^_?ironwood\.ironwood\.ds\.ArrayList\.mergeSort:/,/\.cfi_endproc/' "$dir/program.s" > "$ev/o3-$shape-mergeSort.s"
done
# Measurement hygiene: wait up to 30 minutes for a one-minute load below 3.
for ((wait = 0; wait < 360; wait++)); do
    load=$(sysctl -n vm.loadavg | awk '{print $2}')
    awk -v load="$load" 'BEGIN { exit !(load < 3) }' && break
    sleep 5
done
uptime > "$ev/bench-load.txt"; ps -Ao pid,pcpu,comm -r | head -6 >> "$ev/bench-load.txt"
bin/ironwoodc --unfreed=warn -d "$work/bclasses" "$root/docs/self-hosting/m3/sort-evidence/SortBench.iron" > "$ev/bench-compile.log" 2>&1
bin/ironwoodc --link --unfreed=warn -O3 -cp "$work/bclasses" --main-class Main -o "$work/bench" > "$ev/bench-link.log" 2>&1
"$work/bench" > "$ev/bench.txt"; status bench $?
# A program that never sorts keeps its functions; only closed-world numbering moves.
(cd "$basestage" && scripts/build.sh > "$ev/base-build.log" 2>&1); status base-build $?
for side in base current; do
    if [ "$side" = base ]; then compiler="$basestage/bin/ironwoodc"; else compiler="$stage/bin/ironwoodc"; fi
    "$compiler" --unfreed=warn -d "$work/$side-copy" integration-tests/cases/ds_list_copy.iron > /dev/null 2>&1
    "$compiler" --link -cp "$work/$side-copy" --main-class Main --unfreed=warn -O3 --emit-llvm "$work/$side-copy.ll" -o "$work/$side-copy.bin" > /dev/null 2>&1
done
python3 "$root/docs/self-hosting/m3/sort-evidence/compare_functions.py" "$work/base-copy.ll" "$work/current-copy.ll" > "$ev/llvm-functions.log" 2>&1; status llvm-functions $?
./bin/irondoc -d "$work/list-api" -sourcepath stdlib/src/main/ironwood stdlib/src/main/ironwood/ironwood/ds/ArrayList.iron > "$ev/irondoc.log" 2>&1; status irondoc $?
cd "$root"
git diff --check "$base" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
