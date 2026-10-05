#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check flat TSV grammar, original source identity and operand source ranges."""
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'
csv.field_size_limit(8 * 1024 * 1024)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def rows(data, flat=True):
    return list(csv.DictReader(io.StringIO(data.decode()), delimiter='\t',
                              quoting=csv.QUOTE_NONE if flat else csv.QUOTE_MINIMAL))


def cell(text):
    return text.replace('\\', '\\\\').replace('\t', '\\t').replace('\r', '\\r').replace('\n', '\\n')


def main():
    identity = json.loads((OUT / 'qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    encoded = {}
    for name, sha in identity['input_sha256'].items():
        if not name.startswith('compiler/src/main/java/') or not name.endswith('.java'):
            continue
        source = (install / name).read_bytes()
        assert digest(source) == sha
        encoded[name] = source.decode().encode('utf-16-le')
    assert len(encoded) == 460
    report = json.loads((OUT / 'argument-facts/qualification.json').read_text())
    raw = gzip.decompress((OUT / 'argument-facts/facts.tsv.gz').read_bytes())
    tool = gzip.decompress((OUT / 'argument-facts/tool-source.java.gz').read_bytes())
    assert digest(raw) == report['raw_sha256'] and len(raw) == report['raw_bytes']
    assert digest(tool) == report['tool_source_sha256']
    assert tool == (ROOT / 'scripts/self-hosting/ArgumentFacts.java').read_bytes()
    assert all(c['returncode'] == 0 for c in report['commands'])
    facts = rows(raw)
    original = rows(gzip.decompress((OUT / 'inventory/calls.tsv.gz').read_bytes()))
    fields = ('file', 'start_utf16', 'end_utf16', 'consumer', 'kind', 'declaring_owner', 'resolved_signature')
    key = lambda r: tuple(r[k] for k in fields)
    receivers = [r for r in facts if r['part'] == 'receiver']
    external = [r for r in original if not r['declaring_owner'].startswith('ironwood.')]
    assert len(facts) == 53644 and len(receivers) == len(external) == 30979
    assert len({key(r) for r in receivers}) == 30979
    assert {key(r) for r in receivers} == {key(r) for r in external}
    quotes = constants = 0
    for row in facts:
        assert len(row) == 19 and None not in row and None not in row.values()
        source = encoded[row['file']]
        a, b = int(row['part_start']), int(row['part_end'])
        if a >= 0:
            assert 0 <= a <= b <= len(source) // 2
            assert row['part_source'] == cell(source[a * 2:b * 2].decode('utf-16-le'))
        else:
            assert b == -1 and not row['part_source']
        quotes += '"' in row['part_source']
        if row['constant_encoding'] == 'UTF16 hex':
            units = row['constant_units']
            assert len(units) % 4 == 0 and all(c in '0123456789abcdef' for c in units)
            bytes.fromhex(units).decode('utf-16-be', errors='surrogatepass')
            if row['actual_type'] == 'char': assert len(units) == 4
            constants += 1
    assert quotes > 0 and constants > 0
    control = b'id\toperand\n1\t"x".equals(value)\n2\t"quoted"\n'
    assert [r['operand'] for r in rows(control)] == ['"x".equals(value)', '"quoted"']
    assert rows(control, False) != rows(control)
    losses = {}
    for name in ('calls', 'syntax', 'references', 'traversals'):
        data = gzip.decompress((OUT / 'inventory' / (name + '.tsv.gz')).read_bytes())
        faithful, historical = rows(data), rows(data, False)
        assert len(faithful) == len(historical)
        losses[name] = sum(a['expression'] != b['expression'] for a, b in zip(faithful, historical))
    assert losses == {'calls': 56, 'syntax': 47, 'references': 6, 'traversals': 40}
    result = {'schema': 1, 'source_files': len(encoded), 'rows': len(facts), 'external_calls': len(receivers),
              'quote_operands': quotes, 'UTF16_constants': constants, 'flat_quote_control': 'passed',
              'historical_expression_quote_changes': losses, 'raw_sha256': digest(raw),
              'capture_qualification_sha256': digest((OUT / 'argument-facts/qualification.json').read_bytes()),
              'qualifier_sha256': digest(Path(__file__).read_bytes()),
              'limit': 'formal declarations are not instantiated generic substitutions; dynamic null/alias/lifetime facts remain unresolved'}
    (OUT / 'argument-facts/source-qualification.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: 460 source hashes, 53,644 operand ranges, 30,979 calls and flat-quote control')


if __name__ == '__main__':
    main()
