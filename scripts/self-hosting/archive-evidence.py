#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Preserve exact evidence bytes in deterministic gzip/tar with checked hashes."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile


def verify(archive, manifest):
    expected = json.loads(manifest.read_text())
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress(archive.read_bytes())), mode='r:') as stream:
        actual = {}
        for member in stream:
            if not member.isfile():
                raise ValueError('non-file archive member')
            data = stream.extractfile(member).read()
            actual[member.name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
    assert expected['files'] == actual, 'evidence manifest differs'
    assert expected['archive_sha256'] == hashlib.sha256(archive.read_bytes()).hexdigest()
    print('PASS: exact bytes and manifest for', len(actual), 'evidence files')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('inputs', nargs='*', help='unique-label=directory-or-file')
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if args.verify:
        verify(args.archive, args.manifest)
        return
    if args.archive.exists() or args.manifest.exists():
        raise ValueError('evidence destination already exists')
    files = {}
    for item in args.inputs:
        label, name = item.split('=', 1)
        if '/' in label or label in ('', '.', '..'):
            raise ValueError('invalid input label')
        source = Path(name)
        for path in sorted(source.rglob('*')) if source.is_dir() else [source]:
            if path.is_symlink():
                raise ValueError('symlink in evidence')
            if path.is_file():
                key = label + ('/' + path.relative_to(source).as_posix() if source.is_dir() else '')
                if key in files:
                    raise ValueError('duplicate evidence name')
                files[key] = path.read_bytes()
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w', format=tarfile.USTAR_FORMAT) as stream:
        for name, data in sorted(files.items()):
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            entry.mode = 0o644
            stream.addfile(entry, io.BytesIO(data))
    args.archive.parent.mkdir(parents=True, exist_ok=True)
    args.archive.write_bytes(gzip.compress(buffer.getvalue(), mtime=0))
    report = {'schema': 1, 'archive_sha256': hashlib.sha256(args.archive.read_bytes()).hexdigest(),
              'files': {name: {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
                        for name, data in sorted(files.items())}}
    args.manifest.write_text(json.dumps(report, indent=2) + '\n')
    verify(args.archive, args.manifest)


if __name__ == '__main__':
    main()
