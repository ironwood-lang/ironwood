#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M2.1 evidence run: fresh tree of the checkout's tracked and new files, strict
# build, warning-free test compilation, the frontend pilot built with
# --unfreed=warn, focused tests, differentials and resource runs.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
ev=$root/workspace/m2/m21-evidence
stage=$root/workspace/m2/m21-stage
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
out=$stage/workspace/m2-frontend
mkdir -p "$out"
find compiler/src/main/ironwood -name '*.iron' | sort > "$out/port-sources.txt"
ls scripts/self-hosting/native/Frontend*.iron > "$out/adapter-sources.txt"
iwc $(cat "$out/port-sources.txt" "$out/adapter-sources.txt") --unfreed=warn -d "$out/classes" > "$ev/frontend-compile.log" 2>&1; status frontend-compile $?
iwc --link -cp "$out/classes" --main-class FrontendCapture --unfreed=warn -O3 -o "$out/frontend-capture" > "$ev/frontend-link.log" 2>&1; status frontend-link $?
shasum -a 256 "$out/frontend-capture" > "$ev/frontend-binary.txt"
python3 scripts/self-hosting/generate-frontend-literals.py "$out/literals-check.iron" $(cat "$out/port-sources.txt") > /dev/null
cmp "$out/literals-check.iron" scripts/self-hosting/native/FrontendLiterals.iron > "$ev/literals-check.log" 2>&1; status literals-regenerated $?

tests=("frontend pilot variants match the Java syntax model"
       "frontend pilot dispatch fails closed on untreated variants"
       "frontend pilot retires builders and rejects frees of observed state"
       "frontend pilot matches the Java frontend across artifacts"
       "frontend pilot unwinds every allocation failure cleanly"
       "compiler value helpers keep ownership of results only"
       "compiler value helpers match Java escapes counts lists and records"
       "compiler value helpers leave no storage after allocation failure")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?

corpus=$root/workspace/m2/corpus
python3 scripts/self-hosting/m2-frontend-differential.py corpus "$corpus" "$out/frontend-capture" "$out/diff-corpus" > "$ev/diff-corpus.log" 2>&1; status diff-corpus $?
(find compiler/src/main/ironwood -name '*.iron'; python3 -c "
import json;d=json.load(open('docs/self-hosting/m1/HELPER_BUNDLE.json'))
print('\n'.join(x['path'] for x in d['library_sources_with_m1_additions']))") | sort > "$out/bundle-list.txt"
python3 scripts/self-hosting/m2-frontend-differential.py units "$out/bundle-list.txt" "$out/frontend-capture" "$out/diff-bundle" > "$ev/diff-bundle.log" 2>&1; status diff-bundle $?
git -C "$root" ls-tree -r --name-only "$(cat "$ev/base-head.txt")" | grep '\.iron$' | sort > "$out/wide-list.txt"
python3 scripts/self-hosting/m2-frontend-differential.py units "$out/wide-list.txt" "$out/frontend-capture" "$out/diff-wide" > "$ev/diff-wide.log" 2>&1; status diff-wide $?
(ls "$corpus"/loops-original/sources/*.iron; cat "$out/bundle-list.txt") > "$out/mutation-seeds.txt"
python3 scripts/self-hosting/m2-frontend-differential.py mutations "$out/mutation-seeds.txt" 3000 "$out/frontend-capture" "$out/diff-mutations" > "$ev/diff-mutations.log" 2>&1; status diff-mutations $?

cc=$out/charclass && mkdir -p "$cc"
cp "$root/docs/self-hosting/m2/frontend-evidence/CharacterClasses.iron" "$root/docs/self-hosting/m2/frontend-evidence/CharacterClassesReference.java" "$cc/"
"$JAVA_HOME/bin/javac" -d "$cc" "$cc/CharacterClassesReference.java" && "$JAVA_HOME/bin/java" -cp "$cc" CharacterClassesReference > "$cc/java.txt"
iwc "$cc/CharacterClasses.iron" --unfreed=warn -d "$cc/classes" > "$ev/charclass-compile.log" 2>&1
iwc --link -cp "$cc/classes" --main-class CharacterClasses --unfreed=warn -O3 -o "$cc/charclass" > "$ev/charclass-link.log" 2>&1
"$cc/charclass" > "$cc/native.txt"; cmp "$cc/java.txt" "$cc/native.txt" > "$ev/charclass-compare.log" 2>&1; status charclass $?
shasum -a 256 "$cc/java.txt" "$cc/native.txt" >> "$ev/charclass-compare.log"

mf=$out/manifests && mkdir -p "$mf"
python3 - "$out" <<'EOF'
import json, sys
out = sys.argv[1]
summary = json.load(open(out + '/diff-corpus/comparison.json'))
lines = open(out + '/diff-corpus/manifest.txt').read().splitlines()
for line in lines:
    name = line.split('\t')[1][:-5]
    open(out + '/manifests/%s.txt' % name, 'w').write(line + '\n')
open(out + '/manifests/Bundle.txt', 'w').write(open(out + '/diff-bundle/manifest.txt').read())
base = open(out + '/diff-bundle/manifest.txt').read()
for k in (1, 2, 4, 8, 16):
    open(out + '/scale-%d.txt' % k, 'w').write(base * k)
EOF
cases=(); for f in "$mf"/*.txt; do cases+=(--case "$(basename "$f" .txt)=$f"); done
mkdir -p "$out/j0-classes" && "$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$root/target/self-hosting-m0/J0-final2/lib/ironwoodc.jar" \
    -d "$out/j0-classes" scripts/self-hosting/ReferenceCapture.java scripts/self-hosting/FrontendReference.java
python3 scripts/self-hosting/measure-m2-frontend.py --kind j0 --j0-classpath "$out/j0-classes:$root/target/self-hosting-m0/J0-final2/lib/ironwoodc.jar" \
    --case "Bundle=$mf/Bundle.txt" --output "$out/resources-j0" > "$ev/resources-j0.log" 2>&1; status resources-j0 $?
python3 scripts/self-hosting/measure-m2-frontend.py --kind native --binary "$out/frontend-capture" "${cases[@]}" \
    --output "$out/resources-native" > "$ev/resources-native.log" 2>&1; status resources-native $?
python3 scripts/self-hosting/bisect-m2-stack.py "${cases[@]}" --output "$out/resources-stack" -- \
    "$out/frontend-capture" '{manifest}' '{output}' > "$ev/resources-stack.log" 2>&1; status resources-stack $?
scale=(); for k in 1 2 4 8 16; do scale+=(--case "Scale$k=$out/scale-$k.txt"); done
python3 scripts/self-hosting/measure-m2-frontend.py --kind native --binary "$out/frontend-capture" "${scale[@]}" \
    --output "$out/resources-scale" > "$ev/resources-scale.log" 2>&1; status resources-scale $?
cp "$out/diff-corpus/comparison.json" "$ev/diff-corpus.json"
cp "$out/diff-bundle/comparison.json" "$ev/diff-bundle.json"
cp "$out/diff-wide/comparison.json" "$ev/diff-wide.json"
cp "$out/diff-mutations/comparison.json" "$ev/diff-mutations.json"
cp "$out/diff-bundle/manifest.txt" "$ev/bundle-manifest.txt"
cp "$out/resources-j0/runs.json" "$ev/resources-j0.json"
cp "$out/resources-native/runs.json" "$ev/resources-native.json"
cp "$out/resources-stack/stack.json" "$ev/resources-stack.json"
cp "$out/resources-scale/runs.json" "$ev/resources-scale.json"
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
