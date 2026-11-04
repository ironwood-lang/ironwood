#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded GC0 experiments using the already prepared, isolated BDWGC profile."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import re
import signal
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
GUARD_KIB = 16 * 1024 * 1024


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evaluation', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--llvm-bin', type=Path, default=Path('/opt/homebrew/opt/llvm@23/bin'))
    args = parser.parse_args()
    evaluation, output = args.evaluation.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    raw = output / 'raw'
    raw.mkdir()
    manifest = json.loads((ROOT / 'docs/gc-evaluation/2026-11-04-manifest.json').read_text())
    archive = evaluation / 'gc-8.2.12.tar.gz'
    assert digest(archive) == manifest['inputs'][0]['sha256'], 'collector archive mismatch'
    library = evaluation / 'build-compiler-roots-large/.libs/libgc.a'
    sources = ROOT / 'scripts/gc-evaluation'
    compiler = args.llvm_bin / 'clang'
    builds = []
    for source, name, wrapper in [
        ('accounting_probe.c', 'accounting', False),
        ('typeinfo_probe.c', 'typeinfo', False),
        ('policy_probe.c', 'policy', True),
        ('mmtk_probe.c', 'candidate', True),
        ('probe.c', 'control', False),
    ]:
        inputs = [sources / source]
        if wrapper:
            inputs.append(sources / 'bdwgc_compiler_roots.c')
        command = [str(compiler), '-O3', '-std=c11', '-D_POSIX_C_SOURCE=200809L',
                   '-Wall', '-Wextra', '-Werror', '-I' + str(evaluation / 'sources/gc-8.2.12/include'),
                   *map(str, inputs), str(library), '-o', str(output / name)]
        subprocess.run(command, check=True, capture_output=True)
        builds.append({'name': name, 'sha256': digest(output / name),
                       'inputs': {str(p.relative_to(ROOT)): digest(p) for p in inputs}})

    def run(name, command):
        stdout, stderr = raw / (name + '.stdout'), raw / (name + '.stderr')
        peak = 0
        with stdout.open('w') as out, stderr.open('w') as err:
            child = subprocess.Popen(command, stdout=out, stderr=err, start_new_session=True)
            start = time.monotonic()
            while child.poll() is None:
                rss = subprocess.run(['ps', '-o', 'rss=', '-p', str(child.pid)],
                                     capture_output=True, text=True).stdout.strip()
                if rss:
                    peak = max(peak, int(rss))
                if peak >= GUARD_KIB or time.monotonic() - start > 60:
                    # Kill the complete experiment process group on either limit.
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
                    raise RuntimeError('16 GiB guard or 60-second timeout: ' + name)
                time.sleep(0.1)
            assert child.returncode == 0, name + ': ' + stderr.read_text()[-2000:]
        return {'name': name, 'exit': child.returncode, 'sampled_peak_rss_kib': peak,
                'stdout_sha256': digest(stdout), 'stderr_sha256': digest(stderr),
                'data': [json.loads(line) for line in stdout.read_text().splitlines() if line.startswith('{')]}

    results = [run(name, [str(output / name)]) for name in ['accounting', 'typeinfo', 'policy']]
    randomizer = random.Random(20261104)
    for repetition in range(5):
        jobs = [(label, rare) for label in ['candidate', 'control'] for rare in [0, 1]]
        randomizer.shuffle(jobs)
        for label, rare in jobs:
            command = [str(output / label)]
            command += ['0', 'bench', str(rare), '5000000'] if label == 'candidate' else ['bench', '0', str(rare), '5000000']
            row = run(f'{label}-{rare}-{repetition}', command)
            assert row['data'][0]['gc_events'] == 0
            row.update(label=label, rare=rare, repetition=repetition)
            results.append(row)
    timings = []
    fingerprints = []
    for label in ['candidate', 'control']:
        for rare in [0, 1]:
            rows = [r['data'][0] for r in results if r.get('label') == label and r['rare'] == rare]
            medians = [statistics.median(row['ns']) / row['iterations'] for row in rows]
            assert len({row['checksum'] for row in rows}) == 1
            timings.append({'label': label, 'rare': rare, 'process_medians_ns_per_op': medians,
                            'median_ns_per_op': statistics.median(medians), 'checksum': rows[0]['checksum']})
        disassembly = subprocess.check_output([str(args.llvm_bin / 'llvm-objdump'), '--disassemble',
                '--disassemble-symbols=_pooled_mutate', str(output / label)], text=True)
        (raw / (label + '.disassembly')).write_text(disassembly)
        words = re.findall(r'^\s*[0-9a-f]+:\s+([0-9a-f]{8})\s', disassembly, re.MULTILINE)
        assert words, 'expected macOS arm64 machine code'
        code = b''.join(bytes.fromhex(word)[::-1] for word in words)
        fingerprints.append({'label': label, 'symbol': '_pooled_mutate', 'bytes': len(code),
                             'sha256': hashlib.sha256(code).hexdigest()})
    assert fingerprints[0]['sha256'] == fingerprints[1]['sha256']
    report = {'baseline_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'host_role': 'machost', 'compiler': subprocess.check_output([str(compiler), '--version'], text=True).splitlines()[0],
              'guard_kib': GUARD_KIB, 'per_child_timeout_seconds': 60,
              'profile': manifest['selected_profile'], 'collector_archive_sha256': digest(archive),
              'collector_library_sha256': digest(library),
              'collector_config_sha256': digest(evaluation / 'build-compiler-roots-large/include/config.h'),
              'builds': builds, 'results': results, 'timings': timings, 'fingerprints': fingerprints}
    (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'timings': timings, 'fingerprints': fingerprints}, indent=2))


if __name__ == '__main__':
    main()
