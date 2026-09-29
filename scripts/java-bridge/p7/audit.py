#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Inspect exact combined P7f payloads on the Mac, including the Java 24 refusal."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('p7_qualify', HERE / 'qualify.py')
p7 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p7)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate', required=True, type=Path)
parser.add_argument('--java24', required=True, type=Path, help='Pinned Temurin 24 installation Home')
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
candidate = args.candidate.resolve(); out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
java = Path(os.environ['JAVA_HOME'])

def run(name, command, expected=None):
    command = list(map(str, command))
    (out / (name + '.command.json')).write_text(json.dumps(command, indent=2))
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=120)
    (out / (name + '.log')).write_text(result.stdout)
    if result.returncode or expected is not None and result.stdout != expected: raise RuntimeError(name + ': ' + result.stdout)
    return result.stdout

inventory = json.loads((candidate / 'artifacts.json').read_text())
records = []
for name in p7.CASES:
    jar = candidate / (name + '.jar')
    if p7.digest(jar) != inventory[name]['sha256']: raise RuntimeError('candidate identity changed')
    info = p7.metadata(jar)
    with zipfile.ZipFile(str(jar)) as archive:
        for entry in archive.namelist():
            if entry.endswith('.class'):
                data = archive.read(entry)
                if data[6:8] != b'\x00\x41' or b'java/lang/foreign' in data: raise RuntimeError('Java 21/FFM boundary: ' + entry)
        for target in ('macos-arm64', 'linux-arm64', 'linux-x86_64'):
            prefix = 'target.' + target + '.'
            resource = info[prefix + 'resource']
            payload = out / (name + '-' + target + Path(resource).suffix)
            payload.write_bytes(archive.read(resource))
            if p7.digest(payload) != info[prefix + 'sha256']: raise RuntimeError('image hash mismatch')
            if target == 'macos-arm64':
                run(name + '-' + target + '-signature', ['/usr/bin/codesign', '--verify', '--strict', payload])
                run(name + '-' + target + '-dependencies', ['/usr/bin/otool', '-L', payload])
                run(name + '-' + target + '-deployment', ['/usr/bin/otool', '-l', payload])
            else:
                text = run(name + '-' + target + '-elf', ['llvm-readelf', '--file-header', '--dynamic', '--version-info', payload])
                if 'BIND_NOW' not in text and not re.search(r'FLAGS.*NOW', text): raise RuntimeError('lazy image binding')
                paths = re.findall(r'Library (?:rpath|runpath): \[([^\]]*)\]', text)
                if not paths or any(not p.startswith('$ORIGIN') for value in paths for p in value.split(':')):
                    raise RuntimeError('non-relative runtime dependencies')
                for version in re.findall(r'Name: GLIBC_([0-9.]+)', text.split('Version needs section', 1)[-1]):
                    if tuple(map(int, version.split('.'))) > (2, 17): raise RuntimeError('glibc baseline exceeded')
            run(name + '-' + target + '-disassembly', ['llvm-objdump', '-d', '--no-show-raw-insn', payload])
            records.append({'artifact': name, 'target': target, 'sha256': p7.digest(payload), 'native_build': info[prefix + 'build']})

jars = [candidate / (name + '.jar') for name in p7.CASES] + [candidate / 'ironwood-bridge-values.jar']
cp = os.pathsep.join(map(str, jars))
run('javac-refusal', [java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-cp', cp,
                     '-d', out / 'classes', HERE / 'VersionRefusal.java'])
version = run('java24-version', [args.java24 / 'bin/java', '-version'])
if 'Temurin-24.0.2+12' not in version: raise RuntimeError('unexpected Java 24 pin')
for name in p7.CASES:
    for checked in (False, True):
        with tempfile.TemporaryDirectory(prefix='p7-java24-') as scratch:
            run('refusal-' + name + '-' + str(checked), [args.java24 / 'bin/java', *(['-Xcheck:jni'] if checked else []),
                '-Djava.io.tmpdir=' + scratch, '-cp', cp + os.pathsep + str(out / 'classes'), 'VersionRefusal', name],
                'java24-refused:' + name + '\n')
            if any(Path(scratch).rglob('*')): raise RuntimeError('unsupported JVM extracted native files')
(out / 'payloads.json').write_text(json.dumps(records, indent=2))
(out / 'files.json').write_text(json.dumps({str(p.relative_to(out)): p7.digest(p) for p in out.rglob('*') if p.is_file()}, indent=2))
(out / 'exit.txt').write_text('0\n')
print('P7f payload audit and Java 24 refusals passed: ' + str(out))
