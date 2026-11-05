#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded GC3 source/typed-IR/statepoint/native-root qualification, never production GC."""
import argparse
import importlib.util
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('test_engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
gc = engine.gc
CASES = ('allocate', 'recursion', 'merge', 'loop', 'pending', 'yield', 'capture', 'initialize',
         'constructor', 'exception', 'derived', 'stack', 'stack-clear', 'dispatch', 'static', 'native', 'scalar',
         'version', 'width', 'endian', 'exceptions', 'foreign', 'static-count', 'native-count', 'scratch', 'interior', 'one-past',
         'missing-map', 'stale-version', 'bad-count', 'bad-location', 'stale-abi', 'stale-pc', 'stale-slot', 'stale-function', 'boundary', 'legacy-heap')
MUTATED = ('missing-map', 'stale-version', 'bad-count', 'bad-location', 'stale-abi', 'stale-pc', 'stale-slot', 'stale-function')


def mutate_object(original, case):
    """Deliberate negative images bypass object admission, then must refuse at load/walk."""
    data = bytearray(original)
    assert struct.unpack_from('<I', data)[0] == 0xfeedfacf
    position, section, symbols, strings = 32, None, None, None
    sections = []
    for _ in range(struct.unpack_from('<I', data, 16)[0]):
        command, size = struct.unpack_from('<II', data, position)
        if command == 2:
            symbols, count, strings, _ = struct.unpack_from('<IIII', data, position + 8)
        if command == 0x19:
            for index in range(struct.unpack_from('<I', data, position + 64)[0]):
                header = position + 72 + index * 80
                sections.append(header)
                if data[header:header + 16].split(b'\0')[0] == b'__llvm_stackmaps':
                    section = header
        position += size
    assert section is not None
    offset = struct.unpack_from('<I', data, section + 48)[0]
    if case == 'missing-map':
        flags = struct.unpack_from('<I', data, section + 64)[0]
        struct.pack_into('<I', data, section + 64, flags & ~0x10000000)
    elif case == 'stale-version':
        data[offset] = 2
    elif case == 'bad-count':
        struct.pack_into('<I', data, offset + 4, 0xffffffff)
    elif case == 'stale-function':
        relocations = struct.unpack_from('<I', data, section + 56)[0]
        first = struct.unpack_from('<I', data, relocations + 4)[0]
        second = struct.unpack_from('<I', data, relocations + 12)[0]
        struct.pack_into('<I', data, relocations + 4, (first & 0xff000000) | (second & 0xffffff))
    elif case == 'stale-abi':
        for index in range(count):
            name, _, number, _, value = struct.unpack_from('<IBBHQ', data, symbols + index * 16)
            end = data.index(0, strings + name)
            if data[strings + name:end] == b'_iw_gc_root_abi':
                header = sections[number - 1]
                address = struct.unpack_from('<Q', data, header + 32)[0]
                payload = struct.unpack_from('<I', data, header + 48)[0]
                struct.pack_into('<I', data, payload + value - address, 2)
                break
        else:
            raise AssertionError('missing compiler root ABI')
    else:
        nf, nc, nr = struct.unpack_from('<III', data, offset + 4)
        record = offset + 16 + nf * 24 + nc * 8
        if case == 'bad-location':
            data[record + 16] = 1  # register location, outside the qualified form set
        elif case == 'stale-slot':
            for _ in range(nr):
                locations = struct.unpack_from('<H', data, record + 14)[0]
                for index in range(3, locations):
                    p = record + 16 + index * 12
                    if data[p] == 3:
                        displacement = struct.unpack_from('<i', data, p + 8)[0]
                        struct.pack_into('<i', data, p + 8, displacement + 8)
                        return data
                tail = (record + 16 + locations * 12 + 7) & ~7
                record = (tail + 4 + struct.unpack_from('<H', data, tail + 2)[0] * 4 + 7) & ~7
            raise AssertionError('no root spill location')
        elif case == 'stale-pc':
            for _ in range(nr):
                delta = struct.unpack_from('<I', data, record + 8)[0]
                struct.pack_into('<I', data, record + 8, delta + 1)
                locations = struct.unpack_from('<H', data, record + 14)[0]
                tail = (record + 16 + locations * 12 + 7) & ~7
                liveouts = struct.unpack_from('<H', data, tail + 2)[0]
                record = (tail + 4 + liveouts * 4 + 7) & ~7
        else:
            raise ValueError(case)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--llvm-home', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--target', default=gc.host_target())
    parser.add_argument('--level', action='append', choices=('O0', 'O1', 'O2', 'O3'))
    parser.add_argument('--case', action='append', choices=CASES)
    parser.add_argument('--cross-objects', action='store_true', help='structural ELF checks only, no native qualification')
    args = parser.parse_args()
    if args.target != 'macos-arm64':
        raise ValueError('GC3 root reader has no native qualification for ' + args.target)
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
                          'ironwood.compiler.GcRootTests', str(generated), str(llvm), str(target)], raw, 'compiler-pipeline')]
    objects = []
    for source in (ROOT / 'runtime/src/ironwood_gc_roots.c', ROOT / 'integration-tests/native/gc_roots_probe.c',
                   ROOT / 'integration-tests/native/gc_scalar_exception.cpp'):
        obj = out / (source.stem + '.o')
        command = [str(llvm / 'bin/clang'), *flags, '-std=c++17' if source.suffix == '.cpp' else '-std=c11', '-O3', '-fPIC', '-fvisibility=hidden',
                   '-Wall', '-Wextra', '-Werror', '-Wpedantic', '-I' + str(ROOT / 'runtime/include'),
                   '-I' + str(sdk / 'include/gc'), '-I' + str(generated), '-c', str(source), '-o', str(obj)]
        results.append(engine.run(command, raw, source.stem + '-compile'))
        objects.append(obj)
    for level in args.level or ('O0', 'O1', 'O2', 'O3'):
        executable = out / ('probe-' + level)
        contract = out / (level + '-root-contract.o')
        results.append(engine.run([str(llvm / 'bin/clang'), *flags, '-std=c11', '-O3', '-fvisibility=hidden', '-c',
                                   str(generated / level / 'root-contract.c'), '-o', str(contract)], raw, level + '-contract'))
        def link(obj, destination, name):
            results.append(engine.run([str(llvm / 'bin/clang'), '--driver-mode=g++', *flags,
                                       *manifest['configuration']['target.ldflags'], '-Wl,-dead_strip',
                                       str(obj), str(contract), *map(str, objects), str(sdk / 'lib/libironwood_gc_engine.a'),
                                       str(sdk / 'lib/libgc.a'), '-o', str(destination)], raw, name))
        program_object = generated / level / 'program.o'
        link(program_object, executable, level + '-link')
        for case in args.case or CASES:
            destination = executable
            if case in MUTATED:
                obj = out / (level + '-' + case + '.o')
                obj.write_bytes(mutate_object(program_object.read_bytes(), case))
                destination = out / (level + '-' + case)
                link(obj, destination, level + '-' + case + '-link')
            expected = 86 if case in ('scratch', 'interior', 'one-past', 'boundary', 'legacy-heap') else 0
            results.append(engine.run([str(destination), case], raw, level + '-' + case, expected_exit=expected))
    if args.cross_objects:
        cross_objects(args, generated, llvm, raw, out, results)
    report = {'format': 1, 'production_enabled': False, 'target': args.target, 'root_abi': 'ironwood-gc-roots-1',
              'baseline_revision': gc.output(['git', 'rev-parse', 'HEAD'], cwd=ROOT),
              'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60,
              'sdk_configuration': manifest['gc.config.sha256'], 'results': results,
              'source.sha256': {str(p.relative_to(ROOT)): gc.digest(p) for p in [
                  ROOT / 'runtime/src/ironwood_gc_roots.c', ROOT / 'runtime/include/ironwood_gc_roots.h',
                  ROOT / 'integration-tests/native/gc_roots_probe.c', ROOT / 'integration-tests/native/gc_scalar_exception.cpp',
                  ROOT / 'integration-tests/cases/gc3_closure.iron', ROOT / 'scripts/test-gc-roots.py']},
              'output.sha256': {p.name: gc.digest(p) for p in out.glob('probe-*')}}
    (out / 'results.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'passed': [r['case'] for r in results], 'configuration': manifest['gc.config.sha256']}, indent=2))


def cross_objects(args, generated, llvm, raw, out, results):
    """Compiler-derived IR and actual ELF relocations, with execution still refused."""
    for target, triple in (('linux-arm64', 'aarch64-unknown-linux-gnu'), ('linux-x86_64', 'x86_64-unknown-linux-gnu')):
        directory = out / target
        directory.mkdir()
        cc = [str(llvm / 'bin/clang'), '--target=' + triple, '-S', '-emit-llvm', '-x', 'c', '/dev/null', '-o', str(directory / 'target.ll')]
        results.append(engine.run(cc, raw, target + '-target'))
        target_lines = '\n'.join(line for line in (directory / 'target.ll').read_text().splitlines() if line.startswith('target '))
        original = (generated / 'O3/program.ll').read_text()
        body = '\n'.join(line for line in original.splitlines() if not line.startswith('target '))
        source = directory / 'program.ll'
        source.write_text(target_lines + '\n' + body + '\n')
        commands = [
            ('assemble', [str(llvm / 'bin/llvm-as'), str(source), '-o', str(directory / 'program.bc')]),
            ('optimize', [str(llvm / 'bin/opt'), '-passes=default<O3>', '-S', str(directory / 'program.bc'), '-o', str(directory / 'program.opt.ll')]),
            ('trace', [str(args.java_home / 'bin/java'), '-ea', '-cp', str(ROOT / 'compiler/build/classes') + ':' + str(ROOT / 'compiler/build/test-classes'),
                       'ironwood.compiler.backend.GcRootObjectTests', 'trace', str(directory / 'program.opt.ll'), str(directory / 'program.traced.ll')]),
            ('statepoints', [str(llvm / 'bin/opt'), '-passes=rewrite-statepoints-for-gc,verify', '-S', str(directory / 'program.traced.ll'), '-o', str(directory / 'program.roots.ll')]),
            ('object', [str(llvm / 'bin/llc'), '-O=3', '--relocation-model=pic', '-filetype=obj', str(directory / 'program.roots.ll'), '-o', str(directory / 'program.o')]),
            ('retain', [str(args.java_home / 'bin/java'), '-ea', '-cp', str(ROOT / 'compiler/build/classes') + ':' + str(ROOT / 'compiler/build/test-classes'),
                        'ironwood.compiler.backend.GcRootObjectTests', 'retain', str(directory / 'program.o'), str(directory / 'program.retained.o')]),
            ('name', [str(llvm / 'bin/llvm-objcopy'), '--rename-section', '.llvm_stackmaps=ironwood_gc_maps', str(directory / 'program.retained.o')]),
        ]
        for name, command in commands:
            results.append(engine.run(command, raw, target + '-' + name))


if __name__ == '__main__':
    main()
