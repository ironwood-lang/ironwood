#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Linux arm64 part of both M4 checkpoints, run on the miami VM from a git
# archive of the recorded commit in ~/temp/ironwood-m4-arm64/tree: the twelve
# M4.1/M4.2 tests and the fifteen M4.3 tests with JDK 21 and the IDK 0.2.5
# LLVM 23 toolchain, then the glibc 2.17 symbol check of the native harness
# built against the toolchain's aarch64 sysroot. Logs land in
# ~/temp/ironwood-m4-arm64/logs; the done marker ends the run.
set -u
base=~/temp/ironwood-m4-arm64
tree=$base/tree
logs=$base/logs
export JAVA_HOME=/usr/java/java21
export IRONWOOD_LLVM_HOME=$HOME/temp/test-ironwood/ironwood-idk-0.2.5-linux-arm64/toolchain
export PATH=$JAVA_HOME/bin:$PATH LC_ALL=C.UTF-8 LANG=C.UTF-8
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS IRONWOOD_HOME TMPDIR
rm -rf "$logs" && mkdir -p "$logs"
status() { echo "$1 exit=$2" >> "$logs/status.txt"; }
{ uname -a; nproc; free -m | head -2; ldd --version | head -1; "$JAVA_HOME/bin/java" -version 2>&1;
  "$IRONWOOD_LLVM_HOME/bin/llvm-config" --version; findmnt -n -o FSTYPE --target /tmp;
  findmnt -n -o FSTYPE --target /dev/shm; } > "$logs/host.txt" 2>&1
cd "$tree"
tests=("M4.1 filesystem services lower to typed IR and runtime boundaries"
       "M4.1 filesystem services borrow inputs and own their results"
       "M4.1 filesystem services match Java 21 across artifacts and TMPDIR settings"
       "M4.1 filesystem services unwind every allocation failure without leftovers"
       "M4.1 native temporary creation survives collisions and injected failures"
       "compiler tree deletion matches the Java cleanup policies across artifacts"
       "M4.2 publication moves lower to typed IR and return the caller's target"
       "M4.2 publication moves match Java 21 on one file system across artifacts"
       "M4.2 publication moves keep their guarantees across file systems"
       "M4.2 exclusive publication admits exactly one competing process"
       "M4.2 native publication survives injected races and failures"
       "M4.2 staged publication unwinds every allocation failure without leftovers")
while IFS= read -r name; do tests+=("$name"); done < docs/self-hosting/m4/process-evidence/m4.3-tests.txt
args=(); for name in "${tests[@]}"; do args+=(--test "$name"); done
./scripts/test.sh "${args[@]}" > "$logs/tests.log" 2>&1; status linux-arm64-tests $?
# The native harness against the toolchain's glibc 2.17 sysroot.
T=$IRONWOOD_LLVM_HOME; R=$T/aarch64-conda-linux-gnu/sysroot; W=$logs/abi; mkdir -p "$W/run"
{ $T/bin/clang --sysroot=$R -std=c11 -O2 -fPIC -c runtime/src/ironwood_runtime.c -o "$W/runtime.o" &&
  $T/bin/clang --sysroot=$R -std=c11 -O2 -c integration-tests/runtime/filesystem_services.c -o "$W/harness.o" &&
  $T/bin/clang --sysroot=$R -std=c11 -O2 -c runtime/src/ironwood_case.c -o "$W/case.o" &&
  $T/bin/clang --sysroot=$R --driver-mode=g++ "$W/harness.o" "$W/case.o" -o "$W/harness" &&
  "$W/harness" "$W/run"; } > "$logs/abi-build.log" 2>&1; status abi-harness $?
nm -u "$W/runtime.o" | awk '{print $2}' | sort > "$logs/runtime-undefined.txt"
objdump -T "$W/harness" | grep -o 'GLIBC_[0-9.]*' | sort -u -V > "$logs/glibc-versions.txt"
[ -z "$(awk -F_ '{ split($2, v, "."); if (v[1] > 2 || (v[1] == 2 && v[2] > 17)) print }' "$logs/glibc-versions.txt")" ]
status glibc-baseline $?
! grep -qE '^(renameat2|getrandom)$' "$logs/runtime-undefined.txt"; status no-newer-wrappers $?
rm -rf "$W"
touch "$logs/done"
cat "$logs/status.txt"
