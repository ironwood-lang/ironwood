#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Build a source-only, immutable-input J0 installation, outside measured runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PROFILE = ['-Xms256m', '-Xmx4096m', '-Xss8m', '-XX:+UseG1GC']


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args, **kwargs):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--llvm', type=Path, required=True)
    parser.add_argument('--revision', default='6bde84df320e9dbc32977a2b665d214ac09bc2d4')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/self-hosting-m0/J0')
    parser.add_argument('--report', type=Path, default=ROOT / 'docs/self-hosting/m0/identity.json')
    args = parser.parse_args()
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    env['JAVA_HOME'] = str(args.jdk.resolve())
    env['IRONWOOD_LLVM_HOME'] = str(args.llvm.resolve())
    if args.output.exists():
        raise SystemExit('output already exists; choose an empty installation path')
    args.output.mkdir(parents=True)
    paths = run(['git', 'ls-tree', '-r', '--name-only', args.revision], cwd=ROOT).splitlines()
    selected = [p for p in paths if p.startswith(('compiler/src/main/java/', 'stdlib/src/',
               'runtime/', 'bin/', 'conf/', 'scripts/')) or p == 'VERSION']
    inputs = {}
    for name in selected:
        data = subprocess.check_output(['git', 'show', f'{args.revision}:{name}'], cwd=ROOT)
        target = args.output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        if name.startswith(('bin/', 'scripts/')) and name.endswith('.sh') or name == 'bin/ironwoodc':
            target.chmod(0o755)
        inputs[name] = hashlib.sha256(data).hexdigest()
    classes = args.output / 'classes'
    classes.mkdir()
    sources = sorted((args.output / 'compiler/src/main/java').rglob('*.java'))
    source_list = args.output / 'sources.txt'
    source_list.write_text(''.join(str(p) + '\n' for p in sources))
    javac = [str(args.jdk / 'bin/javac'), '--release', '21', '-encoding', 'UTF-8',
             '-Xlint:all', '-Werror', '-d', str(classes), '@' + str(source_list)]
    build_log = run(javac, env=env)
    (classes / 'ironwood/compiler/VERSION').write_bytes((args.output / 'VERSION').read_bytes())
    jar = args.output / 'lib/ironwoodc.jar'
    jar.parent.mkdir()
    jar_command = [str(args.jdk / 'bin/jar'), '--create', '--date=2026-10-04T00:00:00Z',
                   '--file', str(jar), '--main-class', 'ironwood.compiler.Main', '-C', str(classes), '.']
    build_log += run(jar_command, env=env)
    (args.output / 'conf/jvm.options').write_text('# SPDX-License-Identifier: MIT OR Apache-2.0\n' +
                                               '\n'.join(PROFILE) + '\n')
    version = run([str(args.output / 'bin/ironwoodc'), '--version'], env=env, cwd=args.output)
    settings = run([str(args.jdk / 'bin/java'), *PROFILE, '-XshowSettings:all',
                    '-XX:+PrintFlagsFinal', '-version'], env=env)
    identity = {'schema': 1, 'revision': args.revision, 'input_sha256': inputs,
                'seed_jar_sha256': digest(jar), 'profile': PROFILE,
                'build_commands': [javac, jar_command], 'launcher': str(args.output / 'bin/ironwoodc'),
                'jdk': str(args.jdk), 'jdk_version': run([str(args.jdk / 'bin/java'), '-version'], env=env),
                'jdk_sha256': {n: digest(args.jdk / n) for n in ('release', 'bin/java', 'bin/javac', 'lib/modules')},
                'llvm': str(args.llvm.resolve()), 'compiler_version': version,
                'llvm_sha256': {n: digest(args.llvm / 'bin' / n) for n in
                              ('llvm-as', 'opt', 'llc', 'clang', 'llvm-config', 'llvm-objcopy')},
                'environment': {k: env.get(k) for k in ('JAVA_HOME', 'IRONWOOD_LLVM_HOME', 'PATH',
                    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'TMPDIR', 'LANG', 'LC_ALL')},
                'platform': run(['uname', '-a']),
                'hardware': run(['sysctl', '-n', 'hw.memsize', 'hw.physicalcpu', 'hw.logicalcpu', 'machdep.cpu.brand_string']),
                'limits': run(['/bin/zsh', '-c', 'ulimit -a'])}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(identity, indent=2, sort_keys=True) + '\n')
    args.report.with_name('jvm-effective.txt').write_text(settings)
    args.report.with_name('seed-build.log').write_text(build_log or 'javac and jar exited 0, no diagnostics\n')
    print(version, end='')
    print('seed SHA-256:', identity['seed_jar_sha256'])


if __name__ == '__main__':
    main()
