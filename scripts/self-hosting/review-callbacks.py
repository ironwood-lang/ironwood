#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Join reviewed private functional calls, constructor captures and retained fields."""
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


def main():
    document = OUT / 'CALLBACK_CONTRACTS.md'
    proof, site_proof, field_proof = {}, {}, []
    for line, row in enumerate(document.read_text().splitlines(), 1):
        fields = row.split('|')
        if row.startswith('| API'):
            for identifier in re.findall(r'API\d{4}', fields[1]):
                if identifier in proof:
                    raise ValueError('duplicate functional API review')
                proof[identifier] = {'document_line': line, 'exact_declarations': fields[1].strip(),
                                     'reviewed_contract': fields[2].strip(),
                                     'owner_readiness_first_consumer_required_fixture': fields[3].strip()}
        elif row.startswith('| C'):
            match = re.fullmatch(r'(\w+\.java):(\d+)', fields[2].strip())
            if not match or (match[1], match[2]) in site_proof:
                raise ValueError('invalid or duplicate callback site review')
            site_proof[(match[1], match[2])] = {'id': fields[1].strip(), 'document_line': line,
                                              'reviewed_contract': fields[3].strip(),
                                              'owner_readiness_first_consumer_required_fixture': fields[4].strip()}
        elif row.startswith('| F'):
            match = re.fullmatch(r'(\w+\.java):(\d+) (\w+)', fields[2].strip())
            if not match:
                raise ValueError('bad callback field review')
            field_proof.append({'id': fields[1].strip(), 'document_line': line,
                                'source_name': match[1], 'source_line': int(match[2]),
                                'field': match[3], 'reviewed_contract': fields[3].strip()})
    groups = {}
    for call in read('calls'):
        if call['declaring_owner'].startswith('java.util.function.'):
            key = (call['declaring_owner'], call['resolved_signature'], call['kind'])
            groups.setdefault(key, []).append(call)
    patterns = []
    for pattern in read('dependencies-discovery'):
        key = (pattern['owner'], pattern['signature'], pattern['kind'])
        if key in groups:
            if pattern['id'] not in proof:
                raise ValueError('unreviewed functional API: ' + str(key))
            patterns.append({'id': pattern['id'], 'owner': key[0], 'signature': key[1], 'kind': key[2],
                             'proof': proof[pattern['id']], 'callers': groups.pop(key)})
    if groups or set(proof) != {r['id'] for r in patterns}:
        raise ValueError('stale or unmatched functional API review')
    capture_groups = {}
    capture_rows = read('captures')
    for capture in capture_rows:
        capture_groups.setdefault((capture['file'], capture['callback_start']), []).append(capture)
    sites = []
    for syntax in read('syntax'):
        if syntax['kind'] not in ('LAMBDA', 'METHOD_REFERENCE'):
            continue
        key = (Path(syntax['file']).name, syntax['line'])
        captured = capture_groups.get((syntax['file'], syntax['start_utf16']), [])
        constructor_capture = captured and syntax['context'].startswith('NEW_CLASS:')
        if constructor_capture or key in site_proof:
            if key not in site_proof or not captured:
                raise ValueError('unreviewed or stale captured constructor site: ' + str(key))
            sites.append(dict(syntax, proof=site_proof.pop(key), captures=captured,
                              direct_constructor=bool(constructor_capture)))
    if site_proof or len(sites) != 23 or sum(r['direct_constructor'] for r in sites) != 22:
        raise ValueError('changed retained callback selection requires review')
    direct_rows = sum(len(r['captures']) for r in sites if r['direct_constructor'])
    if direct_rows != 67 or sum(len(r['captures']) for r in sites) != 68:
        raise ValueError('changed capture set requires source review')
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    files = {r['file'] for r in sites} | {c['file'] for p in patterns for c in p['callers']}
    if len({(f['source_name'], f['field']) for f in field_proof}) != len(field_proof):
        raise ValueError('duplicate retained callback field')
    for field in field_proof:
        matched = [file for file in identity['input_sha256']
                   if file.startswith('compiler/src/main/java/') and Path(file).name == field['source_name']]
        if len(matched) != 1:
            raise ValueError('ambiguous or missing callback holder')
        file = matched[0]
        text = (install / file).read_text().splitlines()[field['source_line'] - 1]
        if not re.search(r'\b' + re.escape(field['field']) + r'\b', text):
            raise ValueError('callback field location changed; re-review required')
        field['file'] = file
        field['original_line_text'] = text
        files.add(file)
    files.update('compiler/src/main/java/ironwood/compiler/' + name for name in
                 ('NativeLinkPipeline.java', 'SemanticAnalyzerFactory.java',
                  'semantic/AnonymousParentBinder.java', 'semantic/InvocationPlanningContext.java'))
    for file in files:
        if hashlib.sha256((install / file).read_bytes()).hexdigest() != identity['input_sha256'][file]:
            raise ValueError('frozen source hash mismatch: ' + file)
    report = {'schema': 1, 'revision': identity['revision'],
              'review_sha256': hashlib.sha256(document.read_bytes()).hexdigest(),
              'scope': 'all functional member calls; all captured constructor callbacks; one source-traced method-to-field observer; ten private retained fields',
              'limits': 'not all capture sites; symbol rows are not allocation counts; actual native reclamation requires later proof and fixtures',
              'source_sha256': {file: identity['input_sha256'][file] for file in sorted(files)},
              'fields': field_proof, 'field_count': len(field_proof),
              'patterns': patterns, 'pattern_count': len(patterns),
              'call_count': sum(len(r['callers']) for r in patterns),
              'sites': sites, 'site_count': len(sites), 'constructor_site_count': 22,
              'constructor_capture_rows': direct_rows, 'reviewed_capture_rows': 68,
              'unreviewed_method_capture_rows': len(capture_rows) - 68}
    if len(field_proof) != 10:
        raise ValueError('changed retained field selection requires review')
    (OUT / 'callbacks-reviewed.json.gz').write_bytes(gzip.compress(
        (json.dumps(report, indent=2, ensure_ascii=True) + '\n').encode(), mtime=0))
    print('PASS:', report['pattern_count'], 'functional patterns,', report['call_count'],
          'calls,', report['site_count'], 'retained capture sites,', report['field_count'], 'fields')


if __name__ == '__main__':
    main()
