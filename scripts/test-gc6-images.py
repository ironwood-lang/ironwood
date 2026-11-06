#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded production-packager and independent-image JVM qualification."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'llvm-home', 'java-home', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    sdk, llvm, java, out = (getattr(args, name).resolve() for name in ('sdk', 'llvm_home', 'java_home', 'output'))
    manifest, _ = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    results = []
    for label in ('A', 'B'):
        results.append(engine.run([str(java / 'bin/java'), '-ea', '-cp', str(ROOT / 'compiler/build/classes') + ':'
            + str(ROOT / 'compiler/build/test-classes'), 'ironwood.compiler.Gc6PackageTests', str(out), str(llvm), label],
            raw, 'build-' + label, {'IRONWOOD_GC_HOME': str(sdk)}))
        print('production-image-' + label + ': passed', flush=True)
    results.append(engine.run([str(java / 'bin/javac'), '--release', '21', '-Xlint:all', '-Werror', '-d', str(out),
        str(ROOT / 'scripts/java-bridge/Gc6Images.java')], raw, 'javac'))
    results.append(engine.run([str(java / 'bin/java'), '-Xcheck:jni', '-Xbatch', '-XX:-TieredCompilation', '-XX:CompileThreshold=20',
        '-cp', str(out), 'Gc6Images', str(out)], raw, 'consumer'))
    if (raw / 'consumer.stdout').read_text() != 'GC6 independent production images passed\n' or (raw / 'consumer.stderr').read_text():
        raise RuntimeError('unexpected production-image JNI diagnostics')
    (out / 'results.json').write_text(json.dumps({'format': 1, 'gc6_complete': False, 'target': engine.gc.host_target(),
        'sdk_configuration': manifest['gc.config.sha256'], 'guard_kib': engine.GUARD_KIB,
        'per_child_timeout_seconds': 60, 'results': results}, indent=2, sort_keys=True) + '\n')
    print('independent-image-consumer: passed', flush=True)


if __name__ == '__main__':
    main()
