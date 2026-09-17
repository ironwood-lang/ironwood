#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused TLS smoke for a relocated host package or self-contained IDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('distribution', type=Path)
    parser.add_argument('--llvm-home', type=Path)
    parser.add_argument('--sdk', type=Path)
    args = parser.parse_args()
    root = args.distribution.resolve()
    idk = (root / 'toolchain/bin/clang').exists()
    llvm = (args.llvm_home or root / 'toolchain').resolve()
    sdk = (args.sdk or root / 'toolchain/ironwood-tls').resolve()
    example = root / 'examples/tls'
    env = os.environ.copy()
    for key in ('JAVA_HOME', 'IRONWOOD_RUNTIME_HOME', 'IRONWOOD_LLVM_HOME', 'IRONWOOD_TLS_HOME',
                'DYLD_INSERT_LIBRARIES', 'LD_PRELOAD'):
        env.pop(key, None)
    if not idk:
        env['IRONWOOD_LLVM_HOME'] = str(llvm)
        env['IRONWOOD_TLS_HOME'] = str(sdk)
    def run(command, status=0):
        result = subprocess.run(list(map(str, command)), cwd=example, env=env, capture_output=True, text=True, timeout=180)
        if result.returncode != status: raise AssertionError((command, result.returncode, result.stdout, result.stderr))
        return result.stdout
    for path in ('runtime/src/ironwood_tls.c', 'runtime/include/ironwood_tls.h', 'scripts/prepare-tls.py',
                 'packaging/tls-dependencies.properties', 'packaging/idk-environment.yml', 'LICENSES/MPL-2.0.txt',
                 'docs/TLS.md', 'docs/NETWORKING_M5_VERIFICATION.md'):
        assert (root / path).is_file(), path
    private_caller = example / 'target/Forge.iron'
    private_caller.parent.mkdir(parents=True, exist_ok=True)
    private_caller.write_text('''// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.net.tls;
class Forge { static long value() { return TlsClient.TlsNative.readByte(1L); } }
''')
    run([root / 'bin/ironwoodc', private_caller, '-d', example / 'target/rejected'], 1)
    def properties(path):
        return dict(line.split('=', 1) for line in path.read_text().splitlines() if line and not line.startswith('#'))
    pins = properties(root / 'packaging/tls-dependencies.properties')
    build = properties(sdk / 'build.properties')
    assert properties(sdk / 'dependencies.properties') == pins
    for key, digest in build.items():
        if key.startswith('sha256.'):
            assert hashlib.sha256((sdk / key[7:]).read_bytes()).hexdigest() == digest, key
    assert hashlib.sha256((sdk / 'share/cacert.pem').read_bytes()).hexdigest() == pins['ca.sha256']
    if idk:
        inventory = (root / 'THIRD-PARTY-PACKAGES.tsv').read_text().splitlines()
        for key, name in (('openssl', 'openssl-static'), ('ca', 'mozilla-ca-bundle')):
            assert '\t'.join((name, pins[key + '.version'], pins[key + '.license'], pins[key + '.url'])) in inventory
    run([root / 'bin/ironwoodc', example / 'src/main/ironwood/org/ironwood/tls/TlsEcho.iron',
         '-d', example / 'target/classes', '--unfreed=error'])
    run([root / 'bin/ironwoodc', '--link', '-cp', example / 'target/classes', '--main-class', 'org.ironwood.tls.TlsEcho',
         '-o', example / 'target/TlsEcho', '-O3', '--unfreed=error'])
    # These deliberately unusable ambient paths cannot influence a custom-root client.
    env.update({'OPENSSL_CONF': '/nonexistent/ironwood-openssl.cnf', 'OPENSSL_MODULES': '/nonexistent/ironwood-providers',
                'SSL_CERT_FILE': '/nonexistent/ironwood-ca', 'SSL_CERT_DIR': '/nonexistent/ironwood-ca-dir'})
    if idk: env['PATH'] = str(root / 'toolchain/bin') + os.pathsep + env.get('PATH', '')
    print(run([sys.executable, example / 'peer.py']).strip())
    binary = example / 'target/TlsEcho'
    if platform.system() == 'Darwin':
        dependencies = run(['/usr/bin/otool', '-L', binary])
    else:
        dependencies = run([llvm / 'bin/llvm-readelf', '-d', binary])
        versions = run([llvm / 'bin/llvm-readelf', '--version-info', binary])
        assert all(tuple(map(int, v.split('.'))) <= (2, 17) for v in re.findall(r'Name: GLIBC_([0-9.]+)', versions))
    assert not any(name in dependencies.lower() for name in ('libssl.', 'libcrypto.'))
    (example / 'target/dependencies.txt').write_text(dependencies)
    print('PASS: relocated TLS source, SDK identity, notices, local peer, static dependencies and baseline')


if __name__ == '__main__':
    main()
