#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Offline GC1 fixture link through the runtime source and prepared static SDK."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('prepare_gc', ROOT / 'scripts/prepare-gc.py')
gc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gc)


def build(sdk, llvm, target, destination, image=False):
    manifest, flags = gc.verify(sdk, llvm, target)  # Never prepare/download implicitly.
    if destination.exists():
        raise ValueError('refusing to overwrite fixture: ' + str(destination))
    destination.parent.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'integration-tests/native/gc_engine_probe.c'
    obj = destination.with_suffix('.o')
    compile_flags = [*flags, '-O3', '-std=c11', '-fPIC', '-fvisibility=hidden',
                     '-Wall', '-Wextra', '-Werror', '-Wpedantic',
                     '-I' + str(sdk / 'runtime/include')]
    if image:
        compile_flags.append('-DIW_GC_IMAGE')
    subprocess.run([str(llvm / 'bin/clang'), *compile_flags, '-c', str(source), '-o', str(obj)], check=True)
    libraries = [sdk / 'lib/libironwood_gc_engine.a', sdk / 'lib/libgc.a']
    link_flags = [*flags, *manifest['configuration']['target.ldflags']]
    inputs = [source, obj, *libraries, sdk / 'build.json']
    if image:
        policy = destination.with_suffix('.exports')
        link_flags += ['-dynamiclib' if target.startswith('macos') else '-shared']
        link_flags += gc.image_policy(target, policy)
        inputs.append(policy)
    subprocess.run([str(llvm / 'bin/clang'), *link_flags, str(obj), *map(str, libraries),
                    '-o', str(destination)], check=True)
    symbols = gc.audit_image(destination, llvm, target) if image else {}
    if image:
        all_symbols = gc.output([llvm / 'bin/llvm-nm', '--defined-only', destination])
        if not all(name in all_symbols for name in ('GC_init', 'GC_gcollect', 'iw_gc_collect')):
            raise ValueError('fixture image did not retain a real private collector')
    record = {'gc.config.sha256': manifest['gc.config.sha256'], 'image': image,
              'compile.flags': [str(p).replace(str(sdk), '{sdk}') for p in compile_flags],
              'linked.inputs.sha256': {('sdk/' + str(p.relative_to(sdk))) if p.is_relative_to(sdk)
                                      else str(p.relative_to(ROOT)) if p.is_relative_to(ROOT)
                                      else p.name: gc.digest(p) for p in inputs},
              'output.sha256': gc.digest(destination), 'symbols': symbols}
    destination.with_suffix('.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--llvm-home', type=Path, required=True)
    parser.add_argument('--target', default=gc.host_target())
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--image', action='store_true')
    args = parser.parse_args()
    record = build(args.sdk.resolve(), args.llvm_home.resolve(), args.target, args.output.resolve(), args.image)
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
