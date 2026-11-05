#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded GC4 runtime ownership qualification through GC3's native root pipeline."""
import argparse
import importlib.util
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('gc_roots', ROOT / 'scripts/test-gc-roots.py')
roots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(roots)
engine, gc = roots.engine, roots.gc
CASES = ('unwind', 'null-throw', 'nested', 'wrapper', 'nested-wrappers', 'rethrow', 'cycles', 'trace', 'trace-fail',
         'emergency', 'emergency-steps', 'immortal', 'oom', 'resources', 'published', 'published-success', 'anchors', 'carrier', 'pending', 'yield',
         'stale', 'missing', 'owner-version', 'coverage', 'foreign', 'owner-mask', 'preparation',
         'stale-pc', 'stale-slot', 'stale-function')
ELF_CASES = ('missing-unwind', 'writable-map')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--llvm-home', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--target', default=gc.host_target())
    parser.add_argument('--level', action='append', choices=('O0', 'O1', 'O2', 'O3'))
    parser.add_argument('--case', action='append', choices=CASES + ELF_CASES)
    args = parser.parse_args()
    if args.target not in ('macos-arm64', 'linux-x86_64') or args.target != gc.host_target():
        raise ValueError('unqualified native GC4 target: ' + args.target)
    if args.target == 'macos-arm64' and any(case in ELF_CASES for case in args.case or ()):
        raise ValueError('ELF-specific control requires a native Linux target')
    sdk, llvm, out = args.sdk.resolve(), args.llvm_home.resolve(), args.output.resolve()
    manifest, flags = gc.verify(sdk, llvm, args.target)
    assert manifest['configuration']['adapter.abi'] == 'ironwood-gc-engine-2'
    assert '-DUSE_MARK_BYTES=1' in manifest['configuration']['cflags']
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'
    raw.mkdir()
    target = out / 'target.ll'
    target.write_text('target datalayout = "' + manifest['configuration']['target.data-layout'] + '"\n'
                      'target triple = "' + manifest['configuration']['target.triple'] + '"\n')
    generated = out / 'generated'
    results = [engine.run([str(args.java_home / 'bin/java'), '-ea', '-cp',
                          str(ROOT / 'compiler/build/classes') + ':' + str(ROOT / 'compiler/build/test-classes'),
                          'ironwood.compiler.GcLifetimeTests', str(generated), str(llvm), str(target)], raw, 'compiler-pipeline')]
    sources = [ROOT / 'runtime/src/ironwood_gc_roots.c', ROOT / 'runtime/src/ironwood_gc_lifetimes.c',
               ROOT / 'integration-tests/native/gc_lifetimes_probe.c', ROOT / 'integration-tests/native/gc_scalar_exception.cpp']
    objects = []
    for source in sources:
        obj = out / (source.stem + '.o')
        command = [str(llvm / 'bin/clang'), *flags, '-std=c++17' if source.suffix == '.cpp' else '-std=c11',
                   '-O3', '-fPIC', '-fvisibility=hidden', '-Wall', '-Wextra', '-Werror', '-Wpedantic',
                   '-I' + str(ROOT / 'runtime/include'), '-I' + str(sdk / 'include/gc'), '-I' + str(generated),
                   '-c', str(source), '-o', str(obj)]
        results.append(engine.run(command, raw, source.stem + '-compile'))
        objects.append(obj)
    for level in args.level or ('O0', 'O1', 'O2', 'O3'):
        contract = out / (level + '-contract.o')
        results.append(engine.run([str(llvm / 'bin/clang'), *flags, '-std=c11', '-O3', '-fvisibility=hidden', '-c',
                                   str(generated / level / 'root-contract.c'), '-o', str(contract)], raw, level + '-contract'))
        executable = out / ('probe-' + level)
        link_flags = ['-Wl,-dead_strip'] if args.target == 'macos-arm64' else [
            '-pie', '-L' + str(llvm / 'lib'), '-Wl,-rpath,' + str(llvm / 'lib'),
            '-Wl,--gc-sections,--eh-frame-hdr,-z,defs,-z,now,-z,relro,--exclude-libs,ALL',
            '-Wl,-T,' + str(generated / level / 'root-contract.ld')]
        def link(program, destination, name):
            result = engine.run([str(llvm / 'bin/clang'), '--driver-mode=g++', *flags,
                            *manifest['configuration']['target.ldflags'], *link_flags,
                            str(program), str(contract), *map(str, objects),
                            str(sdk / 'lib/libironwood_gc_engine.a'), str(sdk / 'lib/libgc.a'), '-o', str(destination)], raw, name)
            if args.target.startswith('linux'):
                result['elf_image'] = roots.verify_elf_image(destination)
            results.append(result)
        program = generated / level / 'program.o'
        link(program, executable, level + '-link')
        for case in args.case or CASES + (ELF_CASES if args.target.startswith('linux') else ()):
            destination = executable
            if case == 'writable-map':
                data = bytearray(executable.read_bytes())
                phoff = struct.unpack_from('<Q', data, 32)[0]
                phsize, phcount = struct.unpack_from('<HH', data, 54)
                for header in range(phoff, phoff + phcount * phsize, phsize):
                    if struct.unpack_from('<I', data, header)[0] == 0x6474e552:
                        struct.pack_into('<I', data, header, 0)
                destination = out / (level + '-' + case)
                destination.write_bytes(data)
                destination.chmod(0o755)
            elif case == 'missing-unwind':
                mutated = out / (level + '-' + case + '.o')
                results.append(engine.run([str(llvm / 'bin/llvm-objcopy'), '--remove-section=.eh_frame',
                                           str(program), str(mutated)], raw, level + '-' + case + '-strip'))
                destination = out / (level + '-' + case)
                link(mutated, destination, level + '-' + case + '-link')
            elif case in ('stale-pc', 'stale-slot', 'stale-function'):
                mutated = out / (level + '-' + case + '.o')
                mutated.write_bytes(roots.mutate_object(program.read_bytes(), case))
                destination = out / (level + '-' + case)
                link(mutated, destination, level + '-' + case + '-link')
            results.append(engine.run([str(destination), case], raw, level + '-' + case,
                                      expected_exit=86 if case in ('preparation', 'missing-unwind') else 0))
    inputs = sources + [ROOT / 'runtime/include/ironwood_gc_lifetimes.h', ROOT / 'runtime/include/ironwood_gc_roots.h',
                        ROOT / 'integration-tests/cases/gc4_lifetimes.iron', ROOT / 'scripts/test-gc-lifetimes.py',
                        ROOT / 'compiler/src/test/java/ironwood/compiler/GcLifetimeTests.java',
                        ROOT / 'compiler/src/main/cpp/GcNonmovingStatepoints.cpp']
    report = {'format': 1, 'production_enabled': False, 'target': args.target,
              'root_abi': 'ironwood-gc-roots-2', 'owner_abi': 'ironwood-gc-runtime-1',
              'sdk_configuration': manifest['gc.config.sha256'], 'guard_kib': engine.GUARD_KIB,
              'per_child_timeout_seconds': 60, 'results': results,
              'source.sha256': {str(p.relative_to(ROOT)): gc.digest(p) for p in inputs},
              'output.sha256': {p.name: gc.digest(p) for p in out.glob('probe-*')}}
    (out / 'results.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'passed': [r['case'] for r in results], 'target': args.target}, indent=2))


if __name__ == '__main__':
    main()
