#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M2.2 evidence run: fresh tree of the checkout's tracked and new files, strict
# build, warning-free test compilation, the kernel pilot and its fixtures built
# with --unfreed=warn, focused tests, J0 kernel parity, resource and stack runs,
# the callback counts, the allocation-failure sweep, a frontend differential
# over the new sources, and the whitespace and license audits.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
ev=$root/workspace/m2/m22-evidence
stage=$root/workspace/m2/m22-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
export M2_J0_JAR=$root/target/self-hosting-m0/J0-final2/lib/ironwoodc.jar
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse HEAD > "$ev/base-head.txt"
git -C "$root" ls-files -co --exclude-standard > "$ev/tree-files.txt"
(cd "$root" && tar -cf - -T "$ev/tree-files.txt") | tar -xf - -C "$stage"
(cd "$stage" && find . -type f ! -path './compiler/build/*' -print0 | sort -z | xargs -0 shasum -a 256) > "$ev/tree-sha256.txt"
cd "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }

scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar > "$ev/build-identity.txt"
rm -rf compiler/build/test-classes && mkdir -p compiler/build/test-classes
find compiler/src/test/java -name '*.java' | sort > compiler/build/test-sources.txt
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp compiler/build/classes \
    -d compiler/build/test-classes @compiler/build/test-sources.txt > "$ev/javac.log" 2>&1; status javac $?

iwc() { "$JAVA_HOME/bin/java" -Xss8m -jar compiler/build/ironwoodc.jar "$@"; }
out=$stage/workspace/m2-ownership
mkdir -p "$out"
find compiler/src/main/ironwood -name '*.iron' | sort > "$out/port-sources.txt"
iwc $(cat "$out/port-sources.txt") scripts/self-hosting/native/KernelCapture.iron --unfreed=warn -d "$out/classes" \
    > "$ev/kernel-compile.log" 2>&1; status kernel-compile $?
iwc --link -cp "$out/classes" --main-class KernelCapture --unfreed=warn -O3 -o "$out/kernel-capture" \
    > "$ev/kernel-link.log" 2>&1; status kernel-link $?
for fixture in callbacks failure; do
    iwc $(cat "$out/port-sources.txt") integration-tests/cases/compiler_ownership_$fixture.iron --unfreed=warn \
        -d "$out/$fixture-classes" > "$ev/$fixture-compile.log" 2>&1; status "$fixture-compile" $?
    iwc --link -cp "$out/$fixture-classes" --main-class Main --unfreed=warn -O3 -o "$out/$fixture" \
        > "$ev/$fixture-link.log" 2>&1; status "$fixture-link" $?
done
shasum -a 256 "$out/kernel-capture" "$out/callbacks" "$out/failure" > "$ev/binaries.txt"

tests=("ownership pilot retires versions and rejects frees of observed state"
       "ownership pilot counts callbacks and rejects frees of captured state"
       "ownership pilot matches the Java kernels across artifacts"
       "ownership pilot unwinds every allocation failure cleanly"
       "frontend pilot variants match the Java syntax model"
       "frontend pilot dispatch fails closed on untreated variants"
       "frontend pilot retires builders and rejects frees of observed state"
       "frontend pilot matches the Java frontend across artifacts"
       "frontend pilot unwinds every allocation failure cleanly")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?

refs=$out/references && mkdir -p "$refs/x" "$refs/all"
tar -xzf docs/self-hosting/m0/ownership-resources.tar.gz -C "$refs/x" ordered
tar -xzf docs/self-hosting/m0/kernel-resources.tar.gz -C "$refs/x" ordered
tar -xzf docs/self-hosting/m0/loop-cycle-reference.tar.gz -C "$refs/x" cycle-ordered
cp -R "$refs"/x/ordered/*-sample0-r0 "$refs"/x/cycle-ordered/*-sample0-r0 "$refs/all/"
python3 scripts/self-hosting/measure-m2-kernels.py resources --binary "$out/kernel-capture" --references "$refs/all" \
    --output "$out/resources" > "$ev/resources.log" 2>&1; status resources $?
python3 scripts/self-hosting/check-m2-kernel-budgets.py "$out/resources/runs.json" "$ev/kernel-compile.log" \
    "$ev/kernel-link.log" "$ev/budget-records.json" > "$ev/budget-check.log" 2>&1; status budgets $?
python3 scripts/self-hosting/measure-m2-kernels.py stack --binary "$out/kernel-capture" --output "$out/stack" \
    > "$ev/stack.log" 2>&1; status stack $?
mkdir -p "$out/inputs" && "$out/kernel-capture" inputs 2 1 observer-off "$out/inputs" > "$ev/inputs.log" 2>&1
status inputs $?; cat "$out/inputs/result.txt" >> "$ev/inputs.log"
"$out/callbacks" > "$ev/callbacks-run.log" 2>&1; status callbacks-run-42 $(( $? == 42 ? 0 : 1 ))
limit=0
while true; do
    IRONWOOD_ALLOCATION_LIMIT=$limit "$out/failure" > /dev/null 2>&1; code=$?
    [ $code -eq 43 ] && break
    [ $code -ne 42 ] && { echo "limit $limit exit $code" >> "$ev/failure-sweep.log"; break; }
    limit=$((limit + 1))
done
echo "limits 0..$((limit - 1)) exit 42 (caught OutOfMemoryError); limit $limit exit $code" >> "$ev/failure-sweep.log"
status failure-sweep $(( code == 43 ? 0 : 1 ))

iwc $(cat "$out/port-sources.txt") scripts/self-hosting/native/Frontend*.iron --unfreed=warn -d "$out/frontend-classes" \
    > "$ev/frontend-compile.log" 2>&1; status frontend-compile $?
iwc --link -cp "$out/frontend-classes" --main-class FrontendCapture --unfreed=warn -O3 -o "$out/frontend-capture" \
    > "$ev/frontend-link.log" 2>&1; status frontend-link $?
(ls compiler/src/main/ironwood/ironwood/compiler/ir/*.iron compiler/src/main/ironwood/ironwood/compiler/semantic/*.iron \
    compiler/src/main/ironwood/ironwood/compiler/port/BitRows.iron scripts/self-hosting/native/KernelCapture.iron \
    integration-tests/cases/compiler_ownership_callbacks.iron integration-tests/cases/compiler_ownership_failure.iron) \
    | sort > "$out/new-sources.txt"
python3 scripts/self-hosting/m2-frontend-differential.py units "$out/new-sources.txt" "$out/frontend-capture" \
    "$out/diff-new" > "$ev/diff-new.log" 2>&1; status diff-new-sources $?
cp "$out/diff-new/comparison.json" "$ev/diff-new.json"
cp "$out/resources/runs.json" "$ev/resources.json"
cp "$out/stack/stack.json" "$ev/stack.json"
cd "$root"
git diff --check > "$ev/diff-check.log" 2>&1; status diff-check $?
# git diff --check covers tracked files; new files get the same whitespace check here.
git ls-files -o --exclude-standard | python3 -c "
import sys
bad = 0
for path in sys.stdin.read().split():
    data = open(path, 'rb').read()
    for number, line in enumerate(data.split(b'\n'), 1):
        if line.rstrip(b' \t') != line or b'\r' in line:
            print('%s:%d: whitespace' % (path, number)); bad += 1
    if data and not data.endswith(b'\n'):
        print('%s: no final newline' % path); bad += 1
sys.exit(1 if bad else 0)
" > "$ev/new-file-whitespace.log" 2>&1; status new-file-whitespace $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
