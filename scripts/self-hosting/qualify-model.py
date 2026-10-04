#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check a finite frontend value schema against independent pinned model discovery."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'


def check(rows, treatments):
    actual = {row['name']: row for row in rows}
    expected = {row['name']: row for row in treatments}
    if len(actual) != len(rows) or len(expected) != len(treatments) or actual != expected:
        raise ValueError('untreated model variant/field or stale treatment')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists(): raise ValueError('preserve existing qualification')
    args.output.mkdir(parents=True)
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    jar = install / 'lib/ironwoodc.jar'
    if hashlib.sha256(jar.read_bytes()).hexdigest() != identity['seed_jar_sha256']:
        raise ValueError('seed hash changed')
    files = sorted(path for path in identity['input_sha256']
                   if '/ast/' in path or path.endswith(('/lexer/Token.java', '/lexer/TokenKind.java',
                      '/lexer/LexResult.java', '/parser/ParseResult.java', '/source/SourcePosition.java',
                      '/source/SourceSpan.java', '/diagnostic/Diagnostic.java', '/diagnostic/DiagnosticNote.java',
                      '/lexer/Lexer.java', '/parser/Parser.java', '/source/SourceFile.java',
                      '/lexer/DocumentationComment.java')))
    for path in files:
        for base in (ROOT, install):
            if hashlib.sha256((base / path).read_bytes()).hexdigest() != identity['input_sha256'][path]:
                raise ValueError('model source hash changed')
    names = [path.removeprefix('compiler/src/main/java/').removesuffix('.java').replace('/', '.') for path in files]
    (args.output / 'class-names.txt').write_text('\n'.join(names) + '\n')
    source = ROOT / 'scripts/self-hosting/ModelCapture.java'
    (args.output / 'model-source.java.gz').write_bytes(gzip.compress(source.read_bytes(), mtime=0))
    classes = ROOT / 'target/self-hosting-m0/model-classes'
    classes.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(name, None)
    commands = [[str(Path(identity['jdk']) / 'bin/javac'), '--release', '21', '-Xlint:all', '-Werror',
                 '-cp', str(jar), '-d', str(classes), str(source)]]
    commands += [[str(Path(identity['jdk']) / 'bin/java'), *identity['profile'], '-cp', str(classes) + ':' + str(jar),
                  'ModelCapture', str(args.output / 'class-names.txt')] for _ in range(2)]
    records, outputs = [], []
    for index, command in enumerate(commands):
        process = subprocess.run(command, cwd=ROOT, env=env, capture_output=True)
        (args.output / (str(index) + '.stdout.txt.gz')).write_bytes(gzip.compress(process.stdout, mtime=0))
        (args.output / (str(index) + '.stderr.txt')).write_bytes(process.stderr)
        records.append({'argv': command, 'returncode': process.returncode,
                        'stdout_bytes': len(process.stdout), 'stdout_sha256': hashlib.sha256(process.stdout).hexdigest(),
                        'stderr_bytes': len(process.stderr), 'stderr_sha256': hashlib.sha256(process.stderr).hexdigest()})
        if process.returncode: raise ValueError('model discovery failed')
        if index: outputs.append(process.stdout)
    if len(set(outputs)) != 1: raise ValueError('model discovery differs across JVMs')
    rows = []
    for line in outputs[0].decode().splitlines():
        kind, name, fields = line.split('\t')
        rows.append({'kind': kind, 'name': name, 'fields_or_variants': fields.split(';')})
    schema = json.loads((OUT / 'frontend-model-schema.json').read_text())
    check(rows, schema['treatments'])
    if set(schema['consumers']) != {row['name'] for row in rows} or any(not value for value in schema['consumers'].values()):
        raise ValueError('missing consumer treatment')
    controls = []
    for name, models, treatments in (
        ('removed-treatment', rows, schema['treatments'][1:]),
        ('untreated-variant', rows + [{'kind': 'record', 'name': 'UntreatedVariant', 'fields_or_variants': ['span:SourceSpan']}], schema['treatments']),
        ('removed-field', rows, [{**row, 'fields_or_variants': []} if row['kind'] == 'record' else row for row in schema['treatments']])):
        try: check(models, treatments)
        except ValueError as error: controls.append({'name': name, 'rejected': str(error)})
        else: raise ValueError('coverage control accepted')
    report = {'schema': 1, 'J0_revision': identity['revision'], 'J0_seed_sha256': identity['seed_jar_sha256'],
              'source_sha256': {path: identity['input_sha256'][path] for path in files},
              'capture_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'treatment_sha256': hashlib.sha256((OUT / 'frontend-model-schema.json').read_bytes()).hexdigest(),
              'model_count': len(rows), 'kind_counts': {kind: sum(row['kind'] == kind for row in rows) for kind in ('record', 'enum', 'sealed', 'class')},
              'commands': records, 'negative_controls': controls,
              'scope': 'finite frontend value model; native dispatch evidence remains M1/M2'}
    (args.output / 'qualification.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS:', report['model_count'], 'independent model declarations;', report['kind_counts'], '; three missing-treatment controls rejected')


if __name__ == '__main__':
    main()
