#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Current production bridge timing, separated from native noncollecting kernels."""
import argparse
import importlib.util
import json
from pathlib import Path
import statistics
import sys
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gc8', ROOT / 'scripts/test-gc8.py')
gc8 = importlib.util.module_from_spec(spec); spec.loader.exec_module(gc8)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'llvm-home', 'java-home', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    sdk, llvm, java, out = (getattr(args, k).resolve() for k in ('sdk', 'llvm_home', 'java_home', 'output'))
    if not out.is_relative_to(ROOT / 'workspace'):
        parser.error('generated evidence must stay under ignored workspace/')
    manifest, _ = gc8.engine.gc.verify(sdk, llvm, gc8.engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False); raw = out / 'raw'; raw.mkdir()
    results = []
    env = {'JAVA_HOME': str(java), 'IRONWOOD_GC_HOME': str(sdk),
           'IRONWOOD_RUNTIME_HOME': str(ROOT), 'IRONWOOD_STDLIB_HOME': str(ROOT)}
    def run(name, command):
        results.append(gc8.engine.run(list(map(str, command)), raw, name, env))
        return (raw / (name + '.stdout')).read_text()
    sources = [ROOT / 'integration-tests/cases/gc8/BoundaryCounters.iron',
               *sorted((ROOT / 'examples/java-bridge/listeners/src/main/ironwood').rglob('*.iron'))]
    jar = out / 'workload.jar'; classes = out / 'classes'
    run('produce', [java / 'bin/java', '-jar', ROOT / 'compiler/build/ironwoodc.jar', '--java-bridge', '-O3',
                   '--critical-calls=on', '--llvm-home', llvm, '--export', 'gc8',
                   '--export', 'org.ironwood.javabridge.listeners', '--license', ROOT / 'LICENSE-MIT',
                   '--license', ROOT / 'LICENSE-APACHE', '-o', jar, *sources])
    run('javac', [java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', jar, '-d', classes,
                 ROOT / 'examples/java-bridge/support/BridgeLifetime.java', ROOT / 'scripts/java-bridge/Gc8BoundaryConsumer.java'])
    images = []
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if entry.endswith('.dylib'):
                target = out / Path(entry).name; target.write_bytes(archive.read(entry)); images.append(target)
    if len(images) != 1: raise AssertionError('expected one current native image')
    run('disassembly', [llvm / 'bin/llvm-objdump', '-d', '--no-show-raw-insn', images[0]])
    launch = [java / 'bin/java', '--enable-preview', '--enable-native-access=ALL-UNNAMED',
              '-Xms32m', '-Xmx128m', '-cp', str(classes) + ':' + str(jar)]
    # Checked JNI is functional evidence only; primary timing avoids its checking cost.
    run('checked-callback', [*launch, '-Xcheck:jni', 'Gc8BoundaryConsumer', 'callback-reentry'])
    samples = {}; rows = {}
    labels = ('critical', 'critical-AA', 'jni', 'callback', 'callback-reentry')
    for repeat in range(6):
        for label in labels if repeat % 2 == 0 else reversed(labels):
            data = [json.loads(line) for line in run(label + '-' + str(repeat), [*launch,
                    *(['-Dironwood.bridge.calls=jni'] if label == 'jni' else []),
                    'Gc8BoundaryConsumer', label]).splitlines() if line.startswith('{')]
            if len(data) != 7: raise AssertionError('missing batches')
            samples.setdefault(label, []).append(statistics.median(r['ns'] / r['operations'] for r in data))
            rows[label] = {'operations_per_batch': data[0]['operations'], 'allocations': 0,
                           'explicit_collections_per_batch': data[0]['explicit_collections']}
    report = {'format': 1, 'target': gc8.engine.gc.host_target(), 'sdk_configuration': manifest['gc.config.sha256'],
              'guard_kib': gc8.engine.GUARD_KIB, 'timeout_seconds': 60, 'nominal_sample_interval_ms': 50,
              'timing': {k: gc8.summary(v) for k, v in samples.items()}, 'workloads': rows, 'results': results,
              'binary_sha256': gc8.engine.gc.digest(images[0]), 'jar_sha256': gc8.engine.gc.digest(jar),
              'sources_sha256': {str(p.relative_to(ROOT)): gc8.engine.gc.digest(p) for p in [*sources, Path(__file__).resolve(),
                    ROOT / 'scripts/java-bridge/Gc8BoundaryConsumer.java']}}
    (out / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(str(out.relative_to(ROOT) / 'results.json'), flush=True)


if __name__ == '__main__': main()
