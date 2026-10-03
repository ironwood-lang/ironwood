#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Measure retained-root updates using the unchanged assembled P6a roots jar."""
import argparse
import importlib.util
import json
from pathlib import Path
import platform
import re
import statistics
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--target', choices=('macos-arm64', 'linux-arm64', 'linux-x86_64'), required=True)
    parser.add_argument('--execution-scope', choices=('ARM64 hardware', 'ARM64 virtualization', 'x86-64 physical hardware'), required=True)
    parser.add_argument('--java21-prefix', type=Path)
    parser.add_argument('--jdk-root', type=Path)
    parser.add_argument('--host-notes', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args(); root = args.repository.resolve(); evidence = args.evidence.resolve()
    arches = ('aarch64', 'arm64') if args.target.endswith('arm64') else ('amd64', 'x86_64')
    if platform.system() != ('Darwin' if args.target == 'macos-arm64' else 'Linux') or platform.machine() not in arches:
        parser.error('matching host required')
    if args.target.endswith('x86_64') != args.execution_scope.startswith('x86-64'): parser.error('scope does not match target')
    spec = importlib.util.spec_from_file_location('bridge_candidate', root / 'scripts/java-bridge/check-candidate.py')
    candidate_tools = importlib.util.module_from_spec(spec); sys.dont_write_bytecode = True; spec.loader.exec_module(candidate_tools)
    prep = candidate_tools.PREPARATION; digest = candidate_tools.digest
    evidence.mkdir(parents=True, exist_ok=False)
    candidate = args.candidate.resolve()
    original = json.loads((candidate / 'roots-O3/evidence.json').read_text())['assembled']
    jar = candidate / 'roots-O3/combined' / original['manifest']['artifact']
    if digest(jar) != original['jar.sha256']: raise ValueError('candidate jar changed')
    source = Path(__file__).with_name('RetentionConsumer.java').resolve()
    jdk_root = args.jdk_root or root / 'workspace/java-bridge/jdks'
    jdks = {}
    for major in (21, 22, 23):
        pins = prep.PINS if major == 21 else prep.PINS.with_name(f'java-bridge-jdks-{major}.json')
        prefix = args.java21_prefix if major == 21 and args.java21_prefix else jdk_root / f'temurin-{major}-{args.target}'
        jdks[major] = prep.check_jdk(prefix, args.target, json.loads(pins.read_text()), pins)
        (evidence / f'jdk-{major}.json').write_text(json.dumps(jdks[major], indent=2) + '\n')

    def run(name, command):
        command = list(map(str, command)); (evidence / (name + '.command.json')).write_text(json.dumps(command, indent=2) + '\n')
        result = subprocess.run(command, cwd=root, text=True, capture_output=True, timeout=180)
        (evidence / (name + '.stdout')).write_text(result.stdout); (evidence / (name + '.stderr')).write_text(result.stderr)
        (evidence / (name + '.exit.txt')).write_text(str(result.returncode) + '\n')
        if result.returncode or result.stderr: raise ValueError(f'{name}: {result.returncode}: {result.stdout}{result.stderr}')
        return result.stdout

    classes = evidence / 'classes'
    run('javac', [jdks[21]['javac'], '--release', '21', '-Xlint:all', '-Werror', '-cp', jar, '-d', classes, source])
    classpath = str(jar) + ':' + str(classes)
    records = []
    for major in (21, 22, 23):
        for fork in range(3):
            text = run(f'java{major}-{fork}', [jdks[major]['java'], '-cp', classpath, 'RetentionConsumer'])
            if not text.endswith('retention-lifetime-ok\n'): raise ValueError('missing lifetime verification')
            count = int(re.search(r'retention-calls=(\d+)', text)[1])
            duration = json.loads(re.search(r'duration-ns=(\[[^\n]+\])', text)[1])
            allocation = json.loads(re.search(r'java-bytes=(\[[^\n]+\])', text)[1])
            if count != 200000 or len(duration) != 7 or len(allocation) != 7: raise ValueError('incomplete observations')
            if any(n < 0 for n in allocation) or any(n <= 0 for n in duration): raise ValueError('invalid counters')
            records.append({'jdk': major, 'fork': fork, 'calls': count, 'duration_ns': duration, 'java_bytes': allocation})
        run(f'java{major}-jit', [jdks[major]['java'], '-XX:+UnlockDiagnosticVMOptions', '-XX:+PrintCompilation', '-XX:+PrintInlining', '-cp', classpath, 'RetentionConsumer'])
    summaries = {}
    for major in (21, 22, 23):
        ns = [d / r['calls'] for r in records if r['jdk'] == major for d in r['duration_ns']]
        allocated = [n / r['calls'] for r in records if r['jdk'] == major for n in r['java_bytes']]
        summaries[major] = {'ns_per_call': {'median': statistics.median(ns), 'min': min(ns), 'max': max(ns)},
                            'java_bytes_per_call': {'median': statistics.median(allocated), 'min': min(allocated), 'max': max(allocated)}}
    inputs = [jar, source, Path(__file__), classes / 'RetentionConsumer.class']
    result = {'target': args.target, 'execution_scope': args.execution_scope, 'host_notes': args.host_notes,
              'inputs': {str(p.resolve()): digest(p) for p in inputs}, 'candidate_manifest': original['manifest'],
              'records': records, 'summary': summaries, 'numerical_acceptance': 'pending maintainer review',
              'method': 'three fresh JVM forks per JDK, one million warmup calls, seven observations of 200000 alternating root-retention updates; unchecked JNI; native state and retained-root free refusal checked outside timing; Java allocation recorded; native allocation is covered separately by identified counter fixtures'}
    (evidence / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('Collected 63 retained-root observations against the unchanged candidate; numerical acceptance pending.')


if __name__ == '__main__': main()
