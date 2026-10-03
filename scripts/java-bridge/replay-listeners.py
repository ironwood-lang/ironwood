#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Replay successful P5 fixture children on the three pinned matching JVMs."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=('macos-arm64', 'linux-arm64', 'linux-x86_64'), required=True)
    parser.add_argument('--execution-scope', choices=('ARM64 hardware', 'ARM64 virtualization', 'x86-64 physical hardware'), required=True)
    parser.add_argument('--java21-prefix', type=Path)
    parser.add_argument('--jdk-root', type=Path, default=ROOT / 'workspace/java-bridge/jdks')
    parser.add_argument('--fixture', type=Path, action='append', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    arches = ('aarch64', 'arm64') if args.target.endswith('arm64') else ('amd64', 'x86_64')
    if platform.system() != ('Darwin' if args.target == 'macos-arm64' else 'Linux') or platform.machine() not in arches:
        parser.error('matching host required')
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
    fixtures = [path.resolve() for path in args.fixture]
    if len(set(fixtures)) != len(fixtures): parser.error('duplicate fixture')
    inputs, launches = {}, []
    for fixture in fixtures:
        if not fixture.is_dir(): parser.error('missing fixture: ' + str(fixture))
        for path in sorted(fixture.rglob('*')):
            if path.is_file() and path.suffix in ('.jar', '.so', '.dylib', '.class', '.properties'):
                inputs[str(path)] = candidate.digest(path)
        found = 0
        for path in sorted(fixture.rglob('*.command.txt')):
            command = path.read_text().splitlines()
            environment = {}
            if command and Path(command[0]).name == 'env':
                command = command[1:]
                while command and command[0].startswith('IRONWOOD_ALLOCATION_LIMIT='):
                    key, value = command.pop(0).split('=', 1)
                    if not value.isdigit(): raise ValueError('invalid allocation limit')
                    environment[key] = value
            if not command or Path(command[0]).name != 'java': continue
            if not (path.name.startswith('consumer') or path.name in ('classpath.command.txt', 'module.command.txt')): continue
            if '-Xcheck:jni' not in command: raise ValueError('fixture lacks checked JNI: ' + str(path))
            log = path.with_name(path.name.replace('.command.txt', '.log'))
            expected = log.read_text()
            if not re.fullmatch(r'(callback-carriers-ok:[a-z-]+|owner-callbacks-ok|owner-copy-oom-ok|callback-producer-ok|owner-producer-ok)\n', expected):
                raise ValueError('fixture does not have successful baseline output: ' + str(log))
            inputs[str(path)], inputs[str(log)] = candidate.digest(path), candidate.digest(log)
            launches.append((path, command[1:], environment, expected))
            found += 1
        if found == 0: raise ValueError('fixture has no replayable successful children: ' + str(fixture))
    records = []
    for major in (21, 22, 23):
        pins = prep.PINS if major == 21 else prep.PINS.with_name('java-bridge-jdks-' + str(major) + '.json')
        prefix = args.java21_prefix if major == 21 and args.java21_prefix else args.jdk_root / ('temurin-' + str(major) + '-' + args.target)
        jdk = prep.check_jdk(prefix, args.target, json.loads(pins.read_text()), pins)
        (evidence / ('jdk-' + str(major) + '.json')).write_text(json.dumps(jdk, indent=2) + '\n')
        for index, (path, arguments, overrides, expected) in enumerate(launches):
            name = str(major) + '-' + str(index)
            with tempfile.TemporaryDirectory(prefix='ironwood-listener-replay-') as scratch:
                command = [jdk['java'], '-Djava.io.tmpdir=' + scratch, *arguments]
                (evidence / (name + '.command.json')).write_text(json.dumps(dict(command=command, environment=overrides, source=str(path)), indent=2) + '\n')
                result = subprocess.run(command, cwd=path.parent, env=dict(os.environ, **overrides), text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
                (evidence / (name + '.log')).write_text(result.stdout)
                (evidence / (name + '.exit.txt')).write_text(str(result.returncode) + '\n')
                if result.returncode or result.stdout != expected: raise ValueError('replay failed: ' + name + ': ' + result.stdout)
                records.append(dict(jdk=major, fixture=str(path), result='pass'))
    for path, identity in inputs.items():
        if candidate.digest(Path(path)) != identity: raise ValueError('fixture changed during replay: ' + path)
    (evidence / 'result.json').write_text(json.dumps(dict(target=args.target, execution_scope=args.execution_scope,
        inputs=inputs, records=records), indent=2) + '\n')
    print('PASS: ' + str(len(records)) + ' checked-JNI fixture children across Java 21/22/23')


if __name__ == '__main__':
    main()
