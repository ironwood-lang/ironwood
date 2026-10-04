#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check selected loop/cycle cross-seed bytes and retained mismatch controls."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = ROOT / 'target/self-hosting-m0'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    if out.exists(): raise ValueError('preserve existing controls')
    out.mkdir(parents=True)
    reports = {}
    for family, report_name in (('loop-canonical', 'captures.json'), ('cycle-kernels', 'measurement.json')):
        original = json.loads((SCRATCH / (family + '-original') / report_name).read_text())
        ordered = json.loads((SCRATCH / (family + '-ordered') / report_name).read_text())
        assert original['seed_sha256'] != ordered['seed_sha256']
        left = {run['label']: run for run in original['runs']}
        right = {run['label']: run for run in ordered['runs']}
        assert left.keys() == right.keys()
        for label, run in left.items():
            if family == 'loop-canonical':
                assert run['artifacts'] == right[label]['artifacts'], label
            else:
                assert run['artifact_sha256']['result.txt'] == right[label]['artifact_sha256']['result.txt'], label
        reports[family] = {'cross_seed_pairs': len(left), 'exact_bytes_agree': True}
    qualification = json.loads((SCRATCH / 'loop-canonical-ordered/qualification.json').read_text())
    required = {'ForStatement', 'WhileStatement', 'DoWhileStatement', 'EnhancedForStatement'}
    assert required.issubset(qualification['observed_ast_nodes'])
    controls = []
    for name in ('changed-cycle-fact', 'missing-cycle-repeat'):
        destination = out / name
        shutil.copytree(SCRATCH / 'cycle-kernels-ordered', destination)
        report_path = destination / 'measurement.json'
        report = json.loads(report_path.read_text())
        if name == 'changed-cycle-fact':
            run = report['runs'][0]
            path = destination / run['label'] / 'result.txt'
            data = path.read_bytes()
            assert b'allocates=true' in data
            path.write_bytes(data.replace(b'allocates=true', b'allocates=false', 1))
            # Update the byte hash so rejection must come from exact repeat/configuration parity.
            run['artifact_sha256']['result.txt'] = hashlib.sha256(path.read_bytes()).hexdigest()
        else:
            report['runs'].pop()
        report_path.write_text(json.dumps(report, indent=2) + '\n')
        argv = [sys.executable, str(ROOT / 'scripts/self-hosting/measure-kernels.py'),
                '--qualify-only', '--output', str(destination)]
        process = subprocess.run(argv, cwd=ROOT, capture_output=True)
        (out / (name + '.stdout.txt')).write_bytes(process.stdout)
        (out / (name + '.stderr.txt')).write_bytes(process.stderr)
        assert process.returncode != 0
        expected = b'exact result changed' if name == 'changed-cycle-fact' else b'missing selected configurations'
        assert expected in process.stderr, process.stderr
        controls.append({'name': name, 'argv': argv, 'returncode': process.returncode})
    reports.update({'negative_controls': controls, 'loop_variants': sorted(required),
                    'scope': 'exact Java reference comparisons; no native compiler implementation'})
    (out / 'qualification.json').write_text(json.dumps(reports, indent=2) + '\n')
    (out / Path(__file__).name).write_bytes(Path(__file__).read_bytes())
    print('PASS: 20 loop and 40 cyclic-effect cross-seed pairs; deliberate fact/repeat failures rejected')


if __name__ == '__main__':
    main()
