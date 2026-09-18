#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Deterministic local downloader contracts; no public network acceptance tests."""
import argparse
import base64
import json
import os
from pathlib import Path
import platform
import re
import resource
import shutil
import signal
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / 'projects/wget'
OUT = ROOT / 'integration-tests/target/networking-m6'
CLI = ROOT / 'bin/ironwoodc'
REPORT = {'platform': platform.platform(), 'cases': {}}
BINARY = PROJECT / 'target/wget'
PROGRESS_INTERVAL = 10


def progress(message):
    print(message, file=sys.stderr, flush=True)


def stage(name, action):
    progress('RUN - ' + name)
    start = time.monotonic()
    result = action()
    progress(f'ok - {name} ({time.monotonic() - start:.1f}s)')
    return result


def run(name, command, status=0, env=None, cwd=None, low_fds=False, announce=False, timeout=180):
    command = list(map(str, command))
    log = OUT / (name + '.log')
    start = time.monotonic()
    if announce: progress('RUN - ' + name)
    with subprocess.Popen(command, cwd=cwd or ROOT, env=env, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, start_new_session=True,
                          preexec_fn=(lambda: resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))) if low_fds else None) as process:
        try:
            while True:
                remaining = timeout - (time.monotonic() - start)
                if remaining <= 0: raise TimeoutError(f'{name}: exceeded {timeout}s; see {log}')
                try:
                    stdout, stderr = process.communicate(timeout=min(PROGRESS_INTERVAL, remaining))
                    break
                except subprocess.TimeoutExpired as pending:
                    log.write_bytes((pending.output or b'') + (pending.stderr or b''))
                    progress(f'WAIT - {name} ({time.monotonic() - start:.0f}s; limit {timeout}s); log: {log}')
        except BaseException:
            # Native compilation has descendants. Stop the whole command group
            # on cancellation/timeout so LLVM cannot keep writing build output.
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            stdout, stderr = process.communicate()
            log.write_bytes(stdout + stderr)
            progress(f'STOP - {name}; log: {log}')
            raise
    log.write_bytes(stdout + stderr)
    assert status is None or process.returncode == status, (
        f'{name}: exit {process.returncode}; see {log}\n{stderr.decode(errors="replace")}')
    if announce: progress(f'ok - {name} ({time.monotonic() - start:.1f}s)')
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


def llvm_home():
    # Match launcher priority, then the compiler's LLVM 23 discovery conventions.
    bundled = ROOT / 'toolchain'
    override = os.environ.get('IRONWOOD_LLVM_HOME', '').strip()
    explicit = (bundled / 'lib/jvm/bin/java').is_file() or bool(override)
    if (bundled / 'lib/jvm/bin/java').is_file(): candidates = [bundled]
    elif override: candidates = [Path(override)]
    else:
        candidates = []
        if shutil.which('brew'):
            for formula in ('llvm@23', 'llvm'):
                result = subprocess.run(['brew', '--prefix', formula], capture_output=True, text=True, timeout=10)
                if result.returncode == 0 and result.stdout.strip():
                    candidates.append(Path(result.stdout.strip()))
                    break
        candidates += [Path('/opt/homebrew/opt/llvm'), Path('/usr/local/opt/llvm'), Path('/usr/lib/llvm-23')]
        for name in ('llvm-config-23', 'llvm-config'):
            executable = shutil.which(name)
            if executable: candidates.append(Path(executable).resolve().parent.parent)
    for home in candidates:
        if not all(os.access(home / 'bin' / tool, os.X_OK) for tool in ('clang', 'llvm-config', 'llvm-objdump')):
            continue
        version = subprocess.run([home / 'bin/llvm-config', '--version'], capture_output=True, text=True, timeout=10)
        if version.returncode == 0 and version.stdout.strip().startswith('23.'):
            return home.resolve()
    source = f'selected prefix {candidates[0]}' if explicit else 'bundled, Homebrew and PATH installations'
    raise AssertionError(f'No LLVM 23 clang/llvm-objdump in {source}; set IRONWOOD_LLVM_HOME to a complete LLVM 23 installation')


def build():
    progress('Building six executables at -O3 before running local tests; this can take a few minutes.')
    source = PROJECT / 'src/main/ironwood'
    tests = PROJECT / 'src/test/ironwood'
    classes = PROJECT / 'target/classes'
    run('compile', [CLI, '--source-path', source, '-d', classes, '--unfreed=error',
                    source / 'org/ironwood/wget/Wget.iron'], announce=True)
    for name in ('Wget', 'UrlTests', 'ResponseProbe', 'LifecycleProbe', 'TransferProbe', 'OomProbe'):
        if name != 'Wget':
            run('compile-' + name, [CLI, '--source-path', str(source) + os.pathsep + str(tests),
                '-d', classes, '--unfreed=error', tests / ('org/ironwood/wget/' + name + '.iron')], announce=True)
        run('link-' + name, [CLI, '--link', '-cp', classes, '--main-class', 'org.ironwood.wget.' + name,
            '-o', PROJECT / ('target/' + ('wget' if name == 'Wget' else name)), '-O3', '--unfreed=error',
            '--emit-llvm', OUT / (name + '.ll')], announce=True)


def certificates():
    certs = OUT / 'certificates'
    certs.mkdir(exist_ok=True)
    fixture_env = os.environ.copy()
    for key in ('OPENSSL_CONF', 'OPENSSL_MODULES', 'SSL_CERT_FILE', 'SSL_CERT_DIR'):
        fixture_env.pop(key, None)
    for name in ('trusted', 'other'):
        run('certificate-' + name, ['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt',
            'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', certs / (name + '.key'),
            '-out', certs / (name + '.pem'), '-days', '2', '-subj', '/CN=localhost',
            '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1',
            '-addext', 'basicConstraints=critical,CA:TRUE'], env=fixture_env)
    return certs


def exact(peer, length):
    result = b''
    while len(result) < length:
        data = peer.recv(length - len(result))
        if not data: raise EOFError()
        result += data
    return result


def head(peer):
    result = b''
    while not result.endswith(b'\r\n\r\n'):
        result += exact(peer, 1)
        assert len(result) <= 131072
    return result


class Server:
    def __init__(self, handler, *, tls=False, proxy=None, ipv6=False, reject_proxy=False):
        self.handler, self.tls, self.proxy = handler, tls, proxy
        self.listener = socket.socket(socket.AF_INET6 if ipv6 else socket.AF_INET)
        self.listener.bind(('::1' if ipv6 else '0.0.0.0', 0))
        self.listener.listen(32)
        self.listener.settimeout(.05)
        self.port = self.listener.getsockname()[1]
        self.stop = threading.Event()
        self.requests, self.routes, self.sessions, self.sni, self.errors = [], [], [], [], []
        self.connections = 0
        self.reject_proxy = reject_proxy
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.load_cert_chain(CERTS / 'trusted.pem', CERTS / 'trusted.key')
        self.context.set_servername_callback(lambda stream, name, context: self.sni.append(name))
        self.thread = threading.Thread(target=self.serve)
        self.thread.start()

    def route(self, peer):
        if self.proxy == 'http':
            request = head(peer)
            self.routes.append(request)
            if self.reject_proxy:
                peer.sendall(b'HTTP/1.1 407 Authentication Required\r\nContent-Length: 0\r\n\r\n')
                return False
            peer.sendall(b'HTTP/1.1 103 Hint\r\n\r\nHTTP/1.1 200 Connected\r\nContent-Length: 999\r\n\r\n')
        elif self.proxy == 'socks5':
            assert exact(peer, 3) == b'\x05\x01\x02'
            peer.sendall(b'\x05\x02')
            assert exact(peer, 1) == b'\x01'
            user = exact(peer, exact(peer, 1)[0]); password = exact(peer, exact(peer, 1)[0])
            assert user == b'u\xc3\xa9' and password == b'p:\xff'
            peer.sendall(b'\x01\x00')
            request = exact(peer, 4)
            assert request[:3] == b'\x05\x01\x00'
            if request[3] == 3: address = exact(peer, exact(peer, 1)[0])
            else: address = exact(peer, 4 if request[3] == 1 else 16)
            port = int.from_bytes(exact(peer, 2), 'big')
            self.routes.append((address.decode('ascii') if request[3] == 3 else address.hex(), port))
            peer.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x50')
        elif self.proxy == 'socks4':
            request = exact(peer, 8)
            user = b''
            while True:
                byte = exact(peer, 1)
                if byte == b'\0': break
                user += byte
            assert request[:2] == b'\x04\x01' and request[4:] == socket.inet_aton('127.0.0.1')
            assert user == b'u\xc3\xa9'
            self.routes.append((user.hex(), int.from_bytes(request[2:4], 'big')))
            peer.sendall(b'\x00\x5a\x00\x50\x7f\x00\x00\x01')
        return True

    def serve(self):
        while not self.stop.is_set():
            try: peer, _ = self.listener.accept()
            except socket.timeout: continue
            except OSError: break
            self.connections += 1
            try:
                peer.settimeout(3)
                if not self.route(peer): continue
                if self.tls:
                    peer = self.context.wrap_socket(peer, server_side=True)
                    self.sessions.append(peer.session_reused)
                request = head(peer)
                self.requests.append(request)
                assert request.startswith(b'GET ') and request.endswith(b'\r\n\r\n')
                assert b'\r\nConnection: close\r\n' in request
                assert b'\r\nAccept-Encoding: identity\r\n' in request
                assert b'Authorization:' not in request and b'#' not in request.split(b'\r\n')[0]
                self.handler(peer, request, len(self.requests) - 1, self)
            except (OSError, EOFError):
                pass  # Expected for rejected heads, client deadlines and OOM.
            except Exception as error:
                self.errors.append(repr(error))
            finally:
                peer.close()

    def close(self):
        self.stop.set()
        self.listener.close()
        self.thread.join(5)
        assert not self.thread.is_alive() and not self.errors, self.errors


def wire(data, *, pieces=None, delay=0, hold=0, clean_tls=False, reset=False):
    def send(peer, request, index, server):
        parts = pieces if pieces is not None else [data]
        for part in parts:
            peer.sendall(part)
            if delay: server.stop.wait(delay)
        if hold: server.stop.wait(hold)
        if clean_tls and isinstance(peer, ssl.SSLSocket): peer.unwrap().close()
        if reset: peer.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
    return send


def case(name, response=None, *, handler=None, status=0, body=b'hello', tls=False, proxy=None,
         host=None, path='/', options=(), connections=1, reject_proxy=False, ipv6=False,
         trust='trusted.pem', binary=None, env=None, output=None, low_fds=False):
    server = Server(handler or wire(response), tls=tls, proxy=proxy, ipv6=ipv6, reject_proxy=reject_proxy)
    try:
        args = [binary or BINARY, '--connect-timeout', '2000', '--head-timeout', '1000', '--read-timeout', '500']
        # Override defaults without asking the CLI to accept repeated options.
        for option in ('--connect-timeout', '--head-timeout', '--read-timeout'):
            if option in options:
                index = args.index(option); del args[index:index + 2]
        if tls: args += ['--ca-bundle', CERTS / trust]
        if proxy:
            args += ['--proxy', f'{proxy}://127.0.0.1:{server.port}']
            if proxy == 'socks4': args += ['--socks4-user-file', OUT / 'user']
            else: args += ['--proxy-user-file', OUT / 'user', '--proxy-password-file', OUT / 'password']
        host = host or ('[::1]' if ipv6 else '127.0.0.1')
        args += list(options) + [f'{"https" if tls else "http"}://{host}:{server.port}{path}']
        start = time.monotonic()
        result = run(name, args, status, env=env, low_fds=low_fds)
        elapsed = time.monotonic() - start
        if body is not None: assert result.stdout == body, (name, result.stdout[:200], body[:200])
        if output is not None: assert Path(output).read_bytes() == body
        if connections is not None: assert server.connections == connections, (name, server.connections, connections)
        if server.requests:
            target = path.split('#', 1)[0]
            if not target or target.startswith('?'): target = '/' + target
            assert server.requests[0].startswith(f'GET {target} HTTP/1.1\r\n'.encode())
            assert f'\r\nHost: {host}:{server.port}\r\n'.encode() in server.requests[0]
        assert not any(server.sessions), 'TLS session reuse'
        for route in server.routes:
            if isinstance(route, bytes):
                assert b'Proxy-Authorization: Basic ' + base64.b64encode(b'u\xc3\xa9:p:\xff') in route
        REPORT['cases'][name] = {'connections': server.connections, 'requests': len(server.requests),
                                 'tls_handshakes': len(server.sessions), 'seconds': elapsed,
                                 'status': result.returncode, 'bytes': len(result.stdout)}
        return result, server
    finally:
        server.close()


def protocol_cases():
    ok = b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello'
    case('length', ok)
    case('empty-query-fragment', ok, path='?raw=%2F#not-sent')
    case('ipv6-host', ok, ipv6=True)
    for name, fields in {
        'duplicate-length': b'Content-Length: 5\r\ncontent-length: 005',
        'list-length': b'Content-Length: 5, 005, 5',
        'fold-length': b'Content-Length:\r\n \t5',
        'mixed-case': b'cOnTeNt-LeNgTh: 5\r\nContent-Encoding: Identity',
    }.items(): case(name, b'HTTP/1.1 200 \r\n' + fields + b'\r\n\r\nhello')
    case('close-delimited', b'HTTP/1.0 200 OK\r\n\r\nhello')
    case('chunk-extensions-trailers', b'HTTP/1.1 200 OK\r\nTransfer-Encoding: ChUnKeD\r\n\r\n'
         b'2; flag; quoted="a;\\\"b"\r\nhe\r\n3 ; k = token\r\nllo\r\n0;end\r\nContent-Length: 99\r\nLocation: /ignored\r\nX: a\r\n b\r\n\r\n')
    for interim in (100, 102, 103, 199):
        case('interim-' + str(interim), f'HTTP/1.1 {interim} Hint\r\nX: yes\r\n\r\n'.encode() + ok)
    case('sixteen-interims', b'HTTP/1.1 103 Hint\r\n\r\n' * 16 + ok)
    # Every byte boundary, including CR/LF, status, field, chunk size and payload.
    split = b'HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n'
    case('one-byte-delivery', handler=wire(None, pieces=[bytes([x]) for x in split], delay=.001))
    for code in (204, 205): case('empty-' + str(code), f'HTTP/1.1 {code} Empty\r\n\r\n'.encode(), body=b'')
    case('not-modified', b'HTTP/1.1 304 Cached\r\nContent-Length: 99\r\nTransfer-Encoding: gzip\r\n\r\n', status=8, body=b'')
    case('http-error', b'HTTP/1.1 404 Missing\r\nContent-Length: 5\r\n\r\nhello', status=8, body=b'')
    malformed = {
        'te-cl': b'Transfer-Encoding: chunked\r\nContent-Length: 0',
        'cl-te': b'Content-Length: 0\r\nTransfer-Encoding: chunked',
        'different-length': b'Content-Length: 5, 6',
        'different-repeat': b'Content-Length: 5\r\nContent-Length: 6',
        'length-plus': b'Content-Length: +5', 'length-negative': b'Content-Length: -1',
        'length-overflow': b'Content-Length: 9223372036854775808',
        'length-empty': b'Content-Length:', 'length-comma': b'Content-Length: 5,',
        'length-junk': b'Content-Length: 5x', 'gzip': b'Transfer-Encoding: gzip',
        'gzip-chunked': b'Transfer-Encoding: gzip, chunked',
        'chunked-gzip': b'Transfer-Encoding: chunked, gzip',
        'double-chunked': b'Transfer-Encoding: chunked, chunked',
        'repeat-te': b'Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked',
        'te-param': b'Transfer-Encoding: chunked; key=value',
        'te-bad-param': b'Transfer-Encoding: chunked; key=',
        'te-empty': b'Transfer-Encoding:', 'te-trailing': b'Transfer-Encoding: chunked,',
        'content-gzip': b'Content-Encoding: gzip', 'content-empty': b'Content-Encoding:',
        'field-space': b'Content-Length : 5', 'field-no-colon': b'Wrong',
        'field-control': b'X: a\x00b', 'field-del': b'X: a\x7fb', 'field-empty-name': b': value',
    }
    for name, fields in malformed.items():
        case(name, b'HTTP/1.1 200 OK\r\n' + fields + b'\r\n\r\n', status=74, body=b'')
    for name, data in {
        'status-version': b'HTTP/2.0 200 OK\r\n\r\n',
        'status-case': b'http/1.1 200 OK\r\n\r\n',
        'status-code': b'HTTP/1.1 20X OK\r\n\r\n',
        'status-range': b'HTTP/1.1 600 Bad\r\n\r\n',
        'bare-lf': b'HTTP/1.1 200 OK\n\n', 'bad-crlf': b'HTTP/1.1 200 OK\rX',
        'eof-head': b'HTTP/1.1 200 OK\r\nX: incomplete',
        'eof-interim': b'HTTP/1.1 103 Hint\r\n\r\n',
        'upgrade': b'HTTP/1.1 101 Switch\r\n\r\n',
        'seventeen-interims': b'HTTP/1.1 103 Hint\r\n\r\n' * 17 + ok,
        '204-length': b'HTTP/1.1 204 Empty\r\nContent-Length: 0\r\n\r\n',
        '204-te': b'HTTP/1.1 204 Empty\r\nTransfer-Encoding: chunked\r\n\r\n',
        '100-length': b'HTTP/1.1 100 Hint\r\nContent-Length: 0\r\n\r\n' + ok,
        '103-te': b'HTTP/1.1 103 Hint\r\nTransfer-Encoding: chunked\r\n\r\n' + ok,
        'http10-te': b'HTTP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n',
        'head-over-limit': b'HTTP/1.1 200 OK\r\nX: ' + b'a' * 65536 + b'\r\n\r\n',
        'aggregate-head-limit': (b'HTTP/1.1 103 Hint\r\nX: ' + b'a' * 32750 + b'\r\n\r\n') * 2 + ok,
    }.items(): case(name, data, status=74, body=b'')
    for name, chunk in {
        'chunk-overflow': b'8000000000000000\r\n', 'chunk-sign': b'+1\r\n',
        'chunk-empty': b'\r\n', 'chunk-junk': b'1x\r\n',
        'chunk-trailing-space': b'1;flag \r\n',
        'chunk-open-quote': b'1;x="abc\r\n', 'chunk-empty-extension': b'1;\r\n',
        'chunk-missing-value': b'1;x=\r\n', 'chunk-limit': b'1;x=' + b'a' * 8192 + b'\r\n',
        'chunk-eof-data': b'5\r\nhe', 'chunk-eof-delimiter': b'2\r\nhe\r',
        'chunk-bad-delimiter': b'2\r\nheXY', 'chunk-missing-zero': b'2\r\nhe\r\n',
        'chunk-eof-trailer': b'0\r\nX: a\r\n', 'chunk-bad-trailer': b'0\r\nBad\r\n\r\n',
        'trailer-limit': b'0\r\nX: ' + b'a' * 65536 + b'\r\n\r\n',
    }.items(): case(name, b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n' + chunk, status=74, body=None)
    case('short-length', b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhe', status=74, body=b'he')
    case('205-content', b'HTTP/1.1 205 Reset\r\nContent-Length: 5\r\n\r\nhello', status=74, body=b'')
    case('head-timeout', handler=wire(b'HTTP/1.1 200', hold=.3), options=('--head-timeout', '80'), status=74, body=b'')
    case('interim-deadline', handler=wire(None, pieces=[b'HTTP/1.1 103 Hint\r\n\r\n'] * 5 + [ok], delay=.05),
         options=('--head-timeout', '120'), status=74, body=b'')
    case('body-timeout', handler=wire(b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhe', hold=.3),
         options=('--read-timeout', '80'), status=74, body=b'he')
    case('eof-timeout', handler=wire(b'HTTP/1.1 200 OK\r\n\r\nhe', hold=.3),
         options=('--read-timeout', '80'), status=74, body=b'he')
    case('reset-not-eof', handler=wire(b'HTTP/1.1 200 OK\r\n\r\nhe', reset=True), status=74, body=None)
    binary = bytes(range(256)) * 16384
    case('large-binary', b'HTTP/1.1 200 OK\r\nContent-Length: 4194304\r\n\r\n' + binary, body=binary)
    case('many-chunks', b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n' + b'1\r\nx\r\n' * 32768 + b'0\r\n\r\n', body=b'x' * 32768)
    prefix = b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX:'
    case('exact-head-limit', prefix + b'a' * (65536 - len(prefix) - 4) + b'\r\n\r\nhello')
    case('exact-chunk-trailer-limits', b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n'
         + b'1;x=' + b'a' * 8186 + b'\r\nx\r\n0\r\nX: ' + b'a' * 65529 + b'\r\n\r\n', body=b'x')


def redirects_and_tls():
    ok = b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello'
    for code in (301, 302, 303, 307, 308):
        def redirect(peer, request, index, server, code=code):
            if index == 0: peer.sendall(f'HTTP/1.1 {code} Redirect\r\nLocation: ../final?x=%23#fragment\r\nContent-Length: 7\r\n\r\nignored'.encode())
            else:
                assert request.startswith(b'GET /final?x=%23 HTTP/1.1\r\n')
                peer.sendall(ok)
        case('redirect-' + str(code), handler=redirect, path='/dir/start', connections=2)
    def chain(peer, request, index, server):
        if index < 20: peer.sendall(b'HTTP/1.1 302 Redirect\r\nLocation:\r\n\r\n')
        else: peer.sendall(ok)
    case('twenty-redirects', handler=chain, connections=21)
    case('redirect-loop', b'HTTP/1.1 302 Loop\r\nLocation: #same\r\n\r\n', status=74, body=b'', connections=21)
    for name, location in {'missing': b'', 'duplicate': b'Location: /a\r\nLocation: /b\r\n',
                           'bad-url': b'Location: ftp://invalid/\r\n', 'userinfo': b'Location: http://u:p@invalid/\r\n'}.items():
        case('redirect-' + name, b'HTTP/1.1 302 Redirect\r\n' + location + b'\r\n', status=74, body=b'')
    case('https', ok, tls=True)
    case('https-dns', ok, tls=True, host='localhost')
    case('https-ipv6', ok, tls=True, ipv6=True)
    case('https-wrong-root', ok, tls=True, trust='other.pem', status=74, body=b'')
    case('https-wrong-identity', ok, tls=True, host='wrong.invalid', proxy='http', status=74, body=b'')
    case('https-close-delimited', handler=wire(b'HTTP/1.1 200 OK\r\n\r\nhello', clean_tls=True), tls=True)
    case('https-truncation', b'HTTP/1.1 200 OK\r\n\r\nhello', tls=True, status=74, body=b'hello')
    def same_host(peer, request, index, server):
        if index < 3: peer.sendall(b'HTTP/1.1 302 Redirect\r\nLocation: /next\r\n\r\n')
        else: peer.sendall(ok)
    case('https-fresh-handshakes', handler=same_host, tls=True, host='localhost', connections=4)
    for proxy in ('http', 'socks5', 'socks4'):
        case('proxy-' + proxy, ok, proxy=proxy, host='origin.invalid' if proxy != 'socks4' else None)
        case('https-proxy-' + proxy, ok, proxy=proxy, tls=True, host='localhost' if proxy != 'socks4' else None)
    case('connect-407-no-retry', ok, proxy='http', reject_proxy=True, status=74, body=b'')
    target = Server(wire(ok), tls=True)
    try:
        location = f'https://localhost:{target.port}/final'.encode()
        case('upgrade', b'HTTP/1.1 302 Upgrade\r\nLocation: ' + location + b'\r\n\r\n',
             options=('--ca-bundle', CERTS / 'trusted.pem'))
        assert target.connections == 1
    finally: target.close()
    target = Server(wire(ok))
    try:
        case('downgrade', f'HTTP/1.1 302 Down\r\nLocation: http://127.0.0.1:{target.port}/\r\n\r\n'.encode(),
             tls=True, status=74, body=b'')
        assert target.connections == 0
    finally: target.close()
    target = Server(wire(ok))
    upgraded = Server(wire(f'HTTP/1.1 302 Down\r\nLocation: http://127.0.0.1:{target.port}/\r\n\r\n'.encode()), tls=True)
    try:
        case('downgrade-after-upgrade',
             f'HTTP/1.1 302 Up\r\nLocation: https://localhost:{upgraded.port}/\r\n\r\n'.encode(),
             options=('--ca-bundle', CERTS / 'trusted.pem'), status=74, body=b'')
        assert upgraded.connections == 1 and target.connections == 0
    finally:
        upgraded.close()
        target.close()
    def new_identity(peer, request, index, server):
        peer.sendall(f'HTTP/1.1 302 Other\r\nLocation: https://wrong.invalid:{server.port}/\r\n\r\n'.encode())
    _, server = case('redirect-new-identity', handler=new_identity, tls=True, proxy='http', host='localhost', status=74, body=b'', connections=2)
    assert server.sni == ['localhost', 'wrong.invalid']


def files_and_invalid():
    ok = b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello'
    target = OUT / 'download.bin'
    case('output-file', ok, body=b'', options=('-O', target))
    assert target.read_bytes() == b'hello'
    target.write_bytes(b'original')
    case('output-not-opened-for-error', b'HTTP/1.1 404 Missing\r\n\r\n', status=8, body=b'', options=('-O', target))
    assert target.read_bytes() == b'original'
    case('output-open-error', ok, status=74, body=b'', options=('-O', OUT / 'absent/file'))
    if Path('/dev/full').exists(): case('output-write-error', ok, status=74, body=b'', options=('-O', '/dev/full'))
    # Invoke the real wrapper from a directory containing spaces; relative
    # output paths belong to that caller, never the project's build directory.
    caller = OUT / 'caller directory'; caller.mkdir(exist_ok=True)
    server = Server(wire(ok))
    try:
        run('caller-cwd', [PROJECT / 'run.sh', '-O', 'relative file.bin', f'http://127.0.0.1:{server.port}/'], cwd=caller)
        assert (caller / 'relative file.bin').read_bytes() == b'hello'
    finally: server.close()
    server = Server(wire(ok))
    try:
        reader, writer = os.pipe(); os.close(reader)
        try:
            result = subprocess.run([str(BINARY), f'http://127.0.0.1:{server.port}/'], stdout=writer,
                                    stderr=subprocess.PIPE, timeout=10)
            assert result.returncode != 0, 'closed stdout pipe reported success'
        finally: os.close(writer)
        (OUT / 'stdout-closed.log').write_bytes(str(result.returncode).encode() + b'\n' + result.stderr)
    finally: server.close()
    for index, args in enumerate(([], ['--unknown', 'x'], ['--read-timeout', '-1', 'http://localhost/'],
            ['http://u:p@localhost/'], ['http://localhost/a b'], ['http://localhost:99999/'],
            ['--proxy-password-file', 'x', 'http://localhost/'],
            ['--proxy', 'http://localhost:80', '--proxy-user-file', 'x', 'http://localhost/'])):
        run('invalid-cli-' + str(index), [BINARY, *args], 64)


def allocation_and_code():
    llvm = Path(os.environ['IRONWOOD_LLVM_HOME'])
    diagnostic = OUT / ('calls.dylib' if platform.system() == 'Darwin' else 'calls.so')
    run('build-diagnostic', [llvm / 'bin/clang', '-O2', '-shared', '-fPIC', ROOT / 'integration-tests/native/tls_interpose.c',
        '-o', diagnostic, *([] if platform.system() == 'Darwin' else ['-ldl'])])
    env = os.environ.copy()
    env['DYLD_INSERT_LIBRARIES' if platform.system() == 'Darwin' else 'LD_PRELOAD'] = str(diagnostic)
    for label, payload, count in (
        ('small', b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello', 5),
        ('large', b'HTTP/1.1 200 OK\r\nContent-Length: 4194304\r\n\r\n' + bytes(range(256)) * 16384, 4194304),
        ('chunks', b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n' + b'1;x="ignored"\r\nx\r\n' * 32768 + b'0\r\nX: done\r\n\r\n', 32768),
    ):
        path = OUT / (label + '.wire'); path.write_bytes(payload)
        for traced in (False, True):
            result = run('reader-' + label + ('-traced' if traced else ''), [PROJECT / 'target/ResponseProbe', path], env=env if traced else None)
            assert result.stdout.count(f'BODY {count}\n'.encode()) == 4
            if traced:
                counts = re.findall(rb'TLS_COUNT steady .*alloc=(\d+)', result.stderr)
                assert counts == [b'0'] * 4, result.stderr
    ok = b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello'
    for tls in (False, True):
        result, _ = case('cycles-' + str(tls), ok, tls=tls, binary=PROJECT / 'target/LifecycleProbe',
            body=None, connections=4, options=('-O', '/dev/null'), env=env)
        assert b'CYCLES_CLEAN' in result.stdout
        heaps = list(map(int, re.findall(rb'TLS_HEAP retained=(-?\d+)', result.stderr)))
        assert len(heaps) == 4 and heaps[1:] == [0, 0, 0], result.stderr
        def redirect_cycle(peer, request, index, server):
            if index % 3 != 2: peer.sendall(b'HTTP/1.1 302 Next\r\nLocation: /next\r\n\r\n')
            else: peer.sendall(ok)
        result, _ = case('redirect-cycles-' + str(tls), handler=redirect_cycle, tls=tls,
            binary=PROJECT / 'target/LifecycleProbe', body=None, connections=12, options=('-O', '/dev/null'), env=env, low_fds=True)
        heaps = list(map(int, re.findall(rb'TLS_HEAP retained=(-?\d+)', result.stderr)))
        assert b'CYCLES_CLEAN' in result.stdout and heaps[1:] == [0, 0, 0], result.stderr
        for label in ('large', 'chunks'):
            payload = (OUT / (label + '.wire')).read_bytes()
            for timed in (False, True):
                for traced in (False, True):
                    result, _ = case(f'network-{tls}-{label}-{timed}-{traced}', payload, tls=tls,
                        binary=PROJECT / 'target/TransferProbe', body=None,
                        options=('--read-timeout', '500' if timed else '0'), env=env if traced else None)
                    if traced:
                        counts = re.findall(rb'TLS_COUNT steady .*alloc=(\d+)', result.stderr)
                        # Pinned OpenSSL TLS 1.3 allocates a WPACKET subpacket
                        # per decrypted record (D165 / M5 evidence). The head
                        # already decrypted the first record. HTTP adds none.
                        expected = (len(payload) + 16383) // 16384 - 1 if tls else 0
                        assert counts == [str(expected).encode()], result.stderr
    run('disassembly', [llvm / 'bin/llvm-objdump', '--disassemble', '--no-show-raw-insn', BINARY])
    run('reader-disassembly', [llvm / 'bin/llvm-objdump', '--disassemble', '--no-show-raw-insn', PROJECT / 'target/ResponseProbe'])


def cleanup():
    diagnostic = OUT / ('calls.dylib' if platform.system() == 'Darwin' else 'calls.so')
    llvm = Path(os.environ['IRONWOOD_LLVM_HOME'])
    run('build-diagnostic', [llvm / 'bin/clang', '-O2', '-shared', '-fPIC', ROOT / 'integration-tests/native/tls_interpose.c',
        '-o', diagnostic, *([] if platform.system() == 'Darwin' else ['-ldl'])])
    env = os.environ.copy()
    env['DYLD_INSERT_LIBRARIES' if platform.system() == 'Darwin' else 'LD_PRELOAD'] = str(diagnostic)
    guarded = dict(env, IRONWOOD_TEST_FORBID_DNS='1', IRONWOOD_TEST_FORBID_CONNECT='1')
    run('url-no-dns-or-connect', [PROJECT / 'target/UrlTests'], env=guarded)
    for index, url in enumerate(('http://u:p@not-a-host.invalid/', 'http://not-a-host.invalid:99999/',
                                 'ftp://not-a-host.invalid/', 'http://not-a-host.invalid/%gg')):
        run('invalid-before-dns-' + str(index), [BINARY, url], 64, env=guarded)
    ok = b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello'
    for fault in ('socket-close', 'output-close'):
        fault_env = dict(env, IRONWOOD_TEST_WGET_FAULT=fault)
        case('cleanup-' + fault, ok, options=('-O', '/dev/null'), body=b'', status=74, env=fault_env)
        result, _ = case('primary-before-' + fault, b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhe',
             options=('-O', '/dev/null'), body=b'', status=74, env=fault_env)
        assert b'Premature EOF' in result.stderr
    for tls, proxy in ((False, None), (True, None), (True, 'http')):
        for limit in range(500):
            # One redirect exercises response-owned Location, copied next URL,
            # closed previous transport and a second complete connection graph.
            def redirect(peer, request, index, server):
                if index % 2 == 0: peer.sendall(b'HTTP/1.1 302 Next\r\nLocation: /next\r\n\r\n')
                else: peer.sendall(ok)
            result, _ = case(f'oom-{tls}-{proxy}-{limit}', handler=redirect, tls=tls, proxy=proxy,
                binary=PROJECT / 'target/OomProbe', body=None, connections=None,
                options=('-O', '/dev/null'), env=dict(env, IRONWOOD_ALLOCATION_LIMIT=str(limit)))
            if b'CYCLES_CLEAN' in result.stdout:
                REPORT.setdefault('oom', {})[f'{tls}-{proxy}'] = limit
                break
            assert b'OOM_CLEAN' in result.stdout, result.stdout
        else: raise AssertionError('managed OOM sweep did not reach completion')


def main():
    global CERTS, BINARY
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--group', choices=('protocol', 'tls', 'files', 'allocation', 'cleanup'))
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    progress(f'M6 downloader tests; logs: {OUT}')
    if not args.smoke and args.group in (None, 'allocation', 'cleanup'):
        os.environ['IRONWOOD_LLVM_HOME'] = str(stage('LLVM 23 tools', llvm_home))
    if args.binary: BINARY = args.binary.resolve()
    if not args.skip_build: build()
    else: progress('Using existing executables (--skip-build).')
    CERTS = stage('local test certificates', certificates)
    (OUT / 'user').write_bytes(b'u\xc3\xa9'); (OUT / 'password').write_bytes(b'p:\xff')
    if args.smoke:
        progress('RUN - local HTTP/HTTPS smoke')
        case('http', b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello')
        case('https', b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello', tls=True)
    else:
        run('url-tests', [PROJECT / 'target/UrlTests'], announce=True)
        for name, action in (('protocol', protocol_cases), ('tls', redirects_and_tls),
                             ('files', files_and_invalid), ('allocation', allocation_and_code), ('cleanup', cleanup)):
            if not args.group or args.group == name: stage(name, action)
    (OUT / 'report.json').write_text(json.dumps(REPORT, indent=2) + '\n')
    print(f'PASS: M6 {len(REPORT["cases"])} local downloader cases')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        progress('Interrupted; active command stopped. Rerun without --skip-build if compilation was interrupted.')
        sys.exit(130)
    except TimeoutError as error:
        progress('FAIL - ' + str(error))
        sys.exit(1)
