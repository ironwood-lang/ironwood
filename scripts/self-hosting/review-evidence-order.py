#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Associate the reviewed private evidence-store proofs with every exact site."""
import csv
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
SOURCE = 'compiler/src/main/java/ironwood/compiler/semantic/RejectedFreeEvidence.java'
PROOFS = {'E1': [292, 328], 'E2': list(range(371, 380)), 'E3': list(range(411, 438)),
          'E4': list(range(458, 470)), 'E5': list(range(476, 482)), 'E6': list(range(581, 587))}


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def main():
    source_hash = json.loads((OUT / 'qualified/identity.json').read_text())['input_sha256'][SOURCE]
    if hashlib.sha256((ROOT / SOURCE).read_bytes()).hexdigest() != source_hash:
        raise ValueError('proof source changed; re-review exact operations before regenerating')
    traversals = []
    for row in read('hash-traversals-discovery'):
        if row['file'] != SOURCE:
            continue
        proofs = [name for name, lines in PROOFS.items() if int(row['line']) in lines]
        if len(proofs) != 1:
            raise ValueError('unreviewed evidence traversal: ' + str(row))
        row = dict(row)
        row.update(proof=proofs[0], classification='order-independent traversal', B='B1/B7',
                   phase='M1.1/M1.2/M1.3', first_consumer='M2 ownership/explanation slice',
                   required_fixture='saved identity/value facts; common intersection; atomic budget failure; retirement/refcounts')
        row.pop('review_status')
        traversals.append(row)
    if len(traversals) != 62 or {r['proof'] for r in traversals} != set(PROOFS):
        raise ValueError('evidence traversal selection changed; source review required')
    sources = []
    for row in read('hash-sources-discovery'):
        if row['file'] != SOURCE:
            continue
        uses = [r for r in traversals if row['id'] in r['hash_origins'].split(';')]
        row = dict(row)
        row.update(proof='E7' if not uses else ';'.join(sorted({r['proof'] for r in uses})),
                   classification='lookup/membership only' if not uses else 'order-independent traversal',
                   traversal_sites=[{'start_utf16': r['start_utf16'], 'node': r['node'], 'proof': r['proof']} for r in uses])
        sources.append(row)
    report = {'schema': 1, 'source': SOURCE, 'original_source_sha256': source_hash,
              'scope': 'private RejectedFreeEvidence store only; upstream FunctionAnalyzer ordering remains separate',
              'proof_document': 'SNAPSHOT_CONTRACTS.md',
              'proof_sha256': hashlib.sha256((OUT / 'SNAPSHOT_CONTRACTS.md').read_bytes()).hexdigest(),
              'sources': sources, 'traversals': traversals}
    (OUT / 'evidence-order-reviewed.json.gz').write_bytes(gzip.compress(
        (json.dumps(report, indent=2, ensure_ascii=True) + '\n').encode(), mtime=0))
    print('PASS:', len(sources), 'evidence-store hash origins and', len(traversals), 'exact traversals mapped to source proofs')


if __name__ == '__main__':
    main()
