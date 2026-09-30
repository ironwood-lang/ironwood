#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Qualify an extracted IDK with installed defaults and each supported external JDK."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('jdk_workflow', ROOT / 'scripts/java-bridge/qualify-jdks.py')
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--idk', required=True, type=Path)
    parser.add_argument('--jdk', required=True, action='append', help='21=/home, repeated for 22 and 23')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--producer', type=int, choices=(21, 22, 23))
    parser.add_argument('--resume', action='store_true', help='Reuse matching successful commands and preserve failed logs')
    args = parser.parse_args()
    idk = args.idk.resolve(); out = args.output.resolve()
    homes = {int(value.split('=', 1)[0]): Path(value.split('=', 1)[1]).resolve() for value in args.jdk}
    if set(homes) != {21, 22, 23}: parser.error('supply all three supported JDK homes')
    out.mkdir(parents=True, exist_ok=args.resume)
    records = json.loads((out / 'records.json').read_text()) if args.resume and (out / 'records.json').exists() else []
    env = os.environ.copy()
    for key in ('JAVA_HOME', 'IRONWOOD_LLVM_HOME', 'IRONWOOD_RUNTIME_HOME', 'IRONWOOD_BRIDGE_SUPPORT_HOME',
                'SDKROOT', 'DEVELOPER_DIR', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    env['PATH'] = str(idk / 'bin') + ':/usr/bin:/bin'

    def run(folder, label, command, environment, expected=None):
        folder.mkdir(parents=True, exist_ok=True); command = list(map(str, command))
        saved = folder / (label + '.command.json'); status = folder / (label + '.exit.txt')
        selected = {key: environment.get(key) for key in
            ('JAVA_HOME', 'PATH', 'SDKROOT', 'DEVELOPER_DIR', 'IRONWOOD_LLVM_HOME', 'IRONWOOD_BRIDGE_SUPPORT_HOME')}
        saved_environment = folder / (label + '.environment.json')
        if args.resume and saved.exists() and status.exists() and status.read_text().strip() == '0' \
                and json.loads(saved.read_text()) == command and saved_environment.exists() \
                and json.loads(saved_environment.read_text()) == selected:
            text = (folder / (label + '.log')).read_text()
            if expected is not None and text != expected: raise RuntimeError('saved output differs: ' + label)
            print('REUSE ' + str(folder.relative_to(out)) + '/' + label, flush=True)
            return text
        if saved.exists():
            attempt = len(records)
            for suffix in ('.command.json', '.environment.json', '.log', '.exit.txt'):
                previous = folder / (label + suffix)
                if previous.exists(): previous.rename(folder / (label + '.attempt-' + str(attempt) + suffix))
        (folder / (label + '.command.json')).write_text(json.dumps(command, indent=2) + '\n')
        saved_environment.write_text(json.dumps(selected, indent=2) + '\n')
        with (folder / (label + '.log')).open('w') as log:
            result = subprocess.run(command, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        text = (folder / (label + '.log')).read_text()
        (folder / (label + '.exit.txt')).write_text(str(result.returncode) + '\n')
        records.append({'folder': str(folder.relative_to(out)), 'name': label, 'exit': result.returncode})
        (out / 'records.json').write_text(json.dumps(records, indent=2) + '\n')
        if result.returncode or expected is not None and text != expected:
            raise RuntimeError(label + ': see ' + str(folder / (label + '.log')))
        print('PASS ' + str(folder.relative_to(out)) + '/' + label, flush=True)
        return text

    compiler = idk / 'bin/ironwoodc'
    run(out, 'default-version', [compiler, '--version'], env)
    run(out, 'bundled-jdk', [idk / 'toolchain/lib/jvm/bin/java', '-version'], env)
    run(out, 'llvm', [idk / 'toolchain/bin/llvm-config', '--version'], env, '23.1.0\n')
    for major, home in sorted(homes.items()):
        text = run(out, 'jdk-' + str(major), [home / 'bin/java', '-version'], env)
        if 'Temurin-' + workflow.PINS[major] not in text: raise RuntimeError('JDK pin differs')
    basic = out / 'NativeSmoke.iron'
    basic.write_text('// SPDX-License-Identifier: MIT OR Apache-2.0\n// Verify installed native linking; expected exit status 42.\npublic class NativeSmoke { public static int main(String[] args) { return 42; } }\n')
    run(out, 'native-compile', [compiler, '-d', out / 'native-classes', basic], env)
    run(out, 'native-link', [compiler, '--link', '-cp', out / 'native-classes', '--main-class', 'NativeSmoke',
        '-O3', '-o', out / 'native-smoke'], env)
    native = subprocess.run([out / 'native-smoke'], env=env, capture_output=True)
    if native.returncode != 42 or native.stdout or native.stderr: raise RuntimeError('native default failed')
    (out / 'native.exit.txt').write_text('42\n')
    for step in ('compile', 'link'):
        run(out, 'default-basics-' + step, [idk / ('examples/java-bridge/basics/' + step + '.sh')], env)
    run(out, 'default-basics-run', [idk / 'toolchain/lib/jvm/bin/java', '-Xcheck:jni', '-cp',
        str(idk / 'examples/java-bridge/basics/target/ironwood-basics.jar') + os.pathsep
        + str(idk / 'examples/java-bridge/basics/target/consumer-classes'),
        'org.ironwood.javabridge.basicsconsumer.Main'], env,
        workflow.EXPECTED['org.ironwood.javabridge.basicsconsumer.Main'])

    def installed(path): return idk / path.relative_to(ROOT)

    for producer in ([args.producer] if args.producer else [21, 22, 23]):
        folder = out / ('producer-' + str(producer)); artifacts = folder / 'artifacts'; artifacts.mkdir(parents=True, exist_ok=args.resume)
        selected = dict(env, JAVA_HOME=str(homes[producer]))

        def produce(name, export, inputs):
            run(folder, 'produce-' + name, [compiler, '--java-bridge', '--export', export, '--unfreed=off',
                '-O3', '--license', idk / 'LICENSE-MIT', '--license', idk / 'LICENSE-APACHE',
                '-o', artifacts / (name + '.jar'), *inputs], selected)

        for name, (export, sources) in workflow.CASES.items(): produce(name, export, list(map(installed, sources)))
        basics = sorted(path for path in (idk / 'examples/java-bridge/basics/src/main/ironwood').rglob('*.iron')
                        if not path.name.startswith('._'))
        run(folder, 'compile-basics', [compiler, '--unfreed=error', '-d', folder / 'basics-classes', *basics], selected)
        produce('basics', 'org.ironwood.javabridge.basics', ['-cp', folder / 'basics-classes'])
        run(folder, 'compile-values', [compiler, '--unfreed=error', '-d', folder / 'value-classes',
            idk / 'examples/java-bridge/value/src/main/ironwood/org/ironwood/javabridge/value/Values.iron'], selected)
        run(folder, 'archive-values', [idk / 'bin/ironjar', '--create', '--file', folder / 'values.ironjar',
            '--license', idk / 'LICENSE-MIT', folder / 'value-classes'], selected)
        produce('values', 'org.ironwood.javabridge.value', ['-cp', folder / 'values.ironjar'])
        order = idk / 'projects/OrderBook/src/main/ironwood'
        run(folder, 'compile-orderbook', [compiler, '--unfreed=off', '--source-path', order,
            '-d', folder / 'orderbook-classes', order / 'org/ironwood/orderbook/OrderBook.iron'], selected)
        produce('orderbook', 'org.ironwood.orderbook', ['-cp', folder / 'orderbook-classes'])
        run(folder, 'standalone-values', [compiler, '--java-bridge-values', '-o', folder / 'standalone.jar'], selected)
        if workflow.digest(folder / 'standalone.jar') != workflow.digest(artifacts / 'ironwood-bridge-values.jar'):
            raise RuntimeError('paired companion differs')
        inventory = {}
        for jar in sorted(artifacts.glob('*.jar')):
            inventory[jar.name] = {'sha256': workflow.digest(jar), 'classes': workflow.check_classes(jar)}
            if jar.name != 'ironwood-bridge-values.jar':
                with zipfile.ZipFile(jar) as archive:
                    (folder / (jar.stem + '.properties')).write_bytes(archive.read('META-INF/ironwood/bridge.properties'))
        (folder / 'artifacts.json').write_text(json.dumps(inventory, indent=2) + '\n')
        distribution = folder / 'distribution'
        run(folder, 'distribution', [compiler, '--java-bridge-distribution', '--input', artifacts / 'basics.jar',
            '--group-id', 'org.ironwood.idkcheck', '--artifact-id', 'basics', '--version', '0.0.0-local', '-d', distribution], selected)
        main = distribution / 'basics-0.0.0-local.jar'
        if workflow.digest(main) != workflow.digest(artifacts / 'basics.jar'): raise RuntimeError('distribution changed main bytes')
        main.unlink()  # The verified identical original remains; avoid duplicating Linux source archives.
        cp = os.pathsep.join(map(str, sorted(artifacts.glob('*.jar'))))
        sources = [ROOT / 'scripts/java-bridge/p7/CombinedConsumer.java',
            idk / 'examples/java-bridge/basics/src/main/java/org/ironwood/javabridge/basicsconsumer/Main.java',
            idk / 'examples/java-bridge/value/src/main/java/org/ironwood/javabridge/consumer/Main.java',
            idk / 'projects/OrderBook/java/src/main/java/org/ironwood/orderbook/Main.java']
        for consumer, home in sorted(homes.items()):
            cell = folder / ('consumer-' + str(consumer)); classes = cell / 'classes'
            run(cell, 'javac', [home / 'bin/javac', '--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
                '-sourcepath', '', '-cp', cp, '-d', classes, *sources], selected)
            for name, result in workflow.EXPECTED.items():
                for checked in (False, True):
                    with tempfile.TemporaryDirectory(dir=cell) as temporary:
                        run(cell, name + ('-checked' if checked else ''), [home / 'bin/java', '-Djava.io.tmpdir=' + temporary,
                            *(['-Xcheck:jni'] if checked else []), '-cp', cp + os.pathsep + str(classes), name], selected, result)
            modules = []
            for jar in sorted(artifacts.glob('*.jar')):
                with zipfile.ZipFile(jar) as archive:
                    manifest = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
                    modules.append(next(line.split(': ', 1)[1] for line in manifest.splitlines() if line.startswith('Automatic-Module-Name: ')))
            with tempfile.TemporaryDirectory(dir=cell) as temporary:
                run(cell, 'module', [home / 'bin/java', '-Djava.io.tmpdir=' + temporary, '-Xcheck:jni', '--module-path', cp,
                    '--add-modules', ','.join(modules), '-cp', classes, 'CombinedConsumer'], selected, workflow.EXPECTED['CombinedConsumer'])
        (folder / 'exit.txt').write_text('0\n')
    (out / 'exit.txt').write_text('0\n')


if __name__ == '__main__': main()
