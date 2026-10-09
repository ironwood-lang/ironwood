#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Capture original actual/formal domains without overwriting the discovery catalog."""
import csv
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
csv.field_size_limit(8 * 1024 * 1024)


def main():
    out = ROOT / 'docs/self-hosting/m0/argument-facts'
    if out.exists():
        raise ValueError('capture destination already exists; preserve it before a new capture')
    out.mkdir()
    identity = json.loads((ROOT / 'docs/self-hosting/m0/qualified/identity.json').read_text())
    install = Path(identity['launcher']).parent.parent
    sources = {name: sha for name, sha in identity['input_sha256'].items()
               if name.startswith('compiler/src/main/java/') and name.endswith('.java')}
    assert len(sources) == 460
    for name, sha in sources.items():
        assert hashlib.sha256((install / name).read_bytes()).hexdigest() == sha
    assert hashlib.sha256((install / 'lib/ironwoodc.jar').read_bytes()).hexdigest() == identity['seed_jar_sha256']
    scratch = ROOT / 'target/self-hosting-m0/argument-facts'
    scratch.mkdir(exist_ok=True)
    classes = scratch / 'classes'
    classes.mkdir(exist_ok=True)
    source = ROOT / 'scripts/self-hosting/ArgumentFacts.java'
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(key, None)
    jdk = Path(identity['jdk']) / 'bin'
    commands = [[str(jdk / 'javac'), '--release', '21', '-Xlint:all', '-Werror', '-d', str(classes), str(source)],
                [str(jdk / 'java'), *identity['profile'], '-cp', str(classes), 'ArgumentFacts', str(install), str(scratch / 'facts.tsv')]]
    records = []
    for index, command in enumerate(commands):
        process = subprocess.run(command, env=env, capture_output=True)
        (out / (str(index) + '.stdout')).write_bytes(process.stdout)
        (out / (str(index) + '.stderr')).write_bytes(process.stderr)
        records.append({'argv': command, 'returncode': process.returncode})
        if process.returncode: raise ValueError(process.stderr.decode())
    raw = (scratch / 'facts.tsv').read_bytes()
    (out / 'facts.tsv.gz').write_bytes(gzip.compress(raw, mtime=0))
    (out / 'tool-source.java.gz').write_bytes(gzip.compress(source.read_bytes(), mtime=0))
    with (scratch / 'facts.tsv').open() as stream: facts = list(csv.DictReader(stream, delimiter='\t', quoting=csv.QUOTE_NONE))
    with gzip.open(ROOT / 'docs/self-hosting/m0/inventory/calls.tsv.gz', 'rt') as stream:
        original = [r for r in csv.DictReader(stream, delimiter='\t', quoting=csv.QUOTE_NONE) if not r['declaring_owner'].startswith('ironwood.')]
    fields = ('file', 'start_utf16', 'end_utf16', 'consumer', 'kind', 'declaring_owner', 'resolved_signature')
    key = lambda row: tuple(row[k] for k in fields)
    actual = [row for row in facts if row['part'] == 'receiver']
    assert len(actual) == len(original) == 30979 and {key(r) for r in actual} == {key(r) for r in original}
    report = {'schema': 1, 'scope': 'exact actual/formal types and literal/constant operands; no null/alias/lifetime inference for dynamic expressions',
              'J0_seed_sha256': identity['seed_jar_sha256'], 'tool_source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'raw_sha256': hashlib.sha256(raw).hexdigest(), 'raw_bytes': len(raw), 'rows': len(facts), 'calls': len(actual), 'commands': records}
    (out / 'qualification.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS:', len(actual), 'exact original calls;', len(facts), 'receiver/operand facts')


if __name__ == '__main__':
    main()
