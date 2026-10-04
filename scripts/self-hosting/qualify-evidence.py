#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Run focused contract probes against the hash-pinned original J0."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--probe', choices=('evidence', 'ast'), default='evidence')
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('qualification destination already exists')
    args.output.mkdir(parents=True)
    identity = json.loads((ROOT / 'docs/self-hosting/m0/qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    jar = install / 'lib/ironwoodc.jar'
    if hashlib.sha256(jar.read_bytes()).hexdigest() != identity['seed_jar_sha256']:
        raise ValueError('original J0 seed mismatch')
    jdk = Path(identity['jdk'])
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(name, None)
    if args.probe == 'evidence':
        probe = 'EvidenceOrderProbe'
        main_class = 'ironwood.compiler.semantic.' + probe
        cases = '8/32/128 adversarial keys, forward/reverse insertion and path order; independent copies; identity/value intersection; atomic budget failure; close cleanup'
        limits = 'no resource measurement; queue/GC retirement timing not forced or qualified'
    else:
        probe = 'AstContractProbe'
        main_class = 'ironwood.compiler.ast.' + probe
        cases = 'AST snapshot/null/value/identity contracts, constructor failures, primitive count wrapping, literal dot rendering, locale distinction, ordered first-binding conflicts and control-flow completion'
        limits = 'no resource or full AST variant qualification; no native implementation'
    classes = ROOT / ('target/self-hosting-m0/' + args.probe + '-probe-classes')
    classes.mkdir(parents=True, exist_ok=True)
    source = ROOT / ('scripts/self-hosting/' + probe + '.java')
    (args.output / 'probe-source.java.gz').write_bytes(gzip.compress(source.read_bytes(), mtime=0))
    commands = [[str(jdk / 'bin/javac'), '--release', '21', '-Xlint:all', '-Werror', '-cp', str(jar),
                 '-d', str(classes), str(source)]]
    commands += [[str(jdk / 'bin/java'), *identity['profile'], '-cp', str(classes) + ':' + str(jar),
                  main_class] for _ in range(4)]
    records, outputs = [], []
    for index, command in enumerate(commands):
        process = subprocess.run(command, cwd=ROOT, env=env, capture_output=True)
        (args.output / (str(index) + '.stdout.txt')).write_bytes(process.stdout)
        (args.output / (str(index) + '.stderr.txt')).write_bytes(process.stderr)
        records.append({'argv': command, 'returncode': process.returncode})
        if index:
            outputs.append(process.stdout)
        if process.returncode:
            break
    report = {'schema': 1, 'J0_revision': identity['revision'], 'J0_seed_sha256': identity['seed_jar_sha256'],
              'profile': identity['profile'], 'probe_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'cases': cases, 'limits': limits,
              'commands': records}
    (args.output / 'qualification.json').write_text(json.dumps(report, indent=2) + '\n')
    if len(records) != 5 or any(r['returncode'] for r in records) or len(set(outputs)) != 1:
        raise ValueError('qualification failure or fresh-process difference; retained logs explain the outcome')
    print(outputs[0].decode().rstrip())
    print('PASS: four fresh qualified processes match')


if __name__ == '__main__':
    main()
