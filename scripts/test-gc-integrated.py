#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused normal compiler/runtime GC5 qualification with bounded child processes."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('gc_engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--llvm-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--level', action='append', choices=('O0', 'O1', 'O2', 'O3'))
    parser.add_argument('--case', action='append', choices=('integrated', 'oom', 'no_collect', 'resources', 'pools', 'graphs'))
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'
    raw.mkdir()
    sdk, llvm, java = args.sdk.resolve(), args.llvm_home.resolve(), args.java_home.resolve()
    manifest, flags = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    env = {'JAVA_HOME': str(java), 'IRONWOOD_GC_HOME': str(sdk)}
    command = [str(java / 'bin/java'), '-jar', str(ROOT / 'compiler/build/ironwoodc.jar')]
    results = []

    def run(argv, name, extra=None):
        result = engine.run(argv, raw, name, {**env, **(extra or {})})
        results.append(result)
        print(name + ': passed', flush=True)

    for case in args.case or ('integrated', 'oom', 'no_collect', 'resources', 'pools'):
        if case == 'graphs':
            binary = out / 'native-graphs'
            run([str(llvm / 'bin/clang'), *flags, '-std=c11', '-O3', '-fvisibility=hidden',
                 '-Wall', '-Wextra', '-Werror', '-Wpedantic', '-I' + str(ROOT / 'runtime/include'),
                 '-I' + str(sdk / 'include/gc'), str(ROOT / 'integration-tests/native/gc_native_graphs_probe.c'),
                 str(sdk / 'lib/libironwood_gc_engine.a'), str(sdk / 'lib/libgc.a'),
                 *manifest['configuration']['target.ldflags'], '-o', str(binary)], 'native-graphs-build')
            for mode in ('basic', 'overflow'):
                run([str(binary), mode], 'native-graphs-' + mode)
            assert re.search(r'Mark stack overflow; current size: [0-9]+ entries',
                             (raw / 'native-graphs-overflow.stderr').read_text()), 'no actual mark-stack overflow'
            continue
        source = ROOT / 'integration-tests/cases' / ('gc5_' + case + '.iron')
        entry = {'integrated': 'Gc5Integrated', 'oom': 'Gc5Oom', 'no_collect': 'Gc5NoCollect', 'resources': 'Gc5Resources', 'pools': 'Gc5Pools'}[case]
        classes = out / (case + '-classes')
        run(command + [str(source), '-d', str(classes)], case + '-classes')
        for level in args.level or ('O0', 'O1', 'O2', 'O3'):
            binary = out / (case + '-' + level)
            run(command + ['--link', '--main-class', entry, '-cp', str(classes),
                           '--emit-llvm', str(binary) + '.ll', '-o', str(binary), '-' + level,
                           '--llvm-home', str(llvm)], case + '-' + level + '-link')
            if case == 'oom':
                for mode, limits in [('allocation', {'IRONWOOD_ALLOCATION_LIMIT': '3'}),
                                     ('preparation', {'IRONWOOD_GC_ROOT_CAPACITY': '1'}), ('constructor', {})]:
                    run([str(binary), mode], case + '-' + level + '-' + mode, limits)
            elif case == 'resources':
                run([str(binary), 'success'], case + '-' + level + '-success')
                run([str(binary), 'failure'], case + '-' + level + '-failure', {'IRONWOOD_GC_ROOT_CAPACITY': '1'})
            else:
                run([str(binary)], case + '-' + level + '-run')
                if case == 'integrated':
                    assert (raw / (case + '-' + level + '-run.stdout')).read_text() == 'GC5 integrated passed\n'
    report = {'format': 1, 'production_enabled': True, 'target': engine.gc.host_target(),
              'sdk_configuration': manifest['gc.config.sha256'], 'root_abi': 2, 'runtime_owner_abi': 1,
              'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60, 'results': results,
              'source.sha256': {str(p.relative_to(ROOT)): engine.gc.digest(p) for p in
                                [Path(__file__).resolve(), *sorted((ROOT / 'integration-tests/cases').glob('gc5_*.iron'))]},
              'output.sha256': {p.name: engine.gc.digest(p) for p in out.iterdir() if p.is_file()}}
    (out / 'results.json').write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')


if __name__ == '__main__':
    main()
