#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Record typed later obligations with branch-sensitive first-consumer gates."""
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path
import runpy

COMMON = runpy.run_path(str(Path(__file__).with_name('reconcile-inventory.py')))
ROOT, OUT, read, digest = (COMMON[n] for n in ('ROOT', 'OUT', 'read', 'digest'))


def load(name):
    return json.loads(gzip.decompress((OUT / 'reconciled' / (name + '.json.gz')).read_bytes()))


def site(row):
    return tuple(row[k] for k in ('file', 'start_utf16', 'end_utf16', 'kind'))


def method(row):
    return row['consumer'].split('::')[-1].split('(')[0]


def stage(row):
    file, name, function = row['file'], Path(row['file']).name, method(row)
    if '/ast/' in file or '/ir/' in file: return 2
    if '/semantic/' in file: return 3
    if '/doc/' in file or name in ('IronDoc.java', 'IronDocOptions.java'): return 6
    if name.startswith('IronJar') or name == 'IronClass.java': return 6
    if name == 'SourceSetLoader.java' and function == 'loadBridge': return 7
    if name == 'SourceSetLoader.java' and (function == 'locateClass' or row['consumer'] == '<class>'): return 6
    if name == 'StandardLibrary.java': return 6
    if name == 'Main.java' and function == 'writeClassOutputs': return 6
    if name == 'CompilerPipeline.java' and function in ('analyzeForBridge', 'compileBridge'): return 7
    if '/bridge/' in file or name.startswith('Bridge'): return 7
    return 4


def excluded_edge(row):
    name, function, owner = Path(row['file']).name, method(row), row['declaring_owner']
    if name == 'Main.java':
        return 'source-only S4 uses a private adapter; Main CLI/Bridge/artifact/driver dispatch is excluded'
    if name == 'CompilerPipeline.java' and (function in ('analyzeForBridge', 'compileBridge') or row['resolved_signature'].startswith('analyzeForBridge(')):
        return 'Bridge facade/bridgeAnalysis/proxy branch excluded from ordinary source-only compilation'
    if owner == 'ironwood.compiler.StandardLibrary' and row['resolved_signature'] in ('discover()', 'locate(java.lang.String)'):
        return 'source-only adapter supplies source roots/owned types and installation identity; general archive-first discovery/lookup is excluded'
    if name in ('StandardLibrary.java', 'SourceSetLoader.java') and (owner.endswith(('IronJar', 'IronClass')) or row['resolved_signature'].startswith(('locateClass(', 'discoverArchiveTypes('))):
        return 'archive/class roots and fallback excluded from fixed source-only installation adapter'
    return None


def family(owner, signature):
    if owner == 'java.util.HexFormat': return 'digest-text'
    if 'CRC32' in owner or owner == 'java.security.MessageDigest' or owner == 'java.security.NoSuchAlgorithmException': return 'digest'
    if owner.startswith(('java.util.zip.', 'java.util.jar.')): return 'archive'
    if owner.startswith(('java.lang.Process', 'javax.tools.')): return 'process'
    if owner.startswith(('java.io.ByteArray', 'java.io.Data')) or owner.startswith('java.nio.Byte'): return 'binary'
    if owner == 'java.io.PrintStream': return 'value'
    if owner.startswith(('java.nio.file.', 'java.io.')): return 'host'
    if 'Comparator' in owner or owner.endswith(('TreeMap', 'TreeSet')) or signature.startswith(('sorted(', 'sort(')): return 'sort'
    if owner.startswith('java.math.') or owner in ('java.lang.Number', 'java.lang.Float', 'java.lang.Double'): return 'numeric'
    if 'String' in owner or 'Character' in owner or 'Locale' in owner or '.regex.' in owner or '.charset.' in owner: return 'text'
    if owner.startswith('java.util.function.') or owner == 'java.lang.Runnable': return 'callback'
    if owner.startswith('java.util.stream.'): return 'pipeline'
    if owner.startswith('java.util.') and not owner.endswith(('Objects', 'Optional', 'OptionalInt', 'Properties')): return 'collection'
    if owner.endswith('Properties'): return 'properties'
    return 'value'


RESOLUTION = {
    'digest-text': 'elide stateless HexFormat.of default factory and use private lowercase full-byte-array formatting: two digits per byte including leading zero, no delimiter/prefix/suffix; null byte array fails, input borrowed through conversion and result text independent/owned through comparison or metadata consumer; SHA-256 sites produce 32 bytes; resolve allocation/failure cleanup, exact bytes and changed declaration authority without public HexFormat',
    'binary': 'resolve actual byte slice, endian/unsigned width, range/EOF/write failure and result aliases; private bounded codec/helper with owned output/backing and borrowed input lifetime',
    'archive': 'resolve admitted compression/container/metadata and duplicate/malformed cases, reader/writer identity, owned buffers and staged publication; no writer policy inferred',
    'digest': 'resolve exact input byte slice/encoding, algorithm/length/endian output and authoritative declaration identity; vectors and changed inputs; owned digest buffers',
    'process': 'resolve exact executable/argv/cwd/environment/stdin/output/exit and exceptional cleanup; fixed synchronous service or transition shell/JDK tool, no runtime Java in native compiler',
    'host': 'resolve actual path/stream/buffer producer, UTF-8 and bounds, links/options/missing/access/publication behavior; retain earlier output and retire handles/backing on failures',
    'sort': 'resolve key/extractor/null and comparator-zero domains, first representatives/stable ties, upstream IDs/diagnostics before ordering, owned sorted backing and borrowed payload last use',
    'numeric': 'resolve actual width/radix/raw bits, signed/unsigned/overflow/zero/NaN/conversion and failure order; bounded private helper and result ownership',
    'text': 'resolve exact UTF-16/UTF-8/range/locale/regex/numeric format and nullable operand domain; preserve left-to-right conversion/failure and owned results versus aliases',
    'callback': 'resolve immediate/lazy/retained holder/last invocation, primitive/reference signature, receiver binding, mutation/reentrancy/failure; measure state allocations and safe/unsafe cleanup',
    'pipeline': 'replace this exact pipeline by loops/private helpers; preserve encounter order, short circuit, duplicates, callback timing/failure and mutable/immutable result backing ownership',
    'collection': 'resolve actual key equality/hash/identity and null/duplicate domain, shallow/deep independent copies versus views, mutation/result aliases and iterator validity; normal/failure backing retirement',
    'properties': 'resolve exact admitted property/manifest bytes, escapes/continuations/duplicates/order and defaults; owned parse state and borrowed input cleanup',
    'value': 'resolve this exact declared/instantiated operation, admitted nullable/value/identity/default domain, evaluation/failure order and result alias/owner; private native value/rewrite, no JVM reflection/loading',
}


def main():
    destination = OUT / 'deferred'
    destination.mkdir(exist_ok=True)
    original_calls = read('calls')
    groups, owner_methods = collections.defaultdict(list), collections.defaultdict(set)
    for row in original_calls:
        key = row['file'] + '|' + row['consumer']
        groups[key].append(row)
        if row['consumer'] != '<class>': owner_methods[row['consumer']].add(key)
    phases = {key: stage(rows[0]) for key, rows in groups.items()}
    routes = {key: [] for key in groups}
    edges, excluded = collections.defaultdict(list), []
    for key, rows in groups.items():
        for row in rows:
            if not row['declaring_owner'].startswith('ironwood.'): continue
            reason = excluded_edge(row)
            if reason:
                excluded.append({**row, 'source_only_exclusion': reason})
                continue
            target = row['declaring_owner'] + '::' + row['resolved_signature']
            for callee in sorted(owner_methods.get(target, [])): edges[key].append((callee, row))
    pending = collections.deque(sorted(phases, key=lambda k: (phases[k], k)))
    while pending:
        key = pending.popleft()
        for callee, row in edges[key]:
            if phases[callee] > phases[key]:
                phases[callee] = phases[key]
                routes[callee] = routes[key] + [{k: row[k] for k in ('file', 'line', 'start_utf16', 'end_utf16', 'consumer', 'declaring_owner', 'resolved_signature')}]
                pending.append(callee)
    def gate(row, owner, category='value'):
        old = row['gate']
        if old['migration_consumer'].startswith('S1'):
            selected = {**old, 'admission': 'selected finite M1 preparation only; native M2 gate open'}
            if old['B_owner'] in ('B3', 'B4'):
                selected.update(B_owner='B7', preparation='M1.3',
                                selected_value_boundary='existing lexical Path/name and identity hash values only; no file acquisition or process service')
            return selected
        key = row['file'] + '|' + row['consumer']
        phase = phases.get(key, stage(row))
        name, function = Path(row['file']).name, method(row)
        if phase <= 3: preparation = 'M3.2' if phase == 3 and category in ('numeric', 'text', 'digest', 'digest-text') else 'M3.1'
        elif phase == 4: preparation = 'M3.3'
        elif phase == 6: preparation = 'M5.1' if category == 'digest' else 'M5.2-M5.3' if category in ('archive', 'properties', 'binary') or name in ('IronClass.java', 'IronJar.java', 'StandardLibrary.java') or function in ('locateClass', 'writeClassOutputs') else 'M5.4'
        else: preparation = 'M6.1 before M6.2'
        consumer = 'S' + str(phase) + ': ' + row['consumer']
        driver = name in ('NativeBackend.java', 'LlvmToolchain.java', 'MacNativeTools.java', 'ToolchainDiscovery.java', 'TargetMachine.java')
        if driver and phase >= 4:
            preparation = 'M4.3' if category == 'process' else 'M4.1-M4.2 before M4.3'
            consumer = 'M4.3 native driver (optional S4 driver route): ' + row['consumer']
        elif category == 'process':
            preparation = 'M4.3 before ' + preparation
        if category == 'host' and owner != 'B3': owner = 'B3/B7'
        if category == 'process': owner = 'B4/B7'
        if category in ('text', 'numeric', 'callback', 'value', 'binary'): owner = 'B7'
        if category == 'sort': owner = 'B2/B7'
        if category == 'archive': owner = 'B6'
        if category == 'digest': owner = 'B5'
        if category == 'digest-text':
            owner = 'B5/B7'
            if phase > 4: preparation = 'reuse M3.2 digest-text helper; ' + preparation
        return {'B_owner': owner, 'preparation': preparation, 'migration_consumer': consumer,
                'actual_source_consumer': row['consumer'], 'earlier_owner_call_route': routes.get(key, []),
                'admission': 'deferred; required resolution blocks this named native consumer',
                'route_limit': 'exact source call graph with documented source-only branch exclusions; unresolved conditional/generated/virtual routes block admission'}
    with gzip.open(OUT / 'argument-facts/facts.tsv.gz', 'rt') as stream:
        facts = list(csv.DictReader(stream, delimiter='\t', quoting=csv.QUOTE_NONE))
    operands = collections.defaultdict(list)
    for row in facts: operands[site(row)].append(row)
    builders = json.loads(gzip.decompress((OUT / 'builders-reviewed.json.gz').read_bytes()))
    builder_by = {site(r): r for r in builders['calls']}
    calls = load('calls')
    by_pattern = collections.defaultdict(list)
    for row in calls:
        category = family(row['declaring_owner'], row['resolved_signature'])
        row['gate'] = gate(row, row['gate']['B_owner'], category)
        row['operands'] = operands[site(row)]
        assert row['operands']
        row['dependency_family'] = category
        row['required_resolution']['fixture_id'] = row['pattern_id'] + ':' + row['id'] + ':' + row['consumer']
        row['required_resolution']['source_operation'] = row['literal_source']
        row['required_resolution']['blocked_contract'] = RESOLUTION[category]
        if site(row) in builder_by:
            row['scoped_proofs'].append({'report': 'builders', 'proof': builder_by[site(row)]['proof']})
        by_pattern[row['pattern_id']].append(row['id'])
    api = load('api')
    calls_by_id = {r['id']: r for r in calls}
    for row in api:
        row['site_ids'] = by_pattern[row['id']]
        row['discovery_B_owner'] = row['B_owner']
        row['B_owners'] = sorted({calls_by_id[i]['gate']['B_owner'] for i in row['site_ids']})
        category = family(row['owner'], row['signature'])
        row['dependency_family'] = category
        row['required_resolution'] = RESOLUTION[category]
        row['required_treatment_category'] = 'host service' if category in ('host', 'process') else 'library extension' if category in ('collection', 'sort', 'archive', 'digest') else 'local rewrite'
        row['admission'] = 'per-site scoped proof or source-backed deferred obligation; no whole-module portability claim'
    syntax, captures = load('syntax'), load('captures')
    for row in syntax + captures:
        row['gate'] = gate(row, 'B7')
        row['fixture_id'] = row.get('fixture_id', row.get('required_fixture'))
    storage = load('hash')
    call_by = {(r['file'], r['start_utf16'], r['end_utf16']): r for r in calls}
    for row in storage['origins']:
        offsets = row['node'].rsplit(':', 2)
        source = call_by[row['file'], offsets[1], offsets[2]]
        row['gate'] = source['gate']
        row['producer_operands'] = source['operands']
        row['required_fixture'] = row['id'] + ':producer:' + row['consumer']
    for row in storage['traversals']:
        row['gate'] = gate(row, 'B1/B2/B7', 'collection')
    traversals = {r['id']: r for r in storage['traversals']}
    for row in storage['contributions']:
        row['gate'] = traversals[row['traversal']]['gate']
        row['required_resolution'] += '; first migration consumer and prerequisite are the attached exact gate'
    consumers = [{**r, 'gate': gate(r, 'B1/B7')} for r in load('consumers')]
    payloads = {'api': api, 'calls': calls, 'syntax': syntax, 'captures': captures, 'hash': storage, 'consumers': consumers, 'excluded-edges': excluded}
    artifacts = {}
    for name, payload in payloads.items():
        raw = (json.dumps(payload, indent=2) + '\n').encode()
        compressed = gzip.compress(raw, mtime=0)
        (destination / (name + '.json.gz')).write_bytes(compressed)
        artifacts[name] = {'raw_sha256': digest(raw), 'raw_bytes': len(raw), 'sha256': digest(compressed)}
    manifest = {'schema': 1, 'scope': 'complete exact source demand inventory; later unresolved contracts block their named native consumer; finite selected M1 boundary only',
                'input_reconciliation_manifest_sha256': digest((OUT / 'reconciled/manifest.json').read_bytes()),
                'input_reconciliation_qualification_sha256': digest((OUT / 'reconciled/qualification.json').read_bytes()),
                'builder_review_sha256': digest((OUT / 'builders-reviewed.json.gz').read_bytes()),
                'argument_source_qualification_sha256': digest((OUT / 'argument-facts/source-qualification.json').read_bytes()),
                'contract_document_sha256': digest((OUT / 'DEFERRED_CONTRACTS.md').read_bytes()),
                'tool_sha256': digest(Path(__file__).read_bytes()), 'artifacts': artifacts,
                'counts': {'api': len(api), 'calls': len(calls), 'syntax': len(syntax), 'captures': len(captures), 'origins': len(storage['origins']), 'traversals': len(storage['traversals']), 'contributions': len(storage['contributions'])}}
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('PASS: source-backed per-site deferred contracts, typed operands and branch-sensitive consumer gates')


if __name__ == '__main__':
    main()
