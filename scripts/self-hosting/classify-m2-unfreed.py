#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Classify the M2 pilots' --unfreed=warn findings and local suppressions.

Reads each pilot command's complete compile or link log and records, per
command, its missing-free warnings, any other warnings and its mandatory
errors. Findings are keyed by source site (path relative to the tree, line,
column and message), so a site reported by both the compile and the link
command counts once; a link names the source inside its class archive, which
is mapped back to the pilot source of that name. Every unique site must have exactly one reviewed
classification (invocation-lifetime retention, temporary cleanup work, or a
diagnostic/proof limit) and every reviewed site must still be reported; every
`@SuppressUnfreed` directive in the pilot sources must have a reviewed
justification. Any other warning, any mandatory error, an unreviewed or stale
site, or an unreviewed suppression fails the check.
"""
import argparse
import json
import re
from pathlib import Path

CLASSES = ('invocation-lifetime', 'temporary-cleanup', 'proof-limit')
MISSING_FREE = re.compile(r'without being freed')
HEADER = re.compile(r'^(warning|error): (.*)$')
LOCATION = re.compile(r'^\s*--> (.*):(\d+):(\d+)$')
SUPPRESSION = re.compile(r'@SuppressUnfreed\s+[A-Za-z_][\w<>, .\[\]]*\s+([A-Za-z_]\w*)\s*=')


def resolve(path, sources):
    # A link reports a site inside its class archive: CLASSES/X.ironclass!/source/NAME.
    # Map it back to the one pilot source with that name.
    if path is None or '!/source/' not in path:
        return path
    suffix = path.split('!/source/', 1)[1]
    matches = [source for source in sources if source == suffix or source.endswith('/' + suffix)]
    return matches[0] if len(matches) == 1 else path


def findings(log, tree, sources):
    result = []
    lines = log.read_text().splitlines()
    for index, line in enumerate(lines):
        header = HEADER.match(line)
        if not header:
            continue
        location = LOCATION.match(lines[index + 1]) if index + 1 < len(lines) else None
        path = location.group(1) if location else None
        if path and path.startswith(str(tree) + '/'):
            path = path[len(str(tree)) + 1:]
        path = resolve(path, sources)
        result.append({'severity': header.group(1), 'message': header.group(2), 'path': path,
                       'line': int(location.group(2)) if location else None,
                       'column': int(location.group(3)) if location else None})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tree', type=Path, required=True, help='root the logs report paths under')
    parser.add_argument('--log', action='append', required=True, help='COMMAND=LOG')
    parser.add_argument('--sources', type=Path, required=True, help='file listing the pilot sources, one per line')
    parser.add_argument('--review', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    review = json.loads(args.review.read_text())
    reviewed = {(s['path'], s['line'], s['column'], s['message']): s for s in review['sites']}
    commands = []
    sites = {}
    problems = []
    sources = [line for line in args.sources.read_text().splitlines() if line]
    for entry in args.log:
        name, path = entry.split('=', 1)
        found = findings(Path(path), args.tree.resolve(), sources)
        missing = [f for f in found if f['severity'] == 'warning' and MISSING_FREE.search(f['message'])]
        other = [f for f in found if f['severity'] == 'warning' and not MISSING_FREE.search(f['message'])]
        errors = [f for f in found if f['severity'] == 'error']
        commands.append({'command': name, 'log': path, 'missing_free_findings': len(missing),
                         'other_warnings': len(other), 'mandatory_errors': len(errors),
                         'unique_sites': len({(f['path'], f['line'], f['column'], f['message']) for f in missing})})
        problems += ['%s: %s %s' % (name, f['severity'], f['message']) for f in other + errors]
        for finding in missing:
            key = (finding['path'], finding['line'], finding['column'], finding['message'])
            sites.setdefault(key, []).append(name)
    classified = []
    for key, reporters in sorted(sites.items(), key=lambda item: (item[0][0] or '', item[0][1] or 0, item[0][2] or 0)):
        decision = reviewed.get(key)
        if decision is None or decision.get('classification') not in CLASSES or not decision.get('justification'):
            problems.append('unreviewed site %s:%s:%s %s' % key)
            continue
        classified.append({'path': key[0], 'line': key[1], 'column': key[2], 'message': key[3],
                           'reported_by': reporters, 'classification': decision['classification'],
                           'justification': decision['justification']})
    for key in reviewed:
        if key not in sites:
            problems.append('stale review %s:%s:%s %s' % key)
    suppressions = []
    known = {(s['path'], s['variable']): s for s in review.get('suppressions', [])}
    for source in sources:
        for number, line in enumerate((args.tree / source).read_text().splitlines(), 1):
            match = SUPPRESSION.search(line)
            if not match:
                continue
            # Each suppressed variable needs its own review, so names are unique per file.
            if any((s['path'], s['variable']) == (source, match.group(1)) for s in suppressions):
                problems.append('ambiguous suppression %s:%d %s' % (source, number, match.group(1)))
                continue
            decision = known.get((source, match.group(1)))
            if decision is None or not decision.get('justification'):
                problems.append('unreviewed suppression %s:%d %s' % (source, number, match.group(1)))
                continue
            suppressions.append({'path': source, 'line': number, 'variable': match.group(1),
                                 'justification': decision['justification']})
    for key in known:
        if not any((s['path'], s['variable']) == key for s in suppressions):
            problems.append('stale suppression review %s %s' % key)
    totals = {name: sum(1 for site in classified if site['classification'] == name) for name in CLASSES}
    report = {'commands': commands, 'unique_sites': classified, 'classification_totals': totals,
              'suppressions': suppressions, 'problems': problems}
    args.output.write_text(json.dumps(report, indent=1) + '\n')
    for command in commands:
        print('%-24s missing-free %d (unique %d), other warnings %d, mandatory errors %d' % (
            command['command'], command['missing_free_findings'], command['unique_sites'], command['other_warnings'],
            command['mandatory_errors']))
    print('unique sites', len(classified), totals, 'suppressions', len(suppressions), 'problems', len(problems))
    for problem in problems:
        print('PROBLEM', problem)
    return 1 if problems else 0


if __name__ == '__main__':
    raise SystemExit(main())
