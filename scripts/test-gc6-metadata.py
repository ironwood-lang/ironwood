#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded real JVM load-time refusals for stale and incomplete GC6 pairings."""
import argparse
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
MANIFEST = 'META-INF/ironwood/bridge.properties'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jar', 'java-home', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--type', default='gc6objects.Counter')
    parser.add_argument('--assembled-target', help='check target-specific metadata in an assembled jar')
    args = parser.parse_args()
    jar, java, out = (getattr(args, name).resolve() for name in ('jar', 'java_home', 'output'))
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    results = []

    def run(command, name, expected):
        results.append(engine.run(list(map(str, command)), raw, name))
        if (raw / (name + '.stderr')).read_text() or (raw / (name + '.stdout')).read_text() != expected:
            raise RuntimeError('unexpected compatibility output: ' + name)

    engine.run([str(java / 'bin/javac'), '--release', '21', '-d', str(out),
                str(ROOT / 'scripts/java-bridge/Gc6Compatibility.java')], raw, 'javac')
    entries = {}
    with zipfile.ZipFile(jar) as archive:
        entries = {item.filename: archive.read(item) for item in archive.infolist()}
    manifest = entries[MANIFEST].decode('ascii').splitlines()
    keys = ('schema', 'projection', 'memory.abi', 'layout.version', 'roots.version', 'runtime.owner.version',
            'foreign.segments.version', 'gc.config.sha256', 'native.input.gc.config.sha256',
            'native.input.gc.archive.sha256', 'native.input.gc.adapter.sha256', 'native.input.gc.root-reader.sha256',
            'native.input.gc.sdk.manifest.sha256', 'native.input.llvm.stackmap.version', 'native.input.gc.isolation')
    if args.assembled_target:
        prefix = 'target.' + args.assembled_target + '.'
        keys = tuple(prefix + key[7:] if key.startswith('native.') else prefix + key if key == 'gc.config.sha256' else key for key in keys)
    cases = [('original', None)]
    for key in keys:
        if not any(line.startswith(key + '=') for line in manifest):
            raise RuntimeError('qualification jar lacks ' + key)
        cases.append(('missing-' + key, [line for line in manifest if not line.startswith(key + '=')]))
        cases.append(('stale-' + key, [key + '=stale' if line.startswith(key + '=') else line for line in manifest]))
    cases.append(('missing-manifest', []))
    for name, changed in cases:
        variant = jar
        if changed is not None:
            variant = out / (name + '.jar')
            with zipfile.ZipFile(variant, 'w', compression=zipfile.ZIP_STORED) as archive:
                for entry, value in entries.items():
                    if entry == MANIFEST:
                        if not changed: continue
                        value = ('\n'.join(changed) + '\n').encode('ascii')
                    archive.writestr(entry, value)
        classpath = ':'.join(map(str, (out, variant, jar.parent / 'ironwood-bridge-values.jar')))
        run([java / 'bin/java', '-Xcheck:jni', '-cp', classpath, 'Gc6Compatibility',
             'pass' if changed is None else 'refuse', args.type], name,
            'GC6 compatible image loaded\n' if changed is None else 'GC6 incompatible image refused\n')
        with variant.open('rb') as stream:
            digest = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
        results[-1]['jar.sha256'] = digest.hexdigest()
        results[-1]['jar.bytes'] = variant.stat().st_size
        # Variants are reproducible from this script and the original inventory.
        # Keep one at a time, especially with Linux's complete GCC source bundle.
        if changed is not None: variant.unlink()
        print(name + ': passed', flush=True)
    (out / 'results.json').write_text(json.dumps({'format': 1, 'gc6_complete': False,
        'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60, 'results': results}, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
