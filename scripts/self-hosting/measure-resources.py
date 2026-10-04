#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Freeze geometric sources and measure serial fresh J0 processes on macOS."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
HEADER = '// SPDX-License-Identifier: MIT OR Apache-2.0\n\n'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def original(revision, path):
    return subprocess.check_output(['git', 'show', revision + ':' + path], cwd=ROOT)


def corpus(revision):
    result = {}
    for name in ('BranchJoin', 'SlotOrder'):
        result[name] = {'data': (ROOT / 'scripts/self-hosting/fixtures' / (name + '.iron')).read_bytes(),
                        'family': 'original ownership reference', 'size': 1, 'accepted': name == 'BranchJoin'}
    for size in (16, 64, 256):
        name = 'Volume' + str(size)
        methods = ''.join('    static int f' + str(i) + '() { return ' + str(i) + '; }\n' for i in range(size))
        calls = ''.join('        result += f' + str(i) + '();\n' for i in range(size))
        data = HEADER + '// Geometric source-volume baseline; all methods are called. Exit zero.\nclass ' + name + ' {\n' + methods
        data += '    public static int main(String[] args) {\n        int result = 0;\n' + calls
        data += '        return result == ' + str(size * (size - 1) // 2) + ' ? 0 : 1;\n    }\n}\n'
        result[name] = {'data': data.encode(), 'family': 'source volume', 'size': size, 'accepted': True}
    for size in (16, 64, 128):
        name = 'Control' + str(size)
        lines = [HEADER.rstrip(), '', '// Independent control-flow depth; nested if scopes, no standalone blocks. Exit zero.',
                 'class ' + name + ' {', '    static int run(int value) {', '        int result = 0;']
        lines += ['        ' + '    ' * i + 'if (value == 1) {' for i in range(size)]
        lines += ['        ' + '    ' * size + 'result = 1;']
        lines += ['        ' + '    ' * i + '}' for i in reversed(range(size))]
        lines += ['        return result;', '    }', '    public static int main(String[] args) {',
                  '        return run(1) == 1 ? 0 : 1;', '    }', '}']
        result[name] = {'data': ('\n'.join(lines) + '\n').encode(), 'family': 'control-flow depth', 'size': size, 'accepted': True}
    for size in (8, 16, 32):
        name = 'Types' + str(size)
        nested = 'Object'
        for _ in range(size):
            nested = 'DepthBox<' + nested + '>'
        data = HEADER + '// Independent generic type depth. Null introduces no owned allocation. Exit zero.\n'
        data += 'class DepthBox<T> { T value; }\nclass ' + name + ' {\n'
        data += '    public static int main(String[] args) {\n        ' + nested + ' value = null;\n'
        data += '        return value == null ? 0 : 1;\n    }\n}\n'
        result[name] = {'data': data.encode(), 'family': 'generic type depth', 'size': size, 'accepted': True}
    for size in (8, 32, 128):
        name = 'Ownership' + str(size)
        data = HEADER + '// Repeated real branch snapshots retain one array alias. Clear the slot before final reclamation. Exit zero.\n'
        data += 'class SnapshotNode { int value = 1; }\nclass ' + name + ' {\n'
        data += '    static boolean branch(int value) { return value == 1; }\n'
        data += '    public static int main(String[] args) {\n        SnapshotNode node = new SnapshotNode();\n'
        data += '        SnapshotNode[] slots = new SnapshotNode[1];\n'
        for _ in range(size):
            data += '        if (branch(1)) {\n            slots[0] = node;\n        } else {\n            slots[0] = node;\n        }\n        slots[0] = null;\n'
        data += '        free node;\n        free slots;\n        return 0;\n    }\n}\n'
        result[name] = {'data': data.encode(), 'family': 'ownership snapshots', 'size': size, 'accepted': True}
    for name, path in [('GenericInference', 'examples/genericinference/src/main/ironwood/org/ironwood/genericinference/GenericInference.iron'),
                       ('CapturedAliases', 'examples/capturedaliases/src/main/ironwood/org/ironwood/capturedaliases/CapturedAliases.iron')]:
        data = original(revision, path)
        result[name] = {'data': data, 'family': 'real example', 'size': 1, 'accepted': True,
                        'original_path': path, 'original_sha256': sha(data)}
    data = result['CapturedAliases']['data']
    assert data.count(b'// free captured;') == 1
    result['CapturedAliasesUnsafe'] = {'data': data.replace(b'// free captured;', b'free captured;'),
                                      'family': 'paired unsafe retained source capture', 'size': 1, 'accepted': False,
                                      'derived_from': 'CapturedAliases', 'change': 'activate the documented rejected free'}
    return result


def unwrap(value):
    if isinstance(value, dict):
        if set(value) == {'number_kind', 'payload'}:
            return int(value['payload'])
        return {key: unwrap(item) for key, item in value.items()}
    if isinstance(value, list):
        return [unwrap(item) for item in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--identity', type=Path, default=ROOT / 'docs/self-hosting/m0/qualified/identity.json')
    parser.add_argument('--baseline-label', default='original-J0')
    parser.add_argument('--workload', action='append', help='exact workload name; may repeat')
    parser.add_argument('--all', action='store_true', help='all 17 focused resource workloads, not the compiler test suite')
    parser.add_argument('--repeat', type=int, default=2)
    args = parser.parse_args()
    if (not args.workload and not args.all) or (args.workload and args.all) or args.repeat < 1:
        parser.error('select exact --workload names or --all, with positive --repeat')
    out = args.output.resolve()
    if out.exists():
        raise ValueError('measurement destination exists; preserve prior evidence')
    identity = json.loads(args.identity.read_text())
    if identity['revision'] != '6bde84df320e9dbc32977a2b665d214ac09bc2d4' and args.baseline_label == 'original-J0':
        raise ValueError('a changed seed requires an explicit distinct baseline label')
    cases = corpus(identity['revision'])
    selected = list(cases) if args.all else args.workload
    if len(selected) != len(set(selected)) or any(name not in cases for name in selected):
        raise ValueError('unknown or duplicate workload')
    install = Path(identity['launcher']).parent.parent
    jar = install / 'lib/ironwoodc.jar'
    if sha(jar.read_bytes()) != identity['seed_jar_sha256']:
        raise ValueError('J0 seed mismatch')
    if sha((install / 'lib/ironwood-stdlib.ironjar').read_bytes()) != identity['stdlib_archive_sha256']:
        raise ValueError('frozen library archive mismatch')
    out.mkdir(parents=True)
    (out / 'sources').mkdir()
    source_manifest = {}
    for name, case in cases.items():
        case['data'].decode('utf-8', errors='strict')
        (out / 'sources' / (name + '.iron')).write_bytes(case['data'])
        source_manifest[name] = {key: value for key, value in case.items() if key != 'data'}
        source_manifest[name].update(sha256=sha(case['data']), bytes=len(case['data']))
    (out / 'corpus.json').write_text(json.dumps(source_manifest, indent=2) + '\n')
    tooling = out / 'tooling'; tooling.mkdir()
    bridge = 'compiler/src/test/java/ironwood/compiler/semantic/SemanticObserverBridge.java'
    observer = original(identity['revision'], bridge)
    (tooling / 'SemanticObserverBridge.java').write_bytes(observer)
    env = dict(os.environ)
    cleared = ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
    for name in cleared:
        env.pop(name, None)
    env['IRONWOOD_STDLIB_HOME'] = str(install)
    sources = [tooling / 'SemanticObserverBridge.java']
    for name in ('ReferenceCapture.java', 'ResourceCapture.java'):
        path = tooling / name
        path.write_bytes((ROOT / 'scripts/self-hosting' / name).read_bytes())
        sources.append(path)
    command = [identity['jdk'] + '/bin/javac', '--release', '21', '-Xlint:all', '-Werror',
               '-cp', str(jar), '-d', str(tooling), *map(str, sources)]
    process = subprocess.run(command, cwd=ROOT, env=env, capture_output=True)
    (tooling / 'javac.stdout.txt').write_bytes(process.stdout)
    (tooling / 'javac.stderr.txt').write_bytes(process.stderr)
    report = {'schema': 1, 'J0_revision': identity['revision'], 'J0_seed_sha256': identity['seed_jar_sha256'],
              'identity_sha256': sha(args.identity.read_bytes()), 'baseline_label': args.baseline_label,
              'profile': identity['profile'], 'library_home': str(install), 'cleared_environment': list(cleared),
              'hardware': identity['hardware'], 'platform': identity['platform'],
              'repeat': args.repeat, 'selected': selected,
              'observer_source': bridge, 'observer_source_sha256': sha(observer),
              'adapter_source_sha256': {path.name: sha(path.read_bytes()) for path in sources},
              'javac': {'argv': command, 'returncode': process.returncode}, 'runs': []}
    (out / 'measurement.json').write_text(json.dumps(report, indent=2) + '\n')
    if process.returncode:
        raise ValueError('resource adapter compilation failed; retained diagnostics')
    configurations = [('frontend', sample, False, False) for sample in (False, True)]
    configurations += [('complete', sample, False, False) for sample in (False, True)]
    for name in selected:
        configs = list(configurations)
        if name in ('BranchJoin', 'SlotOrder', 'Ownership128', 'CapturedAliasesUnsafe'):
            configs += [('complete', sample, True, True) for sample in (False, True)]
        for stage, sample, observe, explain in configs:
            for repetition in range(args.repeat):
                label = name + '-' + stage + '-' + str(int(sample)) + str(int(observe)) + str(int(explain)) + '-' + str(repetition)
                destination = out / 'runs' / label
                destination.mkdir(parents=True)
                cmd = ['/usr/bin/time', '-l', identity['jdk'] + '/bin/java', *identity['profile'],
                       '-cp', str(tooling) + ':' + str(jar), 'ironwood.compiler.ResourceCapture',
                       str(out / 'sources' / (name + '.iron')), str(destination), stage,
                       'sample-on' if sample else 'sample-off', 'observer-on' if observe else 'observer-off',
                       'explain-on' if explain else 'explain-off']
                started = time.monotonic()
                process = subprocess.run(cmd, cwd=ROOT, env=env, capture_output=True)
                wall = time.monotonic() - started
                (destination / 'stdout.txt').write_bytes(process.stdout)
                (destination / 'stderr.txt').write_bytes(process.stderr)
                raw = process.stderr.decode(errors='replace')
                rss = re.search(r'^\s*(\d+)\s+maximum resident set size\s*$', raw, re.MULTILINE)
                timing = re.search(r'(\d+(?:\.\d+)?) real\s+(\d+(?:\.\d+)?) user\s+(\d+(?:\.\d+)?) sys', raw)
                record = {'label': label, 'workload': name, 'argv': cmd, 'returncode': process.returncode,
                          'fresh_process_wall_seconds': wall, 'rss_bytes': int(rss.group(1)) if rss else None,
                          'time_real_user_sys_seconds': list(map(float, timing.groups())) if timing else None}
                resource = destination / 'resource.json'
                if resource.exists(): record['metrics'] = unwrap(json.loads(resource.read_text()))
                observer_file = destination / 'observer.json'
                if observer_file.exists(): record['observer'] = unwrap(json.loads(observer_file.read_text()))
                record['artifact_sha256'] = {path.name: sha(path.read_bytes()) for path in sorted(destination.iterdir()) if path.is_file()}
                report['runs'].append(record)
                (out / 'measurement.json').write_text(json.dumps(report, indent=2) + '\n')
                print(label, 'exit=' + str(process.returncode), 'wall=' + format(wall, '.3f'), 'rss=' + str(record['rss_bytes']), flush=True)
                if process.returncode or rss is None or timing is None or 'metrics' not in record:
                    raise ValueError('measurement failure; exact command and logs retained: ' + label)
                expected = True if stage == 'frontend' else cases[name]['accepted']
                if record['metrics']['successful'] != expected:
                    raise ValueError('unexpected source outcome; inspect retained diagnostics: ' + label)
    print('PASS:', len(report['runs']), 'fresh serial resource processes; budgets not yet selected')


if __name__ == '__main__':
    main()
