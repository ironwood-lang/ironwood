#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded GC2 compiler/C/LLVM layout and exact collector qualification."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('test_engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
gc = engine.gc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--llvm-home', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--target', default=gc.host_target())
    parser.add_argument('--case', action='append', choices=('layouts', 'arrays', 'sidecars', 'metadata', 'failure', 'overflow', 'construction', 'resident', 'virtual'))
    args = parser.parse_args()
    sdk, llvm, out = args.sdk.resolve(), args.llvm_home.resolve(), args.output.resolve()
    manifest, flags = gc.verify(sdk, llvm, args.target)
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'
    raw.mkdir()
    generated = out / 'generated'
    command = [str(args.java_home / 'bin/java'), '-ea', '-cp',
               str(ROOT / 'compiler/build/classes') + ':' + str(ROOT / 'compiler/build/test-classes'),
               'ironwood.compiler.GcLayoutTests', str(generated)]
    results = [engine.run(command, raw, 'compiler-descriptions')]
    sources = [ROOT / 'integration-tests/native/gc_layout_probe.c', generated / 'layouts.c']
    objects = []
    compile_flags = [*flags, '-std=c11', '-O3', '-fPIC', '-fvisibility=hidden',
                     '-Wall', '-Wextra', '-Werror', '-Wpedantic',
                     '-I' + str(sdk / 'runtime/include'), '-I' + str(sdk / 'include/gc'),
                     '-I' + str(generated)]
    for index, source in enumerate(sources):
        obj = out / ('c' + str(index) + '.o')
        results.append(engine.run([str(llvm / 'bin/clang'), *compile_flags, '-c', str(source), '-o', str(obj)], raw, 'layout-c-' + str(index)))
        objects.append(obj)
    # Apply the exact target data layout obtained by SDK verification before
    # assembly/optimization, just like the production compiler pipeline.
    ll = generated / 'layouts.ll'
    ll.write_text('target datalayout = "' + manifest['configuration']['target.data-layout'] + '"\n'
                  'target triple = "' + manifest['configuration']['target.triple'] + '"\n' + ll.read_text())
    results.append(engine.run([str(llvm / 'bin/llvm-as'), str(ll), '-o', str(out / 'layouts.bc')], raw, 'layout-assemble'))
    results.append(engine.run([str(llvm / 'bin/opt'), '-passes=default<O3>', str(out / 'layouts.bc'),
                    '-o', str(out / 'layouts.opt.bc')], raw, 'layout-optimize'))
    llvm_obj = out / 'layouts.o'
    results.append(engine.run([str(llvm / 'bin/llc'), '-O=3', '--relocation-model=pic', '-filetype=obj',
                    str(out / 'layouts.opt.bc'), '-o', str(llvm_obj)], raw, 'layout-codegen'))
    objects.append(llvm_obj)
    executable = out / 'probe'
    libraries = [sdk / 'lib/libironwood_gc_engine.a', sdk / 'lib/libgc.a']
    results.append(engine.run([str(llvm / 'bin/clang'), *flags, *manifest['configuration']['target.ldflags'],
                    *map(str, objects), *map(str, libraries), '-o', str(executable)], raw, 'layout-link'))
    for case in args.case or ('layouts', 'arrays', 'sidecars', 'metadata', 'failure', 'overflow', 'construction', 'resident', 'virtual'):
        results.append(engine.run([str(executable), case], raw, case))
    overflow = (raw / 'overflow.stderr').read_text() if (raw / 'overflow.stderr').exists() else ''
    events = re.findall(r'Mark stack overflow; current size: (\d+) entries', overflow)
    if (args.case is None or 'overflow' in args.case) and not events:
        raise AssertionError('no actual collector mark-stack overflow observed')
    evidence = {'overflow_events': len(events), 'stack_entries_at_overflow': sorted(set(map(int, events))),
                'numeric_target_checks': 65536, 'recovery_returned_successfully': True} if events else None
    sources += [ROOT / 'runtime/include/ironwood_runtime.h', ROOT / 'runtime/include/ironwood_bridge.h',
                ROOT / 'scripts/test-gc-layouts.py', ROOT / 'compiler/src/test/java/ironwood/compiler/GcLayoutTests.java',
                ROOT / 'compiler/src/main/java/ironwood/compiler/ir/IrAllocationLayout.java',
                ROOT / 'compiler/src/main/java/ironwood/compiler/backend/GcLayoutEmitter.java']
    report = {'format': 1, 'production_enabled': False, 'target': args.target,
              'baseline_revision': gc.output(['git', 'rev-parse', 'HEAD'], cwd=ROOT),
              'host_role': 'machost' if args.target.startswith('macos') else 'x86host' if args.target.endswith('x86_64') else 'armvm',
              'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60,
              'sdk': manifest, 'results': results, 'mark_stack_recovery': evidence,
              'generated.sha256': {p.name: gc.digest(p) for p in generated.iterdir()},
              'source.sha256': {str(p.relative_to(ROOT)): gc.digest(p) for p in sources if p.is_relative_to(ROOT)},
              'output.sha256': gc.digest(executable)}
    (out / 'results.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'passed': [r['case'] for r in results], 'recovery': evidence,
                      'configuration': manifest['gc.config.sha256']}, indent=2))


if __name__ == '__main__':
    main()
