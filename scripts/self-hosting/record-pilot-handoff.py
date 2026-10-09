#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Freeze exact M1 source prerequisites separately from native M2 qualification."""
import collections
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load(name):
    return json.loads(gzip.decompress((OUT / 'deferred' / (name + '.json.gz')).read_bytes()))


def selected(row):
    return row['gate']['migration_consumer'].startswith('S1')


def main():
    calls = [r for r in load('calls') if selected(r)]
    syntax = [r for r in load('syntax') if selected(r)]
    captures = [r for r in load('captures') if selected(r)]
    storage = load('hash')
    traversal_ids = {r['id'] for r in storage['traversals'] if selected(r)}
    contributions = [r for r in storage['contributions'] if r['traversal'] in traversal_ids]
    assert len(calls) == 1330 and len(captures) == 65
    assert all(r['scoped_proofs'] for r in calls + captures)
    assert all(r['selected_source_scope'] for r in syntax)
    assert all(r['scoped_proofs'] for r in contributions)
    assert {r['gate']['B_owner'] for r in calls} == {'B1', 'B7'}
    phases = collections.defaultdict(list)
    for row in calls: phases[row['gate']['preparation']].append(row['id'])
    assert set(phases) == {'M1.1', 'M1.2', 'M1.3'}
    models = json.loads((OUT / 'frontend-model-schema.json').read_text()), json.loads((OUT / 'operation-model-schema.json').read_text())
    assert len(models[0]['treatments']) == 119 and len(models[1]['declarations']) == 233
    references = ['PILOT_HANDOFF.md', 'deferred/manifest.json', 'deferred/qualification.json',
                  'frontend-model-schema.json', 'operation-model-schema.json', 'FRONTEND_PILOT.md',
                  'OWNERSHIP_PILOT.md', 'OPERATION_MODEL.md', 'OPERATION_FACTORY_CONTRACTS.md',
                  'PILOT_BUDGETS.md', 'pilot-budgets.json', 'pilot-budget-controls.json',
                  'canonical-corpus-manifest.json', 'loop-cycle-reference-manifest.json',
                  'current-delta/qualification.json', 'ordered/DELTA.md', 'REPLAY.md']
    result = {'schema': 1, 'scope': 'M0 complete prerequisite handoff; M1/M2 not implemented or qualified',
              'references': {name: digest((OUT / name).read_bytes()) for name in references},
              'tool_sha256': digest(Path(__file__).read_bytes()), 'calls': calls, 'syntax': syntax,
              'captures': captures, 'hash_contributions': contributions, 'phase_call_ids': dict(phases),
              'selected_B2_requirement': 'none; effect FIFO/vector/value operations require B1/B7 only',
              'source_bundle_split': {'M1.1/M1.3': 'deliver/hash helpers', 'M2.1': 'translate consumers, complete whole bundle, freeze fresh Java reference before native evaluation'},
              'model_declarations': [119, 233], 'native_implementation': False, 'native_pilot_evaluation': False}
    raw = (json.dumps(result, indent=2) + '\n').encode()
    (OUT / 'pilot-handoff.json.gz').write_bytes(gzip.compress(raw, mtime=0))
    print('PASS:', len(calls), 'selected calls;', len(syntax), 'syntax;', len(captures), 'captures;', len(contributions), 'conditional/scoped hash contributions')


if __name__ == '__main__':
    main()
