#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Build and exercise current managed bridge workloads with bounded children.

This replaces the pre-GC6 candidate/projection measurement entry points. It keeps
measurements as observations, without GC8 capacity or performance acceptance.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('gc_engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'llvm-home', 'java-home', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--case', choices=('micro', 'retention', 'orderbook', 'listeners'), required=True)
    parser.add_argument('--quick', action='store_true', help='bounded functional migration smoke, no performance acceptance')
    args = parser.parse_args()
    sdk, llvm, java, out = (getattr(args, name).resolve() for name in ('sdk', 'llvm_home', 'java_home', 'output'))
    manifest, _ = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    helper = ROOT / 'examples/java-bridge/support/BridgeLifetime.java'
    project = ROOT / 'projects/OrderBook'
    exports, sources, consumers = [], [], []
    if args.case == 'micro':
        exports = ['bridgeperf']; sources = [HERE / 'PerformanceProbe.iron']; consumers = [HERE / 'PerformanceConsumer.java']
    elif args.case == 'retention':
        exports = ['mixedlife']; sources = [HERE / 'retention/Holder.iron']; consumers = [HERE / 'RetentionConsumer.java']
    elif args.case == 'orderbook':
        exports = ['bridgeperf', 'org.ironwood.orderbook']
        sources = [HERE / 'OrderBookBatch.iron', *[project / 'src/main/ironwood/org/ironwood/orderbook' / (name + '.iron')
                   for name in ('Order', 'OrderBook', 'PriceLevel')]]
        consumers = [HERE / (name + '.java') for name in ('BridgeBench', 'OrderBookBatchConsumer', 'OrderBookBatchLatency')]
        consumers += [project / 'java/src/main/java/org/ironwood/orderbook/LatencyReport.java']
    else:
        example = ROOT / 'examples/java-bridge/listeners'
        exports = ['org.ironwood.javabridge.listeners']; sources = sorted((example / 'src/main/ironwood').rglob('*.iron'))
        consumers = sorted((example / 'src/main/java').rglob('*.java'))
    results = []
    env = {'JAVA_HOME': str(java), 'IRONWOOD_GC_HOME': str(sdk)}

    def run(name, command):
        results.append(engine.run(list(map(str, command)), raw, name, env))
        print(name + ': passed', flush=True)

    jar, classes = out / 'workload.jar', out / 'classes'
    command = [java / 'bin/java', '-jar', ROOT / 'compiler/build/ironwoodc.jar', '--java-bridge', '-O3',
               '--llvm-home', llvm, '--license', ROOT / 'LICENSE-MIT', '--license', ROOT / 'LICENSE-APACHE', '-o', jar]
    for export in exports: command += ['--export', export]
    run('produce', [*command, *sources])
    run('javac', [java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', jar, '-d', classes, helper, *consumers])
    launch = [java / 'bin/java', '-Xcheck:jni', '-cp', str(classes) + ':' + str(jar)]
    if args.case == 'micro':
        for mode in ('scalar', 'instance', 'object', 'batch', 'string', 'exception'):
            run(mode, [*launch, *(['-Dironwood.measure.count=1000'] if args.quick else []), 'PerformanceConsumer', mode])
    elif args.case == 'retention':
        run('retention', [*launch, *(['-Dironwood.measure.pairs=1000'] if args.quick else []), 'RetentionConsumer'])
    elif args.case == 'orderbook':
        options = ['-Dironwood.measure.cycles=32'] if args.quick else []
        for name in ('BridgeBench', 'OrderBookBatchConsumer'):
            run(name, [*launch, *options, 'org.ironwood.orderbook.' + name, '1', '1'])
        run('latency', [*launch, 'org.ironwood.orderbook.OrderBookBatchLatency', *(['4', '10', '2'] if args.quick else ['20000', '100000', '8'])])
    else:
        for mode in ('java-java', 'native-java'):
            run(mode, [*launch, 'org.ironwood.javabridge.listenerconsumer.Benchmark', mode,
                       *(['1000', '2', '2'] if args.quick else ['1000000', '5', '7'])])
    for result in results:
        if result['case'] not in ('produce', 'javac') and (raw / (result['case'] + '.stderr')).read_text(): raise RuntimeError('unexpected diagnostics: ' + result['case'])
    (out / 'results.json').write_text(json.dumps({'format': 1, 'case': args.case, 'quick': args.quick,
        'sdk_configuration': manifest['gc.config.sha256'], 'guard_kib': engine.GUARD_KIB,
        'per_child_timeout_seconds': 60, 'performance_acceptance': False, 'results': results,
        'sources': {str(path.relative_to(ROOT)): engine.gc.digest(path) for path in [*sources, *consumers, helper]}}, indent=2) + '\n')


if __name__ == '__main__': main()
