#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""GC5 focused call/pool defer controls, using the existing deterministic oracle."""
import argparse
import importlib.util
import json
import platform
from pathlib import Path
import re
import statistics
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent

def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

engine = module('engine', 'scripts/test-gc-engine.py')
perf = module('defer_performance', 'scripts/test-defer-performance.py')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--llvm-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('this assembly evidence driver requires macOS arm64')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    env = {'JAVA_HOME': str(args.java_home.resolve()), 'IRONWOOD_GC_HOME': str(args.sdk.resolve())}
    java = str(args.java_home.resolve() / 'bin/java')
    command = [java, '-jar', str(ROOT / 'compiler/build/ironwoodc.jar')]
    results = []; ordinal = 0
    def run(*argv, executable=None):
        nonlocal ordinal
        ordinal += 1; name = 'child-' + str(ordinal)
        argv = list(map(str, argv))
        if executable is not None: argv[0] = str(executable)
        results.append(engine.run(argv, raw, name, env))
        return (raw / (name + '.stdout')).read_text()
    perf.run = run
    reports = {}
    for case in ('call', 'pool'):
        samples = {'deferred': [], 'nested': []}; normalized = {}
        for variant in samples:
            directory = out / case / variant; directory.mkdir(parents=True)
            source = perf.fixture(case, variant)
            assert 'free ' not in source and 'destructor {' not in source
            path = directory / 'Main.iron'; path.write_text(source)
            run(*command, path, '-d', directory / 'classes')
            run(*command, '--link', '--main-class', 'Main', '-cp', directory / 'classes', '-O3',
                '--llvm-home', args.llvm_home.resolve(), '--emit-llvm', directory / 'program.ll', '-o', directory / 'program')
            perf.inspect(directory, args.llvm_home.resolve() / 'bin')
            # GC5 keeps the native main boundary separate. The measured source
            # loops now inline into ironwood_run, so main alone is insufficient.
            disassembly = (directory / 'disassembly.txt').read_text()
            assert re.search(r'<_ironwood_run>:', disassembly), 'missing measured native entry'
            application = json.loads((directory / 'application.json').read_text())
            perf.normalize_assembly(directory, [*application, '_ironwood_run'])
            normalized[variant] = json.loads((directory / 'normalized-assembly.json').read_text())
            values = dict(zip(perf.FIELDS, map(int, run('gc5-performance', 8, 0,
                              executable=directory / 'program').splitlines()), strict=True))
            assert {k: values[k] for k in perf.FIELDS[:-1]} == perf.oracle(case, 8, 0), values
        assert normalized['deferred'] == normalized['nested'], case + ': defer and finally kernels differ'
        expected = None
        for repeat in range(2):
            for variant in samples if repeat == 0 else reversed(samples):
                values = list(map(int, run('gc5-performance', 32000000, 0,
                                  executable=out / case / variant / 'program').splitlines()))
                if expected is None: expected = values[:-1]
                assert values[:-1] == expected
                assert values[5:9] == [0, 0, 0, 0], 'warmed path allocated or changed live accounting'
                samples[variant].append(values[-1])
        reports[case] = {'iterations': 32000000, 'ns': samples,
                         'median_ns': {k: statistics.median(v) for k, v in samples.items()},
                         'normalized_assembly_identical': True,
                         'normalized.sha256': engine.gc.digest(out / case / 'deferred/normalized-assembly.json'),
                         'results': dict(zip(perf.FIELDS[:-1], expected, strict=True))}
        print(case + ': passed', flush=True)
    (out / 'results.json').write_text(json.dumps({'format': 1, 'guard_kib': engine.GUARD_KIB,
        'per_child_timeout_seconds': 60, 'reports': reports, 'results': results}, indent=2) + '\n')


if __name__ == '__main__':
    main()
