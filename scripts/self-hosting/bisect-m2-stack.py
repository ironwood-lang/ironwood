#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bound a native pilot's main-stack high-water by external stack limits.

For each case the launcher sets the soft and hard main-stack limit before exec
and runs the unchanged binary. A bisection over limits in KiB finds the
smallest limit at which the run exits 0 with outputs identical to the run at
the fixed 8,176-KiB limit; the next smaller limit tried must fail. The stack
high-water lies between the two limits. No bookkeeping is added to the binary.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

CAP = 8176


def digest_tree(path):
    result = hashlib.sha256()
    for file in sorted(p for p in path.rglob('*') if p.is_file()):
        result.update(str(file.relative_to(path)).encode())
        result.update(file.read_bytes())
    return result.hexdigest()


def run(argv_template, manifest, limit, scratch):
    shutil.rmtree(scratch, ignore_errors=True)
    units = [line for line in Path(manifest).read_text().splitlines() if line]
    for index in range(len(units)):
        (scratch / str(index)).mkdir(parents=True)
    argv = [part.replace('{manifest}', manifest).replace('{output}', str(scratch)) for part in argv_template]
    script = 'ulimit -S -s %d && ulimit -H -s %d && exec "$@"' % (limit, limit)
    process = subprocess.run(['/bin/bash', '-c', script, 'launcher'] + argv, capture_output=True, timeout=30)
    return process.returncode, digest_tree(scratch) if process.returncode == 0 else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', action='append', required=True, help='NAME=MANIFEST')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('argv', nargs='+', help='command with {manifest} and {output} placeholders')
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('preserve existing measurements')
    args.output.mkdir(parents=True)
    results = []
    for case in args.case:
        name, manifest = case.split('=', 1)
        scratch = args.output / 'scratch'
        status, reference = run(args.argv, manifest, CAP, scratch)
        assert status == 0, (name, status)
        low, high = 16, CAP  # a failing and a passing limit once the search starts
        status, _ = run(args.argv, manifest, low, scratch)
        trials = [{'limit_kib': CAP, 'exit_status': 0}, {'limit_kib': low, 'exit_status': status}]
        if status == 0:
            high = low
        while high - low > 16:
            middle = (low + high) // 2 // 16 * 16
            if middle <= low:
                middle = low + 16
            status, outputs = run(args.argv, manifest, middle, scratch)
            trials.append({'limit_kib': middle, 'exit_status': status})
            if status == 0 and outputs == reference:
                high = middle
            else:
                low = middle
        results.append({'case': name, 'manifest_sha256': hashlib.sha256(Path(manifest).read_bytes()).hexdigest(),
                        'smallest_passing_limit_kib': high,
                        'largest_failing_limit_kib': low if low < high else None,
                        'fixed_limit_kib': CAP, 'trials': trials})
        print(name, 'passes at', high, 'KiB; fails at', low if low < high else 'none tried', 'KiB')
    shutil.rmtree(args.output / 'scratch', ignore_errors=True)
    (args.output / 'stack.json').write_text(json.dumps({'argv': args.argv, 'results': results}, indent=1) + '\n')


if __name__ == '__main__':
    main()
