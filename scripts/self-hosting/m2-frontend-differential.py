#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""M2.1 frontend differential: native FrontendCapture against J0.

Modes:
  corpus CORPUS_ROOT NATIVE OUTPUT
      CORPUS_ROOT holds the extracted canonical-corpus.tar.gz original/ and
      loop-cycle-reference.tar.gz loops-original/ directories. Every one of the
      36 frontend workloads is compared byte for byte with its frozen J0
      explain-off repeat-0 tokens, lex-diagnostics, ast and parse-diagnostics.
  units LISTFILE NATIVE OUTPUT
      LISTFILE holds repository-relative .iron paths. J0 FrontendReference and
      the native adapter each process the whole list in one process; every
      output file, including an exception's failure.txt, must be equal.
  mutations SEEDLIST COUNT NATIVE OUTPUT
      Writes COUNT seeded mutations (seed 20261005) of the listed sources to
      OUTPUT/inputs and compares them as in units mode.
J0 is the frozen original seed jar (M2_J0_JAR overrides its location); its
identity is checked before use.
"""
import hashlib
import json
import os
import random
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK = '/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home'
# The frozen J0 installation is ignored local state; M2_J0_JAR relocates it.
J0 = Path(os.environ.get('M2_J0_JAR', ROOT / 'target/self-hosting-m0/J0-final2/lib/ironwoodc.jar'))
J0_SHA = '2a8b10ab5575f84d3660b3824a4b80cba7f6f9110023e7e30db3b41103893ad7'
PROFILE = ['-Xms256m', '-Xmx4096m', '-Xss8m', '-XX:+UseG1GC']
FILES = ['tokens.json', 'lex-diagnostics.json', 'ast.json', 'parse-diagnostics.json']
SNIPPETS = ['"', "'", '\\', '"""', 'r"""', '/*', '*/', '//', '/**', '{', '}', '(', ')', '[', ']', '<', '>', '>>',
            '>>>=', '>>=', '<<=', '@', '#', '`', '٣', '\U0001F600', '\r', '\r\n', '\t', '\f', ' ',
            ' ', '0x', '0b2', '1e', '1_', '.5f', '0x_1L', '1__0', "'\\u12'", "'ab'", '"\\q"', '\\u0041',
            'case', 'default ->', 'default:', 'switch (x) {', 'new int[3][2]', 'instanceof final', '::', '...',
            'yield', 'free', 'defer', 'destructor', 'try (', 'catch', 'finally', '@Override', '@SuppressUnfreed',
            '@Deprecated(', 'enum', 'interface', 'class', 'package', 'import static', ';', ',', '.', '?', ':',
            '->', 'this', 'super', '١٢', 'x٣', '$', '_']


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def environment():
    return {k: v for k, v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}


def tooling(out):
    classes = out / 'reference-classes'
    if sha(J0) != J0_SHA:
        raise SystemExit('J0 identity mismatch')
    classes.mkdir(parents=True)
    subprocess.run([JDK + '/bin/javac', '--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-cp', str(J0),
                    '-d', str(classes), str(ROOT / 'scripts/self-hosting/ReferenceCapture.java'),
                    str(ROOT / 'scripts/self-hosting/FrontendReference.java')], check=True)
    return classes


def manifest(paths, out):
    lines = []
    for index, path in enumerate(paths):
        full = ROOT / path if not Path(path).is_absolute() else Path(path)
        lines.append('%s\t%s\t%s' % (full, path, sha(full)))
        (out / 'native' / str(index)).mkdir(parents=True)
    (out / 'manifest.txt').write_text('\n'.join(lines) + '\n')
    return out / 'manifest.txt'


def compare_units(paths, native, out):
    shutil.rmtree(out, ignore_errors=True)
    (out / 'java').mkdir(parents=True)
    manifest_file = manifest(paths, out)
    java = subprocess.run([JDK + '/bin/java'] + PROFILE + ['-cp', '%s:%s' % (tooling(out), J0),
                          'ironwood.compiler.FrontendReference', str(manifest_file), str(out / 'java')],
                          capture_output=True, text=True, env=environment())
    native_run = subprocess.run([native, str(manifest_file), str(out / 'native')], capture_output=True, text=True)
    rows, mismatches = [], 0
    for index, path in enumerate(paths):
        for file in FILES + ['failure.txt']:
            expected_path = out / 'java' / str(index) / file
            actual_path = out / 'native' / str(index) / file
            expected = sha(expected_path) if expected_path.exists() else None
            actual = sha(actual_path) if actual_path.exists() else None
            rows.append({'unit': path, 'file': file, 'j0_sha256': expected, 'native_sha256': actual,
                         'equal': expected == actual})
            mismatches += expected != actual
    malformed = sum(1 for index in range(len(paths))
                    if json.loads((out / 'java' / str(index) / 'lex-diagnostics.json').read_text())
                    or not (out / 'java' / str(index) / 'parse-diagnostics.json').exists()
                    or json.loads((out / 'java' / str(index) / 'parse-diagnostics.json').read_text()))
    summary = {'units': len(paths), 'files': len(rows), 'mismatches': mismatches, 'units_with_diagnostics': malformed,
               'java_exit': java.returncode, 'java_stdout': java.stdout.strip(), 'java_stderr': java.stderr.strip(),
               'native_exit': native_run.returncode, 'native_stdout': native_run.stdout.strip(),
               'native_stderr': native_run.stderr.strip(), 'j0_sha256': J0_SHA}
    (out / 'comparison.json').write_text(json.dumps({'summary': summary, 'rows': rows}, indent=1) + '\n')
    shutil.rmtree(out / 'reference-classes')
    print(json.dumps({k: v for k, v in summary.items() if k not in ('java_stderr', 'native_stderr')}))
    return 1 if mismatches or java.returncode or native_run.returncode else 0


def corpus(corpus_root, native, out):
    references = {}
    for archive in (Path(corpus_root) / 'original', Path(corpus_root) / 'loops-original'):
        for run in json.loads((archive / 'captures.json').read_text())['runs']:
            if run['repeat'] == 0 and not run['explain'] and run['workload'] not in references:
                references[run['workload']] = (archive, run)
    names = sorted(references)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    manifest_file = manifest([str(references[name][0] / 'sources' / (name + '.iron')) for name in names], out)
    # Logical names are the bare file names J0's ReferenceCapture used.
    lines = manifest_file.read_text().splitlines()
    manifest_file.write_text('\n'.join('%s\t%s\t%s' % (line.split('\t')[0], name + '.iron', line.split('\t')[2])
                                       for line, name in zip(lines, names)) + '\n')
    native_run = subprocess.run([native, str(manifest_file), str(out / 'native')], capture_output=True, text=True)
    rows, mismatches = [], 0
    for index, name in enumerate(names):
        archive, run = references[name]
        for file in FILES:
            actual_path = out / 'native' / str(index) / file
            actual = sha(actual_path) if actual_path.exists() else None
            rows.append({'workload': name, 'file': file, 'archive': archive.name, 'j0_run': run['label'],
                         'j0_sha256': run['artifacts'][file]['sha256'], 'native_sha256': actual,
                         'equal': actual == run['artifacts'][file]['sha256']})
            mismatches += actual != run['artifacts'][file]['sha256']
    summary = {'workloads': len(names), 'files': len(rows), 'mismatches': mismatches,
               'native_exit': native_run.returncode, 'native_stdout': native_run.stdout.strip()}
    (out / 'comparison.json').write_text(json.dumps({'summary': summary, 'rows': rows}, indent=1) + '\n')
    print(json.dumps(summary))
    return 1 if mismatches or native_run.returncode else 0


def mutate(text, rng):
    kind = rng.randrange(6)
    if not text:
        return rng.choice(SNIPPETS)
    at = rng.randrange(len(text) + 1)
    if kind == 0:
        return text[:at]
    if kind == 1:
        return text[:at] + text[at + rng.randrange(1, 21):]
    if kind == 2:
        return text[:at] + rng.choice(SNIPPETS) + text[at:]
    lines = text.split('\n')
    if kind == 3 and len(lines) > 1:
        a, b = rng.randrange(len(lines)), rng.randrange(len(lines))
        lines[a], lines[b] = lines[b], lines[a]
        return '\n'.join(lines)
    if kind == 4:
        a = rng.randrange(len(lines))
        lines.insert(a, lines[a])
        return '\n'.join(lines)
    for _ in range(rng.randrange(2, 6)):
        text = mutate(text, rng)
    return text


def mutations(seedlist, count, native, out):
    seeds = [line.strip() for line in Path(seedlist).read_text().splitlines() if line.strip()]
    inputs = out.parent / (out.name + '-inputs')
    shutil.rmtree(inputs, ignore_errors=True)
    inputs.mkdir(parents=True)
    rng = random.Random(20261005)
    paths = []
    for index in range(count):
        seed = seeds[rng.randrange(len(seeds))]
        text = (ROOT / seed if not Path(seed).is_absolute() else Path(seed)).read_text(encoding='utf-8')
        target = inputs / ('m%04d.iron' % index)
        target.write_text(mutate(text, rng), encoding='utf-8', newline='')
        paths.append(str(target))
    return compare_units(paths, native, out)


def main():
    mode = sys.argv[1]
    if mode == 'corpus':
        return corpus(sys.argv[2], sys.argv[3], Path(sys.argv[4]))
    if mode == 'units':
        paths = [line.strip() for line in Path(sys.argv[2]).read_text().splitlines() if line.strip()]
        return compare_units(paths, sys.argv[3], Path(sys.argv[4]))
    if mode == 'mutations':
        return mutations(sys.argv[2], int(sys.argv[3]), sys.argv[4], Path(sys.argv[5]))
    raise SystemExit(__doc__)


if __name__ == '__main__':
    sys.exit(main())
