#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused D168 Section 8 matrix; never runs compiler suites or adoption workloads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import textwrap

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'integration-tests/target/defer-performance'
TEMPLATE = ROOT / 'integration-tests/cases/defer_performance.iron'
NULLABLE_CASES = ['nullable', 'indirect']
CASES = ['call', 'free', 'pool', 'nullable', 'indirect', 'initialization'] + [
    f'{shape}-{count}' for shape in ['straight', 'transfers', 'failing'] for count in [1, 2, 4, 8]]
FIELDS = ['checksum', 'captures', 'actions', 'bodies', 'failures', 'allocations',
          'live_delta', 'first_allocations', 'first_live_delta', 'ns']
COMPILER = ROOT / 'compiler/build/ironwoodc.jar'


def run(*args, executable=None):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, text=True,
                            executable=None if executable is None else str(executable),
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
    if result.returncode:
        raise RuntimeError(f'{args}: exit {result.returncode}\n{result.stdout}')
    return result.stdout


def region(source, name, body):
    pattern = rf'(?m)^( *)// BEGIN {name}\n.*?^\1// END {name}'
    def replace(match):
        return textwrap.indent(textwrap.dedent(body).strip(), match[1])
    source, count = re.subn(pattern, replace, source, flags=re.S)
    if count != 1:
        raise RuntimeError(f'Missing unique source region {name}')
    return source


def protect(variant, actions, body, throwing=False):
    """Each tuple is (ordered capture statements, delayed invocation)."""
    if not actions:
        return body
    if variant == 'deferred':
        return '\n'.join(capture + '\ndefer ' + action + ';' for capture, action in actions) + '\n' + body
    if variant == 'nested':
        capture, action = actions[0]
        return capture + '\ntry {\n' + textwrap.indent(protect(variant, actions[1:], body, throwing), '    ') \
            + '\n} finally {\n    ' + action + ';\n}'
    # Captures are total primitive computations in Main, in declaration order.
    # Failure-capable cleanup still nests finally so no later action can be skipped.
    cleanup = ''
    for _, action in actions:
        if not cleanup:
            cleanup = action + ';'
        elif throwing:
            cleanup = 'try {\n    ' + action + ';\n} finally {\n' + textwrap.indent(cleanup, '    ') + '\n}'
        else:
            cleanup = action + ';\n' + cleanup
    return '\n'.join(capture for capture, _ in actions) + '\ntry {\n' + textwrap.indent(body, '    ') \
        + '\n} finally {\n' + textwrap.indent(cleanup, '    ') + '\n}'


def fixture(case, variant):
    source = TEMPLATE.read_text()
    setup = 'Cell receiver = null;\nArrayObjectPool<Cell> pool = null;'
    teardown = receiver = ''
    failure_check = 'errors++;'
    if case == 'free':
        exercise = 'byte[] value = new byte[16];\n' + protect(variant, [('', 'free value')],
                    'body(i + value[0]);')
    elif case == 'pool':
        setup = '''Builder builder = new Builder();
ArrayObjectPool<Cell> pool = new ArrayObjectPool<Cell>(1, 1, builder, 2.0f);
Cell receiver = null;'''
        teardown = 'free pool;\nfree builder;'
        exercise = 'Cell item = pool.get();\n' + protect(variant, [('', 'pool.release(item)')],
                    'item.value = i;\nbody(item.value);')
    elif case in NULLABLE_CASES:
        # Keep the owned reference separate from the nullable capture's lexical lifetime.
        setup = 'Cell owned = new Cell();\n{\nCell receiver = owned;\nArrayObjectPool<Cell> pool = null;'
        receiver = '// SELECT NULLABLE MEASUREMENT'
        teardown = '}\nfree owned;'
        if case == 'indirect':
            # Fixed process-owned lookup state keeps this load nullable in optimized
            # code. Its two startup allocations are shared by both fixtures.
            source = source.replace('    static long checksum;',
                '    static final Cell OWNED = new Cell();\n'
                '    static final Cell[] CHOICES = {OWNED, null};\n    static long checksum;')
            setup = 'Cell receiver = CHOICES[0];\nArrayObjectPool<Cell> pool = null;'
            receiver = 'receiver = CHOICES[mode == 0 ? 0 : 1];'
            teardown = ''
        actions = [('Cell savedReceiver = receiver;\nint savedArgument = capture(i, 1);',
                    'savedReceiver.observe(savedArgument)')]
        # This outer action proves remaining cleanup is attempted after the null failure.
        exercise = protect(variant, [('', 'record(2, 7, false)')] + actions,
                           'body(i);\nif (mode == 2) throw new Failure(0);', True)
        failure_check = '''if (mode == 0) errors++;
if (mode == 2) {
    if (!(failure instanceof Failure) || ((Failure) failure).id != 0
            || failure.getSecondaryExceptionCount() != 1
            || !(failure.getSecondaryException(0) instanceof NullPointerException)) errors++;
} else {
    if (!(failure instanceof NullPointerException) || failure.getSecondaryExceptionCount() != 0) errors++;
}'''
    else:
        shape, count = ('straight', 1) if case in ['call', 'initialization'] else (case.rsplit('-', 1)[0], int(case.rsplit('-', 1)[1]))
        value = 'i * 3 + step' if shape == 'transfers' else 'i'
        actions = [(f'int saved{n} = capture({value}, {n});',
                    f'record({n}, saved{n}, ' + ('fail' if shape == 'failing' else 'false') + ')')
                   for n in range(1, count + 1)]
        if case == 'initialization':
            actions = [('int saved = capture(i, 1);', 'Target.record(saved)')]
        if shape == 'transfers':
            body = '''body(i * 3 + step);
if ((i & 3) == 0) return;
if ((i & 3) == 1) continue;
if ((i & 3) == 2) break;'''
            split = count // 2
            inner = protect(variant, actions[split:], body)
            outer = protect(variant, actions[:split], '{\n' + textwrap.indent(inner, '    ') + '\n}')
            exercise = 'for (int step = 0; step < 3; step++) {\n' + textwrap.indent(outer, '    ') + '\n}'
        else:
            body = 'body(i);'
            if shape == 'failing':
                body += '\nif (fail && (i & 1) == 0) throw new Failure(0);'
                failure_check = f'''if (mode == 0 || !(failure instanceof Failure)) errors++;
int primary = (i & 1) == 0 ? 0 : {count};
int secondaries = (i & 1) == 0 ? {count} : {count - 1};
if (((Failure) failure).id != primary || failure.getSecondaryExceptionCount() != secondaries) errors++;
for (int j = 0; j < secondaries; j++) {{
    Throwable next = failure.getSecondaryException(j);
    if (!(next instanceof Failure) || ((Failure) next).id != secondaries - j) errors++;
}}'''
            exercise = protect(variant, actions, body, shape == 'failing')
    for name, body in [('EXERCISE', exercise), ('SETUP', setup), ('TEARDOWN', teardown),
                       ('RECEIVER', receiver), ('FAILURE CHECK', failure_check)]:
        source = region(source, name, body)
    if case == 'initialization':
        target = '''class Target {

    static int seed = initialize();

    static int initialize() {

        Main.starts++;
        return 713;
    }

    static void record(int value) throws Exception {

        Main.record(1, value + seed, false);
    }
}

'''
        source = source.replace('class Main {', target + 'class Main {')
        source = source.replace('    static long checksum;', '    static int starts;\n    static int coldBodies;\n    static long checksum;')
        source = source.replace('        bodies++;', '        if (starts == 0) coldBodies++;\n        bodies++;')
        source = source.replace('long firstLive = System.liveAllocationCount() - live;',
            'long firstLive = System.liveAllocationCount() - live;\n        if (starts != 1 || coldBodies != 1) errors++;')
    if case == 'nullable':
        # Branch at the borrow call rather than merging a nullable alias of an
        # owned object; the ordinary ownership analysis rejects that merged free.
        marker = '        // SELECT NULLABLE MEASUREMENT'
        start = source.index(marker)
        end = source.index('        }\n        free owned;', start)
        measurements = source[start + len(marker):end].strip()
        null_measurements = measurements.replace(', receiver, pool)', ', null, pool)')
        source = source[:start] + '        if (mode == 0) {\n' + textwrap.indent(measurements, '    ') \
            + '\n        } else {\n' + textwrap.indent(null_measurements, '    ') + '\n        }\n' + source[end:]
    if case == 'pool':
        # Keep checkout/release in the owning method, matching the existing pool
        # proof boundary; passing this graph through an unknown summary is unsafe.
        for count in ['1', 'mode == 0 ? 1000 : 8', 'count']:
            loop = ('{\n    checksum = 0; captures = 0; actions = 0; bodies = 0; failures = 0;\n'
                    + f'    for (int i = 0; i < ({count}); i++) {{\n'
                    + textwrap.indent(exercise, '        ') + '\n    }\n}')
            source = source.replace(f'batch({count}, ' + ('0' if count == '1' else 'mode')
                                    + ', receiver, pool);', loop)
    return source


def oracle(case, count, mode):
    values = dict.fromkeys(FIELDS[:-1], 0)
    def event(value):
        values['checksum'] = (values['checksum'] * 33 + value) & ((1 << 64) - 1)
    n = int(case.rsplit('-', 1)[1]) if '-' in case else 1
    for i in range(count):
        repeats = 3 if case.startswith('transfers-') and (i & 3) in [1, 3] else 1
        for step in range(repeats):
            value = i * 3 + step if case.startswith('transfers-') else i
            if case not in ['free', 'pool']:
                values['captures'] += n
            values['bodies'] += 1
            event(value + 2000)
            if case not in ['free', 'pool']:
                if case not in NULLABLE_CASES or mode == 0:
                    for action in range(n, 0, -1):
                        event(value * 31 + action + (713 if case == 'initialization' else 0))
                        values['actions'] += 1
                if case in NULLABLE_CASES:
                    event(7)
                    values['actions'] += 1
            if mode:
                values['failures'] += 1
                values['allocations'] += (1 + (mode == 2) if case in NULLABLE_CASES else n + (i % 2 == 0))
    if case == 'free':
        values['allocations'] = count
        values['first_allocations'] = 1
    if mode:
        values['live_delta'] = values['allocations']
    if values['checksum'] >= 1 << 63:
        values['checksum'] -= 1 << 64
    return values


def normalize_assembly(directory, function_names):
    assembly = (directory / 'program.s').read_text()
    normalized = {}
    for name in function_names:
        match = re.search(r'^"?' + re.escape(name) + r'"?:[^\n]*\n(.*?)(?=^\s*\.cfi_endproc)',
                          assembly, re.M | re.S)
        if not match:
            raise RuntimeError('Missing inspected application function: ' + name)
        body = re.sub(r';[^\n]*|//[^\n]*', '', match[1])
        body = re.sub(r'LBB\d+_', 'LBB_', body)
        normalized[name] = [line.strip() for line in body.splitlines() if line.strip()
                            and not line.lstrip().startswith(('.', 'Ltmp', 'Lloh', 'Lfunc_'))]
    (directory / 'normalized-assembly.json').write_text(json.dumps(normalized, indent=2) + '\n')


def inspect(directory, llvm):
    run(llvm / 'llvm-as', directory / 'program.ll', '-o', directory / 'program.bc')
    run(llvm / 'opt', '-passes=default<O3>', '-inline-threshold=1000', '-enable-partial-inlining',
        '-S', directory / 'program.bc', '-o', directory / 'optimized.ll')
    run(llvm / 'llc', '-O=3', '--relocation-model=pic', '-filetype=asm',
        directory / 'optimized.ll', '-o', directory / 'program.s')
    disassembly = run(llvm / 'llvm-objdump', '--disassemble', '--no-show-raw-insn', directory / 'program')
    (directory / 'disassembly.txt').write_text(disassembly)
    sizes = run(llvm / 'llvm-size', '-A', directory / 'program')
    (directory / 'sections.txt').write_text(sizes)
    sections = {}
    for name, size in re.findall(r'^(\S+)\s+(\d+)\s+\d+\s*$', sizes, re.M):
        sections[name] = sections.get(name, 0) + int(size)
    functions = {}
    for name, body in re.findall(r'^[0-9a-f]+ <([^\n]+)>:\n(.*?)(?=\n\n|\Z)', disassembly, re.M | re.S):
        if not (name in ['main', '_main'] or re.search(r'ironwood\.(Main|Cell|Builder|Failure)\.', name)
                or re.search(r'ironwood\.Target\.', name)
                or re.search(r'ironwood\.(destroy|rollback|throw\.ironwood\.Failure)', name)
                or re.search(r'ironwood\.initialize\.slow\.(Main|Cell|Builder|Failure|Target)$', name)):
            continue
        lines = [line.split(':', 1)[1].strip() for line in body.splitlines() if ':' in line]
        functions[name] = {'bytes': 4 * len(lines), 'instructions': lines}
    (directory / 'application.json').write_text(json.dumps(functions, indent=2) + '\n')
    normalize_assembly(directory, functions)
    return {'sections': sections, 'functions': {name: value['bytes'] for name, value in functions.items()},
            'application_bytes': sum(value['bytes'] for value in functions.values()),
            'compiler_sha256': hashlib.sha256(COMPILER.read_bytes()).hexdigest(),
            'binary_sha256': hashlib.sha256((directory / 'program').read_bytes()).hexdigest()}


def control(cases, rounds, count):
    """Same executable path and an identical-binary A/A label for timing attribution."""
    for case in cases:
        directory = OUT / 'controls' / case
        directory.mkdir(parents=True, exist_ok=True)
        sources = {name: OUT / case / name / 'program' for name in ['deferred', 'nested']}
        sources['identical'] = sources['deferred']
        samples = {name: [] for name in sources}
        expected = None
        for repeat in range(rounds):
            order = list(sources) if repeat % 2 == 0 else list(reversed(sources))
            for name in order:
                shutil.copyfile(sources[name], directory / 'program')
                (directory / 'program').chmod(0o755)
                result = list(map(int, run('defer-performance', count, 0,
                    executable=directory / 'program').splitlines()))
                if expected is None:
                    expected = result[:-1]
                assert result[:-1] == expected, (case, name, result, expected)
                samples[name].append(result[-1])
        report = {'case': case, 'iterations': count, 'ns': samples,
                  'binary_sha256': {name: hashlib.sha256(path.read_bytes()).hexdigest()
                                    for name, path in sources.items()},
                  'results': dict(zip(FIELDS[:-1], expected, strict=True)),
                  'median_ns': {name: statistics.median(ns) for name, ns in samples.items()}}
        (directory / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--llvm-home', default=os.environ.get('IRONWOOD_LLVM_HOME'))
    parser.add_argument('--case', action='append', choices=CASES)
    parser.add_argument('--rounds', type=int, default=8)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--control', action='store_true', help='Same-path and identical-binary timing control on built cases')
    parser.add_argument('--iterations', type=int, help='Override successful iteration count for focused investigations')
    options = parser.parse_args()
    if not options.llvm_home or options.rounds < 2 or options.rounds % 2:
        parser.error('LLVM 23 home and an even number of at least two alternating rounds are required')
    if options.iterations is not None and options.iterations <= 0:
        parser.error('Iteration count must be positive')
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('This evidence driver currently inspects macOS ARM64 Mach-O code only')
    llvm = Path(options.llvm_home) / 'bin'
    version = run(llvm / 'llvm-config', '--version').strip()
    if not version.startswith('23.'):
        parser.error(f'Expected LLVM 23, got {version}')
    OUT.mkdir(parents=True, exist_ok=True)
    if options.control:
        control(options.case or ['free'], options.rounds, options.iterations or 32000000)
        return
    for case in options.case or CASES:
        variants = ['deferred', 'nested', 'flat'] if '-' in case else ['deferred', 'nested']
        report = {'case': case, 'host': platform.platform(), 'llvm': version,
                  'java': run('java', '-version').strip(),
                  'revision': run('git', 'rev-parse', 'HEAD').strip(), 'variants': {}, 'runs': {}}
        for variant in variants:
            directory = OUT / case / variant
            directory.mkdir(parents=True, exist_ok=True)
            source = fixture(case, variant)
            if options.skip_build:
                if source != (directory / 'Main.iron').read_text():
                    raise RuntimeError('Refusing to measure a stale generated fixture')
                report['variants'][variant] = json.loads((directory / 'build.json').read_text())
                for name, path in [('compiler', COMPILER), ('binary', directory / 'program')]:
                    if report['variants'][variant][name + '_sha256'] != hashlib.sha256(path.read_bytes()).hexdigest():
                        raise RuntimeError('Refusing stale or modified ' + name)
                normalize_assembly(directory, report['variants'][variant]['functions'])
            else:
                (directory / 'Main.iron').write_text(source)
                print(f'BUILD {case} {variant}', flush=True)
                log = run(ROOT / 'bin/ironwoodc', '--unfreed=error', directory / 'Main.iron', '-d', directory / 'classes')
                log += run(ROOT / 'bin/ironwoodc', '--link', '--unfreed=error', '-cp', directory / 'classes',
                           '--main-class', 'Main', '-O3', '--llvm-home', options.llvm_home,
                           '--emit-llvm', directory / 'program.ll', '-o', directory / 'program')
                (directory / 'build.log').write_text(log)
                report['variants'][variant] = inspect(directory, llvm)
                (directory / 'build.json').write_text(json.dumps(report['variants'][variant], indent=2) + '\n')
            # Small independent oracle checks order, captures, transfers and failure IDs/order.
            for mode in ([0, 1, 2] if case in NULLABLE_CASES else [0, 1] if case.startswith('failing-') else [0]):
                measured = dict(zip(FIELDS, map(int, run('defer-performance', 8, mode,
                    executable=directory / 'program').splitlines()), strict=True))
                assert {k: measured[k] for k in FIELDS[:-1]} == oracle(case, 8, mode), (case, variant, mode, measured, oracle(case, 8, mode))
        for mode in ([0, 1, 2] if case in NULLABLE_CASES else [0, 1] if case.startswith('failing-') else [0]):
            count = 512 if mode else options.iterations or 32000000
            samples = {variant: [] for variant in variants}
            expected = None
            for repeat in range(options.rounds):
                order = variants if repeat % 2 == 0 else list(reversed(variants))
                for variant in order:
                    # argv[0] is also a managed process argument. Keep it identical
                    # so variant directory names cannot perturb the startup heap.
                    result = list(map(int, run('defer-performance', count, mode,
                        executable=OUT / case / variant / 'program').splitlines()))
                    if expected is None:
                        expected = result[:-1]
                    assert result[:-1] == expected, (case, variant, result, expected)
                    samples[variant].append(result[-1])
            report['runs'][str(mode)] = {'iterations': count, 'results': dict(zip(FIELDS[:-1], expected, strict=True)),
                'ns': samples, 'median_ns': {name: statistics.median(ns) for name, ns in samples.items()}}
            # Check full-run counts independently as well as matching paired output.
            n = int(case.rsplit('-', 1)[1]) if '-' in case else 1
            blocks = count // 4 * 8 + [0, 1, 4, 5][count % 4] if case.startswith('transfers-') else count
            allocation_count = count if case == 'free' else (count * (1 + (mode == 2)) if case in NULLABLE_CASES
                else count * n + (count + 1) // 2) if mode else 0
            counts = {'captures': 0 if case in ['free', 'pool'] else blocks * n,
                      'bodies': blocks, 'failures': count if mode else 0,
                      'actions': 0 if case in ['free', 'pool'] else count * (1 if mode else 2)
                      if case in NULLABLE_CASES else blocks * n,
                      'allocations': allocation_count, 'live_delta': allocation_count if mode else 0,
                      'first_allocations': 1 if case == 'free' else 0, 'first_live_delta': 0}
            actual = report['runs'][str(mode)]['results']
            assert all(actual[key] == value for key, value in counts.items()), (case, mode, actual, counts)
            print(f'PASS {case} mode={mode}: ' + str(report['runs'][str(mode)]['median_ns']), flush=True)
        report['comparisons'] = {}
        deferred = report['variants']['deferred']
        deferred_assembly = json.loads((OUT / case / 'deferred/normalized-assembly.json').read_text())
        for variant in variants[1:]:
            baseline = report['variants'][variant]
            baseline_assembly = json.loads((OUT / case / variant / 'normalized-assembly.json').read_text())
            report['comparisons'][variant] = {
                'text_delta': deferred['sections']['__text'] - baseline['sections']['__text'],
                'text_ratio': deferred['sections']['__text'] / baseline['sections']['__text'],
                'application_delta': deferred['application_bytes'] - baseline['application_bytes'],
                'application_ratio': deferred['application_bytes'] / baseline['application_bytes'],
                'identical_assembly': deferred_assembly == baseline_assembly,
                'median_ratios': {mode: results['median_ns']['deferred'] / results['median_ns'][variant]
                                  for mode, results in report['runs'].items()}}
        (OUT / case / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
