#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join reviewed AST contracts to every exact frozen attributed use site."""
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
csv.field_size_limit(8 * 1024 * 1024)


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def selected(path):
    return path.startswith('compiler/src/main/java/ironwood/compiler/ast/')


def main():
    document = OUT / 'AST_CONTRACTS.md'
    proof = {}
    for line, row in enumerate(document.read_text().splitlines(), 1):
        if row.startswith('| API'):
            fields = row.split('|')
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proof:
                    raise ValueError('duplicate reviewed pattern ' + identifier)
                proof[identifier] = {'document_line': line, 'exact_declarations': fields[1].strip(),
                                     'reviewed_contract': fields[2].strip(),
                                     'owner_phase_first_consumer_required_fixture': fields[3].strip()}
    groups = {}
    for call in read('calls'):
        if selected(call['file']) and not call['declaring_owner'].startswith('ironwood.'):
            key = (call['declaring_owner'], call['resolved_signature'], call['kind'])
            groups.setdefault(key, []).append(call)
    result = []
    for pattern in read('dependencies-discovery'):
        key = (pattern['owner'], pattern['signature'], pattern['kind'])
        if key in groups:
            identifier = pattern['id']
            if identifier not in proof:
                raise ValueError('unreviewed AST dependency: ' + identifier + ' ' + str(key))
            result.append({'id': identifier, 'owner': key[0], 'signature': key[1], 'kind': key[2],
                           'proof': proof[identifier], 'callers': groups.pop(key)})
    if groups or {r['id'] for r in result} != set(proof):
        raise ValueError('unmatched attribution or stale review entries')
    if any(selected(r['file']) for name in ('hash-sources-discovery', 'hash-traversals-discovery')
           for r in read(name)):
        raise ValueError('new AST hash dependency requires source review')
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    files = {file: sha for file, sha in identity['input_sha256'].items() if selected(file)}
    install = Path(identity['launcher']).parent.parent
    for file, sha in files.items():
        if hashlib.sha256((install / file).read_bytes()).hexdigest() != sha:
            raise ValueError('frozen source hash mismatch: ' + file)
    report = {'schema': 1, 'revision': identity['revision'], 'scope': 'all production AST files',
              'source_sha256': files, 'file_count': len(files),
              'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'patterns': result, 'pattern_count': len(result),
              'call_count': sum(len(r['callers']) for r in result),
              'hash_sources': [], 'hash_traversals': [],
              'ordering_contract': 'source-order list traversal, first binding per text key; private map lookup only'}
    (OUT / 'ast-reviewed.json.gz').write_bytes(gzip.compress(
        (json.dumps(report, indent=2, ensure_ascii=True) + '\n').encode(), mtime=0))
    print('PASS:', report['file_count'], 'AST files,', report['pattern_count'],
          'reviewed exact patterns,', report['call_count'], 'attributed uses')


if __name__ == '__main__':
    main()
