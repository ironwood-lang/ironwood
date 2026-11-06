#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded JVM qualification of typed native frames and ABI-3 suspended segments."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
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
    args = parser.parse_args()
    sdk, llvm, java, out = (getattr(args, name).resolve() for name in ('sdk', 'llvm_home', 'java_home', 'output'))
    manifest, flags = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    generated = out / 'generated'
    target = out / 'target.ll'
    target.write_text('target datalayout = "' + manifest['configuration']['target.data-layout'] + '"\n'
                      'target triple = "' + manifest['configuration']['target.triple'] + '"\n')
    results = []
    def run(command, name, expected=0):
        result = engine.run(list(map(str, command)), raw, name, expected_exit=expected)
        results.append(result)
        print(name + ': passed', flush=True)
    run([java / 'bin/java', '-ea', '-cp', str(ROOT / 'compiler/build/classes') + ':'
         + str(ROOT / 'compiler/build/test-classes'), 'ironwood.compiler.Gc6SegmentTests', generated, llvm, target], 'typed-pipeline')
    run([java / 'bin/javac', '--release', '21', '-d', out, ROOT / 'scripts/java-bridge/Gc6Segments.java'], 'jvm-consumer')
    mac = engine.gc.host_target() == 'macos-arm64'
    objects = []
    for source in [ROOT / 'runtime/src/ironwood_gc_roots.c', ROOT / 'runtime/src/ironwood_gc_lifetimes.c']:
        obj = out / (source.stem + '.o'); objects.append(obj)
        diagnostics = ['-DIW_GC_ROOT_DIAGNOSTICS'] if args.diagnostics else []
        run([llvm / 'bin/clang', *flags, *diagnostics, '-std=c11', '-O3', '-fPIC', '-fvisibility=hidden', '-Wall', '-Wextra', '-Werror', '-Wpedantic',
             '-I' + str(ROOT / 'runtime/include'), '-I' + str(sdk / 'include/gc'), '-c', source, '-o', obj], source.stem)
    for level in args.level or ('O0', 'O1', 'O2', 'O3'):
        contract = out / (level + '-contract.o')
        run([llvm / 'bin/clang', *flags, '-std=c11', '-O3', '-fPIC', '-fvisibility=hidden', '-c',
             generated / level / 'root-contract.c', '-o', contract], level + '-contract')
        images = []
        for label, symbol in [('A', 'Java_Gc6Segments_invoke'), ('B', 'Java_Gc6Segments_00024Second_invoke')]:
            policy = out / ('exports-' + label)
            policy.write_text('_' + symbol + '\n' if mac else '{ global: ' + symbol + '; local: *; };\n')
            link = ['-dynamiclib', '-Wl,-exported_symbols_list,' + str(policy), '-Wl,-twolevel_namespace'] if mac else [
                '-shared', '-Wl,--version-script=' + str(policy), '-Wl,-Bsymbolic,--exclude-libs,ALL,-z,defs,-z,now,-z,relro,--eh-frame-hdr',
                '-Wl,-T,' + str(generated / level / 'root-contract.ld'), '-L' + str(llvm / 'lib'), '-Wl,-rpath,' + str(llvm / 'lib')]
            image = out / (level + '-' + label + ('.dylib' if mac else '.so')); images.append(image)
            run([llvm / 'bin/clang', '--driver-mode=g++', *flags, '-x', 'c', '-std=c11', '-O3', '-fPIC', '-fvisibility=hidden',
                 '-Wall', '-Wextra', '-Werror', '-Wpedantic', '-I' + str(ROOT / 'runtime/include'), '-I' + str(generated),
                 '-I' + str(java / 'include'), '-I' + str(java / 'include' / ('darwin' if mac else 'linux')),
                 '-DIW_ENTRY=' + symbol, ROOT / 'integration-tests/native/gc6_segments.c', '-x', 'none', generated / level / 'program.o',
                 contract, *objects, sdk / 'lib/libironwood_gc_engine.a', sdk / 'lib/libgc.a',
                 *manifest['configuration']['target.ldflags'], *link, '-o', image], level + '-link-' + label)
        command = [java / 'bin/java', '-Xcheck:jni', '-Xbatch', '-XX:-TieredCompilation', '-XX:+PrintCompilation',
                   '-XX:CompileThreshold=20', '-cp', out, 'Gc6Segments', *images]
        run(command, level + '-reentry')
        stdout = (raw / (level + '-reentry.stdout')).read_text()
        assert 'GC6 suspended segments passed\n' in stdout
        assert re.search(r'\bGc6Segments::reenter\b', stdout), 'HotSpot reentry method was not compiled'
        for mode in (2, 3, 4):
            run([*command, str(mode)], level + '-refusal-' + str(mode), 86)
        if level == 'O3':
            run([*command, 'performance'], 'O3-transition-performance')
            run([llvm / 'bin/llvm-objdump', '-d', '--no-show-raw-insn', images[0]], 'O3-disassembly')
    (out / 'results.json').write_text(json.dumps({'format': 1, 'root_abi': 3, 'owner_abi': 2,
        'target': engine.gc.host_target(), 'gc6_complete': False, 'sdk_configuration': manifest['gc.config.sha256'],
        'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60, 'results': results}, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
