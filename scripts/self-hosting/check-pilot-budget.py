#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check one M2 resource record; numeric success does not qualify other gates."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def check(record, policy):
    if type(record) is not dict:
        raise ValueError('resource record must be an object')
    group = record.get('group')
    if group not in policy['budget_decision']:
        raise ValueError('unknown workload group')
    if record.get('profile') != policy['profile'] or type(record.get('exit_status')) is not int or record['exit_status'] != 0:
        raise ValueError('profile mismatch or unsuccessful process')
    for name in ('soft_stack_limit_kib', 'hard_stack_limit_kib'):
        if type(record.get(name)) is not int or record[name] != policy['native_main_stack_limit_kib']:
            raise ValueError('stack capacity must match the fixed external limit')
    if record.get('rss_kind') != 'kernel_process_peak_bytes':
        raise ValueError('RSS must be process rusage peak bytes')
    for name in ('temporary_outstanding_after_retirement', 'mandatory_build_safety_errors'):
        if type(record.get(name)) is not int or record[name] != 0:
            raise ValueError('missing retirement/safety measurement or nonzero result')
    if record.get('temporary_accounting') != 'native_allocator_observation':
        raise ValueError('association units cannot replace allocator observation')
    for name, limit in policy['budget_decision'][group]['budget'].items():
        value = record.get(name)
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise ValueError('missing/nonfinite/nonpositive metric: ' + name)
        if name == 'rss_bytes' and type(value) is not int:
            raise ValueError('RSS bytes must be an integer')
        if value > limit:
            raise ValueError('budget exceeded: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record', type=Path)
    parser.add_argument('--self-check-output', type=Path)
    args = parser.parse_args()
    if bool(args.record) == bool(args.self_check_output):
        raise ValueError('select one record or synthetic self-check')
    policy_path = ROOT / 'docs/self-hosting/m0/pilot-budgets.json'
    policy = json.loads(policy_path.read_text())
    if args.record:
        check(json.loads(args.record.read_text()), policy)
        print('PASS: numeric resource bounds only; semantic/model/lifetime/repeat gates remain separate')
        return
    if args.self_check_output.exists(): raise ValueError('preserve controls')
    base = {'group': 'ownership', 'profile': policy['profile'], 'exit_status': 0,
            'soft_stack_limit_kib': 8176, 'hard_stack_limit_kib': 8176,
            'rss_kind': 'kernel_process_peak_bytes', 'fresh_wall_seconds': .1,
            'rss_bytes': 100_000_000, 'phase_seconds': .02,
            'temporary_outstanding_after_retirement': 0, 'mandatory_build_safety_errors': 0,
            'temporary_accounting': 'native_allocator_observation', 'synthetic': True}
    positives = []
    for group in policy['budget_decision']:
        record = {**base, 'group': group}
        check(record, policy); positives.append(group)
    negatives = []
    for name, changes in (
        ('wall', {'fresh_wall_seconds': 1.01}), ('RSS', {'rss_bytes': 536_870_913}),
        ('phase', {'phase_seconds': .251}), ('NaN', {'fresh_wall_seconds': float('nan')}),
        ('missing-RSS', {'rss_bytes': None}), ('boolean-RSS', {'rss_bytes': True}),
        ('stack', {'hard_stack_limit_kib': 8192}), ('retirement', {'temporary_outstanding_after_retirement': 1}),
        ('safety', {'mandatory_build_safety_errors': 1}), ('sampled-RSS', {'rss_kind': 'sampled_peak'}),
        ('logical-units', {'temporary_accounting': 'optional_budget_units'}), ('failed-process', {'exit_status': 1})):
        try: check({**base, **changes}, policy)
        except ValueError as error: negatives.append({'name': name, 'error': str(error)})
        else: raise ValueError('accepted bad synthetic resource record')
    args.self_check_output.parent.mkdir(parents=True, exist_ok=True)
    args.self_check_output.write_text(json.dumps({'synthetic_only': True,
        'no_native_evaluation': True, 'positive_groups': positives, 'negative_controls': negatives,
        'policy_sha256': hashlib.sha256(policy_path.read_bytes()).hexdigest(),
        'tool_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}, indent=2) + '\n')
    print('PASS: four synthetic categories and twelve rejected resource/method controls; no native evaluation')


if __name__ == '__main__':
    main()
