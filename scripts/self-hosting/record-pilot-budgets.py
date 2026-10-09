#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Derive fixed M2 budget headroom from frozen Java measurements only."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'docs/self-hosting/m0'
POLICY = {
    'frontend': {'fresh_wall_seconds': 2.0, 'rss_bytes': 1_073_741_824},
    'ownership': {'fresh_wall_seconds': 1.0, 'rss_bytes': 536_870_912, 'phase_seconds': .25},
    'evidence': {'fresh_wall_seconds': 1.0, 'rss_bytes': 536_870_912, 'phase_seconds': .25},
    'effect': {'fresh_wall_seconds': 1.0, 'rss_bytes': 536_870_912, 'phase_seconds': .5},
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists(): raise ValueError('preserve fixed budget record')
    selection = {
        'resources-original': ['initial/measurement.json', 'strict/measurement.json'],
        'kernel-resources': ['original/measurement.json', 'ordered/measurement.json'],
        'ownership-resources': ['ordered/measurement.json'],
        'loop-cycle-reference': ['resources/measurement.json', 'cycle-original/measurement.json', 'cycle-ordered/measurement.json'],
    }
    groups = {name: [] for name in POLICY}
    references = []
    excluded_complete = 0
    for archive, members in selection.items():
        path = EVIDENCE / (archive + '.tar.gz')
        manifest = json.loads((EVIDENCE / (archive + '-manifest.json')).read_text())
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest == manifest['archive_sha256']
        with tarfile.open(path, 'r:gz') as stream:
            for member in members:
                data = stream.extractfile(member).read()
                expected = manifest['files'][member]
                assert len(data) == expected['bytes'] and hashlib.sha256(data).hexdigest() == expected['sha256']
                report = json.loads(data)
                assert report['repeat'] >= 2
                references.append({'archive': path.name, 'archive_sha256': digest,
                                   'member': member, 'member_sha256': expected['sha256']})
                for run in report['runs']:
                    assert run['returncode'] == 0
                    if 'kernel' in run:
                        group = 'effect' if run['kernel'] in ('effect', 'effect-cycle') else run['kernel']
                    elif run['metrics']['stage'] == 'frontend': group = 'frontend'
                    else:
                        excluded_complete += 1
                        continue
                    groups[group].append({'fresh_wall_seconds': run['fresh_process_wall_seconds'],
                        'rss_bytes': run['rss_bytes'], 'phase_seconds': run['metrics'].get('phaseNanos',
                            run['metrics'].get('phaseWallNanos')) / 1e9,
                        'reference': archive + '/' + member, 'label': run['label']})
    result = {}
    for group, runs in groups.items():
        assert runs
        maxima = {name: max(run[name] for run in runs) for name in POLICY[group]}
        result[group] = {'budget': POLICY[group], 'reference_runs': len(runs),
            'measured_java_maxima': maxima,
            'budget_over_java_maximum': {name: POLICY[group][name] / maximum for name, maximum in maxima.items()},
            'maximum_labels': {name: next(run for run in runs if run[name] == maximum) for name, maximum in maxima.items()}}
    report = {'schema': 1, 'fixed_before_native_evaluation': True,
        'tool_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'profile': 'macOS arm64 Apple M5, 32 GiB, qualified pinned JDK/LLVM identities',
        'budget_decision': result, 'references': references,
        'native_main_stack_limit_kib': 8176, 'temporary_outstanding_after_retirement': 0,
        'mandatory_safety_errors_allowed': 0, 'fresh_repeats_minimum': 2,
        'excluded_complete_compiler_runs': excluded_complete,
        'scope': 'M2 finite frontend/operation pilots only; no native run evaluated, no S3/S4 self-build capacity or resident budget',
        'limits': ['RSS is process rusage, not sampled heap/live objects; sampled frame counts are not stack bytes',
                   'main stack capacity must be externally limited; native high-water/depth remains separate evidence',
                   'source/AST/diagnostic outputs can survive an invocation; backing temporaries retire at their proved boundary',
                   'allocation-event and retained-byte measurements must distinguish actual bytes from association accounting units']}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('RECORDED:', {name: len(runs) for name, runs in groups.items()}, '; fixed numerical budgets, no native evaluation')


if __name__ == '__main__':
    main()
