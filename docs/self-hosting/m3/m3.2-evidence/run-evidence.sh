#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M3.2 handoff run: a fresh git archive of the given commit, the strict build,
# warning-free compilation of every compiler test source, the M3.2 tests and
# the consumers that compile every port source (the reference-bound audit and
# the nine M2 pilot tests), the M3.2 classification, then the S3 capacity
# evidence under the pinned JVM profile: J0 on the accumulated port, and the
# handoff build compiling the port with both pilot adapters in one invocation
# and linking each adapter at -O3, twice each, serially, each only after the
# one-minute load falls below 3. Logs land in workspace/m3/m3.2-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
base=adfa0515194209c1cd4fb2766f3097ea79f51573
ev=$root/workspace/m3/m3.2-evidence
stage=$root/workspace/m3.noindex/m3.2-stage
j0=$root/target/self-hosting-m0/J0-final2/lib/ironwoodc.jar
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
profile=(-Xms256m -Xmx4096m -Xss8m -XX:+UseG1GC)
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
git -C "$root" archive "$commit" | tar -xf - -C "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
cd "$stage"
scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar "$j0" > "$ev/build-identity.txt"
rm -rf compiler/build/test-classes && mkdir -p compiler/build/test-classes
find compiler/src/test/java -name '*.java' | sort > compiler/build/test-sources.txt
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp compiler/build/classes \
    -d compiler/build/test-classes @compiler/build/test-sources.txt > "$ev/javac.log" 2>&1; status javac $?
tests=("compiler integral constants match J0 literal decoding and folds across artifacts"
       "compiler SHA-256 matches Java digests across artifacts"
       "compiler text helpers match J0 UTF-8 lengths and Java text across artifacts"
       "compiler floating text matches Java conversions across artifacts"
       "ByteView declaration authority holds for J0 source class and archive contents"
       "compiler semantic helpers borrow inputs and own their results"
       "compiler semantic helpers unwind every allocation failure"
       "compiler port generics spell reference bounds"
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
python3 docs/self-hosting/m3/classify.py M3.2 --markdown "$ev/classification.md" > "$ev/classification.log" 2>&1
status classification $?

# S3 capacity: every port source, both pilot adapters as entry points.
cap=$ev/capacity && mkdir -p "$cap" && out=$ev/capacity-work && mkdir -p "$out"
find compiler/src/main/ironwood -name '*.iron' | sort > "$cap/port-sources.txt"
adapters=(scripts/self-hosting/native/FrontendCapture.iron scripts/self-hosting/native/FrontendWire.iron
          scripts/self-hosting/native/FrontendCensus.iron scripts/self-hosting/native/FrontendLiterals.iron
          scripts/self-hosting/native/KernelCapture.iron)
while IFS= read -r source; do shasum -a 256 "$source"; done < "$cap/port-sources.txt" > "$cap/port-sources.sha256"
for adapter in "${adapters[@]}"; do shasum -a 256 "$adapter"; done >> "$cap/port-sources.sha256"
wait_quiet() {
    for ((wait = 0; wait < 360; wait++)); do
        load=$(sysctl -n vm.loadavg | awk '{print $2}')
        awk -v load="$load" 'BEGIN { exit !(load < 3) }' && break
        sleep 5
    done
}
measure() {
    local name=$1; shift
    wait_quiet
    uptime > "$cap/$name.load"
    local start end
    start=$(python3 -c 'import time; print(time.monotonic())')
    /usr/bin/time -l -o "$cap/$name.rusage" "$JAVA_HOME/bin/java" "${profile[@]}" -Xlog:gc:file="$cap/$name.gc" \
        -XX:+PrintCommandLineFlags "$@" > "$cap/$name.log" 2>&1
    echo $? > "$cap/$name.status"
    end=$(python3 -c 'import time; print(time.monotonic())')
    python3 -c "print(round($end - $start, 3))" > "$cap/$name.wall"
}
for run in 1 2; do
    measure "j0-compile-$run" -jar "$j0" --unfreed=warn -d "$out/j0-$run" $(cat "$cap/port-sources.txt") "${adapters[@]}"
    measure "seed-compile-$run" -jar compiler/build/ironwoodc.jar --unfreed=warn -d "$out/classes-$run" \
        $(cat "$cap/port-sources.txt") "${adapters[@]}"
    measure "seed-link-frontend-$run" -jar compiler/build/ironwoodc.jar --link -cp "$out/classes-$run" \
        --main-class FrontendCapture --unfreed=warn -O3 -o "$out/frontend-$run"
    measure "seed-link-kernel-$run" -jar compiler/build/ironwoodc.jar --link -cp "$out/classes-$run" \
        --main-class KernelCapture --unfreed=warn -O3 -o "$out/kernel-$run"
done
python3 docs/self-hosting/m3/capacity.py "$cap" "$ev/capacity.json" > "$ev/capacity.log" 2>&1; status capacity-summary $?
for run in 1 2; do
    status "j0-compile-$run-expected-failure" "$(cat "$cap/j0-compile-$run.status")"
    for step in seed-compile seed-link-frontend seed-link-kernel; do status "$step-$run" "$(cat "$cap/$step-$run.status")"; done
done
grep -E '^error' "$cap/j0-compile-1.log" | sort | uniq -c | sort -rn > "$ev/j0-errors.txt"
grep -cE '^(error|warning)' "$cap/seed-compile-1.log" > "$ev/seed-diagnostics.txt"
cd "$root"
git diff --check "$base" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
