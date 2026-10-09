#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Turn M2.2 native kernel runs into budget records and check each one.

Each run from measure-m2-kernels.py becomes a check-pilot-budget.py record in
its ownership, evidence or effect group. The outstanding-temporary field is
the live-allocation difference after retirement minus the derived D262
invocation-lived payload; it must be zero. A run whose result differs from J0
fails here as well. Mandatory build safety errors are counted from the pilot's
compile and link logs.
"""
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    runs_path, compile_log, link_log, output = map(Path, sys.argv[1:5])
    spec = importlib.util.spec_from_file_location('budget', ROOT / 'scripts/self-hosting/check-pilot-budget.py')
    budget = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(budget)
    policy = json.loads((ROOT / 'docs/self-hosting/m0/pilot-budgets.json').read_text())
    safety_errors = sum(line.startswith('error') for path in (compile_log, link_log)
                        for line in path.read_text().splitlines())
    records = []
    for run in json.loads(runs_path.read_text())['runs']:
        record = {'group': run['group'], 'profile': run['profile'], 'exit_status': run['exit_status'],
                  'soft_stack_limit_kib': run['soft_stack_limit_kib'], 'hard_stack_limit_kib': run['hard_stack_limit_kib'],
                  'rss_kind': run['rss_kind'], 'fresh_wall_seconds': run['fresh_wall_seconds'], 'rss_bytes': run['rss_bytes'],
                  'phase_seconds': run.get('phase_seconds'),
                  'retained_after_retirement': run.get('retained_after_retirement'),
                  'invocation_lived': run.get('invocation_lived'),
                  'temporary_outstanding_after_retirement': run.get('temporary_outstanding_after_retirement', -1),
                  'mandatory_build_safety_errors': safety_errors,
                  'temporary_accounting': 'native_allocator_observation',
                  'result_matches_j0': run['result_matches_j0'], 'case': run['case'], 'repeat': run['repeat']}
        try:
            budget.check(record, policy)
            if not run['result_matches_j0']:
                raise ValueError('result differs from J0')
            record['budget_check'] = 'PASS'
        except ValueError as failure:
            record['budget_check'] = 'FAIL: ' + str(failure)
        records.append(record)
    output.write_text(json.dumps(records, indent=1) + '\n')
    failed = [r for r in records if r['budget_check'] != 'PASS']
    for group in ('ownership', 'evidence', 'effect'):
        selected = [r for r in records if r['group'] == group]
        print(group, 'records', len(selected), 'max_wall', max(r['fresh_wall_seconds'] for r in selected),
              'max_rss', max(r['rss_bytes'] or 0 for r in selected),
              'max_phase', max(r['phase_seconds'] or 0 for r in selected))
    print('records', len(records), 'failed', len(failed))
    for record in failed:
        print(record['case'], record['repeat'], record['budget_check'])
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
