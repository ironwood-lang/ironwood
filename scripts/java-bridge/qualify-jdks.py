#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Build with each pinned producer JDK and compile/run every supported consumer JDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
P7 = ROOT / 'scripts/java-bridge/p7'
EXAMPLES = ROOT / 'examples/java-bridge'
PINS = {21: '21.0.12.1+1', 22: '22.0.2+9', 23: '23.0.2+7', 24: '24.0.2+12', 25: '25.0.4.1+1'}
REQUIRED = (21, 22, 23)


def native_access(major, modules=None):
    # Java 24 and 25 treat System.load as a restricted method (JEP 472); the grant keeps expected output exact.
    if major < 24: return []
    return ['--enable-native-access=' + (','.join(modules) if modules else 'ALL-UNNAMED')]
CASES = {
    'arrays': ('arraybench', sorted((EXAMPLES / 'arrays').glob('ArrayOps.iron'))),
    'byteviews': ('bytebench', [EXAMPLES / 'byteviews/ByteOps.iron']),
    'generics': ('genericbench', sorted((EXAMPLES / 'generics').glob('*.iron'))),
    'bounded-generics': ('boundedbench', sorted((EXAMPLES / 'bounded-generics').glob('*.iron'))),
    'listeners': ('org.ironwood.javabridge.listeners', sorted((EXAMPLES / 'listeners/src/main/ironwood').rglob('*.iron'))),
}
TESTS = [
    'Java Bridge JDK tools preserve Java 21 APIs and reject unsupported versions',
    'Java Bridge producer publishes paired jars with source parity and failure preservation',
    'Java Bridge assembly preserves paired bytes and rejects mixed or incomplete host artifacts',
    'Java Bridge distribution preserves paired jars and produces reproducible IDE companions',
]
EXPECTED = {
    'CombinedConsumer': 'p7-combined-ok\n',
    'org.ironwood.javabridge.basicsconsumer.Main': 'Java listener: 2\nJava listener: 5\nCounter total: 5\n',
    'org.ironwood.javabridge.consumer.Main': '42\ncopied: bridge\ncaught: example failure\ncontinued: 42\n',
    'org.ironwood.orderbook.Main': 'initial\n99\n100\n101\n80\nafter-market\n102\n30\n2\n120\nfinal\ntrue\ntrue\n4\n170\n2\n',
}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): h.update(chunk)
    return h.hexdigest()


def check_classes(path):
    with zipfile.ZipFile(path) as jar:
        classes = [name for name in jar.namelist() if name.endswith('.class')]
        if not classes: raise RuntimeError('missing classes: ' + str(path))
        for name in classes:
            data = jar.read(name)
            if data[:4] != b'\xca\xfe\xba\xbe' or data[4:8] != b'\x00\x00\x00\x41':
                raise RuntimeError('non-Java-21 class: ' + name)
        return len(classes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True, choices=['macos-arm64', 'linux-arm64', 'linux-x86_64'])
    parser.add_argument('--jdk', required=True, action='append', help='21=/path/to/home, repeated for 22 and 23; 24 and 25 are optional')
    parser.add_argument('--output', required=True, type=Path, help='New evidence directory')
    parser.add_argument('--producer', type=int, choices=sorted(PINS), help='Only this producer, for focused retries')
    args = parser.parse_args()
    homes = {int(value.split('=', 1)[0]): Path(value.split('=', 1)[1]).resolve() for value in args.jdk}
    if not set(REQUIRED) <= set(homes) <= set(PINS): parser.error('supply the pinned JDK 21, 22 and 23 homes; 24 and 25 are optional')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    records = []
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        if env.get(key): parser.error('unset ' + key)

    def run(folder, name, command, environment, expected=None):
        folder.mkdir(parents=True, exist_ok=True)
        command = list(map(str, command))
        (folder / (name + '.command.json')).write_text(json.dumps(command, indent=2) + '\n')
        (folder / (name + '.environment.json')).write_text(json.dumps({k: environment.get(k) for k in
            ('JAVA_HOME', 'PATH', 'IRONWOOD_LLVM_HOME', 'IRONWOOD_BRIDGE_SUPPORT_HOME')}, indent=2) + '\n')
        with (folder / (name + '.log')).open('w') as log:
            result = subprocess.run(command, cwd=ROOT, env=environment, stdout=log,
                                    stderr=subprocess.STDOUT, text=True, timeout=1800)
        result.stdout = (folder / (name + '.log')).read_text()
        (folder / (name + '.exit.txt')).write_text(str(result.returncode) + '\n')
        records.append({'folder': str(folder.relative_to(out)), 'name': name, 'exit': result.returncode})
        (out / 'records.json').write_text(json.dumps(records, indent=2) + '\n')
        if result.returncode or expected is not None and result.stdout != expected:
            raise RuntimeError(name + ': see ' + str(folder / (name + '.log')))
        print('PASS ' + str(folder.relative_to(out)) + '/' + name, flush=True)
        return result.stdout

    for major, home in sorted(homes.items()):
        version = run(out, 'jdk-' + str(major), [home / 'bin/java', '-version'], env)
        if 'Temurin-' + PINS[major] not in version: raise RuntimeError('JDK pin differs: ' + str(home))
    run(out, 'llvm-version', ['llvm-as', '--version'], env)
    (out / 'input.json').write_text(json.dumps({'target': args.target, 'runner.sha256': digest(__file__),
        'homes': {str(k): str(v) for k, v in homes.items()}}, indent=2) + '\n')

    for producer in ([args.producer] if args.producer else sorted(homes)):
        folder = out / ('producer-' + str(producer)); home = homes[producer]
        selected = dict(env, JAVA_HOME=str(home))
        # Deliberately keep the caller's PATH. Build/launcher must honor JAVA_HOME.
        run(folder, 'build', [ROOT / 'scripts/build.sh'], selected)
        test_sources = folder / 'test-sources.txt'
        test_sources.write_text(''.join(str(p) + '\n' for p in sorted((ROOT / 'compiler/src/test/java').rglob('*.java'))))
        test_classes = ROOT / 'compiler/build/test-classes'
        if test_classes.exists(): shutil.rmtree(test_classes)
        run(folder, 'test-javac', [home / 'bin/javac', '--release', '21', '-encoding', 'UTF-8', '-Xlint:all',
            '-Werror', '-cp', ROOT / 'compiler/build/classes', '-d', test_classes, '@' + str(test_sources)], selected)
        selected_tests = TESTS if args.target == 'macos-arm64' else [t for t in TESTS if t != TESTS[1]]
        run(folder, 'tests', [home / 'bin/java', '-Xmx1536m', '-ea', '-cp',
            str(ROOT / 'compiler/build/classes') + os.pathsep + str(test_classes), 'ironwood.compiler.CompilerTests',
            *[a for t in selected_tests for a in ('--test', t)]], selected)
        shutil.copyfile(ROOT / 'compiler/build/ironwoodc.jar', folder / 'ironwoodc.jar')
        compiler = [ROOT / 'bin/ironwoodc']
        artifacts = folder / 'artifacts'; artifacts.mkdir()

        def produce(name, export, inputs):
            jar = artifacts / (name + '.jar')
            run(folder, 'produce-' + name, [*compiler, '--java-bridge', '--export', export, '--unfreed=off',
                '-O3', '--license', ROOT / 'LICENSE-MIT', '--license', ROOT / 'LICENSE-APACHE', '-o', jar, *inputs], selected)
            return jar

        for name, (export, sources) in CASES.items(): produce(name, export, sources)
        basics = sorted((EXAMPLES / 'basics/src/main/ironwood').rglob('*.iron'))
        run(folder, 'compile-basics', [*compiler, '--unfreed=error', '-d', folder / 'basics-classes', *basics], selected)
        produce('basics', 'org.ironwood.javabridge.basics', ['-cp', folder / 'basics-classes'])
        value = EXAMPLES / 'value/src/main/ironwood/org/ironwood/javabridge/value/Values.iron'
        run(folder, 'compile-values', [*compiler, '--unfreed=error', '-d', folder / 'value-classes', value], selected)
        run(folder, 'archive-values', [ROOT / 'bin/ironjar', '--create', '--file', folder / 'values.ironjar',
            '--license', ROOT / 'LICENSE-MIT', folder / 'value-classes'], selected)
        produce('values', 'org.ironwood.javabridge.value', ['-cp', folder / 'values.ironjar'])
        order_source = ROOT / 'projects/OrderBook/src/main/ironwood'
        run(folder, 'compile-orderbook', [*compiler, '--unfreed=off', '--source-path', order_source,
            '-d', folder / 'orderbook-classes', order_source / 'org/ironwood/orderbook/OrderBook.iron'], selected)
        produce('orderbook', 'org.ironwood.orderbook', ['-cp', folder / 'orderbook-classes'])
        run(folder, 'standalone-values', [*compiler, '--java-bridge-values', '-o', folder / 'ironwood-bridge-values.jar'], selected)
        if digest(folder / 'ironwood-bridge-values.jar') != digest(artifacts / 'ironwood-bridge-values.jar'):
            raise RuntimeError('standalone companion differs from producer companion')
        inventory = {}
        for jar in sorted(artifacts.glob('*.jar')):
            inventory[jar.name] = {'sha256': digest(jar), 'classes': check_classes(jar)}
            if jar.name == 'ironwood-bridge-values.jar': continue
            with zipfile.ZipFile(jar) as archive:
                (folder / (jar.stem + '.properties')).write_bytes(archive.read('META-INF/ironwood/bridge.properties'))
            run(folder, 'distribution-' + jar.stem, [*compiler, '--java-bridge-distribution', '--input', jar,
                '--group-id', 'org.ironwood.jdkcheck', '--artifact-id', jar.stem, '--version', '0.0.0-local',
                '-d', folder / ('distribution-' + jar.stem)], selected)
            installed = folder / ('distribution-' + jar.stem) / (jar.stem + '-0.0.0-local.jar')
            if digest(installed) != digest(jar): raise RuntimeError('packaging changed paired bytes')
        inventory['ironwoodc.jar'] = {'sha256': digest(folder / 'ironwoodc.jar'), 'classes': check_classes(folder / 'ironwoodc.jar')}
        (folder / 'artifacts.json').write_text(json.dumps(inventory, indent=2) + '\n')
        cp = os.pathsep.join(str(p) for p in sorted(artifacts.glob('*.jar')))
        sources = [P7 / 'CombinedConsumer.java', P7 / 'VersionAdmission.java',
            EXAMPLES / 'basics/src/main/java/org/ironwood/javabridge/basicsconsumer/Main.java',
            EXAMPLES / 'value/src/main/java/org/ironwood/javabridge/consumer/Main.java',
            ROOT / 'projects/OrderBook/java/src/main/java/org/ironwood/orderbook/Main.java']
        for consumer, consumer_home in sorted(homes.items()):
            cell = folder / ('consumer-' + str(consumer)); classes = cell / 'classes'
            run(cell, 'javac', [consumer_home / 'bin/javac', '--release', '21', '-encoding', 'UTF-8', '-Xlint:all',
                '-Werror', '-sourcepath', '', '-cp', cp, '-d', classes, *sources], selected)
            for name, expected in EXPECTED.items():
                for checked in (False, True):
                    with tempfile.TemporaryDirectory(dir=cell) as temporary:
                        run(cell, name + ('-checked' if checked else ''), [consumer_home / 'bin/java',
                            '-Djava.io.tmpdir=' + temporary, *(['-Xcheck:jni'] if checked else []),
                            *native_access(consumer), '-cp', cp + os.pathsep + str(classes), name], selected, expected)
            modules = []
            for jar in sorted(artifacts.glob('*.jar')):
                with zipfile.ZipFile(jar) as archive:
                    manifest = archive.read('META-INF/MANIFEST.MF').decode()
                    # Module names are wrapped to standard manifest line widths.
                    manifest = manifest.replace('\r\n ', '')
                    modules.append(next(line.split(': ', 1)[1] for line in manifest.splitlines() if line.startswith('Automatic-Module-Name: ')))
            with tempfile.TemporaryDirectory(dir=cell) as temporary:
                run(cell, 'module', [consumer_home / 'bin/java', '-Djava.io.tmpdir=' + temporary,
                    '-Xcheck:jni', *native_access(consumer, modules), '--module-path', cp, '--add-modules', ','.join(modules),
                    '-cp', classes, 'CombinedConsumer'], selected, EXPECTED['CombinedConsumer'])
        (folder / 'exit.txt').write_text('0\n')
    (out / 'exit.txt').write_text('0\n')
    print('PASS producer/consumer matrix: ' + str(out))


if __name__ == '__main__': main()
