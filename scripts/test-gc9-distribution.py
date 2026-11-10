#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused GC9 checks of an extracted host package or IDK, with isolated inputs."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import textwrap
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('engine', ROOT / 'scripts/test-gc-engine.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


def require_metadata_space(jar, directory):
    # The refusal driver writes one ZIP_STORED variant at a time, then removes
    # it. Bound its expanded payload and headers, plus a native extraction and
    # JVM/log scratch reserve; a fixed 30 GiB floor excludes hosted runners.
    with zipfile.ZipFile(jar) as archive:
        expanded = sum(item.file_size + 256 + 2 * len(item.filename.encode('utf-8'))
                       for item in archive.infolist())
    required = 2 * expanded + 256 * 1024**2
    available = shutil.disk_usage(directory).free
    if available < required:
        raise RuntimeError(f'metadata variants require {required} bytes, only {available} available')
    return required


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', required=True, type=Path)
    parser.add_argument('--kind', required=True, choices=('host', 'idk'))
    parser.add_argument('--llvm-home', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--bridge-support-home', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    package, out = args.distribution.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    llvm = package / 'toolchain' if args.kind == 'idk' else args.llvm_home
    java = package / 'toolchain/lib/jvm' if args.kind == 'idk' else args.java_home
    if llvm is None or java is None:
        parser.error('host package checks require --llvm-home and --java-home')
    llvm, java = llvm.resolve(), java.resolve()
    # IDKs must discover bundled tools with no development PATH. Host packages
    # require the caller's external native tools, including the linker.
    search = os.environ.get('PATH', '/usr/bin:/bin') if args.kind == 'host' else '/usr/bin:/bin'
    # env -i removes compiler, Java, SDK and support overrides. macOS retains
    # its documented explicit Apple SDK selections if supplied.
    clean = ['/usr/bin/env', '-i', 'PATH=' + search, 'LANG=C', 'LC_ALL=C']
    if args.kind == 'host':
        clean += ['JAVA_HOME=' + str(java), 'IRONWOOD_LLVM_HOME=' + str(llvm)]
        if args.bridge_support_home is not None:
            clean += ['IRONWOOD_BRIDGE_SUPPORT_HOME=' + str(args.bridge_support_home.resolve())]
    for key in ('SDKROOT', 'DEVELOPER_DIR'):
        if key in os.environ:
            clean.append(key + '=' + os.environ[key])
    compiler = package / 'bin/ironwoodc'
    results = []

    def run(name, command, expected=0, environment=clean):
        result = engine.run([*environment, *map(str, command)], raw, name, expected_exit=expected)
        results.append(result)
        (out / 'progress.json').write_text(json.dumps(results, indent=2, sort_keys=True) + '\n')
        print(name + ': passed', flush=True)
        return (raw / (name + '.stdout')).read_text()

    sdk = package / 'toolchain/ironwood-gc'
    manifest = json.loads((sdk / 'build.json').read_text())
    packaged = json.loads((package / 'packaging/gc-inputs.json').read_text())
    assert packaged['sdk.bundled'] and packaged['production.enabled']
    assert packaged['gc.config.sha256'] == manifest['gc.config.sha256']
    assert packaged['sdk.manifest.sha256'] == engine.gc.digest(sdk / 'build.json')
    assert packaged['sdk.files.sha256'] == manifest['files.sha256']
    sdk_snapshots = {}
    for relative in ('toolchain/ironwood-gc', 'toolchain/ironwood-tls', 'toolchain/ironwood-bridge-support'):
        prefix = package / relative
        if prefix.is_dir():
            sdk_snapshots[relative] = {str(p.relative_to(prefix)): engine.gc.digest(p)
                                       for p in prefix.rglob('*') if p.is_file()}
    for relative in ('LICENSE', 'LICENSE-MIT', 'LICENSE-APACHE', 'LICENSE_MECHANICS',
                     'LICENSES/GPL-2.0-only.txt', 'LICENSES/Classpath-exception-2.0.txt',
                     'LICENSES/Unicode-15.0.txt', 'LICENSES/MPL-2.0.txt',
                     'THIRD_PARTY_NOTICES.md', 'docs/SOURCE_PROVENANCE.md'):
        source = ROOT / relative
        if relative in ('LICENSE_MECHANICS', 'THIRD_PARTY_NOTICES.md'):
            source = ROOT / 'docs' / relative
        assert (package / relative).read_bytes() == source.read_bytes(), relative
    assert engine.gc.digest(sdk / 'sources/gc-8.2.12.tar.gz') == manifest['configuration']['archive.sha256']
    assert (sdk / 'licenses/BDWGC-NOTICES.txt').stat().st_size > 0
    first = run('first-launch', [compiler, '--version'])
    assert first == run('subsequent-launch', [compiler, '--version'])
    assert first.startswith('ironwoodc ' + (package / 'VERSION').read_text().strip() + '\n')
    assert 'LLVM version: 23.1.0\n' in first
    python = llvm / 'bin/python' if args.kind == 'idk' else Path(sys.executable)
    run('gc-offline-verify', [python, package / 'scripts/prepare-gc.py', '--verify',
                             '--prefix', sdk, '--llvm-home', llvm])
    if args.kind == 'idk':
        run('tls-offline-verify', [python, package / 'scripts/prepare-tls.py', '--verify',
                                  '--prefix', package / 'toolchain/ironwood-tls', '--llvm-home', llvm])
        if manifest['configuration']['target'].startswith('linux'):
            run('bridge-support-verify', [python, package / 'scripts/prepare-java-bridge-support.py', '--check',
                '--target', manifest['configuration']['target'], '--prefix', package / 'toolchain/ironwood-bridge-support'])
    for relative, before in sdk_snapshots.items():
        prefix = package / relative
        after = {str(p.relative_to(prefix)): engine.gc.digest(p) for p in prefix.rglob('*') if p.is_file()}
        assert before == after, 'launcher relocation modified verified SDK: ' + relative
    run('stdlib-archive', [package / 'bin/ironjar', '--list', '--file', package / 'lib/ironwood-stdlib.ironjar'])
    run('testing-archive', [package / 'bin/ironjar', '--list', '--file', package / 'lib/ironwood-testing.ironjar'])
    native_artifacts = {}
    with tempfile.TemporaryDirectory(prefix='ironwood-gc9-deployed-') as temporary:
        deployed = Path(temporary)
        source = deployed / 'gc5_integrated.iron'
        shutil.copyfile(ROOT / 'integration-tests/cases/gc5_integrated.iron', source)
        classes, archive = deployed / 'classes', deployed / 'program.ironjar'
        # Pair static collector outputs with the exact unmodified source,
        # licenses, recipe and manifest used to build them.
        shutil.copytree(sdk, deployed / 'collector-inputs')
        run('source-to-class', [compiler, source, '-d', classes])
        run('class-to-archive', [package / 'bin/ironjar', '--create', '--file', archive, classes])
        for name, input_path in [('class', classes), ('archive', archive)]:
            binary = deployed / ('program-' + name)
            run(name + '-link', [compiler, '--link', '--main-class', 'Gc5Integrated', '-cp', input_path,
                                  '-O3', '--llvm-home', llvm, '-o', binary])
            native_artifacts[name] = {'sha256': engine.gc.digest(binary), 'bytes': binary.stat().st_size,
                                      'collector_manifest_sha256': engine.gc.digest(sdk / 'build.json')}
            # Deployed programs have no Java/LLVM/GC/loader-search environment.
            text = run(name + '-deployed', [binary], environment=['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin'])
            assert text == 'GC5 integrated passed\n'
            if manifest['configuration']['target'].startswith('macos'):
                run(name + '-dependencies', ['/usr/bin/otool', '-L', binary])
            else:
                dependencies = run(name + '-dependencies', [llvm / 'bin/llvm-readelf', '-dW', binary])
                assert str(llvm) not in dependencies, 'deployed executable depends on build toolchain'
                versions = run(name + '-versions', [llvm / 'bin/llvm-readelf', '--version-info', binary])
                import re
                assert all(tuple(map(int, v.split('.'))) <= (2, 17)
                           for v in re.findall(r'\bGLIBC_(\d+(?:\.\d+)+)', versions)), 'glibc baseline exceeded'
                arch = 'aarch64' if manifest['configuration']['target'] == 'linux-arm64' else 'x86_64'
                sysroot = llvm / (arch + '-conda-linux-gnu/sysroot')
                loader = sysroot / ('lib/ld-linux-aarch64.so.1' if arch == 'aarch64' else 'lib64/ld-linux-x86-64.so.2')
                support = next(deployed.glob('.ironwood-bridge-support-*/lib'))
                search = ':'.join(map(str, [support, sysroot / 'lib64', sysroot / 'lib', sysroot / 'usr/lib64']))
                command = [loader, '--inhibit-cache', '--library-path', search]
                resolved = run(name + '-glibc217-resolution', [*command, '--list', binary])
                for line in resolved.splitlines():
                    if any(token in line for token in ('libc.so.', 'libm.so.')):
                        assert str(sysroot) in line, 'glibc baseline test used host libraries'
                text = run(name + '-glibc217-deployed', [*command, binary],
                           environment=['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin'])
                assert text == 'GC5 integrated passed\n'
        if manifest['configuration']['target'].startswith('macos'):
            for key in ('SDKROOT', 'DEVELOPER_DIR'):
                refused = deployed / ('refused-' + key)
                run('invalid-' + key, [compiler, '--link', '--main-class', 'Gc5Integrated', '-cp', archive,
                    '--llvm-home', llvm, '-o', refused], 1, [*clean, key + '=/nonexistent/gc9-apple-tools'])
                assert not refused.exists()
        # A nearby altered input must fail before generating any executable.
        properties = sdk / 'build.properties'
        original = properties.read_bytes()
        try:
            properties.write_bytes(original.replace(b'roots.version=2\n', b'roots.version=1\n'))
            assert properties.read_bytes() != original
            refused = deployed / 'refused'
            run('stale-sdk-refusal', [compiler, '--link', '--main-class', 'Gc5Integrated', '-cp', archive,
                                     '--llvm-home', llvm, '-o', refused], 1)
            assert not refused.exists() and 'GC dependency:' in (raw / 'stale-sdk-refusal.stderr').read_text()
        finally:
            properties.write_bytes(original)
        bridge_source = deployed / 'Counter.iron'
        bridge_source.write_text(textwrap.dedent('''\
            // SPDX-License-Identifier: MIT OR Apache-2.0
            // Packaged bridge smoke: handles retain this value across explicit
            // collection and a registered listener reenters from a real JVM.
            package gc9smoke;
            import ironwood.lang.Memory;
            public final class Counter {
                int number;
                Listener listener;
                public Counter(int number) { this.number = number; }
                public int value() { Memory.collect(); return this.number; }
                public void listen(Listener listener) { this.listener = listener; }
                public int fire(int value) { Memory.collect(); return this.listener.call(value); }
                public static void collect() { Memory.collect(); }
            }
            '''))
        listener_source = deployed / 'Listener.iron'
        listener_source.write_text('// SPDX-License-Identifier: MIT OR Apache-2.0\n'
                                   'package gc9smoke;\npublic interface Listener { int call(int value); }\n')
        bridge_classes, jar = deployed / 'bridge-classes', deployed / 'bridge.jar'
        run('bridge-source-to-class', [compiler, bridge_source, listener_source, '-d', bridge_classes])
        run('bridge-producer', [compiler, '--java-bridge', '--export', 'gc9smoke', '-O3',
            '-cp', bridge_classes, '--llvm-home', llvm, '--license', package / 'LICENSE-MIT', '-o', jar])
        with zipfile.ZipFile(jar) as paired:
            names = paired.namelist()
            support = next(n.removesuffix('/Roots.class').replace('/', '.') for n in names
                           if n.startswith('ironwood/bridge/generated/') and n.endswith('/Roots.class'))
            assert 'META-INF/ironwood/source/BDWGC/gc-8.2.12.tar.gz' in names
            assert 'META-INF/ironwood/licenses/BDWGC-NOTICES.txt' in names
        consumer = deployed / 'Gc9Consumer.java'
        consumer.write_text(textwrap.dedent('''\
            // SPDX-License-Identifier: MIT OR Apache-2.0
            import java.lang.reflect.*;
            import java.util.Arrays;
            public final class Gc9Consumer {
                private Gc9Consumer() {}
                public static void main(String[] args) throws Exception {
                    Class<?> roots = Class.forName(args[0] + ".Roots");
                    Class<?> counter = Class.forName("gc9smoke.Counter");
                    Class<?> listener = Class.forName("gc9smoke.Listener");
                    Method factory = Arrays.stream(roots.getMethods())
                        .filter(m -> m.getName().startsWith("construct_")).findFirst().orElseThrow();
                    Object first = factory.invoke(null, 37);
                    Method value = first.getClass().getMethod("value");
                    Object facade = value.invoke(first);
                    Object second = roots.getMethod("retain", counter).invoke(null, facade);
                    ((AutoCloseable)first).close();
                    Method number = counter.getMethod("value");
                    if (!number.invoke(facade).equals(37)) throw new AssertionError("anchor lost");
                    Class<?> listeners = Class.forName(args[0] + ".Listeners");
                    Object registration = listeners.getMethod("open").invoke(null);
                    Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                        (proxy, method, values) -> (Integer)number.invoke(facade) + (Integer)values[0]);
                    Method install = Arrays.stream(listeners.getMethods())
                        .filter(m -> m.getName().startsWith("install_")).findFirst().orElseThrow();
                    install.invoke(null, registration, facade, callback);
                    if (!counter.getMethod("fire", int.class).invoke(facade, 5).equals(42))
                        throw new AssertionError("registered callback/reentry failed");
                    ((AutoCloseable)registration).close();
                    ((AutoCloseable)second).close();
                    counter.getMethod("collect").invoke(null);
                    try { number.invoke(facade); throw new AssertionError("closed facade accepted"); }
                    catch (InvocationTargetException expected) {
                        if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
                    }
                    System.out.println("GC9 packaged handles, registration and reentry passed");
                }
            }
            '''))
        run('bridge-consumer-compile', [java / 'bin/javac', '--release', '21', '-Xlint:all', '-Werror', '-d', deployed, consumer])
        text = run('bridge-jvm-load', [java / 'bin/java', '-Xcheck:jni', '-cp', str(deployed) + ':' + str(jar),
                                      'Gc9Consumer', support])
        assert text == 'GC9 packaged handles, registration and reentry passed\n'
        assert not (raw / 'bridge-jvm-load.stderr').read_text()
        with zipfile.ZipFile(jar) as paired:
            bridge_payloads = {n: hashlib.sha256(paired.read(n)).hexdigest() for n in paired.namelist()
                               if n.endswith(('.so', '.dylib', 'bridge.properties', 'configuration.json', 'build.properties'))}
        (out / 'bridge-artifact.json').write_text(json.dumps({'jar.sha256': engine.gc.digest(jar),
            'payloads.sha256': bridge_payloads}, indent=2, sort_keys=True) + '\n')
        shutil.copyfile(jar, out / 'managed-bridge.jar')
        assert 'built for newer' not in (raw / 'bridge-producer.stderr').read_text()
        if args.kind == 'idk':
            scalar = deployed / 'Scalar.iron'
            scalar.write_text('// SPDX-License-Identifier: MIT OR Apache-2.0\n'
                '// Scalar-only bootstrap still requires complete managed metadata.\n'
                'package gc9scalar;\npublic final class Scalar {\n'
                '    private Scalar() {}\n    public static int plus(int value) { return value + 1; }\n'
                '    public static long live() { return System.liveAllocationCount(); }\n}\n')
            scalar_classes, scalar_jar = deployed / 'scalar-classes', deployed / 'scalar.jar'
            run('scalar-source-to-class', [compiler, scalar, '-d', scalar_classes])
            run('scalar-producer', [compiler, '--java-bridge', '--export', 'gc9scalar', '-O3',
                '-cp', scalar_classes, '--llvm-home', llvm, '--license', package / 'LICENSE-MIT', '-o', scalar_jar])
            require_metadata_space(scalar_jar, out)
            run('scalar-load-refusals', [python, ROOT / 'scripts/test-gc6-metadata.py', '--jar', scalar_jar,
                '--type', 'gc9scalar.Scalar', '--java-home', java, '--output', out / 'scalar-metadata'])
            for name in ('ironwood-nonmoving-statepoints.so', 'ironwood-nonmoving-statepoints.properties'):
                path = package / 'lib' / name
                original = path.read_bytes()
                try:
                    for mutation in ('missing', 'altered'):
                        if mutation == 'missing': path.unlink()
                        else:
                            path.write_bytes(original.replace(b'format=1\n', b'format=0\n')
                                             if name.endswith('.properties') else original + b'altered')
                        refused = deployed / 'refused-pass'
                        run(mutation + '-' + name, [compiler, '--link', '--main-class', 'Gc5Integrated',
                            '-cp', archive, '--llvm-home', llvm, '-o', refused], 1)
                        assert not refused.exists()
                finally:
                    path.write_bytes(original)
    report = {'format': 1, 'kind': args.kind, 'target': manifest['configuration']['target'],
              'gc.config.sha256': manifest['gc.config.sha256'], 'guard_kib': engine.GUARD_KIB,
              'child_timeout_seconds': 60, 'sdk_checksums_preserved': True,
              'sdk_payloads': sdk_snapshots, 'results': results,
              'native_artifacts': native_artifacts,
              'limits': ['Focused native and managed bridge package checks; TLS peer and full target acceptance are separate.']}
    (out / 'results.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('GC9 focused distribution checks passed', flush=True)


if __name__ == '__main__':
    main()
