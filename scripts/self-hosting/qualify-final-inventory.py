#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check complete source-demand coverage and finite/deferred admission controls."""
import copy
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load(directory, name):
    return json.loads(gzip.decompress((OUT / directory / (name + '.json.gz')).read_bytes()))


def call_contract(row):
    assert row['operands'] and row['required_resolution']['fixture_id']
    assert row['required_resolution']['source_operation'] == row['literal_source']
    assert row['gate']['B_owner'] and row['gate']['preparation'] and row['gate']['migration_consumer']
    assert row['gate']['actual_source_consumer'] == row['consumer']
    assert row['required_resolution']['blocked_contract']
    if row['gate']['migration_consumer'].startswith('S1'):
        assert row['scoped_proofs'] and row['gate']['selected_boundary']
        assert row['gate']['preparation'].startswith('M1.')
    if row['file'].endswith('/BridgeAssembler.java'):
        assert row['gate']['migration_consumer'].startswith('S7:')
    if row['file'].endswith('/IronJar.java'):
        assert row['gate']['migration_consumer'].startswith('S6:')
    if row['pattern_id'] in ('API0412', 'API0413'):
        assert row['dependency_family'] == 'digest-text'
        assert row['gate']['B_owner'] == 'B5/B7'
        assert 'lowercase full-byte-array' in row['required_resolution']['blocked_contract']
        if row['gate']['migration_consumer'].startswith('S3:'):
            assert row['gate']['preparation'] == 'M3.2'
        elif row['file'].endswith('/TlsDependency.java'):
            assert row['gate']['preparation'] == 'M3.3'
        else:
            assert row['gate']['preparation'].startswith('reuse M3.2 digest-text helper; ')


def contribution_contract(row):
    assert row['gate']['actual_source_consumer'] and row['required_fixture']
    assert row['required_resolution'] and row['flow_path']
    if row['scoped_proofs'] and all(p.get('conditional') for p in row['scoped_proofs']):
        assert row['classification'].startswith('unresolved')


def main():
    manifest = json.loads((OUT / 'deferred/manifest.json').read_text())
    for field, path in (
        ('input_reconciliation_manifest_sha256', 'reconciled/manifest.json'),
        ('input_reconciliation_qualification_sha256', 'reconciled/qualification.json'),
        ('builder_review_sha256', 'builders-reviewed.json.gz'),
        ('argument_source_qualification_sha256', 'argument-facts/source-qualification.json'),
        ('contract_document_sha256', 'DEFERRED_CONTRACTS.md')):
        assert digest((OUT / path).read_bytes()) == manifest[field]
    assert digest(Path(__file__).with_name('finalize-inventory.py').read_bytes()) == manifest['tool_sha256']
    payloads = {}
    for name, expected in manifest['artifacts'].items():
        compressed = (OUT / 'deferred' / (name + '.json.gz')).read_bytes()
        data = gzip.decompress(compressed)
        assert digest(compressed) == expected['sha256']
        assert len(data) == expected['raw_bytes'] and digest(data) == expected['raw_sha256']
        payloads[name] = json.loads(data)
    calls, api = payloads['calls'], payloads['api']
    assert len(api) == 712 and len(calls) == 30979
    before_calls = load('reconciled', 'calls')
    selected = {r['id'] for r in before_calls if r['gate']['migration_consumer'].startswith('S1')}
    assert len(selected) == 1330
    assert selected == {r['id'] for r in calls if r['gate']['migration_consumer'].startswith('S1')}
    by_pattern = {}
    for old, row in zip(before_calls, calls):
        assert all(row[k] == old[k] for k in ('id', 'pattern_id', 'file', 'consumer', 'kind', 'declaring_owner', 'resolved_signature', 'instantiated_type', 'literal_source'))
        call_contract(row)
        by_pattern.setdefault(row['pattern_id'], []).append(row['id'])
    for row in api:
        assert row['site_ids'] == by_pattern[row['id']]
        assert row['B_owners'] and row['required_resolution']
        assert row['required_treatment_category'] in ('local rewrite', 'library extension', 'host service')
        if row['id'] in ('API0412', 'API0413'):
            assert row['dependency_family'] == 'digest-text' and row['B_owners'] == ['B5/B7']
            assert row['required_treatment_category'] == 'local rewrite'
    assert {r['id'] for r in calls if r['pattern_id'] in ('API0412', 'API0413')} == {'A08029', 'A08030', 'A11399', 'A11400', 'A11469', 'A11470', 'A19407', 'A19408'}
    for name, count in (('syntax', 12413), ('captures', 3054)):
        assert len(payloads[name]) == count
        for old, row in zip(load('reconciled', name), payloads[name]):
            assert row['id'] == old['id'] and row['consumer'] == old['consumer']
            assert row['fixture_id'] and row['gate']['actual_source_consumer'] == row['consumer']
            if name == 'syntax':
                assert row['literal_source'] == old['literal_source'] and row['required_treatment']
            else:
                assert row['captured_type'] == old['captured_type'] and row['callback_source'] == old['callback_source']
                assert row['capture_role'] == old['capture_role'] and row['required_resolution']
    old_hash, storage = load('reconciled', 'hash'), payloads['hash']
    assert storage['witness_edges'] == old_hash['witness_edges']
    assert len(storage['origins']) == 849 and len(storage['traversals']) == 2957 and len(storage['contributions']) == 31325
    for old, row in zip(old_hash['origins'], storage['origins']):
        assert all(old[k] == row[k] for k in ('id', 'literal_source', 'key_or_element_domain', 'equality', 'null_contract', 'classification', 'traversal_ids'))
        assert row['producer_operands'] and row['required_fixture']
    for old, row in zip(old_hash['contributions'], storage['contributions']):
        assert all(old[k] == row[k] for k in ('origin', 'traversal', 'flow_path', 'classification', 'scoped_proofs', 'selected_pilot_classification'))
        # Direct producer-to-traversal edges can have an empty path.
        if row['flow_path']: contribution_contract(row)
        else: assert row['gate']['actual_source_consumer'] and row['required_fixture']
    assert payloads['excluded-edges'] and all(r['source_only_exclusion'] for r in payloads['excluded-edges'])
    assert any(r['file'].endswith('/Main.java') and 'Bridge' in r['declaring_owner'] for r in payloads['excluded-edges'])
    assert any(r['declaring_owner'] == 'ironwood.compiler.StandardLibrary' and r['resolved_signature'] == 'discover()' for r in payloads['excluded-edges'])
    controls = []
    def rejected(label, validator, row):
        try: validator(row)
        except AssertionError: controls.append(label)
        else: raise AssertionError('accepted invalid ' + label)
    row = copy.deepcopy(next(r for r in calls if r['id'] in selected)); row['scoped_proofs'] = []
    rejected('missing selected proof', call_contract, row)
    row = copy.deepcopy(calls[0]); row['operands'] = []
    rejected('missing typed source operand', call_contract, row)
    row = copy.deepcopy(next(r for r in calls if r['file'].endswith('/BridgeAssembler.java'))); row['gate']['migration_consumer'] = 'S4'
    rejected('CLI Bridge branch promoted to S4', call_contract, row)
    row = copy.deepcopy(next(r for r in storage['contributions'] if r['flow_path'] and r['scoped_proofs'] and all(p.get('conditional') for p in r['scoped_proofs']))); row['classification'] = 'order independent globally'
    rejected('conditional order proof promoted globally', contribution_contract, row)
    row = copy.deepcopy(next(r for r in calls if r['id'] == 'A19407')); row['dependency_family'] = 'collection'; row['gate']['preparation'] = 'M3.1'
    rejected('ByteView digest formatting misclassified as collection', call_contract, row)
    result = {'schema': 1, 'manifest_sha256': digest((OUT / 'deferred/manifest.json').read_bytes()),
              'qualifier_sha256': digest(Path(__file__).read_bytes()), 'counts': manifest['counts'],
              'selected_external_calls': len(selected), 'negative_controls': controls,
              'scope': 'M0 source inventory/deferred gates; no native M1/M2 or whole-module qualification'}
    (OUT / 'deferred/qualification.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: complete exact demand coverage, 1,330 selected proofs and', len(controls), 'admission controls')


if __name__ == '__main__':
    main()
