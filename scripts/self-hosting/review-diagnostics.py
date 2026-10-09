#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Pin reviewed diagnostic value closure to exact original attributed calls."""
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
SOURCES = {'compiler/src/main/java/ironwood/compiler/diagnostic/' + name + '.java'
           for name in ('Diagnostic', 'DiagnosticNote')}
csv.field_size_limit(8 * 1024 * 1024)


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def main():
    document = OUT / 'DIAGNOSTIC_CONTRACTS.md'
    proofs = {}
    for line, row in enumerate(document.read_text().splitlines(), 1):
        if row.startswith('| API'):
            fields = row.split('|')
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proofs: raise ValueError('duplicate proof')
                proofs[identifier] = {'line': line, 'contract': fields[2].strip(), 'owner_phase_fixture': fields[3].strip()}
    calls = [call for call in read('calls') if call['file'] in SOURCES and not call['declaring_owner'].startswith('ironwood.')]
    groups = {}
    for call in calls:
        groups.setdefault((call['declaring_owner'], call['resolved_signature'], call['kind']), []).append(call)
    patterns = []
    for pattern in read('dependencies-discovery'):
        key = (pattern['owner'], pattern['signature'], pattern['kind'])
        if key in groups:
            if pattern['id'] not in proofs: raise ValueError('missing exact diagnostic contract ' + pattern['id'])
            patterns.append({'id': pattern['id'], 'owner': key[0], 'signature': key[1], 'kind': key[2],
                             'proof': proofs[pattern['id']], 'callers': groups.pop(key)})
    if groups or set(proofs) != {pattern['id'] for pattern in patterns}:
        raise ValueError('stale or missing diagnostic attribution')
    hashes = [origin for origin in read('hash-sources-discovery') if origin['file'] in SOURCES]
    captures = [capture for capture in read('captures') if capture['file'] in SOURCES]
    if hashes or captures:
        raise ValueError('new hash/captured state requires source review')
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    source_hashes = {path: identity['input_sha256'][path] for path in SOURCES}
    for path, digest in source_hashes.items():
        for root in (ROOT, Path(identity['launcher']).parent.parent):
            if hashlib.sha256((root / path).read_bytes()).hexdigest() != digest:
                raise ValueError('diagnostic source changed: ' + path)
    result = {'schema': 1, 'revision': identity['revision'], 'scope': 'Diagnostic and DiagnosticNote value closure only; formatter deferred',
              'source_sha256': source_hashes, 'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'patterns': patterns, 'pattern_count': len(patterns), 'call_count': len(calls),
              'hash_sources': hashes, 'captured_state': captures,
              'immediate_callback': 'Diagnostic::isError passed to synchronous sequential Stream.anyMatch; preserves encounter short circuit and null failure'}
    (OUT / 'diagnostics-reviewed.json.gz').write_bytes(gzip.compress((json.dumps(result, indent=2) + '\n').encode(), mtime=0))
    print('PASS:', len(patterns), 'exact diagnostic patterns and', len(calls), 'attributed calls')


if __name__ == '__main__':
    main()
