#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused P7f stages using prepared compilers and JDKs, without downloads or publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
EXAMPLES = ROOT / 'examples/java-bridge'
CASES = {
    'arrays': ('arraybench', [EXAMPLES / 'arrays/ArrayOps.iron']),
    'byteviews': ('bytebench', [EXAMPLES / 'byteviews/ByteOps.iron']),
    'generics': ('genericbench', sorted((EXAMPLES / 'generics').glob('*.iron'))),
    'bounded-generics': ('boundedbench', sorted((EXAMPLES / 'bounded-generics').glob('*.iron'))),
    'listeners': ('org.ironwood.javabridge.listeners', sorted((EXAMPLES / 'listeners/src/main/ironwood').rglob('*.iron'))),
}

def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(data)
    return result.hexdigest()

def metadata(path):
    with zipfile.ZipFile(str(path)) as archive:
        text = archive.read('META-INF/ironwood/bridge.properties').decode('ascii')
    decode = lambda value: re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m[1], 16)), value)
    return {decode(k): decode(v) for line in text.splitlines() if line and not line.startswith('#')
            for k, v in [line.split('=', 1)]}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['produce', 'tests', 'assemble', 'launch', 'measure'])
    parser.add_argument('--output', required=True, type=Path, help='New evidence directory')
    parser.add_argument('--target', choices=['macos-arm64', 'linux-arm64', 'linux-x86_64'])
    parser.add_argument('--jdks', type=Path, help='Parent of pinned temurin-22/23 target installations')
    parser.add_argument('--host', action='append', type=Path, default=[], help='Three produce-stage directories for assembly; one for measurement')
    parser.add_argument('--candidate', type=Path, help='Assemble-stage directory, for launch')
    parser.add_argument('--test', action='append', help='Explicit subset when retrying failed checks')
    parser.add_argument('--cpu', type=int)
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    java_home = Path(os.environ['JAVA_HOME']).resolve()
    java = java_home / 'bin/java'
    compiler = [java, '-jar', ROOT / 'compiler/build/ironwoodc.jar']
    records = []
    def run(name, command, expected=None, timeout=900):
        command = list(map(str, command))
        (out / (name + '.command.json')).write_text(json.dumps(command, indent=2) + '\n')
        result = subprocess.run(command, cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                universal_newlines=True, timeout=timeout)
        (out / (name + '.log')).write_text(result.stdout)
        (out / (name + '.exit.txt')).write_text(str(result.returncode) + '\n')
        records.append({'name': name, 'exit': result.returncode})
        (out / 'records.json').write_text(json.dumps(records, indent=2))
        if result.returncode or expected is not None and result.stdout != expected:
            raise RuntimeError(name + ': see ' + str(out / (name + '.log')))
        return result.stdout
    run('java-version', [java, '-version'])
    run('llvm-version', ['llvm-as', '--version'])
    identity = {'compiler': digest(ROOT / 'compiler/build/ironwoodc.jar'), 'runner': digest(Path(__file__)),
                'consumer': digest(HERE / 'CombinedConsumer.java'), 'target': args.target}
    (out / 'identity.json').write_text(json.dumps(identity, indent=2))
    def jdks():
        if args.target is None or args.jdks is None:
            parser.error('this stage needs --target and --jdks')
        homes = {21: java_home}
        for major in (22, 23):
            home = next((args.jdks.resolve() / ('temurin-' + str(major) + '-' + args.target)).glob('jdk-*'))
            homes[major] = home / 'Contents/Home' if args.target.startswith('macos') else home
        for major, home in homes.items():
            text = run('jdk-' + str(major), [home / 'bin/java', '-version'])
            pin = {21: '21.0.12.1+1', 22: '22.0.2+9', 23: '23.0.2+7'}[major]
            if 'Temurin-' + pin not in text: raise RuntimeError('unmatched JDK pin')
        return homes
    def compile_consumer(candidate):
        jars = [candidate / (case + '.jar') for case in CASES] + [candidate / 'ironwood-bridge-values.jar']
        cp = os.pathsep.join(map(str, jars))
        run('javac', [java_home / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', cp,
                      '-d', out / 'classes', HERE / 'CombinedConsumer.java'])
        return jars, cp
    if args.stage == 'produce':
        inventory = {}
        for name, (export, sources) in CASES.items():
            jar = out / (name + '.jar')
            run('produce-' + name, [*compiler, '--java-bridge', '--export', export, '--unfreed=off', '-O3',
                '--license', ROOT / 'LICENSE-MIT', '--license', ROOT / 'LICENSE-APACHE', '-o', jar, *sources])
            inventory[name] = {'sha256': digest(jar), 'metadata': metadata(jar),
                               'inputs': {str(p.relative_to(ROOT)): digest(p) for p in sources}}
        (out / 'artifacts.json').write_text(json.dumps(inventory, indent=2))
        jars, cp = compile_consumer(out)
        run('consumer', [java, '-Xcheck:jni', '-cp', cp + os.pathsep + str(out / 'classes'), 'CombinedConsumer'], 'p7-combined-ok\n')
    elif args.stage == 'tests':
        homes = jdks()
        selected = json.loads((HERE / 'tests.json').read_text())['tests']
        if args.test:
            if not set(args.test).issubset(selected): parser.error('unknown focused test')
            selected = args.test
        all_replays = []
        for index, name in enumerate(selected):
            output = run('test-' + str(index), [java, '-Xmx1536m', '-ea', '-cp',
                str(ROOT / 'compiler/build/classes') + os.pathsep + str(ROOT / 'compiler/build/test-classes'),
                'ironwood.compiler.CompilerTests', '--test', name])
            if not output.endswith('PASS: 1 compiler tests\n'): raise RuntimeError('missing test completion: ' + name)
            folders = [Path(p) for p in re.findall(r'evidence: (/.+)\n', output)]
            for folder in folders:
                for command_file in sorted(folder.rglob('*.command.txt')):
                    command = command_file.read_text().splitlines()
                    positions = [i for i, value in enumerate(command) if value.endswith('/bin/java')]
                    if len(positions) != 1: continue
                    at = positions[0]
                    expected = command_file.with_name(command_file.name.replace('.command.txt', '.log')).read_text()
                    for major in (22, 23):
                        with tempfile.TemporaryDirectory(prefix='p7-replay-') as temporary:
                            argv = command[:at] + [str(homes[major] / 'bin/java'), '-Djava.io.tmpdir=' + temporary] + command[at+1:]
                            label = 'replay-' + str(len(all_replays))
                            actual = run(label, argv, expected)
                        all_replays.append({'test': name, 'original': str(command_file), 'java': major, 'label': label,
                                            'output_sha256': hashlib.sha256(actual.encode()).hexdigest()})
                        (out / 'replays.json').write_text(json.dumps(all_replays, indent=2))
        (out / 'selection.json').write_text(json.dumps(selected, indent=2))
    elif args.stage == 'assemble':
        if len(args.host) != 3: parser.error('supply exactly three --host directories')
        inventory = {}
        values = [host.resolve() / 'ironwood-bridge-values.jar' for host in args.host]
        if len({digest(p) for p in values}) != 1: raise RuntimeError('shared dependency differs between targets')
        shutil.copyfile(values[0], out / values[0].name)
        for name in CASES:
            jars = [host.resolve() / (name + '.jar') for host in args.host]
            jar = out / (name + '.jar')
            run('assemble-' + name, [*compiler, '--java-bridge-assemble', '-o', jar, *jars])
            first = digest(jar)
            run('reverse-' + name, [*compiler, '--java-bridge-assemble', '-o', jar, *reversed(jars)])
            if digest(jar) != first: raise RuntimeError('assembly order changes bytes')
            info = metadata(jar)
            if info['native.targets'] != 'linux-arm64,linux-x86_64,macos-arm64': raise RuntimeError('missing target')
            distribution = out / ('distribution-' + name)
            run('distribution-' + name, [*compiler, '--java-bridge-distribution', '--input', jar, '--group-id',
                'org.ironwood.qualification', '--artifact-id', name, '--version', '0.0.0-p7f', '-d', distribution])
            main = distribution / (name + '-0.0.0-p7f.jar')
            if digest(main) != first: raise RuntimeError('distribution changed main bytes')
            for suffix in ('-sources.jar', '-javadoc.jar'):
                with zipfile.ZipFile(str(distribution / (name + '-0.0.0-p7f' + suffix))) as archive:
                    if not archive.namelist(): raise RuntimeError('empty companion')
            with zipfile.ZipFile(str(jar)) as assembled:
                for host in jars:
                    host_info = metadata(host)
                    with zipfile.ZipFile(str(host)) as single:
                        native = host_info['native.resource']
                        if assembled.read(native) != single.read(native): raise RuntimeError('payload bytes changed')
            inventory[name] = {'sha256': first, 'metadata': info,
                               'distribution': {p.name: digest(p) for p in distribution.iterdir() if p.is_file()}}
        (out / 'artifacts.json').write_text(json.dumps(inventory, indent=2))
    elif args.stage == 'launch':
        homes = jdks()
        if args.candidate is None: parser.error('--candidate required')
        candidate = args.candidate.resolve()
        inventory = json.loads((candidate / 'artifacts.json').read_text())
        for name in CASES:
            if digest(candidate / (name + '.jar')) != inventory[name]['sha256']: raise RuntimeError('candidate changed')
        jars, cp = compile_consumer(candidate)
        modules = [metadata(p)['java.module'] for p in jars[:-1]] + ['ironwood.bridge.values']
        manifest = out / 'consumer.mf'
        class_path = 'Class-Path: ' + ' '.join(p.as_uri() for p in jars)
        # JAR manifests use physical continuation lines, including long URIs.
        lines = [class_path[:70]]
        rest = class_path[70:]
        while rest:
            lines.append(' ' + rest[:69]); rest = rest[69:]
        manifest.write_text('Manifest-Version: 1.0\nMain-Class: CombinedConsumer\n'
                            + '\n'.join(lines) + '\n\n')
        executable = out / 'consumer.jar'
        run('consumer-jar', [java_home / 'bin/jar', '--create', '--file', executable, '--manifest', manifest, '-C', out / 'classes', '.'])
        for major, home in homes.items():
            for checked in (False, True):
                for form in ('classpath', 'module', 'executable'):
                    if form == 'classpath': launch = ['-cp', cp + os.pathsep + str(out / 'classes'), 'CombinedConsumer']
                    elif form == 'module': launch = ['--module-path', cp, '--add-modules', ','.join(modules), '-cp', out / 'classes', 'CombinedConsumer']
                    else: launch = ['-jar', executable]
                    run(str(major) + '-' + form + '-' + str(checked), [home / 'bin/java', *(['-Xcheck:jni'] if checked else []), *launch], 'p7-combined-ok\n')
    elif args.stage == 'measure':
        if len(args.host) != 1: parser.error('measurement needs one qualified --host directory')
        host = args.host[0].resolve()
        inventory = json.loads((host / 'artifacts.json').read_text())
        for name in ('arrays', 'byteviews', 'generics', 'bounded-generics'):
            jar = host / (name + '.jar')
            if digest(jar) != inventory[name]['sha256']: raise RuntimeError('measurement candidate changed')
            run('measure-' + name, ['python3', EXAMPLES / name / 'benchmark.py', '--output', out / name, '--bridge-jar', jar,
                *(['--cpu', args.cpu] if args.cpu is not None else [])], timeout=1800)
    files = {str(p.relative_to(out)): digest(p) for p in out.rglob('*') if p.is_file()}
    (out / 'files.json').write_text(json.dumps(files, indent=2))
    (out / 'exit.txt').write_text('0\n')
    print('P7f ' + args.stage + ' passed: ' + str(out))

if __name__ == '__main__': main()
