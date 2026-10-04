#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check discovery coverage against a deliberate multi-hop Java source fixture."""
import argparse
import csv
from collections import defaultdict
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    scratch = ROOT / 'target/self-hosting-m0/inventory-qualification'
    source = scratch / 'compiler/src/main/java/ironwood/audit/InventorySample.java'
    source.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'scripts/self-hosting/fixtures/InventorySample.java', source)
    classes = scratch / 'tooling'
    classes.mkdir(exist_ok=True)
    subprocess.run([str(args.jdk / 'bin/javac'), '--release', '21', '-Xlint:all', '-Werror',
                    '-d', str(classes), str(ROOT / 'scripts/self-hosting/Inventory.java')], check=True)
    output = scratch / 'inventory'
    subprocess.run([str(args.jdk / 'bin/java'), '-Xms256m', '-Xmx4096m', '-Xss8m', '-XX:+UseG1GC',
                    '-cp', str(classes), 'Inventory', str(scratch), str(output)], check=True)

    def read(name):
        with (output / name).open() as stream:
            return list(csv.DictReader(stream, delimiter='\t'))

    calls = read('calls.tsv')
    assert any(r['declaring_owner'] == 'Array' and r['resolved_signature'] == 'length' for r in calls)
    assert any(r['declaring_owner'] == 'Array' and r['kind'] == 'REFERENCE' for r in calls)
    assert {'<E>of(E)', '<E>of(E,E)'} <= {r['resolved_signature'] for r in calls if r['declaring_owner'] == 'java.util.List'}
    assert any(r['resolved_signature'] == 'addAll(java.util.Collection<? extends E>)' for r in calls)
    captures = read('captures.tsv')
    assert any(r['captured_symbol'].endswith('::captured') for r in captures)
    assert any(r['captured_symbol'].endswith('::result') for r in captures)
    assert any(r['captured_symbol'].endswith('::this') for r in captures)
    declarations = read('containers.tsv')
    assert len({r['symbol'] for r in declarations if r['symbol'].endswith('::sibling')}) == 2
    graph = defaultdict(set)
    for edge in read('flow.tsv'):
        graph[edge['from']].add(edge['to'])
    seed = next(r for r in calls if r['declaring_owner'] == 'java.util.HashSet' and r['kind'] == 'CONSTRUCTOR')
    start = f"E:{seed['file']}:{seed['start_utf16']}:{seed['end_utf16']}"
    visited, work = set(), [start]
    while work:
        node = work.pop()
        if node not in visited:
            visited.add(node)
            work.extend(graph[node])
    assert any('Snapshot::items' in n for n in visited), 'lost record field flow'
    assert any(n.startswith('V:') and n.endswith('::map') for n in visited), 'lost collector flow'
    assert any(n.startswith('V:') and n.endswith('::result') for n in visited), 'lost copy/view flow'
    traversals = read('traversals.tsv')
    assert any(r['node'] in visited and 'map.keySet' in r['expression'] for r in traversals)
    print('PASS: overloads, inherited members, arrays, captures, helper/record/factory/view/copy flow')


if __name__ == '__main__':
    main()
