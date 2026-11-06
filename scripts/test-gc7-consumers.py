#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused GC7 migrated consumer checks, with explicit selections and RSS bounds."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('gc_engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'llvm-home', 'java-home', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--case', action='append', choices=('core', 'pools', 'library', 'examples', 'retained'), required=True)
    args = p.parse_args()
    sdk, llvm, java, out = (getattr(args, name).resolve() for name in ('sdk', 'llvm_home', 'java_home', 'output'))
    engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    command = [java / 'bin/java', '-jar', ROOT / 'compiler/build/ironwoodc.jar']
    env = {'JAVA_HOME': str(java), 'IRONWOOD_GC_HOME': str(sdk)}
    fixtures = []
    cases = ROOT / 'integration-tests/cases'
    if 'core' in args.case:
        fixtures += [(cases / (name + '.iron'), 'Main', 42, None) for name in
                     ('gc7_shared_graphs', 'creation_array_cleanup', 'frontend_correctness', 'defer_calls')]
        fixtures += [(cases / 'defer_server_failure.iron', 'Main', 74, None)]
    if 'pools' in args.case:
        fixtures += [(cases / (name + '.iron'), 'Main', 42, None) for name in
                     ('owning_pools', 'pool_release_helpers', 'ds_caller_item_reclamation', 'unread_primitive_stores')]
    if 'library' in args.case:
        for package, name in [('pool', 'PoolRetentionTests'), ('ds', 'CollectionRetentionTests'), ('bench', 'BenchTests')]:
            fixtures.append((ROOT / 'stdlib/test/ironwood' / package / (name + '.iron'), 'ironwood.' + package + '.' + name, 0,
                             ROOT / 'stdlib/test'))
    if 'examples' in args.case:
        for folder, package, name in [('reclamation', 'reclamation', 'ExplicitReclamation'),
                                      ('deferredcleanup', 'deferredcleanup', 'DeferredCleanup')]:
            source = ROOT / 'examples' / folder / 'src/main/ironwood'
            fixtures.append((source / 'org/ironwood' / package / (name + '.iron'), 'org.ironwood.' + package + '.' + name, 42, source))
    if 'retained' in args.case:
        fixtures += [(cases / (name + '.iron'), 'Main', 42, None) for name in
                     ('gc7_constructor_dispatch', 'gc7_retained_visitor')]
        visitor_input = out / 'visitor-input'; visitor_input.mkdir()
        (visitor_input / 'kept.txt').write_text('abc')
    results = []
    for index, (source, entry, expected, sourcepath) in enumerate(fixtures):
        label = str(index) + '-' + source.stem
        classes, binary = out / (label + '-classes'), out / label
        extra = ['-cp', ROOT / 'compiler/build/ironwood-testing.ironjar'] if sourcepath == ROOT / 'stdlib/test' else []
        if sourcepath: extra += ['--source-path', sourcepath]
        results.append(engine.run(list(map(str, [*command, source, '-d', classes, *extra])), raw, label + '-compile', env))
        classpath = str(classes)
        if sourcepath == ROOT / 'stdlib/test': classpath += ':' + str(ROOT / 'compiler/build/ironwood-testing.ironjar')
        results.append(engine.run(list(map(str, [*command, '--link', '-cp', classpath, '--main-class', entry, '-O3',
            '--llvm-home', llvm, '--emit-llvm', str(binary) + '.ll', '-o', binary])), raw, label + '-link', env))
        results.append(engine.run([str(binary), *([str(visitor_input)] if source.stem == "gc7_retained_visitor" else [])], raw, label + '-run', env, expected_exit=expected))
        if (raw / (label + '-run.stderr')).read_text(): raise RuntimeError('unexpected stderr: ' + label)
        print(label + ': passed', flush=True)
    (out / 'results.json').write_text(json.dumps({'format': 1, 'selections': args.case,
        'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60, 'results': results}, indent=2) + '\n')


if __name__ == '__main__': main()
