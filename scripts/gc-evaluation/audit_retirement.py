#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Extension-independent candidate scan with explicit content-bound dispositions.

This is a regression tripwire, not a proof of pointer provenance or semantics.
The checked-in ledger is reviewed data, never an automatically updated allowlist.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LEDGER = 'docs/gc-evaluation/post-integration-residuals.json'
MATCH = re.compile(r'free|destroy|destruct|unfreed|dealloc|rollback|(?:Deferred)?Free(?:Statement|Instruction)'
                   r'|ReleaseOwned|release_owned|owned.?render|toStringReturnsOwnedFresh'
                   r'|localizedMessageReturnsOwnedFresh|destroy_array|ironwood\.destroy'
                   r'|free(?:Root|Facade)\b|\bdestroy(?:ed|s)?\b|destroyCount|destructionCount'
                   r'|trace_state|owned_message', re.IGNORECASE)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def scan():
    paths = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT)
    candidates, opaque = {}, {}
    scanned = 0

    def inspect(name, data, depth=0):
        nonlocal scanned
        scanned += 1
        if zipfile.is_zipfile(io.BytesIO(data)):
            if depth >= 3:
                raise ValueError('archive nesting requires manual review: ' + name)
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                if sum(v.file_size for v in archive.infolist()) > 64 * 1024**2:
                    raise ValueError('archive inspection budget exceeded: ' + name)
                for item in archive.infolist():
                    if not item.is_dir():
                        inspect(name + '!/' + item.filename, archive.read(item), depth + 1)
            return
        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError:
            opaque[name] = digest(data)
            return
        if '\0' in text:
            opaque[name] = digest(data)
            return
        lines = [(i, line) for i, line in enumerate(text.splitlines(), 1) if MATCH.search(line)]
        if lines:
            candidates[name] = {'sha256': digest(data), 'lines': lines}

    for name in sorted(set(paths.decode().split('\0'))):
        # The one ledger is structured path/hash/disposition data, not executable
        # input. Excluding its self-reference avoids a recursive hash definition.
        if name and name != LEDGER and (ROOT / name).is_file():
            inspect(name, (ROOT / name).read_bytes())
    return candidates, opaque, scanned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scan', type=Path, help='write fresh candidates for human review, without changing the ledger')
    args = parser.parse_args()
    candidates, opaque, scanned = scan()
    if args.scan:
        with args.scan.open('x') as output:
            json.dump({'candidates': candidates, 'opaque': opaque, 'scanned': scanned}, output, indent=2)
            output.write('\n')
        print(f'{scanned} entries, {len(candidates)} candidate files, {len(opaque)} binary entries')
        return
    ledger = json.loads((ROOT / LEDGER).read_text())
    unexplained, open_findings = [], []
    for name, current in candidates.items():
        known = ledger['files'].get(name)
        if known is None or known['sha256'] != current['sha256']:
            unexplained.append(name)
            continue
        if Counter(i for i, _ in current['lines']) != Counter(i for i, kind in known['lines']):
            unexplained.append(name + ': incomplete line dispositions')
        for line, kind in known['lines']:
            if kind not in ledger['dispositions']:
                unexplained.append(name + ': unknown disposition ' + kind)
            elif ledger['dispositions'][kind]['status'] == 'open':
                open_findings.append(f'{name}:{line}')
    for name, sha in opaque.items():
        if ledger['opaque'].get(name, {}).get('sha256') != sha:
            unexplained.append(name + ': binary inspection required')
    stale = sorted(set(ledger['files']) - set(candidates))
    print(json.dumps({'scanned_entries': scanned, 'candidate_files': len(candidates),
                      'matched_lines': sum(len(v['lines']) for v in candidates.values()),
                      'unexplained': unexplained, 'stale_dispositions': stale,
                      'known_open_matches': open_findings}, indent=2))
    if unexplained or stale or open_findings:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
