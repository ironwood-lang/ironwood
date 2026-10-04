#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Capture a selected canonical corpus with exact-byte deduplicated storage."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
ARTIFACTS = ('tokens.json', 'lex-diagnostics.json', 'ast.json', 'parse-diagnostics.json',
             'diagnostics.json', 'typed-ir.json', 'compile-diagnostics.json',
             'final-typed-ir.json', 'status.json', 'library-inputs.json')
HEADER = '// SPDX-License-Identifier: MIT OR Apache-2.0\n\n'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    module.ROOT = ROOT
    return module


def cases(revision):
    module = load(ROOT / 'scripts/self-hosting/measure-resources.py', 'resource_corpus')
    result = module.corpus(revision)
    examples = {'TextBlocks': 'textblocks', 'ClassicSwitch': 'classicswitch',
                'ModernSwitch': 'modernswitch', 'InstanceOfPatterns': 'instanceofpatterns',
                'MultidimensionalArrays': 'multidimensionalarrays', 'DeterministicResources': 'resources'}
    for name, folder in examples.items():
        path = f'examples/{folder}/src/main/ironwood/org/ironwood/{folder}/{name}.iron'
        data = module.original(revision, path)
        result[name] = {'data': data, 'family': 'original frontend/AST example', 'accepted': True,
                        'original_path': path, 'original_sha256': sha(data)}
    positive = {
        'UnicodeSource': '// UTF-8 source uses supplementary text and a Unicode digit in an identifier. Exit zero.\r\nclass UnicodeSource {\r\n    public static int main(String[] args) {\r\n        int value١ = 1;\r\n        String text = "😀";\r\n        return value١ == 1 && text.length() == 2 ? 0 : 1;\r\n    }\r\n}\r\n',
        'NumericForms': '// Radix separators, long widening and negative zero keep exact token/IR payloads. Exit zero.\nclass NumericForms {\n    public static int main(String[] args) {\n        long wide = 0xffff_ffffL;\n        int binary = 0b10_10;\n        double negativeZero = -0.0;\n        return wide == 4294967295L && binary == 10 && negativeZero == 0.0 ? 0 : 1;\n    }\n}\n',
        'ShiftGenerics': '// Adjacent generic closers and shift tokens share punctuation. Null owns no allocation. Exit zero.\nclass ShiftBox<T> {}\nclass ShiftGenerics {\n    public static int main(String[] args) {\n        ShiftBox<ShiftBox<int>> value = null;\n        int shifted = 16 >> 1;\n        return value == null && shifted == 8 ? 0 : 1;\n    }\n}\n',
    }
    for name, text in positive.items():
        result[name] = {'data': (HEADER + text).encode('utf-8'), 'family': 'focused frontend payload/span', 'accepted': True}
    negative = {
        'BadEscape': 'class BadEscape { public static int main(String[] args) { String text = "\\q"; return 0; } }\n',
        'BadRadix': 'class BadRadix { public static int main(String[] args) { int number = 0x; return 0; } }\n',
        'UnterminatedComment': 'class UnterminatedComment { /* unfinished\n',
        'UnterminatedBlock': 'class UnterminatedBlock { public static int main(String[] args) { String text = """\n  unfinished\n',
        'TruncatedParse': 'class TruncatedParse { public static int main(String[] args) { if (true) return\n',
    }
    for name, text in negative.items():
        result[name] = {'data': (HEADER + '// Deliberately malformed source; expected compilation rejection and ordered diagnostics.\n' + text).encode(),
                        'family': 'malformed frontend recovery', 'accepted': False}
    return result


def value(out, record, name):
    return (out / 'blobs' / record['artifacts'][name]['sha256']).read_bytes()


def qualify(out):
    report = json.loads((out / 'captures.json').read_text())
    assert report['repeat'] >= 2
    for name, digest in report['tool_sha256'].items():
        assert sha((out / 'tooling' / name).read_bytes()) == digest, name
    assert sha((out / 'identity.json').read_bytes()) == report['identity_sha256']
    for name, case in report['corpus'].items():
        data = (out / 'sources' / (name + '.iron')).read_bytes()
        assert sha(data) == case['sha256']; data.decode('utf-8', errors='strict')
    expected = {(name, explain, repeat) for name in report['selected']
                for explain in (False, True) for repeat in range(report['repeat'])}
    seen = set(); hashes = {}; modes = {}; nodes = set(); tokens = set(); failures = []
    for run in report['runs']:
        key = (run['workload'], run['explain'], run['repeat'])
        assert key in expected and key not in seen; seen.add(key)
        assert run['returncode'] == 0
        for name, item in run['artifacts'].items():
            data = value(out, run, name)
            assert len(data) == item['bytes'] and sha(data) == item['sha256'], (run['label'], name)
        assert set(ARTIFACTS).issubset(run['artifacts'])
        accepted = report['corpus'][run['workload']]['accepted']
        assert json.loads(value(out, run, 'status.json')) == [accepted, accepted], run['label']
        assert ('output.ll' in run['artifacts']) == accepted
        semantic = {name: run['artifacts'][name]['sha256'] for name in (*ARTIFACTS, 'output.ll') if name in run['artifacts']}
        baseline = hashes.setdefault(key[:2], semantic)
        if baseline != semantic:
            failures.append({'kind': 'fresh repeat', 'group': list(key[:2]), 'run': run['label'],
                             'differences': {name: {'baseline_sha256': baseline.get(name), 'observed_sha256': semantic.get(name)}
                                             for name in baseline.keys() | semantic.keys() if baseline.get(name) != semantic.get(name)}})
        modes.setdefault(run['workload'], {})[run['explain']] = run
        def visit(node):
            if isinstance(node, dict):
                if 'node' in node: nodes.add(node['node'])
                for child in node.values(): visit(child)
            elif isinstance(node, list):
                for child in node: visit(child)
        visit(json.loads(value(out, run, 'ast.json')))
        for token in json.loads(value(out, run, 'tokens.json')):
            tokens.add(token['kind']['name'])
    assert seen == expected, 'missing canonical configuration/repeat'
    for name, configurations in modes.items():
        off = configurations[False]; on = configurations[True]
        for artifact in (*ARTIFACTS, 'output.ll'):
            if artifact not in ('diagnostics.json', 'compile-diagnostics.json'):
                if off['artifacts'].get(artifact) != on['artifacts'].get(artifact):
                    failures.append({'kind': 'explanation non-diagnostic artifact', 'workload': name, 'artifact': artifact})
            else:
                def primary(run):
                    return [{key: val for key, val in diagnostic.items() if key != 'notes'}
                            for diagnostic in json.loads(value(out, run, artifact))]
                if primary(off) != primary(on):
                    failures.append({'kind': 'explanation primary parity', 'workload': name, 'artifact': artifact})
    result = {'qualification_passed': not failures, 'failures': failures, 'baseline_label': report['baseline_label'],
              'qualifier_source_sha256': sha(Path(__file__).read_bytes()),
              'seed_sha256': report['seed_sha256'], 'workloads': len(report['selected']), 'runs': len(seen),
              'observed_ast_nodes': sorted(nodes), 'observed_token_kinds': sorted(tokens),
              'scope': 'selected canonical corpus only; observed variants do not imply complete rewrite coverage',
              'storage': 'every raw artifact byte preserved under SHA-256; materialization restores exact filenames'}
    (out / 'qualification.json').write_text(json.dumps(result, indent=2) + '\n')
    if failures:
        raise ValueError('canonical bytes differ; exact groups and hashes retained in qualification.json')
    print('PASS:', len(seen), 'fresh canonical captures, exact repeats and primary explanation parity')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--identity', type=Path, default=ROOT / 'docs/self-hosting/m0/ordered/qualified-identity.json')
    parser.add_argument('--baseline-label', default='J0-D247')
    parser.add_argument('--repeat', type=int, default=2)
    parser.add_argument('--all', action='store_true', help='all 31 explicitly enumerated canonical workloads, not a compiler suite')
    parser.add_argument('--workload', action='append')
    parser.add_argument('--qualify-only', action='store_true')
    parser.add_argument('--materialize', nargs=2, metavar=('LABEL', 'DESTINATION'))
    args = parser.parse_args(); out = args.output.resolve()
    if args.qualify_only:
        qualify(out); return
    if args.materialize:
        label, destination = args.materialize; target = Path(destination)
        if target.exists(): raise ValueError('materialization destination exists')
        report = json.loads((out / 'captures.json').read_text())
        run = next(run for run in report['runs'] if run['label'] == label)
        target.mkdir(parents=True)
        for name in run['artifacts']:
            (target / name).write_bytes(value(out, run, name))
        return
    if out.exists() or args.repeat < 2 or bool(args.all) == bool(args.workload):
        parser.error('new output, at least two repeats, and --all or exact --workload selections required')
    identity_data = args.identity.read_bytes(); identity = json.loads(identity_data)
    if (args.baseline_label == 'original-J0') != (identity['revision'] == '6bde84df320e9dbc32977a2b665d214ac09bc2d4'):
        raise ValueError('original/ordered identity label mismatch')
    corpus = cases(identity['revision']); selected = list(corpus) if args.all else args.workload
    if len(selected) != len(set(selected)) or any(name not in corpus for name in selected):
        raise ValueError('unknown/duplicate workload')
    install = Path(identity['launcher']).parent.parent; jar = install / 'lib/ironwoodc.jar'
    assert sha(jar.read_bytes()) == identity['seed_jar_sha256']
    assert sha((install / 'lib/ironwood-stdlib.ironjar').read_bytes()) == identity['stdlib_archive_sha256']
    out.mkdir(parents=True)
    for directory in ('sources', 'tooling', 'blobs', 'scratch'): (out / directory).mkdir()
    (out / 'identity.json').write_bytes(identity_data)
    for name, case in corpus.items():
        case['data'].decode('utf-8', errors='strict')
        (out / 'sources' / (name + '.iron')).write_bytes(case['data'])
    tooling = out / 'tooling'
    names = ('ReferenceCapture.java', 'compare.py', 'freeze-corpus.py', 'measure-resources.py')
    for name in names: (tooling / name).write_bytes((ROOT / 'scripts/self-hosting' / name).read_bytes())
    env = dict(os.environ)
    cleared = ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
    for name in cleared: env.pop(name, None)
    env['IRONWOOD_STDLIB_HOME'] = str(install)
    javac = [identity['jdk'] + '/bin/javac', '--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
             '-cp', str(jar), '-d', str(tooling), str(tooling / 'ReferenceCapture.java')]
    compiled = subprocess.run(javac, cwd=ROOT, env=env, capture_output=True)
    (tooling / 'javac.stdout.txt').write_bytes(compiled.stdout); (tooling / 'javac.stderr.txt').write_bytes(compiled.stderr)
    report = {'schema': 1, 'baseline_label': args.baseline_label, 'revision': identity['revision'],
              'seed_sha256': identity['seed_jar_sha256'], 'identity_sha256': sha(identity_data),
              'tool_sha256': {name: sha((tooling / name).read_bytes()) for name in names},
              'profile': identity['profile'], 'cleared_environment': list(cleared), 'library_home': str(install),
              'javac': {'argv': javac, 'returncode': compiled.returncode}, 'repeat': args.repeat, 'selected': selected,
              'corpus': {name: {**{key: val for key, val in case.items() if key != 'data'}, 'sha256': sha(case['data'])}
                         for name, case in corpus.items()}, 'runs': []}
    def checkpoint(): (out / 'captures.json').write_text(json.dumps(report, indent=2) + '\n')
    checkpoint()
    if compiled.returncode: raise ValueError('adapter compilation failed; output retained')
    neutral = load(tooling / 'compare.py', 'neutral_compare')
    for name in selected:
        for explain in (False, True):
            for repeat in range(args.repeat):
                label = f'{name}-explain{int(explain)}-r{repeat}'
                destination = out / 'scratch' / label; destination.mkdir()
                argv = [identity['jdk'] + '/bin/java', *identity['profile'], '-cp', str(tooling) + os.pathsep + str(jar),
                        'ironwood.compiler.ReferenceCapture', str(out / 'sources' / (name + '.iron')),
                        str(destination), 'explain-on' if explain else 'explain-off']
                process = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True)
                (destination / 'stdout.txt').write_bytes(process.stdout); (destination / 'stderr.txt').write_bytes(process.stderr)
                run = {'label': label, 'workload': name, 'explain': explain, 'repeat': repeat,
                       'argv': argv, 'returncode': process.returncode, 'artifacts': {}}
                for path in sorted(destination.iterdir()):
                    data = path.read_bytes(); digest = sha(data); blob = out / 'blobs' / digest
                    if not blob.exists(): blob.write_bytes(data)
                    run['artifacts'][path.name] = {'sha256': digest, 'bytes': len(data)}
                report['runs'].append(run); checkpoint()
                if process.returncode or neutral.compare(destination, destination):
                    raise ValueError('capture failed; raw bytes retained for ' + label)
                shutil.rmtree(destination)
                print(label, 'captured', flush=True)
    qualify(out)


if __name__ == '__main__':
    main()
