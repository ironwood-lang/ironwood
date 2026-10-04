#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Resolve the bounded frontend entry points against exact original attributed calls."""
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
PREFIX = 'ironwood.compiler.'
ROOTS = ['ironwood.compiler.lexer.Lexer::Lexer(ironwood.compiler.source.SourceFile)',
         'ironwood.compiler.lexer.Lexer::lex()',
         'ironwood.compiler.parser.Parser::Parser(ironwood.compiler.source.SourceFile,java.util.List<ironwood.compiler.lexer.Token>)',
         'ironwood.compiler.parser.Parser::parse()',
         'ironwood.compiler.source.SourceFile::of(java.lang.String,java.lang.String)',
         'ironwood.compiler.source.SourceFile::lineText(int)',
         'ironwood.compiler.diagnostic.Diagnostic::hasErrors(java.util.Collection<ironwood.compiler.diagnostic.Diagnostic>)']
csv.field_size_limit(8 * 1024 * 1024)


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def check_owners(methods, model):
    owners = {name.replace('$', '.') for name in model['consumers']}
    if any(method.split('::')[0] not in owners for method in methods if not method.startswith('@field:')):
        raise ValueError('reached production method owner has no independent model treatment')


def main():
    by = collections.defaultdict(list)
    calls = read('calls')
    for row in calls: by[row['consumer']].append(row)
    seen, todo, files, selected = set(ROOTS), list(ROOTS), set(), {}
    while todo:
        consumer = todo.pop()
        for row in by[consumer]:
            key = (row['file'], row['start_utf16'], row['end_utf16'], row['kind'])
            selected[key] = row
            if row['file'] not in files:
                files.add(row['file'])
                target = '@field:' + row['file']
                by[target] = [field for field in by['<class>'] if field['file'] == row['file']]
                seen.add(target)
                todo.append(target)
            if row['declaring_owner'].startswith(PREFIX):
                target = row['declaring_owner'] + '::' + row['resolved_signature']
                if target not in seen:
                    seen.add(target)
                    todo.append(target)
    proof = {}
    reports = ('frontend-reviewed', 'ast-reviewed', 'diagnostics-reviewed')
    for name in reports:
        with gzip.open(OUT / (name + '.json.gz'), 'rt') as stream: report = json.load(stream)
        for pattern in report['patterns']:
            for row in pattern['callers']:
                key = (row['file'], row['start_utf16'], row['end_utf16'], row['kind'])
                if key in proof: raise ValueError('ambiguous proof scope')
                proof[key] = {'pattern': pattern['id'], 'report': name, 'contract': pattern['proof']}
    external = []
    for key, row in selected.items():
        if row['declaring_owner'].startswith('ironwood.'): continue
        if key not in proof: raise ValueError('unreviewed pilot member ' + str(row))
        external.append({**row, **proof[key]})
    permitted = ('/ast/', '/lexer/', '/parser/', '/source/', '/diagnostic/')
    if any(not any(package in path for package in permitted) for path in files):
        raise ValueError('unexpected frontend dependency expansion')
    if any('PatternFlow::' in method or 'DeclaredTypes::' in method or '::read(' in method
           or '::displayName(' in method or '::displayReference(' in method for method in seen):
        raise ValueError('later helper entered pilot')
    captures = [row for row in read('captures') if row['consumer'] in seen]
    if len(captures) != 6 or any(not row['file'].endswith('/Parser.java') for row in captures):
        raise ValueError('new frontend captures require review')
    syntax = [row for row in read('syntax') if row['consumer'] in seen
              or (row['consumer'] == '<class>' and row['file'] in files)]
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    model = json.loads((OUT / 'frontend-model-schema.json').read_text())
    check_owners(seen, model)
    missing_leaf = {'consumers': dict(model['consumers'])}
    del missing_leaf['consumers']['ironwood.compiler.lexer.DocumentationComment']
    try: check_owners(seen, missing_leaf)
    except ValueError as error: leaf_control = str(error)
    else: raise ValueError('missing generated leaf was accepted')
    for path in files:
        for base in (ROOT, install):
            if hashlib.sha256((base / path).read_bytes()).hexdigest() != identity['input_sha256'][path]:
                raise ValueError('pilot source changed')
    selected_rows = sorted(selected.values(), key=lambda row: (row['file'], int(row['start_utf16']), row['kind']))
    result = {'schema': 1, 'revision': identity['revision'], 'roots': ROOTS,
              'scope': 'in-memory Lexer/Parser construction; logical Path identity and all reached constructors; model includes explicitly later-only helper declarations',
              'source_sha256': {path: identity['input_sha256'][path] for path in sorted(files)},
              'methods': sorted(seen), 'calls': selected_rows, 'external_calls': external,
              'method_count': len(seen), 'call_count': len(selected), 'external_call_count': len(external),
              'external_pattern_count': len({row['pattern'] for row in external}),
              'no_attributed_calls': sorted(method for method in seen if not by[method]),
              'leaf_boundary': 'generated record accessors/constructors and pure primitive/enum leaf bodies remain explicit named methods; no attribution row does not mean omitted implementation',
              'missing_generated_leaf_control': leaf_control,
              'model_schema_sha256': hashlib.sha256((OUT / 'frontend-model-schema.json').read_bytes()).hexdigest(),
              'model_names': sorted(model['consumers']), 'captures': captures,
              'capture_contract': 'three immediate lazy Optional.orElseGet supplier sites borrow six locals; explicit presence branches preserve lazy fallback, evaluation order and no retained supplier',
              'syntax': syntax, 'syntax_counts': dict(collections.Counter(row['kind'] for row in syntax)),
              'syntax_contract': 'M1.3 explicit record value/data construction; eight named uninitialized Parser locals receive inert reference/primitive/enum initializers with original assignments/read guards; initialized nullable references with existing guards; direct loops for streams/lambdas; ordinary enum switches and instanceof patterns preserved; private varargs fixed arity; FRONTEND_PILOT.md records exact locals',
              'deferred': ['SourceFile.read and DiagnosticFormatter: B3/B7 M3.3 before S4',
                           'DeclaredType/DeclaredTypes and PatternFlow dispatch: B1/B7 M3.1 before S3',
                           'TypeName displayName/displayReference and locale/split helpers: B7 M3.1 before S3; not reached by parser construction'],
              'limits': 'static source closure, not proof of native dispatch, ownership, allocations or variant execution coverage'}
    (OUT / 'pilot-frontend-reviewed.json.gz').write_bytes(gzip.compress((json.dumps(result, indent=2) + '\n').encode(), mtime=0))
    print('PASS:', len(seen), 'named methods,', len(selected), 'calls,', len(external),
          'external calls,', result['external_pattern_count'], 'patterns; six immediate captured rows')


if __name__ == '__main__':
    main()
