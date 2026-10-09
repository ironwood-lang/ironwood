#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Verify source joins, conditional pilot gates and deliberate reconciliation failures."""
import copy
import gzip
import json
from pathlib import Path
import runpy

COMMON = runpy.run_path(str(Path(__file__).with_name('reconcile-inventory.py')))
ROOT, OUT, read, digest, site = (COMMON[name] for name in ('ROOT', 'OUT', 'read', 'digest', 'site'))


def selected(row):
    return row['gate']['migration_consumer'].startswith('S1')


def selected_call(row):
    if selected(row):
        assert row['scoped_proofs'] and row['gate']['selected_boundary']
        assert 'M1.' in row['gate']['preparation']


def walk(contribution, origin, traversal, edges):
    node = origin['node']
    for index, contained in contribution['flow_path']:
        edge = edges[str(index)]
        prefix = 'C:' if contained else ''
        assert node == prefix + edge['from']
        node = prefix + edge['to']
    assert node == traversal['node']
    if selected(traversal): assert contribution['scoped_proofs']
    if contribution['scoped_proofs'] and all(p.get('conditional') for p in contribution['scoped_proofs']):
        assert contribution['classification'].startswith('unresolved')
        assert all(p['selected_boundary'] and p['later_gate'] for p in contribution['scoped_proofs'])


def main():
    destination = OUT / 'reconciled'
    manifest = json.loads((destination / 'manifest.json').read_text())
    payloads = {}
    for name, record in manifest['artifacts'].items():
        data = gzip.decompress((destination / (name + '.json.gz')).read_bytes())
        assert len(data) == record['raw_bytes'] and digest(data) == record['raw_sha256']
        payloads[name] = json.loads(data)
        if 'rows' in record: assert len(payloads[name]) == record['rows']
    for name, record in manifest['scoped_reports'].items():
        assert digest((OUT / (name + '-reviewed.json.gz')).read_bytes()) == record['sha256']
    assert digest((OUT / 'current-delta/qualification.json').read_bytes()) == manifest['current_delta_qualification_sha256']
    original = json.loads((OUT / 'qualified/identity.json').read_text())
    install = Path(original['launcher']).parent.parent
    encoded = {}
    for name, value in manifest['original_source_sha256'].items():
        data = (install / name).read_bytes()
        assert digest(data) == value
        assert digest((ROOT / name).read_bytes()) == manifest['current_source_sha256'][name]
        encoded[name] = data.decode().encode('utf-16-le')
    def literal(row, key):
        data = encoded[row['file']]
        start, end = int(row['start_utf16']), int(row['end_utf16'])
        assert row[key] == data[start * 2:end * 2].decode('utf-16-le')
        assert data[:start * 2].decode('utf-16-le').count('\n') + 1 == int(row['line'])
    original_external = [r for r in read('calls') if not r['declaring_owner'].startswith('ironwood.')]
    assert len(original_external) == len(payloads['calls']) == 30979
    pattern_rows = {r['id']: r for r in payloads['api']}
    assert set(pattern_rows) == {r['id'] for r in read('dependencies-discovery')}
    for before, row in zip(original_external, payloads['calls']):
        assert all(row[k] == v for k, v in before.items())
        pattern = pattern_rows[row['pattern_id']]
        assert (pattern['owner'], pattern['signature'], pattern['kind']) == (row['declaring_owner'], row['resolved_signature'], row['kind'])
        literal(row, 'literal_source')
        selected_call(row)
    frontend = json.loads(gzip.decompress((OUT / 'pilot-frontend-reviewed.json.gz').read_bytes()))
    by_site = {site(r): r for r in payloads['calls']}
    for row in frontend['external_calls']: assert selected(by_site[site(row)])
    for name, key in (('syntax', 'literal_source'), ('captures', 'literal_capture')):
        originals = read(name)
        assert len(originals) == len(payloads[name])
        for before, row in zip(originals, payloads[name]):
            assert all(row[k] == v for k, v in before.items())
            literal(row, key)
            if selected(row):
                assert row['scoped_proofs'] if name == 'captures' else row['selected_source_scope']
            if name == 'captures':
                assert row['callback_source']
                if '@' not in row['captured_symbol']: assert row['capture_role'].startswith('receiver member access')
    hash_payload = payloads['hash']
    origins, traversals = ({r['id']: r for r in hash_payload[name]} for name in ('origins', 'traversals'))
    assert len(origins) == 849 and len(traversals) == 2957
    expected = {(origin, row['id']) for row in traversals.values() for origin in row['hash_origins'].split(';')}
    actual = {(r['origin'], r['traversal']) for r in hash_payload['contributions']}
    assert expected == actual and len(actual) == len(hash_payload['contributions']) == 31325
    original_origins = read('hash-sources-discovery')
    assert set(origins) == {row['id'] for row in original_origins}
    for before in original_origins:
        row = origins[before['id']]
        assert all(row[k] == v for k, v in before.items())
    original_edges = read('flow')
    for index, edge in hash_payload['witness_edges'].items(): assert edge == original_edges[int(index)]
    assert digest(gzip.decompress((OUT / 'inventory/flow.tsv.gz').read_bytes())) == hash_payload['original_flow_sha256']
    for row in traversals.values(): literal(row, 'literal_source')
    for contribution in hash_payload['contributions']:
        walk(contribution, origins[contribution['origin']], traversals[contribution['traversal']], hash_payload['witness_edges'])
    assert origins['H0296']['classification'] == 'lookup/membership only'
    assert origins['H0296']['scoped_source_proof']['proof']
    negatives = []
    def reject(name, function):
        try: function()
        except AssertionError: negatives.append(name)
        else: raise ValueError('negative control accepted: ' + name)
    call = copy.deepcopy(next(r for r in payloads['calls'] if selected(r)))
    call['scoped_proofs'] = []
    reject('missing selected exact call proof', lambda: selected_call(call))
    call = copy.deepcopy(by_site[site(frontend['external_calls'][0])])
    call['gate']['migration_consumer'] = 'S4'
    reject('selected frontend moved to later gate', lambda: assert_selected(call))
    contribution = copy.deepcopy(next(r for r in hash_payload['contributions'] if r['flow_path']))
    contribution['flow_path'] = contribution['flow_path'][1:]
    reject('missing origin flow edge', lambda: walk(contribution, origins[contribution['origin']], traversals[contribution['traversal']], hash_payload['witness_edges']))
    contribution = copy.deepcopy(next(r for r in hash_payload['contributions'] if r['scoped_proofs'] and all(p.get('conditional') for p in r['scoped_proofs'])))
    contribution['classification'] = 'order-independent traversal'
    reject('bounded proof promoted to general ordering', lambda: walk(contribution, origins[contribution['origin']], traversals[contribution['traversal']], hash_payload['witness_edges']))
    report = {'schema': 1, 'manifest_sha256': digest((destination / 'manifest.json').read_bytes()),
              'source_files': len(encoded), 'patterns': len(pattern_rows), 'external_calls': len(original_external),
              'syntax': len(payloads['syntax']), 'capture_rows': len(payloads['captures']),
              'hash_origins': len(origins), 'hash_traversals': len(traversals), 'hash_contributions': len(actual),
              'selected_calls': sum(selected(r) for r in payloads['calls']),
              'selected_captures': sum(selected(r) for r in payloads['captures']),
              'negative_controls': negatives, 'limits': 'source join and conditional finite gates only; unresolved global contracts still block M0/S0 completion'}
    (destination / 'qualification.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: exact source/contribution joins and', len(negatives), 'reconciliation controls')


def assert_selected(row):
    assert selected(row)


if __name__ == '__main__':
    main()
