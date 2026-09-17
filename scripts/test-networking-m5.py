#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Local TLS certificate, policy, proxy and cleanup contracts. No public hosts."""
import argparse
import base64
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import ssl
import struct
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'integration-tests/target/networking-m5'
CLI = ROOT / 'bin/ironwoodc'
REPORT = {'platform': platform.platform(), 'cases': {}}


def run(name, command, expected=0, env=None):
    result = subprocess.run(list(map(str, command)), cwd=ROOT, env=env, capture_output=True, text=True, timeout=120)
    (OUT / (name + '.log')).write_text(result.stdout + result.stderr)
    assert expected is None or result.returncode == expected, (
        f'{name}: exit {result.returncode}; see {OUT / (name + ".log")}\n{result.stdout}{result.stderr}')
    return result


def certificates(revocation_port):
    certs = OUT / 'certificates'
    certs.mkdir(exist_ok=True)
    def openssl(*args):
        run('certificate-generation', ['openssl', *args])
    for root in ('a', 'b'):
        openssl('req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
                '-keyout', certs / (root + '.key'), '-out', certs / (root + '.pem'), '-days', '30',
                '-subj', '/CN=Ironwood Test Root ' + root, '-addext', 'basicConstraints=critical,CA:TRUE',
                '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
    (certs / 'index').write_text('')
    (certs / 'serial').write_text('01\n')
    (certs / 'newcerts').mkdir(exist_ok=True)
    configuration = f'''[ca]
default_ca=local
[local]
database={certs}/index
serial={certs}/serial
new_certs_dir={certs}/newcerts
certificate={certs}/a.pem
private_key={certs}/a.key
default_md=sha256
default_days=2
default_crl_days=2
policy=policy
unique_subject=no
[policy]
commonName=supplied
'''
    (certs / 'ca.cnf').write_text(configuration)
    for name, anchor, purpose, expired in [('valid', 'a', 'serverAuth', False), ('other', 'b', 'serverAuth', False),
                                           ('expired', 'a', 'serverAuth', True), ('client', 'a', 'clientAuth', False),
                                           ('revoked', 'a', 'serverAuth', False)]:
        openssl('req', '-new', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
                '-keyout', certs / (name + '.key'), '-out', certs / (name + '.csr'), '-subj', '/CN=localhost')
        (certs / (name + '.ext')).write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n'
                + f'extendedKeyUsage={purpose}\nsubjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1\n'
                + f'crlDistributionPoints=URI:http://127.0.0.1:{revocation_port}/revoked.crl\nauthorityInfoAccess=OCSP;URI:http://127.0.0.1:{revocation_port}/ocsp\n')
        if anchor == 'a':
            dates = ['-startdate', '200101000000Z', '-enddate', '210101000000Z'] if expired else []
            openssl('ca', '-batch', '-notext', '-config', certs / 'ca.cnf', '-in', certs / (name + '.csr'),
                    '-out', certs / (name + '.pem'), '-extfile', certs / (name + '.ext'), *dates)
        else:
            openssl('x509', '-req', '-in', certs / (name + '.csr'), '-CA', certs / (anchor + '.pem'),
                    '-CAkey', certs / (anchor + '.key'), '-CAcreateserial', '-out', certs / (name + '.pem'),
                    '-days', '2', '-extfile', certs / (name + '.ext'))
    openssl('ca', '-batch', '-config', certs / 'ca.cnf', '-revoke', certs / 'revoked.pem')
    openssl('ca', '-gencrl', '-config', certs / 'ca.cnf', '-out', certs / 'revoked.crl')
    (certs / 'empty.pem').write_text('')
    (certs / 'invalid.pem').write_text('invalid certificate data\n')
    (certs / 'mixed.pem').write_bytes((certs / 'a.pem').read_bytes() + b'invalid trailing content\n')
    (certs / 'combined.pem').write_bytes((certs / 'a.pem').read_bytes() + (certs / 'b.pem').read_bytes())
    return certs


def exact(peer, count):
    result = b''
    while len(result) < count:
        data = peer.recv(count - len(result))
        if not data:
            raise EOFError('incomplete fixture message')
        result += data
    return result


def cstring(peer):
    result = b''
    while True:
        byte = exact(peer, 1)
        if byte == b'\0': return result
        result += byte
        assert len(result) < 512


def hello_extensions(data):
    # Handshake header, version, random and three length-prefixed fields.
    cursor = 4 + 2 + 32
    cursor += 1 + data[cursor]
    length = int.from_bytes(data[cursor:cursor + 2], 'big'); cursor += 2 + length
    cursor += 1 + data[cursor]
    total = int.from_bytes(data[cursor:cursor + 2], 'big'); cursor += 2
    end = cursor + total
    extensions = {}
    while cursor < end:
        kind, length = struct.unpack('!HH', data[cursor:cursor + 4]); cursor += 4
        extensions[kind] = data[cursor:cursor + length]; cursor += length
    return extensions


def exercise(name, certs, *, certificate='valid', trust='a.pem', identity='localhost', kind='direct',
             mode='success', version=ssl.TLSVersion.TLSv1_3, rounds=2, transfers=3, ambient=False, native_mode=None, diagnostic=None, allocation_limit=None, owned=False, hostname=False):
    listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(); listener.settimeout(.1)
    port = listener.getsockname()[1]
    stop = threading.Event()
    observations = {'handshakes': [], 'hellos': [], 'tickets': 0, 'proxy': [], 'connections': 0}
    errors = []
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = context.maximum_version = version
    context.load_cert_chain(certs / (certificate + '.pem'), certs / (certificate + '.key'))
    def messages(connection, direction, tls_version, content, message, data):
        if direction == 'read' and int(content) == 22 and int(message) == 1:
            extensions = hello_extensions(data)
            assert 41 not in extensions and 42 not in extensions, 'resumption PSK or early data offered'
            sni = extensions.get(0)
            if ':' in identity or all(ch in '0123456789.' for ch in identity):
                assert sni is None, 'numeric peer sent SNI'
            else:
                assert sni and sni[5:].decode() == identity, ('wrong SNI', sni)
            observations['hellos'].append(sorted(extensions))
        if direction == 'write' and int(content) == 22 and int(message) == 4:
            observations['tickets'] += 1
    context._msg_callback = messages
    def proxy(peer):
        if kind == 'socks':
            assert exact(peer, 3) == b'\x05\x01\x02'
            peer.sendall(b'\x05\x02')
            assert exact(peer, 1) == b'\x01'
            user = exact(peer, exact(peer, 1)[0]); password = exact(peer, exact(peer, 1)[0])
            assert user == b'u\xc3\xa9' and password == b'p:\xff'
            peer.sendall(b'\x01\x00')
            if hostname:
                assert exact(peer, 4) == b'\x05\x01\x00\x03'
                assert exact(peer, exact(peer, 1)[0]) == identity.encode('ascii')
                assert exact(peer, 2) == struct.pack('!H', port)
            else:
                assert exact(peer, 4) == b'\x05\x01\x00\x01'
                assert exact(peer, 6) == socket.inet_aton('127.0.0.1') + struct.pack('!H', port)
            peer.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x50')
        elif kind == 'socks4':
            assert exact(peer, 8) == b'\x04\x01' + struct.pack('!H', port) + socket.inet_aton('127.0.0.1')
            assert cstring(peer) == b'u\xc3\xa9'
            peer.sendall(b'\x00\x5a\x00\x50\x7f\x00\x00\x01')
        elif kind == 'http':
            head = b''
            while not head.endswith(b'\r\n\r\n'): head += exact(peer, 1)
            target = identity if hostname else '127.0.0.1'
            assert head.startswith(f'CONNECT {target}:{port} HTTP/1.1\r\n'.encode())
            assert b'Proxy-Authorization: Basic ' + base64.b64encode(b'u\xc3\xa9:p:\xff') in head
            peer.sendall(b'HTTP/1.1 103 Hint\r\n\r\nHTTP/1.1 200 Connected\r\nContent-Length: 99\r\n\r\n')
        observations['proxy'].append(kind)
    def serve():
        while not stop.is_set():
            try: peer, _ = listener.accept()
            except socket.timeout: continue
            except OSError: break
            observations['connections'] += 1
            try:
                peer.settimeout(5)
                proxy(peer)
                if mode == 'handshake-timeout':
                    stop.wait(.6); peer.close(); continue
                with context.wrap_socket(peer, server_side=True) as stream:
                    observations['handshakes'].append({'version': stream.version(), 'reused': stream.session_reused})
                    assert not stream.session_reused
                    if mode in ('read-timeout', 'write-timeout'):
                        stop.wait(.7); continue
                    assert exact(stream, 1) == b'*'; stream.sendall(b'*')
                    for transfer in range(transfers + 1):
                        if transfer > 0:
                            assert exact(stream, 1) == b'*'; stream.sendall(b'*')
                        payload = exact(stream, 257)
                        assert payload == bytes(range(256)) + b'\0'
                        stream.sendall(payload)
                    if mode == 'truncation':
                        socket.socket(fileno=stream.detach()).close()
                    elif mode == 'eof':
                        stream.unwrap().close()
                    else:
                        assert stream.recv(1) == b''
            except (ssl.SSLError, EOFError, ConnectionResetError, BrokenPipeError) as error:
                if allocation_limit is None and mode not in ('failure', 'truncation', 'write-timeout', 'read-timeout', 'fault-timeout'): errors.append(repr(error))
                peer.close()
            except Exception as error:
                errors.append(repr(error)); peer.close()
    thread = threading.Thread(target=serve); thread.start()
    env = os.environ.copy()
    if allocation_limit is not None: env['IRONWOOD_ALLOCATION_LIMIT'] = str(allocation_limit)
    if diagnostic:
        env['DYLD_INSERT_LIBRARIES' if platform.system() == 'Darwin' else 'LD_PRELOAD'] = str(diagnostic)
        if native_mode: env['IRONWOOD_TEST_TLS_MODE'] = native_mode
    if ambient:
        env.update({'SSL_CERT_FILE': str(certs / 'a.pem'), 'SSL_CERT_DIR': str(certs),
                    'OPENSSL_CONF': str(certs / 'ambient.cnf'), 'OPENSSL_MODULES': '/nonexistent/ironwood-modules'})
        (certs / 'ambient.cnf').write_text('openssl_conf=unsafe\n[unsafe]\nssl_conf=ssl\n[ssl]\nsystem_default=system\n[system]\nVerifyMode=None\n')
    try:
        trust_path = 'bundled' if trust == 'bundled' else str(certs / trust)
        arguments = [OUT / ('TlsOwned' if owned else 'TlsProbe'), kind, trust_path, port, port,
                     identity, mode, rounds, transfers]
        if hostname: arguments.append('hostname')
        result = run(name, arguments, None if owned else 42, env)
        if owned:
            assert result.returncode in (1, 42) and 'TLS_LEAK' not in result.stdout, (name, result.stdout, result.stderr)
            if result.returncode == 1: assert 'TLS_READY' not in result.stdout, (name, result.stderr)
            assert not errors, (name, errors)
            REPORT['cases'][name] = observations | {'output': result.stdout.splitlines()}
            return result
        if mode == 'failure' or mode == 'truncation': assert 'TLS_REJECTED' in result.stdout
        elif mode.endswith('timeout'): assert 'TLS_TIMEOUT' in result.stdout
        else:
            assert len(observations['handshakes']) == rounds, observations
            assert 'TLS_IO ' in result.stdout
        assert not errors, (name, errors)
        counts = [{key: int(value) for key, value in re.findall(r'(\w+)=(\d+)', row)}
                  for row in re.findall(r'TLS_COUNT steady (.*)', result.stderr)]
        if diagnostic:
            retained = list(map(int, re.findall(r'TLS_HEAP retained=(-?\d+)', result.stderr)))
            assert len(retained) == rounds and all(value == 0 for value in retained[1:]), retained
            observations['native_retained'] = retained
            assert 'TLS_FDS opened=' in result.stderr
        if counts and name.startswith('benchmark'):
            assert all(row['control'] == 0 and row['alloc'] == 8 * transfers for row in counts), counts
            if mode == 'untimed': assert all(row['clock'] == 2 for row in counts), counts
        REPORT['cases'][name] = observations | {'output': result.stdout.splitlines(), 'native': counts}
    finally:
        stop.set(); listener.close(); thread.join(6)
        assert not thread.is_alive(), f'{name}: fixture peer did not terminate'
        assert not errors, (name, errors)


def native_evidence(tool_home, certs):
    sdk = Path(os.environ.get('IRONWOOD_TLS_HOME', ROOT / 'toolchain/ironwood-tls'))
    manifest = dict(line.split('=', 1) for line in (sdk / 'build.properties').read_text().splitlines())
    clang = tool_home / 'bin/clang'
    flags = json.loads(manifest['compile.flags'])
    includes = ['-I', sdk / 'include', '-I', sdk / 'share']
    binary = OUT / 'allocation-contracts'
    run('native-allocation-compile', [clang, '-std=c11', '-Wall', '-Wextra', '-Werror', *flags, *includes,
         ROOT / 'integration-tests/native/tls_allocation_contracts.c', ROOT / 'runtime/src/ironwood_tcp.c',
         sdk / 'lib/libssl.a', sdk / 'lib/libcrypto.a', '-o', binary])
    result = run('native-allocation', [binary, certs / 'a.pem', manifest['ca.certificates']])
    REPORT['native_allocation'] = result.stdout.strip()
    objdump = tool_home / 'bin/llvm-objdump'
    assert objdump.is_file(), 'selected LLVM toolchain must provide llvm-objdump'
    run('disassembly', [objdump, '--disassemble', '--no-show-raw-insn', OUT / 'TlsProbe'])
    run('adapter-assembly', [clang, '-std=c11', *flags, *includes, '-S', ROOT / 'runtime/src/ironwood_tls.c', '-o', OUT / 'tls.s'])
    print(result.stdout.strip(), flush=True)
    controlled = OUT / 'controlled-default'; controlled.mkdir(exist_ok=True)
    (controlled / 'ironwood_ca_data.h').write_text('/* SPDX-License-Identifier: MIT OR Apache-2.0 */\n'
                                                'static const char ironwood_ca_pem[] = ' + json.dumps((certs / 'a.pem').read_text()) + ';\n')
    trust_binary = OUT / 'trust-contracts'
    run('trust-compile', [clang, '-std=c11', *flags, '-I', controlled, *includes,
                         ROOT / 'integration-tests/native/tls_trust_contracts.c', ROOT / 'runtime/src/ironwood_tcp.c',
                         sdk / 'lib/libssl.a', sdk / 'lib/libcrypto.a', '-o', trust_binary])
    trust = run('trust-contracts', [trust_binary, certs / 'b.pem', certs / 'valid.pem', certs / 'other.pem'],
                env=os.environ | {'SSL_CERT_FILE': str(certs / 'b.pem'), 'SSL_CERT_DIR': str(certs),
                                  'OPENSSL_CONF': '/nonexistent/ironwood.cnf', 'OPENSSL_MODULES': '/nonexistent/modules'})
    REPORT['controlled_trust'] = trust.stdout.strip()
    print(trust.stdout.strip(), flush=True)



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--case')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if not args.skip_build:
        classes = OUT / 'classes'
        run('compile', [CLI, ROOT / 'integration-tests/cases/tls_client/Probe.iron', '-d', classes, '--unfreed=error'])
        run('link', [CLI, '--link', '-cp', classes, '--main-class', 'TlsProbe', '-o', OUT / 'TlsProbe', '-O3',
                     '--unfreed=error', '--emit-llvm', OUT / 'TlsProbe.ll'])
    if not args.skip_build:
        owned_classes = OUT / 'owned-classes'
        run('owned-compile', [CLI, ROOT / 'integration-tests/cases/tls_client/Owned.iron', '-d', owned_classes, '--unfreed=error'])
        run('owned-link', [CLI, '--link', '-cp', owned_classes, '--main-class', 'TlsOwned', '-o', OUT / 'TlsOwned', '-O3', '--unfreed=error'])
    # A real listener appears in signed CRL/OCSP URLs. Any fetch makes it readable.
    revocation = socket.socket(); revocation.bind(('127.0.0.1', 0)); revocation.listen()
    certs = certificates(revocation.getsockname()[1])
    tool_home = Path(os.environ.get('IRONWOOD_LLVM_HOME') or Path(shutil.which('llvm-config')).resolve().parent.parent)
    clang = tool_home / 'bin/clang'
    diagnostic = OUT / ('tls_interpose.dylib' if platform.system() == 'Darwin' else 'tls_interpose.so')
    command = [clang, '-std=c11', '-O3', '-fPIC', '-dynamiclib' if platform.system() == 'Darwin' else '-shared',
               ROOT / 'integration-tests/native/tls_interpose.c', '-o', diagnostic]
    if platform.system() != 'Darwin': command.append('-ldl')
    run('interposer-build', command)
    cases = {
        'tls13-full-handshakes': {'rounds': 30, 'diagnostic': diagnostic},
        'tls12-full-handshakes': {'version': ssl.TLSVersion.TLSv1_2, 'rounds': 30, 'diagnostic': diagnostic},
        'numeric-peer': {'identity': '127.0.0.1'},
        'numeric-ipv6-peer': {'identity': '::1'},
        'wrong-numeric-peer': {'identity': '127.0.0.2', 'mode': 'failure'},
        'hostname-direct': {'hostname': True},
        'hostname-socks5': {'hostname': True, 'kind': 'socks'},
        'hostname-http': {'hostname': True, 'kind': 'http'},
        'wrong-host': {'identity': 'wrong.invalid', 'mode': 'failure'},
        'untrusted': {'trust': 'b.pem', 'mode': 'failure'},
        'expired': {'certificate': 'expired', 'mode': 'failure'},
        'wrong-purpose': {'certificate': 'client', 'mode': 'failure'},
        'custom-root-b': {'certificate': 'other', 'trust': 'b.pem'},
        'explicit-combined-roots': {'certificate': 'other', 'trust': 'combined.pem'},
        'revoked-leaf-without-revocation-checking': {'certificate': 'revoked'},
        'empty-bundle': {'trust': 'empty.pem', 'mode': 'failure'},
        'invalid-bundle': {'trust': 'invalid.pem', 'mode': 'failure'},
        'mixed-bundle': {'trust': 'mixed.pem', 'mode': 'failure'},
        'unreadable-bundle': {'trust': 'missing.pem', 'mode': 'failure'},
        'bundled-rejects-private-root': {'trust': 'bundled', 'mode': 'failure'},
        'ambient-trust-ignored': {'trust': 'bundled', 'mode': 'failure', 'ambient': True},
        'handshake-timeout': {'mode': 'handshake-timeout'},
        'read-timeout': {'mode': 'read-timeout'},
        'write-timeout': {'mode': 'write-timeout'},
        'truncated-record-stream': {'mode': 'truncation'},
        'authenticated-eof': {'mode': 'eof'},
        'input-close': {'mode': 'input-close'},
        'output-close': {'mode': 'output-close'},
        'socks5-authenticated-tls': {'kind': 'socks'},
        'socks4-tls': {'kind': 'socks4'},
        'http-basic-tls': {'kind': 'http'},
        'benchmark': {'transfers': 1000, 'rounds': 3, 'diagnostic': diagnostic},
        'benchmark-untimed': {'transfers': 1000, 'rounds': 3, 'mode': 'untimed', 'diagnostic': diagnostic},
        'benchmark-untraced': {'transfers': 1000, 'rounds': 3},
        'benchmark-untimed-untraced': {'transfers': 1000, 'rounds': 3, 'mode': 'untimed'},
    }
    for fault in ('short-read', 'short-write', 'read-eintr', 'write-eintr', 'read-would-block', 'write-would-block', 'poll-eintr'):
        cases['native-' + fault] = {'native_mode': fault, 'diagnostic': diagnostic, 'rounds': 1}
    cases['native-reset-and-close-error'] = {'native_mode': 'read-error', 'mode': 'failure', 'diagnostic': diagnostic, 'rounds': 1}
    cases['native-deadline'] = {'native_mode': 'write-deadline', 'mode': 'fault-timeout', 'diagnostic': diagnostic, 'rounds': 1}
    if args.case and args.case not in ('native-evidence', 'managed-oom') and args.case not in cases: parser.error('unknown case: ' + args.case)
    for name, case in cases.items():
        if args.case and args.case != name: continue
        exercise(name, certs, **case)
        print('PASS', name, flush=True)
    if not args.case or args.case == 'managed-oom':
        for kind in ('direct', 'socks', 'http'):
            for limit in range(120):
                result = exercise(f'oom-{kind}-{limit}', certs, kind=kind, rounds=1, transfers=0,
                                  allocation_limit=limit, owned=True, diagnostic=diagnostic)
                if 'TLS_IO complete' in result.stdout:
                    print(f'PASS: {kind} managed allocation failures through {limit}, including wrappers', flush=True)
                    break
            else: raise AssertionError('managed allocation sweep did not complete: ' + kind)
    if not args.case or args.case == 'native-evidence': native_evidence(tool_home, certs)
    import select
    assert not select.select([revocation], [], [], 0)[0], 'unexpected CRL/OCSP request'
    revocation.close()
    REPORT['revocation_fetches'] = 0
    (OUT / 'report.json').write_text(json.dumps(REPORT, indent=2) + '\n')
    print(f"PASS: {len(REPORT['cases'])} local TLS scenarios")


if __name__ == '__main__':
    main()
