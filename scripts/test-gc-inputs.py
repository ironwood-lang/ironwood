#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Normal artifact parity and pre-execution refusals for stale GC build inputs."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec); spec.loader.exec_module(engine)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'llvm-home', 'java-home', 'output'): p.add_argument('--' + name, type=Path, required=True)
    args = p.parse_args(); out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir(); results = []
    java = str(args.java_home.resolve() / 'bin/java')
    command = [java, '-jar', str(ROOT / 'compiler/build/ironwoodc.jar')]
    env = {'JAVA_HOME': str(args.java_home.resolve()), 'IRONWOOD_GC_HOME': str(args.sdk.resolve())}
    def run(argv, name, expected=0, extra=None):
        result = engine.run(argv, raw, name, {**env, **(extra or {})}, expected_exit=expected)
        results.append(result); print(name + ': passed', flush=True)
    classes = out / 'classes'
    run(command + [str(ROOT / 'integration-tests/cases/gc5_integrated.iron'), '-d', str(classes)], 'source-to-class')
    archive = out / 'ordinary.ironjar'
    run([java, '-cp', str(ROOT / 'compiler/build/ironwoodc.jar'), 'ironwood.compiler.IronJarMain',
         '--create', '--file', str(archive), str(classes)], 'class-to-archive')
    for name, path in [('classes', classes), ('archive', archive)]:
        binary = out / name / 'program' if name == 'classes' else out / 'archive-program'
        run(command + ['--link', '--main-class', 'Gc5Integrated', '-cp', str(path), '-O3',
                       '--llvm-home', str(args.llvm_home.resolve()), '-o', str(binary)], name + '-link')
        run([str(binary)], name + '-run')
        assert (raw / (name + '-run.stdout')).read_text() == 'GC5 integrated passed\n'
    copy = out / 'relocated SDK'; shutil.copytree(args.sdk.resolve(), copy)
    link = command + ['--link', '--main-class', 'Gc5Integrated', '-cp', str(archive), '-O0',
                      '--llvm-home', str(args.llvm_home.resolve()), '-o', str(out / 'refused')]
    # Every negative is a separate compiler process. No refused executable exists.
    changes = [('root-abi', 'roots.version=2', 'roots.version=1'),
               ('owner-abi', 'runtime.owner.version=1', 'runtime.owner.version=2'),
               ('engine-abi', 'adapter.abi=ironwood-gc-engine-3', 'adapter.abi=ironwood-gc-engine-2'),
               ('llvm-version', 'llvm.version=23.1.0', 'llvm.version=22.1.0'),
               ('configuration-identity', 'configuration.sha256=', 'configuration.sha256=stale')]
    properties = copy / 'build.properties'; original = properties.read_text()
    for name, before, after in changes:
        assert before in original; properties.write_text(original.replace(before, after))
        run(link, name, 1, {'IRONWOOD_GC_HOME': str(copy)})
        assert 'GC dependency:' in (raw / (name + '.stderr')).read_text()
        assert not (out / 'refused').exists()
        properties.write_text(original)
    for name, relative in [('statepoint-pass', 'compiler/src/main/cpp/GcNonmovingStatepoints.cpp'),
                           ('runtime-owner-source', 'runtime/src/ironwood_runtime.c'),
                           ('root-reader-source', 'runtime/src/ironwood_gc_roots.c'),
                           ('configuration-payload', 'configuration.json')]:
        file = copy / relative; saved = file.read_bytes(); file.write_bytes(saved + b'altered')
        run(link, name, 1, {'IRONWOOD_GC_HOME': str(copy)})
        assert 'GC dependency:' in (raw / (name + '.stderr')).read_text()
        file.write_bytes(saved)
    extra = copy / 'unmanifested-input'; extra.write_text('stale')
    run(link, 'extra-sdk-input', 1, {'IRONWOOD_GC_HOME': str(copy)})
    extra.unlink()
    for option in ('--java-bridge-library', '--java-bridge-assemble', '--java-bridge-distribution'):
        run(command + [option], option[2:], 1)
        assert 'GC6 boundary' in (raw / (option[2:] + '.stderr')).read_text()
    (out / 'results.json').write_text(json.dumps({'format': 1, 'guard_kib': engine.GUARD_KIB,
        'per_child_timeout_seconds': 60, 'results': results}, indent=2) + '\n')


if __name__ == '__main__':
    main()
