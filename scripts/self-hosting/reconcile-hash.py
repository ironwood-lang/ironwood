#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Preserve source domains and witnessed conservative paths for every hash contribution."""
import collections
import gzip
import json
from pathlib import Path
import re
import runpy

COMMON = runpy.run_path(str(Path(__file__).with_name('reconcile-inventory.py')))
ROOT, OUT, read, digest = (COMMON[name] for name in ('ROOT', 'OUT', 'read', 'digest'))


def load(name):
    return json.loads(gzip.decompress((OUT / 'reconciled' / (name + '.json.gz')).read_bytes()))


def key_domain(type_text):
    result = type_text.rsplit(')', 1)[-1]
    found = re.search(r'java\.util\.(?:HashMap|IdentityHashMap|HashSet|Map|Set)<', result)
    if not found: return None
    offset, level = found.end(), 0
    for end in range(offset, len(result)):
        char = result[end]
        if char == '<': level += 1
        if char == '>':
            if level == 0: return result[offset:end]
            level -= 1
        if char == ',' and level == 0: return result[offset:end]
    return None


def main():
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    sources = {name: (install / name).read_text() for name in identity['input_sha256']
               if name.startswith('compiler/src/main/java/') and name.endswith('.java')}
    for name, text in sources.items(): assert digest(text.encode()) == identity['input_sha256'][name]
    encoded = {name: text.encode('utf-16-le') for name, text in sources.items()}
    def literal(row):
        return encoded[row['file']][int(row['start_utf16']) * 2:int(row['end_utf16']) * 2].decode('utf-16-le')
    calls = {(r['file'], r['start_utf16'], r['end_utf16']): r for r in load('calls')}
    traversal_gates = {(r['file'], r['start_utf16'], r['end_utf16'], r['node'], r['kind']): r['gate'] for r in load('traversal-gates')}
    syntax = load('syntax')
    declaration_rows = [r for r in syntax if r['kind'] in ('RECORD', 'INTERFACE')]
    def equality(domain, identity_keys):
        if identity_keys: return {'contract': 'reference identity; identity hash; mutable node state excluded from equality'}
        if domain in ('java.lang.String', 'java.lang.Integer', 'java.lang.Long', 'java.lang.Boolean', 'java.lang.Character'):
            return {'contract': 'value equality/hash; String uses UTF-16 sequence; boxed primitive value preserves primitive bits/value', 'domain': domain}
        simple = domain.rsplit('.', 1)[-1] if domain else ''
        declarations = [{'file': r['file'], 'line': r['line'], 'kind': r['kind'],
                         'header': r['literal_source'].split('{', 1)[0].strip(),
                         'explicit_equals': bool(re.search(r'boolean\s+equals\s*\(', r['literal_source'])),
                         'explicit_hash': bool(re.search(r'int\s+hashCode\s*\(', r['literal_source']))}
                        for r in declaration_rows if r['context'] == simple]
        return {'contract': 'declared key equality must be resolved against these source declarations and concrete variants before consumer admission',
                'domain': domain, 'declarations': declarations, 'status': 'unresolved value-member/hash/identity distinction unless scoped proof below applies'}
    original_origins = read('hash-sources-discovery')
    original_traversals = read('hash-traversals-discovery')
    edges = read('flow')
    adjacency = collections.defaultdict(list)
    for index, edge in enumerate(edges):
        adjacency[edge['from']].append((edge['to'], index, False))
        if edge['kind'] not in ('RETAINED', 'RETRIEVED_ELEMENT', 'ITERATED_ELEMENT'):
            adjacency['C:' + edge['from']].append(('C:' + edge['to'], index, True))
    for choices in adjacency.values(): choices.sort()
    contributions, origins, traversals = [], [], []
    by_origin = collections.defaultdict(list)
    for index, row in enumerate(original_traversals):
        identifier = 'T' + str(index + 1).zfill(5)
        gate = traversal_gates.get((row['file'], row['start_utf16'], row['end_utf16'], row['node'], row['kind']))
        assert gate is not None
        traversals.append({**row, 'id': identifier, 'literal_source': literal(row), 'gate': gate,
            'first_candidate_order_operation': row['expression'],
            'limits': 'source operation is a candidate boundary; downstream first observable decision unresolved except exact scoped contributions'})
        for origin in row['hash_origins'].split(';'): by_origin[origin].append((identifier, row['node']))
    proofs = collections.defaultdict(list)
    for name in ('evidence-order', 'effects', 'unfreed'):
        report = json.loads(gzip.decompress((OUT / (name + '-reviewed.json.gz')).read_bytes()))
        for row in report['traversals']:
            selected = [row['reviewed_origin']] if 'reviewed_origin' in row else row['hash_origins'].split(';')
            key = (row['file'], row['start_utf16'], row['end_utf16'], row['node'], row['kind'])
            for origin in selected: proofs[(origin, key)].append({'report': name, 'proof': row.get('proof'), 'classification': row['classification']})
    ownership = json.loads(gzip.decompress((OUT / 'pilot-ownership-reviewed.json.gz').read_bytes()))
    for row in ownership['hash_traversals']:
        key = (row['file'], row['start_utf16'], row['end_utf16'], row['node'], row['kind'])
        for origin in row['hash_origins'].split(';'):
            proofs[(origin, key)].append({'report': 'pilot-ownership', 'proof': row['source_proof'],
                'classification': row['classification'], 'conditional': True,
                'selected_boundary': 'LOCAL_NEW, one pool-conflicting key, empty JoinPath/JoinAlternative, retained immutable children with no selection, keyed output, tight budget stopped before join',
                'later_gate': row['later_gate'], 'ordered_delta': 'D247 slot copy; original source remains retained'})
    frontend = json.loads(gzip.decompress((OUT / 'frontend-reviewed.json.gz').read_bytes()))
    source_proofs = {row['id']: row for row in frontend['hash_sources']}
    traversal_by_id = {r['id']: r for r in traversals}
    used_edges = set()
    for origin in original_origins:
        node = origin['node'].rsplit(':', 2)
        call = calls[origin['file'], node[1], node[2]]
        domain = key_domain(call['instantiated_type'])
        targets = {node for identifier, node in by_origin[origin['id']]}
        parent, work = {origin['node']: None}, collections.deque([origin['node']])
        while work and not targets <= parent.keys():
            current = work.popleft()
            for target, index, contained in adjacency[current]:
                if target not in parent:
                    parent[target] = (current, index, contained)
                    work.append(target)
        assert targets <= parent.keys(), 'lost hash-origin witness'
        immutable = origin['owner'] in ('java.util.Map', 'java.util.Set') or 'Unmodifiable' in origin['signature']
        null_contract = ('reject null keys/values/elements at immutable construction/copy; caller input occurrence domain remains source-gated' if immutable else
                         'HashMap/HashSet/IdentityHashMap permit nullable membership; caller null occurrence domain unresolved' if origin['origin_kind'] == 'constructor/reference' else
                         'collector-specific null and duplicate-key policy unresolved before this exact collected result consumer')
        origins.append({**origin, 'literal_source': call['literal_source'], 'instantiated_type': call['instantiated_type'],
            'key_or_element_domain': domain, 'equality': equality(domain, origin['owner'] == 'java.util.IdentityHashMap'),
            'null_contract': null_contract, 'producer_source': call['expression'], 'gate': call['gate'],
            'ownership': 'result membership backing owned by the source holder/caller; payload references borrowed; immutable copies do not prove source encounter order or payload retirement',
            'classification': 'unresolved original producer and downstream ordering except individually scoped contributions',
            'traversal_ids': [identifier for identifier, target in by_origin[origin['id']]],
            'zero_traversal_limit': 'zero discovered traversal does not prove lookup-only; returned/retained/generated/virtual frontiers must be ruled out before admission' if not targets else None})
        if origin['id'] in source_proofs:
            origins[-1]['scoped_source_proof'] = source_proofs[origin['id']]
            origins[-1]['classification'] = source_proofs[origin['id']]['classification']
            origins[-1]['zero_traversal_limit'] = None
        for identifier, target in by_origin[origin['id']]:
            path, current = [], target
            while parent[current] is not None:
                previous, index, contained = parent[current]
                path.append([index, contained])
                used_edges.add(index)
                current = previous
            path.reverse()
            row = traversal_by_id[identifier]
            key = (row['file'], row['start_utf16'], row['end_utf16'], row['node'], row['kind'])
            proof = proofs.get((origin['id'], key), [])
            selected = row['gate']['migration_consumer'].startswith('S1')
            constructor = 'selected/restricted constructor data only' in row['gate'].get('selected_boundary', '')
            if selected and not proof and constructor:
                proof = [{'report': 'operation-factory', 'proof': 'OPERATION_FACTORY_CONTRACTS.md', 'conditional': True,
                          'classification': 'this original producer graph is excluded from the finite private factory',
                          'selected_boundary': 'empty type/specialization/class inputs; ordered primitive-index reference/parameter/block/call builders; fixed foreign target/context/result',
                          'later_gate': 'B1/B7 M3.1 before general S2/S3 data constructor and original producer'}]
            unconditional = any(not item.get('conditional') for item in proof)
            contributions.append({'origin': origin['id'], 'traversal': identifier, 'flow_path': path, 'scoped_proofs': proof,
                'classification': 'scoped order-independent contribution' if unconditional else 'unresolved general producer/order/first-observable boundary',
                'selected_pilot_classification': 'resolved only under explicit bounded operation restrictions' if proof and not unconditional else None,
                'required_fixture': origin['id'] + ':' + identifier + ':' + row['consumer'],
                'required_resolution': 'follow this witnessed origin/copy/return/retained route into the named traversal; specify exact observable selector/ID/diagnostic/effect order or prove all contributions extensional; test collisions/resizes/permutations and equal selector keys before dependent consumer'})
    assert (len(origins), len(traversals), len(contributions)) == (849, 2957, 31325)
    result = {'schema': 1, 'scope': 'source-backed unresolved ordering obligations with exact contribution proofs; conservative discovery paths are not semantic proofs',
              'origins': origins, 'traversals': traversals, 'contributions': contributions,
              'witness_edges': {str(index): edges[index] for index in sorted(used_edges)},
              'original_flow_sha256': digest(gzip.decompress((OUT / 'inventory/flow.tsv.gz').read_bytes())),
              'limits': 'original D247 slot producer kept as original; current delta separately qualified; lookup-only/order-independent not inferred globally'}
    raw = (json.dumps(result, separators=(',', ':'), ensure_ascii=True) + '\n').encode()
    (OUT / 'reconciled/hash.json.gz').write_bytes(gzip.compress(raw, mtime=0))
    manifest = json.loads((OUT / 'reconciled/manifest.json').read_text())
    manifest['artifacts']['hash'] = {'origins': len(origins), 'traversals': len(traversals), 'contributions': len(contributions),
        'raw_bytes': len(raw), 'raw_sha256': digest(raw), 'classification_counts': dict(collections.Counter(r['classification'] for r in contributions))}
    (OUT / 'reconciled/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('PASS: 849 exact origins/2957 traversals/31325 witnessed contributions;', len(used_edges), 'source flow edges')


if __name__ == '__main__':
    main()
