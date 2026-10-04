#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join the original effect analyzer's reviewed contracts to exact discovery sites."""
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
SOURCE = 'compiler/src/main/java/ironwood/compiler/semantic/ClosedWorldEffectAnalyzer.java'
PROOFS = {236: 'T1', 278: 'T2', 306: 'T3', 337: 'T4', 370: 'T3',
          445: 'T5', 461: 'T6', 463: 'T6'}
csv.field_size_limit(8 * 1024 * 1024)


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def main():
    document = OUT / 'EFFECT_CONTRACTS.md'
    proofs = {}
    for line, row in enumerate(document.read_text().splitlines(), 1):
        if row.startswith('| API'):
            fields = row.split('|')
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proofs:
                    raise ValueError('duplicate reviewed declaration ' + identifier)
                proofs[identifier] = {'document_line': line, 'contract': fields[2].strip(),
                                      'owner_phase_consumer_fixture': fields[3].strip()}
    groups = {}
    for call in read('calls'):
        if call['file'] == SOURCE and not call['declaring_owner'].startswith('ironwood.'):
            key = (call['declaring_owner'], call['resolved_signature'], call['kind'])
            groups.setdefault(key, []).append(call)
    patterns = []
    for pattern in read('dependencies-discovery'):
        key = (pattern['owner'], pattern['signature'], pattern['kind'])
        if key in groups:
            if pattern['id'] not in proofs:
                raise ValueError('unreviewed effect dependency ' + pattern['id'])
            patterns.append({'id': pattern['id'], 'owner': key[0], 'signature': key[1],
                             'kind': key[2], 'proof': proofs[pattern['id']], 'callers': groups.pop(key)})
    if groups or set(proofs) != {row['id'] for row in patterns}:
        raise ValueError('unmatched attribution or stale review entry')
    if len(patterns) != 63 or sum(len(row['callers']) for row in patterns) != 244:
        raise ValueError('effect scope changed; review before regeneration')
    traversals = []
    for row in read('hash-traversals-discovery'):
        if row['file'] != SOURCE:
            continue
        if int(row['line']) not in PROOFS:
            raise ValueError('unreviewed effect traversal ' + str(row))
        row = dict(row)
        row.pop('review_status')
        row.update(proof=PROOFS[int(row['line'])],
                   classification='ordered list traversal; extensional union/existential result',
                   B='B1/B2/B7', phase='M1.1/M1.2/M1.3', first_consumer='M2 effect/snapshot workload',
                   required_fixture='word-boundary parameters; target permutation; foreign effects; unwind reachability')
        traversals.append(row)
    if len(traversals) != 9:
        raise ValueError('effect traversal selection changed')
    sources = []
    for row in read('hash-sources-discovery'):
        if row['file'] != SOURCE:
            continue
        row = dict(row)
        row.update(proof='C1' if row['id'] == 'H0441' else 'C2',
                   classification='identity lookup cache' if row['id'] == 'H0441' else 'extensional observer snapshot')
        sources.append(row)
    if {row['id'] for row in sources} != {'H0441', 'H0442'}:
        raise ValueError('effect hash origins changed')
    if any(row['file'] != SOURCE and {'H0441', 'H0442'}.intersection(row['hash_origins'].split(';'))
           for row in read('hash-traversals-discovery')):
        raise ValueError('new downstream effect-cache traversal needs review')
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    consumers = [SOURCE, 'compiler/src/main/java/ironwood/compiler/ir/IrClass.java',
                 'compiler/src/main/java/ironwood/compiler/ir/IrForeignCallInstruction.java',
                 'compiler/src/main/java/ironwood/compiler/semantic/SemanticAnalyzer.java',
                 'compiler/src/main/java/ironwood/compiler/semantic/SemanticAnalysisObserver.java']
    hashes = {path: identity['input_sha256'][path] for path in consumers}
    install = Path(identity['launcher']).parent.parent
    for path, sha in hashes.items():
        for base in (ROOT, install):
            if hashlib.sha256((base / path).read_bytes()).hexdigest() != sha:
                raise ValueError('proof source changed: ' + path)
    report = {'schema': 1, 'revision': identity['revision'], 'source_sha256': hashes,
              'scope': 'ClosedWorldEffectAnalyzer only, not other summary analyzers',
              'proof_document': document.name, 'proof_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'patterns': patterns, 'sources': sources, 'traversals': traversals}
    (OUT / 'effects-reviewed.json.gz').write_bytes(gzip.compress(
        (json.dumps(report, indent=2, ensure_ascii=True) + '\n').encode(), mtime=0))
    print('PASS: 63 exact effect patterns, 244 calls, 2 hash origins, 9 traversal sites')


if __name__ == '__main__':
    main()
