#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Verify resource evidence and report overhead without choosing pilot budgets."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import statistics


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('measurement', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('qualification output already exists')
    root = args.measurement.resolve()
    report = json.loads((root / 'measurement.json').read_text())
    corpus = json.loads((root / 'corpus.json').read_text())
    for name, expected in report['adapter_source_sha256'].items():
        assert sha(root / 'tooling' / name) == expected, name
    for name, case in corpus.items():
        source = root / 'sources' / (name + '.iron')
        assert sha(source) == case['sha256'] and len(source.read_bytes()) == case['bytes']
        source.read_bytes().decode('utf-8', errors='strict')
    groups = defaultdict(list)
    failures = []
    for run in report['runs']:
        metrics = run['metrics']
        assert run['returncode'] == 0 and run['rss_bytes'] > 0
        assert metrics['failure'] == '' and metrics['phaseWallNanos'] > 0
        expected = True if metrics['stage'] == 'frontend' else corpus[run['workload']]['accepted']
        assert metrics['successful'] == expected
        if metrics['sampled']:
            assert metrics['samples'] > 0 and metrics['sampledUsedHeapHighWaterBytes'] > 0
            assert metrics['sampledWorkerFrameHighWater'] > 0
        else:
            assert metrics['samples'] == metrics['sampledUsedHeapHighWaterBytes'] == metrics['sampledWorkerFrameHighWater'] == 0
        path = root / 'runs' / run['label']
        for name, expected_sha in run['artifact_sha256'].items():
            assert sha(path / name) == expected_sha, (run['label'], name)
        if 'observer' in run:
            observer = run['observer']
            assert observer['finalBudgetLive'] == 0
            assert observer['summaryRetired'] and observer['fieldRetired']
            assert observer['effectRounds'] > 0 and observer['effectAnalyzers'] > 0
        key = (run['workload'], metrics['stage'], metrics['observed'], metrics['explain'])
        groups[key].append(run)
    expected_groups = {(name, stage, False, False) for name in report['selected'] for stage in ('frontend', 'complete')}
    expected_groups |= {(name, 'complete', True, True) for name in report['selected']
                        if name in ('BranchJoin', 'SlotOrder', 'Ownership128', 'CapturedAliasesUnsafe', 'Loops128')}
    assert set(groups) == expected_groups
    qualifications = []
    for key, runs in groups.items():
        assert len(runs) == 2 * report['repeat']
        assert sum(run['metrics']['sampled'] for run in runs) == report['repeat']
        # Only telemetry/time streams vary; these source/semantic artifact bytes must match.
        artifacts = ('library-inputs.json', 'diagnostics.json', 'tokens.json', 'ast.json',
                     'program-counts.json', 'output.ll', 'observer.json')
        mismatches = []
        for name in artifacts:
            values = [run['artifact_sha256'].get(name) for run in runs]
            if len(set(values)) != 1:
                mismatches.append(name)
                failures.append({'group': list(key), 'artifact': name,
                                 'runs': [run['label'] for run in runs], 'sha256': values})
        def median(field, sampled):
            return statistics.median(run[field] for run in runs if run['metrics']['sampled'] == sampled)
        sampled = [run for run in runs if run['metrics']['sampled']]
        qualifications.append({'workload': key[0], 'stage': key[1], 'observed': key[2], 'explain': key[3],
                               'runs': len(runs), 'semantic_artifacts_identical': not mismatches,
                               'mismatched_artifacts': mismatches,
                               'sampling_on_off_wall_ratio': median('fresh_process_wall_seconds', True) / median('fresh_process_wall_seconds', False),
                               'sampling_on_off_rss_ratio': median('rss_bytes', True) / median('rss_bytes', False),
                               'maximum_wall_seconds': max(run['fresh_process_wall_seconds'] for run in runs),
                               'maximum_rss_bytes': max(run['rss_bytes'] for run in runs),
                               'sampled_heap_lower_bound_bytes': max(run['metrics']['sampledUsedHeapHighWaterBytes'] for run in sampled),
                               'sampled_frame_lower_bound': max(run['metrics']['sampledWorkerFrameHighWater'] for run in sampled),
                               'maximum_sample_gap_nanos': max(run['metrics']['maximumSampleGapNanos'] for run in sampled),
                               'error_count': runs[0]['metrics']['errorCount'], 'warning_count': runs[0]['metrics']['warningCount']})
    observation = []
    for key, observed in groups.items():
        if not key[2]: continue
        plain = groups[(key[0], key[1], False, False)]
        def primary(run):
            values = json.loads((root / 'runs' / run['label'] / 'diagnostics.json').read_text())
            return [{name: value for name, value in item.items() if name != 'notes'} for item in values]
        assert primary(observed[0]) == primary(plain[0]), key
        assert observed[0]['artifact_sha256'].get('output.ll') == plain[0]['artifact_sha256'].get('output.ll'), key
        for sampled in (False, True):
            enabled = [run for run in observed if run['metrics']['sampled'] == sampled]
            disabled = [run for run in plain if run['metrics']['sampled'] == sampled]
            observation.append({'workload': key[0], 'sampled': sampled,
                                'observer_and_explanation_on_off_wall_ratio': statistics.median(run['fresh_process_wall_seconds'] for run in enabled) / statistics.median(run['fresh_process_wall_seconds'] for run in disabled),
                                'observer_and_explanation_on_off_rss_ratio': statistics.median(run['rss_bytes'] for run in enabled) / statistics.median(run['rss_bytes'] for run in disabled),
                                'primary_verdict_sequence_and_llvm_identical': True,
                                'counters': enabled[0]['observer']})
    outcome = {'schema': 1, 'measurement_sha256': sha(root / 'measurement.json'),
               'J0_seed_sha256': report['J0_seed_sha256'], 'profile': report['profile'],
               'run_count': len(report['runs']), 'strict_utf8_sources': len(corpus),
               'qualification_passed': not failures, 'failures': failures,
               'sampling_and_repeat_checks': qualifications, 'observation_checks': observation,
               'limits': 'sampled maxima are lower bounds; observer and explain enabled together, so their separate overheads are not identified; stack bytes unmeasured; budgets not selected'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(outcome, indent=2) + '\n')
    if failures:
        raise ValueError('semantic evidence differs; qualification report retains exact groups/hashes; do not normalize differences')
    print('PASS:', len(report['runs']), 'fresh outcomes, repeated/configuration artifact bytes, primary observer verdicts, cleanup counters and overhead records')


if __name__ == '__main__':
    main()
