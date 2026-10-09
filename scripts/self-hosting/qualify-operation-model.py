#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Independently inventory operation-pilot models and require finite treatments."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'docs/self-hosting/m0'


def validate(rows, schema):
    names = {row['name'] for row in rows}
    assert len(names) == len(rows)
    if rows != schema['declarations'] or names != schema['consumers'].keys():
        raise ValueError('untreated operation model declaration or field')
    for name, treatment in schema['consumers'].items():
        if set(treatment) != {'role', 'boundary', 'gate'} or not all(treatment.values()):
            raise ValueError('missing finite treatment: ' + name)
        if treatment['role'] not in ('selected', 'restricted', 'later-only'):
            raise ValueError('unknown treatment: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--discover-only', action='store_true')
    args = parser.parse_args()
    out = args.output.resolve()
    if out.exists(): raise ValueError('preserve evidence')
    out.mkdir(parents=True)
    source = ROOT / 'scripts/self-hosting/ModelCapture.java'
    (out / 'ModelCapture.java.gz').write_bytes(gzip.compress(source.read_bytes(), mtime=0))
    (out / Path(__file__).name).write_bytes(Path(__file__).read_bytes())
    identities = [json.loads(path.read_text()) for path in
                  (EVIDENCE / 'qualified/identity.json', EVIDENCE / 'ordered/qualified-identity.json')]
    suffixes = ('/semantic/FunctionAnalyzer.java', '/semantic/ClosedWorldEffectAnalyzer.java',
                '/semantic/RejectedFreeEvidence.java', '/semantic/UnfreedAllocationTracker.java',
                '/semantic/SemanticAnalysisObserver.java', '/UnfreedMode.java',
                '/source/SourceFile.java', '/source/SourceSpan.java', '/source/SourcePosition.java')
    files = sorted(path for path in identities[0]['input_sha256'] if '/ir/' in path or path.endswith(suffixes))
    names = [path.removeprefix('compiler/src/main/java/').removesuffix('.java').replace('/', '.') for path in files]
    (out / 'classes.txt').write_text('\n'.join(names) + '\n')
    commands, outputs = [], []
    env = dict(os.environ)
    for variable in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(variable, None)
    source_hashes = {}
    for index, identity in enumerate(identities):
        install = Path(identity['launcher']).parent.parent
        jar = install / 'lib/ironwoodc.jar'
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == identity['seed_jar_sha256']
        source_hashes[str(index)] = {path: identity['input_sha256'][path] for path in files}
        for path in files:
            assert hashlib.sha256((install / path).read_bytes()).hexdigest() == identity['input_sha256'][path]
            assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == identities[1]['input_sha256'][path]
        classes = ROOT / 'target/self-hosting-m0/operation-model-classes' / out.name / str(index)
        classes.mkdir(parents=True, exist_ok=True)
        argv = [identity['jdk'] + '/bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', str(jar), '-d', str(classes), str(source)]
        invocations = [argv] + [[identity['jdk'] + '/bin/java', *identity['profile'], '-cp', str(classes) + ':' + str(jar), 'ModelCapture', str(out / 'classes.txt')] for _ in range(2)]
        for repeat, argv in enumerate(invocations):
            process = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True)
            label = str(index) + '-' + str(repeat)
            (out / (label + '.stdout.txt.gz')).write_bytes(gzip.compress(process.stdout, mtime=0))
            (out / (label + '.stderr.txt')).write_bytes(process.stderr)
            commands.append({'argv': argv, 'returncode': process.returncode,
                'stdout_sha256': hashlib.sha256(process.stdout).hexdigest(), 'stdout_bytes': len(process.stdout),
                'stderr_sha256': hashlib.sha256(process.stderr).hexdigest(), 'stderr_bytes': len(process.stderr)})
            assert process.returncode == 0
            if repeat: outputs.append(process.stdout)
    assert len(set(outputs)) == 1
    rows = []
    for line in outputs[0].decode().splitlines():
        kind, name, fields = line.split('\t')
        rows.append({'kind': kind, 'name': name, 'fields_or_variants': fields.split(';')})
    (out / 'declarations.json').write_text(json.dumps(rows, indent=2) + '\n')
    report = {'commands': commands, 'source_sha256': source_hashes, 'declarations': len(rows),
              'tool_sha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in (source, Path(__file__))},
              'source_files': len(files), 'cross_seed_and_repeat_bytes_agree': True,
              'scope': 'independent finite model; native dispatch and input validation remain M1/M2'}
    if not args.discover_only:
        schema = json.loads((EVIDENCE / 'operation-model-schema.json').read_text())
        validate(rows, schema)
        controls = []
        for name, changed in (
                ('missing-declaration', {**schema, 'declarations': schema['declarations'][1:]}),
                ('missing-consumer', {**schema, 'consumers': dict(list(schema['consumers'].items())[1:])}),
                ('unknown-variant', {**schema, 'declarations': schema['declarations'] + [{'name': 'UnknownVariant'}]}),
                ('missing-field', {**schema, 'declarations': [{**row, 'fields_or_variants': []} if row['kind'] == 'record' else row for row in rows]}),
                ('missing-instruction-variant', {**schema, 'declarations': [{**row, 'fields_or_variants': row['fields_or_variants'][1:]} if row['name'] == 'ironwood.compiler.ir.IrInstruction' else row for row in rows]})):
            try: validate(rows, changed)
            except ValueError as error: controls.append({'name': name, 'error': str(error)})
            else: raise ValueError('accepted missing treatment')
        report['negative_controls'] = controls
        report['schema_sha256'] = hashlib.sha256((EVIDENCE / 'operation-model-schema.json').read_bytes()).hexdigest()
        report['qualification_passed'] = True
    (out / 'qualification.json').write_text(json.dumps(report, indent=2) + '\n')
    print('DISCOVERED' if args.discover_only else 'PASS:', len(rows), 'operation model declarations')


if __name__ == '__main__':
    main()
