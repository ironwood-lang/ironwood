#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Execute the focused P7d2 matrix using already prepared tools and JDKs.
set -euo pipefail
GENERIC_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$GENERIC_ROOT"
GENERIC_TARGET=${1:?target: macos-arm64, linux-arm64 or linux-x86_64}
GENERIC_JDK_ROOT=${2:?directory containing the pinned temurin-22/23 target directories}
GENERIC_EVIDENCE=${3:?new evidence directory}
mkdir "$GENERIC_EVIDENCE"
export GENERIC_TARGET GENERIC_JDK_ROOT GENERIC_EVIDENCE
java -version > "$GENERIC_EVIDENCE/java-version.txt" 2>&1
llvm-as --version > "$GENERIC_EVIDENCE/llvm-version.txt"
./scripts/check-licenses.sh > "$GENERIC_EVIDENCE/licenses.txt"
java -Xmx1536m -ea -cp compiler/build/classes:compiler/build/test-classes ironwood.compiler.CompilerTests \
 --test 'Java Bridge read-only generics preserve finite production and reject input loopholes' \
 --test 'Java Bridge bounded generic producer preserves erased clients and failure cleanup' \
 --test 'Java Bridge bounded generic inputs preserve construction and retention proofs' \
 > "$GENERIC_EVIDENCE/fixtures.log" 2>&1
python3 - <<'PY'
import hashlib,json,os,pathlib,subprocess,tempfile
records=[]
base=pathlib.Path('workspace/java-bridge/bounded-generics/producer')
# Only the run produced by this invocation is replayed.
run=max(base.glob('run-*'),key=lambda path:path.stat().st_mtime)
for command_file in sorted(run.glob('*/consumer*.command.txt')):
 command=command_file.read_text().splitlines()
 expected=command_file.with_name(command_file.name.replace('.command.txt','.log')).read_text()
 at=next(i for i,value in enumerate(command) if value.endswith('/bin/java'))
 jar=command[command.index('-cp')+1].split(os.pathsep)[0]
 for major in (22,23):
  home=next(pathlib.Path(os.environ['GENERIC_JDK_ROOT']).joinpath('temurin-'+str(major)+'-'+os.environ['GENERIC_TARGET']).glob('jdk-*'))
  if os.environ['GENERIC_TARGET'].startswith('macos'):home=home/'Contents/Home'
  with tempfile.TemporaryDirectory(prefix='generic-replay-') as scratch:
   args=command[:at]+[str(home/'bin/java'),'-Djava.io.tmpdir='+scratch]+command[at+1:]
   result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,universal_newlines=True,timeout=90)
  records.append({'source':str(command_file),'command':args,'exit':result.returncode,'expected':expected,'actual':result.stdout,
                  'jar_sha256':hashlib.sha256(pathlib.Path(jar).read_bytes()).hexdigest()})
  pathlib.Path(os.environ['GENERIC_EVIDENCE'],'replay.json').write_text(json.dumps(records,indent=2))
  if result.returncode or result.stdout!=expected:raise RuntimeError(str(command_file)+' Java '+str(major))
print('replays:',len(records))
PY
GENERIC_BENCH_ARGS=()
if [[ "$GENERIC_TARGET" = linux-x86_64 ]]; then GENERIC_BENCH_ARGS=(--cpu 1); fi
python3 examples/java-bridge/bounded-generics/benchmark.py "${GENERIC_BENCH_ARGS[@]}" \
 --output "$GENERIC_EVIDENCE/performance" > "$GENERIC_EVIDENCE/performance.log" 2>&1
printf '0\n' > "$GENERIC_EVIDENCE/exit.txt"
