#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join every original compiler deque storage and use to its reviewed contract."""
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


def main():
    document = OUT / 'WORKLIST_CONTRACTS.md'
    stores, proof = {}, {}
    for line, row in enumerate(document.read_text().splitlines(), 1):
        fields = row.split('|')
        if row.startswith('| Q'):
            match = re.fullmatch(r'(\w+\.java):(\d+) (\w+)', fields[2].strip())
            if not match:
                raise ValueError('bad storage contract row')
            key = (match[1], match[2], match[3])
            if key in stores:
                raise ValueError('duplicate storage review')
            stores[key] = {'id': fields[1].strip(), 'document_line': line,
                           'reviewed_contract': fields[3].strip(),
                           'owner_readiness_first_consumer_required_fixture': fields[4].strip()}
        elif row.startswith('| API'):
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proof:
                    raise ValueError('duplicate API review')
                proof[identifier] = {'document_line': line, 'exact_declarations': fields[1].strip(),
                                     'reviewed_contract': fields[2].strip(),
                                     'owner_readiness_first_consumer_required_fixture': fields[3].strip()}
    containers = [r for r in read('containers') if 'ArrayDeque' in r['type'] or 'java.util.Deque<' in r['type']]
    references = read('references')
    origins, parameters = [], []
    for storage in containers:
        key = (Path(storage['file']).name, storage['line'], storage['symbol'].rsplit('::', 1)[-1])
        uses = [r for r in references if r['symbol'] == storage['symbol']]
        if 'ArrayDeque' in storage['initializer']:
            if key not in stores:
                raise ValueError('unreviewed deque origin: ' + str(key))
            origins.append(dict(storage, proof=stores.pop(key), uses=uses))
        elif storage['file'].endswith('/FunctionAnalyzer.java') and storage['line'] == '13351' and key[2] == 'deque':
            parameters.append(dict(storage, contract='borrowed restoreDeque: clear membership, append saved head-to-tail sequence', uses=uses))
        else:
            raise ValueError('unreviewed additional deque declaration: ' + str(key))
    if stores or len(origins) != 44 or len(parameters) != 1:
        raise ValueError('stale or changed deque scope requires review')
    groups = {}
    for call in read('calls'):
        if call['declaring_owner'] in ('java.util.ArrayDeque', 'java.util.Deque'):
            key = (call['declaring_owner'], call['resolved_signature'], call['kind'])
            groups.setdefault(key, []).append(call)
    patterns = []
    for pattern in read('dependencies-discovery'):
        key = (pattern['owner'], pattern['signature'], pattern['kind'])
        if key in groups:
            if pattern['id'] not in proof:
                raise ValueError('unreviewed exact deque member: ' + str(key))
            patterns.append({'id': pattern['id'], 'owner': key[0], 'signature': key[1], 'kind': key[2],
                             'proof': proof[pattern['id']], 'callers': groups.pop(key)})
    if groups or set(proof) != {r['id'] for r in patterns}:
        raise ValueError('stale or unmatched API review')
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    files = {r['file'] for r in origins + parameters}
    install = Path(identity['launcher']).parent.parent
    for file in files:
        if hashlib.sha256((install / file).read_bytes()).hexdigest() != identity['input_sha256'][file]:
            raise ValueError('frozen source hash mismatch: ' + file)
    report = {'schema': 1, 'revision': identity['revision'],
              'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'scope': 'all deque origins and borrowed restore parameter; upstream hash input order remains separate',
              'source_sha256': {file: identity['input_sha256'][file] for file in sorted(files)},
              'origins': origins, 'origin_count': len(origins), 'borrowed_parameters': parameters,
              'reference_count': sum(len(r['uses']) for r in origins + parameters),
              'patterns': patterns, 'pattern_count': len(patterns),
              'call_count': sum(len(r['callers']) for r in patterns)}
    (OUT / 'worklists-reviewed.json.gz').write_bytes(gzip.compress(
        (json.dumps(report, indent=2, ensure_ascii=True) + '\n').encode(), mtime=0))
    print('PASS:', report['origin_count'], 'deque origins,', report['reference_count'],
          'references,', report['pattern_count'], 'exact patterns,', report['call_count'], 'uses')


if __name__ == '__main__':
    main()
