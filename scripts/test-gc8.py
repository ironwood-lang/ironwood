#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded GC8 native timing and resident-graph qualification on macOS arm64."""
import argparse
import importlib.util
import json
from pathlib import Path
import platform
import random
import re
import statistics
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


engine = module('gc8_engine', 'scripts/test-gc-engine.py')
defer = module('gc8_defer', 'scripts/test-defer-performance.py')


def summary(samples):
    """Resample independent process medians, never individual batches."""
    rng = random.Random(20261106)
    centers = sorted(statistics.median(rng.choices(samples, k=len(samples))) for _ in range(10000))
    return {'processes': len(samples), 'process_medians_ns_per_operation': samples,
            'median': statistics.median(samples), 'min': min(samples), 'max': max(samples),
            'bootstrap_median_95_percent': [centers[249], centers[9749]]}


def oracle(count):
    if count <= 0 or count % 4096:
        raise ValueError('kernel count must be a positive multiple of 4096')
    values = list(range(4096)); first = 0
    for i in range(4096):
        index = (i * 17) & 4095
        values[index] += i & 7
        first += values[index] + values[(index + 1) & 4095]
    blocks = count // 4096
    return first * blocks + 28672 * blocks * (blocks - 1) // 2


def disassembly(text):
    """Drop only instruction PCs and resolved address prefixes before symbols.

    Numeric immediates and all instruction operands/opcodes remain. Unknown
    address operands remain too. This intentionally does not promise equal
    hashes across changed layouts or linker versions.
    """
    result = {}
    for name, body in re.findall(r'^[0-9a-f]+ <([^\n]+)>:\n(.*?)(?=\n\n|\Z)', text, re.M | re.S):
        if name == '_ironwood_run' or name.startswith('_ironwood_bridge_entry_') or re.search(r'ironwood\.(gc8\.|org\.ironwood\.orderbook\.|Main\.|Cell\.|Builder\.)', name):
            lines = [re.sub(r'0x[0-9a-f]+ (<[^>]+>)', r'\1', line.split(':', 1)[1].strip())
                     for line in body.splitlines() if ':' in line]
            result[name] = lines
    if not result:
        raise AssertionError('missing measured callers in actual disassembly')
    return result


def build_identity(binary, sources, sdk, configuration):
    return {'binary_sha256': engine.gc.digest(binary),
            'compiler_sha256': engine.gc.digest(ROOT / 'compiler/build/ironwoodc.jar'),
            'stdlib_sha256': engine.gc.digest(ROOT / 'compiler/build/ironwood-stdlib.ironjar'),
            'sdk_configuration': configuration, 'sdk_manifest_sha256': engine.gc.digest(sdk / 'build.json'),
            'sources_sha256': {str(p.resolve().relative_to(ROOT)): engine.gc.digest(p) for p in sources}}


def verify_identity(binary, sdk, configuration):
    saved = json.loads(binary.with_name('identity.json').read_text())
    current = build_identity(binary, [ROOT / p for p in saved['sources_sha256']], sdk, configuration)
    if saved != current:
        raise ValueError('stale or changed workload/compiler/SDK input: ' + str(binary))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'llvm-home', 'java-home', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--mode', choices=('build', 'observer', 'assembly', 'timing', 'memory', 'all'), default='all')
    parser.add_argument('--processes', type=int, default=6)
    parser.add_argument('--operations', type=int, default=4194304)
    args = parser.parse_args()
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'):
        parser.error('GC8 primary evidence currently requires native macOS arm64')
    if args.processes < 6:
        parser.error('at least six independent timing processes per label')
    oracle(args.operations)
    sdk, llvm, java, out = (getattr(args, k).resolve() for k in ('sdk', 'llvm_home', 'java_home', 'output'))
    if not out.is_relative_to(ROOT / 'workspace'):
        parser.error('generated evidence must stay under ignored workspace/')
    manifest, _ = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=True)
    report_path = out / ('results-' + args.mode + '.json')
    if report_path.exists():
        parser.error('refusing to overwrite existing experiment report')
    raw = out / 'raw' / args.mode; raw.mkdir(parents=True, exist_ok=False)
    env = {'JAVA_HOME': str(java), 'IRONWOOD_GC_HOME': str(sdk),
           'IRONWOOD_RUNTIME_HOME': str(ROOT), 'IRONWOOD_STDLIB_HOME': str(ROOT)}
    results = []
    report = {'format': 1, 'entry_revision': engine.gc.output(['git', 'rev-parse', 'HEAD']),
              'host_role': 'machost', 'target': 'macos-arm64', 'guard_kib': engine.GUARD_KIB,
              'timeout_seconds': 60, 'nominal_sample_interval_ms': 50, 'sdk_configuration': manifest['gc.config.sha256'],
              'sdk_manifest_sha256': engine.gc.digest(sdk / 'build.json'), 'results': results}
    def run(command, name, extra=None):
        if (raw / (name + '.stdout')).exists():
            raise ValueError('refusing duplicate child name: ' + name)
        argv = list(map(str, command))
        result = engine.run(argv, raw, name, {**env, **(extra or {})})
        result['command'] = [v.replace(str(ROOT) + '/', '').replace(str(Path.home()), '/Users/developer') for v in argv]
        results.append(result)
        report_path.write_text(json.dumps(report, indent=2) + '\n')
        return (raw / (name + '.stdout')).read_text()
    compiler = [java / 'bin/java', '-jar', ROOT / 'compiler/build/ironwoodc.jar']
    def build(name, sources, entry):
        folder = out / name; folder.mkdir(parents=True, exist_ok=False)
        run([*compiler, *sources, '-d', folder / 'classes'], 'compile-' + name)
        run([*compiler, '--link', '--main-class', entry, '-cp', folder / 'classes', '-O3',
             '--llvm-home', llvm, '--emit-llvm', folder / 'program.ll', '-o', folder / 'program'], 'link-' + name)
        (folder / 'identity.json').write_text(json.dumps(build_identity(folder / 'program', sources, sdk,
                    manifest['gc.config.sha256']), indent=2) + '\n')
        return folder
    if args.mode in ('build', 'all'):
        run(['bash', 'scripts/build.sh'], 'bootstrap')
        run([llvm / 'bin/clang', '-std=c11', '-O3', '-Wall', '-Wextra', '-Werror',
             ROOT / 'integration-tests/native/gc8_clock.c', '-o', out / 'clock'], 'clock-build')
        report['clock'] = json.loads(run([out / 'clock'], 'clock'))
        build('kernels', [ROOT / 'integration-tests/cases/gc8/Gc8Kernels.iron'], 'gc8.Gc8KernelMain')
        project = ROOT / 'projects/OrderBook/src/main/ironwood/org/ironwood/orderbook'
        build('orderbook', [*[project / (s + '.iron') for s in ('Order', 'OrderBook', 'PriceLevel', 'Bench')],
              ROOT / 'integration-tests/cases/gc8/OrderBookQualification.iron'], 'org.ironwood.orderbook.OrderBookQualification')
        for case in ('call', 'pool'):
            for variant in ('deferred', 'nested'):
                folder = out / (case + '-' + variant); folder.mkdir()
                source = folder / 'Main.iron'; source.write_text(defer.fixture(case, variant))
                # build() owns its destination, keep generated input separate.
                build(case + '-' + variant + '-bin', [source], 'Main')
    if args.mode in ('observer', 'build', 'all'):
        # Use the existing helper's bounded subprocesses and the same current SDK.
        run([java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp',
             ROOT / 'compiler/build/classes', '-d', out / 'builder',
             *[ROOT / 'compiler/src/test/java/ironwood/compiler' / (name + '.java') for name in
               ('Gc8Qualification', 'ManagedBridgeTestBuild', 'GcTestProcesses')]], 'observer-javac')
        run([java / 'bin/java', '-ea', '-cp', str(out / 'builder') + ':' + str(ROOT / 'compiler/build/classes'),
             'ironwood.compiler.Gc8Qualification', out / 'graphs', llvm, 'O3'], 'observer-build')
        run([java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', out / 'graphs/permanent.jar',
             '-d', out / 'consumer', ROOT / 'scripts/java-bridge/Gc8Consumer.java'], 'consumer-javac')
        sources = [*[ROOT / 'integration-tests/cases/gc8' / (name + '.iron') for name in ('Graphs', 'Gc8Kernels', 'GraphPhases')],
                   ROOT / 'integration-tests/native/gc8_observer.c', *[ROOT / 'compiler/src/test/java/ironwood/compiler' / (name + '.java')
                   for name in ('Gc8Qualification', 'ManagedBridgeTestBuild', 'GcTestProcesses')]]
        (out / 'graphs/identity.json').write_text(json.dumps(build_identity(out / 'graphs/libbridge.dylib', sources, sdk,
                    manifest['gc.config.sha256']), indent=2) + '\n')
    if args.mode in ('assembly', 'build', 'all'):
        assembly = {}
        for name in ('kernels', 'orderbook', 'call-deferred-bin', 'call-nested-bin', 'pool-deferred-bin', 'pool-nested-bin', 'graphs'):
            folder = out / name; binary = folder / ('libbridge.dylib' if name == 'graphs' else 'program')
            data = run([llvm / 'bin/llvm-objdump', '-d', '--no-show-raw-insn', binary], 'asm-' + name)
            normalized = disassembly(data)
            path = folder / 'actual-assembly.json'; path.write_text(json.dumps(normalized, indent=2) + '\n')
            assembly[name] = {'binary_sha256': engine.gc.digest(binary), 'normalized_sha256': engine.gc.digest(path),
                              'functions': {k: len(v) for k, v in normalized.items()}}
        report['assembly'] = assembly
    if args.mode in ('timing', 'all'):
        for name in ('kernels', 'orderbook', 'call-deferred-bin', 'call-nested-bin', 'pool-deferred-bin', 'pool-nested-bin'):
            verify_identity(out / name / 'program', sdk, manifest['gc.config.sha256'])
        samples = {}; cold = {}; reuse = {}; allocation = {}
        labels = ['reference', 'reference-AA', 'index', 'spaced', 'rare', 'call-deferred', 'call-nested',
                  'pool-deferred', 'pool-nested', 'orderbook', 'orderbook-AA']
        for repeat in range(args.processes):
            for label in labels if repeat % 2 == 0 else reversed(labels):
                name = 'time-' + label + '-' + str(repeat)
                if label in labels[:5]:
                    mode = 'rare' if label == 'rare' else 'index' if label == 'index' else 'reference'
                    values = list(map(int, run([out / 'kernels/program', args.operations, mode,
                                               'spaced' if label == 'spaced' else 'compact'], name).splitlines()))
                    batches = values[1:22:3]; checks = values[2:22:3]; counts = values[3:22:3]
                    assert len(values) == 25 and checks == [oracle(args.operations)] * 7 and values[-1] == checks[0]
                    assert counts == [args.operations // 4096 if mode == 'rare' else 0] * 7
                    operations = args.operations
                    cold.setdefault(label, []).append(values[0]); reuse.setdefault(label, []).append(values[-2] / operations)
                    allocation[label] = counts[0]
                elif label.startswith(('call-', 'pool-')):
                    values = list(map(int, run([out / (label + '-bin/program'), 32000000, 0], name).splitlines()))
                    assert values[5:9] == [0, 0, 0, 0]
                    small = list(map(int, run([out / (label + '-bin/program'), 8, 0], name + '-oracle').splitlines()))
                    assert dict(zip(defer.FIELDS[:-1], small[:-1], strict=True)) == defer.oracle(label.split('-')[0], 8, 0)
                    operations = 32000000; batches = [values[-1]]; allocation[label] = 0
                else:
                    values = list(map(int, run([out / 'orderbook/program'], name).splitlines()))
                    assert len(values) == 10
                    operations = 32000000; batches = values[1:8]
                    cold.setdefault(label, []).append(values[0]); reuse.setdefault(label, []).append(values[-1] / operations)
                    allocation[label] = 0
                samples.setdefault(label, []).append(statistics.median(batches) / operations)
        report['timing'] = {k: summary(v) for k, v in samples.items()}
        report['cold_setup_ns'] = cold; report['reuse_ns_per_operation'] = reuse; report['allocations_per_batch'] = allocation
    if args.mode in ('memory', 'all'):
        image = out / 'graphs/libbridge.dylib'
        verify_identity(image, sdk, manifest['gc.config.sha256'])
        launch = [java / 'bin/java', '-Xcheck:jni', '-Xms32m', '-Xmx128m', '-cp',
                  str(out / 'consumer') + ':' + str(out / 'graphs/permanent.jar'), 'Gc8Consumer', image]
        run([*launch, 'kernels'], 'observe-kernels')
        for nodes, scale in ((4096, 2), (16384, 8)):
            for repeat in range(3):
                run([*launch, nodes, scale], 'resident-' + str(nodes) + '-' + str(repeat))
        run([*launch, 'internal'], 'resident-internal')
    report['compiler_sha256'] = engine.gc.digest(ROOT / 'compiler/build/ironwoodc.jar')
    report['inputs_sha256'] = {str(p.relative_to(ROOT)): engine.gc.digest(p) for p in [Path(__file__).resolve(),
        ROOT / 'integration-tests/cases/gc8/Gc8Kernels.iron', ROOT / 'integration-tests/cases/gc8/Graphs.iron',
        ROOT / 'integration-tests/cases/gc8/GraphPhases.iron', ROOT / 'integration-tests/cases/gc8/OrderBookQualification.iron',
        ROOT / 'integration-tests/native/gc8_clock.c',
        ROOT / 'integration-tests/native/gc8_observer.c', ROOT / 'scripts/java-bridge/Gc8Consumer.java',
        ROOT / 'compiler/src/test/java/ironwood/compiler/Gc8Qualification.java']}
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(str(report_path.relative_to(ROOT)), flush=True)


if __name__ == '__main__':
    main()
