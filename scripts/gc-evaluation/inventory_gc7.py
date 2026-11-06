#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Reproduce GC7's lexical candidate inventory, not a semantic disposition verdict."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
MATCH = re.compile(rb'\b(?:free|destructor)\b|SuppressUnfreed|--unfreed|--explain-rejected-free')
SUFFIXES = {'.java', '.iron', '.c', '.h', '.cpp', '.py', '.sh', '.md', '.json',
            '.xml', '.launch', '.yml', '.yaml', '.properties', '.g4'}


def area(path):
    for prefix in ('compiler/src/main/java', 'compiler/src/main/ironwood', 'compiler/src/test'):
        if path.startswith(prefix + '/'):
            return prefix
    return path.split('/')[0]


def inventory(files, read):
    candidates = {}
    counts = Counter()
    matches = Counter()
    for path in files:
        data = read(path)
        count = len(MATCH.findall(data))
        if not count:
            continue
        candidates[path] = {'sha256': hashlib.sha256(data).hexdigest(), 'matches': count}
        counts[area(path)] += 1
        matches[area(path)] += count
    canonical = json.dumps(candidates, sort_keys=True, separators=(',', ':')).encode()
    return {'candidate_files': candidates, 'candidate_inventory.sha256': hashlib.sha256(canonical).hexdigest(),
            'areas': {key: {'files': counts[key], 'matches': matches[key]} for key in sorted(counts)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', default='3afea8f8')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    revision = subprocess.check_output(['git', 'rev-parse', '--verify', args.revision + '^{commit}'],
                                       cwd=ROOT, text=True).strip()
    files = sorted(path for path in subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard'], cwd=ROOT, text=True).splitlines()
                   if Path(path).suffix in SUFFIXES and (ROOT / path).is_file())
    baseline_files = sorted(path for path in subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', revision], cwd=ROOT, text=True).splitlines()
                            if Path(path).suffix in SUFFIXES)
    # A single read-only Git process avoids rebuilding or checking out any baseline.
    child = subprocess.Popen(['git', 'cat-file', '--batch'], cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    def baseline(path):
        child.stdin.write((revision + ':' + path + '\n').encode())
        child.stdin.flush()
        header = child.stdout.readline().decode().split()
        if len(header) != 3:
            return b''  # A newly tracked file has no baseline object.
        data = child.stdout.read(int(header[2]))
        assert child.stdout.read(1) == b'\n'
        return data
    try:
        before = inventory(baseline_files, baseline)
    finally:
        child.stdin.close()
        child.wait(timeout=10)
    after = inventory(files, lambda path: (ROOT / path).read_bytes())
    result = {'format': 1, 'baseline': revision, 'meaning': 'lexical candidates requiring semantic classification',
              'baseline_inventory': before, 'worktree_inventory': after}
    with args.output.open('x') as output:
        json.dump(result, output, sort_keys=True, indent=2)
        output.write('\n')
    print(json.dumps({'baseline': revision, 'baseline_areas': before['areas'], 'worktree_areas': after['areas'],
                      'baseline_inventory.sha256': before['candidate_inventory.sha256'],
                      'worktree_inventory.sha256': after['candidate_inventory.sha256']}, sort_keys=True))


if __name__ == '__main__':
    main()
