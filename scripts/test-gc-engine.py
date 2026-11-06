#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused bounded GC1 qualification, including nearby failing SDK/isolation controls."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('build_gc', ROOT / 'scripts/build-gc-fixture.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
gc = builder.gc
GUARD_KIB = 16 * 1024 * 1024


def run(command, directory, name, extra_env=None, expected_exit=0, executable=None):
    env = {key: value for key, value in os.environ.items() if not key.startswith('GC_')}
    env.update(extra_env or {})
    stdout, stderr = directory / (name + '.stdout'), directory / (name + '.stderr')
    peak = 0
    with stdout.open('w') as out, stderr.open('w') as err:
        child = subprocess.Popen(command, executable=executable, env=env, stdout=out, stderr=err, start_new_session=True)
        start = time.monotonic()
        while child.poll() is None:
            # Include toolchain children of a guarded compiler driver, not only
            # the Java/Python parent. Every run owns this fresh process group.
            rows = subprocess.run(['ps', '-axo', 'pgid=,rss='], capture_output=True, text=True).stdout.splitlines()
            rss = sum(int(fields[1]) for row in rows if len(fields := row.split()) == 2 and fields[0] == str(child.pid))
            peak = max(peak, rss)
            if peak >= GUARD_KIB or time.monotonic() - start > 60:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
                raise RuntimeError('16 GiB RSS guard or 60-second limit: ' + name)
            time.sleep(0.05)
        if child.returncode != expected_exit:
            raise RuntimeError(name + ': ' + stderr.read_text()[-2000:])
    return {'case': name, 'exit': child.returncode, 'sampled_peak_rss_kib': peak,
            'stdout.sha256': gc.digest(stdout), 'stderr.sha256': gc.digest(stderr),
            'data': [json.loads(line) for line in stdout.read_text().splitlines() if line.startswith('{')]}


def rejected(name, operation):
    try:
        operation()
    except (ValueError, FileNotFoundError, KeyError) as error:
        return {'case': name, 'rejected': True, 'reason': str(error).split(':')[0]}
    raise AssertionError('negative control was admitted: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--llvm-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--target', default=gc.host_target())
    args = parser.parse_args()
    sdk, llvm, out = args.sdk.resolve(), args.llvm_home.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'
    raw.mkdir()
    manifest, flags = gc.verify(sdk, llvm, args.target)
    builds = [builder.build(sdk, llvm, args.target, out / 'probe'),
              builder.build(sdk, llvm, args.target, out / 'image', image=True)]
    results = [run([str(out / 'probe'), case], raw, case) for case in ('policy', 'accounting', 'domains')]
    results.append(run([str(out / 'probe'), 'environment'], raw, 'environment', {'GC_DONT_GC': '1'}))
    # A relocated SDK, verified with network access forbidden at the download API.
    relocated = out / 'relocated SDK'
    shutil.copytree(sdk, relocated)
    with patch.object(gc.urllib.request, 'urlopen', side_effect=AssertionError('network during offline verification')):
        gc.verify(relocated, llvm, args.target)
        for name in ('libgc.a', 'libironwood_gc_engine.a'):
            assert gc.audit_archive(sdk / 'lib' / name, llvm, args.target) == gc.audit_archive(
                relocated / 'lib' / name, llvm, args.target), 'archive audit identity depends on path'
        builds.append(builder.build(relocated, llvm, args.target, out / 'relocated-probe'))
    results.append(run([str(out / 'relocated-probe'), 'domains'], raw, 'relocated-domains'))
    negatives = []
    for name in ('build.json', 'lib/libgc.a', 'lib/libironwood_gc_engine.a', 'include/gc/gc.h',
                 'runtime/src/ironwood_gc_engine.c', 'runtime/include/ironwood_gc_engine.h',
                 'include/ironwood_gc_configuration.h'):
        path = relocated / name
        original = path.read_bytes()
        path.unlink()
        negatives.append(rejected('missing ' + name, lambda: gc.verify(relocated, llvm, args.target)))
        path.write_bytes(original + b'altered')
        negatives.append(rejected('altered ' + name, lambda: gc.verify(relocated, llvm, args.target)))
        path.write_bytes(original)
    path = relocated / 'build.json'
    original = path.read_bytes()
    changed = json.loads(original)
    changed['gc.config.sha256'] = '0' * 64
    path.write_text(json.dumps(changed))
    negatives.append(rejected('configuration hash mismatch', lambda: gc.verify(relocated, llvm, args.target)))
    for key, value in [('target', 'linux-x86_64' if args.target != 'linux-x86_64' else 'macos-arm64'),
                       ('adapter.abi', 'incompatible'), ('memory.abi', 'old-manual-abi'),
                       ('layout.version', 2), ('roots.version', 2), ('isolation', 'public')]:
        changed = json.loads(original)
        changed['configuration'][key] = value
        path.write_text(json.dumps(changed))
        negatives.append(rejected('configuration ' + key, lambda: gc.verify(relocated, llvm, args.target)))
    path.write_bytes(original)
    config = relocated / 'configuration.json'
    data = config.read_bytes()
    config.write_bytes(data + b'\n')
    negatives.append(rejected('noncanonical configuration', lambda: gc.verify(relocated, llvm, args.target)))
    config.write_bytes(data)
    changed = json.loads(original)
    del changed['files.sha256']['include/gc/gc.h']
    path.write_text(json.dumps(changed))
    negatives.append(rejected('omitted manifest header', lambda: gc.verify(relocated, llvm, args.target)))
    path.write_bytes(original)
    # Object-format checks cannot be bypassed by relabeling manifest target text.
    other = 'linux-x86_64' if args.target.endswith('arm64') else 'linux-arm64'
    negatives.append(rejected('actual archive wrong target',
                             lambda: gc.audit_archive(sdk / 'lib/libgc.a', llvm, other)))
    leak = out / 'leak.c'
    leak.write_text('// SPDX-License-Identifier: MIT OR Apache-2.0\n'
                    '__attribute__((visibility("default"))) void GC_gc1_leak(void) {}\n')
    subprocess.run([str(llvm / 'bin/clang'), *flags, '-fPIC', '-c', str(leak), '-o', str(out / 'leak.o')], check=True)
    subprocess.run([str(llvm / 'bin/llvm-ar'), 'rcD', str(out / 'leak.a'), str(out / 'leak.o')], check=True)
    negatives.append(rejected('public archive symbol', lambda: gc.audit_archive(out / 'leak.a', llvm, args.target)))
    subprocess.run([str(llvm / 'bin/clang'), *flags, '-dynamiclib' if args.target.startswith('macos') else '-shared',
                    str(out / 'leak.o'), '-o', str(out / 'leak-image')], check=True)
    negatives.append(rejected('public image symbol', lambda: gc.audit_image(out / 'leak-image', llvm, args.target)))
    # No managed free/realloc API can be called through the adapter header.
    misuse = out / 'domain-misuse.c'
    misuse.write_text('// SPDX-License-Identifier: MIT OR Apache-2.0\n'
                      '#include "ironwood_gc_engine.h"\nvoid misuse(void *p) { iw_gc_deallocate(p); }\n')
    compiled = subprocess.run([str(llvm / 'bin/clang'), *flags, '-std=c11', '-Werror',
                               '-I' + str(sdk / 'runtime/include'), '-c', str(misuse),
                               '-o', str(out / 'domain-misuse.o')], capture_output=True, text=True)
    assert compiled.returncode != 0 and 'iw_gc_deallocate' in compiled.stderr
    negatives.append({'case': 'managed deallocation API absent', 'rejected': True})
    report = {'baseline_revision': gc.output(['git', 'rev-parse', 'HEAD'], cwd=ROOT),
              'host_role': 'machost' if args.target.startswith('macos') else 'armvm' if args.target.endswith('arm64') else 'x86host',
              'target': args.target, 'guard_kib': GUARD_KIB, 'per_child_timeout_seconds': 60,
              'sdk': manifest, 'builds': builds, 'results': results, 'negative_controls': negatives,
              'source.sha256': {str(p.relative_to(ROOT)): gc.digest(p) for p in
                                [ROOT / 'scripts/test-gc-engine.py', ROOT / 'scripts/build-gc-fixture.py',
                                 ROOT / 'integration-tests/native/gc_engine_probe.c']}}
    (out / 'results.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'passed': [r['case'] for r in results], 'negative_controls': len(negatives),
                      'gc.config.sha256': manifest['gc.config.sha256']}, indent=2))


if __name__ == '__main__':
    main()
