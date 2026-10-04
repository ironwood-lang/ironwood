#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Measure and qualify exact seed kernels in serial fresh macOS JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def numbers(path):
    return {key: int(value) for key, value in
            (line.split('=', 1) for line in path.read_text().splitlines())}


def qualify(out):
    report = json.loads((out / 'measurement.json').read_text())
    if report['repeat'] < 2:
        raise ValueError('qualification requires fresh repeats')
    if sha((out / 'identity.json').read_bytes()) != report['identity_sha256']:
        raise ValueError('identity changed')
    identity = json.loads((out / 'identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    if sha((install / 'lib/ironwoodc.jar').read_bytes()) != report['seed_sha256']:
        raise ValueError('seed changed')
    for name, digest in report['tool_sha256'].items():
        if sha((out / 'tooling' / name).read_bytes()) != digest:
            raise ValueError('tool changed: ' + name)
    expected = {(kernel, size, width, sampled, observed, repeat)
                for kernel, size, width in report['cases']
                for sampled in (False, True)
                for observed in ((False, True) if kernel == 'effect' else (False,))
                for repeat in range(report['repeat'])}
    actual = set()
    results = {}
    times = {}
    for run in report['runs']:
        key = tuple(run[name] for name in ('kernel', 'size', 'width', 'sampled', 'observed', 'repeat'))
        if key in actual or key not in expected:
            raise ValueError('duplicate or unknown run')
        actual.add(key)
        directory = out / run['label']
        for name, digest in run['artifact_sha256'].items():
            if sha((directory / name).read_bytes()) != digest:
                raise ValueError('artifact changed: ' + run['label'] + '/' + name)
        if run['returncode'] or not run['rss_bytes'] or not run['time_real_user_sys_seconds']:
            raise ValueError('failed measurement')
        metrics = numbers(directory / 'metrics.txt')
        if metrics != run['metrics'] or any(metrics[name] <= 0 for name in
                ('constructionNanos', 'phaseNanos', 'verificationNanos', 'heapAtStartBytes')):
            raise ValueError('invalid metrics')
        sampled_names = ('samples', 'sampledHeapHighBytes', 'sampledFramesHigh', 'maximumSampleGapNanos')
        if run['sampled']:
            if any(metrics[name] <= 0 for name in sampled_names):
                raise ValueError('sampling missed measured operation')
        elif any(metrics[name] != 0 for name in sampled_names):
            raise ValueError('unsampled values must be unmeasured sentinels')
        expected_rounds = run['size'] + 1 if run['observed'] else 0
        if metrics['observerRounds'] != expected_rounds:
            raise ValueError('retained observer round mismatch')
        group = key[:3]
        digest = run['artifact_sha256']['result.txt']
        if results.setdefault(group, digest) != digest:
            raise ValueError('exact result changed with repetition, sampling or observer')
        if run['kernel'] == 'evidence':
            result = numbers(directory / 'result.txt')
            if result['finalBudgetLive'] != 0 or result['exactIntersections'] != 64 \
                    or result['associations'] != 64 * 2 * run['size'] * 6:
                raise ValueError('snapshot result/retirement mismatch')
        times.setdefault(key[:5], []).append(run['fresh_process_wall_seconds'])
    if actual != expected:
        raise ValueError('missing selected configurations or repeats')
    ratios = []
    for (kernel, size, width, sampled, observed), values in times.items():
        if sampled:
            ratios.append({'kernel': kernel, 'size': size, 'width': width, 'observed': observed,
                           'sampled_over_unsampled_wall_medians': statistics.median(values)
                           / statistics.median(times[(kernel, size, width, False, observed)])})
    result = {'qualification_passed': True, 'baseline_label': report['baseline_label'],
              'seed_sha256': report['seed_sha256'], 'runs': len(actual), 'sampling_ratios': ratios,
              'maximum_process_wall_seconds': max(run['fresh_process_wall_seconds'] for run in report['runs']),
              'maximum_rss_bytes': max(run['rss_bytes'] for run in report['runs']),
              'maximum_sampled_heap_bytes': max(run['metrics']['sampledHeapHighBytes'] for run in report['runs']),
              'maximum_sampled_frames': max(run['metrics']['sampledFramesHigh'] for run in report['runs']),
              'maximum_sample_gap_nanos': max(run['metrics']['maximumSampleGapNanos'] for run in report['runs']),
              'limits': ['fresh-process cost includes setup, checks and serialization',
                         'phase includes the adapter control/check calls, not only seed instructions',
                         'evidence kernel is optional explanation storage, not ownership proof snapshots',
                         'heap sampling is used heap, not live-object accounting; stack frames are sampled/capped',
                         'no forced GC, native pilot measurement or budget decision']}
    (out / 'qualification.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS:', len(actual), 'fresh kernel runs; exact results and cleanup')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--identity', type=Path, default=ROOT / 'docs/self-hosting/m0/ordered/qualified-identity.json')
    parser.add_argument('--baseline-label', default='J0-D247')
    parser.add_argument('--repeat', type=int, default=2)
    parser.add_argument('--qualify-only', action='store_true')
    args = parser.parse_args()
    out = args.output.resolve()
    if args.qualify_only:
        qualify(out)
        return
    if out.exists() or args.repeat < 2:
        raise ValueError('use a new destination and at least two repeats')
    raw_identity = args.identity.read_bytes()
    identity = json.loads(raw_identity)
    original = identity['revision'] == '6bde84df320e9dbc32977a2b665d214ac09bc2d4'
    if (args.baseline_label == 'original-J0') != original:
        raise ValueError('baseline label must distinguish original and ordered seeds')
    install = Path(identity['launcher']).parent.parent
    jar = install / 'lib/ironwoodc.jar'
    if sha(jar.read_bytes()) != identity['seed_jar_sha256']:
        raise ValueError('seed hash mismatch')
    out.mkdir(parents=True)
    (out / 'identity.json').write_bytes(raw_identity)
    tooling = out / 'tooling'; tooling.mkdir()
    source = tooling / 'KernelCapture.java'
    source.write_bytes((ROOT / 'scripts/self-hosting/KernelCapture.java').read_bytes())
    runner = tooling / 'measure-kernels.py'
    runner.write_bytes(Path(__file__).read_bytes())
    env = dict(os.environ)
    cleared = ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
    for name in cleared:
        env.pop(name, None)
    env['IRONWOOD_STDLIB_HOME'] = str(install)
    javac = [identity['jdk'] + '/bin/javac', '--release', '21', '-encoding', 'UTF-8',
             '-Xlint:all', '-Werror', '-cp', str(jar), '-d', str(tooling), str(source)]
    process = subprocess.run(javac, cwd=ROOT, env=env, capture_output=True)
    (tooling / 'javac.stdout.txt').write_bytes(process.stdout)
    (tooling / 'javac.stderr.txt').write_bytes(process.stderr)
    cases = [('evidence', size, 1) for size in (8, 32, 128)]
    cases += [('effect', size, 65) for size in (8, 32, 128)]
    cases += [('effect', 16, width) for width in (8, 257)]
    report = {'schema': 1, 'baseline_label': args.baseline_label, 'revision': identity['revision'],
              'seed_sha256': identity['seed_jar_sha256'], 'identity_sha256': sha(raw_identity),
              'profile': identity['profile'], 'hardware': identity['hardware'], 'platform': identity['platform'],
              'cleared_environment': list(cleared), 'library_home': str(install),
              'tool_sha256': {path.name: sha(path.read_bytes()) for path in (source, runner)},
              'javac': {'argv': javac, 'returncode': process.returncode}, 'cases': cases,
              'repeat': args.repeat, 'runs': []}
    def checkpoint():
        (out / 'measurement.json').write_text(json.dumps(report, indent=2) + '\n')
    checkpoint()
    if process.returncode:
        raise ValueError('adapter compilation failed; diagnostics retained')
    for kernel, size, width in cases:
        for observed in ((False, True) if kernel == 'effect' else (False,)):
            for sampled in (False, True):
                for repeat in range(args.repeat):
                    label = f'{kernel}-{size}-{width}-observer{int(observed)}-sample{int(sampled)}-r{repeat}'
                    destination = out / label; destination.mkdir()
                    argv = ['/usr/bin/time', '-l', identity['jdk'] + '/bin/java', *identity['profile'],
                            '-cp', str(tooling) + os.pathsep + str(jar),
                            'ironwood.compiler.semantic.KernelCapture', kernel, str(size), str(width),
                            'sample-on' if sampled else 'sample-off',
                            'observer-on' if observed else 'observer-off', str(destination)]
                    began = time.monotonic()
                    process = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True)
                    wall = time.monotonic() - began
                    (destination / 'stdout.txt').write_bytes(process.stdout)
                    (destination / 'stderr.txt').write_bytes(process.stderr)
                    raw = process.stderr.decode(errors='replace')
                    rss = re.search(r'^\s*(\d+)\s+maximum resident set size\s*$', raw, re.MULTILINE)
                    timing = re.search(r'(\d+(?:\.\d+)?) real\s+(\d+(?:\.\d+)?) user\s+(\d+(?:\.\d+)?) sys', raw)
                    run = {'label': label, 'kernel': kernel, 'size': size, 'width': width,
                           'sampled': sampled, 'observed': observed, 'repeat': repeat, 'argv': argv,
                           'returncode': process.returncode, 'fresh_process_wall_seconds': wall,
                           'rss_bytes': int(rss.group(1)) if rss else None,
                           'time_real_user_sys_seconds': list(map(float, timing.groups())) if timing else None,
                           'metrics': numbers(destination / 'metrics.txt') if (destination / 'metrics.txt').exists() else {},
                           'artifact_sha256': {path.name: sha(path.read_bytes()) for path in sorted(destination.iterdir())}}
                    report['runs'].append(run); checkpoint()
                    print(label, 'exit=' + str(process.returncode), 'wall=' + format(wall, '.3f'), flush=True)
                    if process.returncode:
                        raise ValueError('kernel failed; exact command and output retained')
    qualify(out)


if __name__ == '__main__':
    main()
