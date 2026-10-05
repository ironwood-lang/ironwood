#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Record the existing frozen-library path-order difference without repairing it."""
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
    args = parser.parse_args()
    out = args.output.resolve()
    if out.exists(): raise ValueError('preserve evidence')
    out.mkdir(parents=True)
    identity = json.loads((ROOT / 'docs/self-hosting/m0/qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    jar = install / 'lib/ironwoodc.jar'
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == identity['seed_jar_sha256']
    source = out / 'PathOrder.iron'
    source.write_text('''// SPDX-License-Identifier: MIT OR Apache-2.0

import ironwood.nio.file.Path;

// Existing J0 library exits one; Java's qualified Unix provider orders these paths oppositely.
// Reclaim both owned paths after their last comparison. This fixture records a migration gate.
class PathOrder {
    public static int main(String[] args) {
        Path privateUse = Path.of("\ue000");
        Path supplementary = Path.of("\U0001f600");
        int order = privateUse.compareTo(supplementary);
        free supplementary;
        free privateUse;
        return order < 0 ? 0 : 1;
    }
}
''', encoding='utf-8')
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(name, None)
    env.update(IRONWOOD_STDLIB_HOME=str(install), IRONWOOD_RUNTIME_HOME=str(install), SDKROOT=identity['sdk']['path'])
    prefix = [identity['jdk'] + '/bin/java', *identity['profile'], '-jar', str(jar)]
    commands = [([*prefix, str(source), '-d', str(out / 'classes'), '--unfreed=error'], 0),
                ([*prefix, '--link', '-cp', str(out / 'classes'), '--main-class', 'PathOrder',
                  '-o', str(out / 'program'), '--emit-llvm', str(out / 'output.ll'), '--unfreed=error',
                  '-O3', '--llvm-home', identity['llvm']], 0)]
    commands += [([str(out / 'program')], 1) for _ in range(2)]
    records = []
    for index, (argv, expected) in enumerate(commands):
        process = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True)
        for name, data in (('stdout', process.stdout), ('stderr', process.stderr)):
            (out / (str(index) + '.' + name + '.txt')).write_bytes(data)
        records.append({'argv': argv, 'returncode': process.returncode, 'expected': expected})
        (out / 'commands.json').write_text(json.dumps(records, indent=2) + '\n')
        assert process.returncode == expected
    (out / Path(__file__).name).write_bytes(Path(__file__).read_bytes())
    (out / 'qualification.json').write_text(json.dumps({'recorded_difference': True,
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'seed_sha256': identity['seed_jar_sha256'], 'java_unix_expected_exit': 0,
        'existing_ironwood_exit': 1, 'repeats': 2,
        'gate': 'B2/B3/B7 before first natural Path sort consumer; no equivalence claim',
        'scope': 'existing library/API evidence, no native compiler/helper pilot or implementation change'}, indent=2) + '\n')
    print('RECORDED: existing library path-order mismatch; two native exits one, Java Unix expects zero')


if __name__ == '__main__':
    main()
