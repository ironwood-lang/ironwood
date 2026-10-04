#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Pin tracker calls and its membership-only snapshot flow to reviewed source."""
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
SOURCE = 'compiler/src/main/java/ironwood/compiler/semantic/UnfreedAllocationTracker.java'
CONSUMER = 'compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java'
csv.field_size_limit(8 * 1024 * 1024)


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def main():
    document = OUT / 'UNFREED_CONTRACTS.md'
    proofs = {}
    for line, row in enumerate(document.read_text().splitlines(), 1):
        if row.startswith('| API'):
            fields = row.split('|')
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proofs: raise ValueError('duplicate proof')
                proofs[identifier] = {'line': line, 'contract': fields[2].strip(), 'owner_phase_fixture': fields[3].strip()}
    calls = [call for call in read('calls') if call['file'] == SOURCE and not call['declaring_owner'].startswith('ironwood.')]
    groups = {}
    for call in calls:
        groups.setdefault((call['declaring_owner'], call['resolved_signature'], call['kind']), []).append(call)
    patterns = []
    for pattern in read('dependencies-discovery'):
        key = (pattern['owner'], pattern['signature'], pattern['kind'])
        if key in groups:
            if pattern['id'] not in proofs: raise ValueError('missing tracker contract ' + pattern['id'])
            patterns.append({'id': pattern['id'], 'owner': key[0], 'signature': key[1], 'kind': key[2],
                             'proof': proofs[pattern['id']], 'callers': groups.pop(key)})
    if groups or set(proofs) != {pattern['id'] for pattern in patterns} or len(patterns) != 25 or len(calls) != 43:
        raise ValueError('stale or missing tracker attribution')
    origins = [row for row in read('hash-sources-discovery') if row['file'] == SOURCE]
    if {row['id'] for row in origins} != {'H0866'}: raise ValueError('tracker hash scope changed')
    traversals = []
    for row in read('hash-traversals-discovery'):
        if 'H0866' not in row['hash_origins'].split(';'): continue
        expected = {SOURCE: {74, 79, 87, 88}, CONSUMER: {12518, 12661, 14266}}
        if int(row['line']) not in expected.get(row['file'], set()): raise ValueError('new tracker traversal')
        traversals.append({**row, 'reviewed_origin': 'H0866', 'classification': 'order-independent membership copy/intersection',
                           'proof': document.name, 'owner': 'B1/B7', 'phase': 'M1.1/M1.3',
                           'other_origin_contributions': 'not proved by this record'})
    if len(traversals) != 9: raise ValueError('tracker propagation scope changed')
    captures = [row for row in read('captures') if row['file'] == SOURCE]
    if len(captures) != 3: raise ValueError('tracker callback scope changed')
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    ordered = json.loads((OUT / 'ordered/qualified-identity.json').read_text())
    hashes = {'original': {path: identity['input_sha256'][path] for path in (SOURCE, CONSUMER)},
              'ordered': {path: ordered['input_sha256'][path] for path in (SOURCE, CONSUMER)}}
    for path in (SOURCE, CONSUMER):
        for profile in (identity, ordered):
            frozen = Path(profile['launcher']).parent.parent / path
            if hashlib.sha256(frozen.read_bytes()).hexdigest() != profile['input_sha256'][path]:
                raise ValueError('frozen proof source changed')
        if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != ordered['input_sha256'][path]:
            raise ValueError('current proof source changed')
    result = {'schema': 1, 'scope': 'tracker and H0866 contribution only', 'source_sha256': hashes,
              'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'patterns': patterns, 'origins': origins, 'traversals': traversals, 'captures': captures,
              'upstream_order_gate': 'B1/B7 M3.1 before S3; caller event order remains separate'}
    (OUT / 'unfreed-reviewed.json.gz').write_bytes(gzip.compress((json.dumps(result, indent=2) + '\n').encode(), mtime=0))
    print('PASS: 25 tracker patterns, 43 calls, 1 origin, 9 contributions, 3 captured rows')


if __name__ == '__main__':
    main()
