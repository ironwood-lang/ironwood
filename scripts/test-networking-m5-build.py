#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Exercise optional TLS selection through actual class/archive native links."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'integration-tests/target/networking-m5-build'


def prepare_toolchain_wrapper(llvm, wrapper, trace):
    if llvm.resolve().is_relative_to(wrapper.resolve()):
        raise ValueError('the selected toolchain cannot be inside the test wrapper')
    # This fixture owns the wrapper tree. Recreate it rather than retaining valid
    # links to old tools or treating dangling links as absent directory entries.
    if wrapper.is_symlink(): wrapper.unlink()
    elif wrapper.exists(): shutil.rmtree(wrapper)
    (wrapper / 'bin').mkdir(parents=True, exist_ok=True)
    for child in llvm.iterdir():
        if child.name != 'bin': (wrapper / child.name).symlink_to(child)
    for child in (llvm / 'bin').iterdir():
        dest = wrapper / 'bin' / child.name
        if child.name != 'clang': dest.symlink_to(child)
    clang = wrapper / 'bin/clang'
    clang.write_text('#!/usr/bin/env python3\nimport os,sys,json\n'
                     + f'with open({str(trace)!r}, "a") as log: log.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                     + f'os.execv({str(llvm / "bin/clang")!r}, [{str(llvm / "bin/clang")!r}] + sys.argv[1:])\n')
    clang.chmod(0o755)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    llvm = Path(os.environ.get('IRONWOOD_LLVM_HOME') or Path(shutil.which('llvm-config')).resolve().parent.parent)
    sdk = Path(os.environ.get('IRONWOOD_TLS_HOME', ROOT / 'toolchain/ironwood-tls')).resolve()
    cli = ROOT / 'bin/ironwoodc'
    trace = OUT / 'commands.jsonl'
    trace.write_text('')
    wrapper = OUT / 'toolchain'
    prepare_toolchain_wrapper(llvm, wrapper, trace)
    env = os.environ | {'IRONWOOD_TLS_HOME': str(OUT / 'absent-sdk'), 'IRONWOOD_LLVM_HOME': str(wrapper)}
    def run(label, command, status=0, environment=env):
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env=environment, capture_output=True, text=True, timeout=180)
        (OUT / (label + '.log')).write_text(result.stdout + result.stderr)
        assert result.returncode == status, (label, result.returncode, result.stderr)
        return result
    plain = OUT / 'Plain.iron'
    plain.write_text('''// SPDX-License-Identifier: MIT OR Apache-2.0
import ironwood.net.Socket;
import ironwood.net.tls.TlsClient;
class Plain {
    static void unused() throws Exception {
        TlsClient value = new TlsClient();
        try { value.connect("localhost", 443, 1); } finally { try { value.close(); } finally { free value; } }
    }
    public static int main(String[] args) throws Exception {
        Socket value = new Socket();
        try { value.close(); return 42; } finally { free value; }
    }
}''')
    classes = OUT / 'classes'
    private_caller = OUT / 'Forge.iron'
    private_caller.write_text('''// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.net.tls;
class Forge { static long value() { return TlsClient.TlsNative.readByte(1L); } }
''')
    rejected = run('private-native-access', [cli, private_caller, '-d', OUT / 'rejected'], 1)
    assert 'not accessible' in rejected.stderr or 'private' in rejected.stderr
    run('plain-class-only', [cli, plain, '-d', classes, '--unfreed=error'])
    run('tls-class-only', [cli, ROOT / 'integration-tests/cases/tls_client/Client.iron', '-d', classes, '--unfreed=error'])
    assert trace.read_text() == '', 'class-only compilation invoked native tools'
    archive = OUT / 'callers.ironjar'
    run('archive', [ROOT / 'bin/ironjar', '--create', '--file', archive, classes])
    for kind, path in [('classes', classes), ('archive', archive)]:
        binary = OUT / ('plain-' + kind)
        run(kind + '-plain', [cli, '--link', '-cp', path, '--main-class', 'Plain', '-o', binary, '-O3'])
        run(kind + '-run', [binary], 42)
        symbols = run(kind + '-symbols', [llvm / 'bin/llvm-nm', binary]).stdout
        assert 'ironwood_tls_' not in symbols and '_SSL_' not in symbols
        assert b'-----BEGIN CERTIFICATE-----' not in binary.read_bytes()
    commands = [json.loads(line) for line in trace.read_text().splitlines()]
    assert commands and all(not any('ironwood_tls' in arg or 'libssl' in arg or str(sdk) in arg for arg in row) for row in commands)
    trace.write_text('')
    for kind, path in [('classes', classes), ('archive', archive)]:
        result = run(kind + '-missing-sdk', [cli, '--link', '-cp', path, '--main-class', 'Client', '-o', OUT / 'missing', '-O3'], 1)
        assert 'TLS dependency:' in result.stderr and 'IRONWOOD_TLS_HOME' in result.stderr
    assert trace.read_text() == '', 'invalid SDK reached native compilation'
    good = env | {'IRONWOOD_TLS_HOME': str(sdk)}
    for kind, path in [('classes', classes), ('archive', archive)]:
        run(kind + '-tls', [cli, '--link', '-cp', path, '--main-class', 'Client', '-o', OUT / ('tls-' + kind), '-O3'], environment=good)
    commands = [json.loads(line) for line in trace.read_text().splitlines()]
    assert any(any(arg.endswith('ironwood_tls.c') for arg in row) for row in commands)
    links = [row for row in commands if '--driver-mode=g++' in row]
    for row in links:
        assert row.index(str(sdk / 'lib/libssl.a')) < row.index(str(sdk / 'lib/libcrypto.a'))
        assert not any('force_load' in arg or 'whole-archive' in arg or arg == '-lssl' for arg in row)
    # Mutations are isolated copies; the prepared SDK is never modified by tests.
    with tempfile.TemporaryDirectory(prefix='sdk mismatch ', dir=OUT) as temporary:
        bad = Path(temporary)
        shutil.copytree(sdk, bad, dirs_exist_ok=True, copy_function=shutil.copy2)
        build = bad / 'build.properties'
        original = build.read_text(); build.unlink(); build.write_text(original.replace('platform=', 'platform=wrong-'))
        trace.write_text('')
        result = run('wrong-platform', [cli, '--link', '-cp', archive, '--main-class', 'Client', '-o', OUT / 'invalid'], 1,
                     good | {'IRONWOOD_TLS_HOME': str(bad)})
        assert 'wrong platform' in result.stderr and trace.read_text() == ''
        build.write_text(original)
        header = bad / 'include/openssl/configuration.h'
        contents = header.read_bytes(); header.unlink(); header.write_bytes(contents + b'\n/* changed */\n')
        result = run('changed-header', [cli, '--link', '-cp', archive, '--main-class', 'Client', '-o', OUT / 'invalid'], 1,
                     good | {'IRONWOOD_TLS_HOME': str(bad)})
        assert 'SDK checksum mismatch' in result.stderr and trace.read_text() == ''
        digest = hashlib.sha256(header.read_bytes()).hexdigest()
        import re
        build.write_text(re.sub(r'(?m)^sha256.include/openssl/configuration.h=.*$',
                               'sha256.include/openssl/configuration.h=' + digest, original))
        run('changed-build-identity', [cli, '--link', '-cp', archive, '--main-class', 'Client', '-o', OUT / 'changed', '-O3'],
            environment=good | {'IRONWOOD_TLS_HOME': str(bad)})
        assert 'ironwood_tls.c' in trace.read_text()
        probe_classes = OUT / 'probe-classes'
        run('probe-compile', ['javac', '--release', '21', '-cp', ROOT / 'compiler/build/ironwoodc.jar',
                             '-d', probe_classes, ROOT / 'compiler/src/test/java/ironwood/compiler/TlsBackendProbe.java'])
        run('source-and-cache', ['java', '-cp', str(probe_classes) + os.pathsep + str(ROOT / 'compiler/build/ironwoodc.jar'),
                                'ironwood.compiler.TlsBackendProbe', OUT, bad, trace, plain,
                                ROOT / 'integration-tests/cases/tls_client/Client.iron'],
            environment=good | {'IRONWOOD_TLS_HOME': str(bad)})
        run('source-plain-run', [OUT / 'Plain'], 42)

    if platform.system() == 'Darwin':
        dependencies = run('dependencies', ['otool', '-L', OUT / 'tls-archive']).stdout
    else:
        dependencies = run('dependencies', [llvm / 'bin/llvm-readelf', '-d', OUT / 'tls-archive']).stdout
        versions = run('glibc', [llvm / 'bin/llvm-readelf', '--version-info', OUT / 'tls-archive']).stdout
        import re
        assert all(tuple(map(int, v.split('.'))) <= (2, 17) for v in re.findall(r'Name: GLIBC_([0-9.]+)', versions))
    assert not any(name in dependencies.lower() for name in ('libssl.', 'libcrypto.'))
    print('PASS: class/archive pruning, absent/mismatched SDK, header identity, static closure and platform baseline')


if __name__ == '__main__':
    main()
