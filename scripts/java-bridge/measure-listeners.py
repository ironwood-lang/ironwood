#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Measure equal result-processor/listener workloads and retain paired evidence."""
import argparse
import csv
import importlib.util
import io
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=('macos-arm64', 'linux-arm64', 'linux-x86_64'), required=True)
    parser.add_argument('--execution-scope', choices=('ARM64 hardware', 'ARM64 virtualization', 'x86-64 physical hardware'), required=True)
    parser.add_argument('--java21-prefix', type=Path)
    parser.add_argument('--jdk-root', type=Path, default=ROOT / 'workspace/java-bridge/jdks')
    parser.add_argument('--llvm-home', type=Path, required=True)
    parser.add_argument('--compiler', type=Path, default=ROOT / 'compiler/build/ironwoodc.jar')
    parser.add_argument('--revision-file', type=Path)
    parser.add_argument('--host-notes', required=True)
    parser.add_argument('--events', type=int, default=1000000)
    parser.add_argument('--warmups', type=int, default=5)
    parser.add_argument('--samples', type=int, default=7)
    parser.add_argument('--forks', type=int, default=3)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    if min(args.events, args.samples, args.forks) <= 0 or args.warmups < 0:
        parser.error('invalid measurement sizes')
    mac = args.target == 'macos-arm64'
    arches = ('aarch64', 'arm64') if args.target.endswith('arm64') else ('amd64', 'x86_64')
    if platform.system() != ('Darwin' if mac else 'Linux') or platform.machine() not in arches:
        parser.error('matching host required; translated timing is not qualification')
    if args.target.endswith('x86_64') != args.execution_scope.startswith('x86-64'):
        parser.error('scope does not match target')
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'IRONWOOD_ALLOCATION_LIMIT'):
        if os.environ.get(name): parser.error('unset ' + name)
    spec = importlib.util.spec_from_file_location('bridge_candidate', Path(__file__).with_name('check-candidate.py'))
    candidate = importlib.util.module_from_spec(spec)
    sys.dont_write_bytecode = True
    spec.loader.exec_module(candidate)
    prep = candidate.PREPARATION
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)

    def run(name, command):
        command = list(map(str, command))
        (evidence / (name + '.command.json')).write_text(json.dumps(command, indent=2) + '\n')
        result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
        (evidence / (name + '.stdout')).write_text(result.stdout)
        (evidence / (name + '.stderr')).write_text(result.stderr)
        (evidence / (name + '.exit.txt')).write_text(str(result.returncode) + '\n')
        if result.returncode: raise ValueError(name + ': ' + result.stdout + result.stderr)
        return result.stdout

    if args.revision_file:
        (evidence / 'revision.txt').write_bytes(args.revision_file.read_bytes())
    else:
        run('revision', ['git', 'rev-parse', 'HEAD'])
        run('working-diff', ['git', 'diff', '--binary'])
    run('os', ['sw_vers'] if mac else ['uname', '-a'])
    run('cpu', ['sysctl', '-n', 'machdep.cpu.brand_string'] if mac else ['lscpu'])
    jdks = {}
    for major in (21, 22, 23):
        pins = prep.PINS if major == 21 else prep.PINS.with_name('java-bridge-jdks-' + str(major) + '.json')
        prefix = args.java21_prefix if major == 21 and args.java21_prefix else args.jdk_root / ('temurin-' + str(major) + '-' + args.target)
        jdks[major] = prep.check_jdk(prefix, args.target, json.loads(pins.read_text()), pins)
        (evidence / ('jdk-' + str(major) + '.json')).write_text(json.dumps(jdks[major], indent=2) + '\n')
    example = ROOT / 'examples/java-bridge/listeners'
    api = sorted((example / 'src/main/ironwood').rglob('*.iron'))
    native_sources = sorted((example / 'src/benchmark/ironwood').rglob('*.iron'))
    java_sources = sorted((example / 'src/main/java').rglob('*.java'))
    inputs = {str(path): candidate.digest(path) for path in [*api, *native_sources, *java_sources, Path(__file__), args.compiler.resolve()]}
    compiler = [jdks[21]['java'], '-jar', args.compiler.resolve()]
    common = ['--llvm-home', args.llvm_home, '--unfreed=error', '-O3']
    jar = evidence / 'listeners.jar'
    native = evidence / 'listener-native'
    run('produce', [*compiler, '--java-bridge', '--export', 'org.ironwood.javabridge.listeners', *common,
        '--license', ROOT / 'LICENSE-MIT', '--license', ROOT / 'LICENSE-APACHE', '-o', jar, *api])
    native_classes = evidence / 'native-classes'
    run('native-compile', [*compiler, '--unfreed=error', '-d', native_classes, *api, *native_sources])
    run('native-build', [*compiler, '--link', '--main-class', 'org.ironwood.javabridge.listenernative.Benchmark',
        '-cp', native_classes, *common, '-o', native])
    classes = evidence / 'classes'
    run('javac', [jdks[21]['javac'], '--release', '21', '-Xlint:all', '-Werror', '-cp', jar, '-d', classes, *java_sources])
    with zipfile.ZipFile(jar) as archive:
        manifest = candidate.properties(archive.read('META-INF/ironwood/bridge.properties'))
        image = evidence / Path(manifest['native.resource']).name
        image.write_bytes(archive.read(manifest['native.resource']))
    if manifest['native.target'] != args.target or candidate.digest(image) != manifest['native.sha256']:
        raise ValueError('unpaired target/native payload')
    run('bridge-disassembly', [args.llvm_home / 'bin/llvm-objdump', '--disassemble', image])
    native_code = run('native-disassembly', [args.llvm_home / 'bin/llvm-objdump', '--disassemble', native])
    if 'ironwood_bridge_callback_' in native_code: raise ValueError('native-only executable acquired bridge callbacks')
    expected = {}
    mask = (1 << 64) - 1

    def signed(value):
        return value if value < 1 << 63 else value - (1 << 64)

    for sample in range(args.samples):
        value, checksum = 17 + sample, 0
        for sequence in range(args.events):
            value = ((value ^ (value >> 13)) * 2862933555777941757 + 3037000493) & mask
            checksum = ((checksum << 7) ^ (checksum >> 3) ^ value ^ sequence) & mask
        expected[sample] = signed(checksum), signed(value)
    observations = []

    def collect(name, command, scenario, jdk, fork):
        rows = list(csv.DictReader(io.StringIO(run(name, command))))
        if len(rows) != args.samples: raise ValueError('missing samples: ' + name)
        for index, row in enumerate(rows):
            sample, events, elapsed = int(row['sample']), int(row['events']), int(row['elapsed_ns'])
            if sample != index or row['scenario'] != scenario or events != args.events or elapsed <= 0:
                raise ValueError('invalid sample: ' + name)
            if (int(row['checksum']), int(row['last_value'])) != expected[sample]:
                raise ValueError('workload mismatch: ' + name)
            if scenario == 'native-native' and int(row['native_allocations']) != 0:
                raise ValueError('native listener allocated')
            if scenario != 'native-native' and int(row['java_bytes']) != 0:
                raise ValueError('warmed Java listener allocated: ' + name + ': ' + row['java_bytes'])
            observations.append(dict(row, jdk=jdk, fork=fork, ns_per_event=elapsed / events, events_per_second=1e9 * events / elapsed))

    for fork in range(args.forks):
        collect('native-native-' + str(fork), [native, args.events, args.warmups, args.samples], 'native-native', None, fork)
    for major, jdk in jdks.items():
        for scenario in ('java-java', 'native-java'):
            for fork in range(args.forks):
                with tempfile.TemporaryDirectory(prefix='ironwood-listener-measure-') as scratch:
                    collect(scenario + '-' + str(major) + '-' + str(fork), [jdk['java'], '-Djava.io.tmpdir=' + scratch,
                        '-cp', str(jar) + os.pathsep + str(classes), 'org.ironwood.javabridge.listenerconsumer.Benchmark',
                        scenario, args.events, args.warmups, args.samples], scenario, major, fork)
    summaries = []
    for scenario, major in [('native-native', None)] + [(s, j) for j in jdks for s in ('java-java', 'native-java')]:
        values = [r['ns_per_event'] for r in observations if r['scenario'] == scenario and r['jdk'] == major]
        median = statistics.median(values)
        summaries.append(dict(scenario=scenario, jdk=major, median_ns_per_event=median,
            events_per_second=1e9 / median, min_ns_per_event=min(values), max_ns_per_event=max(values)))
    for path, identity in inputs.items():
        if candidate.digest(Path(path)) != identity: raise ValueError('input changed during measurement: ' + path)
    result = dict(target=args.target, execution_scope=args.execution_scope, host_notes=args.host_notes,
        events=args.events, warmups=args.warmups, samples=args.samples, forks=args.forks, inputs=inputs,
        artifacts={'jar.sha256': candidate.digest(jar), 'native.sha256': candidate.digest(native), 'manifest': manifest},
        observations=observations, summaries=summaries,
        scope='Amortized batch latency per event, not individual-event tail latency. Native allocation counts in standalone driver; Java allocation counts in JVM drivers. Production bridge native allocation behavior is tested separately. Numerical acceptance remains maintainer review.')
    (evidence / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(summaries, indent=2))


if __name__ == '__main__':
    main()
