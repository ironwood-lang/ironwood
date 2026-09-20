#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused one-call checkpoint cost check, not Milestone 2 performance acceptance."""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'integration-tests/target/defer-call-cost'


def run(*args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    if result.returncode:
        raise RuntimeError(f'{args}:\n{result.stdout}')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--llvm-home', default=os.environ.get('IRONWOOD_LLVM_HOME'))
    options = parser.parse_args()
    if not options.llvm_home:
        parser.error('pass --llvm-home or set IRONWOOD_LLVM_HOME to LLVM 23')
    llvm = Path(options.llvm_home) / 'bin'
    version = run(llvm / 'llvm-config', '--version').strip()
    if not version.startswith('23.'):
        raise RuntimeError(f'Expected LLVM 23, got {version}')
    OUT.mkdir(parents=True, exist_ok=True)
    template = (ROOT / 'integration-tests/cases/defer_call_cost.iron').read_text()
    baseline = template.replace('defer record(first, second);\n            checksum ^= i;',
                                'try { checksum ^= i; } finally { record(first, second); }')
    if baseline == template:
        raise RuntimeError('Paired source replacement did not match')
    report = {'llvm': version, 'host': platform.platform(), 'iterations': 8000000,
              'scope': 'one void call with two saved primitive operands per iteration', 'variants': {}}
    for name, source in [('deferred', template), ('finally', baseline)]:
        directory = OUT / name
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
            result = list(map(int, run(OUT / name / 'program').splitlines()))
            if len(result) != 4 or result[1:3] != [0, 0]:
                raise RuntimeError(f'{name}: invalid result {result}')
            checksums.add(result[0])
            variant = report['variants'][name]
            variant['ns'].append(result[3])
            variant['allocations'].append(result[1])
            variant['live_delta'].append(result[2])
    if len(checksums) != 1:
        raise RuntimeError(f'Checksum mismatch: {checksums}')
    report['checksum'] = checksums.pop()
    # Compare the optimized assembly of the whole application entry point, including
    # the inlined work loop. Ignore comments/local-label spelling and metadata only.
    functions = {}
    for name in ['deferred', 'finally']:
        assembly = (OUT / name / 'program.s').read_text()
        match = re.search(r'^(_?main):\s*(.*?)(?=^\s*\.cfi_endproc)', assembly, re.M | re.S)
        if not match:
            raise RuntimeError('Cannot locate Main.main optimized assembly')
        body = re.sub(r';[^\n]*|//[^\n]*', '', match[2])
        body = re.sub(r'LBB\d+_', 'LBB_', body)
        lines = [line.strip() for line in body.splitlines() if line.strip() and not line.lstrip().startswith('.')]
        functions[name] = lines
        (OUT / name / 'main-instructions.txt').write_text('\n'.join(lines) + '\n')
        report['variants'][name]['main_assembly_lines'] = len(lines)
        report['variants'][name]['median_ns'] = statistics.median(report['variants'][name]['ns'])
    report['identical_main_assembly'] = functions['deferred'] == functions['finally']
    report['median_ratio'] = report['variants']['deferred']['median_ns'] / report['variants']['finally']['median_ns']
    (OUT / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if not report['identical_main_assembly']:
        raise RuntimeError('Review differing optimized application instructions before accepting this checkpoint')


if __name__ == '__main__':
    main()
