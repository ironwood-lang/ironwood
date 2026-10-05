#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Materialize a pinned M0 source/evidence view without changing the checkout."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True, help='immutable full M0 checkpoint commit')
    parser.add_argument('--output', type=Path, required=True, help='new ignored directory inside target/')
    args = parser.parse_args()
    assert re.fullmatch('[0-9a-f]{40}', args.revision), 'use an immutable full commit, not a moving branch'
    commit = subprocess.check_output(['git', 'rev-parse', '--verify', args.revision + '^{commit}'], cwd=ROOT, text=True).strip()
    assert commit == args.revision
    output = args.output.resolve()
    assert output.is_relative_to(ROOT / 'target') and not output.exists()
    assert subprocess.run(['git', 'check-ignore', '-q', str(output)], cwd=ROOT).returncode == 0
    paths = ['scripts/self-hosting', 'docs/self-hosting/m0', 'docs/BEFORE_SELF_HOSTING_PLAN.md',
             'docs/SELF_HOSTING_PLAN.md', 'compiler/src/main/java', 'stdlib/src/main/ironwood']
    raw = subprocess.check_output(['git', 'archive', '--format=tar', commit, *paths], cwd=ROOT)
    output.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(raw), mode='r:') as stream:
        for member in stream:
            destination = output / member.name
            assert destination.resolve().is_relative_to(output)
            if member.isdir(): destination.mkdir(parents=True, exist_ok=True)
            else:
                assert member.isfile(), 'no symlink/device/submodule in replay input'
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(stream.extractfile(member).read())
                destination.chmod(member.mode & 0o777)
    evidence = output / 'docs/self-hosting/m0'
    identity = json.loads((evidence / 'ordered/qualified-identity.json').read_text())
    count = 0
    for name, expected in identity['input_sha256'].items():
        if name.startswith('compiler/src/main/java/') and name.endswith('.java'):
            assert hashlib.sha256((output / name).read_bytes()).hexdigest() == expected
            count += 1
    assert count == 460
    original = json.loads((evidence / 'qualified/identity.json').read_text())
    report = {'schema': 1, 'replay_revision': commit, 'archive_sha256': hashlib.sha256(raw).hexdigest(),
              'ordered_source_files': count, 'original_revision': original['revision'],
              'original_install': str(Path(original['launcher']).parent.parent),
              'ordered_install': str(Path(identity['launcher']).parent.parent),
              'limit': 'source/evidence view only; absolute historical command paths retained; no branch/reset/network, resource rerun or native implementation'}
    (output / 'replay.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS:', count, 'pinned D247 source files in', output)


if __name__ == '__main__':
    main()
