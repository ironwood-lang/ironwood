#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Independent normal-path accounting, graph and bounded-capacity controls."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
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
    parser.add_argument('--case', action='append', choices=('graphs', 'accounting', 'exceptions', 'retention', 'names', 'reuse'))
    args = parser.parse_args()
    sdk, llvm, java, out = (getattr(args, name).resolve() for name in ('sdk', 'llvm_home', 'java_home', 'output'))
    manifest, _ = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'
    raw.mkdir()
    results = []
    env = {'JAVA_HOME': str(java), 'IRONWOOD_GC_HOME': str(sdk),
           'IRONWOOD_RUNTIME_HOME': str(ROOT), 'IRONWOOD_STDLIB_HOME': str(ROOT)}
    compiler = [java / 'bin/java', '-jar', ROOT / 'compiler/build/ironwoodc.jar']
    source = ROOT / 'integration-tests/cases/gc_audit.iron'
    builder = ROOT / 'compiler/src/test/java/ironwood/compiler/GcAuditLink.java'
    report = {'format': 1, 'revision': engine.gc.output(['git', 'rev-parse', 'HEAD'], cwd=ROOT),
              'target': engine.gc.host_target(), 'sdk_configuration': manifest['gc.config.sha256'],
              'sdk_manifest.sha256': engine.gc.digest(sdk / 'build.json'), 'results': results,
              'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60, 'completed': False,
              'capacity_setup': 'qualification-only GC_set_max_heap_size(16 MiB) after normal cold startup; unchanged runtime/SDK',
              'source.sha256': {str(p.relative_to(ROOT)): engine.gc.digest(p) for p in (Path(__file__), source, builder)}}

    def run(command, name):
        if shutil.disk_usage(out).free < 50 * 1024**3:
            raise RuntimeError('audit disk free floor: 50 GiB')
        if sum(p.stat().st_size for p in out.rglob('*') if p.is_file()) > 16 * 1024**3:
            raise RuntimeError('audit output cap: 16 GiB')
        argv = list(map(str, command))
        result = engine.run(argv, raw, name, env)
        result['command'] = [v.replace(str(ROOT) + '/', '').replace(str(Path.home()), '/Users/developer') for v in argv]
        results.append(result)
        (out / 'results.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        print(name + ': passed', flush=True)
        return (raw / (name + '.stdout')).read_text()

    classes = out / 'classes'
    run([*compiler, source, '-d', classes], 'classes')
    run([java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp',
         ROOT / 'compiler/build/classes', '-d', out / 'builder', builder], 'builder')
    expected = {'graphs': 'graphs: 12 phases, 8 retained, 0 after drop\n',
                'accounting': 'construction: 1 counted, published survivor, 0 after drop\n',
                'exceptions': 'exceptions: 12 phases, 6 retained, sidecars excluded, 0 after drop\n',
                'retention': 'cache: 12 phases, retained slot +1, explicit clear +0\n',
                'names': 'names: only 2 explicit calls, no automatic cleanup\n'}
    for level in args.level or ('O0', 'O1', 'O2', 'O3'):
        binary = out / level
        ll = out / (level + '.ll')
        run([*compiler, '--link', '--main-class', 'GcAudit', '-cp', classes, '--emit-llvm', ll,
             '--llvm-home', llvm, '-' + level, '-o', binary], level + '-link')
        generated = ll.read_text()
        forbidden = r'@"?(ironwood_deallocate|ironwood_release_owned_[\w.]*|ironwood_destroy_array_elements|ironwood\.destroy|ironwood\.rollback)\b'
        if re.search(forbidden, generated):
            raise AssertionError('legacy managed cleanup in actual emitted IR')
        for case, stdout in expected.items():
            if args.case and case not in args.case:
                continue
            if run([binary, case], level + '-' + case) != stdout:
                raise AssertionError('unexpected independent fixture output: ' + case)
        if not args.case or 'reuse' in args.case:
            bounded = out / (level + '-bounded')
            run([java / 'bin/java', '-cp', str(out / 'builder') + ':' + str(ROOT / 'compiler/build/classes'),
                 'ironwood.compiler.GcAuditLink', llvm, ll, bounded, level], level + '-bounded-link')
            reuse = run([bounded, 'reuse'], level + '-reuse')
            if not re.fullmatch(r'capacity: 6 exhaustion/drop/collect/refill phases\n[1-9][0-9]*\n', reuse):
                raise AssertionError('missing bounded capacity observation')
        if level == 'O3':
            archive = out / 'audit.ironjar'
            run([java / 'bin/java', '-cp', ROOT / 'compiler/build/classes', 'ironwood.compiler.IronJarMain',
                 '--create', '--file', archive, classes], 'archive')
            alternate = out / 'O3-archive'
            run([*compiler, '--link', '--main-class', 'GcAudit', '-cp', archive,
                 '--llvm-home', llvm, '-O3', '-o', alternate], 'archive-link')
            for case in args.case or ('names', 'graphs', 'accounting', 'exceptions'):
                if case == 'reuse':
                    continue
                if run([alternate, case], 'archive-' + case) != expected[case]:
                    raise AssertionError('archive behavior differs: ' + case)
    report['completed'] = True
    (out / 'results.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
