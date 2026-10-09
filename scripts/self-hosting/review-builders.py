#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join builder contracts to exact calls, typed operands and consumer gates."""
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
csv.field_size_limit(8 * 1024 * 1024)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    document = OUT / 'BUILDER_CONTRACTS.md'
    proofs = {}
    for line, text in enumerate(document.read_text().splitlines(), 1):
        if text.startswith('| API'):
            cells = text.split('|')
            for identifier in re.findall(r'API\d{4}', cells[1]):
                assert identifier not in proofs
                proofs[identifier] = {'line': line, 'contract': cells[2].strip()}
    expected = {'API0021', 'API0030'} | {'API' + str(n).zfill(4) for n in range(178, 191)}
    assert set(proofs) == expected
    all_calls = json.loads(gzip.decompress((OUT / 'reconciled/calls.json.gz').read_bytes()))
    calls = [r for r in all_calls if r['pattern_id'] in expected]
    assert (len(calls), len({r['file'] for r in calls}), len({r['consumer'] for r in calls})) == (5006, 46, 127)
    with gzip.open(OUT / 'argument-facts/facts.tsv.gz', 'rt') as stream:
        facts = list(csv.DictReader(stream, delimiter='\t', quoting=csv.QUOTE_NONE))
    key = lambda r: tuple(r[k] for k in ('file', 'start_utf16', 'end_utf16', 'kind'))
    operands = collections.defaultdict(list)
    for row in facts: operands[key(row)].append(row)
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    ordered = json.loads((OUT / 'ordered/qualified-identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    hashes = {}
    for file in sorted({r['file'] for r in calls}):
        raw = (install / file).read_bytes()
        assert digest(raw) == identity['input_sha256'][file]
        assert digest((ROOT / file).read_bytes()) == ordered['input_sha256'][file]
        hashes[file] = {'original': digest(raw), 'current_ordered': ordered['input_sha256'][file]}
        encoded = raw.decode().encode('utf-16-le')
        for row in (r for r in calls if r['file'] == file):
            assert encoded[int(row['start_utf16']) * 2:int(row['end_utf16']) * 2].decode('utf-16-le') == row['literal_source']
    joined = [{**r, 'proof': proofs[r['pattern_id']], 'operands': operands[key(r)],
               'API_contract_status': 'reviewed exact declaration and immediate typed operands',
               'later_obligation': 'producer/output ordering and native normal/failure retirement remain blocked at actual consumer gate'} for r in calls]
    assert all(r['operands'] and r['operands'][0]['part'] == 'receiver' for r in joined)
    objects = [r for r in joined if r['pattern_id'] == 'API0184']
    domain = collections.Counter(next(a['actual_type'] for a in r['operands'] if a['part'] == 'argument0') for r in objects)
    assert domain == {'java.lang.Integer': 9, 'java.nio.file.Path': 2, 'java.lang.Character': 2}
    sequences = [r for r in joined if r['pattern_id'] == 'API0182']
    assert len(sequences) == 9 and all(next(a['actual_type'] for a in r['operands'] if a['part'] == 'argument0') == 'java.lang.StringBuilder' for r in sequences)
    assert sum(r['pattern_id'] == 'API0183' for r in joined) == 1
    assert sum(r['pattern_id'] == 'API0189' for r in joined) == 5
    probe = json.loads((OUT / 'builder-probe/qualification.json').read_text())
    assert probe['J0_seed_sha256'] == identity['seed_jar_sha256']
    assert len(probe['commands']) == 5 and all(c['returncode'] == 0 for c in probe['commands'])
    assert digest(gzip.decompress((OUT / 'builder-probe/probe-source.java.gz').read_bytes())) == probe['probe_sha256']
    assert digest((ROOT / 'scripts/self-hosting/BuilderContractProbe.java').read_bytes()) == probe['probe_sha256']
    for n in range(1, 5):
        assert (OUT / 'builder-probe' / (str(n) + '.stdout.txt')).read_bytes() == b'checks=37\n'
        assert not (OUT / 'builder-probe' / (str(n) + '.stderr.txt')).read_bytes()
    native = ROOT / 'stdlib/src/main/ironwood/ironwood/lang/StringBuilder.iron'
    assert 'appendCodePoint' not in native.read_text()
    result = {'schema': 1, 'scope': 'exact mutable-text declarations and immediate typed source operands; no global ordering/native qualification',
              'review_sha256': digest(document.read_bytes()), 'reviewer_sha256': digest(Path(__file__).read_bytes()),
              'argument_source_qualification_sha256': digest((OUT / 'argument-facts/source-qualification.json').read_bytes()),
              'source_sha256': hashes, 'native_builder_source_sha256': digest(native.read_bytes()),
              'probe_qualification_sha256': digest((OUT / 'builder-probe/qualification.json').read_bytes()),
              'patterns': [{'id': i, 'proof': proofs[i]} for i in sorted(expected)], 'calls': joined,
              'Object_actual_types': dict(domain), 'fresh_checks': [37] * 4}
    (OUT / 'builders-reviewed.json.gz').write_bytes(gzip.compress((json.dumps(result, indent=2) + '\n').encode(), mtime=0))
    print('PASS: 15 declarations, 5,006 calls, 9 Integer/2 Path/2 Character operands, four 37-check outputs')


if __name__ == '__main__':
    main()
