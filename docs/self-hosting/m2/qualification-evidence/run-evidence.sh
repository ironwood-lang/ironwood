#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M2.3 evidence run: fresh tree of the checkout's tracked and new files, strict
# build, warning-free test compilation, every pilot command with
# --unfreed=warn and complete logs, the missing-free and suppression
# classification, mandatory errors for the unsafe corpus sources in every
# mode, the pilots' safety controls, the final kernel adapter's parity, and
# the audits. It is also the isolated measurement record for every pilot: the
# frontend's budgeted workloads with the J0 bundle reference, the selected
# kernel configurations, and geometric frontend and kernel scales. The tree is
# staged under a .noindex directory, which Spotlight does not index, and each
# measurement group waits for a quiet host and logs the load it started under.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
ev=$root/workspace/m2/m23-evidence
stage=$root/workspace/m2.noindex/m23-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
rm -rf "$ev" "$stage" && mkdir -p "$ev" "$stage"
git -C "$root" rev-parse HEAD > "$ev/base-head.txt"
git -C "$root" ls-files -co --exclude-standard > "$ev/tree-files.txt"
(cd "$root" && tar -cf - -T "$ev/tree-files.txt") | tar -xf - -C "$stage"
(cd "$stage" && find . -type f ! -path './compiler/build/*' -print0 | sort -z | xargs -0 shasum -a 256) > "$ev/tree-sha256.txt"
cd "$stage"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
# Wait (at most ten minutes) for a one-minute load average below 3.
quiet() {
    local waited=0
    while [ "$(sysctl -n vm.loadavg | awk '{print ($2 >= 3.0)}')" = 1 ] && [ $waited -lt 600 ]; do
        sleep 10
        waited=$((waited + 10))
    done
    echo "$1: load averages $(sysctl -n vm.loadavg) after waiting ${waited} s" >> "$ev/load.log"
}

scripts/build.sh > "$ev/build.log" 2>&1; status build $?
shasum -a 256 compiler/build/ironwoodc.jar compiler/build/ironwood-stdlib.ironjar > "$ev/build-identity.txt"
rm -rf compiler/build/test-classes && mkdir -p compiler/build/test-classes
find compiler/src/test/java -name '*.java' | sort > compiler/build/test-sources.txt
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp compiler/build/classes \
    -d compiler/build/test-classes @compiler/build/test-sources.txt > "$ev/javac.log" 2>&1; status javac $?

iwc() { "$JAVA_HOME/bin/java" -Xss8m -jar compiler/build/ironwoodc.jar "$@"; }
out=$stage/workspace/m2-qualify
mkdir -p "$out"
find compiler/src/main/ironwood -name '*.iron' | sort > "$out/port-sources.txt"
# Every pilot command, compile and link, with --unfreed=warn and complete logs.
pilot() {
    local name=$1 main=$2; shift 2
    iwc $(cat "$out/port-sources.txt") "$@" --unfreed=warn -d "$out/$name-classes" > "$ev/$name-compile.log" 2>&1
    status "$name-compile" $?
    iwc --link -cp "$out/$name-classes" --main-class "$main" --unfreed=warn -O3 -o "$out/$name" \
        > "$ev/$name-link.log" 2>&1
    status "$name-link" $?
    printf '%s\n' "$@" >> "$out/pilot-sources.txt"
}
cp "$out/port-sources.txt" "$out/pilot-sources.txt"
pilot frontend FrontendCapture scripts/self-hosting/native/Frontend*.iron
pilot kernels KernelCapture scripts/self-hosting/native/KernelCapture.iron
pilot frontend-failure Main integration-tests/cases/compiler_frontend_failure.iron
pilot ownership-callbacks Main integration-tests/cases/compiler_ownership_callbacks.iron
pilot ownership-failure Main integration-tests/cases/compiler_ownership_failure.iron
shasum -a 256 "$out/frontend" "$out/kernels" "$out/frontend-failure" "$out/ownership-callbacks" \
    "$out/ownership-failure" > "$ev/binaries.txt"
logs=(); for name in frontend kernels frontend-failure ownership-callbacks ownership-failure; do
    logs+=(--log "$name-compile=$ev/$name-compile.log" --log "$name-link=$ev/$name-link.log"); done
python3 scripts/self-hosting/classify-m2-unfreed.py --tree "$stage" "${logs[@]}" --sources "$out/pilot-sources.txt" \
    --review docs/self-hosting/m2/qualification-evidence/unfreed-review.json \
    --output "$ev/unfreed-classification.json" > "$ev/unfreed-classification.log" 2>&1; status unfreed-classification $?

# Mandatory safety errors stay errors in every mode; the unsafe sources are compile-only.
unsafe=$out/unsafe && mkdir -p "$unsafe"
tar -xzf docs/self-hosting/m0/canonical-corpus.tar.gz -C "$unsafe" ordered/sources/CapturedAliasesUnsafe.iron
tar -xzf docs/self-hosting/m0/loop-cycle-reference.tar.gz -C "$unsafe" loops-ordered/sources/LoopFormsUnsafe.iron
# The corpus compiles each source under its logical name; a public type needs its own file name.
mkdir -p "$unsafe/named" && cp "$unsafe/ordered/sources/CapturedAliasesUnsafe.iron" "$unsafe/named/CapturedAliases.iron"
rejected=0
for source in "$unsafe"/named/CapturedAliases.iron "$unsafe"/loops-ordered/sources/LoopFormsUnsafe.iron; do
    for mode in off warn error; do
        iwc "$source" --unfreed=$mode -d "$unsafe/classes" > "$unsafe/log.txt" 2>&1; code=$?
        errors=$(grep -ac '^error' "$unsafe/log.txt")
        echo "$(basename "$source") --unfreed=$mode exit=$code mandatory_errors=$errors" >> "$ev/unsafe-modes.log"
        grep -a '^error' "$unsafe/log.txt" | sed 's/^/    /' >> "$ev/unsafe-modes.log"
        if [ $code -ne 0 ] && [ "$errors" -gt 0 ]; then rejected=$((rejected + 1)); fi
    done
done
status unsafe-rejected-6-of-6 $(( rejected == 6 ? 0 : 1 ))

tests=("frontend pilot retires builders and rejects frees of observed state"
       "ownership pilot retires versions and rejects frees of observed state"
       "ownership pilot counts callbacks and rejects frees of captured state"
       "ownership pilot matches the Java kernels across artifacts")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?

refs=$out/references && mkdir -p "$refs/x" "$refs/all"
tar -xzf docs/self-hosting/m0/ownership-resources.tar.gz -C "$refs/x" ordered
tar -xzf docs/self-hosting/m0/kernel-resources.tar.gz -C "$refs/x" ordered
tar -xzf docs/self-hosting/m0/loop-cycle-reference.tar.gz -C "$refs/x" cycle-ordered
cp -R "$refs"/x/ordered/*-sample0-r0 "$refs"/x/cycle-ordered/*-sample0-r0 "$refs/all/"
quiet kernel-resources
python3 scripts/self-hosting/measure-m2-kernels.py resources --binary "$out/kernels" --references "$refs/all" \
    --output "$out/resources" > "$ev/resources.log" 2>&1; status resources $?
python3 scripts/self-hosting/check-m2-kernel-budgets.py "$out/resources/runs.json" "$ev/kernels-compile.log" \
    "$ev/kernels-link.log" "$ev/budget-records.json" > "$ev/budget-check.log" 2>&1; status budgets $?
quiet kernel-scale
python3 scripts/self-hosting/measure-m2-kernels.py scale --binary "$out/kernels" --output "$out/scale" \
    > "$ev/kernel-scale.log" 2>&1; status kernel-scale $?

# The M2.1 frontend budget workloads: the 36 frozen workloads (compared again
# with their frozen J0 captures) and the whole bundle, plus J0 on the bundle.
corpus=$out/corpus && mkdir -p "$corpus"
tar -xzf docs/self-hosting/m0/canonical-corpus.tar.gz -C "$corpus" original
tar -xzf docs/self-hosting/m0/loop-cycle-reference.tar.gz -C "$corpus" loops-original
python3 scripts/self-hosting/m2-frontend-differential.py corpus "$corpus" "$out/frontend" "$out/diff-corpus" \
    > "$ev/diff-corpus.log" 2>&1; status diff-corpus $?

# The frontend bundle (SOURCE_BUNDLE.json) in one invocation, doubled up to sixteen copies.
python3 - "$out" <<'EOF'
import hashlib, json, sys
out = sys.argv[1]
bundle = json.load(open('docs/self-hosting/m2/SOURCE_BUNDLE.json'))['sources']
lines = []
for unit in bundle:
    data = open(unit['path'], 'rb').read()
    assert hashlib.sha256(data).hexdigest() == unit['sha256'], unit['path']
    lines.append('%s/%s\t%s\t%s' % (out.rsplit('/workspace/', 1)[0], unit['path'], unit['path'], unit['sha256']))
for k in (1, 2, 4, 8, 16):
    open('%s/scale-%d.txt' % (out, k), 'w').write(('\n'.join(lines) + '\n') * k)
EOF
status bundle-hashes $?
mf=$out/manifests && mkdir -p "$mf"
while IFS= read -r line; do
    name=$(printf '%s' "$line" | cut -f2); printf '%s\n' "$line" > "$mf/${name%.iron}.txt"
done < "$out/diff-corpus/manifest.txt"
cp "$out/scale-1.txt" "$mf/Bundle.txt"
cases=(); for file in "$mf"/*.txt; do cases+=(--case "$(basename "$file" .txt)=$file"); done
j0=$root/target/self-hosting-m0/J0-final2/lib/ironwoodc.jar
mkdir -p "$out/j0-classes" && "$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$j0" \
    -d "$out/j0-classes" scripts/self-hosting/ReferenceCapture.java scripts/self-hosting/FrontendReference.java
quiet frontend-j0
python3 scripts/self-hosting/measure-m2-frontend.py --kind j0 --j0-classpath "$out/j0-classes:$j0" \
    --case "Bundle=$mf/Bundle.txt" --output "$out/frontend-j0" > "$ev/frontend-j0.log" 2>&1; status frontend-j0 $?
quiet frontend-resources
python3 scripts/self-hosting/measure-m2-frontend.py --kind native --binary "$out/frontend" "${cases[@]}" \
    --output "$out/frontend-resources" > "$ev/frontend-resources.log" 2>&1; status frontend-resources $?
python3 scripts/self-hosting/check-m2-frontend-budgets.py "$out/frontend-resources/runs.json" "$ev/frontend-compile.log" \
    "$ev/frontend-link.log" "$ev/frontend-budget-records.json" > "$ev/frontend-budget-check.log" 2>&1
status frontend-budgets $?
scale=(); for k in 1 2 4 8 16; do scale+=(--case "Scale$k=$out/scale-$k.txt"); done
quiet frontend-scale
python3 scripts/self-hosting/measure-m2-frontend.py --kind native --binary "$out/frontend" "${scale[@]}" \
    --output "$out/frontend-scale" > "$ev/frontend-scale.log" 2>&1; status frontend-scale $?
cp "$out/resources/runs.json" "$ev/resources.json"
cp "$out/scale/runs.json" "$ev/kernel-scale.json"
cp "$out/frontend-scale/runs.json" "$ev/frontend-scale.json"
cp "$out/frontend-resources/runs.json" "$ev/frontend-resources.json"
cp "$out/frontend-j0/runs.json" "$ev/frontend-j0.json"
cp "$out/diff-corpus/comparison.json" "$ev/diff-corpus.json"
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
