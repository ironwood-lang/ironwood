#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Exercise only the selected M0 loop programs and mandatory unsafe control."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--unsafe-only', action='store_true', help='recheck the changed unsafe fixture only')
    args = parser.parse_args()
    out = args.output.resolve()
    if out.exists(): raise ValueError('preserve existing evidence')
    out.mkdir(parents=True)
    identity_path = ROOT / 'docs/self-hosting/m0/ordered/qualified-identity.json'
    identity = json.loads(identity_path.read_text())
    install = Path(identity['launcher']).parent.parent
    jar = install / 'lib/ironwoodc.jar'
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest(jar) == identity['seed_jar_sha256']
    (out / 'identity.json').write_bytes(identity_path.read_bytes())
    (out / 'qualify-loop-controls.py').write_bytes(Path(__file__).read_bytes())
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(name, None)
    env.update(IRONWOOD_STDLIB_HOME=str(install), IRONWOOD_RUNTIME_HOME=str(install), SDKROOT=identity['sdk']['path'])
    prefix = [identity['jdk'] + '/bin/java', *identity['profile'], '-jar', str(jar)]
    records = []
    def run(label, argv, expected):
        process = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True)
        for name, data in (('stdout', process.stdout), ('stderr', process.stderr)):
            (out / (label + '.' + name + '.txt')).write_bytes(data)
        records.append({'label': label, 'argv': argv, 'returncode': process.returncode,
                        'stdout_sha256': hashlib.sha256(process.stdout).hexdigest(),
                        'stderr_sha256': hashlib.sha256(process.stderr).hexdigest()})
        (out / 'commands.json').write_text(json.dumps(records, indent=2) + '\n')
        assert process.returncode == expected, label
        return process
    names = ('LoopFormsUnsafe',) if args.unsafe_only else ('Loops16', 'Loops64', 'Loops128', 'LoopForms', 'LoopFormsUnsafe')
    for name in names:
        source = out / (name + '.iron')
        source.write_bytes((ROOT / 'target/self-hosting-m0/loop-canonical-original/sources' / source.name).read_bytes())
        if name == 'LoopFormsUnsafe':
            for mode in ('off', 'warn', 'error'):
                process = run(name + '-' + mode, [*prefix, str(source), '-d', str(out / ('unsafe-' + mode)), '--unfreed=' + mode], 1)
                assert b'cannot free' in process.stderr and b'still be observed' in process.stderr \
                    and b'enhanced.source.0' in process.stderr
        else:
            classes = out / (name + '-classes'); program = out / (name + '-program')
            run(name + '-compile', [*prefix, str(source), '-d', str(classes), '--unfreed=error'], 0)
            run(name + '-link', [*prefix, '--link', '-cp', str(classes), '--main-class', name,
                '-o', str(program), '--unfreed=error', '-O3', '--llvm-home', identity['llvm']], 0)
            run(name + '-execute', [str(program)], 0)
    sources = {path.name: digest(path) for path in out.glob('*.iron')}
    (out / 'qualification.json').write_text(json.dumps({'passed': True, 'seed_sha256': digest(jar),
        'source_sha256': sources, 'commands': len(records), 'native_exit_zero': 0 if args.unsafe_only else 4,
        'unsafe_rejected_modes': ['off', 'warn', 'error'],
        'scope': 'focused behavior controls; no resource or native compiler pilot claim'}, indent=2) + '\n')
    print('PASS:', 0 if args.unsafe_only else 4, 'native loop programs; unsafe rejection in all three unfreed modes')


if __name__ == '__main__':
    main()
