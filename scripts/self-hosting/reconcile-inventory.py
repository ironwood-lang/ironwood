#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join original source sites to scoped proofs and explicit deferred consumers."""
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


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def site(row):
    return (row['file'], row['start_utf16'], row['end_utf16'], row.get('kind', ''))


def capture_key(row):
    return tuple(row[key] for key in ('file', 'consumer', 'callback_start', 'start_utf16', 'end_utf16', 'captured_symbol'))


def base(file):
    name = Path(file).name
    if '/ast/' in file or '/ir/' in file: return 2
    if '/semantic/' in file: return 3
    if '/doc/' in file: return 6
    if name.startswith('IronJar') or name == 'IronClass.java': return 6
    if '/bridge/' in file or name.startswith('Bridge'): return 7
    return 4


def main():
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    ordered = json.loads((OUT / 'ordered/qualified-identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    source_data, source_text, source_utf16 = {}, {}, {}
    for name in sorted(identity['input_sha256']):
        if not name.startswith('compiler/src/main/java/') or not name.endswith('.java'): continue
        data = (install / name).read_bytes()
        assert digest(data) == identity['input_sha256'][name]
        assert digest((ROOT / name).read_bytes()) == ordered['input_sha256'][name]
        source_data[name], source_text[name], source_utf16[name] = data, data.decode(), data.decode().encode('utf-16-le')
    assert len(source_data) == 460
    def literal(row):
        begin, end = int(row['start_utf16']), int(row['end_utf16'])
        assert 0 <= begin <= end <= len(source_utf16[row['file']]) // 2
        text = source_utf16[row['file']][begin * 2:end * 2].decode('utf-16-le')
        assert source_utf16[row['file']][:begin * 2].decode('utf-16-le').count('\n') + 1 == int(row['line'])
        return text
    calls = read('calls')
    by_consumer = collections.defaultdict(list)
    owners = collections.defaultdict(set)
    for row in calls:
        key = row['file'] + '|' + row['consumer']
        by_consumer[key].append(row)
        if row['consumer'] != '<class>': owners[row['consumer'].split('::')[0]].add(key)
    # Follow exact attributed methods, including method references. Generated
    # leaves/virtual dispatch without an attributed body remain explicit frontiers.
    adjacency = collections.defaultdict(list)
    for key, rows in by_consumer.items():
        for row in rows:
            if row['declaring_owner'].startswith('ironwood.'):
                targets = [target for target in owners[row['declaring_owner']]
                           if target.split('|', 1)[1] == row['declaring_owner'] + '::' + row['resolved_signature']]
                for target in sorted(targets):
                    adjacency[key].append((target, row))
    phases = {key: base(key.split('|')[0]) for key in by_consumer}
    route = {key: None for key in by_consumer}
    work = collections.deque(sorted(phases, key=lambda key: (phases[key], key)))
    while work:
        key = work.popleft()
        for target, row in adjacency[key]:
            if phases[target] > phases[key]:
                phases[target], route[target] = phases[key], (key, row)
                work.append(target)
    frontend = json.loads(gzip.decompress((OUT / 'pilot-frontend-reviewed.json.gz').read_bytes()))
    ownership = json.loads(gzip.decompress((OUT / 'pilot-ownership-reviewed.json.gz').read_bytes()))
    operation = json.loads((OUT / 'operation-model-schema.json').read_text())['consumers']
    frontend_sites = {site(row) for group in ('calls', 'syntax', 'captures') for row in frontend[group]}
    frontend_methods = set(frontend['methods'])
    ownership_methods = set(ownership['methods'])
    restricted_owners = {name.replace('$', '.') for name, treatment in operation.items() if treatment['role'] != 'later-only'}
    effect_methods = {'ClosedWorldEffectAnalyzer', 'analyze', 'summarize', 'reachableBlocks', 'propagateResultOrigins',
                      'callEffect', 'foreignOrigins', 'targets', 'callArguments', 'callResult', 'locallyAllocates',
                      'referenceConversions', 'isRenderedStringRelease', 'origin', 'mergeOrigin'}
    def pilot_scope(row):
        if site(row) in frontend_sites or row['consumer'] in frontend_methods:
            return 'frontend construction: exact 376-method closure and finite frontend-model-schema; data/constructors only where a record source span also contains later helpers'
        consumer = row['consumer']
        declaring, _, method = consumer.partition('::')
        method = method.split('(', 1)[0]
        if consumer in ownership_methods:
            if declaring.endswith(('JoinPath', 'JoinAlternative')): return None
            if method == 'captureJoinEvidence' and int(row['line']) > 12670: return None
            return 'bounded snapshot/restore/merge only: LOCAL_NEW, one pool conflict, empty JoinPath/JoinAlternative, pre-stopped tight evidence budget; OPERATION_MODEL gates all other roles later'
        if declaring == 'ironwood.compiler.semantic.ClosedWorldEffectAnalyzer' and method in effect_methods:
            return 'bounded effect fixed point: unique Node/Findex METHOD functions, one entry block, DIRECT/foreign calls, reference return, empty classes; OPERATION_MODEL rejects all other variants'
        if declaring.startswith('ironwood.compiler.semantic.ClosedWorldEffectAnalyzer.') and declaring in restricted_owners:
            return 'selected Summary/Effect value fields and independent bit-vector copies; OPERATION_MODEL'
        if consumer == '<class>' and row['file'].endswith('/ClosedWorldEffectAnalyzer.java') and int(row['line']) in (20, 22):
            return 'selected ordered function/summary backing only; unused non-DIRECT target cache remains later under the finite effect boundary'
        declaration = declaring if declaring else ''
        if row.get('kind') == 'RECORD':
            matches = [name for name in restricted_owners if name.rsplit('.', 1)[-1] == row.get('context')]
            if len(matches) == 1:
                return 'selected/restricted operation data declaration only: ' + matches[0] + '; OPERATION_MODEL excludes later roles/helper methods in this source span'
        if declaration in restricted_owners and method == declaration.rsplit('.', 1)[-1]:
            return 'selected/restricted constructor data only; enforce exact OPERATION_MODEL factory fields/variants and reject all other roles'
        return None
    def gate(row, owner):
        selected = pilot_scope(row)
        if selected:
            traversal = row.get('declaring_owner', '') in ('java.util.Iterator', 'java.util.ArrayDeque', 'java.util.Deque') or row.get('kind', '').startswith(('CALL:', 'ARGUMENT_TRAVERSAL:'))
            preparation = 'M1.3' if owner == 'B7' else 'M1.2' if traversal else 'M1.1'
            return {'B_owner': owner, 'preparation': preparation,
                    'migration_consumer': 'S1 (M2.1 frontend)' if selected.startswith('frontend') else 'S1 (M2.2 bounded operations)', 'actual_source_consumer': row['consumer'],
                    'selected_boundary': selected, 'later_roles_gate': 'M3.1 before S2/S3; M3.2 text/numeric and M3.3 backend where actually reached',
                    'earlier_owner_call_route': [], 'route_limit': 'exact selected source/method overlay; no whole-file portability or admission of excluded model roles'}
        key = row['file'] + '|' + row['consumer']
        phase = phases.get(key, base(row['file']))
        name = Path(row['file']).name
        if phase <= 3: preparation = 'M3.1'
        elif phase == 4:
            preparation = 'M4.1 before M4.3' if owner == 'B4' or name in ('NativeBackend.java', 'BridgeNativeSupport.java', 'LlvmToolchain.java', 'MacNativeTools.java', 'ToolchainDiscovery.java') else 'M3.3'
        elif phase == 6: preparation = 'M5.3' if name.startswith('IronJar') or name == 'IronClass.java' else 'M5.4'
        else: preparation = 'M6.1 before M6.2'
        if owner == 'B5' and phase <= 3: preparation = 'M3.2 before S3 digest consumer'
        if owner == 'B5' and phase == 4: preparation = 'M3.3 before S4 digest/trace consumer'
        chain, active = [], key
        while route.get(active):
            parent, call = route[active]
            chain.append({k: call[k] for k in ('file', 'line', 'start_utf16', 'end_utf16', 'consumer', 'declaring_owner', 'resolved_signature')})
            active = parent
        chain.reverse()
        return {'B_owner': owner, 'preparation': preparation, 'migration_consumer': f'S{phase}',
                'actual_source_consumer': row['consumer'], 'earlier_owner_call_route': chain,
                'route_limit': 'exact attributed method route; generated leaves/virtual dispatch and actual branch admission remain unresolved before dependent native consumer'}
    reports = {}
    call_proofs, capture_proofs = collections.defaultdict(list), collections.defaultdict(list)
    for name in ('frontend', 'ast', 'worklists', 'callbacks', 'effects', 'diagnostics', 'unfreed', 'pilot-ownership', 'sorting', 'operation-factory'):
        path = OUT / (name + '-reviewed.json.gz')
        report = json.loads(gzip.decompress(path.read_bytes()))
        reports[name] = {'sha256': digest(path.read_bytes()), 'scope': report['scope']}
        for pattern in report.get('patterns', []):
            for row in pattern.get('callers', []):
                call_proofs[site(row)].append({'report': name, 'pattern': pattern['id'], 'proof': pattern.get('proof')})
        if name == 'sorting':
            for row in report['calls']: call_proofs[site(row)].append({'report': name, 'pattern': row['pattern_id'], 'proof': row['proof'], 'unresolved': row['producer_tie_retention_status']})
        for row in report.get('captures', []) + report.get('captures_in_selected_arguments', []):
            capture_proofs[capture_key(row)].append({'report': name, 'scope': report['scope']})
        for callback in report.get('sites', []):
            for row in callback.get('captures', []): capture_proofs[capture_key(row)].append({'report': name, 'proof': callback['proof']})
    frontend = json.loads(gzip.decompress((OUT / 'pilot-frontend-reviewed.json.gz').read_bytes()))
    for row in frontend['external_calls']:
        call_proofs[site(row)].append({'report': 'pilot-frontend', 'pattern': row['pattern'], 'proof': row['contract']})
    for row in frontend['captures']: capture_proofs[capture_key(row)].append({'report': 'pilot-frontend', 'scope': 'three lazy immediate Parser suppliers only'})
    patterns = read('dependencies-discovery')
    pattern_by = {(r['owner'], r['signature'], r['kind']): r for r in patterns}
    external, pattern_sites = [], collections.defaultdict(list)
    for row in calls:
        if row['declaring_owner'].startswith('ironwood.'): continue
        pattern = pattern_by[row['declaring_owner'], row['resolved_signature'], row['kind']]
        identifier = 'A' + str(len(external) + 1).zfill(5)
        proof = call_proofs.get(site(row), [])
        record = {**row, 'id': identifier, 'pattern_id': pattern['id'], 'literal_source': literal(row),
                  'scoped_proofs': proof, 'status': 'scoped contract; native gate open' if proof else 'unresolved exact caller; native consumer blocked',
                  'gate': gate(row, pattern['B']),
                  'required_resolution': {'source_operation': row['expression'], 'instantiated_domain': row['instantiated_type'],
                      'fixture_id': pattern['id'] + ':' + row['consumer'] + ':' + row['start_utf16'],
                      'unresolved_fields': [] if proof else ['admitted argument/null domain', 'equality and result aliasing', 'mutation/failure evaluation order', 'allocation owner and final borrowed consumer', 'required replacement and positive/negative fixture']}}
        external.append(record)
        pattern_sites[pattern['id']].append(identifier)
    api = []
    for pattern in patterns:
        native_category = 'library extension' if pattern['B'] in ('B1', 'B2', 'B5', 'B6') else 'host service' if pattern['B'] in ('B3', 'B4') else 'local rewrite'
        api.append({k: pattern[k] for k in ('id', 'owner', 'signature', 'kind')} | {'required_treatment_category': native_category,
            'B_owner': pattern['B'], 'site_ids': pattern_sites[pattern['id']], 'limits': 'category chooses preparation owner only; source-scoped proof or explicit unresolved caller gate controls admission'})
    syntax = []
    selected_syntax = collections.defaultdict(list)
    for row in frontend['syntax']: selected_syntax[site(row)].append('pilot-frontend:FRONTEND_PILOT.md and frontend-model-schema.json')
    ownership = json.loads(gzip.decompress((OUT / 'pilot-ownership-reviewed.json.gz').read_bytes()))
    for row in ownership['syntax']: selected_syntax[site(row)].append('pilot-ownership:OWNERSHIP_PILOT.md and finite OPERATION_MODEL.md restrictions')
    treatments = {
        'VAR': 'explicit declared type; preserve initializer timing and inferred generic type, never initialize a new accepted path',
        'UNINITIALIZED': 'explicit initial value plus unchanged guarded assignments/reads; definite assignment proof required',
        'VARARGS': 'fixed arity at private finite call sets or explicit caller-owned arrays; preserve argument evaluation order and allocation ownership',
        'LAMBDA': 'direct helper/loop or private callback with explicit captured state and immediate/retained lifetime',
        'METHOD_REFERENCE': 'preserve receiver evaluation at binding, virtual invocation, primitive argument/result and thrown failures',
        'RECORD': 'explicit ordered fields, compact-constructor evaluation, value equality/hash versus member identity; generated member consumers required',
        'INTERFACE': 'ordinary finite hierarchy; explicit consumer coverage replaces sealed exhaustiveness',
        'RESOURCE_TRY': 'explicit close/free on normal and abrupt exits; preserve primary/suppressed failure order and partial acquisition',
        'SYNCHRONIZED_METHOD': 'resolve method concurrency contract before removing or lowering monitor',
        'TEXT_BLOCK': 'supported literal with exact normalization/output bytes; surrounding stripIndent/formatting APIs have separate sites',
        'ENHANCED_FOR_VARIABLE': 'supported binding; preserve producer traversal order and borrowed iteration lifetime',
        'NULL': 'supported literal; exact presence/equality/dereference and nullable result remain caller obligations',
        'TYPE_PATTERN': 'supported instanceof binding' }
    for row in read('syntax'):
        treatment = treatments[row['kind']]
        if row['kind'] == 'TYPE_PATTERN' and row['context'] == 'PATTERN_CASE_LABEL':
            treatment = 'selector evaluated once; ordered instanceof arms, binding/null/abrupt-exit preservation and finite independent variant coverage'
        if pilot_scope(row) and not selected_syntax.get(site(row)):
            selected_syntax[site(row)].append('OPERATION_MODEL.md/OPERATION_FACTORY_CONTRACTS.md: admitted fixed data/branches only; native model-role validation M1.3 before M2, excluded roles later')
        syntax.append({**row, 'id': 'Y' + str(len(syntax) + 1).zfill(5), 'literal_source': literal(row),
            'required_treatment': treatment, 'gate': gate(row, 'B7'),
            'selected_source_scope': selected_syntax.get(site(row), []),
            'status': 'source syntax identified; scope-specific rewrite/variant proof required before native consumer',
            'fixture_id': row['consumer'] + ':syntax:' + row['kind'] + ':' + row['start_utf16']})
    callbacks, syntax_by_start = [], {(r['file'], r['start_utf16']): r for r in syntax if r['kind'] in ('LAMBDA', 'METHOD_REFERENCE', 'ANONYMOUS')}
    calls_by_file = collections.defaultdict(list)
    for row in calls: calls_by_file[row['file']].append(row)
    for row in read('captures'):
        callback = syntax_by_start.get((row['file'], row['callback_start']))
        proof = list(capture_proofs.get(capture_key(row), []))
        enclosing = [call for call in calls_by_file[row['file']] if call['kind'] != 'REFERENCE' and
                     int(call['start_utf16']) <= int(row['callback_start']) and
                     int(call['end_utf16']) >= int(callback['end_utf16'])]
        enclosing.sort(key=lambda call: int(call['end_utf16']) - int(call['start_utf16']))
        immediate_call = enclosing[:1]
        if row['file'].endswith('/ClosedWorldEffectAnalyzer.java') and immediate_call:
            matched = [item for item in call_proofs.get(site(immediate_call[0]), []) if item['report'] == 'effects']
            proof += [{'report': 'effects', 'exact_enclosing_call': immediate_call[0]['expression'], 'proof': item['proof'],
                       'scope': 'immediate/lazy-with-terminal capture contract in EFFECT_CONTRACTS.md; private model excludes non-DIRECT target-cache and non-return terminator branches'} for item in matched]
        callbacks.append({**row, 'id': 'C' + str(len(callbacks) + 1).zfill(5), 'literal_capture': literal(row),
            'callback_source': callback['literal_source'] if callback else None, 'scoped_proofs': proof,
            'capture_role': 'outer local/parameter value retained by callback' if '@' in row['captured_symbol'] else 'receiver member access; retain receiver identity and read member at invocation, not an assumed eager field copy',
            'direct_enclosing_calls': immediate_call,
            'status': 'scoped contract; native cleanup still required' if proof else 'retention unresolved; dependent native consumer blocked',
            'gate': gate(row, 'B7'), 'required_fixture': row['consumer'] + ':capture:' + row['callback_start'] + ':' + row['captured_symbol'],
            'required_resolution': 'exact holder/result/last-use route for this symbol, normal/exceptional callback effects, accepted independent cleanup and rejected premature capture/holder cleanup'})
    assert (len(api), len(syntax), len(callbacks)) == (712, 12413, 3054)
    consumers = [{'file': rows[0]['file'], 'consumer': rows[0]['consumer'], 'gate': gate(rows[0], 'B1/B7')}
                 for key, rows in sorted(by_consumer.items())]
    traversal_gates = [{**row, 'gate': gate(row, 'B1/B2/B7')} for row in read('traversals')]
    payloads = {'api': api, 'calls': external, 'syntax': syntax, 'captures': callbacks, 'consumers': consumers, 'traversal-gates': traversal_gates}
    destination = OUT / 'reconciled'
    destination.mkdir(exist_ok=True)
    manifest = {'schema': 1, 'scope': 'source-site reconciliation with scoped proofs and explicit unresolved gates; not global reviewed completion',
                'original_source_sha256': {p: digest(d) for p, d in source_data.items()},
                'current_source_sha256': {p: ordered['input_sha256'][p] for p in source_data},
                'scoped_reports': reports, 'artifacts': {}, 'limits': 'no native implementation or resource measurement; conservative routes are admission blockers, not transitive semantic proofs'}
    manifest['current_delta_qualification_sha256'] = digest((OUT / 'current-delta/qualification.json').read_bytes())
    for name, rows in payloads.items():
        raw = (json.dumps(rows, separators=(',', ':'), ensure_ascii=True) + '\n').encode()
        (destination / (name + '.json.gz')).write_bytes(gzip.compress(raw, mtime=0))
        manifest['artifacts'][name] = {'rows': len(rows), 'raw_bytes': len(raw), 'raw_sha256': digest(raw),
                                     'status_counts': dict(collections.Counter(r.get('status', '') for r in rows))}
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('PASS: source reconciliation', {k: len(v) for k, v in payloads.items()})


if __name__ == '__main__':
    main()
