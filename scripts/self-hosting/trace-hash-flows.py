#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Discover transitive hash-origin traversal sites for individual source review."""
import csv
import gzip
import json
from collections import defaultdict, deque
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / 'docs/self-hosting/m0/inventory'


def read(name):
    with gzip.open(OUT / (name + '.tsv.gz'), 'rt') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def write(name, rows):
    with (OUT / (name + '.tsv')).open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def main():
    seeds = []
    for row in read('calls'):
        owner, signature = row['declaring_owner'], row['resolved_signature']
        unordered = owner in ('java.util.HashMap', 'java.util.HashSet', 'java.util.IdentityHashMap') and row['kind'] in ('CONSTRUCTOR', 'REFERENCE')
        factory = owner in ('java.util.Map', 'java.util.Set') and any(x in signature for x in ('of(', 'ofEntries(', 'copyOf('))
        collector = owner == 'java.util.stream.Collectors' and 'java.util.function.Supplier' not in signature and any(x in signature for x in ('toMap(', 'toSet(', 'toUnmodifiableMap(', 'toUnmodifiableSet('))
        if unordered or factory or collector:
            seeds.append(dict(id=f'H{len(seeds)+1:04}', node=f"E:{row['file']}:{row['start_utf16']}:{row['end_utf16']}",
                file=row['file'], line=row['line'], owner=owner, signature=signature,
                consumer=row['consumer'], expression=row['expression'],
                origin_kind='constructor/reference' if unordered else 'factory' if factory else 'collector'))
    graph = defaultdict(set)
    for edge in read('flow'):
        graph[edge['from']].add(edge['to'])
        if edge['kind'] not in ('RETAINED', 'RETRIEVED_ELEMENT', 'ITERATED_ELEMENT'):
            graph['C:' + edge['from']].add('C:' + edge['to'])
    origins = defaultdict(int)
    work = deque()
    for index, seed in enumerate(seeds):
        origins[seed['node']] |= 1 << index
        work.append(seed['node'])
    while work:
        node = work.popleft()
        bits = origins[node]
        for target in graph[node]:
            merged = origins[target] | bits
            if merged != origins[target]:
                origins[target] = merged
                work.append(target)
    traversals = []
    for row in read('traversals'):
        bits = origins[row['node']]
        if bits:
            row['hash_origins'] = ';'.join(seed['id'] for index, seed in enumerate(seeds) if bits & (1 << index))
            row['review_status'] = 'unresolved discovery'
            traversals.append(row)
    write('hash-sources-discovery', seeds)
    write('hash-traversals-discovery', traversals)
    print(json.dumps(dict(sources=len(seeds), transitive_traversals=len(traversals),
                         traversal_files=len({r['file'] for r in traversals})), indent=2))


if __name__ == '__main__':
    main()
