#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded GC6 carrier slice, not complete bridge or suspended-segment qualification."""
import argparse
import hashlib
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
    parser.add_argument('--jni-only', action='store_true', help='rerun JNI unit controls after fixture-only changes')
    args = parser.parse_args()
    sdk, llvm, java, out = (getattr(args, name).resolve() for name in ('sdk', 'llvm_home', 'java_home', 'output'))
    manifest, flags = engine.gc.verify(sdk, llvm, engine.gc.host_target())
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'
    raw.mkdir()
    generated = out / 'generated'
    results = []
    env = {'JAVA_HOME': str(java), 'IRONWOOD_GC_HOME': str(sdk)}

    def run(command, name):
        result = engine.run(list(map(str, command)), raw, name, env)
        results.append(result)
        print(name + ': passed', flush=True)

    run([java / 'bin/java', '-ea', '-cp', str(ROOT / 'compiler/build/classes') + ':'
         + str(ROOT / 'compiler/build/test-classes'), 'ironwood.compiler.Gc6CarrierTests', generated], 'generate')
    run([java / 'bin/javac', '--release', '21', '-d', out,
         ROOT / 'scripts/java-bridge/Gc6CarrierCompletion.java'], 'jvm-consumer')
    classes = out / 'classes'
    compiler = [java / 'bin/java', '-jar', ROOT / 'compiler/build/ironwoodc.jar']
    if not args.jni_only:
        run([*compiler, generated / '_ForeignFailure.iron', generated / 'CarrierProbe.iron', '-d', classes], 'source')
    # Use the actual generated cleanup. Protected-operation doubles isolate JNI
    # completion; they do not establish compiler shim roots or HotSpot reentry.
    fragment = (generated / 'completion.inc').read_text()
    call = '(*frame->env)->DeleteGlobalRef(frame->env, (jobject)(uintptr_t)reference.value.wide);'
    assert fragment.count(call) == 1
    (generated / 'completion-instrumented.inc').write_text(fragment.replace(
        call, 'tracked_delete(frame->env, (jobject)(uintptr_t)reference.value.wide);'))
    mac = engine.gc.host_target() == 'macos-arm64'
    for level in args.level or ('O0', 'O1', 'O2', 'O3'):
        if not args.jni_only:
            executable = out / ('graph-' + level)
            run([*compiler, '--link', '--main-class', 'ironwood.bridge.internal.CarrierProbe', '-cp', classes,
                 '-' + level, '--llvm-home', llvm, '-o', executable], level + '-normal-link')
            run([executable], level + '-normal-run')
            assert (raw / (level + '-normal-run.stdout')).read_text() == 'GC6 carrier graph passed\n'
        images = []
        for label, symbol in [('A', 'Java_Gc6CarrierCompletion_verify'), ('B', 'Java_Gc6CarrierCompletion_00024Second_verify')]:
            policy = out / ('exports-' + label)
            policy.write_text('_' + symbol + '\n' if mac else '{ global: ' + symbol + '; local: *; };\n')
            link_flags = ['-dynamiclib', '-Wl,-exported_symbols_list,' + str(policy), '-Wl,-twolevel_namespace'] if mac else [
                '-shared', '-Wl,--version-script=' + str(policy), '-Wl,-Bsymbolic,--exclude-libs,ALL,-z,defs,-z,now,-z,relro']
            image = out / ('completion-' + label + '-' + level + ('.dylib' if mac else '.so'))
            images.append(image)
            run([llvm / 'bin/clang', *flags, '-' + level, '-std=c11', '-fPIC', '-fvisibility=hidden',
                 '-Wall', '-Wextra', '-Werror', '-Wpedantic', '-I' + str(java / 'include'),
                 '-I' + str(java / 'include' / ('darwin' if mac else 'linux')), '-I' + str(ROOT / 'runtime/include'),
                 '-I' + str(generated), '-DIW_ENTRY=' + symbol, ROOT / 'integration-tests/native/gc6_carrier_completion.c',
                 sdk / 'lib/libironwood_gc_engine.a', sdk / 'lib/libgc.a', *manifest['configuration']['target.ldflags'],
                 *link_flags, '-o', image], level + '-jni-link-' + label)
            command = [llvm / 'bin/llvm-objdump', '--macho', '--exports-trie', image] if mac else [
                llvm / 'bin/llvm-nm', '--dynamic', '--defined-only', '--extern-only', image]
            run(command, level + '-exports-' + label)
            table = (raw / (level + '-exports-' + label + '.stdout')).read_text()
            names = re.findall(r'^0x[0-9A-Fa-f]+\s+(\S+)', table, re.MULTILINE) if mac else [
                line.split()[-1] for line in table.splitlines() if line.strip()]
            assert names == [('_' if mac else '') + symbol], names
            run([llvm / 'bin/llvm-nm', '--defined-only', image], level + '-definitions-' + label)
            definitions = (raw / (level + '-definitions-' + label + '.stdout')).read_text()
            assert all(re.search(r'\b_?' + name + r'$', definitions, re.MULTILINE) for name in (
                'GC_init', 'GC_gcollect', 'iw_gc_collect')), 'actual collector absent'
            command = [llvm / 'bin/llvm-nm', '--undefined-only', image] if mac else [
                llvm / 'bin/llvm-nm', '--dynamic', '--undefined-only', image]
            run(command, level + '-imports-' + label)
            assert not re.search(r'\b_?(GC_|iw_gc_)\w+', (raw / (level + '-imports-' + label + '.stdout')).read_text())
        run([java / 'bin/java', '-Xcheck:jni', '-cp', out, 'Gc6CarrierCompletion', *images], level + '-jni-run')
        assert (raw / (level + '-jni-run.stdout')).read_text() == 'GC6 JNI carrier completion passed\n'
        assert not (raw / (level + '-jni-run.stderr')).read_text()
    report = {'format': 1, 'gc6_complete': False, 'bridge_outputs_qualified': False,
              'target': engine.gc.host_target(), 'guard_kib': engine.GUARD_KIB, 'per_child_timeout_seconds': 60,
              'sdk_configuration': manifest['gc.config.sha256'], 'results': results,
              'source.sha256': {str(p.relative_to(ROOT)): engine.gc.digest(p) for p in [
                  Path(__file__).resolve(), ROOT / 'compiler/src/test/java/ironwood/compiler/Gc6CarrierTests.java',
                  ROOT / 'integration-tests/native/gc6_carrier_completion.c',
                  ROOT / 'scripts/java-bridge/Gc6CarrierCompletion.java']},
              'generated_cleanup.sha256': hashlib.sha256(fragment.encode()).hexdigest()}
    (out / 'results.json').write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')


if __name__ == '__main__':
    main()
