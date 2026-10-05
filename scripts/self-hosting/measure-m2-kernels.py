#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Fresh-process runs and stack bounds for the M2.2 native kernel pilot.

`resources` runs every selected configuration of KernelCapture (ownership
8/32/128 x shapes 1-4 x explanations off/on, evidence 8/32/128, effect chains
and cycles 8/32/128 x 65 and 16 x 8/257 x observer off/on) under an 8,176-KiB
soft and hard main-stack limit set before exec, inside /usr/bin/time -l, with
the runner's monotonic wall time and a kill at the 1-second kernel cap. Each
result.txt is compared byte for byte with the retained J0 result. Live
allocations left after retirement are split into the D262 invocation-lived
payload, derived per kernel in OWNERSHIP.md, and outstanding temporaries,
which must be zero.

`stack` bisects external stack limits per configuration: the smallest limit
at which the unchanged binary exits 0 with the same result, and the next
smaller limit tried, which fails. No bookkeeping is added to the binary.

`scale` (M2.3) doubles each kernel's size from 128: ownership to 4,096 nodes,
evidence and effects to 1,024 nodes or functions, two fresh repeats each,
under the same launcher and a 10-second runner timeout. Sizes above 128 have
no J0 reference; the kernel's own contract checks still run, and the same
retirement split applies.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

STACK_KIB = 8176
WALL_CAP = 1.0
PROFILE = 'macOS arm64 Apple M5, 32 GiB, qualified pinned JDK/LLVM identities'
ITERATIONS = 64
# Per ownership iteration: setState, setDetached, two joined states and three
# blocks (seven state versions); the replacement and the joined child version;
# with recorded explanations, five selected-reason events (counted by the kernel).
STATE_VERSIONS = 7
CHILD_VERSIONS = 2
EVENTS = 5


def configurations():
    result = []
    for size in (8, 32, 128):
        for shape in (1, 2, 3, 4):
            for explain in (0, 1):
                result.append(('ownership', 'ownership', size, shape, 'explain-' + ('on' if explain else 'off'),
                               'ownership-%d-%d-observer%d' % (size, shape, explain)))
    for size in (8, 32, 128):
        result.append(('evidence', 'evidence', size, 1, 'observer-off', 'evidence-%d-1-observer0' % size))
    for kind in ('effect', 'effect-cycle'):
        for size, width in ((8, 65), (32, 65), (128, 65), (16, 8), (16, 257)):
            for observed in (0, 1):
                result.append(('effect', kind, size, width, 'observer-' + ('on' if observed else 'off'),
                               '%s-%d-%d-observer%d' % (kind, size, width, observed)))
    return result


def metrics(path):
    return {key: int(value) for key, value in (line.split('=', 1) for line in path.read_text().splitlines())}


def expected_events(kind, shape, flag):
    # Every join records five events unless explanations are off or the tight
    # budget stopped during setup; a large kernel can exhaust the ample budget.
    return ITERATIONS * EVENTS if kind == 'ownership' and flag == 'explain-on' and shape < 3 else 0


def invocation_lived(kind, shape, flag, values):
    if kind == 'ownership':
        return ITERATIONS * (STATE_VERSIONS + CHILD_VERSIONS * values['childVersionAllocations']) \
            + values['recordedEvents'] + 1
    if kind == 'evidence':
        return ITERATIONS * 2  # one join and one selected-reason event per iteration
    return 1  # the effect projection text


def scale_configurations():
    result = []
    for size in (128, 256, 512, 1024, 2048, 4096):
        for shape in (1, 2, 3):
            result.append(('ownership', 'ownership', size, shape, 'explain-on', 'ownership-%d-%d-on' % (size, shape)))
    # Above about 1,365 colliding nodes the store's fixed snapshot limit stops saving.
    for size in (128, 256, 512, 1024):
        result.append(('evidence', 'evidence', size, 1, 'observer-off', 'evidence-%d' % size))
    # A chain needs one fixed-point round per function, so effects grow quadratically.
    for size in (128, 256, 512, 1024):
        for kind in ('effect', 'effect-cycle'):
            result.append(('effect', kind, size, 65, 'observer-on', '%s-%d-65-on' % (kind, size)))
    return result


def launch(argv, run_dir, env, cap, wall_cap=WALL_CAP):
    run_dir.mkdir(parents=True)
    limits = run_dir / 'stack-limits.txt'
    rusage = run_dir / 'rusage.txt'
    script = ('ulimit -S -s %d && ulimit -H -s %d && { ulimit -S -s; ulimit -H -s; } > "$1" && shift '
              '&& exec /usr/bin/time -l -o "$1" "${@:2}"') % (cap, cap)
    command = ['/bin/bash', '-c', script, 'launcher', str(limits), str(rusage)] + argv
    began = time.monotonic()
    with open(run_dir / 'stdout.txt', 'wb') as out, open(run_dir / 'stderr.txt', 'wb') as err:
        process = subprocess.Popen(command, stdout=out, stderr=err, env=env, start_new_session=True)
        try:
            status = process.wait(timeout=wall_cap)
            timed_out = False
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, 9)
            status = process.wait()
            timed_out = True
    wall = time.monotonic() - began
    raw = rusage.read_text() if rusage.exists() else ''
    rss = re.search(r'^\s*(\d+)\s+maximum resident set size\s*$', raw, re.MULTILINE)
    soft, hard = (limits.read_text().split() + [None, None])[:2] if limits.exists() else (None, None)
    return {'argv': command, 'exit_status': status, 'timed_out': timed_out, 'fresh_wall_seconds': wall,
            'rss_bytes': int(rss.group(1)) if rss else None, 'rss_kind': 'kernel_process_peak_bytes',
            'soft_stack_limit_kib': int(soft) if soft else None, 'hard_stack_limit_kib': int(hard) if hard else None}


def resources(args, env):
    runs = []
    for repeat in range(args.repeats):
        for group, kind, size, width, flag, label in configurations():
            run_dir = args.output / ('%s-r%d' % (label, repeat))
            output = run_dir / 'output'
            output.mkdir(parents=True)
            record = launch([args.binary, kind, str(size), str(width), flag, str(output)], run_dir / 'process', env,
                            STACK_KIB)
            reference = args.references / (label + '-sample0-r0') / 'result.txt'
            result = output / 'result.txt'
            matches = result.exists() and result.read_bytes() == reference.read_bytes()
            if (output / 'metrics.txt').exists():
                # At the selected sizes every join's events must be recorded.
                matches = matches and metrics(output / 'metrics.txt')['recordedEvents'] == expected_events(kind, width, flag)
            record.update({'case': label, 'repeat': repeat, 'group': group, 'kind': kind, 'profile': PROFILE,
                           'result_matches_j0': matches})
            if (output / 'metrics.txt').exists():
                values = metrics(output / 'metrics.txt')
                retained = values['liveAfterRetirement'] - values['liveAfterSetup']
                expected = invocation_lived(kind, width, flag, values)
                record.update({'phase_seconds': values['phaseNanos'] / 1e9,
                               'phase_allocations': values['phaseAllocations'],
                               'live_after_phase': values['liveAfterPhase'] - values['liveAfterSetup'],
                               'recorded_events': values['recordedEvents'],
                               'retained_after_retirement': retained, 'invocation_lived': expected,
                               'temporary_outstanding_after_retirement': retained - expected})
            runs.append(record)
            print(label, repeat, record['exit_status'], matches, '%.3f s' % record['fresh_wall_seconds'],
                  record['rss_bytes'], record.get('phase_seconds'), record.get('temporary_outstanding_after_retirement'))
    (args.output / 'runs.json').write_text(json.dumps({'runs': runs}, indent=1) + '\n')
    mismatched = [run['case'] for run in runs if not run['result_matches_j0'] or run['exit_status'] != 0]
    print('runs', len(runs), 'mismatched', len(mismatched))
    return 1 if mismatched else 0


def scale(args, env):
    runs = []
    for repeat in range(args.repeats):
        for group, kind, size, width, flag, label in scale_configurations():
            run_dir = args.output / ('%s-r%d' % (label, repeat))
            output = run_dir / 'output'
            output.mkdir(parents=True)
            record = launch([args.binary, kind, str(size), str(width), flag, str(output)], run_dir / 'process', env,
                            STACK_KIB, 10.0)
            record.update({'case': label, 'repeat': repeat, 'group': group, 'kind': kind, 'size': size,
                           'profile': PROFILE})
            if record['exit_status'] == 0 and (output / 'metrics.txt').exists():
                values = metrics(output / 'metrics.txt')
                retained = values['liveAfterRetirement'] - values['liveAfterSetup']
                expected = invocation_lived(kind, width, flag, values)
                record.update({'phase_seconds': values['phaseNanos'] / 1e9,
                               'phase_allocations': values['phaseAllocations'],
                               'live_after_phase': values['liveAfterPhase'] - values['liveAfterSetup'],
                               'recorded_events': values['recordedEvents'],
                               'retained_after_retirement': retained, 'invocation_lived': expected,
                               'temporary_outstanding_after_retirement': retained - expected})
            runs.append(record)
            print(label, repeat, record['exit_status'], record['timed_out'], '%.3f s' % record['fresh_wall_seconds'],
                  record['rss_bytes'], record.get('phase_seconds'), record.get('temporary_outstanding_after_retirement'))
    (args.output / 'runs.json').write_text(json.dumps({'runs': runs}, indent=1) + '\n')
    return 0


def stack(args, env):
    results = []
    scratch = args.output / 'scratch'

    def attempt(kind, size, width, flag, limit):
        shutil.rmtree(scratch, ignore_errors=True)
        scratch.mkdir(parents=True)
        script = 'ulimit -S -s %d && ulimit -H -s %d && exec "$@"' % (limit, limit)
        process = subprocess.run(['/bin/bash', '-c', script, 'launcher', args.binary, kind, str(size), str(width), flag,
                                  str(scratch)], capture_output=True, timeout=30, env=env)
        result = scratch / 'result.txt'
        return process.returncode, result.read_bytes() if process.returncode == 0 and result.exists() else None

    for group, kind, size, width, flag, label in configurations():
        status, reference = attempt(kind, size, width, flag, STACK_KIB)
        assert status == 0, (label, status)
        low, high = 16, STACK_KIB
        status, _ = attempt(kind, size, width, flag, low)
        trials = [{'limit_kib': STACK_KIB, 'exit_status': 0}, {'limit_kib': low, 'exit_status': status}]
        if status == 0:
            high = low
        while high - low > 16:
            middle = max((low + high) // 2 // 16 * 16, low + 16)
            status, output = attempt(kind, size, width, flag, middle)
            trials.append({'limit_kib': middle, 'exit_status': status})
            if status == 0 and output == reference:
                high = middle
            else:
                low = middle
        results.append({'case': label, 'smallest_passing_limit_kib': high,
                        'largest_failing_limit_kib': low if low < high else None, 'fixed_limit_kib': STACK_KIB,
                        'trials': trials})
        print(label, 'passes at', high, 'KiB; fails at', low if low < high else 'none tried', 'KiB')
    shutil.rmtree(scratch, ignore_errors=True)
    (args.output / 'stack.json').write_text(json.dumps({'binary': args.binary, 'results': results}, indent=1) + '\n')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['resources', 'stack', 'scale'])
    parser.add_argument('--binary', required=True, help='native KernelCapture executable')
    parser.add_argument('--references', type=Path, help='directory of J0 LABEL-sample0-r0/result.txt files')
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('preserve existing measurements: ' + str(args.output))
    args.output.mkdir(parents=True)
    env = {k: v for k, v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    return {'resources': resources, 'stack': stack, 'scale': scale}[args.mode](args, env)


if __name__ == '__main__':
    raise SystemExit(main())
