#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Preserve current attributed D247 sites alongside the immutable original catalog."""
import csv
import difflib
import gzip
import hashlib
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
SOURCE = 'compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java'
csv.field_size_limit(8 * 1024 * 1024)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    original = json.loads((OUT / 'qualified/identity.json').read_text())
    ordered = json.loads((OUT / 'ordered/qualified-identity.json').read_text())
    before = (Path(original['launcher']).parent.parent / SOURCE).read_bytes()
    after = (ROOT / SOURCE).read_bytes()
    assert digest(before) == original['input_sha256'][SOURCE]
    assert digest(after) == ordered['input_sha256'][SOURCE]
    destination = OUT / 'current-delta'
    destination.mkdir(exist_ok=True)
    capture = ROOT / 'target/self-hosting-m0/current-inventory'
    commands = json.loads((capture / 'commands.json').read_text())
    assert len(commands) == 2 and all(row['returncode'] == 0 for row in commands)
    preserved = {}
    for name in ('commands.json', '0.stdout', '0.stderr', '1.stdout', '1.stderr'):
        data = (capture / name).read_bytes()
        (destination / name).write_bytes(data)
        preserved[name] = {'bytes': len(data), 'sha256': digest(data)}
    tool = (ROOT / 'scripts/self-hosting/Inventory.java').read_bytes()
    (destination / 'inventory-source.java.gz').write_bytes(gzip.compress(tool, mtime=0))
    preserved['inventory-source.java.gz'] = {'raw_bytes': len(tool), 'raw_sha256': digest(tool)}
    tables = {}
    for name in ('calls', 'captures', 'containers', 'references', 'flow', 'syntax', 'traversals'):
        with (capture / 'raw' / (name + '.tsv')).open() as stream:
            reader = csv.DictReader(stream, delimiter='\t')
            fields = reader.fieldnames
            rows = [row for row in reader if row['file'] == SOURCE]
        output = io.StringIO()
        writer = csv.DictWriter(output, fieldnames=fields, delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
        data = output.getvalue().encode()
        (destination / (name + '.tsv.gz')).write_bytes(gzip.compress(data, mtime=0))
        tables[name] = rows
        preserved[name + '.tsv.gz'] = {'rows': len(rows), 'raw_bytes': len(data), 'raw_sha256': digest(data)}
    old_lines, new_lines = before.decode().splitlines(True), after.decode().splitlines(True)
    def offsets(lines):
        result = [0]
        for line in lines: result.append(result[-1] + len(line.encode('utf-16-le')) // 2)
        return result
    old_offsets, new_offsets = offsets(old_lines), offsets(new_lines)
    equal = [(old_offsets[a], old_offsets[b], new_offsets[c] - old_offsets[a])
             for kind, a, b, c, d in difflib.SequenceMatcher(a=old_lines, b=new_lines, autojunk=False).get_opcodes()
             if kind == 'equal']
    def stable(row):
        start, end = int(row['start_utf16']), int(row['end_utf16'])
        return any(a <= start <= end <= b for a, b, delta in equal)
    with gzip.open(OUT / 'inventory/calls.tsv.gz', 'rt') as stream:
        old_calls = [row for row in csv.DictReader(stream, delimiter='\t') if row['file'] == SOURCE]
    changed_old = [row for row in old_calls if not stable(row)]
    new_equal = [(a + delta, b + delta) for a, b, delta in equal]
    changed_new = [row for row in tables['calls'] if not any(a <= int(row['start_utf16']) <= int(row['end_utf16']) <= b for a, b in new_equal)]
    def call_key(row, delta=0):
        return (int(row['start_utf16']) + delta, int(row['end_utf16']) + delta,
                row['consumer'], row['kind'], row['declaring_owner'], row['resolved_signature'])
    mapped_old = {call_key(row, delta) for row in old_calls for a, b, delta in equal
                  if a <= int(row['start_utf16']) <= int(row['end_utf16']) <= b}
    current_equal = {call_key(row) for row in tables['calls']
                     if any(a <= int(row['start_utf16']) <= int(row['end_utf16']) <= b for a, b in new_equal)}
    assert mapped_old == current_equal
    with gzip.open(OUT / 'inventory/dependencies-discovery.tsv.gz', 'rt') as stream:
        api = {(r['owner'], r['signature'], r['kind']): r['id'] for r in csv.DictReader(stream, delimiter='\t')}
    current = after.decode().encode('utf-16-le')
    for row in changed_new:
        row['literal_source'] = current[int(row['start_utf16']) * 2:int(row['end_utf16']) * 2].decode('utf-16-le')
        row['original_pattern'] = api.get((row['declaring_owner'], row['resolved_signature'], row['kind']))
        helper = '::copyArraySlots(' in row['consumer'] or row['resolved_signature'].startswith('copyArraySlots(')
        comment_only = int(row['line']) == 2072
        row['change_role'] = 'new ordered copy helper' if helper else 'comment-only enclosing expression' if comment_only else 'snapshot builder supplied directly'
        row['contract'] = ('ordered/DELTA.md: independent insertion-ordered slot membership, non-null keys/values, borrowed nodes, immediate copy callback, immutable wrapper with no escaping mutable copy' if helper else
                           'SORTING_CONTRACTS.md: unchanged minimum slot index then container allocation index; edited explanatory comment only' if comment_only else
                           'ordered/DELTA.md: unchanged seven-field snapshot construction; supply knownArraySlots directly because the compact constructor now owns its independent ordered copy')
        row['owner_phase_consumer'] = ('B1/B2/B7 M3.1 before full S3 free-probe selection; excluded from selected M2 operation' if comment_only else
                                      'B1 M1.1/B7 M1.3 before M2 snapshot construction; later full ownership M3.1 before S3')
    report = {'schema': 1, 'original_source_sha256': digest(before), 'current_source_sha256': digest(after),
        'scope': 'all current FunctionAnalyzer attribution tables plus exact changed source regions; no overwrite of original IDs or ordering proofs',
        'preserved': preserved, 'unchanged_utf16_regions': equal, 'unchanged_call_count': len(mapped_old),
        'changed_original_calls': changed_old,
        'changed_current_calls': changed_new, 'source_contract': 'ordered/DELTA.md',
        'source_contract_sha256': digest((OUT / 'ordered/DELTA.md').read_bytes()),
        'limits': 'static attribution is not native execution, lifetime proof or classification of the whole analyzer'}
    (destination / 'qualification.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: seven current tables;', len(changed_old), 'old/', len(changed_new), 'current changed-region calls')


if __name__ == '__main__':
    main()
