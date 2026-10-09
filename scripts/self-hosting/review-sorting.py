#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join exact sorting declarations to source facts and blocked consumer gates."""
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
csv.field_size_limit(8 * 1024 * 1024)


def read(name):
    with gzip.open(OUT / 'inventory' / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def gate(row):
    name = Path(row['file']).name
    domain = row['instantiated_type']
    if name == 'NativeBackend.java' or name == 'BridgeNativeSupport.java':
        return 'B2/B3/B7 M4.1 before M4.3 native driver; source-only S4 shell route excluded', 'NaturalAndReversePathCleanup'
    if '/doc/' in row['file']:
        return 'B2/B3/B7 M5.4 before S6 documentation consumers', 'DocumentedTypeMemberAndDiscoveryOrder'
    if name == 'IronJar.java':
        return 'B1/B2/B3/B6 M5.3 before S6 archive consumers', 'ArchiveKeyAndUnicodePathOrder'
    if name == 'InitializedTypeSpecializer.java' or '/backend/' in row['file']:
        return 'B2/B5/B7 M3.3 before S4 optimization/trace consumer', 'UnsignedTraceOrBudgetedSpecializationOrder'
    if '/semantic/' in row['file']:
        return 'B1/B2/B7 M3.1 before S3; text/numeric dependencies M3.2 where reached', 'SemanticSelectorProducerAndTieOrder'
    if name == 'SourceSetLoader.java':
        return 'B2/B7 M3.1 before S3 sorted export input consumer', 'ExportNamePermutation'
    if 'Path' in domain:
        return 'B2/B3/B7 M4.1 and M6.2 before standalone S7 inputs; earlier actual consumer brings required slice forward', 'ProviderOrderVersusUtf16Names'
    return 'B1/B2/B7 M6.1 before M6.2/S7 standalone Bridge consumer; semantic callers have M3.1 before S3 gate', 'BridgeKeyIndexRepresentativeAndInputPermutation'


def main():
    document = OUT / 'SORTING_CONTRACTS.md'
    proofs = {}
    for line, text in enumerate(document.read_text().splitlines(), 1):
        if text.startswith('| API'):
            fields = text.split('|')
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proofs: raise ValueError('duplicate sorting declaration proof')
                proofs[identifier] = {'line': line, 'contract': fields[2].strip()}
    extra = {'API0096', 'API0097', 'API0348', 'API0674', 'API0675', 'API0679', 'API0680'}
    patterns = [row for row in read('dependencies-discovery') if row['B'] == 'B2' or row['id'] in extra]
    if set(proofs) != {row['id'] for row in patterns}: raise ValueError('missing or stale sorting proof')
    by_key = {(row['owner'], row['signature'], row['kind']): row for row in patterns}
    calls = [row for row in read('calls') if (row['declaring_owner'], row['resolved_signature'], row['kind']) in by_key]
    if (len(patterns), len(calls), len({row['file'] for row in calls}), len({row['consumer'] for row in calls})) != (41, 554, 72, 134):
        raise ValueError('sorting source selection changed')
    original = json.loads((OUT / 'qualified/identity.json').read_text())
    ordered = json.loads((OUT / 'ordered/qualified-identity.json').read_text())
    install = Path(original['launcher']).parent.parent
    sources, hashes = {}, {}
    for name in sorted({row['file'] for row in calls}):
        data = (install / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == original['input_sha256'][name]
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == ordered['input_sha256'][name]
        sources[name] = data.decode().encode('utf-16-le')
        hashes[name] = {'original': original['input_sha256'][name], 'current_ordered': ordered['input_sha256'][name]}
    scoped = []
    for row in calls:
        pattern = by_key[row['declaring_owner'], row['resolved_signature'], row['kind']]
        begin, end = int(row['start_utf16']), int(row['end_utf16'])
        excerpt = sources[row['file']][begin * 2:end * 2].decode('utf-16-le')
        assert excerpt and sources[row['file']][:begin * 2].decode('utf-16-le').count('\n') + 1 == int(row['line'])
        blocking_gate, fixture = gate(row)
        scoped.append({**row, 'pattern_id': pattern['id'], 'literal_source': excerpt,
            'proof': proofs[pattern['id']], 'source_owner': row['consumer'],
            'blocking_gate': blocking_gate, 'required_fixture': fixture + ':' + row['consumer'],
            'API_contract_status': 'reviewed exact declaration and immediate source shape',
            'producer_tie_retention_status': 'unresolved until the named original producer/callback/tie consumer fixture passes',
            'payload_ownership': 'owned sorted/result backing; elements/keys/payloads borrowed through their actual last consumer; no null/value domain inferred solely from the constructor',
            'first_source_operation': row['expression']})
    constructors = [row for row in scoped if row['kind'] == 'CONSTRUCTOR' and row['declaring_owner'] in ('java.util.TreeMap', 'java.util.TreeSet')]
    natural = [row for row in scoped if row['declaring_owner'] == 'java.util.stream.Stream' and row['resolved_signature'] == 'sorted()']
    assert len(constructors) == 88 and len(natural) == 41
    sorted_domains = dict(collections.Counter(row['instantiated_type'].split('<', 1)[1].split(',', 1)[0].rstrip('>') for row in constructors))
    assert sum('java.nio.file.Path' in row['instantiated_type'] for row in constructors) == 2
    captures = [row for row in read('captures') if any(row['file'] == call['file'] and
                row['consumer'] == call['consumer'] and int(call['start_utf16']) <= int(row['callback_start']) < int(call['end_utf16'])
                for call in scoped)]
    sdk_path = Path(original['jdk']) / 'lib/src.zip'
    sdk_files = ['java.base/java/util/' + name + '.java' for name in ('TreeMap', 'TreeSet', 'Comparator')]
    sdk_files += ['java.base/java/util/stream/ReduceOps.java', 'java.base/java/util/function/BinaryOperator.java', 'java.base/sun/nio/fs/UnixPath.java']
    with zipfile.ZipFile(sdk_path) as archive:
        sdk_hashes = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in sdk_files}
    result = {'schema': 1, 'scope': 'exact sorting APIs and immediate source facts; upstream/global ordering remains blocked',
        'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(), 'source_sha256': hashes,
        'sdk_contract_source_path': str(sdk_path), 'sdk_contract_source_sha256': sdk_hashes,
        'patterns': [{key: row[key] for key in ('id', 'owner', 'signature', 'kind')} for row in patterns],
        'calls': scoped, 'sorted_origins': constructors, 'sorted_key_domains': sorted_domains,
        'natural_stream_domains': dict(collections.Counter(row['instantiated_type'] for row in natural)),
        'captures_in_selected_arguments': captures,
        'limits': 'not a global hash/capture proof, no native sorting/helper implementation or resource measurement; existing Path mismatch is a blocked fixed-POSIX compiler-demand contract'}
    (OUT / 'sorting-reviewed.json.gz').write_bytes(gzip.compress((json.dumps(result, indent=2) + '\n').encode(), mtime=0))
    print('PASS: 41 exact patterns/554 source calls/88 sorted origins;', len(captures), 'captured argument rows; producer/tie gates remain explicit')


if __name__ == '__main__':
    main()
