#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# M3.3 handoff run: a fresh git archive of the given commit, the strict build,
# warning-free compilation of every compiler test source, the eleven M3.3
# tests, the unchanged consumers (the numeric and IronDocs checks of the new
# Double method, the reference-bound audit over every port source, the four
# SelectiveInlining tests, the native target, shared trace order and version
# tests, and the SHA-256 and ByteView tests whose helper the TLS checks
# reuse), the M3.3 classification, and one compilation of every port source
# with both pilot adapters, which must report no diagnostics. Logs land in
# workspace/m3/m3.3-evidence.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
base=adfa0515194209c1cd4fb2766f3097ea79f51573
ev=$root/workspace/m3/m3.3-evidence
stage=$root/workspace/m3.noindex/m3.3-stage
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm@23
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS IRONWOOD_RUNTIME_HOME IRONWOOD_STDLIB_HOME IRONWOOD_VERSION
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
tests=("compiler MD5 matches Java digests and both trace GUIDs across artifacts"
       "compiler binary helpers match Java unsigned order and trace roots across artifacts"
       "compiler LLVM text matches the Java emitter across artifacts"
       "compiler LLVM scans match the Java target and trace patterns across artifacts"
       "compiler properties subset matches Java Properties across artifacts"
       "compiler header scan matches the Java sysroot patterns across artifacts"
       "compiler installation inputs match Java discovery across layouts and artifacts"
       "compiler build identity matches the embedded compiler version across artifacts"
       "compiler backend helpers borrow inputs and own their results"
       "compiler backend helpers unwind every allocation failure"
       "native runtime objects compile directly without cross-link reuse"
       "numeric standard-library helpers run at O3"
       "IronDocs comments, CLI, links, and reproducible library documentation"
       "compiler port generics spell reference bounds"
       "selective inlining bounds loop candidates and preserves fallbacks"
       "selective library inlining exposes medium loops and preserves recursive fallbacks"
       "selective inlining preserves native checks cleanup and traces"
       "inlining link controls validate budgets and preserve enum specialization"
       "native target layout agrees with configured Clang before optimization"
       "Java Bridge shared traces preserve records under deterministic root ordering"
       "version flags report the embedded compiler version"
       "compiler SHA-256 matches Java digests across artifacts"
       "ByteView declaration authority holds for J0 source class and archive contents")
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
"$JAVA_HOME/bin/java" -ea -Xss8m -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
    "${args[@]}" > "$ev/tests.log" 2>&1; status tests $?
python3 docs/self-hosting/m3/classify.py M3.3 --markdown "$ev/classification.md" > "$ev/classification.log" 2>&1
status classification $?
# Every port source with both pilot adapters, as the M3.2 capacity run compiled them.
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
cd "$root"
git diff --check "$base" "$commit" > "$ev/diff-check.log" 2>&1; status diff-check $?
./scripts/check-licenses.sh > "$ev/licenses.log" 2>&1; status licenses $?
cat "$ev/status.txt"
