#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""P7e0 only. Requires prepared compiler classes, LLVM 23 and pinned JDKs; installs nothing."""
import argparse
import hashlib
import json
import os
import pathlib
import statistics
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--target', required=True, choices=['macos-arm64', 'linux-arm64', 'linux-x86_64'])
parser.add_argument('--jdks', required=True, type=pathlib.Path)
parser.add_argument('--output', required=True, type=pathlib.Path)
parser.add_argument('--cpu', type=int)
parser.add_argument('--forks', type=int, default=3)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[3]
os.chdir(str(root))
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
source = root / 'scripts/java-bridge/ffm'
records = []

def run(name, command, expected=0, env=None, timeout=120):
    command = [str(item) for item in command]
    (out / (name + '.command.json')).write_text(json.dumps({'argv': command, 'env': env or {}}, indent=2))
    child_env = os.environ.copy()
    child_env.pop('IRONWOOD_ALLOCATION_LIMIT', None)
    child_env.update(env or {})
    result = subprocess.run(command, cwd=str(out), env=child_env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, universal_newlines=True, timeout=timeout)
    (out / (name + '.log')).write_text(result.stdout)
    records.append({'name': name, 'exit': result.returncode})
    (out / 'commands.json').write_text(json.dumps(records, indent=2))
    if expected is not None and result.returncode != expected:
        raise RuntimeError(name + ': exit ' + str(result.returncode) + '\n' + result.stdout[-6000:])
    return result

jdk21 = pathlib.Path(os.environ['JAVA_HOME']).resolve()
homes = {21: jdk21}
for version in (22, 23):
    home = next((args.jdks.resolve() / ('temurin-' + str(version) + '-' + args.target)).glob('jdk-*'))
    homes[version] = home / 'Contents/Home' if args.target.startswith('macos') else home
for version, home in homes.items():
    text = run('java-' + str(version), [home / 'bin/java', '-version']).stdout
    if ('version "' + str(version) + '.') not in text:
        raise RuntimeError('wrong JDK: ' + text)
run('llvm', ['llvm-as', '--version'])
cp = str(root / 'compiler/build/classes') + os.pathsep + str(root / 'compiler/build/test-classes')
run('builder-javac', [jdk21 / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', cp,
                      '-d', out / 'builder', source / 'Build.java'])
# Builder discovers runtime from the checkout, so its working directory must be the root.
build_command = [str(jdk21 / 'bin/java'), '-cp', cp + os.pathsep + str(out / 'builder'),
                 'ironwood.compiler.Build', str(out / 'native')]
(out / 'build.command.json').write_text(json.dumps(build_command, indent=2))
with (out / 'build.log').open('w') as log:
    result = subprocess.run(build_command, cwd=str(root), stdout=log, stderr=subprocess.STDOUT, timeout=240)
if result.returncode:
    raise RuntimeError((out / 'build.log').read_text())
generated = out / 'native/src/probe'
jni_classes = out / 'jni21'
run('javac21', [jdk21 / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-d', jni_classes,
                generated / 'JniProbe.java', source / 'Checks.java'])
ffm_classes = out / 'ffm22'
run('javac22', [homes[22] / 'bin/javac', '--release', '22', '-Xlint:all', '-Werror', '-cp', jni_classes,
                '-d', ffm_classes, generated / 'FfmProbe.java', source / 'Launch.java'])
module_classes = out / 'module22'
run('javac-module22', [homes[22] / 'bin/javac', '--release', '22', '-Xlint:all', '-Werror', '-d', module_classes,
                       source / 'module-info.java', generated / 'JniProbe.java', source / 'Checks.java',
                       generated / 'FfmProbe.java', source / 'Launch.java'])
# Constant pools and class versions establish Java 21 source/linkage isolation.
for cls in jni_classes.rglob('*.class'):
    data = cls.read_bytes()
    if data[6:8] != b'\x00\x41' or b'java/lang/foreign' in data:
        raise RuntimeError('Java 21 FFM leakage: ' + str(cls))
for cls in ffm_classes.rglob('*.class'):
    if cls.read_bytes()[6:8] != b'\x00\x42':
        raise RuntimeError('optional class is not Java 22: ' + str(cls))


def java_command(version, transport, level='O3', module=False, grant='matching', mode='verify', extra=()):
    command = [homes[version] / 'bin/java'] + list(extra)
    if transport == 'ffm' and grant != 'none':
        name = 'ironwood.ffm.experiment' if module else 'ALL-UNNAMED'
        if grant == 'wrong':
            name = 'ALL-UNNAMED' if module else 'ironwood.ffm.experiment'
        command += ['--enable-native-access=' + name]
    if module:
        command += ['--module-path', module_classes, '-m', 'ironwood.ffm.experiment/probe.' + ('Launch' if transport == 'ffm' else 'JniProbe')]
    else:
        classpath = str(jni_classes)
        if transport == 'ffm':
            classpath += os.pathsep + str(ffm_classes)
        command += ['-cp', classpath, 'probe.' + ('Launch' if transport == 'ffm' else 'JniProbe')]
    image = out / 'native' / ('probe-' + level + ('.dylib' if args.target.startswith('macos') else '.so'))
    return command + [image, mode]

policy = []
for version in (21, 22, 23):
    for level in ('O0', 'O3'):
        for transport in (('jni',) if version == 21 else ('jni', 'ffm')):
            name = str(version) + '-' + level + '-' + transport
            result = run(name + '-verify', java_command(version, transport, level, extra=['-Xcheck:jni']))
            if 'verify-ok' not in result.stdout or 'WARNING' in result.stdout:
                raise RuntimeError(name + ': validation warning/failure')
            result = run(name + '-oom', java_command(version, transport, level, mode='oom', extra=['-Xcheck:jni']),
                         env={'IRONWOOD_ALLOCATION_LIMIT': '0'})
            if 'oom-ok' not in result.stdout:
                raise RuntimeError(name + ': missing OOM evidence')
    if version == 21:
        continue
    for module in (False, True):
        for grant in ('none', 'matching', 'wrong'):
            name = str(version) + '-policy-' + ('module' if module else 'classpath') + '-' + grant
            result = run(name, java_command(version, 'ffm', module=module, grant=grant), expected=None)
            warning = 'restricted method' in result.stdout
            success = result.returncode == 0 and 'verify-ok' in result.stdout
            policy.append({'java': version, 'module': module, 'grant': grant, 'exit': result.returncode,
                           'warning': warning, 'success': success})
            # Observed pinned 22/23 behavior is asserted, never bypassed.
            if grant == 'matching' and (not success or warning):
                raise RuntimeError(name + ': matching grant failed')
            if grant == 'none' and (not success or not warning):
                raise RuntimeError(name + ': missing ungranted warning/success')
            if grant == 'wrong' and (success or 'IllegalCallerException' not in result.stdout):
                raise RuntimeError(name + ': mismatched grant was not denied')
    result = run(str(version) + '-jni-module', java_command(version, 'jni', module=True))
    if 'WARNING' in result.stdout or 'verify-ok' not in result.stdout:
        raise RuntimeError('JNI module launch failed')
(out / 'policy.json').write_text(json.dumps(policy, indent=2))

# Independent loading and bounded recursion/stack failures run only in children.
for version in (22, 23):
    for transport in ('jni', 'ffm'):
        command = java_command(version, transport)
        command[-2] = out / 'absent-image.so'
        result = run(str(version) + '-' + transport + '-missing-image', command, expected=None)
        if result.returncode == 0 or 'UnsatisfiedLinkError' not in result.stdout:
            raise RuntimeError('missing-image was not explicit')
        for stack in ('512k', '1m'):
            last = 0
            for depth in (128, 512, 2048, 8192, 32768, 131072):
                name = str(version) + '-' + transport + '-stack-' + stack + '-' + str(depth)
                command = java_command(version, transport, mode='stack', extra=[
                    '-Xss' + stack, '-XX:-CreateCoredumpOnCrash', '-XX:ErrorFile=' + str(out / (name + '-hs_err.log'))]) + [str(depth)]
                command = ['/bin/sh', '-c', 'ulimit -c 0; exec "$@"', 'ffm-stack'] + command
                result = run(name, command, expected=None)
                if result.returncode != 0:
                    if 'stack-start:' not in result.stdout or depth == 128:
                        raise RuntimeError(name + ': baseline/startup failure')
                    (out / (str(version) + '-' + transport + '-stack-' + stack + '.json')).write_text(json.dumps({
                        'last_success': last, 'first_failure': depth, 'exit': result.returncode,
                        'note': 'Diagnostic native stack exhaustion; no general recursion guarantee.'}, indent=2))
                    break
                if 'stack-ok:' + str(depth) not in result.stdout:
                    raise RuntimeError(name + ': missing stack result')
                last = depth
            else:
                raise RuntimeError('stack cap did not reach a failure; inspect before enlarging')

samples = []
for version in (22, 23):
    for fork in range(args.forks):
        # Alternate order; independent JVM forks with their own warmup.
        for transport in (('jni', 'ffm') if fork % 2 == 0 else ('ffm', 'jni')):
            name = str(version) + '-' + transport + '-bench-' + str(fork)
            command = java_command(version, transport, mode='bench')
            if args.cpu is not None:
                command = ['taskset', '-c', str(args.cpu)] + command
            result = run(name, command)
            rows = [line.split(',') for line in result.stdout.splitlines() if line.startswith('sample,')]
            if len(rows) != 7:
                raise RuntimeError(name + ': sample count')
            for row in rows:
                samples.append({'java': version, 'transport': transport, 'fork': fork,
                                'count': int(row[1]), 'checksum': int(row[2]), 'elapsed_ns': int(row[3]),
                                'native_allocations': int(row[4]), 'java_bytes': int(row[5]),
                                'ns_per_call': int(row[3]) / int(row[1])})
(out / 'samples.json').write_text(json.dumps(samples, indent=2))
summary = []
for version in (22, 23):
    for transport in ('jni', 'ffm'):
        rows = [s['ns_per_call'] for s in samples if s['java'] == version and s['transport'] == transport]
        median = statistics.median(rows)
        summary.append({'java': version, 'transport': transport, 'median_ns': median,
                        'million_calls_per_second': 1000 / median, 'min_ns': min(rows), 'max_ns': max(rows)})
(out / 'summary.json').write_text(json.dumps(summary, indent=2))
hashes = {}
for directory in (source, out):
    for path in sorted(directory.rglob('*')):
        if path.is_file() and 'support' not in path.parts:
            hashes[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
(out / 'hashes.json').write_text(json.dumps(hashes, indent=2))
(out / 'exit.txt').write_text('0\n')
print(json.dumps(summary, indent=2))
