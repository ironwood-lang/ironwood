#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bind private factory source constructors to exact conditional contracts."""
import gzip
import json
from pathlib import Path
import re
import runpy

COMMON = runpy.run_path(str(Path(__file__).with_name('reconcile-inventory.py')))
ROOT, OUT, digest, read = (COMMON[name] for name in ('ROOT', 'OUT', 'digest', 'read'))


def main():
    document = OUT / 'OPERATION_FACTORY_CONTRACTS.md'
    proofs = {}
    for number, line in enumerate(document.read_text().splitlines(), 1):
        if line.startswith('| API'):
            fields = line.split('|')
            for identifier in re.findall(r'API\d{4}', fields[1]):
                assert identifier not in proofs
                proofs[identifier] = {'line': number, 'contract': fields[2].strip()}
    owners = ['ironwood.compiler.ir.' + name for name in ('IrType', 'IrCallInstruction', 'IrForeignCallInstruction', 'IrFunction', 'IrBasicBlock', 'IrReturnTerminator')]
    owners += ['ironwood.compiler.semantic.RejectedFreeEvidence.' + name for name in ('Budget', 'Limits')]
    selected = [row for row in read('calls') if not row['declaring_owner'].startswith('ironwood.') and
                any(row['consumer'].startswith(owner + '::' + owner.rsplit('.', 1)[-1] + '(') for owner in owners)]
    original = json.loads((OUT / 'qualified/identity.json').read_text())
    install = Path(original['launcher']).parent.parent
    source_hashes = {}
    api = {(r['owner'], r['signature'], r['kind']): r['id'] for r in read('dependencies-discovery')}
    for row in selected:
        data = (install / row['file']).read_bytes()
        assert digest(data) == original['input_sha256'][row['file']]
        source_hashes[row['file']] = digest(data)
        row['literal_source'] = data.decode().encode('utf-16-le')[int(row['start_utf16']) * 2:int(row['end_utf16']) * 2].decode('utf-16-le')
        row['pattern_id'] = api[row['declaring_owner'], row['resolved_signature'], row['kind']]
    assert len(selected) == 51 and {row['pattern_id'] for row in selected} == set(proofs)
    patterns = {}
    for row in selected:
        entry = patterns.setdefault(row['pattern_id'], {'id': row['pattern_id'], 'owner': row['declaring_owner'],
            'signature': row['resolved_signature'], 'kind': row['kind'], 'proof': proofs[row['pattern_id']], 'callers': []})
        entry['callers'].append(row)
    result = {'schema': 1, 'scope': '51 constructor calls/14 patterns: conditional private operation factory data; general source constructors remain later',
        'review_sha256': digest(document.read_bytes()), 'patterns': list(patterns.values()), 'source_sha256': source_hashes,
        'model_sha256': digest((OUT / 'operation-model-schema.json').read_bytes()),
        'factory_source_sha256': digest((ROOT / 'scripts/self-hosting/KernelCapture.java').read_bytes()),
        'limits': 'actual Java resource construction and independent declaration/model evidence; native role rejection/retirement still M1/M2'}
    (OUT / 'operation-factory-reviewed.json.gz').write_bytes(gzip.compress((json.dumps(result, indent=2) + '\n').encode(), mtime=0))
    print('PASS: 51 exact conditional factory calls/14 declarations')


if __name__ == '__main__':
    main()
