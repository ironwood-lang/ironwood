#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check discovery coverage against a deliberate multi-hop Java source fixture."""
import argparse
import csv
import gzip
import importlib.util
import os
from collections import defaultdict
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    scratch = ROOT / 'target/self-hosting-m0/inventory-qualification'
    source = scratch / 'compiler/src/main/java/ironwood/audit/InventorySample.java'
    source.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'scripts/self-hosting/fixtures/InventorySample.java', source)
    classes = scratch / 'tooling'
    classes.mkdir(exist_ok=True)
    subprocess.run([str(args.jdk / 'bin/javac'), '--release', '21', '-Xlint:all', '-Werror',
                    '-d', str(classes), str(ROOT / 'scripts/self-hosting/Inventory.java')], check=True, env=env)
    output = scratch / 'inventory'
    subprocess.run([str(args.jdk / 'bin/java'), '-Xms256m', '-Xmx4096m', '-Xss8m', '-XX:+UseG1GC',
                    '-cp', str(classes), 'Inventory', str(scratch), str(output)], check=True, env=env)

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
    compact = next(r for r in calls if 'Snapshot' in r['consumer'] and r['declaring_owner'] == 'java.util.Set' and 'copyOf' in r['resolved_signature'])
    compact_node = f"E:{compact['file']}:{compact['start_utf16']}:{compact['end_utf16']}"
    compact_visited, compact_work = set(), [compact_node]
    while compact_work:
        node = compact_work.pop()
        if node not in compact_visited:
            compact_visited.add(node)
            compact_work.extend(graph[node])
    assert any('Snapshot::items' in n for n in compact_visited), 'lost compact constructor copy flow'
    assert any(n.startswith('V:') and n.endswith('::map') for n in visited), 'lost collector flow'
    assert any(n.startswith('V:') and n.endswith('::result') for n in visited), 'lost copy/view flow'
    assert any(n.startswith('V:') and n.endswith('::ordered') for n in visited), 'lost bulk-copy order flow'
    assert any(n.startswith('V:') and n.endswith('::bulkCopy') for n in visited), 'lost linked destination traversal'
    assert any(n.startswith('V:') and n.endswith('::destinationAlias') for n in visited), 'lost returned/parameter mutation alias'
    assert any(n.startswith('V:') and n.endswith('InventorySample::carried') for n in visited), 'lost assigned field alias'
    assert any(n.startswith('V:') and n.endswith('::viaField') for n in visited), 'lost alias mutation traversal'
    traversals = read('traversals.tsv')
    assert any(r['node'] in visited and 'map.keySet' in r['expression'] for r in traversals)
    syntax = read('syntax.tsv')
    assert {'VAR', 'TEXT_BLOCK', 'SYNCHRONIZED_METHOD', 'UNINITIALIZED', 'ENHANCED_FOR_VARIABLE'} <= {r['kind'] for r in syntax}
    assert all(r['context'].startswith('excluded') for r in syntax if r['kind'] in ('VAR', 'UNINITIALIZED'))
    assert all(r['context'].startswith('supported iteration binding:') for r in syntax if r['kind'] == 'ENHANCED_FOR_VARIABLE')
    assert sum(r['kind'] == 'UNINITIALIZED' for r in syntax) == 1, 'iteration binding is not an optional initializer'
    for name in ('calls', 'flow', 'traversals'):
        (output / (name + '.tsv.gz')).write_bytes(gzip.compress((output / (name + '.tsv')).read_bytes(), mtime=0))
    specification = importlib.util.spec_from_file_location('hash_trace', ROOT / 'scripts/self-hosting/trace-hash-flows.py')
    trace = importlib.util.module_from_spec(specification)
    sys.dont_write_bytecode = True
    specification.loader.exec_module(trace)
    trace.OUT = output
    trace.main()
    hash_sources = read('hash-sources-discovery.tsv')
    constructor_reference = next(r for r in hash_sources if r['expression'] == 'HashSet::new' and r['signature'].startswith('HashSet('))
    assert any(constructor_reference['id'] in r['hash_origins'].split(';') and 'suppliedCopy.forEach' in r['expression']
               for r in read('hash-traversals-discovery.tsv')), 'lost constructor supplier through collector/result copy/traversal'
    assert not any(r['expression'] == 'original::add' or r['signature'] == 'add(E)' for r in hash_sources), 'bound mutation reference is not a new hash origin'
    print('PASS: overloads, inherited members, arrays, captures, syntax, helper/record/factory/view/copy flow')


if __name__ == '__main__':
    main()
