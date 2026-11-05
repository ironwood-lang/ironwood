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
ELF_CASES = ('missing-unwind', 'writable-map')


def mutate_object(original, case):
    """Deliberate negative images bypass object admission, then must refuse at load/walk."""
    data = bytearray(original)
    if data[:4] == b'\x7fELF':
        return mutate_elf(data, case)
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
        mutate_map(data, offset, case)
    return data


def mutate_map(data, offset, case):
    nf, nc, nr = struct.unpack_from('<III', data, offset + 4)
    record = offset + 16 + nf * 24 + nc * 8
    if case == 'stale-version':
        data[offset] = 2
    elif case == 'bad-count':
        struct.pack_into('<I', data, offset + 4, 0xffffffff)
    elif case == 'bad-location':
        data[record + 16] = 1  # register location, outside the qualified form set
    elif case in ('stale-slot', 'stale-pc'):
        for _ in range(nr):
            locations = struct.unpack_from('<H', data, record + 14)[0]
            if case == 'stale-slot':
                for index in range(3, locations):
                    p = record + 16 + index * 12
                    if data[p] == 3:
                        displacement = struct.unpack_from('<i', data, p + 8)[0]
                        struct.pack_into('<i', data, p + 8, displacement + 8)
                        return
            else:
                delta = struct.unpack_from('<I', data, record + 8)[0]
                struct.pack_into('<I', data, record + 8, delta + 1)
            tail = (record + 16 + locations * 12 + 7) & ~7
            record = (tail + 4 + struct.unpack_from('<H', data, tail + 2)[0] * 4 + 7) & ~7
        if case == 'stale-slot':
            raise AssertionError('no root spill location')
    else:
        raise ValueError(case)


def mutate_elf(data, case):
    table = struct.unpack_from('<Q', data, 40)[0]
    stride, count, names_index = struct.unpack_from('<HHH', data, 58)
    assert stride == 64
    sections = [table + i * stride for i in range(count)]
    names = struct.unpack_from('<Q', data, sections[names_index] + 24)[0]
    def section_name(header):
        offset = names + struct.unpack_from('<I', data, header)[0]
        return data[offset:data.index(0, offset)]
    root = next(h for h in sections if section_name(h) == b'ironwood_gc_maps')
    offset = struct.unpack_from('<Q', data, root + 24)[0]
    if case == 'stale-function':
        relocation = next(h for h in sections if struct.unpack_from('<I', data, h + 4)[0] == 4
                          and struct.unpack_from('<I', data, h + 44)[0] == sections.index(root))
        payload = struct.unpack_from('<Q', data, relocation + 24)[0]
        second = struct.unpack_from('<Q', data, payload + 32)[0]
        struct.pack_into('<Q', data, payload + 8, second)
        struct.pack_into('<q', data, payload + 16, struct.unpack_from('<q', data, payload + 40)[0])
    elif case == 'stale-abi':
        symbols = next(h for h in sections if struct.unpack_from('<I', data, h + 4)[0] == 2)
        payload, size = struct.unpack_from('<QQ', data, symbols + 24)
        strings = sections[struct.unpack_from('<I', data, symbols + 40)[0]]
        strings_offset = struct.unpack_from('<Q', data, strings + 24)[0]
        for symbol in range(payload, payload + size, 24):
            name, _, _, number, value = struct.unpack_from('<IBBHQ', data, symbol)
            start = strings_offset + name
            if data[start:data.index(0, start)] == b'iw_gc_root_abi':
                destination = struct.unpack_from('<Q', data, sections[number] + 24)[0] + value
                struct.pack_into('<I', data, destination, 2)
                break
        else:
            raise AssertionError('missing compiler root ABI')
    else:
        mutate_map(data, offset, case)
    return data


def verify_elf_image(path, allow_empty=False):
    """Check final load ranges, not section names, for the retained PIE root ABI."""
    data = path.read_bytes()
    assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', data, 16)[0] == 3
    phoff = struct.unpack_from('<Q', data, 32)[0]
    phsize, phcount = struct.unpack_from('<HH', data, 54)
    assert phsize == 56
    relro, unwind, dynamic = [], False, None
    for header in range(phoff, phoff + phcount * phsize, phsize):
        kind, flags, offset, address, _, size, memory, _ = struct.unpack_from('<IIQQQQQQ', data, header)
        assert kind != 1 or flags & 3 != 3, 'writable executable load segment'
        if kind == 0x6474e552:
            relro.append((address, address + memory))
        if kind == 0x6474e550 and size:
            unwind = True
        if kind == 2:
            dynamic = (offset, size)
    assert unwind and dynamic is not None, 'missing native unwind/dynamic header'
    for entry in range(dynamic[0], dynamic[0] + dynamic[1], 16):
        kind, value = struct.unpack_from('<QQ', data, entry)
        assert kind != 22 and (kind != 30 or value & 4 == 0), 'text relocation'
    table = struct.unpack_from('<Q', data, 40)[0]
    stride, count = struct.unpack_from('<HH', data, 58)
    assert stride == 64
    bounds = {}
    for header in range(table, table + count * stride, stride):
        if struct.unpack_from('<I', data, header + 4)[0] != 2:
            continue
        offset, size, strings = struct.unpack_from('<QQI', data, header + 24)
        strings_offset = struct.unpack_from('<Q', data, table + strings * stride + 24)[0]
        for symbol in range(offset, offset + size, 24):
            name, _, _, _, value = struct.unpack_from('<IBBHQ', data, symbol)
            start = strings_offset + name
            text = data[start:data.index(0, start)]
            if text in (b'__start_ironwood_gc_maps', b'__stop_ironwood_gc_maps'):
                bounds[text] = value
    begin, end = bounds[b'__start_ironwood_gc_maps'], bounds[b'__stop_ironwood_gc_maps']
    assert end >= begin and (allow_empty or end > begin), 'empty root maps'
    assert allow_empty and end == begin or any(low <= begin < end <= high for low, high in relro), 'maps outside RELRO'
    return {'pie': True, 'map_bytes': end - begin, 'maps_in_relro': end > begin,
            'unwind_header': True, 'no_text_relocations': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--llvm-home', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--target', default=gc.host_target())
    parser.add_argument('--level', action='append', choices=('O0', 'O1', 'O2', 'O3'))
    parser.add_argument('--case', action='append', choices=CASES + ELF_CASES)
    parser.add_argument('--cross-objects', action='store_true', help='structural ELF checks only, no native qualification')
    args = parser.parse_args()
    if args.target not in ('macos-arm64', 'linux-x86_64') or args.target != gc.host_target():
        raise ValueError('GC3 root reader has no native qualification for ' + args.target)
    if args.target == 'macos-arm64' and any(case in ELF_CASES for case in args.case or ()):
        raise ValueError('ELF-specific control requires a native Linux target')
    sdk, llvm, out = args.sdk.resolve(), args.llvm_home.resolve(), args.output.resolve()
    manifest, flags = gc.verify(sdk, llvm, args.target)
    assert manifest['configuration']['adapter.abi'] == 'ironwood-gc-engine-3'
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
        def link(obj, destination, name, allow_empty=False):
            link_flags = ['-Wl,-dead_strip'] if args.target == 'macos-arm64' else [
                '-pie', '-L' + str(llvm / 'lib'), '-Wl,-rpath,' + str(llvm / 'lib'),
                '-Wl,--gc-sections,--eh-frame-hdr,-z,defs,-z,now,-z,relro,--exclude-libs,ALL',
                '-Wl,-T,' + str(generated / level / 'root-contract.ld')]
            result = engine.run([str(llvm / 'bin/clang'), '--driver-mode=g++', *flags,
                                       *manifest['configuration']['target.ldflags'], *link_flags,
                                       str(obj), str(contract), *map(str, objects), str(sdk / 'lib/libironwood_gc_engine.a'),
                                       str(sdk / 'lib/libgc.a'), '-o', str(destination)], raw, name)
            if args.target.startswith('linux'):
                result['elf_image'] = verify_elf_image(destination, allow_empty)
            results.append(result)
        program_object = generated / level / 'program.o'
        link(program_object, executable, level + '-link')
        for case in args.case or CASES + (ELF_CASES if args.target.startswith('linux') else ()):
            destination = executable
            if case == 'writable-map':
                data = bytearray(executable.read_bytes())
                phoff = struct.unpack_from('<Q', data, 32)[0]
                phsize, phcount = struct.unpack_from('<HH', data, 54)
                for header in range(phoff, phoff + phcount * phsize, phsize):
                    if struct.unpack_from('<I', data, header)[0] == 0x6474e552:
                        struct.pack_into('<I', data, header, 0)  # Remove actual load-time RELRO protection.
                destination = out / (level + '-' + case)
                destination.write_bytes(data)
                destination.chmod(0o755)
            elif args.target.startswith('linux') and case in ('missing-map', 'missing-unwind'):
                obj = out / (level + '-' + case + '.o')
                section = 'ironwood_gc_maps' if case == 'missing-map' else '.eh_frame'
                results.append(engine.run([str(llvm / 'bin/llvm-objcopy'), '--remove-section=' + section,
                                           str(program_object), str(obj)], raw, level + '-' + case + '-strip'))
                destination = out / (level + '-' + case)
                link(obj, destination, level + '-' + case + '-link', allow_empty=case == 'missing-map')
            elif case in MUTATED:
                obj = out / (level + '-' + case + '.o')
                obj.write_bytes(mutate_object(program_object.read_bytes(), case))
                destination = out / (level + '-' + case)
                link(obj, destination, level + '-' + case + '-link')
            expected = 86 if case in ('scratch', 'interior', 'one-past', 'boundary', 'legacy-heap', 'missing-unwind') else 0
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
    """Compiler-derived IR and ELF relocations; cross execution is never qualification."""
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
