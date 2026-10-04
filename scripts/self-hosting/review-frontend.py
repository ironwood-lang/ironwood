#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join source-reviewed frontend contracts to exact attributed use sites."""
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
    return any('/' + package + '/' in path for package in ('lexer', 'parser', 'source'))


def main():
    document = OUT / 'FRONTEND_CONTRACTS.md'
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
    dependencies = read('dependencies-discovery')
    calls = read('calls')
    groups = {}
    for call in calls:
        if selected(call['file']) and not call['declaring_owner'].startswith('ironwood.'):
            key = (call['declaring_owner'], call['resolved_signature'], call['kind'])
            groups.setdefault(key, []).append(call)
    result = []
    for pattern in dependencies:
        key = (pattern['owner'], pattern['signature'], pattern['kind'])
        if key in groups:
            identifier = pattern['id']
            if identifier not in proof:
                raise ValueError('unreviewed frontend dependency: ' + identifier + ' ' + str(key))
            result.append({'id': identifier, 'owner': key[0], 'signature': key[1], 'kind': key[2],
                           'proof': proof[identifier], 'callers': groups.pop(key)})
    if groups or {r['id'] for r in result} != set(proof):
        raise ValueError('unmatched attribution or stale review entries')
    hash_sources = [r for r in read('hash-sources-discovery') if selected(r['file'])]
    if len(hash_sources) != 1 or not hash_sources[0]['file'].endswith('/Lexer.java') or 'ofEntries' not in hash_sources[0]['signature']:
        raise ValueError('frontend hash origin changed; re-review required')
    seed = hash_sources[0]
    if any(seed['id'] in r['hash_origins'].split(';') for r in read('hash-traversals-discovery')):
        raise ValueError('keyword order escapes lookup role; source review required')
    references = [r for r in read('references') if r['symbol'].endswith('Lexer::KEYWORDS')]
    if len(references) != 1 or references[0]['expression'] != 'KEYWORDS.getOrDefault':
        raise ValueError('keyword consumer changed; source review required')
    seed = dict(seed, classification='lookup/membership only', equality='String content equality/hash; non-null distinct literal keys and enum values',
                insertion_source='49 source-declared Map.entry constants; immutable Map.ofEntries',
                first_order_sensitive_operation='none: one private terminal getOrDefault read, no view/copy/return/helper escape',
                replacement='B7 M1.3 private keyword switch/static lookup; no general Map factory',
                proof=proof['API0487'], uses=references)
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    report = {'schema': 1, 'revision': identity['revision'],
              'scope': 'lexer/parser/source packages only; reached AST closure and other packages require separate review',
              'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'patterns': result, 'pattern_count': len(result),
              'hash_sources': [seed],
              'call_count': sum(len(r['callers']) for r in result)}
    for file in {c['file'] for r in result for c in r['callers']}:
        frozen = Path(identity['launcher']).parent.parent / file
        if hashlib.sha256(frozen.read_bytes()).hexdigest() != identity['input_sha256'][file]:
            raise ValueError('frozen source hash mismatch: ' + file)
    (OUT / 'frontend-reviewed.json.gz').write_bytes(gzip.compress(
        (json.dumps(report, indent=2, ensure_ascii=True) + '\n').encode(), mtime=0))
    print('PASS:', report['pattern_count'], 'reviewed exact patterns,', report['call_count'], 'attributed frontend uses')


if __name__ == '__main__':
    main()
