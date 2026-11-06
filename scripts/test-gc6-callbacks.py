#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Guarded actual-JVM qualification of generated managed callback adapters."""
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
    parser.add_argument('--level', action='append', choices=('O0', 'O1', 'O2', 'O3'))
    parser.add_argument('--diagnostics', action='store_true')
    parser.add_argument('--batch-fallback', action='store_true')
    args = parser.parse_args()
    sdk, llvm, java, out = (getattr(args, name).resolve() for name in ('sdk', 'llvm_home', 'java_home', 'output'))
    manifest, _ = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    results = []

    def run(command, name, env=None):
        results.append(engine.run(list(map(str, command)), raw, name, env))
        print(name + ': passed', flush=True)

    run([java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-d', out,
         ROOT / 'scripts/java-bridge/Gc6Callbacks.java'], 'consumer-javac')
    for level in args.level or ('O0', 'O1', 'O2', 'O3'):
        folder = out / level
        options = ['-Dironwood.gc.rootFailureTests=true']
        if args.diagnostics: options.append('-Dironwood.gc.rootDiagnostics=true')
        run([java / 'bin/java', *options, '-ea', '-cp', str(ROOT / 'compiler/build/classes') + ':' + str(ROOT / 'compiler/build/test-classes'),
             'ironwood.compiler.Gc6CallbackTests', folder, llvm, level], level + '-generated-build', {'IRONWOOD_GC_HOME': str(sdk)})
        run([java / 'bin/java', '-Xcheck:jni', '-Xbatch', '-XX:-TieredCompilation', '-XX:CompileThreshold=20',
             '-cp', ':'.join(map(str, [out, folder / 'permanent.jar', folder / 'ironwood-bridge-values.jar'])),
             'Gc6Callbacks', folder / 'support.txt'], level + '-consumer')
        stdout = (raw / (level + '-consumer.stdout')).read_text()
        stderr = (raw / (level + '-consumer.stderr')).read_text()
        diagnostic_lines = stderr.splitlines() if args.diagnostics else []
        unexpected = [line for line in stderr.splitlines() if line not in diagnostic_lines
                      or not line.startswith(('GC3 frame pc=', 'GC6 capture step='))]
        if stdout != 'GC6 generated callbacks passed\n' or unexpected:
            raise RuntimeError('unexpected JNI diagnostics in ' + level)
        if args.batch_fallback:
            run([java / 'bin/java', '-Dironwood.bridge.batch.disable=true', '-Xcheck:jni', '-Xbatch', '-XX:-TieredCompilation',
                 '-cp', ':'.join(map(str, [out, folder / 'permanent.jar', folder / 'ironwood-bridge-values.jar'])),
                 'Gc6Callbacks', folder / 'support.txt'], level + '-batch-fallback')
            fallback_error = (raw / (level + '-batch-fallback.stderr')).read_text().splitlines()
            if args.diagnostics:
                fallback_error = [line for line in fallback_error if not line.startswith(('GC3 frame pc=', 'GC6 capture step='))]
            if (raw / (level + '-batch-fallback.stdout')).read_text() != 'GC6 generated callbacks passed\n' or fallback_error:
                raise RuntimeError('unexpected JNI fallback diagnostics in ' + level)
    (out / 'results.json').write_text(json.dumps({'format': 1, 'gc6_complete': False, 'target': engine.gc.host_target(),
        'sdk_configuration': manifest['gc.config.sha256'], 'guard_kib': engine.GUARD_KIB,
        'per_child_timeout_seconds': 60, 'results': results}, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
