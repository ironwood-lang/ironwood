#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Reconcile S0's retained baseline, controls, budgets and finite M1 handoff."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read(name):
    return json.loads((OUT / name).read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scratch', type=Path, required=True, help='new ignored scratch path for controls')
    args = parser.parse_args()
    scratch = args.scratch.resolve()
    assert scratch.is_relative_to(ROOT / 'target') and not scratch.exists()
    scratch.mkdir(parents=True)
    original, ordered = read('qualified/identity.json'), read('ordered/qualified-identity.json')
    assert original['revision'] == '6bde84df320e9dbc32977a2b665d214ac09bc2d4'
    assert ordered['revision'] == '28664736270f19a73c0a79a2f845bac916953471'
    assert original['profile'] == ordered['profile'] == ['-Xms256m', '-Xmx4096m', '-Xss8m', '-XX:+UseG1GC']
    assert original['stdlib_archive_sha256'] == ordered['stdlib_archive_sha256']
    inputs = [n for n in original['input_sha256'] if n.startswith('compiler/src/main/java/') and n.endswith('.java')]
    assert len(inputs) == 460
    assert [n for n in inputs if original['input_sha256'][n] != ordered['input_sha256'][n]] == ['compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java']
    for identity in (original, ordered):
        install = Path(identity['launcher']).parent.parent
        assert digest((install / 'lib/ironwoodc.jar').read_bytes()) == identity['seed_jar_sha256']
        assert digest((install / 'lib/ironwood-stdlib.ironjar').read_bytes()) == identity['stdlib_archive_sha256']
        for name in inputs: assert digest((install / name).read_bytes()) == identity['input_sha256'][name]
    archive_records = {}
    for manifest_path in sorted(OUT.rglob('*-manifest.json')):
        archive = manifest_path.with_name(manifest_path.name.removesuffix('-manifest.json') + '.tar.gz')
        if not archive.exists(): continue
        manifest = json.loads(manifest_path.read_text())
        assert digest(archive.read_bytes()) == manifest['archive_sha256']
        actual = {}
        with tarfile.open(archive, 'r:gz') as stream:
            for member in stream:
                assert member.isfile() and member.name not in actual
                data = stream.extractfile(member).read()
                actual[member.name] = {'sha256': digest(data), 'bytes': len(data)}
                if archive.name == 'original-captures.tar.gz' and member.name.startswith(('BranchJoin-final/', 'SlotOrder-final/')):
                    path = scratch / 'references' / member.name
                    assert path.resolve().is_relative_to(scratch)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
        assert actual == manifest['files']
        archive_records[archive.relative_to(OUT).as_posix()] = {'sha256': manifest['archive_sha256'], 'members': len(actual)}
    assert len(archive_records) == 10
    commands = [
        [sys.executable, str(ROOT / 'scripts/self-hosting/compare.py'), 'selftest',
         str(scratch / 'references/BranchJoin-final'), str(scratch / 'references/SlotOrder-final'), str(scratch / 'mismatches')],
        [sys.executable, str(ROOT / 'scripts/self-hosting/record-pilot-budgets.py'), '--output', str(scratch / 'budgets.json')],
        [sys.executable, str(ROOT / 'scripts/self-hosting/check-pilot-budget.py'), '--self-check-output', str(scratch / 'budget-controls.json')],
    ]
    statuses = []
    for n, argv in enumerate(commands):
        process = subprocess.run(argv, cwd=ROOT, capture_output=True)
        (scratch / (str(n) + '.stdout')).write_bytes(process.stdout)
        (scratch / (str(n) + '.stderr')).write_bytes(process.stderr)
        statuses.append({'argv': argv, 'returncode': process.returncode, 'stdout_sha256': digest(process.stdout), 'stderr_sha256': digest(process.stderr)})
        assert process.returncode == 0, process.stderr.decode()
    (scratch / 'commands.json').write_text(json.dumps(statuses, indent=2) + '\n')
    assert (scratch / 'mismatches/qualification.json').read_bytes() == (OUT / 'comparison-qualification.json').read_bytes()
    assert (scratch / 'budgets.json').read_bytes() == (OUT / 'pilot-budgets.json').read_bytes()
    assert (scratch / 'budget-controls.json').read_bytes() == (OUT / 'pilot-budget-controls.json').read_bytes()
    policy = read('pilot-budgets.json')
    assert policy['fixed_before_native_evaluation'] and policy['native_main_stack_limit_kib'] == 8176
    assert {k: r['reference_runs'] for k, r in policy['budget_decision'].items()} == {'frontend': 88, 'ownership': 96, 'evidence': 24, 'effect': 160}
    controls = read('pilot-budget-controls.json')
    assert controls['synthetic_only'] and controls['no_native_evaluation'] and len(controls['negative_controls']) == 12
    handoff = json.loads(gzip.decompress((OUT / 'pilot-handoff.json.gz').read_bytes()))
    for name, sha in handoff['references'].items(): assert digest((OUT / name).read_bytes()) == sha
    assert handoff['native_implementation'] is False and handoff['native_pilot_evaluation'] is False
    assert len(handoff['calls']) == 1330 and len(handoff['syntax']) == 723 and len(handoff['captures']) == 65 and len(handoff['hash_contributions']) == 446
    assert len({r['id'] for r in handoff['calls']}) == 1330
    assert set(handoff['phase_call_ids']) == {'M1.1', 'M1.2', 'M1.3'}
    assert handoff['model_declarations'] == [119, 233]
    assert read('model-probe/qualification.json')['model_count'] == 119
    operation = read('operation-model-probe/qualification.json')
    assert operation['qualification_passed'] and operation['declarations'] == 233 and len(operation['negative_controls']) == 5
    deferred = read('deferred/qualification.json')
    assert len(deferred['negative_controls']) == 5 and deferred['selected_external_calls'] == 1330
    assert deferred['manifest_sha256'] == digest((OUT / 'deferred/manifest.json').read_bytes())
    refs = ['qualified/identity.json', 'qualified/jvm-effective.json', 'ordered/qualified-identity.json',
            'comparison-qualification.json', 'reference-access-qualification.json', 'deferred/manifest.json',
            'deferred/qualification.json', 'pilot-handoff.json.gz', 'pilot-budgets.json', 'pilot-budget-controls.json',
            'model-probe/qualification.json', 'operation-model-probe/qualification.json', 'REPLAY.md', 'PILOT_HANDOFF.md']
    result = {'schema': 1, 'checkpoint': 'M0.1/M0.2/M0.3 and S0', 'passed': True,
              'scope': 'qualified macOS arm64 M5/32-GiB Java baseline and preparation handoff only',
              'references': {n: digest((OUT / n).read_bytes()) for n in refs}, 'archives': archive_records,
              'comparison_controls': read('comparison-qualification.json'), 'source_files': 460,
              'selected_counts': {'calls': 1330, 'syntax': 723, 'captures': 65, 'hash_contributions': 446},
              'phase_call_counts': {k: len(v) for k, v in handoff['phase_call_ids'].items()},
              'tool_sha256': digest(Path(__file__).read_bytes()), 'control_commands': statuses,
              'native_pilot_implemented': False, 'native_pilot_evaluated': False,
              'remaining': 'all source-backed later unresolved contracts block their named consumer; native M1/M2 allocation/safety/resource/input gates remain required',
              'next_permitted_scope': 'M1.1/M1.2/M1.3 exact B1/B7 prerequisites in PILOT_HANDOFF.md; no selected B2 sort or broad translation'}
    (OUT / 's0-qualification.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: S0 retained archive integrity, real mismatch controls, fixed budget replay and exact M1 handoff')


if __name__ == '__main__':
    main()
