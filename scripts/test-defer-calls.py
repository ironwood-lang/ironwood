#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused single-action lowering checks, not Milestone 2 performance acceptance."""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    if result.returncode:
        raise RuntimeError(f'{args}:\n{result.stdout}')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--llvm-home', default=os.environ.get('IRONWOOD_LLVM_HOME'))
    parser.add_argument('--kind', choices=['call', 'free'], default='call')
    options = parser.parse_args()
    if not options.llvm_home:
        parser.error('pass --llvm-home or set IRONWOOD_LLVM_HOME to LLVM 23')
    llvm = Path(options.llvm_home) / 'bin'
    version = run(llvm / 'llvm-config', '--version').strip()
    if not version.startswith('23.'):
        raise RuntimeError(f'Expected LLVM 23, got {version}')
    output = ROOT / f'integration-tests/target/defer-{options.kind}-cost'
    output.mkdir(parents=True, exist_ok=True)
    template = (ROOT / f'integration-tests/cases/defer_{options.kind}_cost.iron').read_text()
    if options.kind == 'call':
        baseline = template.replace('defer record(first, second);\n            checksum ^= i;',
                                    'try { checksum ^= i; } finally { record(first, second); }')
        expected_allocations = 0
        scope = 'one void call with two saved primitive operands per iteration'
    else:
        baseline = template.replace('defer free value;\n            checksum += value[0] + i;',
                                    'try { checksum += value[0] + i; } finally { free value; }')
        expected_allocations = 8000000
        scope = 'one allocation and one free per iteration'
    if baseline == template:
        raise RuntimeError('Paired source replacement did not match')
    report = {'llvm': version, 'host': platform.platform(), 'iterations': 8000000,
              'scope': scope, 'variants': {}}
    for name, source in [('deferred', template), ('finally', baseline)]:
        directory = output / name
        directory.mkdir(exist_ok=True)
        (directory / 'Main.iron').write_text(source)
        log = run(ROOT / 'bin/ironwoodc', directory / 'Main.iron', '-d', directory / 'classes')
        log += run(ROOT / 'bin/ironwoodc', '--link', '-cp', directory / 'classes',
                  '--main-class', 'Main', '-O3',
                  '--llvm-home', options.llvm_home, '--emit-llvm', directory / 'program.ll',
                  '-o', directory / 'program')
        (directory / 'build.log').write_text(log)
        run(llvm / 'opt', '-passes=default<O3>', '-S', directory / 'program.ll',
            '-o', directory / 'optimized.ll')
        run(llvm / 'llc', '-O3', '-filetype=asm', directory / 'optimized.ll',
            '-o', directory / 'program.s')
        disassembly = run(llvm / 'llvm-objdump', '--disassemble', '--no-show-raw-insn', directory / 'program')
        (directory / 'disassembly.txt').write_text(disassembly)
        report['variants'][name] = {'ns': [], 'allocations': [], 'live_delta': []}
    checksums = set()
    for iteration in range(8):
        order = ['deferred', 'finally'] if iteration % 2 == 0 else ['finally', 'deferred']
        for name in order:
            result = list(map(int, run(output / name / 'program').splitlines()))
            if len(result) != 4 or result[1:3] != [expected_allocations, 0]:
                raise RuntimeError(f'{name}: invalid result {result}')
            checksums.add(result[0])
            variant = report['variants'][name]
            variant['ns'].append(result[3])
            variant['allocations'].append(result[1])
            variant['live_delta'].append(result[2])
    if len(checksums) != 1:
        raise RuntimeError(f'Checksum mismatch: {checksums}')
    report['checksum'] = checksums.pop()
    # Compare reoptimized assembly and the linked entry point separately: final-link
    # optimization may inline a work function retained in the standalone opt output.
    functions = {}
    work_functions = {}
    linked_functions = {}
    for name in ['deferred', 'finally']:
        assembly = (output / name / 'program.s').read_text()
        match = re.search(r'^(_?main):\s*(.*?)(?=^\s*\.cfi_endproc)', assembly, re.M | re.S)
        if not match:
            raise RuntimeError('Cannot locate Main.main optimized assembly')
        body = re.sub(r';[^\n]*|//[^\n]*', '', match[2])
        body = re.sub(r'LBB\d+_', 'LBB_', body)
        lines = [line.strip() for line in body.splitlines() if line.strip() and not line.lstrip().startswith('.')]
        functions[name] = lines
        (output / name / 'main-instructions.txt').write_text('\n'.join(lines) + '\n')
        report['variants'][name]['main_assembly_lines'] = len(lines)
        report['variants'][name]['median_ns'] = statistics.median(report['variants'][name]['ns'])
        if options.kind == 'free':
            work = re.search(r'^_?ironwood\.Main\.work:\s*(.*?)(?=^\s*\.cfi_endproc)', assembly, re.M | re.S)
            if not work:
                raise RuntimeError('Cannot locate allocation/free work loop')
            body = re.sub(r';[^\n]*|//[^\n]*', '', work[1])
            body = re.sub(r'LBB\d+_', 'LBB_', body)
            lines = [line.strip() for line in body.splitlines() if line.strip() and not line.lstrip().startswith('.')]
            work_functions[name] = lines
            (output / name / 'work-instructions.txt').write_text('\n'.join(lines) + '\n')
            report['variants'][name]['work_assembly_lines'] = len(lines)
        disassembly = (output / name / 'disassembly.txt').read_text()
        linked = re.search(r'^[0-9a-f]+ <_?main>:\n(.*?)(?=\n\n|\Z)', disassembly, re.M | re.S)
        if not linked:
            raise RuntimeError('Cannot locate linked application entry point')
        lines = [line.split(':', 1)[1].strip() for line in linked[1].splitlines() if line.strip()]
        linked_functions[name] = lines
        (output / name / 'linked-main-instructions.txt').write_text('\n'.join(lines) + '\n')
        report['variants'][name]['linked_main_instructions'] = len(lines)
    report['identical_main_assembly'] = functions['deferred'] == functions['finally']
    report['identical_linked_main'] = linked_functions['deferred'] == linked_functions['finally']
    if work_functions:
        report['identical_work_assembly'] = work_functions['deferred'] == work_functions['finally']
    report['median_ratio'] = report['variants']['deferred']['median_ns'] / report['variants']['finally']['median_ns']
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if (not report['identical_main_assembly'] or not report['identical_linked_main']
            or not report.get('identical_work_assembly', True)):
        raise RuntimeError('Review differing optimized application instructions before accepting this checkpoint')


if __name__ == '__main__':
    main()
