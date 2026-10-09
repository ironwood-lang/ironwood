#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Compiler-neutral process driver and order-preserving structural comparison."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

JSON_FILES = ('tokens.json', 'lex-diagnostics.json', 'ast.json', 'parse-diagnostics.json',
              'diagnostics.json', 'typed-ir.json', 'compile-diagnostics.json',
              'final-typed-ir.json', 'status.json')


def difference(left, right, path='$'):
    if type(left) is not type(right):
        return path + ': type differs'
    if isinstance(left, dict):
        if left.keys() != right.keys():
            return path + ': fields differ'
        for key in left:
            result = difference(left[key], right[key], path + '.' + key)
            if result:
                return result
    elif isinstance(left, list):
        if len(left) != len(right):
            return path + ': length differs'
        for index, (a, b) in enumerate(zip(left, right)):
            result = difference(a, b, path + '[' + str(index) + ']')
            if result:
                return result
    elif left != right:
        return path + ': value differs'
    return None


def compare(left, right):
    mismatches = []
    for name in JSON_FILES:
        if not (left / name).is_file() or not (right / name).is_file():
            mismatches.append(name + ': missing output')
            continue
        result = difference(json.loads((left / name).read_text()), json.loads((right / name).read_text()))
        if result:
            mismatches.append(name + ': ' + result)
    a, b = left / 'output.ll', right / 'output.ll'
    if a.exists() != b.exists() or (a.exists() and a.read_bytes() != b.read_bytes()):
        mismatches.append('output.ll: bytes or presence differ')
    for directory in (left, right):
        status = directory / 'status.json'
        if status.is_file():
            values = json.loads(status.read_text())
            if values not in ([True, True], [False, False]):
                mismatches.append(str(directory) + ': invalid analysis/compile status pair')
            if values == [True, True] and not (directory / 'output.ll').is_file():
                mismatches.append(str(directory) + ': successful compile lacks LLVM')
    return mismatches


def run(config_path, source, output, explain):
    config = json.loads(config_path.read_text())
    if output.exists():
        raise ValueError('capture destination already exists')
    command = [part.replace('{input}', str(source.resolve())).replace('{output}', str(output.resolve()))
               .replace('{explain}', explain) for part in config['argv']]
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(name, None)
    env.update(config.get('environment', {}))
    output.mkdir(parents=True)
    completed = subprocess.run(command, cwd=config.get('cwd'), env=env, capture_output=True)
    (output / 'process.stdout.txt').write_bytes(completed.stdout)
    (output / 'process.stderr.txt').write_bytes(completed.stderr)
    record = {'argv': command, 'returncode': completed.returncode,
              'input_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'explain': explain, 'environment': config.get('environment', {})}
    (output / 'process.json').write_text(json.dumps(record, indent=2) + '\n')
    if completed.returncode:
        raise ValueError('producer failed, see retained process logs')
    errors = compare(output, output)
    if errors:
        raise ValueError('\n'.join(errors))


def selftest(positive, negative, scratch):
    if scratch.exists():
        raise ValueError('selftest destination already exists')
    results = []
    for name, source, artifact in [('diagnostic', negative, 'diagnostics.json'),
                                   ('ir-edge', positive, 'final-typed-ir.json'),
                                   ('llvm', positive, 'output.ll')]:
        target = scratch / name
        shutil.copytree(source, target)
        if name == 'llvm':
            with (target / artifact).open('ab') as stream:
                stream.write(b'; deliberate generated-output mismatch\n')
        else:
            value = json.loads((target / artifact).read_text())
            if name == 'diagnostic':
                assert value and value[0]['node'] == 'Diagnostic'
                value[0]['message'] += ' deliberate mismatch'
            else:
                def remove_edge(node):
                    if isinstance(node, dict):
                        if node.get('node') == 'IrBranch':
                            del node['falseTarget']
                            return True
                        return any(remove_edge(child) for child in node.values())
                    if isinstance(node, list):
                        return any(remove_edge(child) for child in node)
                    return False
                assert remove_edge(value), 'fixture lacks a real IR branch edge'
            (target / artifact).write_text(json.dumps(value, ensure_ascii=True) + '\n')
        failures = compare(source, target)
        assert failures and all(item.startswith(artifact + ':') for item in failures), failures
        results.append({'mutation': name, 'detected': failures})
    (scratch / 'qualification.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    capture = commands.add_parser('run')
    capture.add_argument('producer', type=Path)
    capture.add_argument('source', type=Path)
    capture.add_argument('output', type=Path)
    capture.add_argument('--explain', choices=('explain-on', 'explain-off'), default='explain-on')
    comparison = commands.add_parser('compare')
    comparison.add_argument('left', type=Path)
    comparison.add_argument('right', type=Path)
    test = commands.add_parser('selftest')
    test.add_argument('positive', type=Path)
    test.add_argument('negative', type=Path)
    test.add_argument('scratch', type=Path)
    args = parser.parse_args()
    if args.command == 'run':
        run(args.producer, args.source, args.output, args.explain)
    elif args.command == 'compare':
        failures = compare(args.left, args.right)
        print('\n'.join(failures) if failures else 'MATCH: tokens, AST, diagnostics, typed IR and LLVM')
        raise SystemExit(bool(failures))
    else:
        selftest(args.positive, args.negative, args.scratch)


if __name__ == '__main__':
    main()
