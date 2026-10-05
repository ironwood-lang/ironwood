#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Fresh-process resource runs for the M2.1 frontend pilot (PILOT_BUDGETS.md procedure).

Each case is one manifest (INPUT, LOGICAL_NAME, SHA256 per line). Native runs
execute FrontendCapture under an 8,176-KiB soft and hard main-stack limit set
by the launcher before exec, inside /usr/bin/time -l; the runner records
monotonic wall time and kills a run at the 2-second frontend cap. J0 runs use
the qualified JVM profile with Java option variables cleared. Every run keeps
its argv, stdout, stderr, rusage text, stack limits and exit status.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STACK_KIB = 8176
WALL_CAP = 2.0
PROFILE = 'macOS arm64 Apple M5, 32 GiB, qualified pinned JDK/LLVM identities'
JDK = '/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home'
JVM = ['-Xms256m', '-Xmx4096m', '-Xss8m', '-XX:+UseG1GC']


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def launch(argv, run_dir, env):
    run_dir.mkdir(parents=True)
    limits = run_dir / 'stack-limits.txt'
    rusage = run_dir / 'rusage.txt'
    script = ('ulimit -S -s %d && ulimit -H -s %d && { ulimit -S -s; ulimit -H -s; } > "$1" && shift '
              '&& exec /usr/bin/time -l -o "$1" "${@:2}"') % (STACK_KIB, STACK_KIB)
    command = ['/bin/bash', '-c', script, 'launcher', str(limits), str(rusage)] + argv
    began = time.monotonic()
    with open(run_dir / 'stdout.txt', 'wb') as out, open(run_dir / 'stderr.txt', 'wb') as err:
        process = subprocess.Popen(command, stdout=out, stderr=err, env=env, start_new_session=True)
        try:
            status = process.wait(timeout=WALL_CAP)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['native', 'j0'], required=True)
    parser.add_argument('--binary', help='native FrontendCapture executable')
    parser.add_argument('--j0-classpath', help='J0 seed jar plus FrontendReference classes')
    parser.add_argument('--case', action='append', required=True, help='NAME=MANIFEST')
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('preserve existing measurements: ' + str(args.output))
    env = {k: v for k, v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    runs = []
    for repeat in range(args.repeats):
        for case in args.case:
            name, manifest = case.split('=', 1)
            run_dir = args.output / ('%s-r%d' % (name, repeat))
            outputs = run_dir / 'outputs'
            units = [line for line in Path(manifest).read_text().splitlines() if line]
            for index in range(len(units)):
                (outputs / str(index)).mkdir(parents=True)
            if args.kind == 'native':
                argv = [args.binary, manifest, str(outputs)]
            else:
                argv = [JDK + '/bin/java'] + JVM + ['-cp', args.j0_classpath, 'ironwood.compiler.FrontendReference',
                                                   manifest, str(outputs)]
            record = launch(argv, run_dir / 'process', env)
            summary = (run_dir / 'process' / 'stdout.txt').read_text().strip().splitlines()
            fields = summary[-1].split() if summary else []
            metrics = {fields[i]: fields[i + 1] for i in range(0, len(fields) - 1, 2)} if fields else {}
            record.update({'case': name, 'repeat': repeat, 'manifest': manifest, 'manifest_sha256': sha(manifest),
                           'units': len(units), 'kind': args.kind, 'profile': PROFILE, 'group': 'frontend',
                           'phase_seconds': int(metrics['phase_nanos']) / 1e9 if 'phase_nanos' in metrics else None,
                           'adapter_summary': summary[-1] if summary else None})
            if args.kind == 'native' and 'outstanding_temporaries' in metrics:
                record['unreachable_after_phase'] = int(metrics['outstanding_temporaries'])
                record['retained_allocations'] = int(metrics['retained_allocations'])
                record['census_allocations'] = int(metrics['census_allocations'])
                record['phase_allocations'] = int(metrics['phase_allocations'])
            runs.append(record)
            print(name, repeat, record['exit_status'], '%.3f s' % record['fresh_wall_seconds'], record['rss_bytes'],
                  record.get('unreachable_after_phase'))
    (args.output / 'runs.json').write_text(json.dumps({'kind': args.kind, 'runs': runs}, indent=1) + '\n')


if __name__ == '__main__':
    main()
