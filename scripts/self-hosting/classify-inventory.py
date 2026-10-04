#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Generate review candidates, never completion evidence, from attributed uses."""
import csv
import gzip
import hashlib
from collections import Counter, defaultdict
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/self-hosting/m0/inventory'


def read(name):
    path = OUT / name
    stream = path.open() if path.exists() else gzip.open(str(path) + '.gz', 'rt')
    with stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def write(name, rows):
    with (OUT / name).open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def stage(file):
    if '/lexer/' in file or '/parser/' in file or '/source/' in file:
        return 'S1', 'M1.3', 'frontend'
    if '/ast/' in file or '/ir/' in file:
        return 'S2', 'M3.1', 'representations'
    if '/semantic/' in file:
        return 'S3', 'M3.1', 'ownership'
    if '/backend/' in file:
        return 'S4', 'M3.3', 'llvm'
    if '/bridge/' in file or 'Bridge' in file:
        return 'S7', 'M6.2', 'artifact'
    if '/doc/' in file or file.endswith(('IronClass.java', 'IronJar.java', 'IronJarMain.java')):
        return 'S6', 'M5.4', 'artifact'
    return 'S4', 'M3.3', 'llvm'


def contract(owner, signature):
    method = signature.split('(')[0].split('>')[-1]
    if owner == 'Array' and signature == 'length':
        return 'directly supported candidate', 'B7', 'array length: primitive int read, no allocation or traversal; null receiver fails'
    if owner == 'Array' and signature == 'Array(int)':
        return 'local rewrite candidate', 'B7', 'array constructor reference: explicit new array helper; negative length fails; fresh shallow storage'
    if owner == 'java.lang.Character' and signature == 'toCodePoint(char,char)':
        return 'local rewrite candidate', 'B7', 'StringPool S3: combine caller-validated surrogate pair with primitive arithmetic; preserve U+FFFD for unpaired input'
    if owner.startswith('java.util.zip.') or owner.startswith('java.util.jar.'):
        return 'library extension', 'B6', 'archive: exact entry names, CRC, bounds, malformed input; explicit close and failure cleanup'
    if owner.startswith('java.security.MessageDigest') or owner == 'java.util.HexFormat':
        return 'local rewrite', 'B5', 'digest: SHA-256 before S3, MD5 before S4; exact raw bytes and established framing; caller owns result buffer'
    if owner.startswith('java.nio.file.'):
        return 'library extension', 'B3', 'filesystem: preserve path/null/IO failure and replacement options; caller closes resources; no publication approximation'
    if owner in ('java.lang.Process', 'java.lang.ProcessBuilder', 'java.lang.Thread'):
        return 'host service', 'B4', 'process: absolute executable, argv, inherited environment/cwd, both outputs, exit and exceptional child cleanup'
    if owner.startswith(('java.lang.Class', 'java.lang.reflect.', 'java.security.Protection', 'java.security.CodeSource', 'java.net.URL')) or owner in ('javax.tools.Tool', 'javax.tools.ToolProvider', 'java.lang.Runtime', 'java.lang.Runtime.Version'):
        return 'local rewrite', 'B7', 'host/reflection: explicit variant visitor and installation metadata; source-only shell bootstrap; preserve null and failure'
    if owner.startswith('java.lang.ref.'):
        return 'local rewrite', 'B7', 'evidence: explicit snapshot ownership/retirement and existing budget; primary safety independent of optional evidence'
    if owner.startswith(('java.util.regex.', 'java.util.stream.', 'java.util.function.')):
        return 'local rewrite', 'B7', 'loops/callbacks: preserve encounter/short-circuit order, selector evaluation and exceptions; explicit captures/owner; no wrapper per node'
    if owner.startswith(('java.util.',)):
        if owner == 'java.util.Comparator' or 'sort(' in signature or owner.startswith(('java.util.TreeMap', 'java.util.TreeSet')):
            return 'library extension', 'B2', 'order: stable semantic comparator with deterministic ties; comparator-zero equivalence for trees; borrowed elements'
        if owner.startswith(('java.util.Optional', 'java.util.Objects')):
            return 'local rewrite', 'B7', 'presence/value: explicit presence, nullable results, value equality/hash; preserve eager/lazy evaluation and null exceptions'
        if owner == 'java.util.Properties':
            return 'local rewrite', 'B7', 'properties: exact admitted grammar/defaults and sorted identity serialization; owned strings and explicit stream closure'
        return 'library extension', 'B1', 'container: independent backing storage, borrowed elements, recorded identity/value and null rules; local traversal; failure rollback; explicit cleanup'
    if owner.startswith(('java.nio.',)):
        return 'local rewrite', 'B7', 'bytes/UTF-8: explicit endian loads/stores, checked bounds, strict decoder versus replacement constructor; fresh caller-owned buffers'
    if owner.startswith('java.math.'):
        return 'local rewrite', 'B7', 'numeric: explicit constant kind and fixed-width payload, exact overflow/sign/radix; distinguish boxed identity from value'
    if owner == 'Array':
        return 'local rewrite', 'B1', 'array: fresh shallow copy, preserve element identity; independent storage; caller owns and shallow-frees array'
    if owner.startswith('java.io.'):
        if owner in ('java.io.PrintStream', 'java.io.IOException'):
            return 'directly supported', 'B7', 'existing IO surface: package spelling rewrite; best-effort print; preserve checked failure/cause and null behavior'
        return 'local rewrite', 'B7', 'stream: preserve byte boundaries, returned storage ownership and close/free distinction; explicit failure cleanup'
    if owner == 'javax.lang.model.SourceVersion':
        return 'local rewrite', 'B7', 'identifier: qualified name/keyword validation, Java-21 identifier-part by S6; frontend ASCII-start contract retained'
    if owner == 'java.lang.String':
        supported = {'charAt','compareTo','contains','endsWith','equals','equalsIgnoreCase','indexOf','isBlank',
                     'isEmpty','lastIndexOf','length','repeat','replace','startsWith','substring','toCharArray','trim',
                     'strip','valueOf'}
        if method in supported or signature == 'String(char[])':
            return 'directly supported', 'B7', 'String: UTF-16 offsets, null/bounds, exact overload and literal replacement; owned result or borrowed identity as API documents'
        return 'local rewrite', 'B7', 'text: no regex/stream/Formatter/Locale facade; exact local grammar/normalization/UTF-8 helper; preserve admitted null/bounds/results'
    if owner in ('java.lang.Integer','java.lang.Long','java.lang.Float','java.lang.Double','java.lang.Character','java.lang.Boolean','java.lang.Byte','java.lang.Short','java.lang.Number'):
        supported = {'parseInt','toHexString','toString','compareUnsigned','parseFloat','parseDouble','isNaN',
                     'isHighSurrogate','isLowSurrogate','isSurrogate','isWhitespace','toCodePoint','digit'}
        if method in supported:
            return 'directly supported', 'B7', 'numeric/character: exact primitive overload; preserve radix, malformed input, signed zeros and UTF-16 behavior; no boxing'
        return 'local rewrite', 'B7', 'numeric/nullable wrapper: primitive payload/presence flag; exact raw bits, unsigned conversion and identity; identifier-part by S6'
    if owner == 'java.lang.System':
        if method in ('arraycopy', 'exit') or signature in ('out', 'err'):
            return 'directly supported', 'B7', 'System: existing copy bounds/overlap and process exit; console best-effort; borrowed stream'
        return 'host service', 'B4', 'environment/installation: explicit host configuration; preserve absence/null; stable semantic identity replaces VM identity hash'
    if signature == 'getClass()':
        return 'local rewrite', 'B7', 'dispatch: explicit compiler node kind, finite variant coverage; no runtime Class'
    return 'local rewrite', 'B7', 'language/base: preserve exact overload/default and value/identity/null/exception behavior; explicit syntax or package adaptation'


def main():
    calls, variables, uses = read('calls.tsv'), read('containers.tsv'), read('references.tsv')
    grouped = defaultdict(list)
    for row in calls:
        if not row['declaring_owner'].startswith('ironwood.'):
            grouped[row['declaring_owner'], row['resolved_signature'], row['kind']].append(row)
    dependencies = []
    for index, ((owner, signature, kind), rows) in enumerate(sorted(grouped.items()), 1):
        status, b, behavior = contract(owner, signature)
        if not status.endswith(' candidate'):
            status += ' candidate, exact call review required'
        consumers = sorted({':'.join(stage(r['file'])[:2]) for r in rows})
        dependencies.append(dict(id=f'API{index:04}', owner=owner, signature=signature, kind=kind,
            category=status, B=b, phase_consumers=';'.join(consumers), contract=behavior,
            allocation_owner='caller owns fresh results/builders; inputs borrowed unless explicit ownership transfer',
            retention_cleanup='invocation graphs retained; independent temporaries only freed by proof; close resources on all paths',
            fixture=';'.join(sorted({stage(r['file'])[2] for r in rows})),
            callers=';'.join(f"{r['file']}:{r['line']}@{r['start_utf16']}" for r in rows)))
    write('dependencies-discovery.tsv', dependencies)
    references = defaultdict(list)
    for row in uses:
        references[row['symbol']].append(row)
    ledger = []
    for variable in variables:
        if not re.search(r'(Map|Set)(<|$)', variable['type']):
            continue
        rows = references[variable['symbol']]
        # No classification is inferred from a constructor's name. Any escape,
        # view, copy, or helper argument remains visible as an order obligation.
        simple = {'get','getOrDefault','put','putIfAbsent','contains','containsKey','containsValue',
                  'add','remove','clear','size','isEmpty'}
        terminal = []
        flowing = []
        name = variable['symbol'].split('::')[-1]
        for row in rows:
            operation = row['expression'].split('.')[-1]
            if row['parent_kind'] == 'MEMBER_SELECT' and operation in simple:
                terminal.append(row)
            else:
                flowing.append(row)
        s, phase, fixture = stage(variable['file'])
        identity = 'IdentityHashMap' in variable['initializer'] or 'IdentityHashMap' in variable['type']
        key = variable['type'].split('<', 1)[-1].split(',', 1)[0].rstrip('>')
        if rows and not flowing:
            classification = 'candidate lookup/membership only, review required'
            boundary = 'all attributed references are terminal key/membership operations; no view/copy/helper/return flow'
            replacement = 'retain hashed lookup with existing equality and null contract'
        else:
            classification = 'unresolved discovery, review required'
            boundary = 'conservative order obligation at first nonterminal use; trace each listed flow before phase qualification'
            replacement = ('identity lookup plus source-ordered key list' if identity else 'linked container from original ordered producer')
        ledger.append(dict(file=variable['file'], line=variable['line'], symbol=variable['symbol'],
            type=variable['type'], initializer=variable['initializer'], equality=('identity' if identity else 'declared key value equality; inspect key record/class contract'),
            key=key, mutation='see all attributed uses; copied mutable state requires independent storage',
            classification=classification, first_order_boundary=boundary,
            required_order='source/insertion order established at original producer; semantic key and source-position ties for originally sorted producers; never hash/address order',
            replacement=replacement, B='B1/B2', phase=phase, first_consumer=s, fixture=fixture,
            all_uses=';'.join(f"{r['file']}:{r['line']}@{r['start_utf16']}:{r['parent_kind']}:{r['expression']}" for r in rows),
            downstream_flows=';'.join(f"{r['file']}:{r['line']}:{r['expression']}" for r in flowing)))
    write('ordering-discovery.tsv', ledger)
    counts = dict(calls=len(calls), external_patterns=len(dependencies), container_variables=len(variables),
                  map_set_variables=len(ledger), container_references=len(uses),
                  classifications=dict(Counter(r['classification'] for r in ledger)),
                  syntax=dict(Counter(r['kind'] for r in read('syntax.tsv'))))
    (OUT / 'counts.json').write_text(json.dumps(counts, indent=2, sort_keys=True) + '\n')
    print(json.dumps(counts, indent=2))
    if '--archive' in sys.argv:
        manifest = {}
        for path in sorted(OUT.glob('*.tsv')):
            data = path.read_bytes()
            archived = path.with_suffix('.tsv.gz')
            archived.write_bytes(gzip.compress(data, mtime=0))
            manifest[path.name] = {'uncompressed_sha256': hashlib.sha256(data).hexdigest(),
                                   'rows': data.count(b'\n') - 1}
            path.unlink()
        (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
