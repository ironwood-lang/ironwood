#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join the real ownership-operation closure to its bounded input/source proofs."""
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
SOURCE = 'compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java'
csv.field_size_limit(8 * 1024 * 1024)


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def main():
    document = OUT / 'OWNERSHIP_PILOT.md'
    proofs = {}
    for line, row in enumerate(document.read_text().splitlines(), 1):
        if row.startswith('| API'):
            fields = row.split('|')
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proofs: raise ValueError('duplicate ownership proof')
                proofs[identifier] = {'line': line, 'contract': fields[2].strip(), 'owner_phase_fixture': fields[3].strip()}
    by = collections.defaultdict(list)
    for row in read('calls'): by[row['consumer']].append(row)
    roots = sorted(consumer for consumer in by if any(consumer.startswith(
        'ironwood.compiler.semantic.FunctionAnalyzer::' + method + '(')
        for method in ('snapshotOwnership', 'restoreOwnership', 'mergeOwnership')))
    if len(roots) != 4: raise ValueError('operation roots changed')
    seen, todo, selected = set(roots), list(roots), {}
    while todo:
        for row in by[todo.pop()]:
            selected[(row['file'], row['start_utf16'], row['end_utf16'], row['kind'])] = row
            if row['declaring_owner'].startswith('ironwood.'):
                target = row['declaring_owner'] + '::' + row['resolved_signature']
                if target not in seen: seen.add(target); todo.append(target)
    external = [row for row in selected.values() if not row['declaring_owner'].startswith('ironwood.')]
    groups = collections.defaultdict(list)
    for row in external: groups[(row['declaring_owner'], row['resolved_signature'], row['kind'])].append(row)
    patterns = []
    for row in read('dependencies-discovery'):
        key = (row['owner'], row['signature'], row['kind'])
        if key in groups:
            if row['id'] not in proofs: raise ValueError('unreviewed exact operation member ' + row['id'])
            patterns.append({'id': row['id'], 'owner': key[0], 'signature': key[1], 'kind': key[2],
                             'proof': proofs[row['id']], 'callers': groups.pop(key)})
    if groups or set(proofs) != {row['id'] for row in patterns}: raise ValueError('unmatched operation proof')
    if (len(seen), len(selected), len(external), len(patterns)) != (97, 528, 310, 67):
        raise ValueError('reviewed closure changed')
    files = {row['file'] for row in selected.values()}
    if {Path(path).name for path in files} != {'FunctionAnalyzer.java', 'RejectedFreeEvidence.java', 'UnfreedAllocationTracker.java'}:
        raise ValueError('unexpected semantic dependency expansion')
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    ordered = json.loads((OUT / 'ordered/qualified-identity.json').read_text())
    source_hashes = {}
    for profile in (identity, ordered):
        install = Path(profile['launcher']).parent.parent
        for path in files:
            if hashlib.sha256((install / path).read_bytes()).hexdigest() != profile['input_sha256'][path]:
                raise ValueError('frozen closure source changed')
        source_hashes[profile['revision']] = {path: profile['input_sha256'][path] for path in sorted(files)}
    for path in files:
        if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != ordered['input_sha256'][path]:
            raise ValueError('current closure source changed')
    traversals = [row for row in read('hash-traversals-discovery') if row['consumer'] in seen]
    captures = [row for row in read('captures') if row['consumer'] in seen]
    syntax = [row for row in read('syntax') if row['consumer'] in seen]
    result = {'schema': 1, 'roots': roots, 'methods': sorted(seen), 'source_sha256': source_hashes,
              'scope': 'selected real snapshot/restore/merge operations, not analyzer constructor/source lowering; conservative closure includes later-only nonempty-path branches',
              'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'patterns': patterns, 'calls': list(selected.values()), 'captures': captures,
              'capture_contract': 'immediate ordered loops borrowing maps/nodes/state/reason/path storage, not field-retained callbacks; retained observer fixture separate',
              'syntax': syntax, 'syntax_contract': 'M1.3 record values/presence and explicit loop callbacks; fixed selected LOCAL_NEW roles and initialized fields',
              'hash_traversals': [{**row, 'classification': 'resolved within explicitly bounded pilot; general consumer remains separately gated',
                                   'source_proof': document.name,
                                   'later_gate': 'B1/B7 M3.1 before S3 for nonempty paths, ONE_OF, competing pool conflicts and event prefixes'} for row in traversals],
              'ordered_delta': 'D247 knownArraySlots independent current-store copy; original attributed ranges remain original',
              'limits': 'no global origin classification; no native ownership, complete analyzer portability or allocation-event counts'}
    (OUT / 'pilot-ownership-reviewed.json.gz').write_bytes(gzip.compress((json.dumps(result, indent=2) + '\n').encode(), mtime=0))
    print('PASS: 97 methods, 528 calls, 67 exact patterns,', len(traversals), 'bounded traversal sites,', len(captures), 'immediate captured rows')


if __name__ == '__main__':
    main()
